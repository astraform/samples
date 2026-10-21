# Marketing customer simulation and A2A integration

This source checkout uses published `ai.astraform:sdk-java:0.4.5` from Maven
Central and `astraform-sdk==0.3.5` from PyPI, with published contracts 1.2.3.
Existing samples v0.2.0 images and archives retain Java 0.4.4/Python 0.3.4 and
their original package names.


Connect the marketing domain provider and its LLM creative agency to a matching
Astraform platform, targeting v0.4.0 or later. Download a compatible bundle only
after publication and verification. The updated source templates opt in to
`completionPolicy.requestFactBindingMode: "RESOLVE_TRUSTED"`; this requires the
coordinator implementation and a native typed customer SDK that preserves
that field. Python SDK 0.3.5 and Java SDK 0.4.5 provide the client
capability through contracts 1.2.3. The matching server capability still requires
a supporting platform; SDK publication does not establish that delivery.
Do not use the released [platform v0.3.0 bundle](https://github.com/astraform/releases/releases/tag/v0.3.0)
as evidence that trusted binding is supported. Its existing strict FINISH support
and the new trusted-binding mode are separate capabilities. Build and verify the
matching platform/sample candidate with the published SDK before launch;
publication and rebuilt image/archive delivery remain separate requirements.
The creative agency exposes A2A 1.0 JSON-RPC through the A2A Java SDK.

The source checkout’s Python launch tools use published `astraform-sdk==0.3.5`
from PyPI. Do not install it alongside `astraform-remote-domain-author-kit`;
both distributions provide the same import packages. See the
[SDK dependency setup](../../README.md#sdk-development).

## Native customer simulation

Run the marketing example using [`run-customer.py`](run-customer.py) and its
default [`sample-configuration.json`](sample-configuration.json). One real-model
customer, Quinn, seeks to understand the actual readiness finding and get
practical advice from the Creative Agency. Clarifying that advice or proposing
a handoff for agency feedback belongs to the same conversation and customer goal.

The launcher creates a Catalog draft, publishes
it and launches it once with generated `AgentJourneysApi.launch_agent_journey`.
It neither sends follow-up
turns nor advances simulated time. Astraform owns subsequent execution and simulated-time progression after
that launch; the helper does not drive later customer turns.

The sample starts at **2026-01-15 08:30 UTC**. The existing provider has one
pending campaign approval and a **daily 09:00 UTC** escalation review. The
provider owns that business rule and publishes the next due time. Astraform
admits the durable work at that boundary and supplies the resulting current
observation to Quinn. No global clock or elapsed real-time delay drives the
review. The customer chooses its messages and actions using its persona and
goal; its outcome is not guaranteed.

Quinn seeks an **actionable recommendation** for the observed external
launch dependency: who should take the next step, what they should do and how
that addresses the dependency. A generic instruction to monitor or wait without
those details does not give Quinn a practical next step. Quinn can ask for material
clarification or propose a handoff and ask the agency what it addresses and what
remains unverified. One adequate reply may resolve the enquiry; further feedback
can help when a useful question remains. The customer chooses its proposals,
messages, waits and stopping decision. The sample does not script follow-up,
force a FINISH decision or enforce a minimum number of turns.

The Creative Agency provides advice and proposal feedback; a request does not
contact the production studio, assign work, obtain a licence or clear the launch.
Quinn's goal concerns understanding the finding and getting practical advice.
If an escalation coordinator is unknown, a useful clarification distinguishes an established
responsibility from a recommendation about which person or role should coordinate
the next step or contact the studio. Repeating a request for execution through an
advice-only service is not progress, and model summaries cannot establish work
that has no confirming observation. Quinn's actual message should ask the specific
clarification identified in the customer's intent, rather than resend a broad
request while merely describing a more focused question in a summary.

The customer and agency instructions distinguish unreported information from an
explicit negative fact and preserve the scope of observed responsibilities. Both
roles are asked to acknowledge supplied answers, including unknown or unavailable
answers, and ask again only for a distinct material detail or changed evidence.
Information to obtain later belongs in proposed next steps. Agreement with advice
does not establish a new execution commitment. These instructions guide the
models; original unsupported or repetitive answers remain observable.
The agency also distinguishes a completed readiness review from blocked launch
approval; completion of the review does not mean the campaign is approved.
The provider reports separate `reviewCompletionStatus` and `launchApprovalStatus`
facts. Customer memory should preserve those distinctions. These are domain-owned
business facts, rather than platform-wide status fields or output validation.

The scheduled review separates its narrative evidence, `payload.reviewFinding`,
from explicit domain-owned `payload.reviewFacts`:

| Fact | Evidence after the scheduled review |
| --- | --- |
| Review completion | `COMPLETED` |
| Launch approval | `BLOCKED` |
| Licence document | Known to be `MISSING` |
| Document supplier | Known to be the external production studio |
| Coordination owner, rights holder, licensing authority and delivery date | Explicitly `UNKNOWN` |

The supplier is responsible for supplying the signed usage licence for the hero
photograph; that responsibility does not establish ownership or licensing
authority. An unknown coordination owner does not establish an unassigned role.
Each knowledge-bearing fact contains `knowledge: "KNOWN"`, `"UNKNOWN"` or
`"UNREPORTED"`; only a known fact contains a `value`. `UNREPORTED` means the
provider has not reported that fact, rather than an explicit finding that its
answer is unknown. The two status fields independently report review completion
and launch approval.

The payload names the seeded approval `brand-refresh-social-review` explicitly.
The provider publishes the finding and its structured facts in `selectedReview`
only when the 09:00 work executes. Before then, the customer sees the pending
review description and unreported review facts. The future fixture remains in
operator configuration, not advance knowledge in the customer's initial facts.
Historical checkpoints without structured facts also remain unreported; the
provider does not infer facts from their prose.

With `requestFactBindingMode: "RESOLVE_TRUSTED"`, Astraform fills each configured
bound request field from the customer's current accepted observations, including
`reviewSummary` and `reviewFacts`. The model still chooses whether and when to contact the agency,
its instruction or question, and any unbound facts; the platform supplies the
bound metadata without asking the model to copy long facts verbatim. The agency
receives the actual finding rather than only a blocked status and still chooses
its own advice. Configured success requires an
actual JSON response with scenario `launch-blocker` and disposition `ESCALATE`
or `UNBLOCK_PLAN`; those existing conditions are unchanged. All templates also
set `completionPolicy.requireCustomerFinish:true`: a qualifying receipt alone
does not stop a customer-selected follow-up. Success requires the qualifying
advice and the customer's own later FINISH decision within the existing limits.
The customer remains free to ask for clarification, wait or stop; no fixed turn
sequence or answer is prescribed. Receipt matching and FINISH do not establish
advice quality, assessment of a specific proposal, an executed escalation or a
resolved campaign blocker. Evaluate the original conversation against Quinn's
goal, including any clarification or proposal feedback requested, as well as its
execution status. Invalid or unhelpful model behavior and unmet completion
requirements remain observable.

### Start the two sample services

From the samples repository root, build and start the marketing provider:

```bash
mvn -f marketing-ops-remote/pom.xml -DskipTests package
ASTRAFORM_NATIVE_SIMULATION_ENABLED=true \
  java -jar marketing-ops-remote/target/marketing-ops-remote.jar --server.port=18090
```

In another terminal start the agency. JSON business responses are opt-in;
legacy clients still receive plain text by default. The native customer's
model remains independent of the agency's generation mode. First load the
[agency provider configuration](../../marketing-ops-creative-agency/README.md#provider-profiles),
including its required inbound token; LLM is the default generation mode.

```bash
mvn -f marketing-ops-creative-agency/pom.xml -DskipTests package
CREATIVE_AGENCY_A2A_RESPONSE_FORMAT=json \
MARKETING_OPS_CREATIVE_AGENCY_BASE_URL=http://host.docker.internal:18091 \
  java -jar marketing-ops-creative-agency/target/marketing-ops-creative-agency.jar --server.port=18091
```

To use Ollama for the agency's decisions and advice, apply its
[Ollama configuration](../../marketing-ops-creative-agency/README.md#ollama-on-the-same-machine)
before starting it. Allow enough output and response time for that model, and
configure its required partner token in both the agency and Astraform target.
The customer uses the independent real-model binding below. For Gemini, use
[the Gemini configuration](#gemini-model-selection).

The complete local Ollama profile adds these agency settings to that configuration:

```dotenv
CREATIVE_AGENCY_LLM_MODEL=qwen3.5:4b
CREATIVE_AGENCY_LLM_REASONING_EFFORT=high
CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS=32768
CREATIVE_AGENCY_LLM_MAX_RESPONSE_BYTES=1048576
CREATIVE_AGENCY_LLM_READ_TIMEOUT=60s
CREATIVE_AGENCY_LLM_MAX_ATTEMPTS=1
CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS=70
CREATIVE_AGENCY_LLM_BINDING_REVISION=marketing-starter-qwen35-4b-thinking-v3
```

This profile enables thinking by default and includes its finite token and
response budgets. The complete provider request has a 60-second deadline
with one attempt; timeout is an error without a retry or fallback answer. Set
Runtime A2A request and poll timeouts to `150s`, with the same target binding
revision. This transport allowance does not extend model generation. Generic provider reasoning remains explicitly
configured; intentional operator overrides remain available. Existing private
profiles are preserved until explicitly updated; accepted runs keep their original
model settings and outputs.

### Configure Astraform Runtime

Initialize the extracted platform bundle for Gemini or Ollama and configure
the customer model in `private/model.env`, following the bundle README. Its
default customer binding key is `local-customer`. The customer's model, memory
and timeout settings are independent of the creative agency's provider settings.

Add these connections to the bundle's `private/runtime.properties`, preserving
any existing bindings. These examples reach sample services on the Docker host.
On Linux, host services must listen on an interface reachable from Docker;
replace `host.docker.internal` with a reachable private address where appropriate.
The A2A request/poll budgets must cover the agency's outer deadline. The example
below uses the Gemini target revision. Both local test profiles use `150s`
for the transport budgets; for Ollama, select `marketing-starter-qwen35-4b-thinking-v3`
for the target binding revision. The scenario's separate wall-time and interaction limits still apply.

```properties
simulation.event.runtime.domain.bindings.marketing-ops.base-url=http://host.docker.internal:18090
simulation.event.runtime.domain.bindings.marketing-ops.domain-id=marketing-ops
simulation.event.runtime.domain.bindings.marketing-ops.binding-revision=marketing-ops-native-review-facts-v4
simulation.event.runtime.domain.bindings.marketing-ops.allow-insecure-http=true
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.request-timeout=150s
simulation.event.runtime.a2a.poll-timeout=150s
simulation.event.runtime.a2a.targets.creative-agency.base-url=http://host.docker.internal:18091
simulation.event.runtime.a2a.targets.creative-agency.binding-revision=marketing-starter-gemini36-high-v2
simulation.event.runtime.a2a.targets.creative-agency.allow-insecure-http=true
```

If the agency requires a token, configure
`simulation.event.runtime.a2a.targets.creative-agency.token` using your existing
secret configuration. Never place that secret in the Catalog or launch body.

Set the target's `input-schema-json` from [`input.schema.json`](input.schema.json).
This target accepts enquiries about the **observed campaign state**:
The operation selector `scenario: "launch-blocker"`, `approvalId`, `campaignKey`,
`queueState`, `reviewSummary` and `reviewFacts` are required. The exact provider states are
`review_pending`, `blocked`, `active` and `clear`. The launch's existing
`requestFactBindings` identify trusted sources for the selected approval,
campaign, queue state, review summary and explicit facts. All templates select
`requestFactBindingMode: "RESOLVE_TRUSTED"`:
Astraform projects these fields from the latest accepted provider observation
into the dispatched request. These fields come from matching fields under
`/domain/observation/selectedReview`, rather than combining an aggregate queue
state with whichever approval happens to be first. Before 09:00 the selected
summary describes the pending task and the review facts are unreported; after the
review it contains the configured finding and facts. A model-supplied omission
or paraphrase does not replace the bound source. Missing trusted evidence still
fails admission. Omitted mode or explicit `VALIDATE` retains the legacy exact-match
validation behavior; explicit modes require a typed SDK that preserves them. The current provider revision is
`marketing-ops.native-events.v4`; update its Runtime binding and the target's
input schema/revision together before creating new runs.

Quinn may legitimately contact the agency before the review. The LLM receives the
current facts and chooses its advice. `AWAIT_REVIEW` and `NO_EXTERNAL_BLOCKER`
do not achieve the configured advice goal; `ESCALATE` and `UNBLOCK_PLAN` do.
The agency does not override a valid model decision based on the queue state.
Quinn chooses whether to enquire, wait or follow up; the sample does not
prescribe that sequence. Plain-text requests use the same selected generator.
Free-text claims are not checked by the platform's structured field bindings.

From this directory, print the correctly escaped properties line:

```bash
python3 - <<'PYSCHEMA'
import json
from pathlib import Path
schema = json.dumps(json.loads(Path('input.schema.json').read_text()), separators=(',', ':'))
print('simulation.event.runtime.a2a.targets.creative-agency.input-schema-json=' + schema.replace('\\', '\\\\'))
PYSCHEMA
```

Append the printed schema line to the bundle's `private/runtime.properties`.
From the extracted bundle, recreate Runtime after installing the bindings:

```bash
./astraform.sh config --quiet
./astraform.sh up -d --force-recreate --wait agent-runtime
```

Start the complete bundle first if this is a fresh installation. Changes apply to
new runs; existing runs retain their accepted binding revisions and configuration.

### Launch and inspect

The marketing templates require `completionPolicy.requireCustomerFinish:true`
and `completionPolicy.requestFactBindingMode: "RESOLVE_TRUSTED"`. **Astraform Python SDK 0.3.5** preserves both fields. Use it with a
platform candidate implementing the mode; the SDK cannot supply coordinator
behavior. From the sample repository root, install the published package in
Python 3.12+:

```bash
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install 'astraform-sdk==0.3.5'
ASTRAFORM_API_BASE=http://localhost:8084 \
  python3 integration/marketing-ops-a2a/run-customer.py \
  --output /tmp/marketing-advice --wait
```

`MARKETING_SAMPLE_FILE` overrides the default with a complete configuration.
Set it to `integration/marketing-ops-a2a/current-state-configuration.json` to use
the existing [current-boundary variant](current-state-configuration.json), whose
start and horizon are both the 09:00 review boundary. It keeps the same customer
goal while admitting due work at the current boundary instead of starting before
the review. Use a fresh output directory for each intentional launch. You can
also supply an edited complete configuration through the same override.

The current provider source uses Java SDK/conformance **0.4.5** from Maven
Central with shared contract **1.2.3**. These domain callbacks do not provide the
customer-launch behavior in the server. The new mode requires both the
published typed client and a supporting platform implementation. Source pins
do not update existing images or archives; see the
[SDK dependencies](../../README.md#sdk-development).

The native provider supplies the marketing run identity. A custom scenario can
also declare `launchRequestTemplate.domainId: "marketing-ops"`, which must match
that provider. It is run classification, not an access grant or tenant boundary.
Python 0.3.5 preserves this optional field. The selected platform
candidate must also implement explicit domain identity. The launcher rejects an
invalid or unsupported declaration before output or Catalog writes. Omitting it
retains existing provider-derived identity and compatibility; it is never
silently removed or manually inserted into raw HTTP.

The launcher uses the normal generated Catalog and Agent Journey APIs. It shares
save, bounded read polling and same-run export behavior with
[`customer-run-evidence.py`](../customer-run-evidence.py), not the banking
launcher. A standalone marketing archive includes that utility beside
`run-customer.py`; its existing read-only Compose mount keeps both available.

For the authored templates and a custom `MARKETING_SAMPLE_FILE` supplying
`completionPolicy.requireCustomerFinish` as either `true` or `false`, the launcher
checks the raw Boolean and native typed SDK serialization before writing output
or creating, publishing or launching a Catalog definition. With Python
SDK 0.3.2, this launcher rejects either explicit value. A custom scenario that omits the field
retains the existing receipt-only behavior; omission does not satisfy these
strict customer-completion requirements. The selected SDK must preserve the
configured value. Any explicit `requestFactBindingMode` must be `VALIDATE` or
`RESOLVE_TRUSTED` and survive native typed serialization; 0.3.5
preserves both explicit modes. Older 0.3.3 clients cannot preserve them.
Omission retains legacy validation. These checks
run before output or Catalog/launch writes. There is no transport bridge, manual
field insertion or silent mode removal. The SDK supports these fields; matching
platform behavior and rebuilt image/archive delivery require separate verification.

The helper prints a JSON document containing the actual launch request,
Catalog identity and launch result. Defaults can be changed with
`ASTRAFORM_API_BASE` (legacy development default `http://localhost:8384`;
set `http://localhost:8084` for the bundle as shown above),
`CUSTOMER_MODEL_BINDING_KEY`, `MARKETING_DOMAIN_BINDING_KEY`,
`CREATIVE_AGENCY_TARGET_KEY`, or `MARKETING_SAMPLE_FILE` for an edited sample.
Each invocation creates a fresh Catalog definition and run. The updated platform
gateway accepts the generated client's canonical `/api/...` routes; older
`ASTRAFORM_API_BASE` values ending in `/api/dashboard` or `/api` are normalized
to the service origin before configuring that client.

Use the existing launcher's [`--inspect` SDK workflow](#same-run-evidence-and-local-image-only-starter)
with the saved run ID and API deployment. It reads status, ledger and terminal
export through generated `SimulationRunsApi` methods without creating another
run. Review `run.json`, `ledger.json` and `evidence.zip`. The evidence should show
the initial review-pending observation, the provider's 09:00 escalation, the
customer's actual decision and message, the agency receipt and the final stop
reason. A failed or ungrounded customer decision remains a failed result; the
helper does not repair it or retry with scripted behavior.

The configuration caps the run at **10 customer decisions, 4 partner
interactions, 8 domain operations, 20 real minutes**, and a simulated horizon
of **2026-01-16 10:00 UTC**. These are scenario limits, not platform claims about
months, years or population capacity. No customer tools are granted by this
scenario; marketing state is supplied through native domain observations.
The provider checkpoint is per customer, and this sample does not establish a
shared marketing database.

### Gemini model selection

For new Gemini runs, select `gemini-3.6-flash` independently in Astraform's
customer profile and the Java creative agency. After loading the agency's
endpoint and credentials, export:

```bash
export CREATIVE_AGENCY_LLM_MODEL=gemini-3.6-flash
export CREATIVE_AGENCY_LLM_BINDING_REVISION=marketing-starter-gemini36-high-v2
export CREATIVE_AGENCY_LLM_REASONING_EFFORT=high
export CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER=max-completion-tokens
export CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS=16384
export CREATIVE_AGENCY_LLM_READ_TIMEOUT=60s
export CREATIVE_AGENCY_LLM_MAX_ATTEMPTS=1
export CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS=70
```

Use the same revision in the Runtime A2A target binding. Restart the agency and Runtime before
launching a new run. The [agency Gemini profile](../../marketing-ops-creative-agency/README.md#google-gemini-through-its-openai-compatibility-endpoint)
contains the remaining endpoint and credential settings. The complete provider
request has a 60-second deadline with one attempt inside the 70-second agency
deadline; configure Runtime request and poll budgets to `150s`. The outer
transport budget does not extend model generation or enable retries. Banking
and marketing retain independent token ceilings. Changing either model requires
restarting its service and creating a fresh simulation; it does not change an
accepted run or rewrite an existing private profile. Immutable published images
retain their recorded versions; a newly built candidate requires its own SDK
and image verification.

## Direct protocol 1.0 proof

From the Astraform samples repository root:

```bash
CREATIVE_AGENCY_GENERATION_MODE=deterministic \
  CREATIVE_AGENCY_LLM_API_KEY= \
  docker compose up -d --build --wait --wait-timeout 300 \
  marketing-ops-creative-agency
./integration/marketing-ops-a2a/check-creative-agency-a2a-v1-live.sh
```

This proof explicitly selects deterministic mode, so it is free, offline,
and reproducible. If deliberately testing an LLM-backed instance,
export `CREATIVE_AGENCY_A2A_TOKEN` before invoking the script.

This checks the sample's A2A protocol handling only. It does not launch an
Astraform customer or establish real-model simulation success. Use the SDK
workflow above for that purpose. Native simulations use supported SDK operations;
retired clock-runtime workflows are not supported launch instructions.


## Same-run evidence and local image-only starter

The existing launcher accepts`--output DIRECTORY --wait` to save the create,
publish and launch identities before their requests, then inspect the accepted
run and export terminal evidence. A new launch requires an empty directory;
a transport error never triggers automatic creation/publication/launch retries.
Use the returned run ID to resume:

```sh
ASTRAFORM_API_BASE=http://localhost:8084 \
  python3 integration/marketing-ops-a2a/run-customer.py \
  --inspect RUN_ID --output /tmp/marketing-advice --wait
```

`--inspect` is read-only. Status/ledger/export reads can retry bounded temporary
transport errors against that same run. The observer's1,500-second default covers
the configured1,200-second scenario limit with export time; it does not extend or
cancel the run. Saved evidence keeps unsuccessful goals and original messages.
Both customer launchers import the same
[`customer-run-evidence.py`](../customer-run-evidence.py) utility for saved-run
inspection/export through the SDK. Marketing does not import the banking
executable or start another coordinator.

[The image-only marketing starter](../../deploy/marketing-starter/README.md)
packages the launcher, selected scenario and private-network image topology. Use images
and a platform with the capabilities required by the selected configuration;
the source templates do not update existing published images or archives.
Current domain source uses Java SDK 0.4.5, and the customer templates use Python
SDK 0.3.5. Rebuild the tools image with that SDK and verify the supporting platform
before packaging updated templates for partners.
