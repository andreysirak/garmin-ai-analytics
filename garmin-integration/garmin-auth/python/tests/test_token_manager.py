import base64
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from kubernetes import client
from kubernetes.client.exceptions import ApiException

from garmin_auth.token_manager import (
    AuthSettings,
    BearerToken,
    GarminAuthError,
    TokenManager,
    _jwt_expiration,
    request_garmin_token,
)


def make_jwt(expires_at: float) -> str:
    payload = base64.urlsafe_b64encode(
        json.dumps({"exp": expires_at}).encode()
    ).decode().rstrip("=")
    return f"header.{payload}.signature"


def encoded(value: str) -> str:
    return base64.b64encode(value.encode()).decode()


class FakeSecretApi:
    def __init__(self) -> None:
        self.secret: client.V1Secret | None = None
        self.create_count = 0
        self.replace_count = 0
        self.read_error: int | None = None
        self.write_error: int | None = None

    def read_namespaced_secret(self, **_kwargs: object) -> client.V1Secret:
        if self.read_error is not None:
            raise ApiException(status=self.read_error)
        if self.secret is None:
            raise ApiException(status=404)
        return self.secret

    def create_namespaced_secret(
        self,
        *,
        body: client.V1Secret,
        **_kwargs: object,
    ) -> client.V1Secret:
        if self.write_error is not None:
            raise ApiException(status=self.write_error)
        if self.secret is not None:
            raise ApiException(status=409)
        body.metadata.resource_version = "1"
        self.secret = body
        self.create_count += 1
        return body

    def replace_namespaced_secret(
        self,
        *,
        body: client.V1Secret,
        **_kwargs: object,
    ) -> client.V1Secret:
        if self.write_error is not None:
            raise ApiException(status=self.write_error)
        if self.secret is None or body.metadata.resource_version != self.secret.metadata.resource_version:
            raise ApiException(status=409)
        body.metadata.resource_version = str(int(body.metadata.resource_version) + 1)
        self.secret = body
        self.replace_count += 1
        return body


@pytest.fixture
def settings() -> AuthSettings:
    return AuthSettings(
        api_key="synthetic-api-key",
        auth_url="https://personal.connectapi.garmin.com/token",
        safety_margin_seconds=1800,
    )


@pytest.fixture
def fake_secret_api() -> FakeSecretApi:
    return FakeSecretApi()


def cached_secret(token: str, expires_at: float) -> client.V1Secret:
    return client.V1Secret(
        metadata=client.V1ObjectMeta(
            name="garmin-access-token",
            namespace="airflow-jobs",
            resource_version="1",
        ),
        data={
            "access-token": encoded(token),
            "expires-at": encoded(str(expires_at)),
            "preserved": encoded("other data"),
        },
    )


def test_settings_require_api_key_and_validate_auth_url(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("GARMIN_API_KEY", raising=False)
    with pytest.raises(GarminAuthError, match="GARMIN_API_KEY"):
        AuthSettings.from_environment()

    monkeypatch.setenv("GARMIN_API_KEY", "synthetic-api-key")
    monkeypatch.setenv("GARMIN_AUTH_URL", "http://non-loopback.example/token")
    with pytest.raises(GarminAuthError, match="HTTPS"):
        AuthSettings.from_environment()


def test_settings_reject_nonpositive_safety_margin(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GARMIN_API_KEY", "synthetic-api-key")
    monkeypatch.setenv("GARMIN_TOKEN_SAFETY_MARGIN_SECONDS", "0")

    with pytest.raises(GarminAuthError, match="positive integer"):
        AuthSettings.from_environment()


def test_valid_cached_token_is_reused_without_authentication(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    access_token = make_jwt(time.time() + 7200)
    fake_secret_api.secret = cached_secret(access_token, time.time() + 7200)

    def authenticate(*_args: object) -> BearerToken:
        pytest.fail("valid cached token must be reused")

    result = TokenManager(settings, fake_secret_api, authenticate=authenticate).ensure_token()

    assert result.access_token == access_token
    assert fake_secret_api.create_count == 0
    assert fake_secret_api.replace_count == 0


def test_missing_secret_creates_cached_token(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    expires_at = time.time() + 7200
    access_token = make_jwt(expires_at)
    manager = TokenManager(
        settings,
        fake_secret_api,
        authenticate=lambda *_args: BearerToken(access_token, expires_at),
    )

    result = manager.ensure_token()

    assert result.access_token == access_token
    assert fake_secret_api.create_count == 1
    assert fake_secret_api.secret.data["access-token"] == encoded(access_token)
    assert fake_secret_api.secret.data["expires-at"] == encoded(str(expires_at))


@pytest.mark.parametrize(
    "expiry_text",
    [str(time.time() - 10), "not-a-timestamp", "NaN"],
)
def test_expired_or_malformed_cache_is_refreshed(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
    expiry_text: str,
) -> None:
    fake_secret_api.secret = cached_secret(make_jwt(time.time() + 7200), time.time() + 7200)
    fake_secret_api.secret.data["expires-at"] = encoded(expiry_text)
    expires_at = time.time() + 7200
    refreshed = make_jwt(expires_at)

    def authenticate(*_args: object) -> BearerToken:
        return BearerToken(refreshed, expires_at)

    result = TokenManager(
        settings,
        fake_secret_api,
        authenticate=authenticate,
    ).ensure_token()

    assert result.access_token == refreshed
    assert fake_secret_api.replace_count == 1
    assert fake_secret_api.secret.data["preserved"] == encoded("other data")


def test_token_expiring_inside_safety_margin_is_refreshed(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    fake_secret_api.secret = cached_secret(make_jwt(time.time() + 60), time.time() + 60)
    refreshed_expiry = time.time() + 7200
    refreshed_token = make_jwt(refreshed_expiry)

    result = TokenManager(
        settings,
        fake_secret_api,
        authenticate=lambda *_args: BearerToken(refreshed_token, refreshed_expiry),
    ).ensure_token()

    assert result.access_token == refreshed_token
    assert fake_secret_api.replace_count == 1


def test_rejects_new_token_that_is_expired_or_near_expiry(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    expires_at = time.time() + 60
    with pytest.raises(GarminAuthError, match="safety margin"):
        TokenManager(
            settings,
            fake_secret_api,
            authenticate=lambda *_args: BearerToken(make_jwt(expires_at), expires_at),
        ).ensure_token()
    assert fake_secret_api.create_count == 0


def test_concurrent_refresh_uses_token_written_by_other_run(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    winner_expiry = time.time() + 7200
    winner_token = make_jwt(winner_expiry)

    def authenticate(*_args: object) -> BearerToken:
        fake_secret_api.create_namespaced_secret(
            namespace="airflow-jobs",
            body=cached_secret(winner_token, winner_expiry),
        )
        return BearerToken(make_jwt(winner_expiry + 1), winner_expiry + 1)

    result = TokenManager(
        settings,
        fake_secret_api,
        authenticate=authenticate,
    ).ensure_token()

    assert result.access_token == winner_token
    assert fake_secret_api.create_count == 1


def test_kubernetes_read_and_write_errors_are_reported_without_secret_data(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
) -> None:
    fake_secret_api.read_error = 403
    with pytest.raises(GarminAuthError, match="HTTP 403"):
        TokenManager(settings, fake_secret_api).ensure_token()

    fake_secret_api.read_error = None
    fake_secret_api.write_error = 403
    expires_at = time.time() + 7200
    token = make_jwt(expires_at)
    with pytest.raises(GarminAuthError, match="HTTP 403") as error:
        TokenManager(
            settings,
            fake_secret_api,
            authenticate=lambda *_args: BearerToken(token, expires_at),
        ).ensure_token()
    assert token not in str(error.value)
    assert settings.api_key not in str(error.value)


def test_token_and_api_key_are_not_represented_or_logged(
    settings: AuthSettings,
    fake_secret_api: FakeSecretApi,
    caplog: pytest.LogCaptureFixture,
) -> None:
    expires_at = time.time() + 7200
    token_value = make_jwt(expires_at)
    manager = TokenManager(
        settings,
        fake_secret_api,
        authenticate=lambda *_args: BearerToken(token_value, expires_at),
    )

    manager.ensure_token()

    assert token_value not in repr(BearerToken(token_value, expires_at))
    assert settings.api_key not in repr(settings)
    assert token_value not in caplog.text
    assert settings.api_key not in caplog.text


def test_jwt_expiry_requires_numeric_finite_exp() -> None:
    assert _jwt_expiration(make_jwt(1234567890)) == 1234567890
    for payload in ({}, {"exp": "later"}, {"exp": float("inf")}):
        segment = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
        with pytest.raises(GarminAuthError):
            _jwt_expiration(f"header.{segment}.signature")


@pytest.fixture
def auth_server() -> tuple[ThreadingHTTPServer, str]:
    state: dict[str, object] = {"status": 200, "body": "", "requests": []}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            state["requests"].append((self.path, self.headers.get("Authorization")))
            if state.get("delay"):
                time.sleep(state["delay"])
            if self.path == "/redirect":
                self.send_response(302)
                self.send_header("Location", "/redirect-target")
                self.end_headers()
                return
            self.send_response(state["status"])
            self.end_headers()
            self.wfile.write(state["body"])

        def log_message(self, *_args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    server.state = state
    try:
        yield server, f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        thread.join(timeout=2)
        server.server_close()


def test_authentication_uses_personal_api_key_header_and_jwt_expiry(
    auth_server: tuple[ThreadingHTTPServer, str],
) -> None:
    server, base_url = auth_server
    server.state["body"] = make_jwt(time.time() + 7200).encode()

    result = request_garmin_token("synthetic-api-key", f"{base_url}/token", 3)

    assert result.expires_at > time.time() + 1800
    assert server.state["requests"] == [("/token", "ApiKey synthetic-api-key")]


@pytest.mark.parametrize(
    ("path", "status", "body"),
    [
        ("/unauthorized", 401, b"private Garmin error"),
        ("/invalid-jwt", 200, b"not-a-jwt"),
        ("/invalid-utf8", 200, b"\xff"),
        ("/redirect", 200, b""),
    ],
)
def test_authentication_errors_are_sanitized(
    auth_server: tuple[ThreadingHTTPServer, str],
    path: str,
    status: int,
    body: bytes,
) -> None:
    server, base_url = auth_server
    server.state["status"] = status
    server.state["body"] = body

    with pytest.raises(GarminAuthError) as error:
        request_garmin_token("synthetic-api-key", f"{base_url}{path}", 3)

    assert "synthetic-api-key" not in str(error.value)
    assert "private Garmin error" not in str(error.value)
    assert not any(request[0] == "/redirect-target" for request in server.state["requests"])


def test_authentication_http_timeout_is_reported(
    auth_server: tuple[ThreadingHTTPServer, str],
) -> None:
    server, base_url = auth_server
    server.state["body"] = make_jwt(time.time() + 7200).encode()
    server.state["delay"] = 0.2

    with pytest.raises(GarminAuthError, match="request failed"):
        request_garmin_token("synthetic-api-key", f"{base_url}/token", 0.05)


def test_authentication_error_logs_do_not_include_credentials(
    auth_server: tuple[ThreadingHTTPServer, str],
    caplog: pytest.LogCaptureFixture,
) -> None:
    server, base_url = auth_server
    server.state["status"] = 401
    server.state["body"] = b"private error"

    with pytest.raises(GarminAuthError):
        request_garmin_token("synthetic-api-key", f"{base_url}/token", 3)

    assert "synthetic-api-key" not in caplog.text
    assert "private error" not in caplog.text
