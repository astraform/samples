"""One local Ollama inference per refund request, without answer repair or fallback.

API reference: https://docs.ollama.com/api/chat
Schema reference: https://docs.ollama.com/capabilities/structured-outputs
"""

import asyncio
import json
import math
from urllib.parse import urlsplit

import httpx


# This is the output contract, not an answer key. All three values come from the LLM.
RESPONSE_SCHEMA = {
    "type": "object",
    "properties": {
        "decision": {"type": "string"},
        "reasonCode": {"type": "string"},
        "policyVersion": {"type": "string"},
    },
    "required": ["decision", "reasonCode", "policyVersion"],
    "additionalProperties": False,
}

# Bound the sample's local inference. Exhausting the output budget is an error,
# never a reason to manufacture a business decision or send a second request.
CONTEXT_TOKENS = 8192
OUTPUT_TOKENS = 512

SYSTEM_INSTRUCTION = (
    "You are a read-only refund policy reviewer. Assess the supplied case using "
    "the supplied instruction and policy. Missing facts remain unknown; do not "
    "invent them. Do not perform refunds or any other external action. Return "
    "one JSON object, without Markdown, matching this output schema: "
    + json.dumps(RESPONSE_SCHEMA, separators=(",", ":"))
)


class OllamaError(RuntimeError):
    """The provider failed or did not return a complete, nonempty text response."""


class OllamaResponder:
    """Call native Ollama once; the scenario grader evaluates the raw model answer.

    Invalid connection configuration raises ValueError at construction. Provider
    failures raise OllamaError. The optional transport supports offline tests;
    each response call owns and closes its client (and supplied transport).
    """

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: float = 90.0,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        try:
            parsed = urlsplit(base_url)
            # Reading port also rejects malformed or out-of-range port numbers.
            parsed.port
            valid_origin = (
                isinstance(base_url, str)
                and not any(char.isspace() for char in base_url)
                and parsed.scheme in {"http", "https"}
                and bool(parsed.hostname)
                and parsed.username is None
                and parsed.password is None
                and parsed.path in {"", "/"}
                and "?" not in base_url
                and "#" not in base_url
            )
        except (ValueError, TypeError, AttributeError):
            valid_origin = False
        if not valid_origin:
            raise ValueError("Ollama base URL must be an HTTP(S) origin without credentials, path, or query")
        if not isinstance(model, str) or not model.strip():
            raise ValueError("Ollama model must be a nonempty model name")
        if (not isinstance(timeout_seconds, (int, float)) or isinstance(timeout_seconds, bool)
                or not math.isfinite(timeout_seconds) or timeout_seconds <= 0):
            raise ValueError("Ollama timeout must be a finite positive number of seconds")
        self.base_url = base_url.rstrip("/")
        self.model = model.strip()
        self.timeout_seconds = timeout_seconds
        self._transport = transport

    async def respond(self, instruction: str, facts: dict) -> str:
        """Return untouched model content; never grade, retry, or repair its answer."""
        if not isinstance(instruction, str) or not instruction.strip():
            raise ValueError("The model needs a nonempty scenario instruction")
        if not isinstance(facts, dict):
            raise ValueError("Scenario facts must be a JSON object")
        # Case IDs can reveal expected outcomes; they are evaluator correlation only.
        business_facts = {key: value for key, value in facts.items() if key != "caseId"}
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": SYSTEM_INSTRUCTION},
                {"role": "user", "content": json.dumps({
                    "instruction": instruction,
                    "facts": business_facts,
                }, allow_nan=False)},
            ],
            "stream": False,
            "think": False,
            "format": RESPONSE_SCHEMA,
            "options": {
                "temperature": 0,
                "num_ctx": CONTEXT_TOKENS,
                "num_predict": OUTPUT_TOKENS,
            },
        }
        try:
            async with asyncio.timeout(self.timeout_seconds):
                async with httpx.AsyncClient(
                    timeout=self.timeout_seconds,
                    transport=self._transport,
                    follow_redirects=False,
                    trust_env=False,
                ) as client:
                    response = await client.post(self.base_url + "/api/chat", json=payload)
                    response.raise_for_status()
        except (TimeoutError, httpx.TimeoutException) as error:
            raise OllamaError("Ollama request timed out; no fallback answer was generated") from error
        except httpx.HTTPStatusError as error:
            raise OllamaError(
                f"Ollama returned HTTP {error.response.status_code}; check the endpoint and installed model"
            ) from error
        except httpx.RequestError as error:
            raise OllamaError("Could not reach Ollama; check its URL and service status") from error

        try:
            body = response.json()
        except ValueError as error:
            raise OllamaError("Ollama returned an invalid JSON response") from error
        if not isinstance(body, dict):
            raise OllamaError("Ollama returned an invalid response object")
        if body.get("error"):
            raise OllamaError("Ollama reported a generation error; no fallback answer was generated")
        if body.get("done") is not True:
            raise OllamaError("Ollama did not complete the response")
        if body.get("done_reason") == "length":
            raise OllamaError("Ollama response was truncated at the output token limit")
        if body.get("done_reason") not in (None, "stop"):
            raise OllamaError("Ollama stopped without a completed answer")
        message = body.get("message")
        content = message.get("content") if isinstance(message, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise OllamaError("Ollama returned no answer text")
        return content
