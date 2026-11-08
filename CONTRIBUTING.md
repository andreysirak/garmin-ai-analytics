# Contributing

- Create a feature branch from `main` and open a pull request targeting `main`.
- Do not commit credentials, Garmin API responses, or personal Garmin data. Use
  synthetic fixtures in tests.
- Add notable changes to the Unreleased section in `CHANGELOG.md`.
- Keep changes focused; do not add database storage, additional Garmin endpoints,
  or changes to repository rulesets without approval.

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

`mvn verify` runs JUnit 5, SpotBugs, PMD, and the parent-level aggregate OWASP
Dependency-Check scan. Tests use synthetic data and local/mock HTTP servers;
never use real Garmin credentials or personal data. Dependency-Check must
update its vulnerability database and fails for CVSS 7.0 or higher. Configure
an NVD API key in `NVD_API_KEY` locally or as the GitHub Actions secret of the
same name so the database can be updated reliably. This key is separate from
Garmin credentials. Do not add suppressions without documenting the rationale.

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

Pull requests should pass **Validate infrastructure** and **Java integration
checks** and **Python integration checks**. Run the optional **Airflow Smoke
Test** when changing Kubernetes or Airflow execution. Do not use Garmin
credentials in CI.
