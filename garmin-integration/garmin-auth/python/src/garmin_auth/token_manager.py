"""Acquire and cache a Garmin Personal API bearer token in Kubernetes."""

from __future__ import annotations

import argparse
import base64
import json
import logging
import math
import os
import time
from dataclasses import dataclass, field
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from kubernetes import client, config
from kubernetes.client.exceptions import ApiException

DEFAULT_AUTH_URL = (
    "https://personal.connectapi.garmin.com/"
    "personalapitoken-service/personal-api/personal-api-key/token"
)
DEFAULT_SECRET_NAME = "garmin-access-token"
DEFAULT_SAFETY_MARGIN_SECONDS = 30 * 60
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30
TOKEN_KEY = "access-token"
EXPIRY_KEY = "expires-at"
LOGGER = logging.getLogger(__name__)


class _RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class GarminAuthError(Exception):
    """Raised when a usable Garmin token cannot be obtained or cached."""


@dataclass(frozen=True)
class AuthSettings:
    api_key: str = field(repr=False)
    auth_url: str = DEFAULT_AUTH_URL
    secret_name: str = DEFAULT_SECRET_NAME
    namespace: str = "airflow-jobs"
    safety_margin_seconds: int = DEFAULT_SAFETY_MARGIN_SECONDS
    request_timeout_seconds: int = DEFAULT_REQUEST_TIMEOUT_SECONDS

    @classmethod
    def from_environment(cls) -> AuthSettings:
        api_key = os.environ.get("GARMIN_API_KEY", "").strip()
        if not api_key:
            raise GarminAuthError("GARMIN_API_KEY is required.")

        auth_url = os.environ.get("GARMIN_AUTH_URL", DEFAULT_AUTH_URL).strip()
        validate_auth_url(auth_url)

        secret_name = os.environ.get("GARMIN_TOKEN_SECRET_NAME", DEFAULT_SECRET_NAME).strip()
        namespace = os.environ.get(
            "GARMIN_TOKEN_SECRET_NAMESPACE",
            os.environ.get("POD_NAMESPACE", "airflow-jobs"),
        ).strip()
        if not secret_name or not namespace:
            raise GarminAuthError("The token Secret name and namespace must not be empty.")

        safety_margin = _positive_int_environment(
            "GARMIN_TOKEN_SAFETY_MARGIN_SECONDS", DEFAULT_SAFETY_MARGIN_SECONDS
        )
        timeout = _positive_int_environment(
            "GARMIN_AUTH_TIMEOUT_SECONDS", DEFAULT_REQUEST_TIMEOUT_SECONDS
        )
        return cls(
            api_key=api_key,
            auth_url=auth_url,
            secret_name=secret_name,
            namespace=namespace,
            safety_margin_seconds=safety_margin,
            request_timeout_seconds=timeout,
        )


@dataclass(frozen=True)
class BearerToken:
    access_token: str = field(repr=False)
    expires_at: float


class TokenManager:
    def __init__(
        self,
        settings: AuthSettings,
        secret_api: client.CoreV1Api,
        *,
        clock: Callable[[], float] = time.time,
        authenticate: Callable[[str, str, int], BearerToken] | None = None,
    ) -> None:
        self._settings = settings
        self._secret_api = secret_api
        self._clock = clock
        self._authenticate = authenticate or request_garmin_token

    def ensure_token(self) -> BearerToken:
        secret = self._read_secret()
        cached_token = self._usable_cached_token(secret)
        if cached_token is not None:
            LOGGER.info("Using the cached Garmin bearer token.")
            return cached_token

        token = self._authenticate(
            self._settings.api_key,
            self._settings.auth_url,
            self._settings.request_timeout_seconds,
        )
        self._validate_new_token(token)

        latest_secret = self._read_secret()
        concurrent_token = self._usable_cached_token(latest_secret)
        if concurrent_token is not None:
            LOGGER.info("A concurrent run refreshed the Garmin bearer token.")
            return concurrent_token

        try:
            self._write_secret(latest_secret, token)
        except ApiException as exc:
            if exc.status == 409:
                concurrent_secret = self._read_secret()
                concurrent_token = self._usable_cached_token(concurrent_secret)
                if concurrent_token is not None:
                    LOGGER.info("A concurrent run refreshed the Garmin bearer token.")
                    return concurrent_token
                raise GarminAuthError(
                    "The token Secret changed concurrently and has no usable token."
                ) from None
            raise GarminAuthError(
                f"Unable to write the Garmin token Secret (HTTP {exc.status or 'unknown'})."
            ) from None

        LOGGER.info("Obtained and cached a Garmin bearer token.")
        return token

    def _read_secret(self) -> client.V1Secret | None:
        try:
            return self._secret_api.read_namespaced_secret(
                name=self._settings.secret_name,
                namespace=self._settings.namespace,
            )
        except ApiException as exc:
            if exc.status == 404:
                return None
            raise GarminAuthError(
                f"Unable to read the Garmin token Secret (HTTP {exc.status or 'unknown'})."
            ) from None

    def _usable_cached_token(self, secret: client.V1Secret | None) -> BearerToken | None:
        if secret is None or not secret.data:
            return None
        access_token = _decode_secret_value(secret.data.get(TOKEN_KEY))
        expires_at_text = _decode_secret_value(secret.data.get(EXPIRY_KEY))
        if access_token is None or expires_at_text is None:
            return None
        try:
            expires_at = float(expires_at_text)
        except ValueError:
            return None
        if not math.isfinite(expires_at):
            return None
        token = BearerToken(access_token=access_token, expires_at=expires_at)
        if expires_at - self._clock() <= self._settings.safety_margin_seconds:
            return None
        return token

    def _validate_new_token(self, token: BearerToken) -> None:
        if not token.access_token or not math.isfinite(token.expires_at):
            raise GarminAuthError("Garmin returned an invalid bearer token.")
        if token.expires_at - self._clock() <= self._settings.safety_margin_seconds:
            raise GarminAuthError(
                "Garmin returned a bearer token that expires within the safety margin."
            )

    def _write_secret(
        self,
        secret: client.V1Secret | None,
        token: BearerToken,
    ) -> None:
        if secret is None:
            is_new = True
            secret = client.V1Secret(
                api_version="v1",
                kind="Secret",
                metadata=client.V1ObjectMeta(
                    name=self._settings.secret_name,
                    namespace=self._settings.namespace,
                ),
                type="Opaque",
                data={},
            )
        else:
            is_new = False

        if secret.data is None:
            secret.data = {}
        secret.data[TOKEN_KEY] = _encode_secret_value(token.access_token)
        secret.data[EXPIRY_KEY] = _encode_secret_value(str(token.expires_at))

        if is_new:
            self._secret_api.create_namespaced_secret(
                namespace=self._settings.namespace,
                body=secret,
            )
            return

        if secret.metadata is None or not secret.metadata.resource_version:
            raise GarminAuthError("The existing token Secret has no resource version.")
        self._secret_api.replace_namespaced_secret(
            name=self._settings.secret_name,
            namespace=self._settings.namespace,
            body=secret,
        )


def request_garmin_token(api_key: str, auth_url: str, timeout_seconds: int) -> BearerToken:
    request = Request(
        auth_url,
        headers={
            "Accept": "*/*",
            "Authorization": f"ApiKey {api_key}",
        },
        method="POST",
    )
    try:
        with build_opener(_RejectRedirects).open(
            request,
            timeout=timeout_seconds,
        ) as response:
            if not 200 <= response.status < 300:
                raise GarminAuthError(
                    f"Garmin authentication failed (HTTP {response.status})."
                )
            try:
                access_token = response.read().decode("utf-8").strip()
            except UnicodeDecodeError:
                raise GarminAuthError("Garmin returned an invalid token response.") from None
    except HTTPError as exc:
        raise GarminAuthError(
            f"Garmin authentication failed (HTTP {exc.code})."
        ) from None
    except (TimeoutError, URLError, OSError, ValueError):
        raise GarminAuthError("Garmin authentication request failed.") from None

    expires_at = _jwt_expiration(access_token)
    return BearerToken(access_token=access_token, expires_at=expires_at)


def _jwt_expiration(access_token: str) -> float:
    segments = access_token.split(".")
    if len(segments) != 3 or not all(segments):
        raise GarminAuthError("Garmin did not return a JWT bearer token.")
    try:
        payload_bytes = base64.b64decode(
            segments[1] + "=" * (-len(segments[1]) % 4),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(payload_bytes)
        expires_at = payload["exp"]
        if isinstance(expires_at, bool) or not isinstance(expires_at, (int, float)):
            raise ValueError
        expires_at = float(expires_at)
        if not math.isfinite(expires_at):
            raise ValueError
        return expires_at
    except (
        ValueError,
        TypeError,
        KeyError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        OverflowError,
    ):
        raise GarminAuthError("Garmin token JWT does not contain a valid exp claim.") from None


def validate_auth_url(auth_url: str) -> None:
    try:
        parsed_url = urlsplit(auth_url)
        parsed_url.port
    except ValueError:
        raise GarminAuthError("GARMIN_AUTH_URL is invalid.") from None
    is_loopback_http = parsed_url.scheme == "http" and parsed_url.hostname in {
        "localhost",
        "127.0.0.1",
        "::1",
    }
    if (
        not parsed_url.hostname
        or parsed_url.username is not None
        or parsed_url.password is not None
        or parsed_url.query
        or parsed_url.fragment
        or (parsed_url.scheme != "https" and not is_loopback_http)
    ):
        raise GarminAuthError(
            "GARMIN_AUTH_URL must be an HTTPS URL (HTTP is allowed for loopback development)."
        )


def _positive_int_environment(name: str, default: int) -> int:
    value = os.environ.get(name)
    if value is None:
        return default
    try:
        parsed = int(value)
    except ValueError:
        raise GarminAuthError(f"{name} must be a positive integer.") from None
    if parsed <= 0:
        raise GarminAuthError(f"{name} must be a positive integer.")
    return parsed


def _decode_secret_value(value: str | None) -> str | None:
    if not value:
        return None
    try:
        decoded = base64.b64decode(value, validate=True).decode("utf-8").strip()
    except (ValueError, UnicodeDecodeError):
        return None
    return decoded or None


def _encode_secret_value(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def _load_kubernetes_config() -> None:
    try:
        config.load_incluster_config()
    except config.ConfigException:
        try:
            config.load_kube_config()
        except config.ConfigException:
            raise GarminAuthError(
                "Kubernetes configuration is unavailable; run in a Pod or configure kubeconfig."
            ) from None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Ensure a usable Garmin Personal API bearer token is cached in Kubernetes."
    )
    parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")

    try:
        settings = AuthSettings.from_environment()
        _load_kubernetes_config()
        TokenManager(settings, client.CoreV1Api()).ensure_token()
    except GarminAuthError as exc:
        LOGGER.error("%s", exc)
        return 1

    print("A usable Garmin bearer token is available in the Kubernetes Secret.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
