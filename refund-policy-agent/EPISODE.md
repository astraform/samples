# Stateful refund conversation reference

This mode is the **partner service under test**. Astraform supplies a simulated
customer through its own MODEL execution; this service handles the customer's
A2A messages. It is deterministic and read-only, uses no LLM, and never calls
payment systems or tools. Existing `deterministic` and `ollama` assessment modes
are unchanged.

The episode starts when the customer requests a return on January 31 without
giving the purchase date. The service asks for that date. The customer replies
later that the item was purchased January 1. Eligibility uses the **original
January 31 request date**, so day 30 is included even if the reply arrives on
February 1. A separate request first submitted February 1 is outside the window.

## Start the service

Build the sample image from this directory for local engineering use:

```bash
docker build -t astraform/refund-policy-agent:local .
```

The supplied `compose.episode.yaml` adds `refund-conversation` to the existing
partner bundle's private Compose network and mounts a persistent named volume
at `/data`. Set `REFUND_CONVERSATION_IMAGE=astraform/refund-policy-agent:local`
in the bundle's `.env` for that local build; a distributed kit should supply a
reviewed, digest-pinned image. Then, from the initialized bundle directory:

```bash
./astraform.sh -f /absolute/path/to/refund-policy-agent/compose.episode.yaml up -d --wait
```

Configure Astraform's target in `private/runtime.properties`:

```properties
simulation.event.runtime.a2a.enabled=true
simulation.event.runtime.a2a.targets.refund-conversation.base-url=http://refund-conversation:8095
simulation.event.runtime.a2a.targets.refund-conversation.binding-revision=refund-conversation-v1
simulation.event.runtime.a2a.targets.refund-conversation.allow-insecure-http=true
```

Restart Agent Runtime after changing its mounted properties. The service
advertises `http://refund-conversation:8095/a2a` in its Agent Card and publishes
no host port. `/health` identifies the mode and enabled fault settings.

The standalone configuration is:

| Setting | Default | Meaning |
| --- | --- | --- |
| `REFUND_AGENT_MODE` | `deterministic` | Set `conversation-reference` for this contract |
| `REFUND_AGENT_URL` | `http://localhost:8095` | Origin reachable from Astraform; appears in the Agent Card |
| `REFUND_CONVERSATION_DB` | `data/refund-conversations.sqlite3` | Persistent SQLite file; the overlay uses `/data/refund-conversations.sqlite3` |
| `REFUND_CONVERSATION_DELAY_MS` | `0` | Integer response delay between 0 and 60000 milliseconds |
| `REFUND_CONVERSATION_WRONG_DAY30` | `false` | Explicit `true` injects an incorrect day-30 rejection |

## A2A and business contract

Use A2A 1.0 JSON-RPC `SendMessage` with a user Message, stable `contextId` for
the episode, and a new `messageId` for each new turn. No task ID is needed. Each
turn returns an immediate agent Message with the same context ID. Business
`needs_information` is an answer in that Message, not an A2A `INPUT_REQUIRED`
Task. There is no pending task to poll or cancel.

Send one text part containing these business facts directly, or place them in
`request.facts` of the existing Astraform
`astraform.isolated-event-a2a-invocation.v1` envelope. The nested request retains
`schemaVersion: astraform.agent-event-request.v1`.

Initial facts:

```json
{
  "schemaVersion": "astraform.refund-conversation-input.v1",
  "phase": "request",
  "customerMessage": "I would like to return this unopened item.",
  "requestDate": "2026-01-31",
  "itemCondition": "unopened"
}
```

The initial phase requires `requestDate` and `itemCondition: unopened`, and
rejects a supplied `purchaseDate`, including null. The exact response text is:

```json
{"schemaVersion":"astraform.refund-conversation-output.v1","decision":"needs_information","reasonCode":"MISSING_REQUIRED_INFORMATION","policyVersion":"refund-policy-v1","message":"Please provide your purchase date."}
```

Follow-up facts in the same context, with a new message ID:

```json
{
  "schemaVersion": "astraform.refund-conversation-input.v1",
  "phase": "provide_information",
  "customerMessage": "I purchased it on January 1, 2026.",
  "purchaseDate": "2026-01-01"
}
```

The response has the same five fields, with `decision: eligible`,
`reasonCode: WITHIN_WINDOW_UNOPENED`, and message
`Your return request is eligible under the original request date.`
If the original request is day 31, the result is `ineligible` /
`OUTSIDE_RETURN_WINDOW`, with message `Your return request is outside the return window.`

`customerMessage` is required nonempty text, but the fixture computes its
decision from the supplied structured dates/condition; it does not interpret
or cross-check the prose with a model. The simulator must validate disclosed
facts before sending them. Dates must be real calendar dates in `YYYY-MM-DD`,
and purchase date cannot follow the original request date. Follow-up
`requestDate` or `itemCondition`, if repeated, must exactly match stored values.
Changing an already supplied purchase date is rejected. Unsupported fields,
a new initial request in an existing context, and a follow-up without an
initial request return A2A invalid-parameter errors.

## Persistence and replay

SQLite stores the original request, disclosed purchase date, input text digest,
reply message ID and reply body. It commits state and reply atomically before
returning or applying the controlled delay. The database uses local WAL
transactions and full synchronous writes. It is a single-service fixture, not
a shared multi-host database.

Reusing `(contextId, messageId)` with the exact same text returns the recorded
reply, including the same response message ID, even after a process restart
or after later turns. Reusing that pair with different text is rejected. This
comparison includes the entire text envelope, so changing whitespace or a
correlation field counts as changed content. New turns need new message IDs;
independent episodes need new context IDs. Contexts do not share business state.

Container restart and recreation preserve state when the same named volume is
mounted. Keep that volume and Compose project name. No reset/delete API is
provided; begin a fresh episode with a fresh context ID. This local fixture's
idempotency does not establish exactly-once behavior for arbitrary partner
agents or for Astraform's complete transport path.

## Controlled failures

The defaults are correct and immediate. Enable faults only in a named test
deployment and record them with its binding revision:

- `REFUND_CONVERSATION_WRONG_DAY30=true` makes day 30 incorrectly return
  `ineligible` / `OUTSIDE_RETURN_WINDOW`. Day 29 remains eligible. Previously
  recorded messages replay their original replies after settings change;
  start a new episode to compare the changed deployment.
- `REFUND_CONVERSATION_DELAY_MS=5000` delays every response by five seconds
  **after** the answer is durable. A client deadline may expire first; that
  does not undo the stored message. Replays receive the same answer and are
  subject to the current configured response delay.

The fault settings are deployment options, not fields that a customer can
request. The service performs no real-world actions in either fault mode.

## Verification

```bash
uv sync --locked
uv run --locked python -m unittest discover -s tests -v
```

Offline tests cover the existing assessment modes, context isolation,
day-0/30/31 decisions, conflicting/malformed inputs, concurrent duplicate
requests, exact reply replay, store/application recreation, and durable state
when delayed delivery is interrupted. A2A tests run through the official SDK.
Container-volume restart and the full native-customer Astraform episode must
also be verified by the kit's integration run; unit tests alone do not prove
those deployment properties.
