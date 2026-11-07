#!/usr/bin/env bash
# Creates local-only Kubernetes Secrets for Airflow. Idempotent: existing secrets are kept.
# Nothing generated here is written to disk or committed.
set -euo pipefail

NS=airflow
kubectl() { command kubectl --context "${KUBE_CONTEXT:-kind-garmin}" "$@"; }
rand() { openssl rand -hex 24; }

exists() { kubectl -n "$NS" get secret "$1" >/dev/null 2>&1; }

create() {
  local name=$1; shift
  if exists "$name"; then
    echo "secret/$name exists, skipping"
  else
    kubectl -n "$NS" create secret generic "$name" "$@"
  fi
}

DB_PASSWORD=$(rand)
ADMIN_PASSWORD=$(rand)

create airflow-postgresql \
  --from-literal=postgres-password="$(rand)" \
  --from-literal=password="$DB_PASSWORD"
# Only used on first creation; the password above is the one stored in the secret.
if ! exists airflow-metadata-db; then
  DB_PASSWORD=$(kubectl -n "$NS" get secret airflow-postgresql -o jsonpath='{.data.password}' | base64 -d)
  create airflow-metadata-db \
    --from-literal=connection="postgresql://airflow:${DB_PASSWORD}@airflow-postgresql.${NS}:5432/airflow"
fi

create airflow-fernet-key --from-literal=fernet-key="$(python3 -c 'import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())')"
create airflow-api-secret-key --from-literal=api-secret-key="$(rand)"
create airflow-jwt-secret --from-literal=jwt-secret="$(rand)"
create airflow-simple-auth --from-literal=passwords.json="{\"admin\": \"${ADMIN_PASSWORD}\"}"
