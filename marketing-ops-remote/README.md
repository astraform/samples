# marketing-ops-remote

This sample is a standalone Java/Spring marketing-ops remote domain provider.

It exists to prove that `remote-domain.v1` is not just documentation theater.
The service owns opaque marketing-ops state, projects status and inspection
fragments, and acknowledges host-driven shutdown over HTTP/JSON.

It also implements `PolicyWindTunnelService` from the Java author kit, which
exposes the Marketing Ops Policy Wind Tunnel provider over
`/policy-wind-tunnel/...`. That matters: Marketing Ops no longer needs
`experiment-service` to discover an in-process pack or carry a Marketing Ops
implementation on the platform classpath.

The optional external A2A collaborator lives separately in the sibling
`marketing-ops-creative-agency` sample. This provider owns Marketing Ops business rules, per-persona state, tools,
policies, population catalogs and Scenario Lab evidence. The companion owns
its A2A endpoint and collaboration behavior; its optional LLM writes advice,
while its existing scenario policy chooses the disposition.

It uses public Java author-kit and conformance artifacts **0.4.2** plus ordinary
Spring dependencies. Both artifacts resolve from Maven Central and use the
published shared contract release **1.2.0**. Building a sibling SDK checkout is
unnecessary. The native capability uses the SDK-generated typed profile;
the sample no longer carries a temporary JSON metadata subclass. It must keep host SPI classes out of its code.

## Requirements

- Java 21
- Maven 3.9+
- Spring Boot `4.1.0`
- Native Jackson 3

## What It Proves

- the remote-domain contract can be generated into a real Spring server
- the service can accept the vendor media type
- prepare, execute-work, status, inspection, and shutdown are all implemented
- Policy Wind Tunnel pack metadata, presets, runs, bundles, artifacts, and
  evidence packs are exposed from the remote domain service
- domain-owned scorecard metadata is published for generic dashboard rendering
- domain-owned Population Builder archetypes and recipes are published through
  `/api/remote-domain/v1/population/catalog`
- Population Builder member templates and validation rules for Marketing Ops
  are published by this provider rather than hard-coded in dashboard generation
- domain state stays opaque to the host and is versioned with `marketing-ops.state.v1`
- no in-process Scenario Lab pack is loaded into `experiment-service`

## What It Does Not Prove Yet

- non-JVM/polyglot implementations beyond the JVM
- production auth and ingress policy for remote domains
- full multi-tenant operational guarantees

Historical integration evidence (the older host routes are not native launch instructions):

- host-side remote registration and manifest discovery exist in `agent-runtime`
- Docker Compose wiring exists for local stack integration
- the same host can run banking remote and `marketing-ops` remote together in lab mode
- `experiment-service` can register Marketing Ops as a remote Scenario Lab pack
  through `scenario.lab.remote.providers`
- Population Builder can discover Marketing Ops population metadata across the
  remote-domain boundary instead of relying on dashboard-owned recipe fixtures
- the default product posture can still stay single-active-domain-first

The point is no longer just “a real service can satisfy the protocol.” The
point is that the host/runtime cutover path is now real, while mixed-domain
coexistence remains a lab capability rather than the default product UX.


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
`marketing-ops.native-events.v3`. Revision headers pin requests and replies.
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

The optional sample-owned `scheduledWork[].payload.reviewFinding` is a nonblank
string of at most 1,200 characters. It supplies a concrete review finding when
that scheduled work executes. For the blocker scenario, the existing approval
record keeps its unresolved `BLOCKED_EXTERNAL` state and its summary becomes
the supplied finding; `lastAction` reports recording the finding, not executing
an external escalation. Without this optional field, prior domain behavior is
preserved. Missing field and an invalid supplied value are different: blank,
non-string or oversized values are rejected.

Before due work, the customer observation contains the existing pending task
summary. The finding exists in operator-owned schedule/checkpoint data but is
not forwarded to the customer's model before the review. The
[example request schema](../integration/marketing-ops-a2a/input.schema.json)
binds `reviewSummary` to that current summary through the platform's existing
fact-binding mechanism. `prepare` at the exact review boundary applies the
finding immediately; repeated identical work replays the same result.

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
actual Creative Agency response. That helper only creates, publishes and
launches; Astraform owns subsequent execution and stopping. Small-run evidence
does not establish multi-customer or multi-year capacity. The sample uses the
published SDKs; this provider-specific change does not require a new SDK release.
