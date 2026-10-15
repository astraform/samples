# Java Spring Banking Remote Domain

Standalone Java/Spring reference provider for `remote-domain.v1`.

The provider uses published [Java SDK 0.4.3](https://central.sonatype.com/artifact/ai.astraform/remote-domain-author-kit-java/0.4.3)
and [contract bundle 1.2.1](https://github.com/astraform/remote-domain-contracts/releases/tag/v1.2.1).
Its existing SDK controller delegates banking operations to
`BankingRemoteDomainService`; the SDK supplies the protocol models, manifest
builder and conformance validation. The banking database, statement calculations
and customer access rules remain owned by this sample.

For customer simulations, use the public
[Astraform Platform v0.2.0 bundle](https://github.com/astraform/releases/releases/tag/v0.2.0).
Initialize it and configure its customer model using the included README.
The SDK is a dependency of this service; the running platform is a separate
installation. Neither building this sample nor launching through the SDK
requires a private platform checkout.

This sample proves a banking domain can run outside the Astraform core process
while still exposing runtime state, product metadata, domain tools, Policy Wind
Tunnel metadata, bundles, artifacts, and scorecards through public remote-domain
contracts.

## Scope

This is a reference provider, not a complete bank core.

It owns:

- banking opaque state
- banking product catalog compatibility at `/api/products`
- manifest-declared banking tools
- scheduled banking work execution
- Policy Wind Tunnel pack, presets, artifacts, and evidence pack endpoints

It does not own:

- Astraform host orchestration
- dashboard rendering
- generic agent activation
- platform scheduling authority (the global clock is retired)
- platform evidence ledger storage

## Partner-owned account database and customer MCP access

This provider owns its PostgreSQL account storage and exposes the official
MCP Java SDK 2.0.0 Streamable HTTP endpoint at `/mcp`. Its database is separate
from Astraform's simulation and evidence database.

The partner populates banking records first, then sends persona, goals and
customer/tool references to Astraform. Customer agents call the granted
`get_customer_accounts({})` MCP tool; the partner agent reads the same database
through its banking API. Astraform need not store or send account snapshots for
these reads. The bank does not preload balances or statements into the customer
model context; only an actual tool result supplies them.

The customer LLM runs in Astraform. For this Java banking scenario, the separate
[banking advice agent](../banking-advice-agent/README.md#start-the-bank-and-partner)
uses the same Python A2A implementation as the Python banking scenario, pointed
at this Java backend. The supplied partner Gemini configuration selects
`gemini-2.5-flash`; Astraform's v0.2.0 bundle separately configures its customer
model. Configure `BANKING_LLM_*` on the partner process, not this bank service.

Start the separate bank database and backend:

```bash
docker compose -f compose.database.yaml up -d --wait
BANKING_BACKEND_ENABLED=true \
BANKING_DB_URL=jdbc:postgresql://localhost:5544/banking \
mvn -B -ntp spring-boot:run -Dspring-boot.run.arguments=--server.port=8098
```

The local example database uses `banking` for database/user/password and a named
volume. `BANKING_DB_USERNAME`, `BANKING_DB_PASSWORD`, and `BANKING_DB_POOL_SIZE`
override those settings. Stopping/restarting the service or database preserves
records. The backend is opt-in (`BANKING_BACKEND_ENABLED=false` by default), so
existing remote-domain protocol consumers remain compatible.

Populate two distinct customers before creating their Astraform agents:

```bash
curl --fail-with-body -X PUT -H 'Content-Type: application/json' \
  --data-binary @examples/customer-a.json \
  http://localhost:8098/api/banking/v1/contexts/bank-trial/customers/customer-a
curl --fail-with-body -X PUT -H 'Content-Type: application/json' \
  --data-binary @examples/customer-b.json \
  http://localhost:8098/api/banking/v1/contexts/bank-trial/customers/customer-b
```

Account amounts are decimal strings. The examples contain actual stored scenario
facts (different credit limits of `3000` and `4500`), not scripted customer
decisions. Repeating a seed with identical data returns the existing record;
attempting to overwrite it or assign an existing account to another customer
returns HTTP 409. The bank's private partner API can read either customer:

```bash
curl --fail-with-body \
  http://localhost:8098/api/banking/v1/contexts/bank-trial/customers/customer-a/accounts
```

The same partner read route optionally accepts `statementPeriod=YYYY-MM`, the
statement's closing month, for example `?statementPeriod=2026-01`. Valid years
are `0001` through `9999`, with a two-digit month; malformed or empty values
return HTTP 400. The response echoes `statementPeriod` and contains only issued
statements whose `statementEndDate` falls in that month. Accounts, customer
identity, resource version and logical time are unchanged. An unissued month
returns HTTP 200 with an empty `statements` array; it does not generate future
statements or substitute another month. The indexed query selects from full bank
history before applying its bound, so months outside the default 12-statement
window remain available. More than 100 matching statements returns HTTP 422, never a partial
month. Without the parameter, the existing response shape uses a latest-12 default.
This is a limit of 12 records per customer, not a rolling 12-month filter; several
accounts can cover fewer months. Simulation-owned reads still require the same
three exact read context parameters alongside this optional filter.

The customer MCP tool has **no customer/account selector arguments**. The trusted
Astraform runtime adds the configured subject outside the model's arguments:

```json
{
  "name": "get_customer_accounts",
  "arguments": {},
  "_meta": {
    "astraform.customerContext": {
      "contextRef": "bank-trial",
      "subjectRef": "customer-a"
    }
  }
}
```

Each call queries only that subject in that bank context. A missing binding or
model-supplied selector fails. The same MCP session may serve multiple agents;
the provider has no mutable session-wide “current customer.” The partner API
can query different customer references and must use its trusted conversation
mapping to select the correct one.

Authentication remains deferred for this private-network sample. `_meta` is a
trusted caller assertion, not a credential: access to these routes must be
limited to the configured host and partner backend. Customer models receive
only the scoped MCP grant, never general access to the setup/partner APIs.

Exact API contracts are in
[`banking-backend.v1.yaml`](src/main/resources/openapi/banking-backend.v1.yaml),
with [seed](src/main/resources/schemas/banking-customer-seed.v1.schema.json) and
[MCP/partner response](src/main/resources/schemas/banking-customer-accounts.v1.schema.json)
schemas. The provider advertises and validates the MCP output schema.

### Native monthly events against the bank database

The `remote-domain.native-reference-state.v1` profile in contract bundle 1.2.1
connects monthly statement events to these authoritative records. Enable it
explicitly after building the service with `mvn -B -ntp package -DskipTests`:

```bash
BANKING_BACKEND_ENABLED=true \
BANKING_DB_URL=jdbc:postgresql://localhost:5544/banking \
ASTRAFORM_NATIVE_SIMULATION_ENABLED=true \
ASTRAFORM_NATIVE_SIMULATION_PROFILE=remote-domain.native-reference-state.v1 \
java -jar target/java-spring-banking.jar --server.port=8098
```

The profile property defaults to `remote-domain.native-state-transform.v1`, preserving
the earlier checkpoint-only deployment. These are distinct manifest profiles.
Prepare and `domain-system-work-window` retain their existing SDK endpoints. The
[released reference profile](https://github.com/astraform/remote-domain-contracts/blob/v1.2.1/remote-domain/v1/profiles/native-reference-state/README.md)
defines the contract, declared through the SDK's `nativeReferenceState(...)`
manifest builder. Customer account reads use the bank's `/mcp` endpoint; this
profile does not advertise the checkpoint-only account tool described below.

Before launch, populate data through the inclusive simulation start. Reference
prepare binds `{contextRef, subjectRef}` to one native `contextId`, requires the
same stored logical time, and creates no accounts or start-boundary statements.
Only optional `personaConfiguration.decisionTriggerPolicy` is accepted; business
records come from the bank. The native context version and bank `resourceVersion`
are separate. A later eligible monthly window uses the existing calendar-month
statement calculations, stores the statement/account update and new version/time,
and saves its exact response in one database transaction. The current supported
work kind is `credit_card_statement_close`, one monthly work item per window,
covering that customer's up to 100 accounts. Its generated period is the previous
calendar month, with 4% minimum payment and a due date 14 days after period end.

An identical operation retry returns the stored response even after its deadline,
restart or subsequent windows. Reusing its key with changed content, advancing a
stale context, new expired work, or taking over another run's customer is rejected.
No automatic rewind, context takeover or receipt deletion is provided. The bank
keeps complete statement history; unfiltered API/tool reads return the latest 12
statements in chronological order. The partner API's optional `statementPeriod`
filter reads the requested closing month from that full history. Customer rows are locked only for bounded local
database work, allowing different customers to progress independently.

Astraform retains a reference checkpoint and statement-available notifications,
not an account snapshot. When a monthly statement is scheduled within the horizon,
its observation includes `nextStatementAtEpochMs`, equal to the bank's accepted
`nextDueEpochMs`. The observation also includes `observedAtUtc` and, when a next
statement is scheduled, `nextStatementAtUtc`: readable ISO 8601 UTC equivalents
of `observedAtEpochMs` and `nextStatementAtEpochMs`. These are derived from the
same bank timestamps; numeric values remain authoritative for coordination.
That is the actual billing schedule; it does not prescribe the customer's action.
The customer must choose its granted MCP read to obtain
account details. Once attached, `_meta["astraform.customerContext"]` requires
`readContext: {contextId, resourceVersion, logicalTimeEpochMs}` alongside the
trusted customer references. The partner supplies these same three fields as GET
query parameters on `/accounts`. All must match the committed bank state; stale,
foreign-owner or missing guards fail instead of returning a differently timed
record. Static, unowned seeded records still support the original unguarded reads.
These scheduled bank updates do not enable customer-selected write tools.

The pure-state profile remains separate compatibility behavior. Its snapshots and
scheduled changes never become this database's authoritative records. Shared-run
population/capacity, broader banking work and customer-selected writes remain open.

## Customer Decision Trigger Policy

The provider evaluates committed banking evidence with
`banking.customer-decision-trigger-policy.v1`. It can emit statement-close,
payment-due, payment-missed, material-interest, material-fee, credit-limit,
rate/policy, hardship, liquidity, and optional monthly-review triggers. The
trigger payload contains selected domain facts only; it never carries prompts
or model output. Allowed facts must also match their bounded scalar type;
prompt, system-prompt, raw-provider, and chain-of-thought fields are rejected
recursively at any nesting level before a trigger is emitted.

Every numeric fact in a decision-trigger payload uses the versioned
`canonical_decimal_string.v1` encoding. This keeps financial precision and
large identifiers identical across Java, Python, and I-JSON consumers;
excessive precision, scale, or magnitude is rejected instead of rounded.

Defaults are deliberately selective: monthly review is disabled, credit
interest must reach `25.00`, and a fee must reach `10.00`. A domain population
can override only `enabled`, `priority`, and `minimumAmount` under
`personaConfiguration.decisionTriggerPolicy`:

```json
{
  "decisionTriggerPolicy": {
    "interest_charged": {
      "minimumAmount": 20,
      "priority": "HIGH"
    },
    "monthly_review": {
      "enabled": true,
      "priority": "NORMAL"
    }
  }
}
```

Unknown policies, unknown fields, invalid priorities, negative thresholds,
and non-boolean enablement fail preparation. This is domain-owned policy;
Astraform core validates and persists emitted opportunities but does not own
banking thresholds.

Scenario Lab retains `EVENT_ONLY` as an event-driven activation setting: its
persona configuration uses a zero calendar interval and decision triggers rather
than calendar polling. This retained configuration does not establish a supported
Scenario Lab cohort activation path; see the [migration boundary](#scenario-lab-clock-migration-boundary). In the current v1 contract this provider can
publish policy triggers from `domain-system-work-window`. Persona
`execute-work` responses cannot yet carry triggers, and the optional monthly
review mapping does not itself schedule a review. A native customer scenario
must configure its own decision opportunities and scheduled domain work; enabling
this policy alone does not make the customer act.

## Requirements

- Java 21
- Maven 3.9+
- Spring Boot `4.1.0`
- Native Jackson 3
- `ai.astraform:remote-domain-author-kit-java:0.4.3`
- `ai.astraform:remote-domain-conformance-java:0.4.3` (test dependency)

Both SDK dependencies share `remote-domain.sdk.version` in `pom.xml` and resolve
from Maven Central. Building the SDK from a sibling checkout is unnecessary.

## Run

From this directory:

```bash
mvn -B -ntp spring-boot:run
```

Default local port:

```text
http://localhost:8094
```

Manifest:

```bash
curl -fsS http://localhost:8094/api/remote-domain/v1/manifest
```

Health:

```bash
curl -fsS http://localhost:8094/actuator/health
```

The exact OCI image can be checked with the platform-neutral conformance CLI
and the checked-in `conformance/opportunity-worker-profile.json`:

```bash
remote-domain-conformance \
  --base-url http://127.0.0.1:8094 \
  --domain-id banking \
  --opportunity-worker-profile conformance/opportunity-worker-profile.json \
  --provider-artifact-digest "sha256:<exact OCI manifest digest>" \
  --population-catalog
```

Product catalog:

```bash
curl -fsS http://localhost:8094/api/products
```

Policy Wind Tunnel metadata:

```bash
curl -fsS http://localhost:8094/policy-wind-tunnel/pack
curl -fsS http://localhost:8094/policy-wind-tunnel/presets
```

## Test

From this directory:

```bash
mvn -B -ntp test -DskipITs
```

The tests use the sample repository root as the workspace root and expect the
banking scenario fixtures under `../scenarios`.

## Docker

Build from the sample repository root, not from this subdirectory:

```bash
docker build -f java-spring-banking/Dockerfile -t astraform/java-spring-banking:local .
```

Run:

```bash
docker run --rm -p 8094:8094 astraform/java-spring-banking:local
```

## Connect to Astraform

Use the [shared banking guide](../banking-advice-agent/README.md) for the complete
MCP, A2A and optional monthly-statement setup. In the extracted platform v0.2.0
bundle, partner connection settings belong in `private/runtime.properties`;
customer model settings belong in `private/model.env`. The Java bank must be
reachable from the Runtime container, for example on host port `8098`:

```properties
simulation.event.runtime.mcp.bindings.banking.endpoint=http://host.docker.internal:8098/mcp
simulation.event.runtime.mcp.bindings.banking.binding-revision=java-bank-mcp-v1
simulation.event.runtime.mcp.bindings.banking.allowed-tool-names=get_customer_accounts
simulation.event.runtime.mcp.bindings.banking.allow-insecure-http=true
```

The sample requires a trusted local/private network. On Linux, bind the host
service to an interface reachable from the Docker bridge. A host-run SDK launcher
uses the bundle's gateway, `http://localhost:8084` by default, rather than a direct
Runtime port. The shared guide supplies the partner target and native domain
bindings; the MCP settings above alone do not launch a run or advance bank time.

After adding bindings, run this from the extracted platform bundle directory:

```sh
./astraform.sh up -d --wait --force-recreate agent-runtime
```

Update binding revisions when changing connection semantics and start fresh runs.
The provider remains out of process and communicates through public contracts.
Do not copy its source into Astraform or give the customer general bank API access.

## Checkpoint-only compatibility profile

For a checkpoint-only integration, enable the profile explicitly when starting the provider:

```sh
java -jar target/java-spring-banking.jar --server.port=8097 --astraform.native-simulation.enabled=true
```

This deployment setting defaults to `false` to preserve the sample's existing
legacy configuration. When enabled without a profile override, the existing SDK
endpoints advertise `remote-domain.native-state-transform.v1` at
`capabilities.nativeSimulation`, pinned to the existing provider artifact digest
(`build-sha256:…` or `oci-sha256:…`).
The current profile accepts at most one seeded account and no persona
`scheduledWork`; it reuses automatic monthly credit-card statement closing,
its minimum-payment calculation and actual decision triggers. It does not
change the broader provider-managed banking operations.

Astraform supplies the profile/context/start/horizon in
`payload.initialProjectionHints.nativeSimulation` during prepare. The returned
opaque state carries the context version, processed-through time and either
next due time or an exclusive no-work-before bound. Preparation includes any
statement due exactly at the start; later windows use `(from,to]`, may execute
one statement, and cannot cross the next due boundary. The core coordinator
must also stop at any earlier customer/partner decision. Customer observations
contain current account facts and the latest actual statement, without future
scenario configuration.

This checkpoint-only profile also advertises the read-only
`get_customer_accounts` tool through `readOnlyCustomerTool(...)`. A published
Astraform customer may be granted this descriptor with its exact binding, provider,
descriptor and schema pins. The model chooses whether to call it; the tool accepts
`{}` and uses only that customer's accepted opaque checkpoint. The result contains
current account fields, including the actual credit limit, and the latest statement.
The automatic observation omits the credit limit, so obtaining it demonstrates a
real account-tool read.

Execution uses the existing SDK `/api/remote-domain/v1/execute-work` endpoint with
one `TOOL_CALL` item at a zero-length window at the checkpoint's accepted logical
time. The provider rejects unprepared native checkpoints, other tools, customer
selectors and time advancement on this path. `result.toolResult` and the read event
are returned, while `nextState`, its version and its timing remain unchanged. No
account mutation or partner-side independent lookup is implemented by this tool.

This is a pure state transformation profile: the caller durably owns the
opaque checkpoint and exact request/response receipts. An identical request
can be replayed after restart without an external business side effect. It
is not a provider-hosted receipt service or an exactly-once promise for remote
banking actions. Native calls pin `X-Astraform-Expected-Provider-Revision` and
verify the echoed `X-Astraform-Provider-Revision` as well as manifest identity.

The [released checkpoint profile](https://github.com/astraform/remote-domain-contracts/blob/v1.2.1/remote-domain/v1/profiles/native-state-transform/README.md)
and its tool schema are included in Java SDK 0.4.3. The sample uses the SDK's
`nativeStateTransform(...)` and `readOnlyCustomerTool(...)` builders through the
SDK controller.
The existing conformance test covers the default legacy configuration. Validate
each native profile separately with the SDK's packaged schemas and the provider's
behavior checks. This remains a development sample; SDK adoption alone does not
certify complete platform simulation behavior.

### Scenario Lab clock migration boundary

Astraform's native platform has no global clock runtime. The current local
source removes the retired clock and dependent Runtime request implementations;
partners must use supported native SDK operations. New `CLOCK_DRIVEN_HYBRID`
requests reject before creating local/Experiment run state, admitting work or
starting workers. Clock-dependent mutating lifecycle controls reject before
changing run state, and unsupported Scenario Lab execution rejects before
creating output directories, resetting or activating agents. Native restoration
of those execution capabilities remains under #470. An SDK method's presence
does not make execution supported.

Pack/preset metadata and valid stored historical run/artifact reads and exports
remain available. Terminal status/artifact reads retain stored results;
nonterminal historical refresh can serve an existing stored artifact, otherwise
it reports unavailability instead of querying a live clock or inventing a
snapshot. Same-operation SDK reconciliation of already recorded completion also
remains supported. Removing the retired requests preserves captured results
without rerunning them. Pack metadata, stored evidence and supported SDK
operations retain their existing behavior. Useful cadence, horizon, cohort,
domain intervention and
pause/resume/cancel/finalization semantics remain required native restoration
work under #470 and the platform's existing run/evidence owners.

The ordinary customer-simulation path continues to use generated SDK methods,
the bank's manifest/prepare/work-window operations and Astraform-owned durable
execution. Its issued/monthly capability requirements are described in the
[shared banking guide](../banking-advice-agent/README.md). The removal does not
change those flows or their SDK dependency pins. Published images and starter
archives retain the source from their release; these local edits have not been
published.
