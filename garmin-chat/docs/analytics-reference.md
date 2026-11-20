# Garmin chat analytics reference

The current scalar workflow uses model-generated routing, Java validation,
parameterized SQL and a second model call for evidence explanation. These are
implementation contracts; they are not a guarantee of real-model answer quality.

[Chat overview](../README.md) · [Setup](setup.md) · [Verification](testing.md)

## Analytics selection contract

The router selects these parameters; Java executes them through the analytics service:

```json
{
  "metrics": ["restingHeartRate"],
  "aggregation": "AVG",
  "period": {"type": "LAST_WEEK"}
}
```

| Canonical field | Evidence unit | Allowed aggregations |
| --- | --- | --- |
| `totalSteps` | steps | SUM, AVG, MIN, MAX |
| `restingHeartRate` | bpm | AVG, MIN, MAX |
| `activeKilocalories` | kcal | SUM, AVG, MIN, MAX |
| `sleepingSeconds` | hours (SQL conversion from seconds) | SUM, AVG, MIN, MAX |

One operation applies independently to one to four distinct metrics. Profile,
field metadata, units and conversion divisors come from server configuration
and the Java catalog, never the model. Unknown properties, fields and operations,
duplicate metrics, incorrect JSON types, contradictory periods
and invalid/future dates are rejected. The reference date and timezone stay in
the system message; the question is passed verbatim as the user message.

The Java metric catalog generates the system context before routing.
It lists field meaning, units, synonyms, allowed aggregations and limitations
for the four supported fields. The model should clarify ambiguity without
querying the database, including questions about work productivity. The service covers
scalar aggregations over a single period. Sleep quality, medical conclusions,
correlation, period comparisons, trends, rankings and thresholds are outside
that scope.

`garmin.chat.max-query-days` / `GARMIN_CHAT_MAX_QUERY_DAYS` is a positive server
setting, default 31; Helm exposes `chat.maxQueryDays`. Count both boundary dates.
No period is silently shortened. Existing relative-date semantics are retained:
last N days excludes today, last week is the previous Monday–Sunday, and explicit
dates require a four-digit year. Shared period rules live in one prompt resource.

Coverage contract implemented by aggregation SQL and the `DataCoverage` record:

| Disjoint day category, per metric | Definition |
| --- | --- |
| `missingSummaryDays` | No row for the configured profile and expected date |
| `missingMetricDays` | Summary exists but the selected key is absent |
| `nullMetricDays` | Summary and key exist; the value is JSON null |
| `invalidTypeDays` | Key contains a non-null value whose JSON type is not number; numeric strings are invalid |
| `daysWithData` | Key contains a JSON number, including recorded zero |

Each expected calendar day belongs to exactly one category. `expectedDays` is
the inclusive date count; `summaryDays = expectedDays - missingSummaryDays`.
`missingDays = missingSummaryDays + missingMetricDays + nullMetricDays`, excluding
invalid types. Consequently `expectedDays = daysWithData + missingDays + invalidTypeDays`.
`zeroDays` is a subset of `daysWithData`, not another category. Aggregates use
numeric readings only; all-unavailable values stay null, including SUM. AVG
divides by numeric readings, not expected days. `complete()` is true only when
every expected day has a numeric reading. Partial totals must be labelled
incomplete in the final answer. No missing reading is replaced with zero.

### PostgreSQL aggregation

`DailySummaryRepository.aggregate(QueryIntent)` returns one `MetricAggregation`
per requested metric: typed metric/operation, PostgreSQL `numeric` as Java
`BigDecimal` (nullable), unit, date range and coverage. It takes the profile from
`GarminChatProperties`, not the intent or a caller-supplied ID. The repository
also rejects reversed or oversized ranges before JDBC.

One [fixed SQL template](../backend/src/main/resources/sql/metric-aggregation.sql)
runs per metric in a short read-only, repeatable-read transaction. Profile,
inclusive dates, catalog field key, allowed operation and conversion divisor
are bound parameters; SQL text never comes from the model. Expected calendar
dates are left-joined to staging so completely absent summaries remain visible.
Only JSON numbers are cast, and PostgreSQL performs SUM/AVG/MIN/MAX before
dividing sleep seconds by 3600. Decimal precision is retained without Java
rounding. Numeric strings, booleans, arrays and objects are invalid types.
Numeric plausibility or medical interpretation is outside this query's scope.

The legacy full-summary repository method remains available, but the chat
service calls only aggregation. The React interface is unchanged.

## Analytics execution, evidence and context budget

Java executes accepted selections directly through `GarminAnalyticsService`.
The service checks field and operation allowlists, compatibility, periods and
query range before PostgreSQL, then validates the returned metrics, operation,
units, dates and coverage. Repository errors remain database failures, never
model input. The existing tool adapter is retained for future tool-driven flows;
the routed scalar workflow does not register callbacks or a tool-loop advisor.
Unexpected tool calls during explanation are rejected without dispatching them.

Named periods require only their type. `LAST_N_DAYS` additionally needs `days`;
`EXPLICIT` needs `startDate` and `endDate`. Omitted auxiliary fields use neutral
Java defaults. Conflicting nonempty dates or nonzero counts for named periods
remain invalid. Existing full objects with neutral fields still decode.

The synchronous request uses one virtual worker so the HTTP caller can
return when its deadline expires. The worker checks the deadline before
checking Ollama availability, before each model turn and before and after analytics execution.
Expiration or interruption of the waiting caller interrupts the worker.
Spring's JDK HTTP transport attempts cancellation while waiting for an exchange.
In-flight HTTP and JDBC operations remain subject to their own timeouts.
A client disconnect on the synchronous servlet endpoint may only be noticed
when the server writes the response.

The analytics service returns `QueryEvidence`, for example (synthetic):

```json
{
  "aggregation": "AVG",
  "dateRange": {"startDate": "2024-02-26", "endDate": "2024-03-03"},
  "metrics": [{
    "metric": "sleepingSeconds", "value": 1.5, "unit": "hours",
    "coverage": {
      "expectedDays": 7, "summaryDays": 6, "daysWithData": 3, "zeroDays": 1,
      "missingSummaryDays": 1, "missingMetricDays": 1, "nullMetricDays": 1,
      "invalidTypeDays": 1, "missingDays": 3, "complete": false
    }
  }]
}
```

The explanation model gets the original question and the complete verified
`QueryEvidence` in a separate system message. It is instructed to use supplied values, identify partial totals
and explain coverage, including missing readings and valid zeros.

Before each inference turn, `PromptBudget` counts UTF-8 bytes for the messages,
routing schema, tool definition, arguments and result, using one estimated token per byte.
It also reserves 512 tokens for template overhead and `chat.responseTokens`
for output. Oversized questions stop before Ollama and SQL. If the conversation
including evidence exceeds the budget, the backend requests a shorter question
or fewer metrics and the final model turn is skipped. The evidence is passed
intact when it fits.

`POST /api/chat` accepts `{"message":"..."}` and returns `answer`, `startDate`
and `endDate`. Dates come from successfully executed evidence and describe
the inclusive range, including gaps. Clarification and unsupported answers
without a query have null dates. Blank requests return HTTP 400, and
database and model failures return safe HTTP 503s.

## Backend packages

The backend uses packages under `com.garmin.analytics.chat`.

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP validation and error responses |
| `service` | Chat use-case entry point |
| `execution` | Request correlation, deadline and cancellation |
| `ai` | ChatClient conversation, model transport, availability and per-turn checks |
| `ai.routing` | Structured decision decoding and validation |
| `ai.prompt` | Prompt assembly from resources and context budgeting |
| `ai.tool` | Routed analytics execution and retained tool adapters |
| `analytics` | Business validation, date resolution and evidence assembly |
| `repository` | Fixed SQL, bound parameters and result mapping |
| `model` | Shared records and metric catalog |
| `diagnostics` | Opt-in structured diagnostics |
| `config` | Application and transport wiring |

Application contracts live in their owning package. Their implementations
live in its `impl` subpackage and use the contract name, an optional technology
qualifier, and the `Impl` suffix. For example, `DailySummaryRepository` is
implemented by `repository.impl.DailySummaryRepositoryPostgresImpl`.
