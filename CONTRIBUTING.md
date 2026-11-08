# Contributing

- Create a feature branch from `main` and open a pull request targeting `main`.
- Use squash merges to keep `main` history focused.
- Ensure the required **Validate infrastructure** PR check passes. The optional
  **Airflow Smoke Test** can be run manually when the change affects deployment or
  execution; see the README for instructions and local Mac checks.
- Use synthetic fixtures only. Never commit Garmin responses, personal data or credentials.
- Add notable changes to the Unreleased table in `CHANGELOG.md`. When cutting a
  release, move its entries under a version and date heading and leave a fresh
  empty Unreleased table.

## Future checks

These are planned, not installed or enforced yet:

- Python: Black, Ruff and pytest.
- Java: JUnit 5, Spotless and SpotBugs via `mvn verify`.
- Scripts and workflows: ShellCheck and actionlint.
