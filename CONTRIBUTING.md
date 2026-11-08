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

Use Java 25 and run from the project root:

```sh
cd garmin-integration
mvn clean verify
mvn -pl garmin-daily-summary -am package
mvn -pl garmin-sleep -am package
```

`mvn verify` runs JUnit 5, SpotBugs, PMD, and one aggregate OWASP
Dependency-Check scan. Tests must use synthetic data and local/mock HTTP servers.
Dependency-Check must update its vulnerability database; an unavailable database
is a failed verification, not a skipped check. Configure an NVD API key in the
local `NVD_API_KEY` environment variable or the GitHub Actions secret of the
same name so the database can be updated reliably. The key is passed through
Maven's `MAVEN_ARGS` and is separate from Garmin credentials. Do not add
justified suppressions without documenting their rationale next to the
suppression.

Pull requests should pass **Validate infrastructure** and **Java integration
checks**. Run the optional **Airflow Smoke Test** when changing Kubernetes or
Airflow execution. Do not use Garmin credentials in CI.
