"""Bounded loopback-only HTTP transport independent of application internals."""

import json
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalHttp:
    def __init__(self, base, timeout=120):
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "http" or parsed.hostname not in {
            "127.0.0.1",
            "localhost",
            "::1",
        }:
            raise ValueError(
                "Use an HTTP loopback address; port-forward remote services explicitly"
            )
        if (
            parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError(
                "Endpoint must be a bare loopback origin without credentials"
            )
        if not 0 < timeout <= 600:
            raise ValueError("HTTP timeout must be between 0 and 600 seconds")
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect()
        )

    def json(self, path, payload=None):
        data = (
            None if payload is None else json.dumps(payload, allow_nan=False).encode()
        )
        request = urllib.request.Request(
            self.base + path, data=data, headers={"Content-Type": "application/json"}
        )
        with self.opener.open(request, timeout=self.timeout) as response:
            body = response.read(8 * 1024 * 1024 + 1)
            if len(body) > 8 * 1024 * 1024:
                raise ValueError("Response exceeds evaluation size limit")
            return json.loads(body)


