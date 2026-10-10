# marketing-ops-creative-agency

External A2A companion agent for the Marketing Ops sample. By default, its LLM
selects the business decision and writes the advice through the configured
OpenAI-compatible Chat Completions endpoint. Deterministic mode is an explicit
offline protocol fixture.

Build and run this service from the sample source with Java 21 and Maven 3.9+.
It uses public Spring Boot and A2A SDK dependencies. For customer simulations,
run Astraform separately from the public
[platform v0.2.0 bundle](https://github.com/astraform/releases/releases/tag/v0.2.0)
and follow the [marketing setup guide](../integration/marketing-ops-a2a/README.md#native-customer-simulation).
No private platform source or platform build is required.

## Protocol and runtime

- Java 21
- Spring Boot 4.1.0
- A2A Java SDK 1.2.0.Final
- A2A protocol 1.0 JSON-RPC

This is a latest-only target. Its Agent Card advertises protocol `1.0`, and
every request to `POST /a2a` must include `A2A-Version: 1.0`. The service does
not negotiate an older wire contract.

## Responsibility boundary

The service supports three scenarios:

- `creative-rework`
- `asset-review`
- `launch-blocker`

The partner LLM chooses `scenario`, `disposition`, a summary, one to five
suggested actions and up to four questions. An explicitly requested scenario
constrains the supported operation; otherwise the model selects it. The service
validates the JSON shape, scenario/decision enums, their allowed pairing and size
limits, then renders the model's decision without substituting a keyword rule.
A valid but poor decision remains visible for simulation evaluation.

The decision and advice do not execute campaign work or prove that a blocker is
resolved. `generationContentAuthoritative=false` retains that boundary. It does
not mean the host overrides the model's decision. `generationDecisionSource`
records `llm` or `deterministic`; a model failure produces a failed task, with no
silent template fallback.

Marketing Ops domain state and Scenario Lab evidence remain owned by the sibling
`marketing-ops-remote` provider. Conversation context and A2A tasks are held in
memory, so this is a sample target agent rather than a production system of
record.

## Structured business response for native goal evaluation

`CREATIVE_AGENCY_A2A_RESPONSE_FORMAT=json` wraps a successful response in the
sample-owned [business response schema](src/main/resources/schemas/creative-agency-response.v1.schema.json).
The A2A message still carries a text part; its text is now a JSON object with
`schemaVersion`, `scenario`, `disposition`, `responseText` and `metadata`.
The selected generator supplies the business fields. In the default LLM mode,
`scenario` and `disposition` come from the validated model output.

For the native envelope `astraform.isolated-event-a2a-invocation.v1`, the
service requires the operation selector `scenario: "launch-blocker"` and reads
the observed `campaignKey` and `queueState` from `request.facts`. Native routing
uses that explicit selector, without keyword matching the serialized envelope.
The sample's Astraform target also requires `reviewSummary`. It binds the campaign,
queue state and exact review-record summary to the latest provider observation
before dispatch. The agency extracts the customer instruction
and complete bounded facts before constructing the model input; transport IDs do
not displace the customer's request. Instructions are limited to 8,000 characters
and serialized facts to 16,000; oversized current input is rejected, not silently
truncated. Missing or invalid native fields are rejected.

The LLM receives the observed queue state and the meanings of the allowed
decisions. It can choose `AWAIT_REVIEW`, `NO_EXTERNAL_BLOCKER`, `ESCALATE` or
`UNBLOCK_PLAN` for a launch-blocker request. The host does not force a particular
decision from the queue state. An incorrect recommendation is observable partner
behavior. The existing advice goal checks the actual returned disposition; it is
not an independent judgment of the advice's factual correctness.

Follow-ups include the last five successful customer/agency exchange pairs in
the same context. Each historical side is normalized and bounded to 2,000
characters. Failed turns do not advance this history. This remains in-memory
sample context, not durable long-term memory or RAG.

The default `text` preserves the existing human-readable response. A failed
generation still produces a failed A2A task with its existing failure evidence,
not a success envelope or fallback. Set the format when deploying the agent;
it cannot be requested or changed by customer input.

The [native marketing customer example](../integration/marketing-ops-a2a/README.md#native-customer-simulation)
uses JSON mode so Astraform can evaluate the actual partner receipt. Its goal
is obtaining advice, not proving that the campaign was launched or its blocker
resolved. This is separate from the LLM's structured-output setting, which
controls the agency's internal model call.

## Generation modes

### Deterministic protocol fixture

Explicitly select the fixture when exercising the protocol without a model:

```bash
CREATIVE_AGENCY_GENERATION_MODE=deterministic mvn spring-boot:run
```

### LLM (default)

LLM mode uses one provider-neutral transport:

```text
POST {CREATIVE_AGENCY_LLM_BASE_URL}/chat/completions
```

It deliberately uses only the shared Chat Completions subset: `model`, text system and user
messages, `stream=false`, one configured output-token field, optional
`reasoning_effort`, and an optional standard `response_format`.
The implementation is a Spring `RestClient`, not a
provider SDK. There are no OpenAI, Google, Ollama, or vLLM branches in the code.

Configure the provider and inbound token before startup. Missing LLM configuration
fails startup; it does not switch to the deterministic fixture. Minimum configuration:

```bash
export CREATIVE_AGENCY_GENERATION_MODE=llm
export CREATIVE_AGENCY_LLM_PROVIDER_ID='<operator label>'
export CREATIVE_AGENCY_LLM_BASE_URL='<OpenAI-compatible API base URL>'
export CREATIVE_AGENCY_LLM_AUTH_TYPE=bearer   # or none
export CREATIVE_AGENCY_LLM_API_KEY='<secret>' # omit when auth type is none
export CREATIVE_AGENCY_LLM_MODEL='<model name>'
export CREATIVE_AGENCY_LLM_BINDING_REVISION='<non-secret policy revision>'
export CREATIVE_AGENCY_A2A_TOKEN='<random partner token of at least 32 characters>'
mvn spring-boot:run
```

`CREATIVE_AGENCY_LLM_PROVIDER_ID` is provenance only. It never changes request
behavior. Endpoint, authentication, model, and explicit capability settings are
the actual contract. Provider ID, model, and binding revision accept only ASCII
letters, digits, `.`, `_`, `:`, `/`, and `-` (up to 160 characters).

`CREATIVE_AGENCY_LLM_BINDING_REVISION` is an operator-owned version label for
the endpoint/model/prompt policy. Change it when those semantics change; do not
change it for routine key rotation. The response metadata records the provider
label, requested model, server-reported model when present, binding revision,
completion ID when present, explicitly configured reasoning effort, and standard token counts when present. It never
records the API key or base URL.

Every LLM-backed `POST` also requires `X-Astraform-Agent-Token`. This protects
remote spend and local compute alike. One configured partner token represents
one trusted caller principal; do not share an instance or token across tenants.
Deterministic mode remains public when the token is absent, but setting
`CREATIVE_AGENCY_A2A_TOKEN` protects its `POST` routes in exactly the same way.
This allows offline integration proofs to exercise the real authentication
boundary without calling or paying for a model.

## Provider profiles

These are deployment profiles for the same adapter, not separate product modes.
Every LLM profile also requires a random `CREATIVE_AGENCY_A2A_TOKEN` of at least
32 characters, as shown in the minimum configuration above.

### OpenAI

```bash
export CREATIVE_AGENCY_GENERATION_MODE=llm
export CREATIVE_AGENCY_LLM_PROVIDER_ID=openai
export CREATIVE_AGENCY_LLM_BASE_URL=https://api.openai.com/v1
export CREATIVE_AGENCY_LLM_AUTH_TYPE=bearer
export CREATIVE_AGENCY_LLM_API_KEY="$OPENAI_API_KEY"
export CREATIVE_AGENCY_LLM_MODEL=gpt-5.6
export CREATIVE_AGENCY_LLM_BINDING_REVISION=openai-gpt56-decisions-v1
export CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER=max-completion-tokens
export CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS=4096
```

### Google Gemini through its OpenAI compatibility endpoint

```bash
export CREATIVE_AGENCY_GENERATION_MODE=llm
export CREATIVE_AGENCY_LLM_PROVIDER_ID=google-gemini
export CREATIVE_AGENCY_LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
export CREATIVE_AGENCY_LLM_AUTH_TYPE=bearer
export CREATIVE_AGENCY_LLM_API_KEY="$GEMINI_API_KEY"
export CREATIVE_AGENCY_LLM_MODEL=gemini-2.5-flash
export CREATIVE_AGENCY_LLM_BINDING_REVISION=marketing-agency-gemini25-flash-review-v2
export CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER=omit
```

This supplied Gemini profile omits the output-token field. Select a token
field only when supported by your chosen model/API revision; an omitted field
means `CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS` does not cap that request. The raw
response-byte limit remains enforced locally.

### Ollama on the same machine

Pull the model before starting the sample.

```bash
export CREATIVE_AGENCY_GENERATION_MODE=llm
export CREATIVE_AGENCY_LLM_PROVIDER_ID=ollama
export CREATIVE_AGENCY_LLM_BASE_URL=http://localhost:11434/v1
export CREATIVE_AGENCY_LLM_AUTH_TYPE=none
unset CREATIVE_AGENCY_LLM_API_KEY
export CREATIVE_AGENCY_LLM_MODEL=qwen3.5:4b
export CREATIVE_AGENCY_LLM_BINDING_REVISION=ollama-qwen35-nonthinking-decisions-v1
export CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER=max-tokens
export CREATIVE_AGENCY_LLM_REASONING_EFFORT=none
```

For this local Qwen profile, `none` requests non-thinking generation through
[Ollama's OpenAI compatibility endpoint](https://docs.ollama.com/api/openai-compatibility).
Ollama maps `reasoning_effort=none` to `think=false` for models with boolean
thinking support. This avoids spending the response deadline on hidden reasoning;
it does not guarantee a particular response time. The setting is optional and
omitted by default, preserving the selected provider/model's defaults.

That `localhost` URL is only correct when the sample itself runs directly on
the host with Maven. From the sample's Docker container, `localhost` means the
container—not the host running Ollama. On Docker Desktop use
`http://host.docker.internal:11434/v1`, or use a same-network service hostname
such as `http://ollama:11434/v1`. In either Docker case also set
`CREATIVE_AGENCY_LLM_ALLOW_INSECURE_HTTP=true`.

Non-loopback cleartext HTTP is accepted only with `auth-type=none`; the service
will not send a bearer secret over it.

### vLLM

```bash
export CREATIVE_AGENCY_GENERATION_MODE=llm
export CREATIVE_AGENCY_LLM_PROVIDER_ID=vllm
export CREATIVE_AGENCY_LLM_BASE_URL=http://localhost:8000/v1
export CREATIVE_AGENCY_LLM_AUTH_TYPE=none
unset CREATIVE_AGENCY_LLM_API_KEY
export CREATIVE_AGENCY_LLM_MODEL='<served model ID>'
export CREATIVE_AGENCY_LLM_BINDING_REVISION=vllm-model-decisions-v1
export CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER=max-tokens
```

The `localhost` URL is for a Maven-run sample. From Docker Desktop use
`http://host.docker.internal:8000/v1`, or address a same-network vLLM service by
its Compose hostname, and set `CREATIVE_AGENCY_LLM_ALLOW_INSECURE_HTTP=true`.
The served model must have a working chat template (configured explicitly when
the model does not provide one), otherwise vLLM's Chat Completions endpoint
cannot construct the prompt.

If vLLM is configured with an API key, use HTTPS behind a protected proxy and
switch to `auth-type=bearer`.

## Compatibility controls

“OpenAI-compatible” is a common wire convention, not a complete standard. The
optional request fields are therefore explicit rather than guessed:

| Environment variable | Values | Default | Why it exists |
| --- | --- | --- | --- |
| `CREATIVE_AGENCY_LLM_STRUCTURED_OUTPUT_MODE` | `json-schema`, `json-object`, `prompt-only` | `json-schema` | Model servers differ in structured-output support |
| `CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER` | `max-completion-tokens`, `max-tokens`, `omit` | `max-tokens` | Compatible servers differ; omit an undocumented field instead of guessing |
| `CREATIVE_AGENCY_LLM_REASONING_EFFORT` | Provider-supported value, such as `none`, `low`, `medium`, or `high` | Empty; omitted | Explicitly requests the reasoning mode supported by the deployed model |

`json-schema` sends the standard strict JSON Schema shape. `json-object` sends
the older JSON mode. `prompt-only` omits `response_format` for weaker local
servers. All three paths still parse and validate the exact response locally.
The service never auto-downgrades after a provider rejects a setting.

The prompt puts the agency policy in a `system` message and the customer
instruction, observed facts and bounded exchanges in a separate `user` message. No tools,
provider-specific extensions, arbitrary headers, or temperature are sent.
`reasoning_effort` is sent only when explicitly configured; unsupported values
remain provider errors. Change the binding revision when changing this policy.

For reasoning models, `max_completion_tokens` may include both hidden reasoning
and visible output. Treat `CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS` as the budget
for the selected wire parameter—not a promise that every token is visible—and
validate the value against the chosen model. `finish_reason=length` is rejected
rather than accepted as a complete business response.

Operational settings:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `CREATIVE_AGENCY_LLM_CONNECT_TIMEOUT` | `2s` | Per-attempt connection deadline |
| `CREATIVE_AGENCY_LLM_READ_TIMEOUT` | `15s` | Per-attempt response deadline |
| `CREATIVE_AGENCY_LLM_MAX_ATTEMPTS` | `1` | Total attempts, capped at 3 |
| `CREATIVE_AGENCY_LLM_RETRY_BACKOFF` | `250ms` | Bounded exponential retry backoff |
| `CREATIVE_AGENCY_LLM_MAX_CONCURRENCY` | `4` | Maximum simultaneous model calls |
| `CREATIVE_AGENCY_LLM_MAX_REQUESTS_PER_MINUTE` | `60` | Per-instance upstream attempt cap |
| `CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS` | `800` | Value sent through the selected token-limit parameter; unused with `omit` |
| `CREATIVE_AGENCY_LLM_MAX_RESPONSE_BYTES` | `131072` | Raw upstream response cap |
| `CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS` | `28` | Outer A2A blocking deadline |
| `CREATIVE_AGENCY_A2A_MAX_CONCURRENT_REQUESTS` | `8` | A2A admission limit |

The default is one attempt because a timeout does not prove the provider failed
to generate or bill the request. If retries are enabled, every upstream attempt
counts against the local rate limit. The complete provider timeout/retry budget
must fit inside the outer A2A deadline or startup fails.

Official compatibility references:

- [OpenAI Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [Google Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs)
- [vLLM OpenAI-compatible server](https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/)

## Direct A2A 1.0 smoke

Read the Agent Card:

```bash
curl --fail-with-body \
  -H 'Accept: application/json' \
  -H 'A2A-Version: 1.0' \
  http://localhost:8091/.well-known/agent-card.json \
  | jq '.supportedInterfaces'
```

Send a message:

```bash
curl --fail-with-body \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'A2A-Version: 1.0' \
  --data-binary @- \
  http://localhost:8091/a2a <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "creative-agency-smoke-1",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "parts": [
        {"text": "Rework this paid-social hero around one promise and one CTA."}
      ],
      "messageId": "creative-agency-message-1",
      "contextId": "creative-agency-context-1"
    },
    "metadata": {
      "scenario": "creative-rework",
      "domainId": "marketing-ops"
    }
  }
}
JSON
```

In LLM mode, or whenever `CREATIVE_AGENCY_A2A_TOKEN` is configured, add:

```bash
-H "X-Astraform-Agent-Token: $CREATIVE_AGENCY_A2A_TOKEN"
```

The sample-owned
[`check-creative-agency-a2a-v1-live.sh`](../integration/marketing-ops-a2a/check-creative-agency-a2a-v1-live.sh)
checks the card, sends the operation, and proves a missing version header is
rejected.

## Endpoints and A2A behavior

- `GET /.well-known/agent-card.json`
- `POST /a2a` with `A2A-Version: 1.0`
- `GET /a2a/health`
- `GET /actuator/health`

`CancelTask` is deliberately rejected with `TaskNotCancelableError`. A remote
model call cannot be reliably terminated after admission, so the sample does
not claim cancellation it cannot enforce. Unknown or inaccessible task IDs
return `TaskNotFoundError`. Blocking sends and `returnImmediately=true` are both
supported; admission remains held until execution finishes.

A2A request bodies are capped at 64 KiB. Active work and provider calls are
bounded. Excess work is rejected before the SDK creates task queues.

Default port: `8091`.

## Docker Compose

The repository root Compose file builds the service:

```bash
docker compose up -d --build marketing-ops-creative-agency
```

Export the selected LLM profile before starting Compose. Secrets are runtime
environment values, never Docker build arguments. The published port binds to
loopback by default; change `MARKETING_OPS_CREATIVE_AGENCY_BIND_ADDRESS` only
behind a protected network or gateway.

When the model server runs on the host, replace a profile's `localhost` URL with
`host.docker.internal` on Docker Desktop. When it is another Compose service,
use that service's network hostname. In either case, cleartext non-loopback HTTP
also requires `CREATIVE_AGENCY_LLM_ALLOW_INSECURE_HTTP=true` and `auth-type=none`.

The standalone protocol check can use explicit deterministic mode without
calling a model endpoint. Customer simulations use the LLM configuration above;
passing an offline protocol check does not establish model connectivity or
customer-goal success.

## Connect the partner to Astraform

The [marketing simulation guide](../integration/marketing-ops-a2a/README.md#native-customer-simulation)
provides the complete domain, A2A and response-schema bindings. Add those settings
to `private/runtime.properties` in the extracted platform v0.2.0 bundle. Configure
Astraform's customer model separately in `private/model.env`.

For a host-run agency, Runtime uses `http://host.docker.internal:8091`, not
`localhost:8091`. On Linux, the host service must listen on an interface reachable
from the Docker bridge. Keep the random partner token in deployment configuration;
it is not customer persona text or a scenario fact.

After adding or changing bindings, recreate Runtime from the platform bundle:

```sh
./astraform.sh up -d --wait --force-recreate agent-runtime
```

Update the affected binding revision and use a fresh run. The published SDK
launcher connects through the platform gateway, `http://localhost:8084` by default.
Astraform owns customer execution; this service owns partner responses and its
bounded in-memory A2A context. Restarting the agency loses that context and its
task store. Neither this sample nor its protocol check claims remote exactly-once
execution or durable partner conversation history.
