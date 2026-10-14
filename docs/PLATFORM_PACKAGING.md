# Deploy Astraform and connect your services

A partner deployment has three parts: the Astraform platform, your services and
data, and a client that configures and launches simulations. Your services stay
outside Astraform and use public protocols. You do not need platform source to
build a domain provider or run a customer simulation.

## Platform installation

Download the archive and matching checksum from the public
[Astraform Platform v0.2.0 release](https://github.com/astraform/releases/releases/tag/v0.2.0).
The bundle contains image-only Compose deployment, locked image references,
initialization tools and operating instructions. Follow its included README to
verify the archive, initialize a Gemini or Ollama profile, configure your model
and start the services. The platform is distributed separately from these samples.

The initialized bundle gives you:

| File | What you configure |
| --- | --- |
| `.env` | Deployment identity, host API port and generated internal/database credentials |
| `private/model.env` | Customer model, embedding/memory configuration and provider credentials |
| `private/runtime.properties` | Partner A2A targets, customer MCP tools and optional native domain providers |

The SDK API base is the dashboard/gateway origin, `http://localhost:8084` by
default. Generated clients supply `/api/...`. Only that gateway is exposed by
the evaluation bundle; your client does not need a direct internal Runtime port.

Use the bundle's documented resource requirements and a trusted local/private
network. This single-host evaluation deployment does not supply public ingress,
per-user authentication, high availability or a production certification.

## Choose a sample or use your existing agent

- [Image-only Python banking starter](../deploy/banking-starter/README.md): a
  prepared starter archive with a separate bank database, LLM banking partner
  and one customer scenario. It is planned for the shared samples v0.1.0
  release; use only assets that appear on an actual published release.
- [Banking source guide](../banking-advice-agent/README.md): build the Java or
  Python bank and use the shared Banking Advice partner and SDK launcher.
- [Marketing source guide](../integration/marketing-ops-a2a/README.md#native-customer-simulation):
  build the Marketing Ops domain and Creative Agency partner, then configure
  and launch a marketing customer.
- Your own existing A2A agent: configure its target URL and create customers
  through the SDK. Add customer MCP tools or a native domain provider only when
  your scenario needs them.

Each sample service uses published dependencies. The Java providers use author
kit **0.4.2**; the Python bank and launch clients use author kit **0.3.2**; both
use public contracts **1.2.0**. The author kits include the platform client and
domain authoring interfaces. No second platform-client SDK or platform checkout
is required. Follow the module README for its build and runtime requirements.

## Connect endpoints

These are the implemented property names in the platform bundle's
`private/runtime.properties`. Replace the example names and endpoints with your
own, and preserve any existing bindings. Customer configuration in API requests
must refer to those exact binding keys.

```properties
# Existing partner agent: receives messages over A2A.
simulation.event.runtime.a2a.targets.partner-agent.base-url=http://host.docker.internal:8099
simulation.event.runtime.a2a.targets.partner-agent.binding-revision=partner-agent-v1
simulation.event.runtime.a2a.targets.partner-agent.allow-insecure-http=true
simulation.event.runtime.a2a.request-timeout=150s

# Optional customer tools: the SDK grants individual customers access.
simulation.event.runtime.mcp.bindings.partner-tools.endpoint=http://host.docker.internal:8098/mcp
simulation.event.runtime.mcp.bindings.partner-tools.binding-revision=partner-tools-v1
simulation.event.runtime.mcp.bindings.partner-tools.allowed-tool-names=get_customer_accounts
simulation.event.runtime.mcp.bindings.partner-tools.allow-insecure-http=true

# Optional native domain: partner-owned business changes at simulated boundaries.
simulation.event.runtime.domain.bindings.partner-domain.base-url=http://host.docker.internal:8098
simulation.event.runtime.domain.bindings.partner-domain.domain-id=your-domain
simulation.event.runtime.domain.bindings.partner-domain.binding-revision=partner-domain-v1
simulation.event.runtime.domain.bindings.partner-domain.allow-insecure-http=true
```

The MCP tool name above is the banking example. Use your service's declared tool
names and schemas for another domain. The banking and marketing guides provide
their exact A2A request-schema and domain bindings; a target URL alone does not
supply a scenario's structured message contract.

URLs must be reachable from Runtime's container. `host.docker.internal` addresses
a host-run service; on Linux, that service must listen on an interface reachable
from the Docker bridge. For another container use a shared-network hostname.
Do not use `localhost` for a different service. Enable cleartext HTTP only on a
trusted local/private network. Keep tokens and provider credentials in deployment
configuration, outside scenario JSON, source control and exported evidence.

After changes, run from the extracted platform bundle directory:

```sh
./astraform.sh config --quiet
./astraform.sh up -d --wait --force-recreate agent-runtime
```

Give changed connection semantics a new binding revision and launch fresh runs.
Do not change bindings underneath an active run and assume it will safely adopt
the new settings. The platform bundle's README covers restart, backup and
configuration ownership.

## Create a customer and launch a simulation

The SDK workflow is:

1. Discover the configured customer tools and their descriptors when using MCP.
2. Create a Catalog draft with persona, purpose, goals, constraints, model binding
   and permitted tools.
3. Publish that customer definition and retain its revision identifiers.
4. Launch an Agent Journey with the revision, partner target, external customer
   context, logical start/horizon and bounded stopping policy.
5. Preserve the accepted run ID. Inspect its progress and export evidence through
   the SDK instead of submitting a replacement launch after a timeout.

For the bundle's customer profiles, `local-customer` is the model binding key for
either Gemini or Ollama. Configure a partner agent's model separately in its own
service. Sample scenario JSON contains editable persona, goal, facts, tool grants
and execution limits; the sample guides identify those exact fields.

Your backend owns account records, campaign state and other business data.
Populate the required records before launch. Astraform stores customer definitions,
execution state and evidence, including returned tool data; it does not become
your business database. Grant a customer access to its own external context,
rather than exposing an unrestricted customer/account selector to the model.

A2A and MCP alone do not move your business data through simulated time. For a
time-dependent scenario, implement the native domain provider contract. Astraform
coordinates a logical boundary; your provider performs and records the business
update and returns its receipt and next eligible boundary. The banking sample
uses this for monthly statements. No global clock or Kafka tick broadcast is
required. A decision opportunity lets the customer choose whether to act; it
does not require a message on every boundary.

## Evaluate and adapt the result

Inspect the customer goal, stop reason, conversations, actual tool receipts and
domain changes. An execution can finish with an unmet or abandoned goal. A
model's unhelpful answer is evidence to evaluate, not an installation failure to
hide. Service health and protocol conformance are useful separate checks, but
neither proves answer quality or capacity for thousands of customers.

To replace the sample, keep the same integration boundaries: your A2A agent
handles conversations, MCP exposes the customer tools you choose, and an optional
domain provider owns scheduled business updates. Change the scenario and bindings,
then launch a fresh customer/run. Astraform communicates over the declared APIs;
it does not import your provider code or access its storage directly.

## Distribution and licenses

All samples use one repository release version. A shared release contains the
source for every example and separate starter archives for the examples packaged
for deployment. Adding a language or framework does not create another release
stream, and downloading one starter does not install the others.

The published first samples release, **v0.1.0**, includes a Python banking starter targeting
platform **v0.2.0**, Python SDK **0.3.2** and contracts **1.2.0**. Java and marketing
remain source examples in that release; they do not yet have starter archives.
Local starter preparation now covers Java/Python banking (issued or monthly)
and marketing (scheduled or current-boundary), using the shared deterministic
packaging owner. Each new candidate is explicitly `localVerificationOnly`,
records installed image IDs and payload hashes, and leaves published locks,
release metadata and archives unchanged. The issued banking candidate requires
the unreleased customer-FINISH Experiment/API fix; it is not compatible with a
public v0.2.0 platform download. See the [banking guide](../deploy/banking-starter/README.md#local-java-and-scheduled-statement-candidates)
and [marketing guide](../deploy/marketing-starter/README.md). Java SDK 0.4.2
provider callbacks and Python SDK 0.3.2 customer launch/export are distinct
verification paths; these candidates do not certify a Java customer client.

Download published starters from the [samples release list](https://github.com/astraform/samples/releases).
The platform and SDKs are versioned independently; platform bundles are on the
separate [platform release list](https://github.com/astraform/releases/releases).

All Astraform-authored examples, configuration and documentation in this
repository use [Apache License 2.0](../LICENSE); see [NOTICE](../NOTICE). The
standalone Python images and starter retain component license copies. This
grant excludes Astraform platform software and its private source. Third-party
dependencies retain their own terms.

Maintainers preparing archives and images should use the separate
[publication instructions](PUBLICATION_CHECKLIST.md). The partner setup above
requires released platform artifacts, public SDK packages and the selected
sample services, not a private platform build or an internal proof script.
