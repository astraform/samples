# Python banking starter

This starter connects a real LLM customer in Astraform to a real LLM banking agent over A2A 1.0 JSON-RPC. The customer reads its own bank account through MCP; the partner agent reads the same bank backend through its banking API. Bank records live in this starter's separate PostgreSQL database. Astraform stores simulation state and evidence in its own database.

This guide targets **Astraform Platform v0.2.0** and the **Python SDK 0.3.1** included in the agent image. You need the platform and starter archives, Docker and your model configuration; no private source checkout or host SDK installation is required. A package marked `localVerificationOnly` requires the recorded locally installed images and is an engineering rehearsal, not a public starter release.

## What you will run

Avery has an already-issued January statement and a budget of AUD 100. The goal is to understand the payment amount, due date and whether the budget covers the minimum or full balance. The customer chooses what to ask and whether to continue. This first example uses fixed bank data; it does not demonstrate monthly statement generation or partner-time progression.

`scenario.json` contains the persona, goal, initial bank seed, MCP tool allowance, completion conditions and execution limits. It is copied from the existing issued-statement example. Defaults permit four partner interactions, ten customer decisions, three repeated exchanges and ten minutes of wall time. The January statement is seeded before the customer is created; customer identities and tool access are bound by the SDK launch, not chosen by the LLM.

Three prebuilt images run the bank, its PostgreSQL database and the banking agent. The agent image also contains the published Python SDK 0.3.1 and the launch/inspect/export helper. This is an evaluation sample; the banking agent reads current bank records per request and does not claim persistent conversation memory or implement real payment execution.

## Download and extract

Use the starter archive and checksum supplied together for your selected release. Check before extracting:

```sh
shasum -a 256 -c astraform-banking-starter-VERSION.tar.gz.sha256
tar -xzf astraform-banking-starter-VERSION.tar.gz
cd astraform-banking-starter-VERSION
```

Replace `VERSION` with the downloaded starter version. On Linux, use `sha256sum -c` instead of `shasum -a 256 -c`. Keep `bundle-manifest.json`, `image-lock.json` and `release.env` with the extracted files: they identify the package contents and exact container images. The starter and platform are separate downloads and can have different version numbers.

Get the platform bundle from the [public v0.2.0 release](https://github.com/astraform/releases/releases/tag/v0.2.0) and follow its included guide to initialize it. Configure its customer model before continuing below.

## Prerequisites

- An extracted Astraform platform bundle, initialized for Gemini or Ollama, with its customer model configured.
- Docker Engine/Desktop with Compose v2, Python 3.10+ for initialization, and the platform bundle's stated resources/image-store requirements. Run both bundles on the same Docker engine.
- A separate choice of partner-agent model. Gemini requires a key; Ollama requires the configured model to be installed and reachable from a container. On Linux, Ollama must listen on a host interface reachable through the Docker bridge.

The starter publishes no host ports. It connects to the platform's private Docker network; its database stays on a separate private network. This is for a trusted local evaluation, not an internet-facing bank deployment.

## Connect and start

From the extracted starter directory, point initialization at a fresh, initialized platform bundle:

```sh
python3 init.py --platform ../astraform-platform-v0.2.0 --model gemini
```

Use the actual platform folder name. `--model ollama` selects the partner's local-model template instead. Initialization writes `.env` and `private/partner.env`, creates `runs/`, saves a backup of the platform Runtime connection file and appends the banking MCP/A2A bindings to it. It refuses existing banking bindings so it cannot silently replace your integration. No services or simulations start during initialization.

Edit `private/partner.env`: for Gemini, set `BANKING_LLM_API_KEY`. Customer-model configuration remains in the platform's `private/model.env`; these are separate agents and may use different providers. The supplied partner defaults are Gemini 2.5 Flash or Ollama Qwen3.5 4B. Keep values as unquoted single lines. For a partner model change, update `BANKING_LLM_BINDING_REVISION`, recreate `advice` and use a fresh run. For customer-model or Runtime connection changes, follow the platform guide, update the affected binding revision and recreate `agent-runtime`. Finish existing runs before changing these settings; the new configuration is for fresh launches.

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

The starter initializer sets an A2A timeout of 150 seconds for Gemini or 650 seconds for Ollama. The partner request timeout is 120 or 600 seconds respectively. These connection settings are separate from the customer's model timeout and the scenario's ten-minute wall-time limit. Adjust all relevant limits for a slow local model rather than assuming a longer HTTP timeout extends the simulation.

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
| Stop limits | `launchRequestTemplate.maxPartnerInteractions`; `completionPolicy.maxCustomerDecisions`, `maxRepeatedExchanges`, `maxWallTimeMs` |

The goal is a human purpose; the response conditions check structured bank facts such as the account, statement month, amount and due date. Keep those conditions consistent if you change the seeded bank records. They do not score whether the partner's prose explains the AU$100 budget correctly; review that in the conversation evidence.

The helper supplies new catalog/request identities, discovers the configured MCP descriptor and binds the fresh bank context to `customer-a`. Leave the corresponding placeholders in the scenario; the LLM does not select its customer identity or another customer's account. The customer can read only its assigned account context. The partner agent reads the same bank through the backend API, using the host-provided context.

This scenario uses the `GOAL` completion policy and a fixed logical time. Its bank seed and launch `logicalTimeEpochMs` must match. Extending a horizon alone does not add monthly statement generation or time-progress notifications to this starter.

## Launch once, then inspect the same run

From this starter directory:

```sh
./starter.sh run --rm customer --output /runs/first-run --wait --timeout 900
```

The SDK helper seeds a new isolated bank context, discovers the scoped MCP tool, creates and publishes a customer definition, launches one Agent Journey and waits for its evidence. It saves request identities before writes. `runs/first-run/manifest.json` records the accepted run and catalog IDs.

If the local wait expires, use the saved `runId` to continue reading that run:

```sh
./starter.sh run --rm customer --inspect RUN_ID --output /runs/first-run --wait --timeout 900
```

Do not launch again merely because a request timed out. If launch acceptance is uncertain, `launch-request.json` preserves the exact request identity for reconciliation using the SDK/API; the helper refuses to launch into a nonempty output directory. It deliberately does not retry writes. Inspection retries transient read errors at most three times and cannot create another run.

A new intentional experiment uses a new directory such as `/runs/second-run`. Editing `scenario.json` changes future launches only. Keep host-owned customer references out of model-authored messages; use the existing catalog/launch configuration to assign each customer's access.

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
