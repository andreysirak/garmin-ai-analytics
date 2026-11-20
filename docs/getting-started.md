# Run Garmin AI Analytics locally

Set up the local ingestion stack first, then follow the chat guide for its reader
credentials and model/application deployment. Commands below run from the
repository root. Existing installations must follow the linked migration guidance
before changing storage.

[Project overview](../README.md) · [Local resources and troubleshooting](local-operations.md) · [Run the pipeline](ingestion-guide.md)

## Quick start

For the Spring Boot + Spring AI + Ollama + React text demo in the existing kind
cluster, see [Garmin chat](../garmin-chat/README.md). `make chat-up` builds and loads
the app images, deploys the chat Helm chart, initializes its model cache, and
forwards the frontend to http://127.0.0.1:5173 in the same terminal. If the default
port is occupied, open the address printed by `kubectl` on the `Forwarding from` line.
The demo interprets supported metrics, aggregations and dates from natural
questions. PostgreSQL calculates results through a read-only account for the
server-selected profile; Ollama explains compact evidence without raw summaries.
The structured analytics flow is implemented locally and awaits deployment review.

### Prerequisites

Install Docker (or Colima), kind, kubectl, Helm, Python 3.12, and OpenSSL. Analytics PostgreSQL also requires the local `psql` client (`brew install libpq` on macOS). Make checks find Homebrew libpq automatically, even when its `bin` directory is not on PATH. Java 25 and Maven are needed for direct Java development; Make targets can build Java code using Docker.

### 1. Configure credentials once

```bash
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
```

Fill in the five PostgreSQL passwords and `garmin.api_key` in this single private
file. See [credential setup](credentials.md)
for the exact account mapping and adopting an existing cluster's values without
rotating them. Existing Secrets are never overwritten; conflicts name the entry
without printing its value.

For a fresh local installation, leave `"fernet_key": ""` as supplied: no key
generation is needed. Airflow then stores connection passwords and Variables
unencrypted in its metadata database. An existing encrypted installation must
keep its original key; deployment rejects an empty value that conflicts with its
live Secret. See [local Fernet behavior](credentials.md#fresh-installation).

An external secrets manager is intentionally deferred to keep the local setup
simple. The private file is the current workaround for setup and rebuilds;
Kubernetes Secrets still supply runtime credentials. See
[the rationale and trade-offs](credentials.md#why-this-local-workaround-exists).

### 2. Prepare the cluster

```bash
make prepare-database-storage  # First installation only; migrate existing databases first
make cluster-create
make infra-validate
```

Both PostgreSQL databases use separate host directories under `data/postgresql/`.
Make runs the databases as the host UID and checks mount identities, directory
ownership, and write permissions before deployment. Existing
node-local PVCs need a one-time backup/restore migration; keep the original
credentials and any existing Fernet key in the local file. See
[database storage and rebuild](database-storage.md).

### 3. Deploy PostgreSQL and Airflow

```bash
make postgresql-up
make infra-up
```

`postgresql-up` requires only the three analytics passwords, not a Garmin API key.
It noninteractively configures the bootstrap, schema-manager, and loader accounts,
preserving existing passwords. `infra-up` uses the same file for Airflow's database
accounts, Fernet setting, Garmin API Secret, and the schema/loader connection passwords.
No password prompts, `postgresql-loader-password`, or manually duplicated
`config/connections.json` passwords are needed for normal setup or a kind rebuild.

`config/connections.example.json` defines where and as whom Airflow connects;
`config/credentials.local.json` supplies the passwords. Both Garmin PostgreSQL
definitions are checked in without password fields and used by default. Optional
`config/connections.json` (mode 0600) provides local field overrides and additional
connections. Resolved definitions are validated and sent through stdin directly
to Airflow, without generated password files. Existing entries are preserved.
See [credential handling](credentials.md)
and [local analytics PostgreSQL](postgresql.md) for details. The Mac endpoint
is `127.0.0.1:5432`; Airflow uses the internal analytics Service.

#### One-command alternative

After configuring `credentials.local.json` in step 1 and starting Docker/Colima,
you can replace steps 2 and 3 with:

```bash
make stack-up
```

This runs the existing storage preparation, cluster creation/reuse, manifest
validation, PostgreSQL deployment, and Airflow deployment targets in order,
including their readiness checks. It supports a fresh installation and repeat
runs. All individual targets above remain available.

To reapply configuration and restart an existing stack:

```bash
make stack-restart
```

It reuses `stack-up`, restarts both databases and the Airflow services, and waits
for their rollouts. Finish active work first because database connections are
briefly interrupted. Neither command triggers DAGs or deletes the cluster,
database files, raw archives, or existing credentials.

Both commands first check local configuration and existing kind node settings.
Changes to the node image, topology, bind mounts, or host ports require an
explicit rebuild and stop before deployment or restart. Older node-local
installations require the documented migration first. Follow the
[storage migration and rebuild guide](database-storage.md#startup-and-restart-55)
when a preflight fails; the wrappers never recreate a cluster automatically.

Inspect the running cluster:

```bash
make infra-status     # Namespaces, Helm releases and Pods across all namespaces
make infra-resources  # Node capacity, Pod requests/limits, allocation totals and actual usage
```

`infra-resources` uses `kubectl top nodes` and
`kubectl top pods --all-namespaces --sort-by=memory` for actual CPU/memory usage.
This requires a working Metrics Server. If metrics are unavailable, it prints
an explanation; the capacity, requests/limits and allocation report still works.
Requests and limits are configured values; usage shows measured consumption.
Both targets use the existing kind context.

## Next steps

Open Airflow and [run the pipeline](ingestion-guide.md#4-run-the-pipeline).
Once the database is available, follow the [chat setup](../garmin-chat/README.md)
for the read-only account, local values and `make chat-up`. `make stack-up`
prepares PostgreSQL and Airflow; it does not deploy the chat stack.

For CPU/memory configuration and scheduling diagnostics, see
[local operations](local-operations.md). The historical bounded capacity check is
[recorded separately](verification/0076-local-capacity-reference.md).
