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

### AI-assisted contributions

AI-assisted contributions are welcome, whether you use GitHub Copilot, ChatGPT,
Cursor, or another tool. You remain responsible for understanding, reviewing,
and validating everything you submit. Review generated code for correctness,
security, unnecessary complexity, and architectural fit; do not submit it blindly.
Use synthetic data only, and never share credentials, tokens, or personal Garmin
data with AI tools. State any unverified assumptions or incomplete tests.

For every AI-assisted PR, add an **AI-assisted development note** to the PR
description or a PR comment, for example:

> **AI-assisted development note**
> - **Tools used:** [tools and what they assisted with]
> - **Human review and ownership:** [architecture/design decisions reviewed, code understood, manual changes or refactoring]
> - **Verification:** [tests, CI checks, and manual validation performed]
> - **Remaining concerns:** [none, or linked follow-up issues]

This is a transparency and engineering-quality note, not a claim that AI-generated code is independently trustworthy. Do not include secrets, private data, or sensitive prompts.

### AI assistant environments and authorization

There are two different workflows, and their permissions should not be conflated:

- **Local IntelliJ IDEA / IDE coding agent:** may inspect the repository, edit
  local working-tree files, and run agreed local checks as part of an authorized
  coding task. The project owner reviews the diff and ordinarily performs
  local commits and pushes manually. Preparing code or tests does **not**
  automatically authorize a local commit, push, merge, or branch-history rewrite.
- **Browser-connected ChatGPT / Codex GitHub integration:** has the ability to
  change the **remote GitHub repository** directly. Every remote write action
  (commits, pushes, branch creation/deletion, issues, PR lifecycle, comments,
  reviews, or repository settings) requires explicit user instructions for the
  specific action and target. If permission is absent or ambiguous, **ask first**.

The reported incidents involving many small generated commits, unsolicited PR
creation and history manipulation occurred in the **browser GitHub integration**
workflow. They are not attributed to the IDE agent. These safeguards do not
prevent routine local file edits or running tests in the IDE.

All contributors retain responsibility for reviewing generated changes, securing
credentials and personal data, and authorizing Git history operations. Work in
a contributor's own fork is managed by that contributor; upstream writes and
merges require the appropriate repository authorization.

### AI-assisted Git commits and history safety

AI coding assistants in either environment must prepare **coherent, reviewable changes**, not generate a
separate commit for every file, tool call, or small edit. Prefer completing the
requested change, reviewing the combined diff, and making **one meaningful commit**
when a commit has been explicitly authorized. Multiple commits are appropriate only
when they represent intentional, independently understandable steps agreed with
the human. The owner controls when changes are committed; AI tools must not
automatically commit after each edit.

**Commit, squash, and force-push are three distinct actions with separate consent.** In the IDE, the owner normally performs Git operations; for a remote GitHub agent, permission must specify each requested write:

- **Normal commit/push:** only when the human explicitly authorizes it, and only
  within the agreed branch and scope. Prefer non-force, fast-forward pushes.
- **Squash or rebase:** rewriting commit history is **not automatic**. Never
  squash, amend, rebase, reset published history, or recreate existing commits
  merely to make the PR look cleaner. Do so only after an **explicit request
  to perform that specific history change**. A request to update a PR or reduce
  future commit noise does not authorize rewriting existing commits.
- **Force-push:** **never force-push without the human's explicit permission
  for that push**, even if a squash/rebase was requested. Approval to squash
  does not implicitly approve force-push, and approval to force-push does not
  implicitly approve squash. Explain that collaborators may need to
  resynchronize local branches before asking for approval. When approved,
  prefer `--force-with-lease` over `--force` and verify the expected
  remote head; neither option substitutes for permission.
- **No implicit merge:** opening or updating a PR, obtaining green checks, or
  receiving permission for a commit, squash, or force-push never authorizes
  merging. The project owner must explicitly approve any merge.

When a request can be completed without rewriting history, choose the
non-destructive option. If required permission is missing, stop before the
destructive operation and request approval, without blocking unrelated
non-destructive changes. Clearly report any commits, history rewrites, and
pushes in the PR description or handoff.

### Pull request operations require explicit authorization

**No autonomous remote PR management.** Agents with GitHub write access must not perform **any pull
request operation** unless the human explicitly requests that specific action
on the intended PR. This includes opening or closing a PR, reopening it,
marking it draft or ready for review, merging or enabling auto-merge,
retargeting its base branch, changing its title or description, adding labels,
assigning or requesting reviewers, submitting approvals or reviews, posting
or resolving review discussions, or deleting a PR branch.

A request to modify code, document a rule, create an issue, or prepare a change
is **not** automatically permission to create, close, or otherwise manage a PR.
A request to update an existing PR authorizes only the requested changes
within that PR's scope; it does not authorize changing the PR's lifecycle
state or unrelated metadata, and it never grants permission to force-push,
rewrite history, merge, or open/close another PR.

**If the requested PR operation, target PR, or permission is not explicit or
is ambiguous, stop and ask the human which action they want before acting.**
Do not infer permission from prior approvals, normal project workflows, or an
assumption that a cleanup action is helpful. Read-only inspection and
proposing a plan are permitted without modifying GitHub state.

### Suggested AI coding assistant prompts (optional)

Both variants require human code review, safe synthetic fixtures, and honest
verification reporting. Choose the one for the actual environment.

**Local IntelliJ IDEA coding agent:**

```text
You are assisting with Garmin AI Analytics in a local IDE.
Read README.md, CONTRIBUTING.md, the relevant issue, and architecture docs.
Inspect the current working tree and git status before changing files.
Edit local code and tests within the agreed scope; prefer simple solutions.
Use only synthetic Garmin data. Never disclose personal data or credentials.
Run relevant local checks and summarize diff, results, and remaining risks.
The owner ordinarily reviews and performs local commits and pushes.
Do not commit, push, change PRs or history, force-push, or merge unless explicitly asked.
Local file editing and test execution do not require separate remote GitHub approval.
```

**Browser-connected GitHub/Codex agent with remote write access:**

```text
You are assisting with Garmin AI Analytics using a remote GitHub integration.
Read README.md, CONTRIBUTING.md, relevant issues, and architecture docs.
Inspect current repository state before proposing changes.
Prefer one coherent reviewed commit over numerous AI-generated tiny commits.
Never commit, push, create/delete remote branches, issues or PRs, edit PR
metadata, comment, review, close/reopen, merge or change settings unless the
human explicitly requests the specific action on the intended target.
A request to edit code does not authorize PR creation or other GitHub writes.
Never squash/rebase/amend or otherwise rewrite history without an explicit request.
Never force-push without separate explicit permission for that force-push.
Never merge without explicit project-owner approval.
If scope, target, or authorization is ambiguous, ask before acting.
Use synthetic fixtures; do not expose Garmin responses, tokens or personal data.
Report exactly what changed and what verification was actually performed.
```

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
mvn -pl garmin-data-ingestion/java -am package
```

`mvn verify` runs JUnit 5, SpotBugs, and PMD. Tests use synthetic data and
mocked HTTP clients; never use real Garmin credentials or personal data.
Mockito's subclass mock maker is selected in test resources. Application tests mock
the injected JDK HTTP client and `TokenService` interface; failure tests can also mock
`FileWriter`. Tests for `FileWriterLocalImpl` and `TokenServiceKubernetesImpl` use
temporary files to verify the implementations independently.
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
  apache-airflow-providers-postgres \
  -e "garmin-integration/garmin-auth/python[airflow,dev]" \
  -e "garmin-integration/garmin-data-ingestion/python[dev]" \
  -e "garmin-airflow-operators[dev]"
ruff check airflow/dags airflow/tests garmin-airflow-operators \
  garmin-integration/garmin-auth/python \
  garmin-integration/garmin-data-ingestion/python
pytest -q airflow/tests garmin-airflow-operators/tests \
  garmin-integration/garmin-auth/python/tests \
  garmin-integration/garmin-data-ingestion/python/tests
```

The shared ingestion and daily-summary load operators are exported as
`garmin_data_ingestion.GarminIngestionOperator` and
`garmin_airflow_operators.GarminDailySummaryLoadOperator`; `airflow/Dockerfile` copies
both packages alongside `garmin_auth`. Rebuild/load the Airflow image and run
`make airflow-operator-rollout` after operator changes. Both ingestion image tags
continue to use the same shared JAR. The DAG constructs the attempt-directory
template and passes it through the required `output_dir` parameter; `mount_path`
controls only the stable PVC mount.

The loading project is `garmin-airflow-operators/`, with a standard `src/` layout
and packaged SQL resources. `GarminDailySummaryLoadOperator` inherits archive
validation and transactional execution from `JsonArchiveLoadOperator`. Ingestion
remains in its existing project. The shared archive reader can be unit-tested
with temporary files without running a DAG or accessing PostgreSQL. The loader's unit tests mock XCom and
PostgresHook; the DAG tests resolve the named file XCom reference with a mocked
task instance and inspect wiring and rendered dates without executing DAG tasks. See [issue #38 verification](docs/verification/0038-daily-summary-loader.md)
for the unit-only commands.

Python tests mock Kubernetes operations and use synthetic credentials and
local HTTP fixtures. Ingestion tests cover explicit query dates, opaque query
values, stable PVC mounts, directory and JSON-file path XComs, and the date/try-number layout with
completed and unmarked archive fixtures. Do not run authentication against Garmin from tests.
See [issue #32 verification](docs/verification/0032-python-ingestion.md) for the
recorded isolated Airflow/Kubernetes retry check and its test-only substitutions.

Pull requests should pass **Validate infrastructure**, **Garmin API Integration
Java**, and **Garmin API Integration Python**. Keep **Garmin API Integration
Python** configured as a required status check in the repository ruleset: it
explicitly runs the DagBag import/registration suite before the operator and
authentication tests. Preserve this job name when editing CI so the required
status-check configuration continues to match. Run the optional **Airflow Smoke
Test** when changing Kubernetes or Airflow execution. Do not use Garmin
credentials in CI.

For analytics PostgreSQL infrastructure changes, also run the bootstrap tests with
`python3 -m unittest discover -s infrastructure/tests -v`. On the local kind
cluster, run `make postgresql-up`, set the loader password and import the local
Airflow connection as documented, then run `make postgresql-verify` to check
roles, Mac/internal client connectivity, and synthetic-row persistence across
Pod replacement and deployment rollback. See
[Local analytics PostgreSQL](docs/postgresql.md) for the verification behavior
and storage lifecycle.
