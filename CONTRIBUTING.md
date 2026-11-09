# Contributing

- Create a feature branch from `main` and open a pull request targeting `main`.
- Do not commit credentials, Garmin API responses, or personal Garmin data. Use
  synthetic fixtures in tests.
- Add notable changes to the Unreleased section in `CHANGELOG.md`.
- Keep changes focused; do not add database storage, additional Garmin endpoints,
  or changes to repository rulesets without approval.

## Your first contribution

Contributions are welcome, whether you want to fix a bug, improve tests or documentation, or propose a feature. You **do not need an invitation or collaborator access** to contribute through a fork and pull request.

1. Browse [open issues](https://github.com/andreysirak/garmin-ai-analytics/issues) for a focused task, or open an issue to suggest an improvement. If you're new to the project, look for `good first issue` when available; not every issue is ready for external contribution.
2. Comment on an issue to discuss scope before investing time, especially for architecture, infrastructure, API, or data-model changes. You do not need an assignment to propose a change.
3. **Fork** the repository into your GitHub account, create a branch from `main` in your fork, and implement one focused change. See [Quick start](README.md#quick-start) for the local setup.
4. Use **synthetic fixtures only**: never include personal Garmin responses, API keys, bearer tokens, local credentials, or other secrets in code, tests, logs, issues, or PRs.
5. Run the relevant Java, Python, or infrastructure checks described below. You don't need a running Garmin account or real personal data to submit a contribution.
6. Open a pull request **from your fork to this repository's `main`**, link the issue (for example, `Closes #123` when appropriate), describe the change and verification, and respond to review feedback. Include the AI-assisted development note below if you used AI tools.

The project owner reviews contributions and decides whether to merge them. A PR is a proposal, not a guarantee of acceptance. Please keep changes small and discuss substantial design changes first. There is no need to request direct write access.

## Planning and delivery workflow

Use **GitHub Projects** to track work through **Backlog → Ready → In Progress → In Review → Done**.

- A **Story** is a deliverable capability tracked as an issue labeled `story`. Describe its outcome and acceptance criteria.
- Split each Story into focused **Task sub-issues**, linked using GitHub's parent/sub-issue relationship. Tasks should be small enough to review and verify independently.
- Link each implementation PR to its Task issue (for example, `Closes #123`). A PR may close a Task after review and merge; opening a PR does **not** mean the Task is done.
- Move a Task to **In Review** when its PR is ready for review and to **Done** once the PR is merged, checks pass, and the Task is closed.
- **Story Definition of Done:** every related Task sub-issue is closed as completed, implementing PRs have been reviewed and merged, Story acceptance criteria are met, and necessary tests and documentation are updated. Close the Story only then.
- Work may have direct bug/refactoring issues when creating a Story would add no value. Avoid splitting a coherent change merely to meet an arbitrary file-count limit.

## Pull request review standard

Keep PRs **focused on one independently reviewable outcome**. A PR description should include:

1. **Why / scope:** linked Task issue, intended behavior, and relevant architectural decisions.
2. **Verification:** tests/checks executed and results, plus any manual testing or known limitations.
3. **Review notes:** noteworthy design choices, risks, and follow-up issues.

The author must understand the code, its role in the architecture, and any design patterns or abstractions used. Review for correctness, readability, security, duplication, appropriate complexity, and maintainability. Generated code is **not automatically review-ready**. Refactor when necessary before requesting review.

All AI-assisted development tools are permitted. For every AI-assisted PR, add an **AI-assisted development note** to the PR description or a PR comment, for example:

> **AI-assisted development note**
> - **Tools used:** [tools and what they assisted with]
> - **Human review and ownership:** [architecture/design decisions reviewed, code understood, manual changes or refactoring]
> - **Verification:** [tests, CI checks, and manual validation performed]
> - **Remaining concerns:** [none, or linked follow-up issues]

This is a transparency and engineering-quality note, not a claim that AI-generated code is independently trustworthy. Do not include secrets, private data, or sensitive prompts.

**Approval policy:** AI agents may propose issues, branches, changes, commits, pushes, and PRs, but must not perform repository mutations without the project owner's explicit authorization. Do not merge without explicit approval.

See [Retrospective 001 — Garmin API Integration](docs/retrospectives/0001-garmin-api-integration.md) for the lessons behind this workflow.

## Java style

Java code follows the Google Java Style Guide: two-space indentation, explicit
imports, no wildcard imports, and a 100-character line limit.

The repository `.editorconfig` provides editor defaults. To import the IntelliJ
scheme, open **Settings | Editor | Code Style | Java**, choose the gear menu,
select **Import Scheme | IntelliJ IDEA code style XML**, and select
`config/idea/GoogleStyle.xml`. Use IntelliJ IDEA's native **Reformat Code**
action; formatting is IDE-managed and is not enforced by a Maven formatting
plugin. The XML was checked for well-formedness; interactive import into an
IntelliJ installation is not part of automated validation.

## Java verification

Use Java 25 and run from the project root or the Maven parent directory:

```sh
cd garmin-integration
mvn clean verify
mvn -pl garmin-daily-summary/java -am package
mvn -pl garmin-sleep/java -am package
```

`mvn verify` runs JUnit 5, SpotBugs, and PMD. Tests use synthetic data and
local/mock HTTP servers; never use real Garmin credentials or personal data.
Dependency vulnerability scanning is temporarily excluded because the current
Dependency-Check release fails without an NVD API key and unauthenticated NVD
updates were rate-limited. See [issue #6](https://github.com/andreysirak/garmin-ai-analytics/issues/6)
for investigation and criteria to restore a reliable scan.

## Python verification

Use Python 3.12 and create a local virtual environment from the repository root:

```sh
python3.12 -m venv .venv
source .venv/bin/activate
constraints="https://raw.githubusercontent.com/apache/airflow/constraints-3.3.1/constraints-3.12.txt"
python -m pip install --constraint "$constraints" \
  -e "garmin-integration/garmin-auth/python[airflow,dev]" \
  -e "garmin-integration/garmin-daily-summary/python[dev]" \
  -e "garmin-integration/garmin-sleep/python[dev]"
ruff check airflow/dags airflow/tests \
  garmin-integration/garmin-auth/python \
  garmin-integration/garmin-daily-summary/python \
  garmin-integration/garmin-sleep/python
pytest -q airflow/tests \
  garmin-integration/garmin-auth/python/tests \
  garmin-integration/garmin-daily-summary/python/tests \
  garmin-integration/garmin-sleep/python/tests
```

Python tests mock Kubernetes operations and use synthetic credentials and
local HTTP fixtures. Do not run authentication against Garmin from tests.

Pull requests should pass **Validate infrastructure**, **Garmin API Integration
Java**, and **Garmin API Integration Python**. Run the optional **Airflow Smoke
Test** when changing Kubernetes or Airflow execution. Do not use Garmin
credentials in CI.
