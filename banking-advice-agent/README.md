# Banking advice agent and customer simulation

The image-only Python banking starter is prepared under
[`deploy/banking-starter`](../deploy/banking-starter/README.md). It reuses this agent
and the Python bank. Published Python SDK 0.3.3 supports launch, inspection and
evidence export, including native `completionPolicy.requireCustomerFinish`.
The strict issued-statement scenario also requires the published platform v0.3.0 implementation;
the published platform v0.2.0 does not provide that stopping behavior.
The published samples v0.1.0 Python banking starter retains its original scenario,
SDK versions and completion behavior. The monthly source workflow below omits
that field and remains compatible with the public platform bundle. Current source
locks install Python SDK 0.3.3; rebuild images to consume those source changes.

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
Ollama or Gemini. Current strict templates use the published
[platform v0.3.0 bundle](https://github.com/astraform/releases/releases/tag/v0.3.0).
Download its archive and checksum, then verify and initialize the selected
profile using its included README. The immutable samples v0.1.0 starter retains
its original [platform v0.2.0 dependency](https://github.com/astraform/releases/releases/tag/v0.2.0).
No platform source checkout or source build is required for a matching release.
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

For this sample bank, `CC`-prefixed products use a credit-card ledger convention:
a negative balance is money owed on the card. The partner supplies the model
with that convention and exact current debt, credit balance and remaining modeled
credit limit derived from the guarded bank record. For example, a balance of
`-1200` and limit of `3000` means `1200` owed and `1800` of the modeled limit
remaining. These values keep accounts and currencies separate; missing limits or
balances remain unknown. The model must not describe a negative card balance as
proof of an overdraft, zero spending power, or a requirement to reach a positive
balance before repayment can reduce the debt. Remaining modeled credit is not a
purchase-authorization guarantee: pending authorizations, holds and restrictions
are absent from this lookup. Unknown products are not assigned this convention.

The model is instructed to answer the actual question and keep minimum/due-date
questions focused on the issued statement, rather than volunteering balances or
credit availability. Current account interpretations are explicitly marked as the
ledger at the bank as-of time; their amounts must not be attributed to an earlier
statement closing date, even if the numbers happen to match. Amounts owed are debt,
not a positive credit balance.

Current account amounts are separate from the selected issued statement. A later
zero or positive balance does not replace its recorded minimum and due date or
prove payment settlement. The model is instructed to state missing payment history,
APR or future minimum-payment policy when those facts are needed. The canonical
[bank account schema](../python-fastapi-banking/src/banking_remote_domain_python/schemas/banking-customer-accounts.v1.schema.json)
documents these field meanings for both bank implementations. These grounded inputs
and instructions improve the model's evidence; the agent still returns its actual
prose unchanged, so transport/schema success alone does not prove answer quality.

For budget questions, the local advice source also provides an
`issuedStatementInterpretation` from the selected closing amount and its own
matching credit-card account/currency. It stays separate from current ledger
amounts; unknown products, missing amounts, ambiguous accounts or currency
mismatches are not assigned a guessed debt. This annotation is model input only;
the public request/response schemas and raw bank records remain unchanged.

The model is instructed to compare the customer's explicitly stated budget with
both the issued minimum and the amount owed, using that same budget throughout.
The budget is a customer premise, not verified bank funds. Basic same-currency
capacity arithmetic does not require APR or payment-allocation policy. Any
remaining-balance subtraction is a conditional illustration, assuming the whole
payment reduces this debt and no intervening charges; actual posting, allocation
and settlement remain unknown. Missing/ambiguous budgets or currencies require
clarification rather than inferred amounts or exchange rates. Answers are still
actual model prose, preserved without templates, repair or hidden retries.

Each partner answer sees **only this request and its current bank records**.
The partner does not retain conversation history; reusing an A2A context ID does
not supply earlier exchanges. Astraform's customer memory remains separate.
General multi-turn partner memory and model-selected bank tools are not implemented
by this sample.

The non-strict monthly sample below can run alongside platform v0.2.0; current
strict issued-statement templates target platform v0.3.0. Sample services are not included
in the platform images. The configuration uses the reference-state domain profile to attach a
prepopulated bank customer and generate monthly statements in that same bank
database. It starts before the first statement exists. This is a bounded monthly-statement example,
not evidence of years of simulation or 1,000-customer capacity. The older
snapshot-based provider profile remains separate.

The Java bank provider uses published [Java SDK 0.4.3 from Maven Central](https://central.sonatype.com/artifact/ai.astraform/remote-domain-author-kit-java/0.4.3).
The Python bank provider and this monthly scenario's launch helper use published
[Python author kit 0.3.3](https://pypi.org/project/astraform-remote-domain-author-kit/0.3.3/).
Both SDKs use [shared contract 1.2.1](https://github.com/astraform/remote-domain-contracts/releases/tag/v1.2.1)
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
export BANKING_LLM_MODEL=gemini-3.6-flash
export BANKING_LLM_AUTH_TYPE=bearer
export BANKING_LLM_API_KEY="$GEMINI_API_KEY"
export BANKING_LLM_BINDING_REVISION=banking-starter-gemini36-high-v1
export BANKING_LLM_REASONING_EFFORT=high
export BANKING_LLM_MAX_OUTPUT_TOKENS=32768
export BANKING_LLM_TIMEOUT_SECONDS=840
export BANKING_LLM_TEMPERATURE=1.0
export BANKING_LLM_MAX_CONCURRENCY=1
```

This current source partner example uses `gemini-3.6-flash`. Select the same
model independently in the platform's `private/model.env` for a both-role
Gemini run; the partner variables do not configure the customer. Rebuild current
candidate images to adopt this profile. Historical published archives and
initialized private profiles retain their original versions and settings.

The same client can be configured for Ollama's OpenAI-compatible endpoint:

```bash
export BANKING_LLM_BASE_URL=http://localhost:11434/v1
export BANKING_LLM_MODEL=qwen3.5:4b
export BANKING_LLM_AUTH_TYPE=none
unset BANKING_LLM_API_KEY
export BANKING_LLM_BINDING_REVISION=bank-ollama-qwen35-thinking-v8
export BANKING_LLM_REASONING_EFFORT=high
export BANKING_LLM_MAX_OUTPUT_TOKENS=32768
export BANKING_LLM_TIMEOUT_SECONDS=840
export BANKING_LLM_TEMPERATURE=1.0
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
| `BANKING_LLM_TIMEOUT_SECONDS` | `120` | Total model queue, connection and response deadline, 1–900 seconds |
| `BANKING_LLM_MAX_OUTPUT_TOKENS` | `2048` | Provider output cap, 1–32768 tokens |
| `BANKING_LLM_MAX_CONCURRENCY` | `4` | Maximum concurrent model calls per process, 1–32 |
| `BANKING_LLM_TEMPERATURE` | `0` | Explicit sampling temperature, a finite number from 0–2; lower variance does not prove factual accuracy |
| `BANKING_LLM_REASONING_EFFORT` | omitted | Optional provider-supported setting |
| `BANKING_LLM_DIAGNOSTICS` | `false` | Include actual model request and response in service logs |

The guarded bank read has a separate ten-second deadline. Configure Astraform's
`simulation.event.runtime.a2a.request-timeout` above the bank + model deadlines;
use `150s` only with the adapter's 120-second default. The supplied current
Gemini profile and Ollama thinking preset instead use
`reasoning_effort=high`, 32,768 total generated tokens, a finite 840-second model
deadline and temperature `1.0`; configure A2A request and poll timeouts to `900s`.
These partner limits are separate from Astraform's customer-model timeout.
Those are ceilings, not guaranteed duration or accuracy. Ollama's compatibility
API cannot set context size per request: configure the model server to allow the
input plus generated tokens before testing. The [Qwen model card](https://huggingface.co/Qwen/Qwen3.5-4B)
recommends 32,768 output tokens for most queries, and [Ollama's thinking controls](https://docs.ollama.com/api/openai-compatibility)
map `high` to enabled thinking for models with Boolean thinking support. Other
providers retain the generic defaults above unless explicitly configured. The model is called once per
valid request, with no internal retry. Network/provider errors, truncation, refusal,
tool calls, empty answers and answers over 4096 characters produce an A2A error;
no successful canned answer is substituted. Upstream responses are bounded to
1 MiB (1,048,576 bytes), including a provider's separate reasoning field. Only the
unchanged final `message.content` is returned as advice; reasoning is neither
substituted for it nor added to the customer-facing answer. Use the actual model
output to assess quality; prose is not repaired
or automatically proven truthful.

Before a bank-backed answer is accepted, a bounded literal-date guard checks
ISO `YYYY-MM-DD` / `YYYY-MM` and common English month/day/year, day/month/year
and month/year forms. An invalid calendar date or a newly introduced date absent
from the selected statement, bank as-of time and customer question fails the
existing model attempt with an A2A error; no answer is rewritten or retried.
The model also receives `statementDateRoles` from the same selected issued record:
closing date/month and payment due date are distinct. A second bounded check rejects
recognized dates directly following explicit closing/statement-date or payment-due
labels when they conflict with that role in the record. It does not understand
arbitrary prose or all ways a date may be attributed.
An explicit requested statement month remains valid even when no statement exists.
Dates already mentioned in the question can be discussed conditionally, but remain
unverified unless the bank records confirm them. This recognizer checks date
literals, not their attribution or conditional wording; it does not cover every
natural-language date form, relative dates, all factual claims or general answer
quality. It does not interpret arbitrary four-digit amounts or credit limits as
years. Legacy customer-supplied mode retains its conditional-answer behavior.

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
simulation.event.runtime.a2a.request-timeout=900s
simulation.event.runtime.a2a.poll-timeout=900s
simulation.event.runtime.a2a.targets.banking-advice.base-url=http://host.docker.internal:8099
simulation.event.runtime.a2a.targets.banking-advice.binding-revision=banking-starter-gemini36-high-v1
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
simulation.event.runtime.a2a.request-timeout=900s
simulation.event.runtime.a2a.poll-timeout=900s
simulation.event.runtime.a2a.targets.banking-python-advice.base-url=http://host.docker.internal:8101
simulation.event.runtime.a2a.targets.banking-python-advice.binding-revision=banking-starter-gemini36-high-v1
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
python3 -m pip install 'astraform-remote-domain-author-kit==0.3.3'
```

For this monthly scenario, which omits `requireCustomerFinish`, no SDK source
checkout or locally built wheel is needed. The Python bank installs
the same version with the `[fastapi]` extra through its own project dependency
lock; the launcher does not require that extra. Conformance remains separate
testing tooling. See the [SDK dependencies](../README.md#sdk-development).

The launcher shares saved-run inspection and export behavior with
[`customer-run-evidence.py`](../integration/customer-run-evidence.py). Its local
Docker build places that utility beside `/app/run-customer.py`; source execution
uses the same file from `integration/`. The utility does not seed a bank,
create/publish an agent or launch a customer.

To verify a Java customer rather than only a Java bank provider, the same
launcher supports `--prepare-only --output /fresh/empty/prepared-output`.
It seeds and discovers capabilities but makes no Catalog create/publish or
Journey launch writes. Its `prepared-scenario.json` has `catalog` and `launch`
objects for the existing downloaded Java SDK 0.4.3 `FirstSimulation` client.
See the [extracted starter Java preparation route](../deploy/banking-starter/README.md#prepare-for-the-existing-java-customer-sdk-client)
for paths and the launch-once/inspect-same-run boundary. No extra runtime layer
or SDK transport bridge is introduced.

Use the updated platform gateway with canonical `/api/...` routing:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
BANKING_BACKEND_URL=http://localhost:8098 \
  python3 run-customer.py
```

[`run-customer.py`](run-customer.py) does these steps once:

1. Populate a fresh partner bank context from `bankCustomerSeed` using
   `PUT /api/banking/v1/contexts/{contextRef}/customers/customer-a`.
2. Discover current tool pins with the generated
   `SimulationEventsApi.inspect_public_customer_mcp_capabilities` method.
3. Create and publish the persona with generated `AgentCatalogApi` methods
   `create_agent_definition` and `publish_agent_definition`.
4. Print the exact launch request, then call generated
   `AgentJourneysApi.launch_agent_journey` once for that immutable revision and
   print its IDs and accepted response. The script sends
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
decisions, three repeated exchanges and two hours of wall-time admission.

**The local issued-statement scenario waits for the customer to finish.** Its
`completionPolicy.requireCustomerFinish: true` requires both a matching actual
partner receipt and a later accepted customer `FINISH` decision. A customer
`CONTACT_PARTNER` follow-up continues through the existing dispatch path even if
the previous receipt matched; all decision, interaction, repetition and time
limits still apply. `FINISH` without matching evidence is not achievement.
Omitted or false values preserve the earlier receipt-only stopping behavior.

This optional field targets the platform v0.3.0 Experiment service and a native
customer SDK whose generated completion-policy model preserves the supplied
Boolean. Published Python SDK 0.3.3 supplies this native client support;
published platform v0.2.0 still lacks the required stopping behavior.
The launcher checks raw type and typed round-trip fidelity before bank seeding or
create/publish/launch writes, then uses the normal generated Agent Journey API.
It never manually inserts a JSON extension, drops the value, retries a failed
launch or chooses customer behavior. Both explicit true and explicit false fail
with older SDK 0.3.2; omission remains compatible. Current source pins select
0.3.3, while existing published images and archives retain their recorded versions.

The receipt conditions still verify statement records, not understanding. They
match schema, payment status, customer, account, bill, statement end, minimum,
currency, due date and closing amount against the seed. Neither those checks nor
a model-authored `FINISH` prove that the advice correctly explains the AU$100
budget. Inspect the actual partner prose and customer reaction to verify that
AU$100 covers the AU$48 minimum but does not repay AU$1,200, and retain missing
answers, contradictions and unsuccessful runs. Several turns may be useful, but
a particular number of turns is not guaranteed. No answer text, customer decision
or payment action is scripted.

## Shared Java and Python scenarios

Use these same three files for either bank implementation:

| Configuration | Scenario | Participation |
| --- | --- | --- |
| `issued-statement-configuration.json` | Already-issued January statement and AU$100 payment budget | Local `requireCustomerFinish: true` waits for the customer to finish; no domain advance |
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

Inspect the accepted run with the existing SDK helper. From the samples
repository root, use its saved run ID and the same API deployment:

```bash
ASTRAFORM_API_BASE=http://localhost:8084 \
  python3 banking-advice-agent/run-customer.py \
  --inspect RUN_ID --output /tmp/banking-first --wait
```

Use the original output directory when one was saved, or a fresh directory for
this run. `--inspect` performs only generated `SimulationRunsApi` reads; it never
seeds a bank, creates or publishes a customer, or launches a replacement. Review
`run.json`, `ledger.json` and terminal `evidence.zip`. Verify the accepted domain
preparation, future statement work and customer notification,
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
