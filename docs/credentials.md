# One local setup file

From the repository root:

```bash
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
```

Edit this one file in a private editor. Fill in each placeholder once, then deploy
to an already prepared kind cluster with:

```bash
make postgresql-up
make infra-up
```

No PostgreSQL password prompts or manually duplicated `config/connections.json`
passwords are required. The credentials file must remain a regular, non-symlink
file with mode **0600**; deployment rejects other permissions rather than silently
changing them. It is excluded from Git and Docker build contexts. Passwords are
preserved exactly, including punctuation/Unicode, and must be nonempty single-line
strings. Secret values never belong in shell arguments, logs, commits, or issues.

## Why this local workaround exists

An external secrets manager and an Airflow external secrets backend are deliberately
not integrated yet. For this small local kind + Colima application, the project
currently favors a simple setup over operating another service or adding cloud
authentication and secret-provider integration just for development credentials.

The workaround keeps responsibilities explicit:

- `config/connections.example.json` defines where and as whom Airflow connects.
  It is checked in and contains no Garmin connection passwords.
- `config/credentials.local.json` is the single private source for actual database
  passwords, the Garmin API key, and the original Airflow Fernet key.
- Optional `config/connections.json` provides local definition overrides and
  additional connections; it does not duplicate the two Garmin passwords.

Deployment validates the private file and checks for conflicts, creates missing
Kubernetes Secrets through stdin, and configures database roles. The connection
importer combines the checked-in definitions with the private passwords in memory
and sends the resolved payload through stdin to Airflow. Airflow stores connection
passwords encrypted in metadata using the supplied Fernet key. No resolved JSON
password file or separate credential snapshot is generated.

Kubernetes Secrets are still used to supply credentials to running workloads.
They are the runtime delivery mechanism; the local file is the current setup and
rebuild source. This arrangement does not provide centralized secret management,
rotation, or access auditing. The developer remains responsible for protecting
and backing up the file, preserving the Fernet key, and coordinating rotations.

Revisit an external secrets manager when shared environments or cloud deployment
justify the integration. The connection definitions can remain unchanged: replace
the private-file password lookup with the chosen manager's lookup rather than
adding password copies to the definitions. This is a deliberate local development
trade-off, not a claim that a plaintext file replaces production secret management.

## Accounts and field mapping

These are the accounts configured by the checked-in
[Airflow Helm values](../infrastructure/helm/airflow/values.yaml),
[analytics Helm values](../infrastructure/helm/postgresql/values.yaml), and
[role setup](../infrastructure/postgresql/roles.sql):

| Local entry | PostgreSQL account / purpose | Existing source |
| --- | --- | --- |
| `airflow.database.postgres_password` | `postgres`, Airflow PostgreSQL administrator | `airflow/airflow-postgresql`, key `postgres-password` |
| `airflow.database.password` | `airflow`, metadata login for database `airflow` | `airflow/airflow-postgresql`, key `password` |
| `postgresql.garmin_analytics_password` | `garmin_analytics`, analytics bootstrap superuser | `garmin-analytics/garmin-postgresql`, key `password` |
| `postgresql.garmin_schema_manager_password` | `garmin_schema_manager`, schema setup | Previous private config / Airflow `garmin_analytics_schema` Connection |
| `postgresql.garmin_loader_password` | `garmin_loader`, staging reads/writes | Previous private config / Airflow `garmin_analytics_loader` Connection |
| `airflow.fernet_key` | Encrypt/decrypt stored Airflow connection values | `airflow/airflow-fernet-key`, key `fernet-key` |
| `garmin.api_key` | Garmin Personal API authentication | Existing private config / `garmin-auth/garmin-api-credentials`, key `api-key` |

Airflow enables its separate `postgres` administrator and `airflow` login, so their
passwords are separate fields even if an older bootstrap happened to give them
the same value. Analytics uses the official PostgreSQL image with bootstrap user
`garmin_analytics` and `enablePostgresUser: false`; there is no additional configured
analytics `postgres` account to invent a password for. `garmin_owner` is NOLOGIN
and requires no password.

### Fresh installation

Choose five database passwords and supply a URL-safe base64-encoded 32-byte Fernet
key. A new key can be generated directly into an untouched template field without
displaying it (never do this for an existing installation):

```bash
python3 - <<'PY'
import base64, json, os
from pathlib import Path
path = Path("config/credentials.local.json")
if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600:
    raise SystemExit("Expected a regular mode-0600 credentials file")
settings = json.loads(path.read_text())
if settings["airflow"]["fernet_key"] != "SET_AIRFLOW_FERNET_KEY":
    raise SystemExit("Existing Fernet key left unchanged")
settings["airflow"]["fernet_key"] = base64.urlsafe_b64encode(os.urandom(32)).decode()
path.write_text(json.dumps(settings, indent=2) + "\n")
PY
```

`make postgresql-up` requires only the three `postgresql.*` password fields; it
works with no `garmin` or `airflow` section. `make infra-up` additionally requires
both Airflow database passwords, the Fernet key, the Garmin API key, and both
application-role passwords used to generate connections. Placeholders are errors,
not usable default credentials. Each target validates its file fields and compares
managed live Secrets before applying Kubernetes manifests.

### Existing cluster: adopt the values already in use

Do this **before** deleting/rebuilding the cluster. Copy the template only if the
local file does not already exist; otherwise extend the existing file in place,
preserving `garmin.api_key` and all working values.

1. Populate every entry using the existing sources in the table, not newly chosen
   passwords. Kubernetes `Secret.data` values are base64-encoded; decode exactly
   once in a private editor/Secret viewer. Do not print them into terminal logs.
2. Copy the **original** Fernet key. A freshly generated key cannot decrypt the
   existing Airflow Connections, even if the metadata files were preserved.
3. For the two application roles, copy their existing passwords from the old
   private connection configuration or password manager. The current Airflow
   Connections are another source when decrypted by the current installation
   using its original Fernet key. An older schema Connection may still be named
   `garmin_analytics_migrations`. PostgreSQL stores verifiers, not recoverable
   plaintext passwords; an unknown password needs a separately reviewed rotation.
4. Remove password fields from any Garmin entries in an optional
   `config/connections.json` after adopting their passwords. Keep nonsecret field
   overrides and unrelated custom entries. Managed passwords belong only in
   `credentials.local.json`; inline copies are rejected even when they match.
5. Check configuration without applying anything:

   ```bash
   make analytics-credentials-check
   make airflow-credentials-check
   ```

Conflicts name the local entry and Secret/key, never either value. Missing Secret
keys are also conflicts. Deployment creates absent Secrets through stdin and never
patches/replaces existing ones. The metadata connection URI is derived from the
Airflow login password with proper URL encoding. API/access errors abort rather
than being interpreted as an absent Secret.

Existing application-role passwords are authenticated through the analytics
Service before grants or password changes. The helper rejects passwordless/trust
authentication. Only roles with no password receive the configured value, using
native `psql \password` without a TTY: input and confirmation are supplied through
stdin, client-side encryption protects SQL/server logs, and prompts/output are
captured. A conflicting existing password is reported, never reset automatically.

## Connection definitions and private passwords

`config/connections.example.json` is the default source of connection IDs, type,
host, port, database, login, and descriptions. Both Garmin entries remain explicit
and contain no password fields. `credentials.local.json` supplies only their
actual passwords through this mapping:

| Airflow connection ID | Private password entry |
| --- | --- |
| `garmin_analytics_schema` | `postgresql.garmin_schema_manager_password` |
| `garmin_analytics_loader` | `postgresql.garmin_loader_password` |

After Airflow is ready, `make infra-up` reads those definitions, resolves the
passwords, and registers both connections. Normal setup requires editing only
`credentials.local.json` for passwords, not copying a connection file.

An optional mode-0600 `config/connections.json` can override individual definition
fields or add unrelated connections. `AIRFLOW_CONNECTIONS_FILE` selects an
alternative override file. Absent or empty override files are fine. Partial
overrides keep unspecified default fields; both required Garmin IDs remain.
The resolved settings, port ranges, PostgreSQL type, and intended dedicated logins
are validated before import. Managed password fields in either definition file
are errors; move those values to the mapped private entries.

Import creates missing connections and preserves existing custom entries; it
does not delete entries omitted from the file. Existing managed connection
conflicts still fail without overwriting credentials or connection destinations.

```bash
make airflow-connections-import
```

This reuses `credentials.local.json`; it does not ask for passwords. Credential
transport to the scheduler is through stdin. The importer registers objects using
Airflow's connection model in one transaction. It creates no plaintext password
files, temporary JSON import files, or credential snapshots. Airflow encrypts
stored connection passwords in its metadata database using the original Fernet key.

## Rebuild and explicit rotation

Preserve this same local file with both host database directories. After explicit
cluster recreation, `make postgresql-up` and `make infra-up` recreate missing
database/API/Fernet Secrets from the same values and reattach existing data. Role
passwords and encrypted Airflow connections continue to match. There are no
`db-secrets-export`/`db-secrets-restore` snapshots. Protect the local file with your
database backups; never discard the original Fernet key.

API/JWT session keys and the SimpleAuth UI password are generated only when their
Secrets are absent. They are not PostgreSQL logins or stored-data encryption keys;
they can change on a rebuild. Existing runtime Secrets are preserved in place.

Password rotation remains an explicit administrative task. The interactive
`postgresql-loader-password` and `postgresql-schema-manager-password` targets are
available for deliberate rotation, not normal setup. Coordinate a rotation with
the local file and the affected live Secret/Connection yourself; deployment will
report a mismatch instead of silently making credentials agree.
