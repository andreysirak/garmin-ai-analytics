#!/usr/bin/env bash
set -euo pipefail

NAMESPACE=airflow
JOBS_NAMESPACE=airflow-jobs
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

if [[ -n "${GARMIN_API_KEY:-}" ]]; then
  create_if_missing "$JOBS_NAMESPACE" garmin-api-credentials \
    --from-literal=api-key="$GARMIN_API_KEY"
else
  echo "GARMIN_API_KEY is unset; skipping Garmin credentials Secret"
fi
create_if_missing "$JOBS_NAMESPACE" garmin-access-token \
  --from-literal=expires-at=0
unset DB_PASSWORD ADMIN_PASSWORD GARMIN_API_KEY
