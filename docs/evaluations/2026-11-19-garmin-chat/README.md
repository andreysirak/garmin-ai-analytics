# Garmin chat evaluation — 19 November 2026

Historical baseline for [PR #68](https://github.com/andreysirak/garmin-ai-analytics/pull/68)
and [issue #66](https://github.com/andreysirak/garmin-ai-analytics/issues/66).
**Outcome: the tested configuration does not meet the chat acceptance criteria.**
This documentation change does not change application behavior or model defaults.

## Method and provenance

The owner supplied a manifest, summary, 27 routing records and 18 explanation
records from local Ollama. Repository review recomputed counts and latency
statistics and inspected all generated answers. It did not rerun inference.

- Inference: 2026-11-19 11:46:24–11:48:52 UTC (12:46:24–12:48:52 Oslo).
- Workspace revision: [`e860bfa5`](https://github.com/andreysirak/garmin-ai-analytics/commit/e860bfa5dce29fc839963c56a5c48169ea9ef908).
  The deployed backend commit was unavailable from image metadata; the workspace
  revision must not be presented as a verified deployed commit.
- Model: `qwen2.5:1.5b-instruct-q4_K_M`; Ollama `0.40.16`.
- Model digest: `65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b`.
- Generation: CPU, temperature `0`, context `32768`, output limit `1024`, no streaming.
- Ollama container: limits 2 CPU / 3 GiB; requests 250m CPU / 1536 MiB.
- Nine routing questions and six explanation cases, each repeated three times,
  sequentially. Three frozen evidence objects were each tested with the original
  question and a supported question matching the evidence.
- All calls went directly to local Ollama. No application endpoints or PostgreSQL
  queries were used during this isolated evaluation. It is not an end-to-end test.
- Captured messages and schema were combined with reconstructed deployed
  generation defaults. Original application-to-provider wire bytes were not captured.
- Only one model was installed and evaluated. No comparative model result exists.

## Results

| Stage | Calls | Reported pass | Reported fail | Median | p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Routing | 27 | 6 | 21 | 1.50 s | 3.20 s |
| Explanation | 18 | 0 | 18 | 3.85 s | 13.29 s |

These are per-inference HTTP wall times, including model loading/cache effects,
excluding application and SQL work. p95 uses nearest rank. Routing ranged from
1.18 to 24.02 seconds; explanation from 0.80 to 13.29 seconds. All 45 calls returned
HTTP 200 and finish reason `stop`: protocol completion did not establish quality.

Routing passed 22.2% of runs on this small diagnostic set: two of nine distinct
questions, each repeated three times. Repeated cases are not independent samples
of general production accuracy. Temperature zero did not make every repetition identical.

## Routing

Every response selected `ANALYZE`; none selected `CLARIFY` or `UNSUPPORTED`.

| ID | Question | Expected | Observed | Pass / runs |
| --- | --- | --- | --- | ---: |
| R01 | how productive i was last week ? | Clarify goal | Average steps | 0/3 |
| R02 | Was I productive last week? | Clarify goal | Total steps | 0/3 |
| R03 | How did I perform yesterday? | Clarify goal | Total steps | 0/3 |
| R04 | Do days with more physical activity tend to be followed by longer sleep? | Unsupported relationship | Average sleep; invented 31-day period | 0/3 |
| R05 | Does walking more lead to more sleep the following night? | Unsupported relationship | Sleep average or step total; invented yesterday period | 0/3 |
| R06 | How many steps did I take last week? | Total steps / last calendar week | Matching selection | 3/3 |
| R07 | What was my average resting heart rate last week? | Average daily resting heart rate / last calendar week | Matching selection | 3/3 |
| R08 | Average resting heart rate? | Clarify period | Invented 30- or 31-day period | 0/3 |
| R09 | What was my sleep quality last week? | Unsupported metric | Substituted recorded sleep duration | 0/3 |

A valid executable selection can still misinterpret the user. The experiment does
not isolate model capability from prompt, chat-template or constrained-decoding
effects. It does not prove that a model upgrade alone will fix routing.

## Explanation

All 18 answers failed at least one required collector check. This does **not**
mean every answer fabricated a measurement: missing coverage, partial-total
qualification, exact numeric preservation and explicit dates also affect grading.

| Case | Frozen evidence | Question | Observed failures across repetitions |
| --- | --- | --- | --- |
| E01-original | Incomplete step average | Productivity | Steps substituted for productivity; invented expectations or confused coverage |
| E01-matching | Same step average | Average steps over explicit dates | Main value retained; coverage omitted or null/absent readings confused; one average called a partial total |
| E02-original | Incomplete recorded sleep average | Activity followed by longer sleep | Invented activity groups, comparison baseline and negative relationship from sleep-only evidence |
| E02-matching | Same sleep average | Recorded daily-summary sleep average over explicit dates | Main value rounded; coverage confused or unsupported derived total added |
| E03-original | Incomplete step sum | Steps last week | Main value retained; partial-total and coverage qualifications omitted |
| E03-matching | Same step sum | Total steps over explicit dates | Main value retained; partial-total and coverage qualifications omitted |

Explanation failed even when the question matched the evidence. Its problems are
not solely consequences of routing. A scalar sleep average cannot establish a
next-night relationship; calendar-day sleep duration is not primary overnight sleep.

## Grading caveats

The collector used offline validation, deterministic checks and textual review;
no model graded itself. The grading implementation was not supplied, so the
annotations are not independently reproducible from this report alone.

- `results.json` preserves **reported** grades and check statuses without silently
  correcting them. At least one productivity answer incorrectly received `PASS`
  for acknowledging the question's limitation, despite asserting productivity
  from steps. Other failed checks already made its overall grade `FAIL`.
- Individual checks can be `NEEDS_REVIEW` while an overall answer is `FAIL`.
- Whether dates must appear in prose when the UI displays them is a product
  policy. Rounding a long decimal is not inherently hallucination, though it
  violated the evaluated exact-value instruction.
- A correct main number does not excuse treating an incomplete sum as complete,
  contradicting coverage or inventing analytical conclusions.
- Only three personal evidence objects were tested. Freeze a reviewed rubric
  and add synthetic cases before making broader reliability claims.

## Conclusions and next experiment

Java executes accepted selections and supplies compact evidence, but semantic
routing and explanation groundedness remain inadequate. Mocked unit tests cannot
measure those behaviors. No RAG, MCP or additional agent layer was evaluated.

1. Review grading rules; distinguish policy violations, omissions, contradictions
   and invented claims. Keep uncertain judgments reviewable.
2. Isolate the all-`ANALYZE` behavior using a route-only contract and a controlled
   structured-output versus unconstrained-output comparison. Change one factor
   at a time and record prompt/template/schema versions.
3. Compare a more capable approved model on identical cases and frozen inputs.
   No candidate has been measured; private memory estimates are not sizing evidence.
4. Expand with synthetic complete, missing, null, invalid and zero readings.
5. Expose application-rendered values, units, dates and coverage independently
   from generated commentary; evaluate the latter before treating it as grounded.

Keep #66 open and PR #68 in draft pending owner review. This report is a failure
baseline, not an implementation fix or a completed model comparison.

## Public and private evidence

[`results.json`](results.json) contains per-run routes, timings, reported check
statuses, settings and SHA-256 hashes identifying the reviewed private artifacts.
It is a sanitized historical record, not a public reproduction dataset.

Personal evidence values, raw model explanations containing those values,
request IDs and deployment identifiers are excluded from Git. The owner retains
the original artifacts privately. No private record was labeled synthetic and
no synthetic replacement experiment was performed.

Procedure: [chat diagnostics](../../../garmin-chat/docs/chat-diagnostics.md).
