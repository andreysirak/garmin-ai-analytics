# Historical chat check counts

This note preserves a pre-tool-calling-refactor report formerly embedded in the
chat README. It is historical evidence, not a result for the current revision.

| Provenance | Recorded information |
| --- | --- |
| Source | Chat README on commit `df3cbbce828900da861a7b2bd7dc51922ac946c1`, inherited from earlier work |
| Claimed execution stage | Step 3, before the tool-calling refactor |
| Exact tested revision/environment/date | Not supplied in that paragraph; do not infer them from the source-document revision |
| Model responses | Stubbed; no live inference evidence |
| Rerun during documentation changes | None |

## Original report

Before the tool-calling refactor, Step 3 verification passed 226 offline tests
and 35 read-only PostgreSQL tests and built the executable JAR. All model
responses in these checks are stubbed. The opt-in suite requires
database environment settings. Both suites run without an Ollama connection.

[Current verification instructions](../../garmin-chat/docs/testing.md)

New verification records should identify the tested commit, execution date,
environment, commands, outcomes and skipped checks. Distinguish synthetic
responses from real-model runs and keep private readings/credentials out of reports.
