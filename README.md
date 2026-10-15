# Astraform Samples

Build and adapt partner services that Astraform customer agents can exercise.
Platform operations use the published Java/Python SDKs directly; partner
services expose A2A, MCP and domain APIs. The next samples release, **v0.2.0**,
is prepared locally for **platform v0.3.0**, Java **0.4.3**, Python **0.3.3** and
contracts **1.2.1**. Those new platform/sample artifacts are not yet published.
Strict issued banking and marketing require that supporting platform as well
as the native typed SDK. The immutable public baseline remains samples v0.1.0
with platform v0.2.0 and its original dependency versions.

Clone the examples to build and adapt them:

```sh
git clone https://github.com/astraform/samples.git
cd samples
```

## Choose your first simulation

| Start here | What it runs |
| --- | --- |
| [Python banking starter](deploy/banking-starter/README.md) | An image-only deployment with a separate bank database, an LLM banking partner and one customer asking about an issued statement. Available as a starter archive in the published repository release v0.1.0. That archive retains its original scenario and completion behavior. |
| [Banking from sample source](banking-advice-agent/README.md) | Build either bank and configure the shared LLM partner. The default and monthly cases use the published customer SDK; strict issued-statement completion uses the published native SDK and requires the supporting local platform described in the guide. |
| [Marketing from sample source](integration/marketing-ops-a2a/README.md#native-customer-simulation) | Build the Marketing Ops provider and LLM Creative Agency, configure their connections and launch a customer seeking campaign advice. |

For the historical starter v0.1.0 and compatible non-strict source scenarios,
the public baseline is [Astraform Platform v0.2.0](https://github.com/astraform/releases/releases/tag/v0.2.0).
Current strict templates and the next starter release target platform **v0.3.0**;
obtain its archive/checksum from the [public release list](https://github.com/astraform/releases/releases)
only after publication. Follow the selected bundle's README to verify, extract
and initialize it, select
Gemini or Ollama for customer agents, and start the platform. The platform bundle
and editable sample source are separate: the samples do not build or import
Astraform runtime modules.

The public [deployment guide](https://astraform.ai/deploy/) explains the platform
installation. The selected sample guide explains its own services and scenario.
A healthy platform is only the starting point; launch a simulation to observe
actual model calls, tool access and conversations.

## Configure the platform, customer and partner

| Configuration | Where it belongs |
| --- | --- |
| Astraform customer model and memory | The extracted platform bundle's `private/model.env` |
| Partner A2A endpoint, customer MCP endpoint and optional domain provider | The bundle's `private/runtime.properties` |
| Customer persona, goals, permitted tools, scenario facts and stop limits | The sample scenario JSON, submitted through the SDK |
| Partner-agent model, prompt, credentials and history | The partner service's own configuration |
| Bank accounts or campaign state | The partner service/database |

Use `http://localhost:8084` as the SDK API base for the bundle's default port;
choose the actual configured port if different. Generated clients add `/api/...`.
The bundle's `local-customer` model binding works with either supplied model
profile. Changing the platform customer model does not change the partner model.

Runtime connects to partner URLs from inside a container. For host-run services,
the sample guides use `host.docker.internal`; Linux host services must listen on
an interface reachable from the Docker bridge. `localhost` inside Runtime refers
to Runtime itself. Keep these evaluation services on a trusted local/private
network; the sample banking routes do not implement public-facing authentication.

After changing connections, recreate `agent-runtime` from the extracted platform
bundle directory:

```sh
./astraform.sh up -d --wait --force-recreate agent-runtime
```

Update the affected binding revision and start a fresh run after configuration
changes. The SDK creates and publishes a customer definition, then launches an
Agent Journey. Astraform owns subsequent customer execution. Preserve the returned
run ID and inspect/export that run instead of launching again after a timeout.
Customer choices, partner mistakes and failed goals remain in the evidence; a
completed execution does not by itself mean the customer achieved its goal.

## Sample services

| Component | Role |
| --- | --- |
| [Java banking](java-spring-banking/README.md) | Java/Spring bank, customer-scoped MCP access and optional monthly statement work |
| [Python banking](python-fastapi-banking/README.md) | Python/FastAPI bank using the same public contracts and shared banking scenarios |
| [Banking Advice](banking-advice-agent/README.md) | LLM partner agent that answers using the selected bank's records over A2A |
| [Marketing Ops](marketing-ops-remote/README.md) | Java/Spring campaign-state provider and scheduled domain work |
| [Creative Agency](marketing-ops-creative-agency/README.md) | LLM marketing partner agent over A2A |
| [Refund Policy](refund-policy-agent/README.md) | Small A2A refund example with explicit deterministic and Ollama modes |

Banking partner records live separately from Astraform state and evidence.
Customers receive only their configured tool grants and account context. An A2A
conversation alone does not advance partner business data; monthly statements
and other scheduled changes require the optional native domain integration.
The banking and marketing guides describe those bindings and their limitations.

## SDK development

Each author kit includes both integration directions: domain interfaces for
Astraform to call your provider, and a platform client for configuring customers,
launching simulations and retrieving results. Use:

- Java: [`ai.astraform:remote-domain-author-kit-java:0.4.3`](https://central.sonatype.com/artifact/ai.astraform/remote-domain-author-kit-java/0.4.3), with Java 21 and Maven 3.9+ for these samples.
- Python: [`astraform-remote-domain-author-kit==0.3.3`](https://pypi.org/project/astraform-remote-domain-author-kit/0.3.3/), with Python 3.12+ and the module's committed `uv.lock`.
- Public [contracts 1.2.1](https://github.com/astraform/remote-domain-contracts/releases/tag/v1.2.1), covering both platform and domain-provider APIs.

The Java modules resolve the SDK from Maven Central; Python resolves it from
PyPI. Conformance packages remain separate validation tools. Those released
dependencies need no SDK checkout, private build parent or standalone
platform-client package. See each module README for its build and test commands.

Published Java 0.4.3 and Python 0.3.3 preserve an explicit
`completionPolicy.requireCustomerFinish` Boolean through their native generated
clients. The launchers retain their preflight guard for older unsupported SDKs;
they never drop or manually insert the field. Current bank/advice source locks
install Python 0.3.3. Strict completion also requires the supporting platform
implementation; the published platform v0.2.0 and samples v0.1.0 images/archives
retain their original behavior. New source pins do not upgrade existing images.

The root `compose.yaml` builds sample services from this repository; it does not
start Astraform. Select the services required by your chosen guide and supply
their model settings before startup. Use the public platform bundle alongside
it. [Deployment and ownership](docs/PLATFORM_PACKAGING.md) explains the connection
model and how to replace a sample with your own agent or domain service.

## Releases

All examples share one repository release version, such as **v0.1.0**. Each
[release](https://github.com/astraform/samples/releases) provides the source for
all examples and any prepared starter archives. Choose the domain/language you
need; you do not need to install all samples.

The published **v0.1.0** release includes the Python banking starter archive and
checksum. Java, marketing and the other examples are available as source; they
do not yet have published starter archives. The released banking starter targets
platform **v0.2.0**, Python SDK **0.3.2** and contracts **1.2.0**.

The **v0.2.0 preparation** selects Python-issued, Java-issued, Java-monthly
[banking starters](deploy/banking-starter/README.md#local-java-and-scheduled-statement-candidates)
and a [scheduled marketing starter](deploy/marketing-starter/README.md), requiring
platform **v0.3.0**. The existing packagers accept an explicit supporting platform
release for public-mode candidates and retain a separate `localVerificationOnly`
mode for installed-image rehearsals. Neither mode publishes artifacts.
`release.json` stays `publicationReady:false` with unset artifact identities until
the actual images and archives pass review. Do not use the old v0.2.0 platform
download for strict templates or treat this source plan as a published release.

Platform deployment bundles remain in [astraform/releases](https://github.com/astraform/releases/releases).
Platform and SDK versions are independent of the samples version. Only download
assets after their release is published; passing CI is not publication.

See the [changelog](CHANGELOG.md), [v0.2.0 preparation notes](docs/releases/v0.2.0.md), [historical v0.1.0 notes](docs/releases/v0.1.0.md)
and [maintainer publication instructions](docs/PUBLICATION_CHECKLIST.md). One
**Publish Samples Release** workflow prepares or publishes the repository release.
Image publication is separate and is needed only when images change.

## Example data

The [seeded banking personas](scenarios/demo/seeded-cohorts/README.md) are
entirely invented/generated demo data, including their names, dates of birth and
account scenarios. They were not copied from real customers. Keep real partner
records and simulation exports out of this repository.

## License

All Astraform-authored code, configuration and documentation in this repository,
including the Java, Python, banking, marketing and refund examples, is licensed
under [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). Partners may use,
modify and redistribute these examples under that license. Component-level
LICENSE/NOTICE copies are retained for standalone images and starter archives.
Third-party dependencies retain their own terms. This grant does not license
the separately distributed Astraform platform or its private source.
