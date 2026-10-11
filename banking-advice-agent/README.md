# Banking advice agent and customer simulation

The first image-only Python banking starter is prepared under
[`deploy/banking-starter`](../deploy/banking-starter/README.md). It reuses this agent,
the Python bank and the published SDK for launch, inspection and evidence export.
The starter images have been published to private registry packages; a public
starter archive is not yet available. The source workflow below uses the public
platform bundle and published SDKs.

The partner owns the banking database and populates customer records before
creating Astraform customers. Astraform receives each customer's persona, goals,
permitted MCP tools and external customer references. Customer and partner use
that same banking backend with different access:

- The customer model calls the granted `get_customer_accounts({})` MCP tool. The
  runtime supplies the customer's identity outside the model's arguments.
- This partner agent reads the banking API for the customer associated with the
  incoming conversation. It can serve different customers through that API.
- Astraform retains tool results, messages and execution evidence. This path
  neither sends account snapshots to the bank nor preloads banking records into
  the customer prompt.

The customer uses the model selected by Astraform's deployment binding, such as
Ollama or Gemini. Download the [platform v0.2.0 bundle](https://github.com/astraform/releases/releases/tag/v0.2.0)
and initialize the selected profile using its included README. No platform source
checkout or source build is required.
**Both actors use an LLM:** Astraform runs the customer model; this separately
configured partner model answers the customer's actual question using bank records.
The partner uses the official A2A Python SDK 1.1.4 and returns immediate A2A 1.0
Messages. It cannot execute payments or mutate accounts. There is no deterministic
answer mode or canned-answer fallback; missing model configuration prevents startup.
Asynchronous A2A tasks are unsupported.

The bank lookup remains application-controlled: an explicit `statementPeriod`
selects a closing month, otherwise it retrieves the latest issued statement.
The LLM reads the question, selected statement, current accounts and bank as-of
time, then writes the answer. It does not autonomously choose another lookup or
parse a month from free text. If the question and selected month disagree, it is
instructed to explain the mismatch rather than relabel the bank record.

Each partner answer sees **only this request and its current bank records**.
The partner does not retain conversation history; reusing an A2A context ID does
not supply earlier exchanges. Astraform's customer memory remains separate.
General multi-turn partner memory and model-selected bank tools are not implemented
by this sample.

The sample services below run alongside platform v0.2.0; they are not included
in the platform images. The configuration uses the reference-state domain profile to attach a
prepopulated bank customer and generate monthly statements in that same bank
database. It starts before the first statement exists. This is a bounded monthly-statement example,
not evidence of years of simulation or 1,000-customer capacity. The older
snapshot-based provider profile remains separate.

The Java bank provider uses published [Java SDK 0.4.2 from Maven Central](https://central.sonatype.com/artifact/ai.astraform/remote-domain-author-kit-java/0.4.2).
The Python bank provider and this scenario's launch helper use published
[Python author kit 0.3.2](https://pypi.org/project/astraform-remote-domain-author-kit/0.3.2/).
Both SDKs use [shared contract 1.2.0](https://github.com/astraform/remote-domain-contracts/releases/tag/v1.2.0)
and include domain helpers plus the platform client. Customer account reads use
the bank's MCP endpoint; this separately running partner agent uses A2A plus
the banking HTTP API.

## Start the bank and partner

Start the [banking backend and PostgreSQL database](../java-spring-banking/README.md#partner-owned-account-database-and-customer-mcp-access)
on port 8098. For this scenario, enable its reference-state profile; from the
`java-spring-banking` directory:

```bash
docker compose -f compose.database.yaml up -d --wait
BANKING_BACKEND_ENABLED=true \
BANKING_DB_URL=jdbc:postgresql://localhost:5544/banking \
  mvn -B -ntp spring-boot:run \
  '-Dspring-boot.run.arguments=--server.port=8098 --astraform.native-simulation.enabled=true --astraform.native-simulation.profile=remote-domain.native-reference-state.v1'
```

Alternatively, start the [Python bank and its separate PostgreSQL database](../python-fastapi-banking/README.md#native-customer-simulation-with-a-separate-bank-database)
on port `8092`. Both expose the same setup/read API and customer MCP tool. Point
this partner's `BANKING_BACKEND_URL` at the selected bank.
Then configure this partner from this directory with Python 3.12–3.14 and `uv`.
These settings are separate from Astraform's customer-model profile. Load your
existing Gemini key into `GEMINI_API_KEY` through your local secret configuration;
do not put it in a tracked file:

```bash
export BANKING_LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
export BANKING_LLM_MODEL=gemini-2.5-flash
export BANKING_LLM_AUTH_TYPE=bearer
export BANKING_LLM_API_KEY="$GEMINI_API_KEY"
export BANKING_LLM_BINDING_REVISION=bank-gemini25-flash-v1
export BANKING_LLM_REASONING_EFFORT=none
```

This partner example uses `gemini-2.5-flash`. The platform bundle's customer
profile independently defaults to `gemini-3.6-flash`; the two need not match.

The same client can be configured for Ollama's OpenAI-compatible endpoint:

```bash
export BANKING_LLM_BASE_URL=http://localhost:11434/v1
export BANKING_LLM_MODEL=qwen3.5:4b
export BANKING_LLM_AUTH_TYPE=none
unset BANKING_LLM_API_KEY
export BANKING_LLM_BINDING_REVISION=bank-ollama-qwen35-v1
export BANKING_LLM_REASONING_EFFORT=none
```

That is a configuration example, not a fresh Ollama acceptance result for this
partner. Model servers differ; this sample sends `max_tokens` and optional
`reasoning_effort`, and expects a non-streaming chat-completion text response.
See [Gemini compatibility](https://ai.google.dev/gemini-api/docs/openai) and
[Ollama compatibility](https://docs.ollama.com/api/openai-compatibility).
Choose one model configuration, then start the service:

```bash
uv sync --locked
BANKING_BACKEND_URL=http://127.0.0.1:8098 \
BANKING_CUSTOMER_BINDING_KEY=banking \
BANKING_ADVICE_AGENT_URL=http://host.docker.internal:8099 \
  uv run --locked uvicorn app:create_app --factory --host 0.0.0.0 --port 8099
```

`BANKING_BACKEND_URL` is the banking HTTP(S) origin as reached by this process.
`BANKING_CUSTOMER_BINDING_KEY` selects the matching reference in an incoming
request; it defaults to `banking`. `BANKING_ADVICE_AGENT_URL` is the origin
advertised to A2A callers. Use a hostname reachable by Agent Runtime.

The Agent Card is at `/.well-known/agent-card.json`, JSON-RPC at `/a2a`, and
health at `/health`. Health must report `mode: bank-backend`, `responseMode: llm`,
the configured `model` and `modelBindingRevision`, and `conversationMemory: request-only`.
Health confirms service/configuration availability; it does not call the model or
prove provider readiness. Authentication remains deferred for the private-network development setup;
customer references are trusted host assertions, not credentials.


## Partner response and model limits

`BANKING_LLM_BASE_URL`, `BANKING_LLM_MODEL` and `BANKING_LLM_BINDING_REVISION` are
required. Authentication defaults to `bearer`, which requires `BANKING_LLM_API_KEY`;
`none` requires the key to be unset. Restart the partner after a model/configuration
change and update the Astraform A2A target binding revision before launching a new run.

| Setting | Default | Meaning |
| --- | --- | --- |
| `BANKING_LLM_TIMEOUT_SECONDS` | `120` | Total model queue, connection and response deadline, 1–600 seconds |
| `BANKING_LLM_MAX_OUTPUT_TOKENS` | `2048` | Provider output cap, 1–8192 tokens |
| `BANKING_LLM_MAX_CONCURRENCY` | `4` | Maximum concurrent model calls per process, 1–32 |
| `BANKING_LLM_REASONING_EFFORT` | omitted | Optional provider-supported setting |
| `BANKING_LLM_DIAGNOSTICS` | `false` | Include actual model request and response in service logs |

The guarded bank read has a separate ten-second deadline. Configure Astraform's
`simulation.event.runtime.a2a.request-timeout` above the bank + model deadlines;
use `150s` with the defaults, and raise it if using a slower local model. This is
separate from Astraform's customer-model timeout. The model is called once per
valid request, with no internal retry. Network/provider errors, truncation, refusal,
tool calls, empty answers and answers over 4096 characters produce an A2A error;
no successful canned answer is substituted. Upstream responses are bounded to
131072 bytes. Use the actual model output to assess quality; prose is not repaired
or automatically proven truthful.

The model's text is returned verbatim in `message`. The existing JSON fields
`decision`, `missingFields`, `statement`, `statementPeriod` and `bankingContext`
are produced from the retrieved records. **`decision` classifies statement
availability/payment status; it is not the model's decision or an answer-quality
score.** For example, an answer about credit limit can accompany `need_information`
when no complete statement exists. Existing simulation receipt conditions inspect
those fields, so a matched goal is not by itself proof that the explanation is good.

Each model attempt logs its message/context identity, model/revision, latency,
result and usage when the provider supplies it. For local investigation enable
`BANKING_LLM_DIAGNOSTICS=true` to retain actual prompts and raw model responses in
the partner service log. These contain customer data; keep them private. API keys
and authorization headers are never part of these diagnostics. Normal A2A evidence
retains the actual answer. This sample has no persistent partner attempt database.

## Configure Astraform's endpoints

Initialize the extracted platform bundle with `./init.sh --model gemini` or
`./init.sh --model ollama`, and configure the customer's model in
`private/model.env`. The supplied binding key is `local-customer`; keep the
published customer's `execution.modelBindingKey` aligned with it. Follow the
bundle's instructions for model installation, memory and timeouts. Model settings
belong in that file, separately from the partner's `BANKING_LLM_*` settings.

Add the following connections to the bundle's `private/runtime.properties`.
These are deployment settings, not customer persona instructions. This example
runs the bank and partner on the Docker host, so Runtime uses
`host.docker.internal`. On Linux, services must listen on a host interface
reachable from Docker; use reachable private addresses for services elsewhere.

The launch's wall-time goal deadline stops new model admission. An already
admitted call may settle under its invocation timeout; simulated time is
independent of these limits. Keep the provider HTTP timeout within the outer
customer invocation timeout, and allow the A2A timeout to cover the partner's
own bank lookup and model call.

```properties
simulation.event.runtime.mcp.bindings.banking.endpoint=http://host.docker.internal:8098/mcp
simulation.event.runtime.mcp.bindings.banking.binding-revision=banking-mcp-v1
simulation.event.runtime.mcp.bindings.banking.allowed-tool-names=get_customer_accounts
simulation.event.runtime.mcp.bindings.banking.allow-insecure-http=true
simulation.event.runtime.domain.bindings.banking-reference.base-url=http://host.docker.internal:8098
simulation.event.runtime.domain.bindings.banking-reference.domain-id=banking
simulation.event.runtime.domain.bindings.banking-reference.binding-revision=banking-reference-v1
simulation.event.runtime.domain.bindings.banking-reference.allow-insecure-http=true
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.request-timeout=150s
simulation.event.runtime.a2a.targets.banking-advice.base-url=http://host.docker.internal:8099
simulation.event.runtime.a2a.targets.banking-advice.binding-revision=bank-db-advice-v5-llm-gemini25-flash
simulation.event.runtime.a2a.targets.banking-advice.allow-insecure-http=true
```

Set the bank-backed target's `input-schema-json` to
[`bank-backend.input.schema.json`](bank-backend.input.schema.json). This contract
asks for the customer's question and permits an optional `statementPeriod`
closing-month selector; it does not advertise legacy supplied statement fields.
The optional `astraformCustomerContexts` field is reserved for
the host to add before dispatch, and is not a customer input requirement.
Generate its `.properties` line here; properties files require doubled JSON
backslashes, whereas environment variables use compact JSON directly:

```bash
python3 - <<'PY'
import json
from pathlib import Path
schema = json.dumps(json.loads(Path('bank-backend.input.schema.json').read_text()), separators=(',', ':'))
print('simulation.event.runtime.a2a.targets.banking-advice.input-schema-json=' + schema.replace('\\', '\\\\'))
PY
```

Append the printed schema line to `private/runtime.properties` alongside the
bindings above. From the extracted platform bundle, load the configuration:

```bash
./astraform.sh config --quiet
./astraform.sh up -d --force-recreate --wait agent-runtime
```

Start the complete bundle first if this is a fresh installation. Existing frozen
runs are not changed by a new binding revision or schema; launch a fresh run.

### Python banking endpoint bindings

Start the [Python bank and its separate database](../python-fastapi-banking/README.md#native-customer-simulation-with-a-separate-bank-database)
on port `8092`. After configuring `BANKING_LLM_*` as above, start a separate
banking partner from this directory; port `8101` lets the Java partner remain on
`8099` if both are running:

```bash
uv sync --locked
BANKING_BACKEND_URL=http://127.0.0.1:8092 \
BANKING_CUSTOMER_BINDING_KEY=banking-python \
BANKING_ADVICE_AGENT_URL=http://host.docker.internal:8101 \
  uv run --locked uvicorn app:create_app --factory --host 0.0.0.0 --port 8101
```

Add the following to the bundle's `private/runtime.properties`. The keys must match the
[Python launch overrides](#shared-java-and-python-scenarios); the domain ID is
`banking-python`. Adjust hostnames/ports to addresses reachable from Runtime.

```properties
simulation.event.runtime.mcp.bindings.banking-python.endpoint=http://host.docker.internal:8092/mcp
simulation.event.runtime.mcp.bindings.banking-python.binding-revision=banking-python-mcp-v1
simulation.event.runtime.mcp.bindings.banking-python.allowed-tool-names=get_customer_accounts
simulation.event.runtime.mcp.bindings.banking-python.allow-insecure-http=true
simulation.event.runtime.domain.bindings.banking-python-reference.base-url=http://host.docker.internal:8092
simulation.event.runtime.domain.bindings.banking-python-reference.domain-id=banking-python
simulation.event.runtime.domain.bindings.banking-python-reference.binding-revision=banking-python-reference-v1
simulation.event.runtime.domain.bindings.banking-python-reference.allow-insecure-http=true
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.request-timeout=150s
simulation.event.runtime.a2a.targets.banking-python-advice.base-url=http://host.docker.internal:8101
simulation.event.runtime.a2a.targets.banking-python-advice.binding-revision=bank-python-advice-v2-llm-gemini25-flash
simulation.event.runtime.a2a.targets.banking-python-advice.allow-insecure-http=true
```

Generate and append the same tracked bank-backed input schema under the Python
target key, using the earlier recipe:

```bash
python3 - <<'PYSCHEMA'
import json
from pathlib import Path
schema = json.dumps(json.loads(Path('bank-backend.input.schema.json').read_text()), separators=(',', ':'))
print('simulation.event.runtime.a2a.targets.banking-python-advice.input-schema-json=' + schema.replace('\\', '\\\\'))
PYSCHEMA
```

Append the printed schema line to the same `private/runtime.properties` file,
then recreate Runtime from the extracted bundle with
`./astraform.sh up -d --force-recreate --wait agent-runtime`.
The launcher does not install these bindings or configure the partner service.

## Customer configuration and launch

[`sample-configuration.json`](sample-configuration.json) contains the existing
bank seed, Catalog create body and simulation launch body. The customer is Avery,
a practical credit-card customer who wants to understand the repayment on their
upcoming statement and their credit limit. The model decides its own actions
and messages.

Partner-authored `role`, `purpose`, `goals`, `constraints` and
`additionalInstructions` describe a person, their objectives and what banking
services they can use, in ordinary language. They contain no protocol IDs,
function names, JSON paths, response formats or instructions about Runtime
state. They do not prescribe a sequence of account lookups, conversations or
waiting.

Capability configuration is separate: `draft.tools` grants and pins the permitted
account lookup, deployment bindings describe the available services, and launch
configuration binds the customer and sets simulation limits. Agent Runtime owns
the model-facing protocol instructions, capability schemas, action format and
interpretation of actual returned results. Partners should not copy those
technical instructions into a persona to make a scenario run.

The configuration uses:

| Configuration | Meaning |
| --- | --- |
| `draft.role`, `purpose`, `goals`, `constraints`, `additionalInstructions` | Partner-authored persona, objectives and awareness of available services, in ordinary language |
| `draft.execution.modelBindingKey: local-customer` | Deployment-selected customer model |
| `draft.tools` | Pinned read-only MCP grant for `get_customer_accounts`, at most 2 calls |
| `bankCustomerSeed` | 15 January bank records: AUD 1,200 credit-card debt, AUD 3,000 limit, no issued statements |
| `toolContexts` | `banking` binding, a fresh bank context for each independent run, `customer-a` bank customer |
| `domain` | `banking-reference` reference-state provider, selected `banking` tool context, monthly `credit_card_statement_close`, at most 16 domain operations |
| `partnerTargetAgentKey: banking-advice` | Permitted external A2A partner |
| `logicalTimeEpochMs` | 15 January 2026 UTC inclusive starting time, matching the bank seed |
| `logicalHorizonEpochMs` | 15 March 2026 UTC upper bound, not a promise of reaching that date |
| `completionPolicy` | Goal index 0, actual partner payment advice for this customer; limits of 10 customer decisions, 4 partner interactions, 10 wall-time minutes and 3 repeated exchanges |

`domain.personaConfiguration` is empty. Bank records are created by the partner
API first. `domain.toolContextBindingKey: banking` selects the same immutable
customer reference used by the MCP tool and partner lookup. The provider must
advertise `remote-domain.native-reference-state.v1`,
`TRANSACTIONAL_REFERENCE_STATE`, and `ATTACH_PREPOPULATED_STATE`.

The next monthly boundary is 1 February. The bank applies its existing statement
rule to the stored AUD 1,200 debt: a 4% minimum of AUD 48, due 14 February. Those
future statement values are not supplied to the customer at launch. The domain
event notifies the customer when new information is available; account details
come from subsequent model-selected MCP calls or the partner's bank lookup.
The scheduler advances to due events rather than broadcasting a world clock.
The model may finish early or exhaust a limit; the sample does not force waiting
or guarantee that it reaches February.

With the bank, partner and platform running, `run-customer.py` uses the platform
client included in the **existing Astraform author kit** for tool discovery,
customer creation/publication and simulation launch. The same author-kit package
provides domain authoring; the bank seed still uses the partner's own HTTP API.

Install the published author kit in the launcher's Python 3.12+ virtual environment:

```bash
python3 -m pip install 'astraform-remote-domain-author-kit==0.3.2'
```

No SDK source checkout or locally built wheel is needed. The Python bank installs
the same version with the `[fastapi]` extra through its own project dependency
lock; the launcher does not require that extra. Conformance remains separate
testing tooling. See the [SDK dependencies](../README.md#sdk-development).

Use the updated platform gateway with canonical `/api/...` routing:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
BANKING_BACKEND_URL=http://localhost:8098 \
  python3 run-customer.py
```

[`run-customer.py`](run-customer.py) does these steps once:

1. Populate a fresh partner bank context from `bankCustomerSeed` using
   `PUT /api/banking/v1/contexts/{contextRef}/customers/customer-a`.
2. Discover the current tool pins with
   `GET /api/simulation-events/mcp-capabilities?bindingKey=banking`.
3. Create and publish the persona with `/api/agent-definitions` and
   `/api/agent-definitions/{catalogAgentId}/publish`.
4. Print the exact launch request, then launch that immutable revision once with
   `POST /api/agent-journeys` and print its IDs and accepted response. The script sends
   no subsequent customer turns or domain operations. Keep stdout and errors as
   the attempt's record; failures are not retried or repaired by the helper.

`ASTRAFORM_API_BASE` retains a development default of `http://localhost:8484`.
Set it explicitly to the released bundle origin, `http://localhost:8084`, as in
the examples above, or to your selected dashboard port. Do not append `/api`. The helper also accepts
the older `/api/dashboard` and `/api` suffixes by removing them before configuring
the generated client.
`BANKING_DOMAIN_BINDING_KEY` defaults to `banking-reference`;
`BANKING_CUSTOMER_BINDING_KEY` defaults to `banking`.
`BANKING_SUBJECT_REF` defaults to `customer-a`.
`BANKING_PARTNER_TARGET_AGENT_KEY` optionally selects another configured A2A target;
omitting it preserves the scenario's `banking-advice` target. This changes routing
only, not the separately running partner service's backend URL.
`BANKING_SEED_FILE` can override the seed with another partner-authored bank
record; its logical time must match the launch start. The older February bank
examples do not match this January scenario.

`BANKING_CONTEXT_REF` defaults to a new `bank-statement-<uuid>` context. Each
independent simulation needs its own bank state: an attached context belongs to
one domain execution and cannot be reused by a second run. Identical setup
requests are idempotent, but that does not reset advanced accounts. An explicit
context override must name a fresh context. Each script invocation publishes
and launches a new Astraform customer; these remain separate single-customer
runs, not proof of a shared multi-customer platform run.

## An issued statement and a payment budget

[`issued-statement-configuration.json`](issued-statement-configuration.json) gives
Avery one practical question: understand the required payment and due date for an
already-issued January statement, and whether a personal AU$100 budget covers the
minimum payment and repays the full balance. Avery chooses its own questions,
account lookups and follow-ups; the scenario does not prescribe a conversation.

The bank seed reuses the existing issued-statement fixture: AU$1,200 credit-card
debt, an AU$48 minimum payment, a 31 January statement end and a 14 February due
date. Both the bank and customer start on 5 February 2026 UTC. The horizon equals
the start, and the launch omits `domain`, so this scenario has no native monthly
statement scheduling or simulated-time advance. The AU$100 budget is a personal
customer fact; account amounts and dates come from the bank, not initial customer
memory. The customer can ask for clarification, but cannot make or schedule a
payment, and the records do not establish interest, APR or transaction history.

Run it from this directory with the same public-API launcher:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
BANKING_BACKEND_URL=http://localhost:8098 \
BANKING_SAMPLE_FILE=issued-statement-configuration.json \
  python3 run-customer.py
```

The launcher preserves an omitted `domain` rather than adding one. Existing
monthly scenarios retain their domain configuration. The current platform
requires a bounded `GOAL` completion policy for the scoped account tool: this
case allows two account lookups, four partner interactions, ten customer
decisions, three repeated exchanges and ten minutes of wall-time admission.

**The completion conditions verify statement records, not understanding.** They
match the actual partner receipt's schema, payment status, customer, account,
bill, statement end, minimum, currency, due date and closing amount against the
seed. A matching receipt can end the run after the customer reaction without
requiring the customer to choose `FINISH`. It does not prove the partner explained
whether AU$100 covers the minimum while leaving debt, or that Avery understood
that explanation. Assess those points separately from the actual partner prose
and customer reaction; retain contradictions, missing answers and failures.
Several turns may be useful, but a particular number of turns is not guaranteed.
No answer text, customer decision or payment action is scripted.

## Shared Java and Python scenarios

Use these same three files for either bank implementation:

| Configuration | Scenario | Participation |
| --- | --- | --- |
| `issued-statement-configuration.json` | Already-issued January statement and AU$100 payment budget | Omitted policy preserves early goal/conversation stopping; no domain advance |
| `sample-configuration.json` | Upcoming statement and customer account lookup | Omitted policy preserves early stopping; monthly domain work is available |
| `three-month-configuration.json` | January–March statement review | `THROUGH_HORIZON` keeps the customer eligible through April 2 |

For a Java/Python comparison, keep persona, goals, initial records, allowed tools,
model settings, limits and logical dates identical. Each run gets fresh bank,
Catalog and request IDs, and discovery pins the selected provider's capabilities.
Only backend URLs and deployment binding identities differ. Customer decisions
and conversation text can differ because both actors use a model; preserve those
differences and failures. Compare account scope, statement periods, scheduling,
configured goal checks, participation and exported evidence rather than expecting
identical transcripts or forcing the same answer.

Use the [Python banking endpoint bindings](#python-banking-endpoint-bindings)
above; keep separate Runtime bindings if both banks are deployed. The domain
binding must use `domain-id=banking-python`, and both its base URL and the MCP
endpoint must reach that Python bank. The A2A target must
reach an instance of the existing LLM banking partner whose `BANKING_BACKEND_URL`
also points to Python. For example, with bindings named `banking-python`,
`banking-python-reference` and `banking-python-advice` already configured:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
BANKING_BACKEND_URL=http://localhost:8092 \
BANKING_CUSTOMER_BINDING_KEY=banking-python \
BANKING_DOMAIN_BINDING_KEY=banking-python-reference \
BANKING_PARTNER_TARGET_AGENT_KEY=banking-python-advice \
BANKING_SAMPLE_FILE=issued-statement-configuration.json \
  python3 run-customer.py
```

The launcher does not create deployment bindings or change the partner service's
configuration. Start that partner with `BANKING_CUSTOMER_BINDING_KEY=banking-python`
as well, so its trusted customer lookup selects the same binding. Keep separate
bank records for each comparison run. Inspect each run independently; the shared
configuration alone is not evidence that both runs passed.

## Three monthly statements

[`three-month-configuration.json`](three-month-configuration.json) asks Avery to
understand repayment for the January, February and March 2026 statements. It starts
on January 15 with the same bank seed. The bank can issue those statements on
February 1, March 1 and April 1; the horizon is April 2. The customer chooses
its own questions and whether to wait, continue or finish.

This shared configuration now opts into `participationPolicy: THROUGH_HORIZON`.
An achieved goal or `FINISH` ends the current conversation but does not remove the
customer from later scheduled events. The customer can choose to do nothing at
each event. This requires a Runtime advertising `retainedParticipationSupported`
and a coordinator that supports the policy. It does not create periodic model
calls or bypass any run limit.

Select it from this directory with the existing launcher and the backend/bindings
chosen above:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
BANKING_BACKEND_URL=http://localhost:8098 \
BANKING_SAMPLE_FILE=three-month-configuration.json \
  python3 run-customer.py
```

`BANKING_SAMPLE_FILE` defaults to `sample-configuration.json`. The launcher reads
the account tool's `maxCalls` from the selected configuration: two in the original
sample and six here. Invalid grants are rejected before seeding the bank; service
and schema pins still come from capability discovery.

This case allows 20 customer decisions, twelve partner interactions, six account
lookups, 16 domain operations, ten wall-time minutes and three repeated exchanges.
Each invocation uses a fresh partner context and makes one platform launch;
it sends no scripted follow-up turns.

The configured goal additionally requires an actual partner receipt for this
account's statement ending March 31. **That final receipt alone does not prove
that January and February were reviewed.** Check the conversations and statement
dates in the exported evidence. Goal status and participation coverage are separate:
`HORIZON_COVERED` requires confirmed coverage through the horizon. A model failure
or safety limit can stop participation even after the goal was achieved.

The seed has no new spending, payments or interest work, so minimum-payment amounts
may stay the same. This scenario does not establish aggregate goal evaluation,
financial realism or shared-run population capacity.

## A2A request and backend lookup

The model-facing business input requires only:

```json
{
  "schemaVersion": "banking.statement-advice.request.v1",
  "customerMessage": "Can you explain my current minimum payment and due date?"
}
```

This is a protocol example, not a required customer utterance. After the model
chooses to contact the partner, Astraform supplies the accepted reference:

```json
"astraformCustomerContexts": [
  {
    "bindingKey": "banking",
    "contextRef": "bank-statement-example",
    "subjectRef": "customer-a",
    "readContext": {
      "contextId": "<accepted-domain-context-id>",
      "resourceVersion": 2,
      "logicalTimeEpochMs": 1769904000000
    }
  }
]
```

These illustrative guard values must come from the accepted domain checkpoint,
not the sample configuration or customer model. The partner fetches
`GET /api/banking/v1/contexts/{contextRef}/customers/{subjectRef}/accounts`
with `contextId`, `resourceVersion` and `logicalTimeEpochMs` query parameters.
The bank requires the exact owner, version and processed-through time for an
attached context. The partner also verifies the returned customer, version and
logical time. Without a period selector, it chooses the latest issued statement
by end date and bill ID from the bank's latest 12 statements. A different or
future bank version is an error, not substituted evidence.
Static, unowned bank contexts retain the earlier read without `readContext`.
This sample is not a general financial advisor across every account or query type.

To request a particular closing month, the customer may add
`"statementPeriod": "2026-02"` to the business input. This means a statement whose
`statementEndDate` falls in February 2026, not a statement issued or payable in
February. It is a request preference, not a claim that the statement exists.
The format is `YYYY-MM`, with years 0001–9999. Dates mentioned only in
`customerMessage` do not select a period; the partner does not interpret that
free text. Legacy supplied-statement mode rejects `statementPeriod`.

The partner forwards `statementPeriod` alongside the trusted read guards. The
bank searches retained statement history for that month before applying its
100-result bound; more than 100 matching statements produces HTTP 422 instead
of silently dropping records. The partner verifies the returned period echo and every
statement's closing month, then selects the latest matching end date and bill
ID. The reply echoes `statementPeriod`. If the bank returns no issued statement
for that month, the reply is `need_information` with an empty `statement`; it
never substitutes a different month or assumes the missing period is future.
Reinstall the updated input schema and update the partner binding revision
before launching new period-aware runs.

The reply follows [`output.schema.json`](output.schema.json): `payment_due`,
`no_payment_due` or `need_information`, the actual statement fields, and
`bankingContext` with the bank's context, customer, version and logical time.
Missing statements yield `need_information`; a failed bank lookup is an A2A
error. Supplied statement fields never replace bank data in backend mode.
Advice does not execute or schedule a payment.

## Inspect the outcome

Use GET `/api/simulation-runs/{runId}`, `/api/simulation-runs/{runId}/ledger`,
and the terminal `/api/simulation-runs/{runId}/export` under the service origin
selected by `ASTRAFORM_API_BASE`. Verify
the accepted domain preparation, future statement work and customer notification,
model-selected MCP calls and returned account values, outgoing A2A messages,
matching bank version/time provenance and the customer's final decision.

The completion policy's `goalIndex: 0` evaluates **the statement-advice goal
only**, using the configured partner-reply conditions. The second authored goal,
learning the actual credit limit, remains unverified by this completion policy.
A successful partner answer does not by itself prove a customer MCP read or
that second goal. Check the actual tool receipt and subsequent model use
separately. Model failures, abandoned goals and exhausted budgets remain unsuccessful recorded
results; neither this sample nor its client repairs model decisions.

Focused checks run with:

```bash
uv run --locked python -m unittest discover -s tests -v
```

These checks cover A2A transport, two different customer lookups over HTTP,
trusted read-guard forwarding, rejection of mismatched bank version/time,
missing identity/failed lookup, legacy statement validation, verbatim model prose,
explicit configuration, and failed/truncated/oversized model responses without
fallback or retry. The focused checks use local HTTP model fixtures; they do
not claim real-model simulation success.

## Earlier supplied-statement mode

Omitting `BANKING_BACKEND_URL` retains the older supplied-statement mode for
existing examples and evidence. Health explicitly labels it
`legacy-supplied-statement`. It now also requires an LLM; the legacy distinction
concerns the source of facts, not deterministic answer generation. It does not
query the bank and does not satisfy
the shared-backend scenario. Configure its Runtime target with the earlier
[`input.schema.json`](input.schema.json), which retains validated supplied
statement fields. Select the schema that matches the deployed partner mode; do
not use that legacy contract for the bank-backed customer scenario.

## License

The Astraform-authored code, configuration and documentation in this component are licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). This permits use, modification and redistribution under that license. Third-party dependencies retain their own terms. The repository-wide [license](../LICENSE) also covers the other Astraform-authored samples; Astraform's platform software remains excluded.
