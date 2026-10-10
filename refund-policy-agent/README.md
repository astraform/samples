# Sample refund-policy A2A agent

A small external agent for exercising one complete Astraform evaluation. It uses
the [official Python A2A SDK](https://github.com/a2aproject/a2a-python/tree/v1.1.4)
at pinned version 1.1.4 and exposes A2A 1.0 JSON-RPC. Choose deterministic rules
(default), a local Ollama model to assess supplied facts, or the
[stateful conversation reference](EPISODE.md). The two assessment modes need no
model API key, authentication, database, or platform source. Neither executes
refunds; Ollama mode calls your configured local model service.

The default mode is a deterministic reference. [Run the LLM version with local
Ollama](OLLAMA.md) to evaluate model-generated decisions against the same cases.
Its purpose is to make discovery, messaging, scenario grading, and evidence
observable before connecting a partner's agent. All modes use immediate Message
responses. The conversation mode persists its business context and message
replies in SQLite, including across restarts. None implements asynchronous A2A
tasks or tool execution. The instructions below concern the original assessment
modes; [EPISODE.md](EPISODE.md) defines the separate conversation contract.

## Run the agent by itself

From this directory, with Docker running:

```bash
docker build -t astraform/refund-policy-agent:local .
docker run --rm --name refund-policy-agent \
  -p 127.0.0.1:8095:8095 \
  -e REFUND_AGENT_URL=http://localhost:8095 \
  astraform/refund-policy-agent:local
```

The Agent Card is at `http://localhost:8095/.well-known/agent-card.json`; the
health endpoint is `/health`. In another terminal, send a standard A2A request:

```bash
curl --fail-with-body http://localhost:8095/a2a \
  -H 'Content-Type: application/json' -H 'A2A-Version: 1.0' \
  --data-binary @- <<'JSON'
{
  "jsonrpc": "2.0",
  "id": "first-refund-check",
  "method": "SendMessage",
  "params": {
    "message": {
      "messageId": "refund-request-1",
      "contextId": "refund-example-1",
      "role": "ROLE_USER",
      "parts": [{"text": "{\"purchaseDate\":\"2026-01-01\",\"requestDate\":\"2026-01-31\",\"itemCondition\":\"unopened\"}", "mediaType": "text/plain"}]
    },
    "configuration": {"acceptedOutputModes": ["text/plain"], "returnImmediately": false}
  }
}
JSON
```

The response's `result.message.parts[0].text` contains:

```json
{"decision":"eligible","reasonCode":"WITHIN_WINDOW_UNOPENED","policyVersion":"refund-policy-v1"}
```

For local development without Docker, install `uv` and Python 3.12–3.14:

```bash
uv sync --locked
uv run --locked uvicorn app:create_app --factory --host 127.0.0.1 --port 8095
uv run --locked python -m unittest discover -s tests -v
```

## Run through Astraform

Use the separate Astraform partner-evaluation bundle. Build the sample image
with the command above, then initialize the extracted bundle if it is new:

```bash
./init.sh --port 8284
```

In that bundle's `private/runtime.properties`, add:

```properties
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.targets.refund-sample.base-url=http://refund-agent:8095
simulation.event.runtime.a2a.targets.refund-sample.binding-revision=refund-sample-v1
simulation.event.runtime.a2a.targets.refund-sample.allow-insecure-http=true
```

Run the following **from the bundle directory**, replacing the sample path with
its actual absolute path. The supplied Compose overlay starts the agent on the
same private container network as Astraform and advertises the matching URL.

```bash
./astraform.sh -f /absolute/path/to/refund-policy-agent/compose.astraform.yaml up -d --wait
python3 ./check-agent.py --target-key refund-sample \
  --scenario scenarios/refund-policy-v1.json --confirm-target-test
```

If Agent Runtime was already running when you edited its properties, run
`./astraform.sh restart agent-runtime`, then repeat the `up -d --wait` command
before checking. Changing a mounted file alone does not reload its settings.

The checker prints the evidence directory and grades nine cases: purchase day,
day 30, day 31, opened items, missing facts, and conflicting exclusions. A passing
run exits zero. Its evidence files include the returned decision and the runtime
receipt. These external-agent runs are not shown in the current Journey UI.

To stop this bundle and its sample without deleting their evidence or database:

```bash
./astraform.sh -f /absolute/path/to/refund-policy-agent/compose.astraform.yaml stop
```

For an agent on another private host, set `REFUND_AGENT_URL` to its origin as
reachable from Astraform and use the same origin in the target binding. The
Agent Card must advertise that origin, not its container's localhost address.

## What the sample understands

- A JSON text object with `purchaseDate`, `requestDate`, and `itemCondition`.
- Alternatively, the current Astraform scenario envelope; `extract_request` in
  `app.py` unwraps `request.facts`. This application-level input mapping is
  separate from the standard A2A protocol implementation owned by the SDK.
- Calendar dates in `YYYY-MM-DD`; item condition `opened` or `unopened`.
  Absent/null facts mean unknown. Deterministic mode rejects malformed business
  values; Ollama mode passes the supplied values to the model for assessment.
  Both modes reject invalid JSON or an unsupported input envelope.
- The fixed `refund-policy-v1`: day 30 included; day 31 excluded; an expired
  window takes precedence over opened packaging; known exclusions take
  precedence over missing facts. Remaining unknown facts yield `needs_information`
  as a completed business answer, not an A2A request for another conversation turn.

In deterministic mode, a supplied policy object's version and 30-day window
must match the fixed rules; descriptive policy text does not reprogram them.
In Ollama mode, the model receives the supplied instruction, policy and facts;
its answer is returned unchanged, including mistakes. It never falls back to
the fixed rules. For plain business JSON without a policy, `default-policy.json`
provides the sample policy as model input. The agent never reads the checker's
expected answers; Ollama input also excludes case IDs. Its source and image
have no dependency on the Astraform repository.
