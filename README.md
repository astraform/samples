# Astraform Samples

Build and adapt partner services that Astraform customer agents can exercise.
Platform operations use the native Java/Python SDKs directly; partner services
expose A2A, MCP and domain APIs. The published **samples v0.2.0** images and
starter archives target **platform v0.4.0**, Java **0.4.4**, Python **0.3.4**
and contracts **1.2.2**. Their dependency names and bytes remain unchanged.

This source checkout targets the next SDK releases: **`ai.astraform:sdk-java:0.4.5`**
and **`astraform-sdk==0.3.5`**, built against published **contracts 1.2.3**.
Registry-only source builds require those SDK releases to be published first.
See [SDK development](#sdk-development) before building this checkout.

Clone the examples to build and adapt them:

```sh
git clone https://github.com/astraform/samples.git
cd samples
```

## Choose your first simulation

| Start here | What it runs |
| --- | --- |
| [Python banking starter](deploy/banking-starter/README.md) | An image-only deployment with a separate bank database, an LLM banking partner and one customer asking about an issued statement. Available in the published samples v0.2.0 release. Follow its bundled dependency requirements. |
| [Banking from sample source](banking-advice-agent/README.md) | Build either bank and configure the shared LLM partner. The default and monthly cases use the published customer SDK; strict issued-statement completion and the declared domain label use the published native SDK and require the supporting platform capabilities described in the guide. |
| [Marketing from sample source](integration/marketing-ops-a2a/README.md#native-customer-simulation) | Build the Marketing Ops provider and LLM Creative Agency, configure their connections and launch a customer seeking campaign advice. |

For the historical starter v0.1.0 and compatible non-strict source scenarios,
the public baseline is [Astraform Platform v0.2.0](https://github.com/astraform/releases/releases/tag/v0.2.0).
The current source scenarios and published v0.2.0 starters target platform **v0.4.0**
for explicit banking domain identity and marketing trusted request-fact binding.
Obtain its archive/checksum from the public platform releases.
Platform v0.3.0 supplies strict customer-FINISH behavior but does not supply
these additional capabilities. Verify the selected server and image versions
before launching.
Follow the selected bundle's README to verify, extract and initialize it, select
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
| [Java provider starter](starter-java-spring/README.md) | Minimal Java/Spring domain lifecycle and file-backed population catalog for a new provider |
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

Each SDK includes both integration directions: domain interfaces for Astraform
to call your provider, and a platform client for configuring customers, launching
simulations and retrieving results. Current source dependencies are:

- [Astraform SDK for Java](https://github.com/astraform/sdk-java): `ai.astraform:sdk-java:0.4.5`, with Java 21 and Maven 3.9+.
- [Astraform SDK for Python](https://github.com/astraform/sdk-python): `astraform-sdk==0.3.5`, with Python 3.12+.
- Published [contracts 1.2.3](https://github.com/astraform/remote-domain-contracts/releases/tag/v1.2.3).

These SDK names are prepared for the next SDK releases. Wait for their Maven
Central/PyPI publication before a registry-only source build. The two Python
modules retain their last genuine registry locks until then; regenerate and
review both `uv.lock` files after publication before using `uv sync --locked`
or building images. Do not replace registry hashes with guessed download URLs.
Java provider tests use `ai.astraform:conformance-java:0.4.5`; Python conformance
uses `astraform-conformance==0.3.5`. Replace the old conformance distribution
rather than installing both, because they provide the same imports and command.
Java source providers use
Spring Boot's parent and do not inherit the SDK's `ai.astraform:sdk-java-parent`.

The published v0.2.0 starters continue to use
[`ai.astraform:remote-domain-author-kit-java:0.4.4`](https://central.sonatype.com/artifact/ai.astraform/remote-domain-author-kit-java/0.4.4)
and [`astraform-remote-domain-author-kit==0.3.4`](https://pypi.org/project/astraform-remote-domain-author-kit/0.3.4/).
The new names preserve Java/Python imports and public APIs. Replace the old
Python distribution rather than installing both: they provide the same import
packages. No separate platform-client package is required.

Domain providers implement the versioned `remote-domain.v1` protocol. Its
protocol IDs, routes, profile names and SDK imports retain their existing names;
the SDK package rename does not change the provider integration contract.

The native generated clients preserve `domainId`,
`completionPolicy.requestFactBindingMode` and an explicit
`completionPolicy.requireCustomerFinish` Boolean. The launchers reject older
unsupported SDKs before writes; they never drop or manually insert fields.
Strict completion and trusted bindings also require the supporting platform
implementation. New source pins do not upgrade existing images or archives.

The root `compose.yaml` builds sample services from this repository; it does not
start Astraform. New installations use the Compose project `astraform-samples`;
Compose's standard `COMPOSE_PROJECT_NAME` environment setting overrides it.
Select the services required by your chosen guide and supply their model settings
before startup. Use the public platform bundle alongside it.
[Deployment and ownership](docs/PLATFORM_PACKAGING.md) explains the connection
model and how to replace a sample with your own agent or domain service.

For an existing deployment, keep its current project name before running Compose.
Project names determine the default network and volume names, so switching names
would select different database volumes. Inspect the actual labels, including
volumes whose containers have already stopped:

```sh
docker compose ls --all
docker ps --all --format '{{.Names}}: {{.Label "com.docker.compose.project"}}'
docker volume ls --filter label=com.docker.compose.volume=python-banking-data
docker volume inspect <existing-volume> --format '{{index .Labels "com.docker.compose.project"}}'
```

Set `COMPOSE_PROJECT_NAME=<existing-project-name>` in this checkout's ignored
local `.env`, or export it in the shell before every Compose command. Render
`docker compose config` to confirm the project and volume names before startup.
This reuses the existing volume namespace; no migration or volume removal is
needed. Keep the separate platform bundle's project and evidence-volume settings
unchanged as well.

## Test maintenance

Keep tests that exercise business behavior, validation, serialization, HTTP/SDK
integration, security boundaries and generated package integrity. Do not add tests
that snapshot source, documentation, POM or workflow text, ban class names, or
freeze editorial wording, version strings and catalog/default getter values.
Exercise behavior with inputs and observable results instead.

## Releases

All examples share one repository release version, such as **v0.1.0**. Each
[release](https://github.com/astraform/samples/releases) provides the source for
all examples and any prepared starter archives. Choose the domain/language you
need; you do not need to install all samples.

The published **v0.2.0** release provides Python-issued, Java-issued and
Java-monthly [banking starters](deploy/banking-starter/README.md#java-and-monthly-banking)
and a [scheduled marketing starter](deploy/marketing-starter/README.md), targeting
platform **v0.4.0**. Their manifests retain Java SDK **0.4.4**, Python SDK
**0.3.4** and contracts **1.2.2**. `release.json` records the published revisions,
image digests and archive checksums; preserve that immutable release selection.
The historical **v0.1.0** Python banking starter retains platform **v0.2.0**,
Python SDK **0.3.2** and contracts **1.2.0**.

The next source release uses the renamed SDK packages described in
[SDK development](#sdk-development). Publish those SDKs and regenerate the Python
registry locks before building new images. Select a new samples version and
review its actual source/image revisions, digests and archive checksums before
preparing a new release manifest. Do not reuse v0.2.0 artifact identities.
The generic [Java provider starter](starter-java-spring/README.md) is source-only
in this checkout. Build or copy it from samples; it is no longer a Maven Central
template artifact in the Java SDK release. It does not add a new image or starter
archive to the published v0.2.0 selection.
The existing packagers accept an explicit supporting platform release for
public-mode candidates and retain a separate `localVerificationOnly` mode for
installed-image rehearsals. Neither mode publishes artifacts.

Platform deployment bundles remain in [astraform/releases](https://github.com/astraform/releases/releases).
Platform and SDK versions are independent of the samples version. Only download
assets after their release is published; passing CI is not publication.

See the [changelog](CHANGELOG.md), [v0.2.0 release notes](docs/releases/v0.2.0.md), [historical v0.1.0 notes](docs/releases/v0.1.0.md)
and [maintainer publication instructions](docs/PUBLICATION_CHECKLIST.md). One
**Publish Samples Release** workflow prepares or publishes the repository release.
Image publication is separate and is needed only when images change.

Current source publishes the five sample images in this repository's package set:
`ghcr.io/astraform/samples-java-spring-banking`,
`ghcr.io/astraform/samples-marketing-ops-remote`,
`ghcr.io/astraform/samples-marketing-ops-creative-agency`,
`ghcr.io/astraform/samples-banking-starter-bank` and
`ghcr.io/astraform/samples-banking-starter-advice`. Each image records
`https://github.com/astraform/samples` as its OCI source and the build commit as
its revision. Publish the complete set from one reviewed new source commit;
select only its verified digests for the next starter release. Previously
published packages and v0.1.0 assets retain their original identities and bytes.
The image workflow and the starter release workflow remain separate manual
actions; changing package names or passing CI does not publish either.

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
