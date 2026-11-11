#!/usr/bin/env bash
set -euo pipefail

NAMESPACE=airflow
JOBS_NAMESPACE=airflow-jobs
AUTH_NAMESPACE=garmin-auth
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
kubectl() {
  command kubectl --context "${KUBE_CONTEXT:-kind-garmin-ai-analytics}" "$@"
}
random_secret() {
  openssl rand -hex 24
}
secret_exists() {
  kubectl -n "$1" get secret "$2" >/dev/null 2>&1
}
create_if_missing() {
  local namespace=$1
  local name=$2
  shift 2
  if secret_exists "$namespace" "$name"; then
    echo "secret/$name exists, skipping"
  else
    kubectl -n "$namespace" create secret generic "$name" "$@" >/dev/null
    echo "secret/$name created"
  fi
}
bootstrap_postgresql() {
  local existing_secret
  # Fail on API/authorization errors rather than treating them as a missing Secret.
  existing_secret="$(kubectl -n garmin-analytics get secret garmin-postgresql \
    --ignore-not-found -o name)"
  if [[ -n "$existing_secret" ]]; then
    echo "secret/garmin-postgresql exists, skipping"
  else
    # Stream the password: no plaintext manifest, argument, or normal output.
    random_secret | tr -d '\n' | kubectl -n garmin-analytics create secret generic \
      garmin-postgresql --from-file=password=/dev/stdin >/dev/null
    echo "secret/garmin-postgresql created"
  fi
}

case "${1:-}" in
  --postgresql-only)
    bootstrap_postgresql
    exit 0
    ;;
  "") ;;
  *) echo "Usage: $0 [--postgresql-only]" >&2; exit 1 ;;
esac

bootstrap_garmin_credentials() {
  local credentials_file="${REPO_ROOT}/config/credentials.local.json"
  local expected_context="${KUBE_CONTEXT:-kind-garmin-ai-analytics}"
  local active_context
  local existing_secret

  if [[ ! -f "$credentials_file" ]]; then
    echo "Local Garmin credentials file is absent; preserving any existing Secret"
    return
  fi

  if ! python3 "${SCRIPT_DIR}/read_garmin_api_key.py" --validate-only \
    "$credentials_file" >/dev/null; then
    return 1
  fi

  if ! active_context="$(command kubectl config current-context 2>/dev/null)"; then
    echo "Unable to determine the active Kubernetes context; not writing Garmin credentials" >&2
    return 1
  fi
  if [[ "$active_context" != "$expected_context" ]]; then
    echo "Active Kubernetes context does not match expected context; not writing Garmin credentials" >&2
    return 1
  fi

  if ! existing_secret="$(kubectl -n "$AUTH_NAMESPACE" get secret garmin-api-credentials \
    --ignore-not-found -o name)"; then
    echo "Unable to check for existing Garmin credentials Secret" >&2
    return 1
  fi
  if [[ -n "$existing_secret" ]]; then
    echo "secret/garmin-api-credentials already exists; leaving it unchanged"
    return
  fi

  if ! python3 "${SCRIPT_DIR}/read_garmin_api_key.py" "$credentials_file" |
    kubectl -n "$AUTH_NAMESPACE" create secret generic garmin-api-credentials \
      --from-file=api-key=/dev/stdin >/dev/null; then
    echo "Failed to create Garmin credentials Secret" >&2
    return 1
  fi
  echo "secret/garmin-api-credentials created"
}

DB_PASSWORD=$(random_secret)
ADMIN_PASSWORD=$(random_secret)

create_if_missing "$NAMESPACE" airflow-postgresql \
  --from-literal=postgres-password="$DB_PASSWORD" \
  --from-literal=password="$DB_PASSWORD"
if ! secret_exists "$NAMESPACE" airflow-metadata-db; then
  DB_PASSWORD=$(
    kubectl -n "$NAMESPACE" get secret airflow-postgresql \
      -o jsonpath='{.data.password}' | base64 -d
  )
  create_if_missing "$NAMESPACE" airflow-metadata-db \
    --from-literal=connection="postgresql://airflow:${DB_PASSWORD}@airflow-postgresql.${NAMESPACE}:5432/airflow"
fi

create_if_missing "$NAMESPACE" airflow-fernet-key \
  --from-literal=fernet-key="$(python3 -c 'import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())')"
create_if_missing "$NAMESPACE" airflow-api-secret-key \
  --from-literal=api-secret-key="$(random_secret)"
create_if_missing "$NAMESPACE" airflow-jwt-secret \
  --from-literal=jwt-secret="$(random_secret)"
create_if_missing "$NAMESPACE" airflow-simple-auth \
  --from-literal=passwords.json="{\"admin\": \"${ADMIN_PASSWORD}\"}"

bootstrap_garmin_credentials
create_if_missing "$JOBS_NAMESPACE" garmin-access-token \
  --from-literal=expires-at=0
unset DB_PASSWORD ADMIN_PASSWORD
