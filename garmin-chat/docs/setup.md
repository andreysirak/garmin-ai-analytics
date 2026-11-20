# Set up and run Garmin chat

Follow this guide after the [local PostgreSQL/Airflow setup](../../docs/getting-started.md)
and a successful daily-summary load. Run all commands from the repository root.
The reader login, saved private credentials and Kubernetes Secret must agree
before running `make chat-up`.

[Chat overview](../README.md) · [Analytics reference](analytics-reference.md) · [Verification](testing.md)

## Configuration and prerequisites

Use the existing single-node `kind-garmin-ai-analytics` context, analytics
PostgreSQL and `staging.stg_garmin_daily_summary` with backfilled data. kind's
`standard` storage class must be available. Install Docker, kind, Helm, kubectl
and Python. Docker builds provide Java 25 and Node; local Java/Node are needed
only for the [offline checks](testing.md#offline-verification).

The profile is **server-side configuration**, not question/model/client input.
In Kubernetes, `GARMIN_CHAT_PROFILE_ID` comes from key `profile-id` in
`garmin-chat-database`. It must be a positive profile ID present in staging.
Local backend development supplies that environment variable directly.

See the [configuration reference](configuration.md) for chart defaults, local
overrides, model/context settings and transport/application/proxy timeouts.

## Reader login and Secret (manual setup/review)

Use the dedicated read-only login, not an administrator or loader. If absent,
create it once after staging exists:

```sh
psql -X -h 127.0.0.1 -p 5432 -U garmin_analytics -d garmin_analytics -W \
  -f garmin-chat/sql/create-reader.sql
```

The script grants `garmin_chat_reader` only CONNECT, schema USAGE and SELECT on
the daily-summary table. Read-only transactions are its default; Hikari also
marks connections read-only. The script prompts for a password using `\password`
and fails if the role already exists. Reuse an existing working login instead
of rerunning role creation. Homebrew libpq provides `psql` on macOS.

In the existing mode-0600 `config/credentials.local.json`, preserve all entries
and add `postgresql.garmin_chat_reader_password` and a **positive JSON integer**
at `chat.profile_id`. These additional chat entries are not managed by the
existing infrastructure bootstrap helpers.

For a new installation, create the Secret through stdin, without printing or
committing its contents. Run from the repository root:

```sh
python3 - <<'PY' | kubectl --context kind-garmin-ai-analytics -n garmin-analytics create -f -
import json
import sys
sys.path.insert(0, "infrastructure/scripts")
from local_credentials import DEFAULT_FILE, load_private_json, value
credentials = load_private_json(DEFAULT_FILE)
password = value(credentials, "postgresql.garmin_chat_reader_password")
profile = credentials.get("chat", {}).get("profile_id")
if type(profile) is not int or profile <= 0:
    raise SystemExit("Set chat.profile_id to the selected positive profile ID")
print(json.dumps({
    "apiVersion": "v1", "kind": "Secret",
    "metadata": {"name": "garmin-chat-database", "namespace": "garmin-analytics"},
    "type": "Opaque",
    "stringData": {"username": "garmin_chat_reader", "password": password,
                   "profile-id": str(profile)}
}))
PY
```

`create` fails rather than replacing an existing Secret. For an **existing chat
installation**, if the profile key is missing, add just that key before redeploying;
leave its working username and password untouched:

```sh
python3 - <<'PY' | kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  patch secret garmin-chat-database --type merge --patch-file=/dev/stdin
import json
import sys
sys.path.insert(0, "infrastructure/scripts")
from local_credentials import DEFAULT_FILE, load_private_json
profile = load_private_json(DEFAULT_FILE).get("chat", {}).get("profile_id")
if type(profile) is not int or profile <= 0:
    raise SystemExit("Set chat.profile_id to the selected positive profile ID")
print(json.dumps({"stringData": {"profile-id": str(profile)}}))
PY
```

Helm injects `username`, `password` and `profile-id` using `secretKeyRef`, without
storing their values in the release. Custom Secret/key names are configurable
through `database.existingSecret`, `usernameKey`, `passwordKey`, `profileIdKey`.
The internal database URL defaults to
`garmin-postgresql.garmin-analytics.svc.cluster.local:5432/garmin_analytics`.
Preserve the original working credentials when rebuilding the cluster. Updating
a database password also requires updating its Secret and restarting the backend.

## Deploy and open (for human review)

```sh
make chat-up
# Optional: make chat-up CHAT_VALUES=/private/path/chat-values.yaml
```

This lints values, builds/loads React and Spring Boot images into the existing
kind cluster, upgrades/installs the Helm chart, and waits for workloads and the
model-pull Job (timeout 30 minutes). A build ID restarts app Pods to pick up newly
loaded local images. After success, it starts frontend port-forwarding in the
same terminal. Open the address on the `Forwarding from` line. It normally uses
**http://127.0.0.1:5173** and uses a free port if the default is occupied. Set
`CHAT_PORT` to require a specific port. Keep the terminal open. Ctrl+C stops
forwarding while Pods keep running.

```sh
make chat-ui  # Reconnect later without rebuilding or redeploying.
# Optional: make chat-ui CHAT_PORT=5174
```

The ordinary setup Job runs per Helm revision and pulls a model only if missing.
It reaches Ollama through its Service, so downloads are written by the server
into the model PVC. Failed pulls fail the Job and `chat-up`; correct the cause
and rerun to create a fresh Job. Pod restarts reuse the model files. The PVC is
retained on Helm uninstall, but kind's node-local storage does not survive
cluster deletion. Existing host-backed PostgreSQL storage remains unchanged.

Inspect readiness and initialization:

```sh
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  get deployments,pods,jobs,pvc -l app.kubernetes.io/instance=garmin-chat
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  logs -l app.kubernetes.io/instance=garmin-chat,app.kubernetes.io/component=model-pull \
  --all-containers=true --prefix --tail=50
```

The backend exposes `/actuator/health/ollamaModel` via its internal Service
(port-forward backend 8081 for direct inspection). It reports available when
Ollama shows the selected model and declares its tool capability. An unavailable
or incompatible model yields HTTP 503 with `Retry-After: 10` and a safe reason.
Database failures have a separate safe error. Kubernetes probes keep the backend able to explain model
unavailability; Ollama server readiness permits setup before a model exists.
An `Insufficient cpu` event means a scheduling reservation problem, not an
active download. Interrupt an unsuccessful wait, fix resources and rerun
`chat-up`; a cluster rebuild is not required.
