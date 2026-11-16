# PostgreSQL host ownership: local verification

Verified on 2026-11-16 after the first host-backed analytics startup failed.

## Observed failure

The storage init container completed, but PostgreSQL reported that
`/var/lib/postgresql/data/pgdata` had wrong ownership. Read-only metadata checks
showed Colima using SSHFS and the directory owned by Mac UID 501, while the
PostgreSQL container ran as image-default UID 999. The original write probe did
not cover PostgreSQL's stricter requirement that the server own its data directory.

## Change

Make supplies the host UID to both database Pods and PostgreSQL containers.
Their init containers inherit that UID. The host probe checks actual ownership
of newly created directories and existing data directories; init checks repeat
the owner check on the mounted PVC. Neither check rewrites ownership or initializes
existing storage. Image versions, database roles, credentials, and kind mounts
are unchanged.

## Executed checks

- All **68** infrastructure unit tests passed. Added cases cover new/existing
  directories owned by the host UID and reject mismatched created/existing owners.
- `make infra-validate` rendered both Kustomize overlays and pinned Helm charts.
  Additional local decoding of both rendered StatefulSets confirmed that the Pod,
  PostgreSQL container, and storage init container all use host UID 501 and include
  the ownership guard.
- `make database-storage-check` passed against the current Mac/Colima/kind mounts.
  It read identity/ownership metadata and created/removed only temporary probes;
  existing PostgreSQL files and credentials were not changed.
- Disposable containers using the exact `postgres:17.11-bookworm` and
  `bitnamilegacy/postgresql:16.1.0-debian-11-r15` images initialized synthetic
  databases as the host UID on temporary directories under the same Mac/Colima
  mount. Authenticated SQL wrote a synthetic row. After clean stop/removal and
  a replacement container, the row remained readable in both databases.
- Test containers had no network or published ports. They used synthetic password
  files in temporary directories; all test data and containers were removed.
  Whitespace checks passed.

The live database workload was not redeployed or restarted by the agent. Full
stack startup still requires applying the reviewed changes with `make stack-up`;
no further kind recreation is required for this UID configuration change.
