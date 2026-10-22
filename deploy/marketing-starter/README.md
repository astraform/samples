# Marketing starter

These instructions describe the samples **v0.2.2** scheduled-marketing starter,
using published **`ai.astraform:sdk-java:0.4.5`**, **`astraform-sdk==0.3.5`** and
**contracts 1.2.3**. Use the selected archive's compatibility manifest and pinned
images. Download matching archives from the [samples v0.2.2 release](https://github.com/astraform/samples/releases/tag/v0.2.2) only after its assets are available; a source
version or successful build does not publish or update those artifacts.

Published v0.2.1 archives retain the same SDKs, contracts and image digests.
v0.2.2 updates packaged instructions without changing scenarios or services;
the original v0.2.1 archive bytes remain unchanged.

The historical published samples v0.2.0 archives retain Java SDK 0.4.4,
Python SDK 0.3.4 and contracts 1.2.2. Their images and archives remain unchanged.

Run the Marketing Ops domain provider and its LLM Creative Agency from matching images,
then launch a customer through the native Python SDK. The platform is a separate
image bundle; customer and partner models are configured independently. The provider implements the versioned `remote-domain.v1` protocol; the SDK package rename does not change its protocol or profile identifiers.

The authored templates require a platform implementing both
`completionPolicy.requireCustomerFinish` and
`completionPolicy.requestFactBindingMode: "RESOLVE_TRUSTED"`. The current source
tools image targets **`astraform-sdk==0.3.5`**, which preserves both fields. The
domain image must implement `marketing-ops.native-events.v4`; current provider
source targets **`ai.astraform:sdk-java:0.4.5`** and published contracts **1.2.3**.
The supplied public archives already pin matching domain/tools images; running
an unmodified archive requires no image build, host SDK installation or private
source checkout. If you customize service source, dependencies or schemas,
build and verify matching images from your reviewed source and registry locks.
Use a supporting platform release, targeting v0.4.0 or later. Download the
selected platform only after publication and verification. SDK publication does
not add server behavior, and platform v0.3.0's strict FINISH support does not
establish trusted-binding support. Source updates do not update existing images
or archives.

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
The value classifies the run; it does not grant access. The native Python SDK
preserves it. Invalid or unsupported typed fields are rejected before Catalog or launch
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
python3 init.py --platform ../astraform-platform-v0.4.0 --model gemini
```

Select `--model ollama` for the existing local marketing model profile. Configure
`private/partner.env`; Gemini requires `CREATIVE_AGENCY_LLM_API_KEY`. Customer
settings remain in the platform's `private/model.env`. The initializer creates
a private agency token and installs the same value in Runtime's target binding;
never put it in a scenario, customer prompt or shared evidence. Existing
connections/timeouts and partial initialization are refused rather than replaced.
No service or simulation starts during initialization.

For a shared installation, initialize marketing first, then follow the
[Java monthly banking second-domain procedure](https://astraform.ai/docs/starters/monthly-banking/#existing-platform).
The v0.2.2 monthly-banking archive includes the complete procedure in its
README. For unchanged v0.2.1 downloads, use the online procedure; their original
README files are not updated retroactively. The procedure preserves this
starter's bindings, agency token, shared timeout budgets and saved platform runs.
Do not rerun this initializer over an existing banking installation or delete
its connections to bypass the refusal. This documented route adds monthly
banking to marketing; it does not automate arbitrary connection replacement or
live registration. Finish active runs before the required Runtime recreation.

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
registry. Platform v0.2.0/v0.3.0 cannot run these trusted-binding templates. A new launch creates and publishes one Catalog definition and launches
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

The marketing example has one customer goal: understand the actual readiness
finding and get practical agency advice. The customer can clarify an answer or
propose a handoff for agency feedback within that conversation. The models choose
their messages and actions; the example does not prescribe follow-up turns,
require a proposal, force success or perform an external handoff.

The packager's `--scenario scheduled` selection starts at 2026-01-15 08:30 UTC;
the domain's 09:00 review publishes its own finding, and the customer chooses its
actions. `--scenario current` starts at 09:00 with the review admitted at the
current boundary. Both timing variants use the same customer goal and native
domain provider. The scheduled horizon extends to 2026-01-16 10:00 UTC, while the current variant has a fixed
boundary. These are evaluation bounds, not month/year capacity claims.

The selected timing variant is recorded in `starter-config.json`, and its complete
configuration is shipped as `scenario.json`. Compose selects that file through
`MARKETING_SAMPLE_FILE`. Edit the complete scenario before a fresh launch to
change its customer, facts or limits; existing runs retain their accepted
configuration. The source launcher also accepts a complete configuration through
`MARKETING_SAMPLE_FILE`.

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
use the same selected record. When changing the provider or schema from source,
rebuild the domain image and update its matching Runtime binding revision with
the schema before a fresh run. Unmodified public archives already include the
matching schema and image bindings.

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
selected deadline with a 150-second floor: both supplied profiles use `150s`.
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
