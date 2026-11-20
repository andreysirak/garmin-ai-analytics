# Chat configuration reference

[Chat overview](../README.md) · [Setup](setup.md) · [Analytics reference](analytics-reference.md)

[Chart defaults](../../infrastructure/helm/garmin-chat/values.yaml) configure:

| Setting | Default / meaning |
| --- | --- |
| `chat.timezone` | `Europe/Oslo`; IANA timezone for relative dates |
| `chat.responseTokens` | 1024; answer token limit and reserved context space |
| `chat.maxQueryDays` | 31 inclusive calendar days; rejects larger ranges before PostgreSQL |
| `chat.modelTimeoutSeconds` | 300 seconds per Ollama HTTP call |
| `chat.executionTimeoutSeconds` | Total backend deadline. Defaults to two model timeouts plus 60 seconds (660 seconds with default settings). |
| `ollama.contextLength` | 8192; used by both Spring AI and Ollama |
| `ollama.model` | `qwen2.5:1.5b-instruct-q4_K_M` |
| `ollama.resources` | CPU request 250m / limit 2; memory request 1536Mi / limit 3Gi |
| `database.existingSecret` | `garmin-chat-database` |
| `database.profileIdKey` | `profile-id` |

The model is a small [quantized Qwen model](https://ollama.com/library/qwen2.5:1.5b-instruct-q4_K_M)
on pinned [Ollama 0.40.16](https://github.com/ollama/ollama/releases/tag/v0.40.16).
Inference uses CPU, one loaded model, one concurrent inference and one queued
request. Requests/limits/model are configurable. Keep the configured context
within the selected model's supported window and available memory.

Ollama publishes a [tool-enabled template for this exact tag](https://ollama.com/library/qwen2.5:1.5b-instruct-q4_K_M/blobs/eb4402837c78).
The installed model must advertise `tools` through `/api/show`. The library
template and capability check establish support for the protocol. Accuracy of
tool selection, arguments and the final answer still needs live verification.

CPU inference can take time even with compact evidence. A supported question can
require two inference turns: routing and evidence explanation. These calls, the
capability check and database work share the existing backend deadline. Helm sets the frontend
proxy timeout 30 seconds above that deadline.

Direct backend runs configure the HTTP read timeout with
`OLLAMA_REQUEST_TIMEOUT` (default `300s`) and the independent application
deadline with `GARMIN_CHAT_EXECUTION_TIMEOUT` (default `660s`). If you override
the transport timeout, allow time for up to two turns, capability checks and SQL in
the application deadline. Set any external proxy timeout above the application
deadline. Timeouts return HTTP 503 with a retry hint.

Optional deployment overrides:

```sh
cp garmin-chat/values.example.yaml garmin-chat/values.local.yaml
```

The local file is ignored by Git and optional. It can change model, context,
resources, timezone and response reserve. It contains no fixed dates or personal
profile ID. Existing overrides are preserved. Offline checks do not deploy workloads.


## Sources of truth

This is the single prose reference for chat configuration defaults. The executable
sources take precedence: [Helm values](../../infrastructure/helm/garmin-chat/values.yaml)
for Kubernetes deployments and [application.yaml](../backend/src/main/resources/application.yaml)
for direct backend runs. Update this page alongside changes to those defaults.
Do not copy the settings table into setup, testing or overview pages.

The profile and database password are supplied through the existing Secret, not
Helm values. See [reader credentials](setup.md#reader-login-and-secret-manual-setupreview).
