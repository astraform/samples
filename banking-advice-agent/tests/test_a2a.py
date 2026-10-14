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

from app import INPUT_SCHEMA, create_app, llm_configuration


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
        "BANKING_LLM_TEMPERATURE": "0",
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
            {"BANKING_LLM_TIMEOUT_SECONDS": "900.01"},
            {"BANKING_LLM_MAX_OUTPUT_TOKENS": "0"},
            {"BANKING_LLM_MAX_OUTPUT_TOKENS": "32769"},
            {"BANKING_LLM_MAX_CONCURRENCY": "0"},
            {"BANKING_LLM_TEMPERATURE": "-0.1"},
            {"BANKING_LLM_TEMPERATURE": "2.1"},
            {"BANKING_LLM_TEMPERATURE": "NaN"},
            {"BANKING_LLM_TEMPERATURE": "Infinity"},
        ]
        for settings in bad_settings:
            with self.subTest(settings=settings), patch.dict(os.environ, settings):
                with self.assertRaises(ValueError):
                    create_app("http://banking-advice:8096", backend_url="")
        self.assertEqual([], self.model_requests)

    def test_generic_defaults_remain_unchanged_and_thinking_limits_are_finite(self):
        with patch.dict(os.environ):
            for key in ("TIMEOUT_SECONDS", "MAX_OUTPUT_TOKENS", "TEMPERATURE", "REASONING_EFFORT"):
                os.environ.pop("BANKING_LLM_" + key, None)
            config = llm_configuration()
        self.assertEqual(120, config["timeout"])
        self.assertEqual(2048, config["max_output_tokens"])
        self.assertEqual(0, config["temperature"])
        self.assertEqual("", config["reasoning_effort"])
        with patch.dict(os.environ, {"BANKING_LLM_TIMEOUT_SECONDS": "900",
                                     "BANKING_LLM_MAX_OUTPUT_TOKENS": "32768"}):
            config = llm_configuration()
        self.assertEqual(900, config["timeout"])
        self.assertEqual(32768, config["max_output_tokens"])
        self.assertEqual([], self.model_requests)

    def test_thinking_request_preserves_final_answer_and_keeps_reasoning_separate(self):
        reasoning = "Fixture reasoning is separate from final prose. " * 4000
        self.model_response = completion(reasoning=reasoning)
        self.assertGreater(len(json.dumps(self.model_response).encode()), 131072)
        with patch.dict(os.environ, {"BANKING_LLM_TIMEOUT_SECONDS": "840",
                                     "BANKING_LLM_MAX_OUTPUT_TOKENS": "32768",
                                     "BANKING_LLM_REASONING_EFFORT": "high",
                                     "BANKING_LLM_TEMPERATURE": "1.0",
                                     "BANKING_LLM_DIAGNOSTICS": "true"}):
            with self.assertLogs("uvicorn.error", level="INFO") as logs:
                with TestClient(create_app("http://banking-advice:8096", backend_url="")) as client:
                    rpc = send_message(client, {"schemaVersion": INPUT_SCHEMA,
                                               "customerMessage": "Explain the supplied records."})
        answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
        self.assertEqual(MODEL_TEXT, answer["message"])
        self.assertNotIn("Fixture reasoning", json.dumps(rpc))
        self.assertEqual(1, len(self.model_requests))
        request = self.model_requests[0]["body"]
        self.assertEqual(32768, request["max_tokens"])
        self.assertEqual("high", request["reasoning_effort"])
        self.assertEqual(1.0, request["temperature"])
        self.assertIsInstance(request["temperature"], float)
        self.assertIs(False, request["stream"])
        record = json.loads(next(line.split("banking_llm_attempt ", 1)[1] for line in logs.output
                                 if "banking_llm_attempt " in line))
        self.assertEqual("SUCCEEDED", record["status"])
        self.assertEqual(self.model_response, record["providerResponse"])

    def test_provider_envelope_at_one_mib_is_accepted_without_changing_final_answer(self):
        self.model_response = {**completion(), "padding": ""}
        overhead = len(json.dumps(self.model_response).encode())
        self.model_response["padding"] = "x" * (1048576 - overhead)
        self.assertEqual(1048576, len(json.dumps(self.model_response).encode()))
        rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain these records."})
        answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
        self.assertEqual(MODEL_TEXT, answer["message"])
        self.assertEqual(1, len(self.model_requests))

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
        evidence = json.loads(next(message["content"] for message in request["body"]["messages"]
                                   if message["role"] == "user"))
        self.assertEqual("customer-supplied", evidence["source"])
        self.assertNotIn("accountInterpretationsDateBasis", evidence)
        self.assertNotIn("accountInterpretations", evidence)
        self.assertNotIn("statementDateRoles", evidence)
        self.assertNotIn("issuedStatementInterpretation", evidence)

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
            ("oversize-response", {**completion(), "padding": "x" * 1048576}, 200),
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

    def test_legacy_customer_supplied_mode_keeps_conditional_unknown_date_answer_verbatim(self):
        actual_prose = "If your supplied statement is dated January 31, 2025, I would need its " \
                       "amount and due date; I have not verified it with the bank."
        self.model_response = completion(actual_prose)
        answer = json.loads(self.send({"schemaVersion": INPUT_SCHEMA,
                                       "customerMessage": "Explain my supplied statement."})["result"]["message"]["parts"][0]["text"])
        self.assertEqual(actual_prose, answer["message"])
        self.assertNotIn("bankingContext", answer)

    def test_model_timeout_is_one_failed_attempt_not_a_fallback_reply(self):
        self.model_delay = 1.25
        rpc = self.send({"schemaVersion": INPUT_SCHEMA, "customerMessage": "Explain this statement."})
        self.assertEqual(-32603, rpc["error"]["code"])
        self.assertNotIn("result", rpc)
        self.assertEqual(1, len(self.model_requests))


R4_BAD_DATE_PROSE = (
    "The minimum payment for the January 2026 (Jan 31, 2025) statement is $48 AUD, "
    "and it is due on February 14, 2026. \n\nRegarding your total balance, your current ledger debt "
    "as of the latest bank records available is $1,200 AUD. Please note that issued statements "
    "describe obligations for their specific closing period (Jan 31, 2026), whereas current "
    "ledger amounts reflect the state at the time those records were generated (Feb 5, 2026). "
    "These two figures may differ; only the statement amount counts as the official debt "
    "for that specific billing cycle.")


R5_BAD_DATE_ROLE_PROSE = (
    "Based on your latest issued statement, which covers the closing period of February 2026 "
    "(statement date: 2026-02-14), the minimum amount due is A$48. "
    "The payment is required by February 14, 2026.")


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
        self.backend_url = f"http://127.0.0.1:{server.server_port}"
        self.client = TestClient(create_app("http://banking-advice:8096", backend_url=self.backend_url))
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

    def model_evidence(self):
        messages = self.model_requests[-1]["body"]["messages"]
        return json.loads(next(message["content"] for message in messages if message["role"] == "user"))

    def credit_card(self, **overrides):
        return {"accountId": "customer-a-card", "name": "Credit card", "productCode": "CC001",
                "balance": "-1200", "currency": "AUD", "limit": "3000", **overrides}

    def test_negative_card_debt_and_remaining_limit_are_grounded_before_model_call(self):
        def bank_view(view):
            view["accounts"] = [self.credit_card()]
            view["statements"][0]["closingAmount"] = "-1200"

        self.response_transform = bank_view
        # The model's real response is not replaced by a successful canned answer.
        actual_prose = "  Model-authored explanation — unchanged.\n  "
        self.model_response = completion(actual_prose)
        answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
        evidence = self.model_evidence()
        self.assertEqual("bank-api", evidence["source"])
        self.assertEqual([self.credit_card()], evidence["accounts"])
        self.assertEqual([{"accountId": "customer-a-card", "productCode": "CC001", "currency": "AUD",
                           "balanceConvention": "negative-is-amount-owed", "currentAmountOwed": "1200",
                           "currentCreditBalance": "0", "creditLimit": "3000", "remainingCreditLimit": "1800",
                           "missingFields": []}], evidence["accountInterpretations"])
        self.assertEqual("2026-02-01T00:00:00+00:00", evidence["bankAsOfUtc"])
        self.assertEqual("current-ledger-at-bankAsOfUtc", evidence["accountInterpretationsDateBasis"])
        self.assertEqual({"closingDate": "2026-01-31", "closingPeriod": "2026-01",
                          "paymentDueDate": "2026-02-14"}, evidence["statementDateRoles"])
        self.assertEqual(0.0, self.model_requests[-1]["body"]["temperature"])
        self.assertEqual("2026-01-31", evidence["statementResult"]["statement"]["statementEndDate"])
        self.assertEqual("48", answer["statement"]["minimumAmountDue"])
        self.assertEqual("2026-02-14", answer["statement"]["dueDate"])
        self.assertEqual(actual_prose, answer["message"])
        self.assertNotIn("accountInterpretations", answer)  # Provider inputs do not change the A2A contract.
        self.assertNotIn("issuedStatementInterpretation", answer)
        self.assertEqual({"accountId": "customer-a-card", "currency": "AUD",
                          "balanceConvention": "negative-is-amount-owed",
                          "dateBasis": "issued-statement-at-statementEndDate", "statementEndDate": "2026-01-31",
                          "closingAmountOwed": "1200", "closingCreditBalance": "0"},
                         evidence["issuedStatementInterpretation"])
        self.assertEqual(1, len(self.model_requests))

    def test_arbitrary_budget_questions_use_issued_debt_not_a_later_current_credit_balance(self):
        def bank_view(view):
            view["accounts"] = [self.credit_card(balance="200.00", currency="EUR")]
            view["statements"][0].update(closingAmount="-225.50", minimumAmountDue="17.25", currency="EUR")

        self.response_transform = bank_view
        # B below/equal/above M and D, including zero. The fixture verifies input
        # provenance and verbatim transport; only a real model run assesses prose.
        for index, budget in enumerate(("0", "17.24", "17.25", "80.13", "225.50", "250.75")):
            with self.subTest(budget=budget):
                question = (f"I have EUR {budget} for payment. Does it cover the minimum and the whole "
                            "issued balance? Explain the nominal comparison, not an actual payment.")
                prose = f"  Actual fixture provider prose for request {index}, returned unchanged.  "
                self.model_response = completion(prose)
                answer = json.loads(send_message(self.client, {**self.facts("customer-a"), "customerMessage": question})
                                    ["result"]["message"]["parts"][0]["text"])
                evidence = self.model_evidence()
                self.assertEqual(question, evidence["customerQuestion"])
                self.assertEqual(("0", "200.00"), (evidence["accountInterpretations"][0]["currentAmountOwed"],
                                                    evidence["accountInterpretations"][0]["currentCreditBalance"]))
                self.assertEqual({"accountId": "customer-a-card", "currency": "EUR",
                                  "balanceConvention": "negative-is-amount-owed",
                                  "dateBasis": "issued-statement-at-statementEndDate", "statementEndDate": "2026-01-31",
                                  "closingAmountOwed": "225.50", "closingCreditBalance": "0"},
                                 evidence["issuedStatementInterpretation"])
                self.assertEqual(("17.25", "-225.50", "EUR"), tuple(answer["statement"][key]
                                                                        for key in ("minimumAmountDue", "closingAmount", "currency")))
                self.assertEqual(prose, answer["message"])
                self.assertNotIn("budget", evidence)  # No parser, guessed budget or financial reply is authored.
                self.assertNotIn("issuedStatementInterpretation", answer)
        self.assertEqual(6, len(self.model_requests))

    def test_issued_zero_or_positive_credit_is_not_current_debt_or_a_cancelled_minimum(self):
        for closing, owed, credit in (("0", "0", "0"), ("150.75", "0", "150.75")):
            with self.subTest(closing=closing):
                def bank_view(view):
                    view["accounts"] = [self.credit_card(balance="-900")]
                    view["statements"][0].update(closingAmount=closing, minimumAmountDue="7.00")

                self.response_transform = bank_view
                question = "I have AUD 20. What do this issued balance and recorded minimum mean for my budget?"
                answer = json.loads(send_message(self.client, {**self.facts("customer-a"), "customerMessage": question})
                                    ["result"]["message"]["parts"][0]["text"])
                evidence = self.model_evidence()
                self.assertEqual((owed, credit), (evidence["issuedStatementInterpretation"]["closingAmountOwed"],
                                                  evidence["issuedStatementInterpretation"]["closingCreditBalance"]))
                self.assertEqual("900", evidence["accountInterpretations"][0]["currentAmountOwed"])
                self.assertEqual("7.00", answer["statement"]["minimumAmountDue"])
                self.assertEqual("payment_due", answer["decision"])
                self.assertEqual(MODEL_TEXT, answer["message"])
        self.assertEqual(2, len(self.model_requests))

    def test_missing_budget_or_unknown_issued_sign_and_currency_are_not_guessed(self):
        for case in ("missing-budget", "foreign-budget", "missing-closing", "unknown-product",
                     "different-currency", "ambiguous-account"):
            with self.subTest(case=case):
                def bank_view(view):
                    view["accounts"] = [self.credit_card()]
                    view["statements"][0]["closingAmount"] = "-900.25"
                    if case == "missing-closing":
                        view["statements"][0].pop("closingAmount")
                    elif case == "unknown-product":
                        view["accounts"][0]["productCode"] = "CUSTOM"
                    elif case == "different-currency":
                        view["accounts"][0]["currency"] = "EUR"
                    elif case == "ambiguous-account":
                        view["accounts"].append(self.credit_card(productCode="CUSTOM"))

                self.response_transform = bank_view
                question = ("I have USD 50 for an AUD statement. Can you compare without an exchange rate?"
                            if case == "foreign-budget" else
                            "Does my available budget cover the required amount? I have not provided an amount or currency.")
                answer = json.loads(send_message(self.client, {**self.facts("customer-a"), "customerMessage": question})
                                    ["result"]["message"]["parts"][0]["text"])
                evidence = self.model_evidence()
                if case in {"missing-budget", "foreign-budget"}:
                    self.assertEqual(("AUD", "900.25"), (evidence["issuedStatementInterpretation"]["currency"],
                                                         evidence["issuedStatementInterpretation"]["closingAmountOwed"]))
                else:
                    self.assertEqual({}, evidence["issuedStatementInterpretation"])
                self.assertEqual(question, evidence["customerQuestion"])
                self.assertEqual(MODEL_TEXT, answer["message"])
                self.assertNotIn("budget", evidence)
                self.assertEqual("48", answer["statement"]["minimumAmountDue"])
        self.assertEqual(6, len(self.model_requests))

    def test_issued_decimal_precision_is_preserved_independently_of_current_ledger(self):
        closing = "-12345678901234567890123456789.123456789"
        def bank_view(view):
            view["accounts"] = [self.credit_card(balance="0")]
            view["statements"][0]["closingAmount"] = closing

        self.response_transform = bank_view
        send_message(self.client, self.facts("customer-a"))
        evidence = self.model_evidence()
        self.assertEqual(closing[1:], evidence["issuedStatementInterpretation"]["closingAmountOwed"])
        self.assertEqual("0", evidence["accountInterpretations"][0]["currentAmountOwed"])
        self.assertEqual(closing, evidence["statementResult"]["statement"]["closingAmount"])
        self.assertEqual(1, len(self.model_requests))

    def test_actual_r9_minimum_coverage_contradiction_is_preserved_without_a_hidden_retry(self):
        self.response_transform = self.issued_card_view
        question = 'Can you tell me if paying AU$100 will clear my entire January 2026 credit card balance of AU$1,200, or if it only covers the minimum payment?'
        actual_prose = 'Paying AU$100 will not clear your entire January 2026 credit card balance. It will only cover part of the minimum payment.\n\nFor the closing period ending January 31, 2026:\n*   **Total Amount Owed:** AU$1,200\n*   **Minimum Payment Due:** AU$48 (due by February 14, 2026)\n*   **Your Proposed Payment:** AU$100\n\nAn AU$100 payment covers the minimum payment requirement but does not repay the full balance of AU$1,200.'
        self.model_response = completion(actual_prose)
        answer = json.loads(send_message(self.client, {**self.facts("customer-a"), "customerMessage": question,
                                                       "statementPeriod": "2026-01"})
                            ["result"]["message"]["parts"][0]["text"])
        # This is the real contradictory provider reply. Guidance affects future
        # generation; there is no prose repair or semantic truth classifier.
        self.assertEqual(actual_prose, answer["message"])
        evidence = self.model_evidence()
        self.assertEqual(question, evidence["customerQuestion"])
        self.assertEqual("48", evidence["statementResult"]["statement"]["minimumAmountDue"])
        self.assertEqual("1200", evidence["issuedStatementInterpretation"]["closingAmountOwed"])
        self.assertEqual("AUD", evidence["issuedStatementInterpretation"]["currency"])
        self.assertEqual(1, len(self.model_requests))

    def test_actual_r8_incomplete_reply_is_not_rewritten_or_retried_by_budget_guidance(self):
        self.response_transform = self.issued_card_view
        question = ("I have AUD 100 available. My January 2026 statement shows a closing balance of AUD 1,200 owed "
                    "and a minimum payment of AUD 48. Does my AU$100 cover the minimum payment? "
                    "How much of the total balance would it repay?")
        # Preserved real R8 counterexample: inputs/prompt can improve generation,
        # but there is no semantic answer validator or successful-answer repair.
        actual_prose = ("Based on your January 2026 statement, the minimum payment due is AUD 48. "
                        "Your available funds of AUD 100 would cover this minimum payment in full.\n\n"
                        "Regarding the total balance: The statement shows a closing balance of AUD 1,200 owed "
                        "for the period ending January 31, 2026. Repaying the AUD 48 minimum payment would "
                        "reduce this specific debt by that amount only. It is not possible to determine "
                        "how much of the total balance would be repaid based solely on a partial payment "
                        "without knowing your repayment policy or if additional payments were made separately.\n\n"
                        "Please note that the current ledger as of February 5, 2026, shows AUD 1,200 owed "
                        "with a remaining credit limit of AUD 1,800. This does not confirm whether the "
                        "January statement balance has been paid off.")
        self.model_response = completion(actual_prose)
        answer = json.loads(send_message(self.client, {**self.facts("customer-a"), "customerMessage": question,
                                                       "statementPeriod": "2026-01"})
                            ["result"]["message"]["parts"][0]["text"])
        self.assertEqual(actual_prose, answer["message"])
        self.assertEqual(question, self.model_evidence()["customerQuestion"])
        self.assertEqual("1200", self.model_evidence()["issuedStatementInterpretation"]["closingAmountOwed"])
        self.assertEqual(1, len(self.model_requests))

    def test_current_balance_cannot_cancel_or_replace_an_issued_statement_obligation(self):
        for balance, owed, credit, remaining in (("-1200", "1200", "0", "1800"),
                                                 ("0", "0", "0", "3000"),
                                                 ("200", "0", "200", "3000"),
                                                 ("-3200", "3200", "0", "0")):
            with self.subTest(balance=balance):
                def bank_view(view):
                    view["accounts"] = [self.credit_card(balance=balance)]
                    view["statements"][0]["closingAmount"] = "-1200"

                self.response_transform = bank_view
                answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
                interpretation = self.model_evidence()["accountInterpretations"][0]
                self.assertEqual(owed, interpretation["currentAmountOwed"])
                self.assertEqual(credit, interpretation["currentCreditBalance"])
                self.assertEqual(remaining, interpretation["remainingCreditLimit"])
                self.assertEqual("-1200", answer["statement"]["closingAmount"])
                self.assertEqual("48", answer["statement"]["minimumAmountDue"])
                self.assertEqual("payment_due", answer["decision"])
                self.assertEqual(MODEL_TEXT, answer["message"])

    def test_interpretations_keep_accounts_and_currencies_separate_and_unknown_products_unknown(self):
        accounts = [self.credit_card(),
                    self.credit_card(accountId="euro-card", productCode="CC002", currency="EUR",
                                     balance="-225.50", limit="500.00"),
                    self.credit_card(accountId="deposit", productCode="TS001", balance="-50"),
                    self.credit_card(accountId="unknown", productCode="CUSTOM", name="Credit card", balance="-900")]
        self.response_transform = lambda view: view.update(accounts=accounts)
        send_message(self.client, self.facts("customer-a"))
        evidence = self.model_evidence()
        self.assertEqual(accounts, evidence["accounts"])
        self.assertEqual(["customer-a-card", "euro-card"], [item["accountId"] for item in evidence["accountInterpretations"]])
        aud, eur = evidence["accountInterpretations"]
        self.assertEqual(("AUD", "1800"), (aud["currency"], aud["remainingCreditLimit"]))
        self.assertEqual(("EUR", "225.50", "274.50"), (eur["currency"], eur["currentAmountOwed"], eur["remainingCreditLimit"]))

    def test_missing_limit_or_balance_does_not_become_a_zero_or_guessed_capacity(self):
        for missing in ("limit", "balance"):
            with self.subTest(missing=missing):
                account = self.credit_card()
                account.pop(missing)
                self.response_transform = lambda view: view.update(accounts=[account])
                answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
                interpretation = self.model_evidence()["accountInterpretations"][0]
                self.assertEqual([missing], interpretation["missingFields"])
                self.assertNotIn("remainingCreditLimit", interpretation)
                self.assertEqual("payment_due", answer["decision"])
                if missing == "limit":
                    self.assertEqual("1200", interpretation["currentAmountOwed"])
                    self.assertNotIn("creditLimit", interpretation)
                else:
                    self.assertEqual("3000", interpretation["creditLimit"])
                    self.assertNotIn("currentAmountOwed", interpretation)

    def test_malformed_card_amounts_fail_before_model_dispatch_without_substituted_advice(self):
        for key, value in (("balance", True), ("balance", 1200), ("balance", "NaN"),
                           ("balance", "-1e3"), ("balance", "9" * 41),
                           ("limit", "-1"), ("limit", "+3000"), ("limit", "Infinity")):
            with self.subTest(key=key, value=value):
                self.response_transform = lambda view: view.update(accounts=[self.credit_card(**{key: value})])
                rpc = send_message(self.client, self.facts("customer-a"))
                self.assertEqual(-32603, rpc["error"]["code"])
                self.assertNotIn("result", rpc)
        self.assertEqual([], self.model_requests)

    def test_statement_without_its_own_account_is_rejected_before_model_call(self):
        def bank_view(view):
            view["accounts"] = [self.credit_card(accountId="another-card")]

        self.response_transform = bank_view
        rpc = send_message(self.client, self.facts("customer-a"))
        self.assertEqual(-32603, rpc["error"]["code"])
        self.assertNotIn("result", rpc)
        self.assertEqual([], self.model_requests)

    def test_large_bounded_decimal_card_amounts_do_not_lose_precision(self):
        balance = "-12345678901234567890123456789.123456789"
        limit = "22345678901234567890123456789.123456789"
        self.response_transform = lambda view: view.update(accounts=[self.credit_card(balance=balance, limit=limit)])
        send_message(self.client, self.facts("customer-a"))
        interpretation = self.model_evidence()["accountInterpretations"][0]
        self.assertEqual(balance[1:], interpretation["currentAmountOwed"])
        self.assertEqual("10000000000000000000000000000.000000000", interpretation["remainingCreditLimit"])

    def issued_card_view(self, view):
        view["accounts"] = [self.credit_card()]
        if view["statements"]:
            view["statements"][0]["closingAmount"] = "-1200"
        view["logicalTimeEpochMs"] = 1770249600000  # Actual counterexample's bank as-of: Feb 5, 2026.

    def test_actual_wrong_year_reply_fails_closed_once_and_preserves_failed_provider_prose(self):
        self.response_transform = self.issued_card_view
        self.model_response = completion(R4_BAD_DATE_PROSE)
        with patch.dict(os.environ, {"BANKING_LLM_DIAGNOSTICS": "true"}):
            with TestClient(create_app("http://banking-advice:8096", backend_url=self.backend_url)) as client:
                with self.assertLogs("uvicorn.error", level="INFO") as logs:
                    rpc = send_message(client, {**self.facts("customer-a"), "statementPeriod": "2026-01"})
        self.assertEqual(-32603, rpc["error"]["code"])
        self.assertNotIn("result", rpc)
        self.assertEqual(1, len(self.model_requests))
        attempts = [json.loads(record.message.removeprefix("banking_llm_attempt "))
                    for record in logs.records if record.message.startswith("banking_llm_attempt ")]
        self.assertEqual(1, len(attempts))
        self.assertEqual("FAILED", attempts[0]["status"])
        self.assertEqual("ValueError", attempts[0]["failureType"])
        self.assertTrue(attempts[0]["providerRequestDispatched"])
        self.assertEqual(R4_BAD_DATE_PROSE, attempts[0]["providerResponse"]["choices"][0]["message"]["content"])

    def test_source_correct_dates_and_original_question_are_accepted_without_prose_repair(self):
        self.response_transform = self.issued_card_view
        for actual_prose in (R4_BAD_DATE_PROSE.replace("Jan 31, 2025", "Jan 31, 2026"),
                             "On your latest issued statement for the closing period of January 2026, "
                             "the minimum amount due is 48 AUD. The stated due date on this document "
                             "is February 14, 2026."):
            with self.subTest(actual_prose=actual_prose):
                self.model_response = completion(actual_prose)
                facts = {**self.facts("customer-a"), "customerMessage":
                         "Explain the minimum amount due and due date on my latest issued statement. "
                         "This is a read-only verification; do not make any payment."}
                answer = json.loads(send_message(self.client, facts)["result"]["message"]["parts"][0]["text"])
                self.assertEqual(actual_prose, answer["message"])
                self.assertEqual("48", answer["statement"]["minimumAmountDue"])
                self.assertEqual("2026-02-14", answer["statement"]["dueDate"])
        self.assertEqual(2, len(self.model_requests))

    def test_common_calendar_date_forms_match_the_source_without_rewriting_text(self):
        self.response_transform = self.issued_card_view
        for mentioned_date in ("2026-01-31", "Jan 31, 2026", "Jan. 31st, 2026", "January 31, 2026",
                               "31 January 2026", "31-Jan-2026", "31January2026", "Jan31,2026"):
            with self.subTest(mentioned_date=mentioned_date):
                actual_prose = "The selected statement closed on " + mentioned_date + "."
                self.model_response = completion(actual_prose)
                answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
                self.assertEqual(actual_prose, answer["message"])

    def test_invalid_or_new_unsupported_calendar_dates_never_become_a_successful_reply(self):
        self.response_transform = self.issued_card_view
        for mentioned_date in ("2026-02-30", "February 30, 2026", "31 April 2026", "January 2025",
                               "2025-01", "Jan31,2025", "31January2025"):
            with self.subTest(mentioned_date=mentioned_date):
                before = len(self.model_requests)
                self.model_response = completion("Your selected statement date is " + mentioned_date + ".")
                rpc = send_message(self.client, self.facts("customer-a"))
                self.assertEqual(-32603, rpc["error"]["code"])
                self.assertNotIn("result", rpc)
                self.assertEqual(before + 1, len(self.model_requests))

    def test_requested_unavailable_month_and_question_origin_date_can_be_discussed_conditionally(self):
        self.response_transform = self.issued_card_view
        cases = [({**self.facts("customer-a"), "statementPeriod": "2026-05"},
                  "The lookup returned no issued statement for May 2026 (2026-05); its amount and due date are unknown."),
                 ({**self.facts("customer-a"), "customerMessage": "Can you verify my claimed December 31, 2025 statement?"},
                  "Your claimed date of 31 December 2025 is unverified by this selected bank record, "
                  "which closes on January 31, 2026.")]
        for facts, actual_prose in cases:
            with self.subTest(actual_prose=actual_prose):
                self.model_response = completion(actual_prose)
                answer = json.loads(send_message(self.client, facts)["result"]["message"]["parts"][0]["text"])
                self.assertEqual(actual_prose, answer["message"])
                if "statementPeriod" in facts:
                    self.assertEqual({}, answer["statement"])
                    self.assertEqual("need_information", answer["decision"])

    def test_four_digit_monetary_amounts_are_not_interpreted_as_calendar_years(self):
        self.response_transform = self.issued_card_view
        actual_prose = "The bank shows a limit of 3000 AUD, current debt of 1200 AUD and remaining " \
                       "modeled limit of 1800 AUD; purchasing authorization is unknown."
        self.model_response = completion(actual_prose)
        answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
        self.assertEqual(actual_prose, answer["message"])

    def test_actual_r5_closing_due_role_mixup_fails_closed_once_with_raw_response_preserved(self):
        self.response_transform = self.issued_card_view
        self.model_response = completion(R5_BAD_DATE_ROLE_PROSE)
        with patch.dict(os.environ, {"BANKING_LLM_DIAGNOSTICS": "true"}):
            with TestClient(create_app("http://banking-advice:8096", backend_url=self.backend_url)) as client:
                with self.assertLogs("uvicorn.error", level="INFO") as logs:
                    rpc = send_message(client, self.facts("customer-a"))
        self.assertEqual(-32603, rpc["error"]["code"])
        self.assertNotIn("result", rpc)
        self.assertEqual(1, len(self.model_requests))
        attempts = [json.loads(record.message.removeprefix("banking_llm_attempt "))
                    for record in logs.records if record.message.startswith("banking_llm_attempt ")]
        self.assertEqual(1, len(attempts))
        self.assertEqual("FAILED", attempts[0]["status"])
        self.assertEqual("ValueError", attempts[0]["failureType"])
        self.assertEqual(R5_BAD_DATE_ROLE_PROSE, attempts[0]["providerResponse"]["choices"][0]["message"]["content"])

    def test_correct_closing_and_due_roles_in_one_sentence_remain_verbatim(self):
        self.response_transform = self.issued_card_view
        cases = (R5_BAD_DATE_ROLE_PROSE.replace("closing period of February 2026", "closing period of January 2026")
                                      .replace("statement date: 2026-02-14", "statement date: 2026-01-31"),
                 "The statement date is Jan 31, 2026; the due date is Feb 14, 2026.",
                 "The closing period ended on January 31, 2026, payment due February 14, 2026.")
        for actual_prose in cases:
            with self.subTest(actual_prose=actual_prose):
                self.model_response = completion(actual_prose)
                answer = json.loads(send_message(self.client, self.facts("customer-a"))["result"]["message"]["parts"][0]["text"])
                self.assertEqual(actual_prose, answer["message"])

    def test_explicit_wrong_closing_or_due_roles_fail_even_when_the_date_exists_in_another_role(self):
        self.response_transform = self.issued_card_view
        for actual_prose in ("The closing period is February 2026.", "The statement date is February 14, 2026.",
                             "The statement closing date is 2026-02-14.", "The payment due date is January 31, 2026.",
                             "The closing period ended on February 14, 2026, payment due February 14, 2026."):
            with self.subTest(actual_prose=actual_prose):
                before = len(self.model_requests)
                self.model_response = completion(actual_prose)
                rpc = send_message(self.client, self.facts("customer-a"))
                self.assertEqual(-32603, rpc["error"]["code"])
                self.assertNotIn("result", rpc)
                self.assertEqual(before + 1, len(self.model_requests))

    def test_question_origin_role_and_unavailable_requested_period_remain_unverified_discussion(self):
        self.response_transform = self.issued_card_view
        cases = [({**self.facts("customer-a"), "statementPeriod": "2026-05"},
                  "No bank-issued statement for the closing period of May 2026 was returned; the date is unknown."),
                 ({**self.facts("customer-a"), "customerMessage": "Can you verify my claimed statement date December 31, 2025?"},
                  "Your claimed statement date December 31, 2025 is not verified by the selected bank record. "
                  "The selected statement date is January 31, 2026.")]
        for facts, actual_prose in cases:
            with self.subTest(actual_prose=actual_prose):
                self.model_response = completion(actual_prose)
                answer = json.loads(send_message(self.client, facts)["result"]["message"]["parts"][0]["text"])
                self.assertEqual(actual_prose, answer["message"])
                if "statementPeriod" in facts:
                    self.assertEqual("need_information", answer["decision"])
                    self.assertEqual({}, self.model_evidence()["statementDateRoles"])

    def test_temperature_is_optional_bounded_configuration_serialized_explicitly_on_the_wire(self):
        for setting, expected in ((None, 0.0), ("0.25", 0.25), ("2", 2.0)):
            with self.subTest(setting=setting), patch.dict(os.environ):
                if setting is None:
                    os.environ.pop("BANKING_LLM_TEMPERATURE", None)
                else:
                    os.environ["BANKING_LLM_TEMPERATURE"] = setting
                with TestClient(create_app("http://banking-advice:8096", backend_url=self.backend_url)) as client:
                    rpc = send_message(client, self.facts("customer-a"))
                answer = json.loads(rpc["result"]["message"]["parts"][0]["text"])
                self.assertEqual(MODEL_TEXT, answer["message"])
                self.assertEqual(expected, self.model_requests[-1]["body"]["temperature"])

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
