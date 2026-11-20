"""Optional component-testing adapter to production Java prompts and validators.

Not imported by the standalone evaluation runner's transport or trial core.
"""

import json
import os
from pathlib import Path
import subprocess

from suite import REPO, production_fingerprint


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
