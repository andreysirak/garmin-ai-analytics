# Documentation and verification conventions

[Documentation index](README.md) · [Contribution guide](../CONTRIBUTING.md)

## Testing terminology

| Term | What runs | What a passing result establishes |
| --- | --- | --- |
| Offline/code tests | Code with synthetic fixtures and substituted model, database or transport boundaries | The tested code contracts behave as asserted; not real-model answer quality. |
| SQL integration checks | Production SQL against PostgreSQL; synthetic inputs and optionally separate read-only comparisons | SQL values, coverage and reader behaviour for the exercised scenarios. Report whether stored-data comparisons ran. |
| Real-model evaluation | Actual prompts/model with fixed questions or frozen evidence and repeat trials | Observed routing/explanation quality for the recorded model and cases, subject to the grading rubric. |
| Application-path checks | HTTP request through the deployed backend, routing, SQL and explanation | Behaviour of the exercised chat path; factual acceptance requires checking the evidence, not just HTTP 200. |
| Ingestion end-to-end checks | Airflow tasks, Java ingestion and archives | Only the specific pipeline stages and external/stub boundaries actually exercised. Chat HTTP tests are not ingestion tests. |

An evaluation harness's own tests belong to offline/code tests, even if they
simulate model HTTP responses. Use `PASS`, `FAIL`, operational error, pending
review and skipped explicitly where applicable. An unexecuted check is not a pass.

## Configuration ownership

Executable settings remain authoritative. Chat defaults are documented once in
[the configuration reference](../garmin-chat/docs/configuration.md), with links to
Helm values and application configuration. Setup and overview pages link there.
Update the reference when a default changes; distinguish direct backend runs from
Helm deployment overrides and locally chosen settings from checked-in defaults.
This organization reduces duplication; it does not automatically detect setting drift.

## Current instructions versus historical evidence

Keep commands for current use in guides. Put verification observations under
`docs/verification/` or `docs/evaluations/`, recording:

- Execution date and tested code revision.
- Environment, relevant settings and model version/digest for inference.
- Commands, fixtures or data category, outcome and skipped checks.
- Whether dependencies/models were real, stubbed or mocked.

When older provenance is incomplete, say what is unknown. Do not invent a test
revision from the commit containing a report. Avoid “this task/change” and old
passing counts in instructions: readers may be following them months later.
Keep private measurements, credentials and traces out of committed reports.

## Check documentation links

Install [Lychee](https://github.com/lycheeverse/lychee#installation) v0.24.2,
then run from the repository root:

```sh
make docs-check
```

The target checks Git-tracked Markdown files, relative file/image paths and
heading/HTML-anchor fragments **offline**. Stage new Markdown files with `git add`
before checking them locally. CI uses a clean checkout, a pinned action and the
same Lychee version. External HTTP links are deliberately not contacted, so this
check does not verify their availability or the correctness of documentation prose.
CI runs on PRs targeting main and pushes to main; no credentials or running stack
are needed. Required branch-protection checks are a separate repository setting.
