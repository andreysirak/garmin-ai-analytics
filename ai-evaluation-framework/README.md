# Garmin Chat AI evaluation

The standalone Java 25 runner evaluates the public Garmin Chat HTTP API or an
installed Ollama model. It uses Maven, Jackson and the JDK HTTP client. The
framework has no dependency on the Garmin Chat backend, Spring Boot or Spring AI.

The versioned suite contains 23 synthetic scenarios. There are eight routing
regressions, twelve explanation regressions and three routing holdouts.
[Case catalogue](TEST-CASES.md) · [Technical reference](REFERENCE.md)

## Run offline checks

Build or test this Maven module independently with JDK 25. From the repository root, run `mvn -f ai-evaluation-framework/pom.xml test` or use the Make targets below.

```sh
make chat-eval-test
make chat-eval-component-test
make chat-eval-list
```

The standalone tests cover the fixture oracle, grading, capture, review,
comparison and the two HTTP adapters against local stubs. The backend target
checks production prompts, routing validation, advisor execution and AI flow
with substituted model responses. The optional SQL target uses a separate
isolated PostgreSQL instance.

## Capture a model baseline

Start with Ollama running on loopback and the model already installed.
Remote services need an explicit port-forward. The runner queries version,
installed digest and model metadata, then checks the digest again at completion.

```sh
make chat-eval-model EVAL_OUTPUT=data/evaluations/baseline-001
```

By default this runs 20 regression scenarios three times (60 requests).
Use `EVAL_STAGE=routing` or `explanation` for one stage and
`EVAL_SPLIT=holdout` for reserved routing questions. Keep holdouts out of tuning.

## Capture a black-box application run

Use a dedicated synthetic deployment populated only with invented test
records. Set its clock, backend revision, model and settings for the experiment
before the run. The evaluated path is the public `POST /api/chat` endpoint.
The runner sends only the question and has no database or provider connection
in this mode.

```sh
make chat-eval-application EVAL_OUTPUT=data/evaluations/application-001 \
  EVAL_SYNTHETIC_DATABASE=yes EVAL_DEPLOYMENT_ID=synthetic-backend-COMMIT
```

The acknowledgement records the operator's confirmation of isolation. The
default run sends eight routing questions three times (24 requests).
The backend queries the synthetic database prepared for this deployment.
Use the expected synthetic records and observed answer to review facts,
coverage and behavior. If the evidence is insufficient, leave the affected
checks pending. An independently obtained synthetic trace may help reviewers.

## Review and compare

Build the CLI with `make chat-eval-build` if needed. Copy the template, then
fill in the reviewer, rationale and check values. Use `PASS`, `FAIL` or
`NEEDS_REVIEW` for each required check.

```sh
EVAL_JAR=ai-evaluation-framework/target/ai-evaluation.jar
cp -p data/evaluations/baseline-001/review-template.json data/evaluations/baseline-001/annotations.json
java -jar "$EVAL_JAR" review \
  data/evaluations/baseline-001 data/evaluations/baseline-001/annotations.json \
  --output data/evaluations/baseline-001/reviewed.jsonl

java -jar "$EVAL_JAR" compare \
  data/evaluations/baseline-001 data/evaluations/candidate-001 \
  --baseline-results reviewed.jsonl --candidate-results reviewed.jsonl \
  --output data/evaluations/comparison-001.json
```

Run and review the candidate under the same protocol. For captures with only
deterministic verdicts, omit the `*-results` options to compare raw results.
The Java runner writes capture format v2. Create a new Java baseline before
comparing candidates. Historical Python format v1 captures remain historical
evidence.

CLI exit **0** means all run/review trials passed or the comparison detected
no regressions, missing/added trials or inconclusive results. Exit **2** reports
outstanding findings. Exit **1** indicates a setup or protocol error. An
unchanged failure can appear in a clean comparison, so read the status counts.

## Settings

| Make variable | Default | Purpose |
| --- | --- | --- |
| `EVAL_OUTPUT` | Required | New capture directory under `data/evaluations/` |
| `EVAL_MODEL` | `qwen2.5:1.5b-instruct-q4_K_M` | Installed Ollama model tag |
| `EVAL_OLLAMA_URL` | `http://127.0.0.1:11434` | Model endpoint |
| `EVAL_APPLICATION_URL` | `http://127.0.0.1:8081` | Synthetic application endpoint |
| `EVAL_STAGE` | `all` | Model mode stage, `routing`, `explanation` or `all` |
| `EVAL_SPLIT` | `regression` | `regression`, `holdout` or `all` |
| `EVAL_REPETITIONS` | `3` | Trials per case, 1 to 20 |
| `EVAL_TIMEOUT` | `120` | HTTP deadline in seconds, through response body |
| `EVAL_TEMPERATURE` | `0` | Ollama sampling temperature |
| `EVAL_NUM_CTX` | `32768` | Ollama context window |
| `EVAL_NUM_PREDICT` | `1024` | Ollama output token limit |
| `EVAL_NUM_GPU` | `0` | Ollama GPU offload option |
| `EVAL_SYNTHETIC_DATABASE` | `no` | Set to `yes` to attest application isolation |
| `EVAL_DEPLOYMENT_ID` | Required in application mode | Synthetic deployment and revision identifier |

Use `java -jar "$EVAL_JAR" --help` for the full list of CLI options.
Keep all outputs private and use only synthetic inputs.
