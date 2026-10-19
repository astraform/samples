import json
import unittest

import httpx
from unittest.mock import AsyncMock, Mock, patch

from starlette.testclient import TestClient

from app import create_app
from ollama_backend import OllamaError, OllamaResponder


class A2AContractTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app("http://refund-agent:8095", mode="deterministic"))
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def send(self, content, output_mode="text/plain"):
        return self.client.post("/a2a", headers={"A2A-Version": "1.0"}, json={
            "jsonrpc": "2.0", "id": "request-1", "method": "SendMessage",
            "params": {
                "message": {
                    "role": "ROLE_USER", "messageId": "message-1", "contextId": "context-1",
                    "parts": [{"text": content, "mediaType": "text/plain"}],
                },
                "configuration": {"acceptedOutputModes": [output_mode], "returnImmediately": False},
            },
        })

    def test_card_advertises_sdk_protocol_and_business_skill(self):
        response = self.client.get("/.well-known/agent-card.json")
        self.assertEqual(200, response.status_code)
        card = response.json()
        self.assertEqual([{
            "url": "http://refund-agent:8095/a2a",
            "protocolBinding": "JSONRPC", "protocolVersion": "1.0",
        }], card["supportedInterfaces"])
        self.assertEqual("refund-policy-v1", card["skills"][0]["id"])
        self.assertEqual(["text/plain"], card["defaultOutputModes"])
        self.assertFalse(card.get("securityRequirements"))

    def test_plain_business_input_echoes_context_in_standard_message(self):
        response = self.send(json.dumps({
            "purchaseDate": "2026-01-01", "requestDate": "2026-01-31", "itemCondition": "unopened",
        }))
        self.assertEqual(200, response.status_code)
        rpc = response.json()
        self.assertEqual("request-1", rpc["id"])
        message = rpc["result"]["message"]
        self.assertEqual("context-1", message["contextId"])
        self.assertEqual("ROLE_AGENT", message["role"])
        self.assertTrue(message["messageId"])
        self.assertFalse(message.get("taskId"))
        self.assertEqual({
            "decision": "eligible", "reasonCode": "WITHIN_WINDOW_UNOPENED", "policyVersion": "refund-policy-v1",
        }, json.loads(message["parts"][0]["text"]))

    def test_astraform_envelope_and_current_runtime_output_mode(self):
        response = self.send(json.dumps({
            "schemaVersion": "astraform.isolated-event-a2a-invocation.v1",
            "eventId": "correlation-only", "logicalTimeEpochMs": 9999999999999,
            "request": {"schemaVersion": "astraform.agent-event-request.v1", "facts": {
                "purchaseDate": "2026-01-01", "requestDate": "2026-02-01", "itemCondition": "unopened",
            }},
        }), output_mode="text")
        message = response.json()["result"]["message"]
        self.assertEqual("OUTSIDE_RETURN_WINDOW", json.loads(message["parts"][0]["text"])["reasonCode"])

    def test_invalid_business_input_is_a_protocol_error_not_eligibility(self):
        for content in ("not JSON", "[]", '{"purchaseDate":"yesterday"}',
                        '{"schemaVersion":"unsupported"}'):
            with self.subTest(content=content):
                rpc = self.send(content).json()
                self.assertNotIn("result", rpc)
                self.assertEqual(-32602, rpc["error"]["code"])

    def test_unknown_operation_is_handled_by_the_sdk(self):
        rpc = self.client.post("/a2a", headers={"A2A-Version": "1.0"}, json={
            "jsonrpc": "2.0", "id": "unknown", "method": "InventedMethod", "params": {},
        }).json()
        self.assertEqual(-32601, rpc["error"]["code"])


class LLMA2AContractTest(unittest.TestCase):
    send = A2AContractTest.send

    def connect(self, responder):
        self.client = TestClient(create_app("http://refund-ollama:8095", mode="ollama", responder=responder))
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def test_model_output_is_not_replaced_by_rules_or_reformatted(self):
        raw = "This intentionally invalid answer must reach the grader unchanged."
        responder = Mock(model="test-model", respond=AsyncMock(return_value=raw))
        self.connect(responder)
        with patch("app.assess", side_effect=AssertionError("LLM mode must not call the rules")):
            message = self.send('{"itemCondition":"opened"}').json()["result"]["message"]
        self.assertEqual(raw, message["parts"][0]["text"])
        instruction, facts = responder.respond.call_args.args
        self.assertIn("refund", instruction)
        self.assertEqual("refund-policy-v1", facts["policy"]["policyVersion"])
        self.assertEqual("opened", facts["itemCondition"])

    def test_scenario_instruction_and_policy_are_given_to_model(self):
        responder = Mock(model="test-model", respond=AsyncMock(return_value="{}"))
        self.connect(responder)
        policy = {"policyVersion": "trial-other-policy", "maximumCalendarDays": 14}
        self.send(json.dumps({
            "schemaVersion": "astraform.isolated-event-a2a-invocation.v1",
            "request": {"schemaVersion": "astraform.agent-event-request.v1",
                        "instruction": "Use this supplied policy.", "facts": {"policy": policy}},
        }))
        instruction, facts = responder.respond.call_args.args
        self.assertEqual("Use this supplied policy.", instruction)
        self.assertEqual(policy, facts["policy"])

    def test_provider_failure_is_reported_without_rule_fallback(self):
        responder = Mock(model="test-model", respond=AsyncMock(side_effect=OllamaError("Ollama unavailable")))
        self.connect(responder)
        with patch("app.assess", side_effect=AssertionError("No fallback permitted")):
            rpc = self.send('{"itemCondition":"opened"}').json()
        self.assertNotIn("result", rpc)
        self.assertEqual(-32603, rpc["error"]["code"])

    def test_health_and_card_identify_the_llm_mode(self):
        responder = Mock(model="test-model", respond=AsyncMock())
        self.connect(responder)
        health = self.client.get("/health").json()
        self.assertEqual("ollama", health["mode"])
        self.assertEqual(responder.model, health["model"])
        card = self.client.get("/.well-known/agent-card.json").json()
        self.assertIn(responder.model, card["description"])

    def test_ollama_environment_reaches_provider_without_changing_raw_answer(self):
        raw = ' {"decision":"wrong","reasonCode":"unchanged","policyVersion":"other"}\n'
        context_tokens, output_tokens, timeout = 2048, 256, 2
        for configured_thinking, expected_thinking in ((None, True), ("false", False)):
            with self.subTest(thinking=configured_thinking):
                def handle(request):
                    payload = json.loads(request.content)
                    self.assertEqual(expected_thinking, payload["think"])
                    self.assertEqual(context_tokens, payload["options"]["num_ctx"])
                    self.assertEqual(output_tokens, payload["options"]["num_predict"])
                    self.assertTrue(all(value == timeout for value in request.extensions["timeout"].values()))
                    return httpx.Response(200, json={"done": True, "done_reason": "stop",
                        "message": {"thinking": "Provider reasoning is not the business answer.", "content": raw}})

                def configured_responder(*args, **kwargs):
                    return OllamaResponder(*args, **kwargs, transport=httpx.MockTransport(handle))

                environment = {"OLLAMA_MODEL": "test-model", "OLLAMA_CONTEXT_TOKENS": str(context_tokens),
                    "OLLAMA_OUTPUT_TOKENS": str(output_tokens), "OLLAMA_TIMEOUT_SECONDS": str(timeout)}
                if configured_thinking is not None:
                    environment["OLLAMA_THINKING"] = configured_thinking
                with patch.dict("os.environ", environment, clear=True), patch("app.OllamaResponder", side_effect=configured_responder):
                    with TestClient(create_app("http://refund-ollama:8095", mode="ollama")) as client:
                        self.client = client
                        message = self.send('{"itemCondition":"opened"}').json()["result"]["message"]
                self.assertEqual(raw, message["parts"][0]["text"])

    def test_invalid_mode_and_missing_model_fail_at_startup(self):
        with self.assertRaises(ValueError):
            create_app(mode="unknown")
        with patch.dict("os.environ", {"OLLAMA_MODEL": ""}):
            with self.assertRaises(ValueError):
                create_app(mode="ollama")
        for configuration in ({"OLLAMA_THINKING": "maybe"}, {"OLLAMA_CONTEXT_TOKENS": "0"},
                              {"OLLAMA_OUTPUT_TOKENS": "unlimited"}):
            with self.subTest(configuration=configuration), patch.dict("os.environ", {"OLLAMA_MODEL": "test-model", **configuration}, clear=True):
                with self.assertRaises(ValueError):
                    create_app(mode="ollama")


if __name__ == "__main__":
    unittest.main()
