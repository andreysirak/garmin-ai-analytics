# Verify Garmin chat

Check application code, SQL and real-model answers separately. This guide retains
the existing commands and manual checklist. Previously reported test counts
below are historical; moving this documentation did not rerun those checks.
Run commands from the repository root. Deployment steps remain manual.

[Chat overview](../README.md) · [Setup](setup.md) · [Analytics reference](analytics-reference.md) · [Diagnostics](chat-diagnostics.md)

<a id="step-1-manual-verification-deployment-requires-approval"></a>

## Manual application verification

The local automated checks do not establish live Ollama answer correctness.
Run this checklist against the deployment you intend to review, after arranging
any required deployment approval.
It uses the existing summary analytics; correlation remains unsupported.

1. Rebuild/load the app images and upgrade
   the existing chat release:

   ```sh
   make chat-up
   ```

   This also checks the cached model and forwards the frontend. Keep the
   terminal open and open the address on the `Forwarding from` line. Confirm
   the introduction reads “Ask your Garmin watch”. The deployment reuses the
   local values and reader Secret in the existing cluster. Use `make chat-ui`
   to restart forwarding on its own.

2. Use a known backfilled week. Replace the example dates below if necessary.
   Each answer should show the requested inclusive range, correct units and
   coverage, with numbers matching PostgreSQL:

   | Question | Expected calculation/unit |
   | --- | --- |
   | How many steps did I record in total from 2026-10-05 through 2026-10-11? | SUM / steps |
   | What was my average resting heart rate from 2026-10-05 through 2026-10-11? | AVG / bpm |
   | What was my maximum daily active calorie burn from 2026-10-05 through 2026-10-11? | MAX / kcal |
   | What was my average daily recorded sleep time from 2026-10-05 through 2026-10-11? | AVG / hours |
   | What was my minimum daily step count from 2026-10-05 through 2026-10-11? | MIN / steps |

   Sleep summaries still use the existing calendar-day `sleepingSeconds` field
   divided by 3600. They must not be described as a verified overnight sleep
   duration, a sleep-quality assessment or a medical finding.

   Set `GARMIN_CHAT_PROFILE_ID` privately in the checking terminal to the
   server-configured profile. Compare with the fixed SQL using the existing
   reader login/password prompt; for example:

   ```sh
   psql -X -h 127.0.0.1 -p 5432 -U garmin_chat_reader -d garmin_analytics -W \
     --set=profileId="$GARMIN_CHAT_PROFILE_ID" \
     --set=startDate="'2026-10-05'" --set=endDate="'2026-10-11'" \
     --set=metric="'restingHeartRate'" --set=aggregation="'AVG'" --set=divisor=1 \
     --file=garmin-chat/backend/src/main/resources/sql/metric-aggregation.sql
   ```

   Repeat with the selected field/operation; use divisor 3600 for
   `sleepingSeconds` and 1 for other metrics. Recorded zeros must stay valid,
   unknowns must remain unknown and incomplete totals must be labelled partial.

3. Ask “Do days with more physical activity tend to be followed by longer
   sleep?” and “Correlate steps and sleep from 2026-10-05 through 2026-10-11”.
   Expect friendly unsupported guidance and one supported example, no coefficient
   or invented conclusion, and no date caption. The old “Unsupported aggregation”
   and “Date range not selected” messages must not appear. Unsupported requests
   should finish without a tool call or SQL.

4. Ask “Average resting heart rate” to check clarification, then ask a dated
   question containing an impossible date such as 2026-02-30. Expect clear
   guidance without a date caption. Ask for total steps over a known empty
   period, such as 2000-01-01 through 2000-01-07: expect a direct no-data answer
   with those dates still displayed.

5. Repeat one supported dated question three times. Wording may vary; the
   calculated values, units, dates and coverage must remain consistent with SQL.
   Then alternate supported and unsupported questions: the date caption should
   disappear for rejection and reappear for a supported result. Inspect the
   browser Network panel if needed: requests remain `{"message":"..."}` and
   successful responses contain only `answer`, `startDate`, `endDate`.

Keep profile IDs, credentials, real measurements and answers out of commits and
public logs. Record manual results only after executing the checks above, with
the revision, deployment, model, dates and any skipped scenarios identified.

## AI evaluation suite

The [evaluation guide](../evaluation/README.md) separates offline contracts,
real-model routing/explanation trials and synthetic application-path captures.
The [catalogue](../evaluation/TEST-CASES.md) lists stable scenarios and expected
behaviour. Start with `make chat-eval-test`; a passing offline suite does not
establish model answer quality. Live runs require already-running services and
explicit output directories. Follow the guide for evidence review and comparison.

## Offline verification

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)  # macOS; select Java 25 on other OSes
mvn -B -ntp -f garmin-chat/backend/pom.xml \
  -Dtest='*Test,!PostgresMetricAggregationDatabaseTest' clean test
npm --prefix garmin-chat/frontend ci
npm --prefix garmin-chat/frontend test
npm --prefix garmin-chat/frontend run build
python3 -m venv /tmp/garmin-chat-checks
/tmp/garmin-chat-checks/bin/python -m pip install -r garmin-chat/tests/requirements.txt
PATH="/tmp/garmin-chat-checks/bin:$PATH" make chat-validate
```

Tests use synthetic summaries, a fixed Clock, stub repositories/models and fake
HTTP transport/CLIs. They cover explicit/relative/invalid dates, timezone and DST,
clarification and unsupported answers without tools, bound profile/date arguments,
compact evidence with distinct coverage and no raw payloads, no data,
model/database failures, call limits, the request deadline, and oversized
questions and evidence. Budget tests include system text, JSON, question, response
reserve and non-Latin text. Configuration, React, Helm render, model setup and
Make sequencing checks remain offline. No Git write operations are performed.

The PostgreSQL aggregation suite is opt-in. Set `GARMIN_CHAT_TEST_DB_URL`
(normally `jdbc:postgresql://127.0.0.1:5432/garmin_analytics`),
`GARMIN_CHAT_TEST_DB_USERNAME` and `GARMIN_CHAT_TEST_DB_PASSWORD` privately from
the existing reader Secret. Set `GARMIN_CHAT_TEST_PROFILE_ID` from its
`profile-id` key to include comparison against backfilled data, then run:

```sh
mvn -B -ntp -f garmin-chat/backend/pom.xml \
  -Dtest=PostgresMetricAggregationDatabaseTest test
```

These tests perform SELECTs only. Synthetic rows replace just the staging
relation in the production SQL, so no test table, database, container or data
writes are needed. They cover all approved operations, independent per-metric
coverage, other-profile/out-of-range exclusion, absent/null/invalid values,
recorded zeros, leap-day boundaries, decimal precision and fractional sleep
hours. The reader-role check verifies default read-only mode and no staging
write privileges. The optional real-data check selects the latest stored week
and compares all 15 supported metric/operation pairs plus coverage against
[direct table calculations](../backend/src/test/resources/sql/metric-aggregation-comparison.sql).
It prints no personal readings or profile identifiers. Database-backed service
tests also verify calculated compact evidence and the no-data path with stubbed
model responses. The offline HTTP integration test exercises MVC, repository
mapping and the real Spring AI client with mocked JDBC and HTTP boundaries.

For earlier test counts and their limitations, see the
[historical verification note](../../docs/verification/0076-chat-checks-reference.md).
Those results do not establish that the current revision passes.
