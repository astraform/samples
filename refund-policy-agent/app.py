"""A small, standalone A2A server; all protocol handling belongs to the SDK."""

import asyncio
import json
import os
from pathlib import Path
import sqlite3
from urllib.parse import urlsplit

from a2a.helpers import get_message_text, new_text_message
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentInterface, AgentSkill
from a2a.utils.errors import InternalError, InvalidParamsError, UnsupportedOperationError
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from refund_policy import assess
from ollama_backend import OllamaError, OllamaResponder
from conversation import ConversationStore, INPUT_SCHEMA, MAX_DELAY_MS


DEFAULT_INSTRUCTION = (
    "Assess the refund request using the supplied policy and facts. Return only a JSON object "
    "with decision, reasonCode and policyVersion. Do not execute any refund or external action."
)
DEFAULT_POLICY = json.loads(Path(__file__).with_name("default-policy.json").read_text())


def extract_request(text: str, *, business_schema: str | None = None) -> tuple[str, dict]:
    """Accept plain business JSON or unwrap the documented Astraform scenario input."""
    try:
        payload = json.loads(text)
    except (ValueError, RecursionError) as error:
        raise ValueError("Send one JSON object containing refund request facts") from error
    if not isinstance(payload, dict):
        raise ValueError("Refund request must be a JSON object")
    if business_schema is not None and payload.get("schemaVersion") == business_schema:
        return DEFAULT_INSTRUCTION, payload
    if "schemaVersion" in payload:
        if payload["schemaVersion"] != "astraform.isolated-event-a2a-invocation.v1":
            raise ValueError("Unsupported input envelope")
        request = payload.get("request")
        if (not isinstance(request, dict)
                or request.get("schemaVersion") != "astraform.agent-event-request.v1"
                or not isinstance(request.get("facts"), dict)):
            raise ValueError("Scenario envelope must contain request.facts")
        instruction = request.get("instruction", DEFAULT_INSTRUCTION)
        if not isinstance(instruction, str) or not instruction.strip():
            raise ValueError("Scenario instruction must be nonempty text")
        return instruction, request["facts"]
    return DEFAULT_INSTRUCTION, payload


class RefundAgent(AgentExecutor):
    def __init__(self, mode: str, responder: OllamaResponder | None = None,
                 conversation_store: ConversationStore | None = None, delay_ms: int = 0):
        self.mode = mode
        self.responder = responder
        self.conversation_store = conversation_store
        self.delay_ms = delay_ms

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        try:
            input_text = get_message_text(context.message)
            instruction, facts = extract_request(
                input_text, business_schema=INPUT_SCHEMA if self.mode == "conversation-reference" else None,
            )
            reply_id = None
            if self.mode == "deterministic":
                text = json.dumps(assess(facts))
            elif self.mode == "conversation-reference":
                reply = await asyncio.to_thread(
                    self.conversation_store.reply, context.context_id, context.message.message_id,
                    input_text, facts,
                )
                text, reply_id = reply.text, reply.message_id
                # Persist before delaying: a lost reply can later replay the recorded answer.
                if self.delay_ms:
                    await asyncio.sleep(self.delay_ms / 1000)
            else:
                # Supply policy data; only the model determines the business answer.
                model_facts = {"policy": DEFAULT_POLICY, **facts}
                text = await self.responder.respond(instruction, model_facts)
        except ValueError as error:
            raise InvalidParamsError(str(error)) from error
        except OllamaError as error:
            raise InternalError(str(error)) from error
        except sqlite3.Error as error:
            raise InternalError("Conversation state is unavailable") from error
        message = new_text_message(
            text, media_type="text/plain", context_id=context.context_id,
        )
        if reply_id is not None:
            message.message_id = reply_id
        await event_queue.enqueue_event(message)

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        raise UnsupportedOperationError("This sample returns immediate messages, not running tasks")


def create_app(base_url: str | None = None, *, mode: str | None = None,
               responder: OllamaResponder | None = None,
               conversation_store: ConversationStore | None = None,
               conversation_delay_ms: int | None = None) -> Starlette:
    base_url = (base_url or os.environ.get("REFUND_AGENT_URL", "http://localhost:8095")).rstrip("/")
    parsed = urlsplit(base_url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("REFUND_AGENT_URL must be the reachable HTTP(S) origin, without a path")
    mode = mode or os.environ.get("REFUND_AGENT_MODE", "deterministic")
    if mode not in {"deterministic", "ollama", "conversation-reference"}:
        raise ValueError("REFUND_AGENT_MODE must be deterministic, ollama or conversation-reference")
    if mode == "ollama" and responder is None:
        responder = OllamaResponder(
            os.environ.get("OLLAMA_BASE_URL", "http://127.0.0.1:11434"),
            os.environ.get("OLLAMA_MODEL", ""),
            float(os.environ.get("OLLAMA_TIMEOUT_SECONDS", "90")),
        )
    delay_ms = 0
    if mode == "conversation-reference":
        delay_ms = (conversation_delay_ms if conversation_delay_ms is not None else
                    int(os.environ.get("REFUND_CONVERSATION_DELAY_MS", "0")))
        if type(delay_ms) is not int or not 0 <= delay_ms <= MAX_DELAY_MS:
            raise ValueError(f"REFUND_CONVERSATION_DELAY_MS must be an integer between 0 and {MAX_DELAY_MS}")
        wrong_day30 = os.environ.get("REFUND_CONVERSATION_WRONG_DAY30", "false").lower()
        if wrong_day30 not in {"true", "false"}:
            raise ValueError("REFUND_CONVERSATION_WRONG_DAY30 must be true or false")
        if conversation_store is None:
            conversation_store = ConversationStore(
                os.environ.get("REFUND_CONVERSATION_DB", "data/refund-conversations.sqlite3"),
                wrong_day30=wrong_day30 == "true",
            )
    model = responder.model if mode == "ollama" else None
    description = (f"LLM refund assessment through local Ollama model {model}; no payment calls."
                   if mode == "ollama" else
                   "Deterministic, read-only refund-policy-v1 decisions. No model or payment calls.")
    if mode == "conversation-reference":
        description = ("Stateful, read-only refund-policy-v1 conversation reference. "
                       "Asks for purchase date and preserves the original request date. No model or payment calls.")
    skill = AgentSkill(
        id="refund-policy-v1", name="Assess refund eligibility",
        description="Send a JSON text object with purchaseDate, requestDate and itemCondition. "
                    "Returns decision, reasonCode and policyVersion. Dates are YYYY-MM-DD; "
                    "condition is opened or unopened. Missing values remain unknown.",
        tags=["refund", "policy", "sample"],
        examples=['{"purchaseDate":"2026-01-01","requestDate":"2026-01-31",'
                  '"itemCondition":"unopened"}'],
    )
    if mode == "conversation-reference":
        skill = AgentSkill(
            id="refund-conversation-v1", name="Complete a refund eligibility conversation",
            description=f"Send JSON text using {INPUT_SCHEMA}. Start with phase=request, customerMessage, "
                        "requestDate and itemCondition=unopened. Continue the same contextId with "
                        "phase=provide_information, customerMessage and purchaseDate. Returns a read-only "
                        "business decision and message; each turn is an immediate A2A Message.",
            tags=["refund", "conversation", "sample"],
        )
    card = AgentCard(
        name=f"Sample Refund Policy Agent ({mode})",
        description=description,
        version="0.3.0",
        supported_interfaces=[AgentInterface(
            url=base_url + "/a2a", protocol_binding="JSONRPC", protocol_version="1.0",
        )],
        capabilities=AgentCapabilities(streaming=False, push_notifications=False),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        skills=[skill],
    )
    handler = DefaultRequestHandler(
        RefundAgent(mode, responder, conversation_store, delay_ms), InMemoryTaskStore(), agent_card=card,
    )

    async def health(_request):
        # Process health only; the first assessment checks model availability.
        details = {"status": "UP", "agent": "refund-policy-v1", "mode": mode, "model": model}
        if mode == "conversation-reference":
            details["faults"] = {"delayMs": delay_ms, "wrongDay30": conversation_store.wrong_day30}
        return JSONResponse(details)

    return Starlette(routes=[
        Route("/health", health),
        *create_agent_card_routes(card),
        *create_jsonrpc_routes(handler, rpc_url="/a2a"),
    ])
