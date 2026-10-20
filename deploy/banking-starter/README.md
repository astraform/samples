# Banking starter

The published samples v0.2.0 archives retain Java SDK 0.4.4/Python SDK 0.3.4.
When packaging from newer source, use the generated manifest’s compatibility
section: the next SDK packages are `ai.astraform:sdk-java:0.4.5` and
`astraform-sdk==0.3.5` with contracts 1.2.3. New packages and rebuilt images are
required before distributing a starter using those dependencies.

Run a model-backed banking customer against a banking advice agent over A2A 1.0 JSON-RPC. The customer can read its own account through MCP; the advice agent reads the same partner-owned records through the banking API. Bank data lives in the starter's PostgreSQL database. Astraform stores simulation state and evidence separately. The bank is a domain provider; `remote-domain.v1` and its profile names identify its supported provider protocol.

The published samples **v0.2.0** downloads target **Platform v0.4.0**, with **Java SDK 0.4.4**, **Python SDK 0.3.4** and **contracts 1.2.2** where applicable. Download the platform and one starter archive, verify their checksums, and configure your models. The supplied images include the SDKs and launch tools; no private source checkout, image build or host SDK installation is required for the container workflow.

## Choose a banking scenario

| Variant | Archive | Behavior |
| --- | --- | --- |
| Python issued | `astraform-banking-starter-v0.2.0.tar.gz` | Explain an existing January statement using the Python bank. |
| Java issued | `astraform-java-banking-issued-starter-v0.2.0.tar.gz` | The same enquiry using the Java bank. |
| Java monthly | `astraform-java-banking-monthly-starter-v0.2.0.tar.gz` | Generate January–March statements through the Java native provider. |

The issued customer, Avery, has AUD 100 available and wants to understand the bank's explanation of the AUD 48 minimum, AUD 1,200 full balance and due date. Reading account records can inform the customer's questions; a lookup alone does not satisfy this request for advice. The model chooses its questions, lookups and clarifications. This enquiry does not make or schedule a payment, monitor a due date or generate another statement.

The monthly scenario starts on 15 January and retains participation through 2 April. The bank generates statements through `remote-domain.native-reference-state.v1` and the `banking-reference` binding. The customer can wait, finish a conversation and react to later observations. No script advances a global clock or supplies follow-up messages. The March receipt is its configured goal boundary; inspect January/February work and horizon coverage separately.

`starter-config.json` identifies the selected implementation and scenario. Java uses bank port 8094, JDBC settings and domain `banking`; Python uses port 8092, its PostgreSQL URL and domain `banking-python`. Both use the same advice-agent image and Python customer launcher. A Java bank provider does not select a Java customer client; use the optional Java SDK route below for that.

## Download and extract

Download your selected archive and matching checksum from [Samples v0.2.0](https://github.com/astraform/samples/releases/tag/v0.2.0). Keep them beside the separately extracted [Platform v0.4.0 bundle](https://github.com/astraform/releases/releases/tag/v0.4.0).

Set `archive` to the exact file you downloaded:

```sh
archive=astraform-banking-starter-v0.2.0.tar.gz
shasum -a 256 -c "$archive.sha256"
tar -xzf "$archive"
cd "${archive%.tar.gz}"
```

On Linux, `sha256sum -c` can replace `shasum -a 256 -c`. Keep `bundle-manifest.json`, `image-lock.json`, `release.env` and `starter-config.json` with the extracted files. They record compatibility, package contents and the pinned images; platform and samples versions are independent.

## Prerequisites

- An extracted Platform v0.4.0 bundle, initialized for Gemini or Ollama, with its customer model configured.
- Docker Engine/Desktop with Compose v2, Python 3.10+ for initialization, and the platform bundle's stated resources and image-store requirements. Run both bundles on the same Docker engine.
- A separate partner-agent model choice. Gemini requires a key. Ollama requires the selected model to be installed and reachable from a container; on Linux, a loopback-only listener may be unreachable from the Docker bridge.

The starter publishes no host ports. Partner services connect to the platform's private Docker network; the bank database stays on a separate private network. Use a trusted local/private environment.

## Connect and start

From the extracted starter directory, initialize against a fresh, initialized platform bundle:

```sh
python3 init.py --platform ../astraform-platform-v0.4.0 --model gemini
```

Use the actual platform folder name. `--model ollama` selects the partner's local-model template instead. Initialization writes `.env` and `private/partner.env`, creates `runs/`, backs up the platform Runtime connection file and appends the starter's MCP/A2A bindings and, for monthly banking, its native-domain binding. It starts no services or simulations. Existing banking bindings, request/poll timeouts or an already initialized starter are refused; preserve existing configuration and connect manually instead of overwriting it.

Set `BANKING_LLM_API_KEY` in `private/partner.env` for Gemini. Values must be unquoted single lines. Customer model settings remain in the platform's `private/model.env`: the customer and partner are separate agents and may use different providers.

| Partner setting | Gemini template | Ollama template |
| --- | --- | --- |
| Model | `gemini-3.6-flash` | `qwen3.5:4b` |
| Reasoning | `BANKING_LLM_REASONING_EFFORT=high` | `BANKING_LLM_REASONING_EFFORT=high` (thinking enabled) |
| Output ceiling | 32,768 tokens | 32,768 tokens |
| Temperature | `1.0` | `1.0` |
| Model deadline | 60 seconds | 60 seconds |
| Model attempts | One, without automatic retries | One, without automatic retries |

For Gemini on both sides, select `AI_CHAT_MODEL=gemini-3.6-flash` independently in the platform customer profile and retain the scenario's `local-customer` binding. For Ollama, install `qwen3.5:4b` on your existing server. The partner uses Ollama's OpenAI-compatible API, which cannot set context per request; configure that server/model for at least 65,536 context tokens so the input and output ceiling fit. See [Ollama context configuration](https://docs.ollama.com/api/openai-compatibility#setting-the-local-context-size). The platform's Ollama customer profile also enables thinking and uses a 65,536-token context and 32,768-token output ceiling. Customer memory additionally requires the embedding model and dimensions specified by the platform guide.

Start the platform using its included guide, then start the starter:

```sh
# From the platform bundle directory:
./astraform.sh up -d --wait

# From this starter directory:
./starter.sh config --quiet
./starter.sh pull
./starter.sh up -d --wait
```

If Runtime was already running during initialization, recreate it to load the new connections. From the platform directory:

```sh
./astraform.sh up -d --force-recreate --wait agent-runtime
```

Initialization sets A2A request and poll budgets to the selected partner model deadline plus 60 seconds for bank reads and response handling: `120s` for either supplied template. These transport budgets do not extend the 60-second model deadline or retry model generation. The platform's separate root-event recovery budget handles durable A2A receipts; keep it distinct from model-event/provider retries. Scenario wall-time limits and the launcher's observation wait are separate again.

For a model or generation-setting change, finish existing runs first. Update `private/partner.env` and `BANKING_LLM_BINDING_REVISION`, keep the Runtime target's `simulation.event.runtime.a2a.targets.banking-advice.binding-revision` aligned, then recreate `advice` and `agent-runtime` and launch a fresh run. For customer-model changes, follow the platform guide, including its `AI_BINDING_REVISION` and memory-store requirements. Initialization does not rewrite an existing private profile.

The advice agent bounds the full upstream response, including reasoning, to 1 MiB and accepts a final answer of at most 4,096 characters. Truncated, oversized or late responses fail without repairing the answer, substituting advice or automatically retrying the model. Thinking and a successful transport response do not guarantee accurate advice or customer understanding; inspect the actual answer against bank records.

## Configure the customer and scenario

Review `scenario.json` before a new launch. Persona and goal describe a human purpose; response conditions check configured receipt evidence rather than prescribing the customer's words.

| What you control | Field in `scenario.json` |
| --- | --- |
| Persona and purpose | `catalogDraftRequest.draft.role`, `purpose`, `constraints`, `additionalInstructions` |
| Customer goal | `catalogDraftRequest.draft.goals` |
| Customer model binding | `catalogDraftRequest.draft.execution.modelBindingKey` (`local-customer` for either profile) |
| Tool allowance | `catalogDraftRequest.draft.tools`: customer-scoped `get_customer_accounts` and its `maxCalls` limit |
| Bank seed | `bankCustomerSeed.accounts` and `bankCustomerSeed.statements` |
| Initial personal facts | `launchRequestTemplate.facts` and `initialAgentState` |
| Required partner evidence | `launchRequestTemplate.completionPolicy.partnerResponseConditions` |
| Wait for customer completion | `launchRequestTemplate.completionPolicy.requireCustomerFinish` |
| Stopping bounds | `launchRequestTemplate.maxPartnerInteractions`; `completionPolicy.maxCustomerDecisions`, `maxRepeatedExchanges`, `maxWallTimeMs` |

Issued banking permits four partner interactions, ten customer decisions, three repeated exchanges and two hours of wall time. Its `requireCustomerFinish: true` waits for an accepted customer FINISH after matching bank evidence so a useful clarification can continue. It neither forces the customer to finish nor scores whether the prose explains the AUD 100 budget correctly. Omission or explicit false retains receipt-qualified completion. An explicit Boolean still requires a typed SDK that preserves it; the launcher rejects unsupported serialization before bank seeding or Catalog/Journey writes.

The issued bank seed and launch time must match. Extending its horizon alone does not enable monthly statement generation. The monthly archive supplies its own scenario, empty initial statements, native binding and `THROUGH_HORIZON` participation. It omits `requireCustomerFinish`, permits twenty customer decisions and has a ten-minute wall-time bound. Goal status and participation through the horizon are separate results; model failures and exhausted limits remain recorded outcomes.

The launcher creates a fresh bank context, discovers the actual MCP descriptor and pins its binding/schema identities. It binds access to `customer-a`; leave the corresponding placeholders in the scenario. The LLM does not select another customer identity or account. The partner reads the same scoped bank records through its own banking API. Use a separate new run directory for an intentional experiment, and change the scenario only before that launch.

### Compatibility

Use the releases recorded by the selected archive. Issued banking's strict-FINISH behavior requires Platform v0.3.0 or later, and its explicit `domainId` raises the requirement to v0.4.0. The monthly scenario requires retained-participation support. Published Python 0.3.4 and Java 0.4.4 preserve the fields used by these archives. Publishing or installing an SDK does not upgrade platform images.

Older Python SDK 0.3.2 cannot preserve an explicit `requireCustomerFinish` field; do not substitute it for the issued launcher. The historical Samples v0.1.0 archive targets Platform v0.2.0 and Python 0.3.2 instead; follow that archive's own guide and manifest. Initialization never upgrades platform images or silently removes a completion criterion.

## Launch once, then inspect the same run

The `advice` image includes `run-customer.py` and its shared SDK evidence observer in `/app`. From the starter directory:

```sh
./starter.sh run --rm customer --output /runs/first-run --wait --timeout 8220
```

The native SDK launcher seeds the isolated bank context, discovers the scoped tool, creates and publishes one customer, launches one Journey and waits for its evidence. It saves operation identities before writes. `runs/first-run/manifest.json` records the accepted run and Catalog IDs.

The default observation wait is 8,220 seconds, allowing the issued scenario's two-hour admission window and additional time for accepted work and finalization. It does not extend a model deadline or the scenario wall-time bound; the monthly scenario still has its separate ten-minute bound. If the local wait expires, continue reading the accepted run:

```sh
./starter.sh run --rm customer --inspect RUN_ID --output /runs/first-run --wait --timeout 8220
```

Do not launch again because a request timed out. Preserve the output directory, including `session.json` and `launch-request.json`, for reconciliation through the native SDK using the original request identity and deployment. The container launcher refuses a new launch into a nonempty directory and does not automatically retry writes. Its observer makes at most three attempts for a transient read; those reads cannot create another run or retry model generation.

For a new intentional simulation, choose a new directory such as `/runs/second-run`. Keep host-owned references out of model-authored messages and retain failed outcomes rather than rewriting them to make a run pass.

## Use the Java customer SDK

The container launcher uses Python even when the bank provider is Java. To let Java own customer creation, publication, launch and export, prepare the selected banking scenario first:

```sh
./starter.sh run --rm customer --prepare-only --output /runs/java-prepared
```

This seeds one bank context and discovers the scoped MCP descriptor. It writes `runs/java-prepared/prepared-scenario.json` with top-level `catalog` and `launch` objects, plus setup receipts. Preparation writes partner-bank data but makes no Catalog create/publish or Journey launch request and invokes no customer model. Use a new empty directory for each intentional preparation.

Use the [Java client and SDK setup guide](https://astraform.ai/docs/starters/java-banking/#java-sdk-client) with published Java SDK 0.4.4. Pass the prepared banking JSON to `FirstSimulation launch SCENARIO OUTPUT`; do not replace it with the generic returns scenario. The scenario path is its absolute host path, such as `/absolute/path/to/starter/runs/java-prepared/prepared-scenario.json`, rather than the container path `/runs/java-prepared`.

Set `ASTRAFORM_API_BASE` to the platform's deployment origin without `/api`. Choose a separate Java output directory that does not yet exist, such as `first-run`; do not create it in advance. The Java client validates typed field preservation before Catalog writes. Use its inspect/resume commands with the original saved directory and identities after an uncertain response; do not prepare or launch a replacement. The downloadable Java client is separate from the starter archive and requires JDK 21+ and Maven on the host.

## Read the result

Saved under `runs/first-run/`:

- `manifest.json`: run and Catalog identities.
- `run.json`: platform status, goal state and stopping reason.
- `ledger.json`: up to 100 entries per ledger category for bounded inspection.
- `evidence.zip`: exported simulation evidence, including exchanges and tool activity.
- Request/result JSON files: the scenario and exact operations used.

Check `status`, `metadata.nativeProgress.goal.status` and `metadata.nativeProgress.stopReason` in `run.json`. A completed run and achieved configured goal do not establish that the answer is correct. Inspect the actual customer messages, partner replies and MCP activity in the export. For monthly banking, also inspect statement periods, domain work and horizon coverage; a March receipt alone does not establish the earlier review or customer understanding.

Exit 0 means the SDK operation or evidence collection succeeded, including collection of a failed or abandoned goal. Exit 1 indicates a client/API error, 2 invalid arguments and 3 a local observation deadline. Real models can make different communication and tool choices; the launcher does not rewrite them.

For service diagnosis, use `./starter.sh ps` and `./starter.sh logs --tail 100 bank advice`. Keep logs, `.env`, `private/` and customer evidence private. Healthy services do not establish model access: the advice agent's health endpoint does not call the model provider or check whether Ollama has the requested model. The simulation exercises the actual model, MCP and A2A path.

## Stop and preserve data

Stop this starter before taking down the platform network:

```sh
./starter.sh down
```

Ordinary `down` removes its containers and internal network while retaining bank data and the host `runs/` directory. Preserve the platform's database and evidence volumes as well. Use `./starter.sh down -v` only when this starter's bank database is confirmed disposable after saving evidence. Do not use global Docker cleanup commands.

To disconnect permanently, remove this starter's marked banking block from the platform Runtime connection file. Restore the initializer's backup only if no later connection changes need preserving.

## License

Astraform-authored code, configuration and documentation in this component are licensed under [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). Third-party dependencies retain their own terms. This grant does not license Astraform's platform software or other sample components.
