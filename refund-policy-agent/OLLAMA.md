# Run the refund agent with local Ollama

This mode makes real local model calls. The flow is:

**Astraform → A2A refund agent → Ollama → model answer → Astraform grader.**

The model reads the scenario instruction, policy and case facts, then chooses
the decision, reason code and policy version. No code calculates eligibility
in this mode. There is no answer repair, retry, or deterministic fallback.
Provider failure is an error; an incorrect model answer remains incorrect in
the evaluation report. The existing deterministic mode remains a separate
reference baseline.

## Use an existing installation

Ollama must already be running with a chat model installed. This sample does
not install Ollama or download a model automatically. Inspect your installation:

```bash
ollama list
```

The examples and Compose default use `qwen3.5:4b`. If you choose this model
and it is not installed, download it explicitly:

```bash
ollama pull qwen3.5:4b
```

This leaves existing models installed. You can select another installed model
that supports the request options used here. We use the native
[chat API](https://docs.ollama.com/api/chat) and
[structured JSON output](https://docs.ollama.com/capabilities/structured-outputs).
The JSON schema constrains the three field types; it supplies no correct
decision. Thinking is enabled by default. The bounded local profile uses temperature
0, 65,536 context tokens, 32,768 maximum output tokens (including thinking), and
a 60-second total inference deadline with one attempt and no automatic retry.
These are ceilings, not reserved output or an expected response time. The larger context can use more memory than the old
8,192-token profile. Results may still vary between models, versions or runs.

The existing environment configuration accepts `OLLAMA_THINKING=true|false`,
`OLLAMA_CONTEXT_TOKENS`, `OLLAMA_OUTPUT_TOKENS`, and `OLLAMA_TIMEOUT_SECONDS`.
Thinking may be explicitly disabled by an operator for a different model;
there is no automatic fallback to disabled thinking. Token budgets must be
positive integers with output below context, and the deadline must be finite
and positive. Keep the caller's A2A deadline longer than the model deadline.
A thinking-only or truncated response still fails; the agent never returns
reasoning as a business answer.

## Run the agent directly

From this sample directory:

```bash
REFUND_AGENT_MODE=ollama \
OLLAMA_MODEL=qwen3.5:4b \
OLLAMA_BASE_URL=http://127.0.0.1:11434 \
uv run --locked uvicorn app:create_app --factory --host 127.0.0.1 --port 8095
```

Use the same A2A request shown in `README.md`. The Agent Card and `/health`
identify Ollama mode and the selected model. Health reports that the agent
process is running; a successful assessment also requires Ollama and the model.

## Run through the Astraform evaluation bundle

Build the sample image from this directory:

```bash
docker build -t astraform/refund-policy-agent:local .
```

In the extracted bundle's `private/runtime.properties`, keep A2A enabled and add
the new target. If `enabled` or `request-timeout` already exists, update its
single entry instead of duplicating it:

```properties
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.request-timeout=70s
simulation.event.runtime.a2a.targets.refund-ollama.base-url=http://refund-ollama:8095
simulation.event.runtime.a2a.targets.refund-ollama.binding-revision=refund-ollama-qwen3-5-4b-thinking-v3
simulation.event.runtime.a2a.targets.refund-ollama.allow-insecure-http=true
```

The 70-second A2A transport budget gives the sample time to report a
60-second model timeout. The checker waits up to 90 seconds per case. Neither
outer budget extends the model deadline or enables another model attempt.
Timing out the checker alone does not cancel work already accepted by the agent.
Provider cancellation closes the local request; immediate GPU quiescence depends
on the Ollama server.

Run these commands from the **bundle directory**, replacing the sample path:

```bash
./astraform.sh -f /absolute/path/to/refund-policy-agent/compose.ollama.yaml up -d --wait
./astraform.sh restart agent-runtime
./astraform.sh up -d --wait
python3 ./check-agent.py --target-key refund-ollama \
  --scenario scenarios/refund-policy-v1.json --timeout 90 --confirm-target-test
```

This adds the LLM service without requiring the deterministic service. To
manage both together, pass both sample overlay files before the Compose
command. Existing evaluation evidence is preserved.

The overlay reaches the host's existing Ollama at
`http://host.docker.internal:11434` on Docker Desktop. For another host setup,
set `OLLAMA_BASE_URL` in the bundle's `.env` to an address reachable from the
agent container. The container's `localhost` is not the host. `OLLAMA_MODEL`
and the thinking, token-budget and timeout variables above can also be set there. The wrapper reads `.env`
over inherited shell settings. Recreate the LLM service after changing these
environment values. Record a new target binding revision when model or prompt
behavior changes; the model name alone does not pin its installed weights.

The checker makes up to nine local model calls. It saves each raw business
answer and grade under `evidence/`; passing is not guaranteed. Use the separate
`refund-sample` target for the deterministic baseline. Neither run tests model
tool use or a continuing multi-event conversation.

## Rerun an already configured bundle

With the bundle, refund agent, and Ollama service running, execute this from
the **bundle directory**. The bundled refund scenario is selected by default:

```bash
python3 check-agent.py --target-key refund-ollama --timeout 90 --confirm-target-test
```

Each run writes new evidence. No image rebuild or runtime restart is needed
when configuration has not changed.

Downloaded model files remain on disk and are listed by `ollama list`.
Selecting a model in the agent configuration does not load it into memory; an assessment loads it
when needed. Use `ollama ps` to see models currently loaded in memory. After
the evaluation finishes, release this model's memory with:

```bash
ollama stop qwen3.5:4b
```

This keeps the model installed and the agent configured. The next assessment
loads it again. See Ollama's [memory management FAQ](https://docs.ollama.com/faq)
and [CLI reference](https://docs.ollama.com/cli).

## Check behavior

Inspect the original business answer and grade for each case. Completion and
correctness are separate: a completed response can still misapply the policy,
invent missing facts or choose the wrong decision. Thinking does not guarantee
accuracy. A timeout, thinking-only response or truncated answer remains a failure;
the sample does not repair the answer, disable thinking or retry generation.

Offline tests use mock HTTP responses and do not invoke Ollama:

```bash
uv run --locked python -m unittest discover -s tests -v
```

They verify that LLM mode bypasses the deterministic rules, preserves wrong or
malformed model answers, forwards scenario instructions/policies, strips case
IDs, and reports provider errors without a fallback answer.
