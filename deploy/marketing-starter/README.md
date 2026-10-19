# Marketing starter

Run the Marketing Ops domain and its LLM Creative Agency from images built for
this candidate, then launch a real-model customer through a generated Python SDK
supporting `completionPolicy.requestFactBindingMode`. The updated source templates
select `RESOLVE_TRUSTED`, which published Python **0.3.4** preserves. Current domain
source selects Java SDK **0.4.4** with public contracts **1.2.2**; its callbacks
do not supply customer-launch behavior in the server. Rebuild the tools image
with Python 0.3.4 and use a platform implementing trusted binding. Earlier
candidate images retain their recorded dependencies.
The platform is a separate image bundle. Customer and partner models are
configured independently.

Samples **v0.2.0** prepares a scheduled-marketing public archive targeting
the published [platform v0.3.0](https://github.com/astraform/releases/releases/tag/v0.3.0)
for the previous validation-only configuration; the new source trusted-binding
configuration requires a supporting platform release before that compatibility
claim can be renewed; the native typed SDK support is now published.
The new sample images and archive are not published yet. Public-mode preparation
requires `--platform-release v0.3.0`, anonymous digest-pinned images and verified
source/architecture metadata; release selection remains `publicationReady:false`
until real artifacts are reviewed. The separate `--local-verification` mode
still records installed image IDs and `localVerificationOnly`, including the
current-boundary option. Do not distribute a local rehearsal as a public download
or infer independent partner acceptance from image availability. `image-lock.json`, `release.env`, `starter-config.json`
and the manifest identify its topology, scenario, source and actual payload.
The tools service reuses a Python SDK image, while the marketing launcher and
the shared `customer-run-evidence.py` utility are supplied by this archive and mounted
read-only; they are not claimed to be embedded in that published image.
The shared utility saves and inspects the same accepted run through SDK reads;
the marketing launcher owns its normal generated create/publish/launch calls.
It does not import the banking launcher or start a helper service.

The native provider supplies the run's marketing identity. A custom scenario
may additionally declare `launchRequestTemplate.domainId: "marketing-ops"`; it must
match the provider. Published Python 0.3.4 preserves it, and the selected platform
must implement explicit domain identity. The launcher rejects unsupported typed
serialization before output or Catalog writes. The value is classification, not
an access grant. Omitting it retains the existing provider identity. Public
packaging still rejects an unreleased explicit platform capability rather than
dropping it.

Both updated source marketing templates supply
`completionPolicy.requireCustomerFinish:true` and
`completionPolicy.requestFactBindingMode: "RESOLVE_TRUSTED"`. Astraform fills the
configured bound metadata from accepted observations; the customer model still
chooses whether to contact the agency, its question and unbound facts. Agency
advice and customer FINISH remain autonomous and both strict success conditions
remain required. Published Python SDK 0.3.4 supports FINISH and preserves the
new binding mode. A supporting coordinator implementation and rebuilt tools
images/archives still require separate delivery and verification. Source SDK pins
do not update released images. An older scenario omitting the new mode keeps
legacy exact-match validation; omission does not enable trusted projection. There
is no transport bridge or silent mode removal. Verify the matching platform,
then rebuild and verify the tools image and archive before
distributing this updated starter. Publication does not update existing archives.

## Initialize and run

Use Docker Compose v2, Python 3.10+ for initialization, a fresh initialized
platform bundle, and reachable model configuration. Both bundles use the same
Docker engine. Gemini requires the partner API key; Ollama requires its existing
model and host endpoint to be reachable from a container. Keep the evaluation
on a trusted private network. This starter publishes no host ports.

Verify the archive's SHA256 checksum before extraction. From its directory:

```sh
python3 init.py --platform ../astraform-platform-candidate --model gemini
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

For a verified public archive, run `./starter.sh pull` before starting its services.
Local archives require installed images; do not pull their local tags from a
registry. Platform v0.2.0 cannot run these strict marketing templates. A new launch creates and publishes one Catalog definition and launches
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

Both current scenarios require a qualifying partner receipt **and a later
accepted customer FINISH** through `completionPolicy.requireCustomerFinish:true`.
A selected customer follow-up remains eligible for dispatch after the first
qualifying reply. These conditions do not verify the advice's factual quality or
prove that the external production studio supplied its missing licence. Inspect
the domain observations, actual advice, customer decisions and stop reason separately.
A model mistake remains in evidence; there is no scripted customer or fallback.

The current agency templates select Gemini 3.6 Flash with high reasoning or
Ollama Qwen3.5 4B with thinking enabled by default. They do not
select the customer's model. For Gemini on both roles, independently set
`AI_CHAT_MODEL=gemini-3.6-flash` in the platform's customer profile. Existing
archives and initialized private profiles retain their recorded settings. For an
explicit partner model change, update
`CREATIVE_AGENCY_LLM_BINDING_REVISION` and the matching Runtime target revision,
recreate both services and start a fresh run. Accepted runs keep their provenance.
Other provider settings are documented in the existing Creative Agency guide.

The agency allows output caps 64–32,768 tokens, model reads up to 900 seconds and
agent deadlines up to 900 seconds. Gemini keeps `reasoning_effort=high`,
`max-completion-tokens`, a 16,384-token ceiling, a 60-second complete provider
request deadline and a 70-second outer agent deadline. The complete local Ollama profile uses
`reasoning_effort=high`, `max-tokens`, a 32,768-token ceiling, a 60-second
complete provider-request deadline and a 70-second outer agent deadline. Its raw-response ceiling is 1 MiB so reasoning
and final content can fit without changing the final business-field limits.
Compose uses one model attempt and the adapter's
two-second connect default; the complete provider budget must fit inside the
outer deadline. Initialization derives Runtime request/poll timeouts from that
selected deadline with a 150-second floor: both local test profiles use `150s`.
These A2A transport budgets do not extend either model deadline. A slow or
failed model response is reported without an automatic model retry; thinking stays enabled.
The selected revisions `marketing-starter-gemini36-high-v2` and
`marketing-starter-qwen35-4b-thinking-v3` match the corresponding Runtime targets.
Initialized private profiles are not rewritten; explicitly adopt the complete
profile and update both revisions before a fresh run. An intentional operator
reasoning override remains available. Customer goal wall limits are independent
and are not extended by this profile. These are bounded settings, not duration
or answer-quality guarantees.

Stop only this starter's services with `./starter.sh down`. Its domain checkpoint
and agency task/history state are in memory; it does not provide a durable shared
marketing database. Preserve run exports and the platform's evidence/database
volumes. Do not use global Docker cleanup commands.
