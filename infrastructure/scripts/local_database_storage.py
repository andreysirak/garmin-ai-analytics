"""Prepare and verify Mac -> Colima/Docker -> kind PostgreSQL bind mounts."""

import argparse
import json
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STORAGE_ROOT = Path(os.environ.get("GARMIN_DATABASE_STORAGE_ROOT", ROOT / "data/postgresql"))
DATABASES = {
    "airflow": {"node_path": "/mnt/postgresql-airflow", "uid": 1001},
    "garmin-analytics": {"node_path": "/mnt/postgresql-garmin-analytics", "uid": 999},
}
MARKER = ".volume-id"
PROBE_IMAGE = "busybox:1.37"


def volume_identity(directory: Path, database: str) -> str:
    """Read a prepared volume identity without creating missing directories."""
    identity = (directory / MARKER).read_text().strip()
    if not re.fullmatch(re.escape(database) + r":[0-9a-f]{32}", identity):
        raise ValueError(f"Invalid storage identity for {database}")
    return identity


def prepare(directory: Path, database: str) -> None:
    """Explicitly initialize an empty volume root; never adopt unidentified data."""
    directory.mkdir(parents=True, exist_ok=True)
    if not (directory / MARKER).exists():
        if any(directory.iterdir()):
            raise ValueError(f"{database}: unidentified nonempty directory; migrate using the guide")
        with (directory / MARKER).open("x") as marker:
            marker.write(f"{database}:{uuid.uuid4().hex}\n")
    volume_identity(directory, database)
    # Only the volume root changes. Never recursively chmod/chown database files.
    os.chown(directory, -1, os.getgid())
    os.chown(directory / MARKER, -1, os.getgid())
    directory.chmod(0o2770)
    (directory / MARKER).chmod(0o640)


def run(command: list[str]) -> str:
    """Capture command output so failures cannot expose database configuration."""
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip()


def check_host(directory: Path, database: str, group: int) -> None:
    """Check the daemon sees the same marker and can write as the database UID."""
    identity = volume_identity(directory, database)
    uid = DATABASES[database]["uid"]
    actual = run([
        "docker", "run", "--rm", "--user", f"{uid}:{uid}", "--group-add", str(group),
        "--mount", f"type=bind,source={directory.resolve()},target=/storage",
        PROBE_IMAGE, "sh", "-ec",
        'cat /storage/.volume-id; probe=$(mktemp /storage/.write-check.XXXXXX); rm "$probe"',
    ])
    if actual != identity:
        raise ValueError(f"{database}: Colima/Docker sees a different storage directory")


def check_node_mount(node: dict, directory: Path, database: str) -> None:
    """Reject absent, read-only, or incorrectly sourced kind mounts."""
    target = DATABASES[database]["node_path"]
    mounts = [mount for mount in node.get("Mounts", []) if mount.get("Destination") == target]
    if len(mounts) != 1:
        raise ValueError(f"{database}: missing kind bind mount; explicit cluster recreation required")
    mount = mounts[0]
    if (mount.get("Type") != "bind" or mount.get("RW") is not True
            or Path(mount.get("Source", "")).resolve() != directory.resolve()):
        raise ValueError(f"{database}: incorrect or read-only kind bind mount")


def check_node(cluster: str) -> None:
    """Verify both node mounts against the host identities before deployment."""
    name = f"{cluster}-control-plane"
    node = json.loads(run(["docker", "inspect", name]))[0]
    for database, settings in DATABASES.items():
        directory = STORAGE_ROOT / database
        check_node_mount(node, directory, database)
        actual = run(["docker", "exec", name, "cat", f"{settings['node_path']}/{MARKER}"])
        if actual != volume_identity(directory, database):
            raise ValueError(f"{database}: kind does not see the expected host storage")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "check-host", "check", "identity"])
    parser.add_argument("--cluster", default="garmin-ai-analytics")
    parser.add_argument("--context")
    parser.add_argument("--database", choices=DATABASES)
    args = parser.parse_args()
    if args.context is not None and args.context != f"kind-{args.cluster}":
        parser.error("The Kubernetes context must match the checked kind cluster")
    try:
        if args.action == "identity":
            if not args.database:
                parser.error("identity requires --database")
            print(volume_identity(STORAGE_ROOT / args.database, args.database))
            return
        for database in DATABASES:
            directory = STORAGE_ROOT / database
            if args.action == "prepare":
                prepare(directory, database)
            check_host(directory, database, os.getgid())
        if args.action == "check":
            check_node(args.cluster)
    except ValueError as exception:
        sys.exit(f"{exception}. PostgreSQL must not start; see docs/database-storage.md.")
    except (OSError, IndexError, subprocess.CalledProcessError):
        sys.exit(
            "Database storage check failed; PostgreSQL must not start. Check prepared directories, "
            "Colima writable mounts, database UID permissions, and kind extraMounts. "
            "Existing data/cluster were not replaced. See docs/database-storage.md."
        )
    print("Database storage identities and write permissions verified")


if __name__ == "__main__":
    main()
