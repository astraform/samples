# Marketing starter

Run the Marketing Ops domain and its LLM Creative Agency from matching images,
then launch a customer through the native Python SDK. The platform is a separate
image bundle; customer and partner models are configured independently.

The authored templates require a platform implementing both
`completionPolicy.requireCustomerFinish` and
`completionPolicy.requestFactBindingMode: "RESOLVE_TRUSTED"`. Python SDK **0.3.4**
preserves both fields. The domain image must implement
`marketing-ops.native-events.v4`; its source uses Java SDK **0.4.4** and public
contracts **1.2.2**. Use matching domain/tools images and a supporting platform
release. SDK publication does not add server behavior, and platform v0.3.0's
strict FINISH support does not establish trusted-binding support. Source updates
do not update existing images or archives.

Public archives use anonymous digest-pinned images. Check `image-lock.json`,
`release.env`, `starter-config.json` and the manifest for the selected images,
scenario and compatibility requirements. Archives prepared with
`--local-verification` instead require their recorded image IDs already installed;
keep those archives local rather than distributing them as public downloads.
The tools image provides the Python SDK. This archive supplies the marketing
launcher and its shared SDK evidence observer through read-only mounts.

The provider supplies the run's marketing identity. A custom scenario may also
set `launchRequestTemplate.domainId: "marketing-ops"`, which must match the
provider and requires explicit domain-identity support in the selected platform.
The value classifies the run; it does not grant access. Python SDK 0.3.4 preserves
it. Invalid or unsupported typed fields are rejected before Catalog or launch
writes rather than silently removed. Omitting domainId retains provider-derived
identity.

With trusted binding, Astraform supplies bound metadata from accepted domain
observations. The customer chooses whether to contact the agency, its message
and unbound facts. A qualifying agency response and a later customer FINISH are
both required; a received response alone does not establish advice quality.
A custom scenario omitting trusted-binding mode retains legacy exact-match
validation. There is no transport bridge or silent mode removal.

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

Choose the customer case when preparing an archive: the marketing packager's
`--case advice` is the default, and `--case proposal-review` selects the separate
proposal-assessment goal. Advice asks for an actionable recommendation; proposal
review asks the customer to develop its own handoff and seek the agency's
assessment of its coverage and remaining gaps. The models choose the messages
and actions. Neither case scripts follow-up turns or performs an external handoff.
Proposal-review packaging uses the scheduled boundary. The existing advice case
also supports `--scenario current`.

The selected case is stored in `starter-config.json` and its complete template is
shipped as `scenario.json`. Compose explicitly selects that file through
`MARKETING_SAMPLE_FILE`; it takes precedence over the source launcher's `--case`
flag. To change a packaged case, prepare another archive or edit its complete
scenario before launching. The source launcher can select either authored case
directly when that environment variable is unset.

The `scheduled` candidate starts at 2026-01-15 08:30 UTC; the domain's 09:00 review
publishes its own finding, and the customer chooses its actions. The `current`
variant starts at09:00 with the review admitted at the current boundary. Both
reuse the existing marketing scenarios and native domain provider. The scheduled
horizon extends to 2026-01-16 10:00 UTC, while the current variant has a fixed
boundary. These are evaluation bounds, not month/year capacity claims.

All authored scenarios require a qualifying partner receipt **and a later
accepted customer FINISH** through `completionPolicy.requireCustomerFinish:true`.
A selected customer follow-up remains eligible for dispatch after the first
qualifying reply. These conditions do not verify the advice's factual quality or
prove that a proposed handoff was assessed or that the external production
studio supplied its missing licence. Inspect
the domain observations, actual advice, customer decisions and stop reason separately.
A model mistake remains in evidence; there is no scripted customer or fallback.

Use a domain image implementing `marketing-ops.native-events.v4` with the
updated templates and input schema. The provider publishes one `selectedReview`
with its approval identity, campaign, queue state, summary and explicit
`reviewFacts`. The facts separate completed review from blocked launch approval,
known document supplier from unknown coordination owner, and unknown rights,
licensing authority and delivery date. `KNOWN` carries a value; `UNKNOWN` and
`UNREPORTED` do not establish absence. Facts become visible only when the review
executes; historical missing facts remain unreported. Trusted agency bindings
use the same selected record. Rebuild the domain image and update its Runtime
binding revision together with the schema before using these configurations.

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
