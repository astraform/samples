# Python FastAPI Banking Remote Domain

Python banking reference implementation for `remote-domain.v1`, using published
Python author kit `0.3.2` and shared contract bundle `1.2.0`. The locked SDK comes from
PyPI; this sample does not require an editable checkout of the SDK or access
to Astraform's platform source. Use the public
[platform v0.2.0 bundle](https://github.com/astraform/releases/releases/tag/v0.2.0)
for the running platform and its included README for initialization.

The native customer-simulation path reuses the banking rules in this provider
and the [shared banking advice scenarios](../banking-advice-agent/README.md).
The bank stores authoritative records in its own PostgreSQL database. Astraform
stores the customer persona, goals, permitted tools, references and run evidence.
The customer calls the scoped MCP tool; the LLM banking partner reads the same
bank through its HTTP API.

The existing snapshot-based remote-domain, product catalog, decision-trigger and
Policy Wind Tunnel contracts remain available. Native parity is scoped to
prepopulated accounts and monthly credit-card statements; it does not imply all
Java banking behavior or shared-run population capacity is implemented.

## Native customer simulation with a separate bank database

The SDK runs synchronous domain callbacks on worker threads. This sample keeps
the SDK default of one admitted provider callback per app/process. Health checks
and independent banking API/MCP reads can continue while that callback waits for
database I/O. An additional SDK callback receives retryable HTTP 503
`provider_busy` rather than joining a queue; clients should retry with backoff
and the same idempotency key. Cancellation retains the callback's slot until its
work finishes. Database pool size is separate from SDK callback capacity.

The sample uses pooled database connections acquired within each operation.
Its existing sequential customer scenarios and bounded dispatch checks do not
establish general concurrent-load capacity. See [SDK 0.2.1 release notes](https://github.com/astraform/remote-domain-sdk-python/releases/tag/v0.2.1)
for the adapter's concurrency and timeout boundaries.

From this directory, start the Python sample's own database and provider:

```bash
docker compose -f compose.database.yaml up -d --wait
uv sync --locked
BANKING_BACKEND_ENABLED=true \
BANKING_BACKEND_DATABASE_URL=postgresql://banking:banking@127.0.0.1:5545/banking_python \
ASTRAFORM_NATIVE_SIMULATION_ENABLED=true \
ASTRAFORM_NATIVE_SIMULATION_PROFILE=remote-domain.native-reference-state.v1 \
  uv run --locked uvicorn banking_remote_domain_python.main:app --host 0.0.0.0 --port 8092
```

`compose.database.yaml` owns a separate PostgreSQL container and named volume;
it shares neither the Astraform database nor the Java sample database. Port
`5545` can be changed with `PYTHON_BANKING_DB_HOST_PORT`. The sample defaults use
database `banking_python` and user/password `banking`. Set
`BANKING_BACKEND_DATABASE_URL` for your deployment; `BANKING_BACKEND_MAX_POOL_SIZE`
defaults to `8`. Database and native support are opt-in for a directly started
process; the repository-root Compose service enables both for the Python sample.

The provider exposes the same business API and MCP tool as the Java sample:

| Interface | Purpose |
| --- | --- |
| `PUT /api/banking/v1/contexts/{contextRef}/customers/{subjectRef}` | Seed the partner-owned account and issued-statement data before launch |
| `GET /api/banking/v1/contexts/{contextRef}/customers/{subjectRef}/accounts` | Partner read; optional `statementPeriod=YYYY-MM` selects an issued closing month |
| `/mcp`, tool `get_customer_accounts` | Customer read using host-supplied customer identity, with no model-selected account/customer arguments |
| Existing SDK prepare and `domain-system-work-window` routes | Attach the seeded customer and commit eligible monthly statement work |

Unfiltered reads return the latest 12 statements. A period filter selects from
the full history and returns no statements when the month has not been issued.
An attached simulation supplies exact `contextId`, `resourceVersion` and
`logicalTimeEpochMs` read guards. Stale or foreign context guards fail rather
than returning data from a different simulated point. Authentication remains
deferred: these development routes require a trusted private network, and the
host-supplied customer context is not a credential.

The reference profile stores account changes and the operation response in the
same transaction. Retries replay the committed response; they do not issue a
second statement. A fresh independent simulation must seed a fresh context.
The supported native work kind is `credit_card_statement_close`; the provider
uses its existing monthly calculation rather than a separate scripted engine.
The customer observation includes `observedAtUtc` and, when a statement is
scheduled within the horizon, `nextStatementAtUtc`. These ISO 8601 UTC strings
represent the same authoritative values as `observedAtEpochMs` and
`nextStatementAtEpochMs`; numeric timestamps still control coordination. They
make the bank's actual schedule readable without prescribing customer actions.

Use the same [scenario files and launch client](../banking-advice-agent/README.md#shared-java-and-python-scenarios)
for both providers. Keep personas, goals, seed records and limits the same; change
the bank URL and the deployment's MCP/domain/A2A bindings. The
[Python endpoint setup](../banking-advice-agent/README.md#python-banking-endpoint-bindings)
provides the complete binding properties and matching partner startup command.
The setup helper uses the platform client included in the existing Astraform author
kit, alongside domain authoring. Both are included in the published `0.3.2` author kit used by
this sample's registry-resolved lock. Conformance remains separate validation
tooling. See [SDK dependency setup](../README.md#sdk-development).

These bindings go in the extracted platform bundle's `private/runtime.properties`;
the customer model remains in `private/model.env`. Use the bundle gateway origin
as `ASTRAFORM_API_BASE` (`http://localhost:8084` at the default port), with no
`/api` suffix. Recreate Runtime from the bundle directory after editing bindings:

```sh
./astraform.sh up -d --wait --force-recreate agent-runtime
```

Runtime reaches a host-run bank through `http://host.docker.internal:8092`.
On Linux, the bank must listen on an interface reachable from the Docker bridge.
No platform source checkout or platform build is involved.

## Customer Decision Trigger Policy

The provider evaluates committed banking evidence with
`banking.customer-decision-trigger-policy.v1`. It supports statement-close,
payment-due, payment-missed, material-interest, material-fee, credit-limit,
rate/policy, hardship, liquidity, and optional monthly-review triggers. Trigger
payloads contain selected domain facts only, never prompts or model output.
Allowed facts must also match their bounded scalar type; prompt,
system-prompt, raw-provider, and chain-of-thought fields are rejected
recursively at any nesting level before a trigger is emitted.

Every numeric fact in a decision-trigger payload uses the versioned
`canonical_decimal_string.v1` encoding. This keeps financial precision and
large identifiers identical across Java, Python, and I-JSON consumers;
excessive precision, scale, or magnitude is rejected instead of rounded.

Defaults suppress routine noise: monthly review is disabled, credit interest
must reach `25.00`, and a fee must reach `10.00`. A population can override
only `enabled`, `priority`, and `minimumAmount` under
`personaConfiguration.decisionTriggerPolicy`:

```json
{
  "decisionTriggerPolicy": {
    "interest_charged": {
      "minimumAmount": 20,
      "priority": "HIGH"
    },
    "monthly_review": {
      "enabled": true,
      "priority": "NORMAL"
    }
  }
}
```

Unknown policies, unknown fields, invalid priorities, negative thresholds,
and non-boolean enablement fail preparation. The Java and Python policies are
covered by matching V1 event vectors and the live provider parity gate.

Scenario Lab exposes `EVENT_ONLY` as the explicit event-driven activation
mode. Activated personas receive a zero calendar interval; only durable
decision triggers can wake them. In the current v1 contract this provider can
publish policy triggers from `domain-system-work-window`. Persona
`execute-work` responses cannot yet carry triggers, and the optional monthly
review mapping does not itself schedule a review. Native customer scenarios
configure decision opportunities and domain work separately; enabling this
policy alone does not make the customer act.

## Domain Shape

- Domain ID: `banking-python`
- Schema version: `banking-python.state.v1`
- Policy Wind Tunnel pack: `banking-python-policy-pack`
- Scorecards:
  - `banking-python.hardship-customer-impact.v1`
  - `banking-python.liquidity-buffer.v1`
- Default port: `8092`

## Local Run without the bank database

```bash
cd python-fastapi-banking
uv sync --locked
uv run --locked uvicorn banking_remote_domain_python.main:app --host 0.0.0.0 --port 8092
```

Health check:

```bash
curl http://localhost:8092/actuator/health
```

Banking product catalog:

```bash
curl http://localhost:8092/api/products
curl 'http://localhost:8092/api/products?productCategory=CRED_AND_CHRG_CARDS'
```

Remote Policy Wind Tunnel metadata:

```bash
curl http://localhost:8092/policy-wind-tunnel/pack
curl http://localhost:8092/policy-wind-tunnel/presets
```

## Compose

Build the sample container from the repository root:

```bash
docker build -f python-fastapi-banking/Dockerfile -t astraform/python-fastapi-banking:local .
```

The local image writes a `build-sha256` identity derived from the complete
provider wheel set and resolved Python environment. Production
`PLATFORM_OWNED_V1` deployments must start an exact image manifest and inject
that independently resolved digest:

```bash
DIGEST='sha256:<exact platform manifest digest>'
docker run \
  -e "ASTRAFORM_PROVIDER_ARTIFACT_DIGEST=oci-${DIGEST}" \
  -e ASTRAFORM_REQUIRE_OCI_PROVIDER_DIGEST=true \
  "ghcr.io/astraform/banking-starter-bank@${DIGEST}"
```

These are settings on the bank container, separate from the platform's URL
binding. The public starter's `image-lock.json` records its distributed image
identities; use the actual published digest for your selected build. Do not
treat a mutable image tag as proof of a provider revision.

## Tests

```bash
cd python-fastapi-banking
docker compose -f compose.database.yaml up -d --wait
uv sync --locked --extra test
BANKING_TEST_DATABASE_URL=postgresql://banking:banking@127.0.0.1:5545/banking_python \
  uv run --locked --extra test pytest
```

The PostgreSQL checks require `BANKING_TEST_DATABASE_URL`; without it they are
skipped. CI supplies its own PostgreSQL service and runs these checks.

### Protocol conformance and provider parity

Schema-backed conformance run:

The author kit and conformance SDK `0.3.2` packages are published on PyPI.
The source installation below uses those versions. Prebuilt images and starter
archives retain the SDK versions recorded in their image and release metadata;
a new image must be built from updated source to include SDK `0.3.2`. Record the
running provider's source revision or image digest separately from the
conformance tool version.

```bash
cd python-fastapi-banking
python3 -m venv .venv-conformance
. .venv-conformance/bin/activate
pip install -e '.[test]'
pip install 'astraform-remote-domain-conformance==0.3.2'
# Start the selected provider image by its actual digest on localhost:8092.
mkdir -p build
remote-domain-conformance \
  --base-url http://127.0.0.1:8092 \
  --domain-id banking-python \
  --opportunity-worker-profile conformance/opportunity-worker-profile.json \
  --provider-artifact-digest "sha256:<exact OCI manifest digest>" \
  --population-catalog \
  > build/conformance-run-report.json
```

This is a local conformance diagnostic. Sample CI neither retains nor signs a
DSSE report, and the published image is baseline and `NO_TOKEN_ONLY` capable,
not `REMOTE_AUTHORIZED`. Remote-worker authorization is deferred pending a
simpler end-to-end authorization design.

Policy Wind Tunnel conformance run:

```bash
cd python-fastapi-banking
. .venv-conformance/bin/activate
PYTHONPATH=conformance remote-domain-conformance \
  --app policy_wind_tunnel_app:app \
  --wind-tunnel \
  --pack-id banking-python-policy-pack \
  --preset-id hardship-python-smoke
```

The fixture delegates pack, bundle, and artifact production to the sample while
adapting only the SDK's legacy baseline launch; it does not reopen retired Fast
Batch launches on the production app.

Java/Python provider parity check, when both providers are running:

```bash
../scripts/check_banking_remote_provider_parity.py
```

This compares the running sample providers through their declared endpoints.
It is separate from a real customer simulation: protocol conformance and matching
banking calculations do not prove model behavior, achieved goals or large-population
capacity. Use the shared banking scenario to inspect actual customer/partner
conversations and tool receipts.

## License

The Astraform-authored code, configuration and documentation in this component are licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). This permits use, modification and redistribution under that license. Third-party dependencies retain their own terms. The repository-wide [license](../LICENSE) also covers the other Astraform-authored samples; Astraform's platform software remains excluded.
