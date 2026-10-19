"""Offline provider-boundary tests; no Ollama server or real model is contacted."""

import asyncio
import json
import unittest

import httpx

from ollama_backend import OllamaError, OllamaResponder, RESPONSE_SCHEMA


class ClosingTransport(httpx.MockTransport):
    closed = False

    async def aclose(self):
        self.closed = True
        await super().aclose()


class OllamaConfigurationTest(unittest.TestCase):
    def test_rejects_invalid_origins(self):
        for base_url in (
            "", "localhost:11434", "ftp://localhost", "http://user:secret@localhost",
            "http://@localhost", "http://localhost/api", "http://localhost?key=x",
            "http://localhost?", "http://localhost#fragment", "http://localhost:99999",
            "http://local host", "http://localhost\n", "http://[bad-ipv6", None,
        ):
            with self.subTest(base_url=base_url), self.assertRaises(ValueError):
                OllamaResponder(base_url, "qwen3:8b")

    def test_accepts_local_and_private_origins(self):
        for base_url in ("http://127.0.0.1:11434", "http://host.docker.internal:11434/",
                         "https://ollama.internal", "http://[::1]:11434"):
            with self.subTest(base_url=base_url):
                self.assertEqual(base_url.rstrip("/"), OllamaResponder(base_url, "qwen3:8b").base_url)

    def test_rejects_missing_model_and_invalid_deadlines(self):
        for model in ("", " ", None):
            with self.subTest(model=model), self.assertRaises(ValueError):
                OllamaResponder("http://localhost:11434", model)
        for timeout in (0, -1, float("inf"), float("nan"), "90", True):
            with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                OllamaResponder("http://localhost:11434", "qwen3:8b", timeout)


    def test_rejects_invalid_thinking_and_unbounded_token_budgets(self):
        for configuration in ({"thinking": "true"}, {"thinking": 1},
                              {"context_tokens": 0}, {"output_tokens": -1},
                              {"output_tokens": True}, {"context_tokens": 1024, "output_tokens": 1024}):
            with self.subTest(configuration=configuration), self.assertRaises(ValueError):
                OllamaResponder("http://localhost:11434", "qwen3:8b", **configuration)


class OllamaResponseTest(unittest.IsolatedAsyncioTestCase):
    def responder(self, handler, **configuration):
        transport = ClosingTransport(handler)
        self.addCleanup(lambda: self.assertTrue(transport.closed))
        return OllamaResponder("http://ollama.internal:11434", "qwen3:8b", transport=transport, **configuration)

    async def test_sends_supplied_policy_without_case_id_and_preserves_raw_answer(self):
        # This intentionally wrong decision must survive untouched for the grader.
        answer = ' {"decision":"wrong", "reasonCode":"invented", "policyVersion":"other"}\n'
        facts = {"caseId": "eligible-answer-hint", "purchaseDate": "2026-01-01",
                 "policy": {"maximumCalendarDays": 7}}
        def handle(request):
            self.assertEqual("POST", request.method)
            self.assertEqual("http://ollama.internal:11434/api/chat", str(request.url))
            payload = json.loads(request.content)
            self.assertEqual("qwen3:8b", payload["model"])
            self.assertFalse(payload["stream"])
            self.assertTrue(payload["think"])
            self.assertEqual(RESPONSE_SCHEMA, payload["format"])
            self.assertEqual(0, payload["options"]["temperature"])
            self.assertGreater(payload["options"]["num_predict"], 0)
            self.assertGreater(payload["options"]["num_ctx"], payload["options"]["num_predict"])
            supplied = json.loads(payload["messages"][1]["content"])
            self.assertEqual("Apply this supplied policy.", supplied["instruction"])
            self.assertEqual({key: value for key, value in facts.items() if key != "caseId"}, supplied["facts"])
            self.assertNotIn("eligible-answer-hint", request.content.decode())
            return httpx.Response(200, json={"done": True, "done_reason": "stop",
                                            "message": {"content": answer}})

        result = await self.responder(handle).respond("Apply this supplied policy.", facts)
        self.assertEqual(answer, result)
        self.assertEqual("eligible-answer-hint", facts["caseId"])

    async def test_does_not_repair_invalid_business_json(self):
        responder = self.responder(lambda _: httpx.Response(200, json={
            "done": True, "done_reason": "stop", "message": {"content": "Not JSON"},
        }))
        self.assertEqual("Not JSON", await responder.respond("Assess the supplied policy.", {}))

    async def test_provider_http_failure_is_not_retried_or_converted_to_a_decision(self):
        calls = []

        def handle(request):
            calls.append(request)
            return httpx.Response(404, json={"error": "model not found"})

        with self.assertRaisesRegex(OllamaError, "HTTP 404"):
            await self.responder(handle).respond("Assess the policy.", {})
        self.assertEqual(1, len(calls))

    async def test_redirect_is_not_followed(self):
        calls = []

        def handle(request):
            calls.append(request)
            return httpx.Response(307, headers={"location": "https://elsewhere.example/api/chat"})

        with self.assertRaisesRegex(OllamaError, "HTTP 307"):
            await self.responder(handle).respond("Assess the policy.", {})
        self.assertEqual(1, len(calls))

    async def test_connection_failure_and_timeout_are_explicit(self):
        for error_type, text in ((httpx.ConnectError, "Could not reach"),
                                 (httpx.ReadTimeout, "timed out")):
            with self.subTest(error_type=error_type):
                def handle(request):
                    raise error_type("provider unavailable", request=request)
                with self.assertRaisesRegex(OllamaError, text):
                    await self.responder(handle).respond("Assess the policy.", {})

    async def test_total_deadline_cancels_slow_provider_and_closes_transport(self):
        calls = []

        async def handle(request):
            calls.append(request)
            await asyncio.sleep(1)
            self.fail("The total deadline should cancel the provider request")

        transport = ClosingTransport(handle)
        responder = OllamaResponder("http://localhost:11434", "qwen3:8b", 0.01, transport=transport)
        with self.assertRaisesRegex(OllamaError, "timed out"):
            await responder.respond("Assess the policy.", {})
        self.assertTrue(transport.closed)
        self.assertEqual(1, len(calls))

    async def test_rejects_missing_empty_and_unfinished_responses(self):
        responses = [
            ({"done": False, "message": {"content": "partial"}}, "did not complete"),
            ({"done": True, "done_reason": "length", "message": {"content": "partial"}}, "truncated"),
            ({"done": True, "done_reason": "load", "message": {"content": "partial"}}, "without a completed"),
            ({"done": True, "done_reason": {}, "message": {"content": "partial"}}, "without a completed"),
            ({"done": True, "message": {"content": " "}}, "no answer text"),
            ({"done": True, "message": {"content": None}}, "no answer text"),
            ({"done": True, "done_reason": "stop", "message": {"thinking": "Reasoning only, no final answer."}}, "no answer text"),
            ({"done": True}, "no answer text"),
            ({"error": "generation failed"}, "generation error"),
            ([], "invalid response object"),
        ]
        for payload, text in responses:
            with self.subTest(payload=payload), self.assertRaisesRegex(OllamaError, text):
                await self.responder(lambda _: httpx.Response(200, json=payload)).respond("Assess.", {})

    async def test_invalid_provider_json_fails_clearly(self):
        with self.assertRaisesRegex(OllamaError, "invalid JSON"):
            await self.responder(lambda _: httpx.Response(200, text="<html>error</html>")).respond("Assess.", {})

    async def test_invalid_input_fails_before_contacting_provider(self):
        def unexpected_call(_request):
            self.fail("Invalid input must not contact Ollama")

        responder = OllamaResponder("http://localhost:11434", "qwen3:8b",
                                    transport=httpx.MockTransport(unexpected_call))
        for instruction, facts in (("", {}), (None, {}), ("Assess.", [])):
            with self.subTest(instruction=instruction, facts=facts), self.assertRaises(ValueError):
                await responder.respond(instruction, facts)


if __name__ == "__main__":
    unittest.main()
