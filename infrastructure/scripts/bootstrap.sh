#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  --postgresql-only) scope=analytics ;;
  "") scope=airflow ;;
  *) echo "Usage: $0 [--postgresql-only]" >&2; exit 1 ;;
esac

exec python3 "${SCRIPT_DIR}/database_credentials.py" apply --scope "$scope"
