"""Apply grants and initialize unset role passwords without rotating existing passwords."""

import argparse
import os
import subprocess
import sys
from pathlib import Path

from database_credentials import checked_secrets
from local_credentials import DEFAULT_FILE, ROLE_PASSWORDS, load, value

SERVICE = "garmin-postgresql.garmin-analytics.svc.cluster.local"
ROLES_SQL = Path(__file__).resolve().parents[1] / "postgresql/roles.sql"
ADMIN_COMMAND = (
    'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; '
    'exec psql -X -w -h garmin-postgresql.garmin-analytics.svc.cluster.local '
    '-U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"'
)


def psql(context: str, arguments: list[str], contents: str = "", admin: bool = True):
    """Use stdin without a TTY; capture prompts/errors and never put passwords in arguments."""
    command = ["kubectl", "--context", context, "-n", "garmin-analytics", "exec", "-i",
               "statefulset/garmin-postgresql", "-c", "postgresql", "--"]
    if admin:
        command.extend(["sh", "-ec", ADMIN_COMMAND, "sh", *arguments])
    else:
        command.extend(["env", "-u", "PGPASSWORD", "PGPASSFILE=/dev/null", "psql", "-X",
                        "-h", SERVICE, "-d", "garmin_analytics", *arguments])
    return subprocess.run(command, input=contents, capture_output=True, text=True, timeout=60)


def existing_passwords(context: str) -> set[str]:
    """Query only role names with passwords, never their stored verifiers."""
    result = psql(context, ["-At", "-c", "SELECT rolname FROM pg_authid WHERE rolname IN "
                           "('garmin_schema_manager', 'garmin_loader') AND rolpassword IS NOT NULL"])
    if result.returncode:
        raise ValueError("Cannot inspect analytics roles using the configured bootstrap account")
    return set(result.stdout.splitlines())


def check_password(context: str, role: str, password: str) -> None:
    """Authenticate through the Service, rejecting passwordless/trust configurations."""
    query = ["-U", role, "-At", "-c", "SELECT 1"]
    if psql(context, ["-w", *query], admin=False).returncode == 0:
        raise ValueError(f"{ROLE_PASSWORDS[role]}: database must require password authentication")
    if psql(context, ["-W", *query], password + "\n", admin=False).returncode:
        raise ValueError(f"{ROLE_PASSWORDS[role]}: conflicts with the existing PostgreSQL login; "
                         "deployment never rotates passwords")


def configure(context: str, credentials: dict) -> None:
    """Validate every existing password before grants or password initialization."""
    checked_secrets(context, credentials, "analytics")
    existing = existing_passwords(context)
    for role in ROLE_PASSWORDS:
        if role in existing:
            check_password(context, role, value(credentials, ROLE_PASSWORDS[role]))
    result = psql(context, [], ROLES_SQL.read_text())
    if result.returncode:
        raise ValueError("Analytics role/grant setup failed; credential values were suppressed")
    for role, entry in ROLE_PASSWORDS.items():
        if role not in existing:
            password = value(credentials, entry)
            # psql encrypts client-side. No plaintext password appears in SQL or server logs.
            result = psql(context, ["-c", f"\\password {role}"], f"{password}\n{password}\n")
            if result.returncode:
                raise ValueError(f"{entry}: could not initialize the PostgreSQL password")
        check_password(context, role, value(credentials, entry))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, default=os.environ.get("CREDENTIALS_FILE", DEFAULT_FILE))
    args = parser.parse_args()
    try:
        configure(os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"),
                  load(args.file, "analytics"))
    except ValueError as exception:
        sys.exit(str(exception))
    except (OSError, subprocess.SubprocessError):
        sys.exit("PostgreSQL role setup failed; credential values were suppressed")
    print("Analytics roles configured; existing passwords preserved")


if __name__ == "__main__":
    main()
