# Marketing customer simulation and A2A integration


Connect the marketing domain provider and its LLM creative agency to the
[Astraform v0.2.0 deployment bundle](https://github.com/astraform/releases/releases/tag/v0.2.0).
The bundle supplies Astraform's runtime and coordinator as released images.
Build the sample services below and use the published SDK to launch a customer;
no platform source checkout is required. The creative agency exposes A2A 1.0
JSON-RPC through the A2A Java SDK.

## Native customer simulation

[`sample-configuration.json`](sample-configuration.json) configures one real-model
customer, Quinn, using the existing marketing provider and Creative
Agency. [`run-customer.py`](run-customer.py) creates a Catalog draft, publishes
it and launches it once through `/api/agent-journeys`. It neither sends follow-up
turns nor advances simulated time. Astraform owns subsequent execution and simulated-time progression after
that launch; the helper does not drive later customer turns.

The sample starts at **2026-01-15 08:30 UTC**. The existing provider has one
pending campaign approval and a **daily 09:00 UTC** escalation review. The
provider owns that business rule and publishes the next due time. Astraform
admits the durable work at that boundary and supplies the resulting current
observation to Quinn. No global clock or elapsed real-time delay drives the
review. The customer chooses its messages and actions using its persona and
goal; its outcome is not guaranteed.

The goal is to obtain an **actionable recommendation** for the observed external
launch dependency: who should take the next step, what they should do and how
that addresses the dependency. A generic instruction to monitor or wait without
those details does not give Quinn a practical next step. Quinn can ask for
clarification, wait or decide to stop; neither model is forced to select a
particular action or success label.

The scheduled review's `payload.reviewFinding` defines a sample business fact:
the external production studio has not supplied the signed usage licence for
the hero photograph. The provider publishes that finding in the existing review
record's summary only when the 09:00 review executes. Before then, the customer
sees the pending-review description. The future fixture is visible in operator
configuration, not supplied as advance knowledge in the customer prompt.

Quinn's request includes the current `reviewSummary`, bound to that observed
record, so the agency receives the actual finding rather than only a blocked
status. The agency LLM still chooses its advice. Configured success requires an
actual JSON response with scenario `launch-blocker` and disposition `ESCALATE`
or `UNBLOCK_PLAN`; those existing conditions are unchanged. This checks receipt
of a qualifying recommendation, not its factual quality, an executed escalation
or a resolved campaign blocker. Invalid or unhelpful model behavior remains
observable, and stopping before the criteria match remains a failed goal.

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

The recorded local LLM setup adds these agency settings to that configuration:

```dotenv
CREATIVE_AGENCY_LLM_MODEL=qwen3.5:4b
CREATIVE_AGENCY_LLM_REASONING_EFFORT=none
CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS=2048
CREATIVE_AGENCY_LLM_READ_TIMEOUT=90s
CREATIVE_AGENCY_LLM_MAX_ATTEMPTS=1
CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS=110
CREATIVE_AGENCY_LLM_BINDING_REVISION=marketing-agency-ollama-decisions-v1
```

The agency's optional reasoning setting leaves other deployments unchanged
when omitted. This local model enables thinking by default; `none` requests
nonthinking generation through Ollama's OpenAI-compatible endpoint.

### Configure Astraform Runtime

Initialize the extracted platform bundle for Gemini or Ollama and configure
the customer model in `private/model.env`, following the bundle README. Its
default customer binding key is `local-customer`. The customer's model, memory
and timeout settings are independent of the creative agency's provider settings.

Add these connections to the bundle's `private/runtime.properties`, preserving
any existing bindings. These examples reach sample services on the Docker host.
On Linux, host services must listen on an interface reachable from Docker;
replace `host.docker.internal` with a reachable private address where appropriate.
The A2A timeout must exceed the agency's request budget. The scenario's separate
wall-time and interaction limits still apply.

```properties
simulation.event.runtime.domain.bindings.marketing-ops.base-url=http://host.docker.internal:18090
simulation.event.runtime.domain.bindings.marketing-ops.domain-id=marketing-ops
simulation.event.runtime.domain.bindings.marketing-ops.binding-revision=marketing-ops-native-review-findings-v3
simulation.event.runtime.domain.bindings.marketing-ops.allow-insecure-http=true
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.request-timeout=120s
simulation.event.runtime.a2a.targets.creative-agency.base-url=http://host.docker.internal:18091
simulation.event.runtime.a2a.targets.creative-agency.binding-revision=marketing-agency-review-evidence-v2
simulation.event.runtime.a2a.targets.creative-agency.allow-insecure-http=true
```

If the agency requires a token, configure
`simulation.event.runtime.a2a.targets.creative-agency.token` using your existing
secret configuration. Never place that secret in the Catalog or launch body.

Set the target's `input-schema-json` from [`input.schema.json`](input.schema.json).
This target accepts enquiries about the **observed campaign state**:
The operation selector `scenario: "launch-blocker"`, `campaignKey`, `queueState`
and `reviewSummary` are required. The exact provider states are
`review_pending`, `blocked`, `active` and `clear`. The launch's existing
`requestFactBindings` require the campaign and queue state to match the latest
accepted provider observation. `reviewSummary` must exactly match
`/domain/observation/pendingApprovals/0/summary`; this example retains one
pending/blocked approval. Before 09:00 that summary describes the pending task;
after the review it contains the configured finding. Inventing or paraphrasing
a bound field fails admission. The current provider revision is
`marketing-ops.native-events.v3`; update its Runtime binding and the target's
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

The helper uses the platform client included in the published **Astraform Python
author kit 0.3.1**. Install it in a Python 3.12+ virtual environment. From the
sample repository root:

```bash
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install 'astraform-remote-domain-author-kit==0.3.1'
ASTRAFORM_API_BASE=http://localhost:8084 \
  python3 integration/marketing-ops-a2a/run-customer.py
```

The provider uses Java author-kit/conformance **0.4.1** from Maven Central.
Both SDKs use shared contract **1.2.0**. No SDK checkout or local wheel build is
needed; see the [SDK dependencies](../../README.md#sdk-development).

The helper prints one JSON document containing the actual launch request,
Catalog identity and launch result. Defaults can be changed with
`ASTRAFORM_API_BASE` (legacy development default `http://localhost:8384`;
set `http://localhost:8084` for the bundle as shown above),
`CUSTOMER_MODEL_BINDING_KEY`, `MARKETING_DOMAIN_BINDING_KEY`,
`CREATIVE_AGENCY_TARGET_KEY`, or `MARKETING_SAMPLE_FILE` for an edited sample.
Each invocation creates a fresh Catalog definition and run. The updated platform
gateway accepts the generated client's canonical `/api/...` routes; older
`ASTRAFORM_API_BASE` values ending in `/api/dashboard` or `/api` are normalized
to the service origin before configuring that client.

Read the returned run through `GET /api/simulation-runs/{runId}`;
retrieve its evidence through
`GET /api/simulation-runs/{runId}/export`. The evidence should show
the initial review-pending observation, the provider's 09:00 escalation, the
customer's actual decision and message, the agency receipt and the final stop
reason. A failed or ungrounded customer decision remains a failed result; the
helper does not repair it or retry with scripted behavior.

The configuration caps the run at **10 customer decisions, 4 partner
interactions, 8 domain operations, 20 real minutes**, and a simulated horizon
of **2026-01-16 10:00 UTC**. These are scenario limits, not platform claims about
months, years or population capacity. No tools are granted in this slice;
existing marketing tools remain in the provider, with native MCP integration
tracked separately. The provider checkpoint is per customer, and this sample
does not establish a shared marketing database.

### Gemini model selection

For new Gemini runs, select `gemini-3.6-flash` in Astraform's customer profile
and retain `gemini-2.5-flash` for the Java creative agency. The two bindings are
independent. After loading the agency's Gemini settings, export:

```bash
export CREATIVE_AGENCY_LLM_MODEL=gemini-2.5-flash
export CREATIVE_AGENCY_LLM_BINDING_REVISION=marketing-agency-gemini25-flash-review-v2
```

Use the same revision in the Runtime A2A target binding. Restart the agency and Runtime before
launching a new run. The [agency Gemini profile](../../marketing-ops-creative-agency/README.md#google-gemini-through-its-openai-compatibility-endpoint)
contains the remaining endpoint and credential settings. Changing either model requires restarting its service and creating a fresh
simulation; it does not change an accepted run.

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
workflow above for that purpose. Internal platform restart, replay and retired
clock-runtime proofs are maintained with the platform source and are not required
to build or run these sample services.
