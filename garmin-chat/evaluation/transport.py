"""Bounded loopback-only HTTP and a test-scope production Java bridge."""

import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from suite import REPO, production_fingerprint


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


class JavaBridge:
    def __init__(self, require_stamp=True):
        backend = REPO / "garmin-chat/backend"
        dependencies = backend / "target/evaluation-classpath.txt"
        if not dependencies.is_file():
            raise ValueError("Run make chat-eval-prepare with Java 25 first")
        if require_stamp:
            stamp = backend / "target/evaluation-prepared.json"
            if (
                not stamp.is_file()
                or json.loads(stamp.read_text())["production_sha256"]
                != production_fingerprint()
            ):
                raise ValueError(
                    "Compiled evaluation sources are stale; rerun make chat-eval-prepare"
                )
        self.classpath = os.pathsep.join(
            [
                str(backend / "target/test-classes"),
                str(backend / "target/classes"),
                dependencies.read_text().strip(),
            ]
        )

    def call(self, action, items, protocol):
        request = {"action": action, "items": items, **protocol}
        java = (
            str(Path(os.environ["JAVA_HOME"]) / "bin/java")
            if os.environ.get("JAVA_HOME")
            else "java"
        )
        result = subprocess.run(
            [
                java,
                "-cp",
                self.classpath,
                "com.garmin.analytics.chat.evaluation.EvaluationBridge",
            ],
            input=json.dumps(request),
            text=True,
            capture_output=True,
            timeout=120,
        )
        if result.returncode:
            raise RuntimeError(
                "Java bridge failed; compile with Java 25 and rerun offline checks"
            )
        # Some JVMs print startup warnings before main. The protocol JSON is the last line.
        output = json.loads(result.stdout.strip().splitlines()[-1])
        if not isinstance(output, list) or len(output) != len(items):
            raise ValueError("Java bridge response count mismatch")
        return output
