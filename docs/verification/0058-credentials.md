# Single-file credential setup: local verification

Verified on 2026-11-16 before live deployment. Only synthetic credentials and
isolated temporary PostgreSQL and Airflow containers were used. The real local credentials
file and live Kubernetes Secrets/databases were not read or changed.

## Offline tests

```bash
python3 -m unittest discover -s infrastructure/tests -v
make infra-validate
bash -n infrastructure/scripts/bootstrap.sh
git diff --check
```

- All 51 infrastructure tests passed. They cover required fields per target,
  mode 0600, symlinks, placeholders, Fernet validation, URI escaping, Secret
  comparison/creation over stdin, named conflicts without values, and API errors.
- Analytics bootstrap works with no Garmin API key. Tests invoke the real
  bootstrap with a synthetic kubectl and invoke Make with invalid configuration
  to confirm validation precedes deployment tools.
- Both Garmin connection definitions/descriptions come from the checked-in
  `connections.example.json`, with no password fields. Only the private credential
  file supplies their passwords. Partial local overrides and additional custom
  connections are supported. Invalid definitions and inline managed passwords are
  rejected. Mocked session tests also reject any file-based import path.
- Both Kustomize overlays and both pinned Helm charts rendered successfully.
  The initial sandbox attempt could not resolve the chart registry; rendering
  passed with network access. No Helm deployment command was executed.
- Shell syntax and whitespace checks passed.

## Native PostgreSQL password flow

A disposable `postgres:17.11-bookworm` container used temporary in-memory data,
synthetic environment/password inputs, no host database mounts, and no published
ports. The actual role-configuration helper ran against it through a test-only
kubectl-to-Docker transport; no live kubectl calls were made.

- Fresh roles/grants and both application passwords were initialized through
  native `psql \password` with stdin input, captured prompts, and no TTY.
- Authentication through the synthetic Service hostname succeeded, including
  passwords containing punctuation and Unicode.
- Repeating setup preserved the existing stored password verifiers exactly.
- A conflicting loader password failed with its local entry name; both existing
  verifiers remained unchanged.
- The temporary container was removed and its absence confirmed afterward.

## Real Airflow connection registration

The actual connection registration script was executed in the local
`garmin-airflow:local` image, with Airflow **3.3.1** and a disposable SQLite
metadata database. The container had no network, host mounts, or published ports.
Only a synthetic private credentials file was edited for password values; no
local connection override file was present. Definitions came from the checked-in
example. The resolved payload and Fernet key were passed through stdin.

- Both `garmin_analytics_schema` and `garmin_analytics_loader` were registered
  with their intended logins, original descriptions, and other definition fields.
- Passwords were encrypted in metadata and decrypted correctly with the supplied
  Fernet key. No temporary plaintext JSON import file was created.
- Repeat registration preserved existing entries without duplicates.
- A conflicting loader password rejected the transaction, preserved the original
  credentials, and created no pending additional connection.
- The disposable container was removed after validation.

Full-stack Airflow deployment and kind deletion/recreation were not performed.
Those remain deployment acceptance checks after review. This record does not
claim that the live cluster has migrated to the new setup.
