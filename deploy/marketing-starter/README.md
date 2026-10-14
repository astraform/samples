# Marketing starter — local verification candidate

Run the Marketing Ops domain and its LLM Creative Agency from prebuilt images,
then launch a real-model customer through Python SDK **0.3.2**. The domain image
uses Java SDK **0.4.2**; this verifies its provider callbacks, not a Java customer
launcher. Shared contracts remain **1.2.0**. The platform is a separate image
bundle. Customer and partner models are configured independently.

This new starter is `localVerificationOnly`: it requires the installed image IDs
recorded in `bundle-manifest.json`. No marketing starter has been published.
Do not distribute it as a verified public download or infer partner acceptance
from image availability. `image-lock.json`, `release.env`, `starter-config.json`
and the manifest identify its topology, scenario, source and actual payload.
The tools service reuses a Python SDK image, while the marketing launcher and
its existing banking evidence helper are supplied by this archive and mounted
read-only; they are not claimed to be embedded in that published image.

## Initialize and run

Use Docker Compose v2, Python 3.10+ for initialization, a fresh initialized
platform bundle, and reachable model configuration. Both bundles use the same
Docker engine. Gemini requires the partner API key; Ollama requires its existing
model and host endpoint to be reachable from a container. Keep the evaluation
on a trusted private network. This starter publishes no host ports.

Verify the archive's SHA256 checksum before extraction. From its directory:

```sh
python3 init.py --platform ../astraform-platform-v0.2.0 --model gemini
```

Select `--model ollama` for the existing local marketing model profile. Configure
`private/partner.env`; Gemini requires `CREATIVE_AGENCY_LLM_API_KEY`. Customer
settings remain in the platform's `private/model.env`. The initializer creates
a private agency token and installs the same value in Runtime's target binding;
never put it in a scenario, customer prompt or shared evidence. Existing
connections/timeouts and partial initialization are refused rather than replaced.
No service or simulation starts during initialization.

Start the platform, then recreate its Runtime to load the new connections:

```sh
# From the initialized platform directory:
./astraform.sh up -d --wait
./astraform.sh up -d --force-recreate --wait agent-runtime

# From this starter directory:
./starter.sh config --quiet
./starter.sh up -d --wait
./starter.sh run --rm customer --output /runs/first --wait
```

Local archives require installed images; do not pull their local tags from a
registry. A new launch creates and publishes one Catalog definition and launches
one journey. The helper saves those operation identities before its first write.
A response timeout never causes a replacement customer or launch.

Resume inspection of the returned run, using the same API deployment:

```sh
./starter.sh run --rm customer --inspect RUN_ID --output /runs/first --wait
```

Terminal results, ledger inspection and `evidence.zip` preserve failed goals as
well as successful ones. The observer defaults to1,500 seconds for the scenario's
1,200-second wall limit; changing an observer timeout does not extend a simulation.
`--inspect` performs only reads. Do not rerun a launch after an uncertain response.

## Scenario and provider configuration

The `scheduled` candidate starts at 2026-01-15 08:30 UTC; the domain's 09:00 review
publishes its own finding, and the customer chooses its actions. The `current`
variant starts at09:00 with the review admitted at the current boundary. Both
reuse the existing marketing scenarios and native domain provider. The scheduled
horizon extends to 2026-01-16 10:00 UTC, while the current variant has a fixed
boundary. These are evaluation bounds, not month/year capacity claims.

The goal conditions check a qualifying partner receipt. They do not verify the
advice's factual quality, require a later customer FINISH, or prove that the
external production studio supplied its missing licence. Inspect the domain
observations, actual advice, customer decisions and stop reason separately.
A model mistake remains in evidence; there is no scripted customer or fallback.

The supplied agency templates preserve the existing Gemini 2.5 and Ollama Qwen3.5
4B marketing profiles; they do not select the customer's model or apply the
banking thinking profile. For an explicit partner model change, update
`CREATIVE_AGENCY_LLM_BINDING_REVISION` and the matching Runtime target revision,
recreate both services and start a fresh run. Accepted runs keep their provenance.
Other provider settings are documented in the existing Creative Agency guide.

The agency allows output caps 64–16,384 tokens, model reads up to 120 seconds and
agent deadlines up to 300 seconds. Supplied Gemini uses 120 seconds/140 seconds;
Ollama uses 90 seconds/110 seconds, and both fit the Runtime 150-second request/poll
budgets. Gemini's existing `token-limit-parameter=omit` profile sends no token
ceiling; the local raw response limit still applies. Do not copy banking's32,768
output or840-second partner timeout into this adapter.

Stop only this starter's services with `./starter.sh down`. Its domain checkpoint
and agency task/history state are in memory; it does not provide a durable shared
marketing database. Preserve run exports and the platform's evidence/database
volumes. Do not use global Docker cleanup commands.
