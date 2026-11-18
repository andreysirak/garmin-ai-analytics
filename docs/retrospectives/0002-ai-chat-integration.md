# Retrospective 002 — AI Chat Integration (PR #57)

**Scope:** [PR #57 — Build the AI explanation for Garmin daily summaries](https://github.com/andreysirak/garmin-ai-analytics/pull/57) (implements [#41](https://github.com/andreysirak/garmin-ai-analytics/issues/41)).

## What worked

- Delivered a first vertical slice connecting React, Spring Boot/Spring AI, local Ollama, read-only PostgreSQL and the local kind deployment.
- Kept arithmetic in predefined, parameterized PostgreSQL queries. The LLM extracts structured intent and explains compact evidence rather than generating SQL or calculating from raw JSONB.
- Added validation, bounded metrics and dates, missing-data coverage, tests and understandable error messages.
- Used incremental AI-agent tasks to investigate, implement and verify components, with the project owner responsible for architecture, review, manual testing and Git decisions.
- Tested with actual Garmin data. Investigation showed that daily-summary `sleepingSeconds` is clipped to calendar-day windows and is unsuitable as a substitute for full overnight sleep in next-day correlation. This prevented a misleading analytical result.

## What didn't work

- The integration PR expanded beyond its original goal into model deployment, backend/query logic, frontend, Helm/Make tooling and testing. At the time of this retrospective it contained **7 commits and 80 changed files**; these are a snapshot, not a quality metric by themselves.
- The project owner had already noted in the PR discussion that frontend, backend and AI implementation should have been split. Faster AI-assisted implementation made further scope expansion feel deceptively inexpensive.
- The human review, integration and real-world verification workload grew with the PR. Passing mocked model tests did not guarantee natural-language reliability with a real local model.
- A live question — “How active have I been over the past week?” — returned an inappropriate date-validation message. This remains a follow-up and should not be described as solved.
- The **original intended demo question** — whether more active days tend to be followed by longer sleep — was not delivered within this PR. Investigation revealed it requires **additional overnight sleep staging and explicit date alignment**, not just a new LLM prompt. We chose to stabilize and merge the smaller summary-only foundation rather than misrepresent the intended demo as complete.

A large PR can be a pragmatic choice for a first demonstrable vertical slice. The lesson is to stop expansion when that slice is achieved and track improvements separately.

## How I worked with AI agents

I defined the product goals, architecture, supported query rules and acceptance criteria. AI agents helped with implementation, tests, documentation and technical investigations. I challenged proposed designs, reviewed the resulting changes, ran manual questions using actual data and retained responsibility for the implementation and the decision to merge. AI assistance accelerated coding but did not remove the need for human ownership of correctness, data meaning, integration and review.

## How I would split this work next time

1. **Infrastructure/model bootstrap:** Ollama deployment, configuration, startup and isolated smoke tests.
2. **Backend data contract:** read-only SQL, allowlisted metrics/aggregations, coverage and independently verified database tests.
3. **Frontend/API integration:** React chat, request/response contract, validation and error rendering.
4. **LLM integration:** structured intent, validation boundary, grounded explanation and real-model tests.
5. **Evaluation and performance:** natural-language regression suite, measurements and targeted optimization in follow-up PRs.

Each stage should have an explicit scope, review checkpoint, definition of done and an exit condition. New ideas discovered along the way should usually become linked issues instead of silently growing the active PR.

## Actions agreed

1. Treat PR #57 as the **initial integration baseline**, subject to final review, committed-state checks, CI requirements and the owner's merge approval; do not claim the AI experience is complete.
2. Continue intent quality, relative-date resolution, ambiguity handling and real-model evaluation in [#66](https://github.com/andreysirak/garmin-ai-analytics/issues/66).
3. Benchmark cold/warm local Ollama and end-to-end request latency before optimizing in [#67](https://github.com/andreysirak/garmin-ai-analytics/issues/67).
4. Keep owner-led cleanup/refactoring separate in [#62](https://github.com/andreysirak/garmin-ai-analytics/issues/62); defer conversation memory [#61](https://github.com/andreysirak/garmin-ai-analytics/issues/61).
5. Carry forward the **original demo question that was deferred from PR #57** — **“Do days with more physical activity tend to be followed by longer sleep?”** — as the outstanding relationship-analysis acceptance goal, not a steps-count demo. First stage overnight sleep records and pair activity on day N with sleep ending on N+1; coordinate with the sleep data foundation in [#17](https://github.com/andreysirak/garmin-ai-analytics/issues/17). Calculate correlation in validated SQL, report coverage and avoid causal claims.
6. Require documented manual verification using **real Ollama responses**, plus SQL comparison, alongside mocked automated tests before calling a demo scenario reliable.

## Follow-up

See [PR #57's merge-decision comment](https://github.com/andreysirak/garmin-ai-analytics/pull/57#issuecomment-6976042635) and [CONTRIBUTING.md](../../CONTRIBUTING.md). This retrospective documents decisions and lessons learned; it does not replace the outstanding implementation, verification or review tasks.
