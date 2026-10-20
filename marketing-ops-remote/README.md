# Marketing Operations Domain Provider

This source checkout targets the next SDK releases: `ai.astraform:sdk-java:0.4.5`
and `astraform-sdk==0.3.5`, using published contracts 1.2.3. These SDK releases
must be published before registry-only builds work. Existing samples v0.2.0
images and archives retain Java 0.4.4/Python 0.3.4 and their original package names.

This sample is a standalone Java/Spring marketing-ops domain provider.

The service implements `remote-domain.v1`, owns opaque marketing-ops state,
projects status and inspection fragments, and acknowledges host-driven shutdown
over HTTP/JSON.

It also implements `PolicyWindTunnelService` from the Java SDK, which
exposes the Marketing Ops Policy Wind Tunnel provider over
`/policy-wind-tunnel/...`. Marketing Ops does not need
`experiment-service` to discover an in-process pack or carry a Marketing Ops
implementation on the platform classpath.

The optional external A2A collaborator lives separately in the sibling
`marketing-ops-creative-agency` sample. This provider owns Marketing Ops business rules, per-persona state, tools,
policies, population catalogs and Scenario Lab evidence. The companion owns
its A2A endpoint and collaboration behavior. In LLM mode, that model chooses
both the advice and its disposition; neither performs the provider's business work.

It uses public Java SDK and conformance artifacts **0.4.5** plus ordinary
Spring dependencies. After the SDK releases are published, both artifacts resolve from Maven Central.
They use published shared contract release **1.2.3**. Building a sibling SDK checkout is
unnecessary. The native capability uses the SDK-generated typed profile. Host
SPI classes are not required in the provider.

## Requirements

- Java 21
- Maven 3.9+
- Spring Boot `4.1.0`
- Native Jackson 3

## Capabilities

- the provider contract supplies generated Spring server interfaces
- the service can accept the vendor media type
- prepare, execute-work, status, inspection, and shutdown are all implemented
- Policy Wind Tunnel pack metadata, presets, runs, bundles, artifacts, and
  evidence packs are exposed from the domain provider
- domain-owned scorecard metadata is published for generic dashboard rendering
- domain-owned Population Builder archetypes and recipes are published through
  `/api/remote-domain/v1/population/catalog`
- Population Builder member templates and validation rules for Marketing Ops
  are published by this provider rather than hard-coded in dashboard generation
- domain state stays opaque to the host and is versioned with `marketing-ops.state.v1`
- no in-process Scenario Lab pack is loaded into `experiment-service`

## Deployment limits

- this reference implementation targets Java/Spring
- production authentication and ingress policy require deployment-specific configuration
- full multi-tenant operational guarantees

The provider exposes registration, manifest discovery and population metadata
through the provider contract. For native customer simulations, use the
launch instructions below. Historical Scenario Lab registration through
`scenario.lab.remote.providers` is not a native customer launch path.


## Native customer simulation (local development)

Enable the native event profile explicitly:

```sh
mvn -B -ntp verify
java -jar target/marketing-ops-remote.jar \
  --server.port=18090 \
  --astraform.native-simulation.enabled=true
```

The existing service/controller implements
`remote-domain.native-state-transform.v1`, with provider revision
`marketing-ops.native-events.v4`. Revision headers pin requests and replies.
`prepare` seeds a per-customer checkpoint at the supplied logical start and
executes work due exactly at that start. `domain-system-work-window` reuses
existing review/escalation/readiness rules for the next `(previous, current]`
boundary. Each response includes the accepted version, current observation and
next due time or no-work boundary. It stores at most 32 recurring schedules and
32 approvals, plus the existing bounded activity history; it never preallocates
a day's event for every day in a long horizon.

Configure three existing surfaces:

| Configuration | Location | Meaning |
| --- | --- | --- |
| Customer persona, purpose, goals and model | Catalog draft in [the sample configuration](../integration/marketing-ops-a2a/sample-configuration.json) | The actual LLM customer's identity and intent; `execution.modelBindingKey` selects an operator-configured model |
| Marketing work and initial business state | The launch's `domain.personaConfiguration` | Existing `domainConfig` and `scheduledWork`, including work identity, kind, daily UTC time and business action payload |
| Provider, model and agency endpoints | Agent Runtime deployment properties | Partner-controlled endpoints and revisions; customers cannot select URLs or credentials |

Scheduled work can supply these domain-owned payload fields:

- `approvalId`: identifies the pending approval to review. An unknown identifier
  is rejected when the work executes; omission preserves first-approval selection.
- `reviewFinding`: a nonblank narrative of at most 1,200 characters, published
  when that scheduled work executes.
- `reviewFacts`: separate `reviewCompletionStatus` and `launchApprovalStatus`,
  plus knowledge-bearing `licenceDocument`, `documentSupplier`,
  `coordinationOwner`, `rightsHolder`, `licensingAuthority` and `deliveryDate`.

Each knowledge-bearing fact has `knowledge: "KNOWN"`, `"UNKNOWN"` or
`"UNREPORTED"`. Only `KNOWN` carries a nonblank `value` of at most 1,200
characters. Known licence-document values are `MISSING`, `RECEIVED` or
`VERIFIED`. Missing facts become `UNREPORTED`; an explicit `UNKNOWN` reports
that the answer is not established. Neither establishes absence or an unassigned
responsibility. Review statuses are `PENDING`, `IN_PROGRESS`, `COMPLETED`,
`UNKNOWN` or `UNREPORTED`; launch statuses are `PENDING_REVIEW`, `BLOCKED`,
`READY_FOR_LAUNCH`, `APPROVED`, `UNKNOWN` or `UNREPORTED`. A stated launch status
must agree with the provider's deterministic approval transition.

For the blocker scenario, the approval keeps its unresolved `BLOCKED_EXTERNAL`
state and its summary becomes the supplied finding. `lastAction` reports
recording that finding, not contacting an external party. The review can be
completed while launch approval remains blocked. Omission of the optional
payload fields preserves prior transitions; invalid supplied values are rejected.

The observation, status and inspection expose `selectedReview`: the approval
identity, campaign key, target-specific queue state, narrative `reviewSummary`,
structured `reviewFacts` and, after execution, `reviewedAtEpochMs`. All agency
bindings use that same selected record, rather than mixing aggregate status
with another approval's finding. It is the latest review snapshot, not a full
approval history.

Before due work, the customer sees the existing first pending task and
`UNREPORTED` review facts. Future findings stay in operator-owned
schedule/checkpoint data until execution. Historical checkpoints without a
selected review remain readable and receive unreported facts; the provider does
not infer facts from their prose. The
[example request schema](../integration/marketing-ops-a2a/input.schema.json)
binds the selected record through the platform's existing fact-binding mechanism.
`prepare` at the exact review boundary applies the finding immediately;
repeated identical work replays the same result.

The native slice supports `task` and `action` DAILY UTC schedules, with up to 32
co-due items per work window. Unsupported recurrence, timezone, prompt-driven
work and jitter settings are rejected. The existing legacy tools remain on the
legacy path; native customer tools/MCP and effectful `post_review_decision` are
not advertised as supported by this slice. The default mode retains the old
protocol behavior. Calendar recurrence and the remaining tool capabilities are
tracked migration work, not retired product features.

This is a pure checkpoint provider: there is no hidden marketing database read
or write. It is suitable for the existing per-persona sample; shared campaign
resources need a separately scoped authoritative provider implementation. The
banking reference-state profile serves a different ownership model and is not
copied into this sample.

Use the [native launch-once instructions](../integration/marketing-ops-a2a/README.md)
to publish a real-model customer, observe a scheduled review and receive an
actual Creative Agency response. The launcher uses the native SDK to create,
publish and launch the customer; Astraform owns subsequent execution and stopping. The per-persona sample does
not establish multi-customer or multi-year capacity.
