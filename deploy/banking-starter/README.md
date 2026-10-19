# Banking starter

This starter connects a real LLM customer in Astraform to a real LLM banking agent over A2A 1.0 JSON-RPC. The customer reads its own bank account through MCP; the partner agent reads the same bank backend through its banking API. Bank records live in this starter's separate PostgreSQL database. Astraform stores simulation state and evidence in its own database.

The immutable published baseline uses **Astraform Platform v0.2.0** and **Python SDK 0.3.2** in the bank/advice images. Current source targets **Java SDK 0.4.4 / Python SDK 0.3.4** for newly built local candidates; a source version target does not upgrade an existing image. You need the platform and starter archives, Docker and your model configuration; no private source checkout or host SDK installation is required. A package marked `localVerificationOnly` requires the recorded locally installed images and is an engineering rehearsal, not a public starter release.

The current issued-statement scenario opts into `completionPolicy.requireCustomerFinish: true`. It requires the supporting platform v0.3.0 implementation and a customer SDK whose generated completion-policy model preserves this field. Platform v0.3.0 is published; samples v0.2.0 remains in preparation. Use the new sample images and starter archives only after they are published and verified. Published platform v0.2.0 and Python SDK 0.3.2 do not support it. A customer launcher using SDK 0.3.2 rejects any explicit Boolean value before bank seeding or create/publish/launch writes; it does not add the field through a transport bridge. Inspection of an existing run still uses the published SDK. Existing published images and archives retain their recorded dependency versions. Older `starter-config.json` manifests retain their recorded `requiresUnreleasedCustomerSdk` and `requiredCustomerSdkCapabilities` markers. Published Python SDK 0.3.4 supplies the native client capability; using it requires a new verified customer installation/image and the supporting platform. Existing archive bytes and recorded image versions are unchanged.

The banking image owns `run-customer.py` and the shared `customer-run-evidence.py` beside it in `/app`. The updated Docker build includes both. This archive does not supply replacement launcher scripts or a helper service, and an older published image is not assumed to contain the updated code.

## What you will run

Avery has an already-issued January statement and a budget of AUD 100. The goal is to obtain and understand the banking advice service's explanation of the payment amount, due date and what that personal budget covers against the minimum and full balance. Reading account records can inform the customer's questions, but a lookup alone does not satisfy this request for the bank's advice. The customer shares its budget amount/currency and chooses its own questions, lookups, clarifications and stopping decision. The enquiry is resolved when the customer understands the bank's explanation and has no useful clarification left. Payment processing, due-date monitoring and another statement are outside this enquiry. This first example uses fixed bank data; it does not demonstrate monthly statement generation or partner-time progression.

`scenario.json` contains the persona, goal, initial bank seed, MCP tool allowance, completion conditions and execution limits. It is copied from the existing issued-statement example. The issued-statement defaults permit four partner interactions, ten customer decisions, three repeated exchanges and two hours of wall time. The January statement is seeded before the customer is created; customer identities and tool access are bound by the SDK launch, not chosen by the LLM.

The current issued-statement source scenario also supplies optional
`launchRequestTemplate.domainId`. Packaging uses the explicit starter identity:
`banking-python` for Python or `banking` for Java. This run label is not an access
grant or tenant boundary. Published Python 0.3.4 and Java 0.4.4 preserve the
field; a supporting platform implementing explicit domain identity is also
required. The launcher rejects unsupported typed serialization before bank setup
or Catalog writes, including preparation. Public packaging continues to refuse
this scenario while its required platform capability is unreleased; local
rehearsal records that server requirement explicitly.
Native monthly scenarios retain provider-derived identity without the field.

Three prebuilt images run the bank, its PostgreSQL database and the banking agent. The agent image contains the launch/inspect/export helper. The historical public v0.1.0 image contains Python SDK 0.3.2; a current candidate must be rebuilt with a customer SDK supporting its selected scenario. This is an evaluation sample; the banking agent reads current bank records per request and does not claim persistent conversation memory or implement real payment execution.

## Download and extract

Select a published [Astraform Samples release](https://github.com/astraform/samples/releases) and download its banking starter archive and matching checksum. Samples share one repository version; each prepared starter is a separate asset, so you do not need to install the other examples. Check before extracting:

The prepared v0.2.0 release uses these distinct names; download them only after
publication. Choose the asset matching your bank and scenario:

| Variant | Archive |
| --- | --- |
| Python issued | `astraform-banking-starter-v0.2.0.tar.gz` |
| Java issued | `astraform-java-banking-issued-starter-v0.2.0.tar.gz` |
| Java monthly | `astraform-java-banking-monthly-starter-v0.2.0.tar.gz` |

```sh
archive=astraform-banking-starter-v0.2.0.tar.gz
shasum -a 256 -c "$archive.sha256"
tar -xzf "$archive"
cd "${archive%.tar.gz}"
```

Set `archive` to the exact downloaded filename, including its version. The historical Python archive uses `v0.1.0`. On Linux, use `sha256sum -c` instead of `shasum -a 256 -c`. Keep `bundle-manifest.json`, `image-lock.json` and `release.env` with the extracted files: they identify the package contents and exact container images. The starter and platform are separate downloads and can have different version numbers.

For the immutable starter v0.1.0, use the [public platform v0.2.0 release](https://github.com/astraform/releases/releases/tag/v0.2.0). The prepared starter v0.2.0 requires the published [platform v0.3.0 bundle](https://github.com/astraform/releases/releases/tag/v0.3.0); download its archive and checksum and verify them before initialization. Follow the selected bundle's guide and configure its customer model before continuing.

## Prerequisites

- An extracted Astraform platform bundle, initialized for Gemini or Ollama, with its customer model configured.
- Docker Engine/Desktop with Compose v2, Python 3.10+ for initialization, and the platform bundle's stated resources/image-store requirements. Run both bundles on the same Docker engine.
- A separate choice of partner-agent model. Gemini requires a key; Ollama requires the configured model to be installed and reachable from a container. On Linux, Ollama must listen on a host interface reachable through the Docker bridge.

The starter publishes no host ports. It connects to the platform's private Docker network; its database stays on a separate private network. This is for a trusted local evaluation, not an internet-facing bank deployment.

## Connect and start

From the extracted starter directory, point initialization at a fresh, initialized platform bundle:

```sh
python3 init.py --platform ../astraform-platform-v0.3.0 --model gemini
```

Use the actual platform folder name. `--model ollama` selects the partner's local-model template instead. Initialization writes `.env` and `private/partner.env`, creates `runs/`, saves a backup of the platform Runtime connection file and appends the banking MCP/A2A bindings to it. It refuses existing banking bindings so it cannot silently replace your integration. No services or simulations start during initialization.

Edit `private/partner.env`: for Gemini, set `BANKING_LLM_API_KEY`. Customer-model configuration remains in the platform's `private/model.env`; these are separate agents and may use different providers. The current source partner templates select Gemini 3.6 Flash or Ollama Qwen3.5 4B. To use Gemini 3.6 on both sides, select `AI_CHAT_MODEL=gemini-3.6-flash` independently in the platform profile and keep its customer binding key aligned with the scenario. Existing archives and initialized private profiles retain their original values. Keep values as unquoted single lines. For a partner model change, update `BANKING_LLM_BINDING_REVISION`, recreate `advice` and use a fresh run. For customer-model or Runtime connection changes, follow the platform guide, update the affected binding revision and recreate `agent-runtime`. Finish existing runs before changing these settings; the new configuration is for fresh launches.

Start the platform using its guide, then the starter:

```sh
# Run this from the platform bundle directory first:
./astraform.sh up -d --wait

# Run the following from this starter directory:
./starter.sh config --quiet
./starter.sh pull
./starter.sh up -d --wait
```

If Runtime was already running when you initialized the starter, recreate it to load the new connections. From the platform directory:

```sh
./astraform.sh up -d --force-recreate --wait agent-runtime
```

The Gemini and Ollama banking templates use `reasoning_effort=high`, a 32,768-token output ceiling and temperature `1.0`. Both local test templates limit each model call to 60 seconds with one attempt. Initialization adds 60 seconds around the selected model deadline for bank reads and A2A response handling, producing request and poll budgets of `120s` for either provider. These transport budgets do not extend the model deadline or retry model generation. The Runtime target and partner use the same selected binding revision (`banking-starter-gemini36-high-v2` for Gemini). Initialization refuses an existing request or poll timeout before writing anything, and refuses an already initialized starter; it never upgrades or overwrites a private profile. These limits are separate from the customer model and the scenario's two-hour wall-time limit (7,200,000 ms). A longer HTTP timeout does not itself extend the simulation.

The local source Ollama partner preset enables thinking with `BANKING_LLM_REASONING_EFFORT=high`, uses temperature `1.0`, and identifies the configuration as `banking-starter-qwen35-4b-thinking-v9`. To adopt it, build `advice` from the updated source and update both `BANKING_LLM_BINDING_REVISION` and the platform Runtime target's `simulation.event.runtime.a2a.targets.banking-advice.binding-revision`. Review the partner configuration, recreate `advice` and `agent-runtime`, then use a fresh run. Existing published images and private profiles retain their original provenance. The partner bounds the whole upstream response, including reasoning, to 1 MiB and returns only the original final answer (at most 4,096 characters). Thinking does not prove factual accuracy; check the actual answer against its bank records.

The current local platform-source Ollama customer profile keeps `AI_CHAT_THINK=true`, context 65,536, output ceiling 32,768 and temperature `1.0`, with `AI_RESPONSE_TIMEOUT=60s`, `AGENT_RUNTIME_MODEL_INVOCATION_TIMEOUT=60s` and `simulation.event.runtime.model-event-max-attempts=1`. The single-generation setting requires the matching updated Runtime source; an existing published bundle is not upgraded by editing its configuration. Existing archives and initialized profiles retain their original values: update `private/model.env` and `AI_BINDING_REVISION` explicitly before recreating Runtime and launching a fresh run. Both agents still use `qwen3.5:4b`. The partner uses Ollama's OpenAI-compatible API, which cannot set context per request; its server/model configuration must allow 65,536 tokens so the request input plus generation ceiling fits. Check the existing server context before testing; a new model alias or server restart is unnecessary when that context is already configured. See [Ollama context configuration](https://docs.ollama.com/api/openai-compatibility#setting-the-local-context-size). The advice business prompt, date guards, source facts and response schema are unchanged by the thinking settings. It compares a customer-stated budget with issued minimum and closing debt while distinguishing arithmetic from future posting, fees and payment allocation. The budget remains self-reported; no payment is made or presumed. Initialization preserves existing private profiles and accepted runs. Truncated, oversized or late responses fail without repair or model retry. Review actual model results and bank evidence; these limits do not guarantee customer understanding or accurate advice.

## Configure the customer and scenario

Edit `scenario.json` before a new launch. These existing API fields are the configuration used by the SDK helper:

| What you control | Field in `scenario.json` |
| --- | --- |
| Customer persona and purpose | `catalogDraftRequest.draft.role`, `purpose`, `constraints`, `additionalInstructions` |
| Customer goal | `catalogDraftRequest.draft.goals` |
| Customer model binding | `catalogDraftRequest.draft.execution.modelBindingKey` (`local-customer` names the platform binding for either Gemini or Ollama) |
| Customer tool allowance | `catalogDraftRequest.draft.tools`: this starter supports the customer-scoped `get_customer_accounts` MCP tool and its `maxCalls` limit |
| Bank data available before the run | `bankCustomerSeed.accounts` and `bankCustomerSeed.statements` |
| Personal facts supplied to the customer | `launchRequestTemplate.facts` and `initialAgentState` |
| Partner response requirements | `launchRequestTemplate.completionPolicy.partnerResponseConditions` |
| Wait for customer completion | `launchRequestTemplate.completionPolicy.requireCustomerFinish` (native SDK 0.3.4 and supporting platform v0.3.0) |
| Stop limits | `launchRequestTemplate.maxPartnerInteractions`; `completionPolicy.maxCustomerDecisions`, `maxRepeatedExchanges`, `maxWallTimeMs` |

The goal is a human purpose; the response conditions check structured bank facts such as the account, statement month, amount and due date. Keep those conditions consistent if you change the seeded bank records. The local `requireCustomerFinish: true` setting also waits for an accepted customer `FINISH` after matching evidence, so a clarification request can continue instead of being cut off by the earlier receipt. It neither forces the customer to finish nor scores whether the partner's prose explains the AU$100 budget correctly; review that in the conversation evidence. Omission retains earlier receipt-only stopping behavior. Explicit false has that same runtime meaning but still requires a typed SDK that preserves the field. The launcher validates the raw Boolean and its typed round-trip, then calls the normal generated journey API; it never drops or manually reinserts the setting.

The launcher supplies new catalog/request identities, discovers the configured MCP descriptor and binds the fresh bank context to `customer-a`. Leave the corresponding placeholders in the scenario; the LLM does not select its customer identity or another customer's account. The customer can read only its assigned account context. The partner agent reads the same bank through the backend API, using the host-provided context.

This scenario uses the `GOAL` completion policy and a fixed logical time. Its bank seed and launch `logicalTimeEpochMs` must match. Extending a horizon alone does not add monthly statement generation or time-progress notifications to this starter.

## Launch once, then inspect the same run

From this starter directory:

```sh
./starter.sh run --rm customer --output /runs/first-run --wait --timeout 8220
```

The SDK helper seeds a new isolated bank context, discovers the scoped MCP tool, creates and publishes a customer definition, launches one Agent Journey and waits for its evidence. The default observer wait is 8,220 seconds: the two-hour admission window plus a 900-second admitted call and 120 seconds for finalization. This read wait does not extend the scenario deadline or retry a launch. It saves request identities before writes. `runs/first-run/manifest.json` records the accepted run and catalog IDs.

If the local wait expires, use the saved `runId` to continue reading that run:

```sh
./starter.sh run --rm customer --inspect RUN_ID --output /runs/first-run --wait --timeout 8220
```

Do not launch again merely because a request timed out. If launch acceptance is uncertain, `launch-request.json` preserves the exact request identity for reconciliation using the SDK/API; the helper refuses to launch into a nonempty output directory. It deliberately does not retry writes. Inspection retries transient read errors at most three times and cannot create another run.

A new intentional experiment uses a new directory such as `/runs/second-run`. Editing `scenario.json` changes future launches only. Keep host-owned customer references out of model-authored messages; use the existing catalog/launch configuration to assign each customer's access.

## Prepare for the existing Java customer SDK client

A Java bank provider and a Python customer launcher are separate SDK boundaries.
From an extracted, freshly built candidate, prepare the same scenario without
creating a Catalog definition or launching a Journey:

```sh
./starter.sh run --rm customer --prepare-only --output /runs/java-prepared
```

This uses the existing launcher to seed one isolated bank context and discover
its scoped MCP descriptor. It saves `runs/java-prepared/prepared-scenario.json`
with top-level `catalog` and `launch` objects, plus the seed and capability
receipts. Preparation makes partner-bank setup writes, but performs **zero
Catalog create/publish or Agent Journey launch writes**. It does not execute a
customer model or manufacture a customer decision. Use a new empty output
folder for every intentional preparation.

Pass that JSON to the existing downloaded Java `FirstSimulation` client, built
with published Java SDK 0.4.4 for scenarios declaring `domainId`. The client
must validate typed preservation before Catalog writes, and the platform must
implement the declared domain identity. A custom scenario omitting it retains
provider-derived identity, subject to its other required capabilities. Use its documented
classpath and `launch SCENARIO OUTPUT` command, the extracted platform's public
origin as `ASTRAFORM_API_BASE`, and a separate new, not-yet-existing Java output directory (for example, `first-run`); do not create it in advance.
For example, the Java command's scenario argument is the absolute host path
`/absolute/path/to/starter/runs/java-prepared/prepared-scenario.json`, not the
container path `/runs/java-prepared`. Use the same client's inspect/resume
commands to collect evidence from the accepted run; do not prepare or launch a
replacement after a timeout. The Java client is a separate existing download;
this starter archive does not embed it or imply a new public release.

## Read the result

Saved under `runs/first-run/`:

- `manifest.json`: run and catalog identities.
- `run.json`: actual platform status, customer goal and stopping reason.
- `ledger.json`: up to 100 entries per ledger category for a quick inspection.
- `evidence.zip`: the complete exported simulation evidence, including exchanges and tool activity.
- Request/result JSON files: the scenario and exact create/publish/launch operations used.

In `run.json`, check `status`, `metadata.nativeProgress.goal.status` and `metadata.nativeProgress.stopReason`. For example, `COMPLETED` with `ACHIEVED` / `GOAL_ACHIEVED` reports the configured goal outcome; `FAILED` with `ABANDONED` / `GOAL_ABANDONED` preserves the customer's failure to complete it. Open `evidence.zip` to inspect the actual customer messages, partner replies and MCP activity before judging answer quality.

A completed command does not mean the customer achieved its goal. Exit 0 means the SDK operation or evidence collection succeeded; a failed or abandoned goal remains recorded as such. Exit 1 indicates a client/API error, 2 invalid arguments, and 3 a local inspection deadline. Read `run.json` and the evidence before deciding whether the partner behaved correctly. The customer and partner are real models: answers and tool/communication choices can vary, and the helper does not rewrite them to make a run pass.

For service diagnosis, use `./starter.sh ps` and `./starter.sh logs --tail 100 bank advice`. Keep service logs, `.env`, `private/` and customer evidence private. A healthy startup confirms the local services and their configuration loaded. The partner health endpoint does not call the model provider or verify that an Ollama model is installed. The SDK simulation checks actual model, MCP and A2A interaction.

## Stop and preserve data

Stop the starter before taking down the platform, because the starter uses its network:

```sh
./starter.sh down
```

This removes the starter containers and its internal network while retaining bank data and `runs/`. Then stop the platform through its guide. `./starter.sh down -v` also deletes this starter's bank database; use it only for a disposable evaluation after saving evidence. Do not use global Docker prune commands. To disconnect permanently, remove this starter's marked banking block from the platform connection file; the initializer's backup is safe to restore only if no later connection changes need preserving.

## License

The Astraform-authored code, configuration and documentation in this component are licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). This permits use, modification and redistribution under that license. Third-party dependencies retain their own terms. This grant does not license Astraform's platform software or other sample components.


## Local Java and scheduled-statement candidates

The existing packager selects `--bank python|java` and `--scenario issued|monthly`.
Public-mode preparation requires `--platform-release v0.3.0`, digest-pinned
anonymous images and their actual full source revision. The shared samples
v0.2.0 release plan selects Python-issued, Java-issued and Java-monthly variants.
Its `publicationReady:false` selection is completed only after real artifact
review; preparing an archive does not publish it. The separate
`--local-verification` mode retains installed-image IDs and explicit local-only
markers, including the existing Python-monthly option. Do not mix the two modes.
Historical v0.1.0 archives and digests remain immutable.

`starter-config.json` identifies the implementation, bank port, native domain
profile and scenario. Java uses port 8094, JDBC settings and domain`banking`;
Python uses port 8092, its PostgreSQL URL and domain`banking-python`. Both reuse
the same separate bank database, banking advice service and Python customer
launcher. The monthly scenario omits `requireCustomerFinish` and can use published
Python SDK 0.3.2 for launch/inspect/export. The strict issued scenario requires a
native typed customer SDK that preserves its explicit completion field; its
client capability is published in Python SDK 0.3.4 and the supporting platform
v0.3.0 is published. Coordinated sample image/archive delivery remains pending. Earlier candidate
images retain their recorded Java 0.4.2 / Python 0.3.2 dependencies. A fresh local
candidate must verify its assembled Java 0.4.4 / Python 0.3.4 image contents;
manifest source targets alone do not prove installed versions. The default
customer launcher remains Python, so running it against a Java bank proves the
Java provider path, not a Java customer launch/export path. Use the preparation
route above when verifying the Java customer SDK.

The `monthly` variant copies the existing three-month scenario to`scenario.json`,
enables `remote-domain.native-reference-state.v1` and installs the existing
`banking-reference` domain binding alongside MCP and A2A. It starts at January 15
and retains participation through April 2, permitting statements for January,
February andMarch. It requires Runtime's`retainedParticipationSupported`
capability. The existing launcher discovers/pins capabilities and gives each
run a fresh bank context; no script advances time or sends follow-up messages.

The March partner receipt is the configured goal boundary; it alone does not
prove January/February review, customer understanding or horizon coverage.
Inspect actual domain work, statement periods, MCP/A2A activity and participation
coverage in the same run's exported evidence. The source's ten-minute monthly
wall limit is separate from the issued scenario's two-hour limit. Model failures
and exhausted limits remain recorded outcomes.

For all local candidates, use the packaged images already installed and follow
the existing initialization/start/inspect instructions. Do not pull local tags.
The issued variant explicitly requires the customer-FINISH behavior targeted
by platform v0.3.0; the public v0.2.0 download cannot supply it. Public-mode
manifests record the selected release and required capabilities without claiming
that it is published. Local manifests retain the unreleased-platform warning.
Initialization never upgrades platform images or silently removes the criterion.
