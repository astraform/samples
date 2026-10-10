import json
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from starlette.testclient import TestClient

from app import INPUT_SCHEMA, create_app


def send_message(client, facts, *, wrapped=False):
    if wrapped:
        facts = {"schemaVersion": "astraform.isolated-event-a2a-invocation.v1",
                 "request": {"schemaVersion": "astraform.agent-event-request.v1", "facts": facts}}
    return client.post("/a2a", headers={"A2A-Version": "1.0"}, json={
        "jsonrpc": "2.0", "id": str(uuid4()), "method": "SendMessage",
        "params": {
            "message": {"role": "ROLE_USER", "messageId": str(uuid4()), "contextId": "statement-advice",
                        "parts": [{"text": json.dumps(facts), "mediaType": "text/plain"}]},
            "configuration": {"acceptedOutputModes": ["text"], "returnImmediately": False},
        },
    }).json()


MODEL_TEXT = "  Bank model reply: these are the records available for your question.\nReview the details — no payment was made.  "


def completion(content=MODEL_TEXT, *, finish_reason="stop", **message_fields):
    return {"choices": [{"message": {"role": "assistant", "content": content, **message_fields},
                         "finish_reason": finish_reason}]}


def start_model_fixture(owner):
    """Exercise the real HTTP model boundary without a provider or model call."""
    owner.model_requests = []
    owner.model_response = completion()
    owner.model_status = 200
    owner.model_delay = 0

    class ModelHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            owner.model_requests.append({"path": self.path, "headers": dict(self.headers),
                                         "body": json.loads(raw)})
            time.sleep(owner.model_delay)
            body = owner.model_response
            body = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(owner.model_status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Expected when the client enforces its request timeout.

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), ModelHandler)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    thread.start()
    owner.addCleanup(server.server_close)
    owner.addCleanup(server.shutdown)
    owner.model_env = {
        "BANKING_LLM_BASE_URL": f"http://127.0.0.1:{server.server_port}/v1",
        "BANKING_LLM_MODEL": "fixture-bank-model",
        "BANKING_LLM_BINDING_REVISION": "fixture-v1",
        "BANKING_LLM_AUTH_TYPE": "none",
        "BANKING_LLM_API_KEY": "",
        "BANKING_LLM_TIMEOUT_SECONDS": "1",
        "BANKING_LLM_MAX_OUTPUT_TOKENS": "128",
        "BANKING_LLM_MAX_CONCURRENCY": "2",
        "BANKING_LLM_REASONING_EFFORT": "",
        "BANKING_LLM_DIAGNOSTICS": "false",
    }
    environment = patch.dict(os.environ, owner.model_env)
    environment.start()
    owner.addCleanup(environment.stop)


class BankingAdviceContractTest(unittest.TestCase):
    def setUp(self):
        start_model_fixture(self)
        self.client = TestClient(create_app("http://banking-advice:8096", backend_url=""))
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def send(self, facts, *, wrapped=False):
        return send_message(self.client, facts, wrapped=wrapped)

    def test_advertised_input_matches_backend_or_legacy_mode(self):
        schemas = Path(__file__).resolve().parents[1]
        for backend_url, filename in (("", "input.schema.json"),
                                      ("http://bank:8098", "bank-backend.input.schema.json")):
            with self.subTest(backend_url=backend_url):
                with TestClient(create_app("http://banking-advice:8096", backend_url=backend_url)) as client:
                    card = client.get("/.well-known/agent-card.json").json()
                    skill = card["skills"][0]
                    health = client.get("/health").json()
                schema = json.loads((schemas / filename).read_text())
                self.assertEqual("0.4.0", card["version"])
                self.assertEqual("llm", health["responseMode"])
                self.assertEqual("fixture-bank-model", health["model"])
                self.assertEqual("fixture-v1", health["modelBindingRevision"])
                self.assertEqual("request-only", health["conversationMemory"])
                self.assertEqual("bank-backend" if backend_url else "legacy-supplied-statement", health["mode"])
                self.assertIn(filename, skill["description"])
                self.assertEqual(not bool(backend_url), "minimumAmountDue" in skill["description"])
                if backend_url:
                    self.assertEqual({"schemaVersion", "customerMessage", "statementPeriod", "astraformCustomerContexts"},
                                     set(schema["properties"]))
                    self.assertEqual({"schemaVersion", "customerMessage"}, set(schema["required"]))
                    self.assertFalse(schema["additionalProperties"])
                    self.assertIn("Without it, advice uses the latest issued statement", skill["description"])
                else:
                    self.assertIn("statement", schema["properties"])
                    self.assertNotIn("statementPeriod", schema["properties"])

    def test_bank_metadata_stays_authoritative_and_model_prose_is_preserved(self):
        for minimum, expected in ((48.0, "payment_due"), ("0", "no_payment_due")):
            with self.subTest(minimum=minimum):
                statement = {"minimumAmountDue": minimum, "currency": "AUD",
                             "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}
                rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain this statement.",
                                 "statement": statement}, wrapped=True)
                message = rpc["result"]["message"]
                answer = json.loads(message["parts"][0]["text"])
                self.assertEqual(expected, answer["decision"])
                self.assertEqual(statement, answer["statement"])
                self.assertTrue(answer["readOnly"])
                self.assertEqual(MODEL_TEXT, answer["message"])
        self.assertEqual(2, len(self.model_requests))

    def test_missing_statement_requests_information_without_inventing_facts(self):
        rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Do I have a payment due?"})
        answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
        self.assertEqual("need_information", answer["decision"])
        self.assertEqual({}, answer["statement"])
        self.assertEqual({"minimumAmountDue", "currency", "statementEndDate", "dueDate"},
                         set(answer["missingFields"]))
        self.assertEqual(MODEL_TEXT, answer["message"])

    def test_malformed_statement_is_protocol_error_instead_of_advice(self):
        for statement in ({"minimumAmountDue": -1}, {"minimumAmountDue": True},
                          {"dueDate": "2026-02-30"}, {"paymentExecuted": True},
                          {"accountId": 9007199254740992}, {"minimumAmountDue": 1e30}):
            with self.subTest(statement=statement):
                rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain.",
                                 "statement": statement})
                self.assertEqual(-32602, rpc["error"]["code"])
        self.assertEqual([], self.model_requests)

    def test_account_identifiers_preserve_safe_numbers_and_large_strings(self):
        for identifier in (9007199254740991, "9223372036854775807"):
            with self.subTest(identifier=identifier):
                rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain my statement.",
                                 "statement": {"accountId": identifier}})
                answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
                self.assertEqual(identifier, answer["statement"]["accountId"])

    def test_legacy_mode_rejects_period_selector(self):
        rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain February.",
                         "statementPeriod": "2026-02"})
        self.assertEqual(-32602, rpc["error"]["code"])
        self.assertEqual([], self.model_requests)

    def test_required_model_configuration_fails_at_startup_without_fallback(self):
        bad_settings = [
            {"BANKING_LLM_BASE_URL": ""},
            {"BANKING_LLM_BASE_URL": "ftp://fixture.invalid/v1"},
            {"BANKING_LLM_BASE_URL": "https://user:password@fixture.invalid/v1"},
            {"BANKING_LLM_MODEL": " "},
            {"BANKING_LLM_BINDING_REVISION": ""},
            {"BANKING_LLM_AUTH_TYPE": "bearer", "BANKING_LLM_API_KEY": ""},
            {"BANKING_LLM_AUTH_TYPE": "unsupported"},
            {"BANKING_LLM_TIMEOUT_SECONDS": "0"},
            {"BANKING_LLM_MAX_OUTPUT_TOKENS": "0"},
            {"BANKING_LLM_MAX_CONCURRENCY": "0"},
        ]
        for settings in bad_settings:
            with self.subTest(settings=settings), patch.dict(os.environ, settings):
                with self.assertRaises(ValueError):
                    create_app("http://banking-advice:8096", backend_url="")
        self.assertEqual([], self.model_requests)

    def test_bearer_request_uses_plain_text_completion_and_keeps_key_out_of_public_metadata(self):
        token = "fixture-only-not-a-secret"
        with patch.dict(os.environ, {"BANKING_LLM_AUTH_TYPE": "bearer", "BANKING_LLM_API_KEY": token,
                                     "BANKING_LLM_REASONING_EFFORT": "low"}):
            with TestClient(create_app("http://banking-advice:8096", backend_url="")) as client:
                rpc = send_message(client, {"schemaVersion": INPUT_SCHEMA,
                                           "customerMessage": "What can you tell me from these records?"})
                public = [rpc, client.get("/health").json(), client.get("/.well-known/agent-card.json").json()]
        answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
        self.assertEqual(MODEL_TEXT, answer["message"])
        self.assertEqual(1, len(self.model_requests))
        request = self.model_requests[0]
        self.assertEqual("/v1/chat/completions", request["path"])
        self.assertEqual("Bearer " + token, request["headers"]["Authorization"])
        self.assertEqual("fixture-bank-model", request["body"]["model"])
        self.assertEqual("low", request["body"]["reasoning_effort"])
        self.assertNotIn("response_format", request["body"])
        self.assertNotIn("tools", request["body"])
        self.assertNotIn(token, json.dumps(public))
        self.assertNotIn(token, json.dumps(request["body"]))

    def test_invalid_model_completion_and_http_failure_never_return_canned_advice(self):
        cases = [
            ("invalid-json", b"not JSON", 200),
            ("missing-choices", {}, 200),
            ("empty-choices", {"choices": []}, 200),
            ("missing-content", {"choices": [{"message": {"role": "assistant"}, "finish_reason": "stop"}]}, 200),
            ("nontext-content", completion([{"type": "text", "text": "not supported"}]), 200),
            ("blank-content", completion(" \n\t"), 200),
            ("oversize-content", completion("x" * 4097), 200),
            ("truncated", completion(finish_reason="length"), 200),
            ("refusal", completion(refusal="Cannot comply"), 200),
            ("tool-call", completion(tool_calls=[{"id": "call-1", "function": {"name": "pay"}}]), 200),
            ("function-call", completion(function_call={"name": "pay", "arguments": "{}"}), 200),
            ("oversize-response", {**completion(), "padding": "x" * 131072}, 200),
            ("http-error", b"provider-private-detail", 503),
        ]
        for name, response, status in cases:
            with self.subTest(case=name):
                self.model_response, self.model_status = response, status
                before = len(self.model_requests)
                rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain this statement."})
                self.assertEqual(-32603, rpc["error"]["code"])
                self.assertNotIn("result", rpc)
                self.assertNotIn("provider-private-detail", json.dumps(rpc))
                self.assertEqual(before + 1, len(self.model_requests))

    def test_model_timeout_is_one_failed_attempt_not_a_fallback_reply(self):
        self.model_delay = 1.25
        rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain this statement."})
        self.assertEqual(-32603, rpc["error"]["code"])
        self.assertNotIn("result", rpc)
        self.assertEqual(1, len(self.model_requests))


class BankingBackendAdviceTest(unittest.TestCase):
    def setUp(self):
        start_model_fixture(self)
        self.requests = []
        requests = self.requests
        self.extra_statements = []
        self.response_transform = None
        owner = self

        class BankHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                subject = self.path.split("/")[-2]
                if subject not in {"customer-a", "customer-b"}:
                    self.send_error(404)
                    return
                minimum = "48" if subject == "customer-a" else "72"
                view = {"contextRef": "bank-trial", "subjectRef": subject, "version": 1,
                        "logicalTimeEpochMs": 1769904000000,
                        "accounts": [{"accountId": subject + "-card"}],
                        "statements": [{"billId": subject + "-bill", "accountId": subject + "-card",
                                        "minimumAmountDue": minimum, "currency": "AUD",
                                        "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}]}
                view["statements"].extend(owner.extra_statements)
                query = parse_qs(urlsplit(self.path).query)
                if "statementPeriod" in query:
                    period = query["statementPeriod"][0]
                    view["statementPeriod"] = period
                    view["statements"] = [s for s in view["statements"] if s["statementEndDate"][:7] == period]
                if owner.response_transform:
                    owner.response_transform(view)
                raw = json.dumps(view).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), BankHandler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.client = TestClient(create_app("http://banking-advice:8096",
                                            backend_url=f"http://127.0.0.1:{server.server_port}"))
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def facts(self, subject):
        return {"schemaVersion": INPUT_SCHEMA, "customerMessage": "What is my minimum payment?",
                "astraformCustomerContexts": [{"bindingKey": "banking", "contextRef": "bank-trial",
                                              "subjectRef": subject}]}

    def test_partner_fetches_both_customers_from_backend_and_ignores_supplied_statement(self):
        for subject, minimum in (("customer-a", "48"), ("customer-b", "72")):
            facts = self.facts(subject)
            # Even a valid but false supplied statement cannot replace the bank's record.
            if subject == "customer-b":
                facts["statement"] = {"minimumAmountDue": "999", "currency": "AUD",
                                      "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}
            rpc = send_message(self.client, facts, wrapped=True)
            answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
            self.assertEqual(minimum, answer["statement"]["minimumAmountDue"])
            self.assertEqual(subject, answer["bankingContext"]["subjectRef"])
            self.assertEqual(MODEL_TEXT, answer["message"])
            request = self.model_requests[-1]
            self.assertEqual("/v1/chat/completions", request["path"])
            self.assertNotIn("Authorization", request["headers"])
            messages = request["body"]["messages"]
            self.assertTrue(any(message["role"] == "system" for message in messages))
            user_text = "\n".join(message["content"] for message in messages if message["role"] == "user")
            for value in (facts["customerMessage"], minimum, subject + "-card", "2026-01-31", "2026-02-14"):
                self.assertIn(value, user_text)
            self.assertNotIn("999", user_text)
        self.assertEqual([f"/api/banking/v1/contexts/bank-trial/customers/{subject}/accounts"
                          for subject in ("customer-a", "customer-b")], self.requests)
        self.assertEqual(2, len(self.model_requests))

    def test_missing_identity_or_failed_lookup_never_falls_back_to_supplied_facts(self):
        facts = self.facts("unknown-customer")
        facts["statement"] = {"minimumAmountDue": "48", "currency": "AUD",
                              "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}
        self.assertEqual(-32603, send_message(self.client, facts)["error"]["code"])
        facts.pop("astraformCustomerContexts")
        self.assertEqual(-32602, send_message(self.client, facts)["error"]["code"])
        self.assertEqual([], self.model_requests)

    def test_partner_forwards_trusted_read_guard_and_accepts_matching_bank_version(self):
        facts = self.facts("customer-a")
        guard = {"contextId": "simulation:customer-a/run-123", "resourceVersion": 1,
                 "logicalTimeEpochMs": 1769904000000}
        facts["astraformCustomerContexts"][0]["readContext"] = guard
        rpc = send_message(self.client, facts, wrapped=True)
        answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
        self.assertEqual("payment_due", answer["decision"])
        self.assertEqual({key: [str(value)] for key, value in guard.items()},
                         parse_qs(urlsplit(self.requests[-1]).query))

    def test_partner_rejects_bank_data_outside_accepted_version_or_time(self):
        for changed in ({"resourceVersion": 2}, {"logicalTimeEpochMs": 1768435200000}):
            with self.subTest(changed=changed):
                facts = self.facts("customer-a")
                facts["astraformCustomerContexts"][0]["readContext"] = {
                    "contextId": "simulation-a", "resourceVersion": 1,
                    "logicalTimeEpochMs": 1769904000000, **changed}
                self.assertEqual(-32603, send_message(self.client, facts)["error"]["code"])
        self.assertEqual([], self.model_requests)

    def test_partial_read_guard_is_rejected_before_contacting_bank(self):
        facts = self.facts("customer-a")
        facts["astraformCustomerContexts"][0]["readContext"] = {"contextId": "simulation-a"}
        self.assertEqual(-32602, send_message(self.client, facts)["error"]["code"])
        self.assertEqual([], self.requests)
        self.assertEqual([], self.model_requests)

    def test_period_selects_older_statement_and_preserves_guard_while_default_stays_latest(self):
        earlier = {"billId": "older-bill", "accountId": "customer-a-card", "minimumAmountDue": "30",
                   "currency": "AUD", "statementEndDate": "2025-12-31", "dueDate": "2026-01-14"}
        self.extra_statements.append(earlier)
        facts = self.facts("customer-a")
        guard = {"contextId": "simulation:customer-a/run-123", "resourceVersion": 1,
                 "logicalTimeEpochMs": 1769904000000}
        facts["astraformCustomerContexts"][0]["readContext"] = guard
        facts["statementPeriod"] = "2025-12"
        answer = json.loads(send_message(self.client, facts, wrapped=True)["result"]["message"]["parts"][0]["text"])
        self.assertEqual(earlier, answer["statement"])
        self.assertEqual("2025-12", answer["statementPeriod"])
        selected_prompt = json.dumps(self.model_requests[-1]["body"]["messages"])
        self.assertIn("2025-12-31", selected_prompt)
        self.assertNotIn("2026-01-31", selected_prompt)
        self.assertEqual({key: [str(value)] for key, value in {**guard, "statementPeriod": "2025-12"}.items()},
                         parse_qs(urlsplit(self.requests[-1]).query))
        facts.pop("statementPeriod")
        answer = json.loads(send_message(self.client, facts)["result"]["message"]["parts"][0]["text"])
        self.assertEqual("2026-01-31", answer["statement"]["statementEndDate"])
        self.assertNotIn("statementPeriod", answer)

    def test_unavailable_period_does_not_substitute_latest_statement_or_assume_future(self):
        for period in ("2025-11", "2026-02", "0001-01", "9999-12"):
            with self.subTest(period=period):
                facts = {**self.facts("customer-a"), "statementPeriod": period}
                answer = json.loads(send_message(self.client, facts)["result"]["message"]["parts"][0]["text"])
                self.assertEqual("need_information", answer["decision"])
                self.assertEqual({}, answer["statement"])
                self.assertEqual(period, answer["statementPeriod"])
                self.assertEqual(MODEL_TEXT, answer["message"])
                selected_prompt = json.dumps(self.model_requests[-1]["body"]["messages"])
                self.assertIn(period, selected_prompt)
                self.assertNotIn("2026-01-31", selected_prompt)

    def test_invalid_period_is_rejected_before_contacting_bank(self):
        for period in (None, 202601, "", "0000-01", "2026-00", "2026-13", "2026-1", "10000-01", "2026-01-31", "2026-01\n"):
            with self.subTest(period=period):
                facts = {**self.facts("customer-a"), "statementPeriod": period}
                self.assertEqual(-32602, send_message(self.client, facts)["error"]["code"])
        self.assertEqual([], self.requests)
        self.assertEqual([], self.model_requests)

    def test_partner_rejects_missing_or_mismatched_period_and_wrong_month_statement(self):
        for mutation in (lambda view: view.pop("statementPeriod"),
                         lambda view: view.update(statementPeriod="2026-02"),
                         lambda view: view["statements"][0].update(statementEndDate="2025-12-31")):
            with self.subTest(mutation=mutation):
                self.response_transform = mutation
                facts = {**self.facts("customer-a"), "statementPeriod": "2026-01"}
                self.assertEqual(-32603, send_message(self.client, facts)["error"]["code"])
        self.assertEqual([], self.model_requests)


if __name__ == "__main__":
    unittest.main()
