"""Offline state, restart, fault and SDK-boundary checks for the conversation fixture."""

import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

from a2a.helpers import new_text_message
from starlette.testclient import TestClient

from app import create_app, RefundAgent
from conversation import ConversationStore, INPUT_SCHEMA, OUTPUT_SCHEMA


def initial(request_date="2026-01-31", **changes):
    return {"schemaVersion": INPUT_SCHEMA, "phase": "request",
            "customerMessage": "I would like to return my unopened item.",
            "requestDate": request_date, "itemCondition": "unopened", **changes}


def followup(purchase_date="2026-01-01", **changes):
    return {"schemaVersion": INPUT_SCHEMA, "phase": "provide_information",
            "customerMessage": "My purchase date was " + purchase_date,
            "purchaseDate": purchase_date, **changes}


class ConversationStateTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.database = Path(folder.name) / "state.sqlite3"
        self.store = ConversationStore(self.database)

    def send(self, facts, message_id="first", context_id="customer-a", store=None):
        return (store or self.store).reply(context_id, message_id, json.dumps(facts), facts)

    def test_original_request_date_controls_day30_even_when_reply_arrives_day31(self):
        answer = json.loads(self.send(initial()).text)
        self.assertEqual({
            "schemaVersion": OUTPUT_SCHEMA, "decision": "needs_information",
            "reasonCode": "MISSING_REQUIRED_INFORMATION", "policyVersion": "refund-policy-v1",
            "message": "Please provide your purchase date.",
        }, answer)
        # No clock or reply date participates: the stored request was January 31.
        answer = json.loads(self.send(followup(), "second").text)
        self.assertEqual("eligible", answer["decision"])
        self.assertEqual("WITHIN_WINDOW_UNOPENED", answer["reasonCode"])

    def test_day0_day30_day31_boundaries_are_separate_contexts(self):
        for request_date, expected in (("2026-01-01", "eligible"),
                                       ("2026-01-31", "eligible"),
                                       ("2026-02-01", "ineligible")):
            with self.subTest(request_date=request_date):
                self.send(initial(request_date), context_id=request_date)
                answer = json.loads(self.send(followup(), "second", request_date).text)
                self.assertEqual(expected, answer["decision"])

    def test_followup_without_context_state_does_not_leak_another_context(self):
        self.send(initial())
        with self.assertRaisesRegex(ValueError, "No initial request"):
            self.send(followup(), "second", "customer-b")
        self.send(initial("2026-02-01"), context_id="customer-b")
        self.assertEqual("ineligible", json.loads(self.send(followup(), "second", "customer-b").text)["decision"])
        self.assertEqual("eligible", json.loads(self.send(followup(), "second").text)["decision"])

    def test_replay_preserves_exact_reply_id_and_body_even_after_followup(self):
        first = self.send(initial())
        second = self.send(followup(), "second")
        self.assertEqual(first, self.send(initial()))
        self.assertEqual(second, self.send(followup(), "second"))
        self.assertNotEqual(first.message_id, second.message_id)

    def test_changed_message_content_rejected_without_changing_context(self):
        self.send(initial())
        with self.assertRaisesRegex(ValueError, "different content"):
            self.send(initial("2026-02-01"))
        self.assertEqual("eligible", json.loads(self.send(followup(), "second").text)["decision"])

    def test_replay_is_exact_input_text_not_json_equivalence(self):
        facts = initial()
        self.send(facts)
        with self.assertRaisesRegex(ValueError, "different content"):
            self.store.reply("customer-a", "first", json.dumps(facts, indent=2), facts)

    def test_state_and_replay_survive_store_recreation(self):
        first = self.send(initial())
        restarted = ConversationStore(self.database)
        self.assertEqual(first, self.send(initial(), store=restarted))
        second = self.send(followup(), "second", store=restarted)
        self.assertEqual("eligible", json.loads(second.text)["decision"])
        self.assertEqual(second, self.send(followup(), "second", store=ConversationStore(self.database)))

    def test_concurrent_duplicate_requests_commit_one_recorded_reply(self):
        with ThreadPoolExecutor(max_workers=2) as executor:
            replies = list(executor.map(lambda _: self.send(initial()), range(2)))
        self.assertEqual(replies[0], replies[1])

    def test_new_request_in_an_existing_context_is_rejected(self):
        self.send(initial())
        with self.assertRaisesRegex(ValueError, "already has a request"):
            self.send(initial(), "new-initial")

    def test_immutable_facts_cannot_change_but_matching_repetitions_are_allowed(self):
        self.send(initial())
        for changes in ({"requestDate": "2026-02-01"}, {"itemCondition": "opened"},
                        {"requestDate": None}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "conflicts"):
                self.send(followup(**changes), "second")
        answer = self.send(followup(requestDate="2026-01-31", itemCondition="unopened"), "second")
        self.assertEqual("eligible", json.loads(answer.text)["decision"])
        with self.assertRaisesRegex(ValueError, "purchaseDate conflicts"):
            self.send(followup("2026-01-02"), "third")

    def test_invalid_initial_facts_are_rejected_without_creating_state(self):
        invalid = [initial("yesterday"), initial("2026-02-30"), initial(itemCondition="opened"),
                   initial(purchaseDate=None), initial(customerMessage=" "),
                   initial(customerMessage=True), initial(phase="cancel"), initial(extra="x"),
                   initial(schemaVersion="other")]
        for facts in invalid:
            with self.subTest(facts=facts), self.assertRaises(ValueError):
                self.send(facts)
        with self.assertRaisesRegex(ValueError, "No initial request"):
            self.send(followup(), "second")
        self.send(initial())

    def test_invalid_followup_dates_are_rejected_and_can_be_corrected(self):
        self.send(initial())
        for purchase_date in ("yesterday", "2026-02-30", "2026-02-01"):
            with self.subTest(purchase_date=purchase_date), self.assertRaises(ValueError):
                self.send(followup(purchase_date), "second")
        self.assertEqual("eligible", json.loads(self.send(followup(), "second").text)["decision"])

    def test_wrong_day30_fault_is_explicit_and_recorded_replies_survive_fault_change(self):
        faulty = ConversationStore(self.database, wrong_day30=True)
        self.send(initial(), store=faulty)
        wrong = self.send(followup(), "second", store=faulty)
        self.assertEqual("ineligible", json.loads(wrong.text)["decision"])
        self.assertEqual(wrong, self.send(followup(), "second", store=ConversationStore(self.database)))
        self.send(initial("2026-01-30"), context_id="day29", store=faulty)
        self.assertEqual("eligible", json.loads(self.send(followup(), "second", "day29", faulty).text)["decision"])


class ConversationA2ATest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.database = Path(folder.name) / "state.sqlite3"

    def client(self, **settings):
        return TestClient(create_app("http://refund-conversation:8095", mode="conversation-reference",
                                    conversation_store=ConversationStore(self.database), **settings))

    def send(self, client, facts, message_id="first", context_id="episode-one", logical_time=0):
        content = json.dumps({
            "schemaVersion": "astraform.isolated-event-a2a-invocation.v1",
            "logicalTimeEpochMs": logical_time,
            "request": {"schemaVersion": "astraform.agent-event-request.v1", "facts": facts},
        })
        return client.post("/a2a", headers={"A2A-Version": "1.0"}, json={
            "jsonrpc": "2.0", "id": "rpc-" + message_id, "method": "SendMessage",
            "params": {
                "message": {"role": "ROLE_USER", "messageId": message_id, "contextId": context_id,
                            "parts": [{"text": content, "mediaType": "text/plain"}]},
                "configuration": {"acceptedOutputModes": ["text"], "returnImmediately": False},
            },
        }).json()

    def test_sdk_messages_continue_after_application_restart_and_replay_identically(self):
        with self.client() as client:
            first = self.send(client, initial())["result"]["message"]
            self.assertEqual("episode-one", first["contextId"])
            self.assertFalse(first.get("taskId"))
        with self.client() as restarted:
            second = self.send(restarted, followup(), "second", logical_time=86_400_000)["result"]["message"]
            self.assertEqual("episode-one", second["contextId"])
            self.assertEqual("eligible", json.loads(second["parts"][0]["text"])["decision"])
            self.assertEqual(first, self.send(restarted, initial())["result"]["message"])
            self.assertEqual(second, self.send(restarted, followup(), "second", logical_time=86_400_000)["result"]["message"])

    def test_conflicting_message_and_missing_context_return_parameter_errors(self):
        with self.client() as client:
            self.send(client, initial())
            for rpc in (self.send(client, initial("2026-02-01")),
                        self.send(client, followup(), "second", "another-context"),
                        self.send(client, followup(requestDate="2026-02-01"), "second")):
                self.assertNotIn("result", rpc)
                self.assertEqual(-32602, rpc["error"]["code"])

    def test_health_card_and_fault_configuration_identify_conversation_mode(self):
        with self.client() as client:
            health = client.get("/health").json()
            self.assertEqual("conversation-reference", health["mode"])
            self.assertEqual({"delayMs": 0, "wrongDay30": False}, health["faults"])
            card = client.get("/.well-known/agent-card.json").json()
            self.assertEqual("refund-conversation-v1", card["skills"][0]["id"])
            self.assertEqual("1.0", card["supportedInterfaces"][0]["protocolVersion"])

    def test_invalid_delay_or_fault_configuration_fails_at_startup(self):
        for delay in (-1, 60_001, True, 0.5):
            with self.subTest(delay=delay), self.assertRaises(ValueError):
                self.client(conversation_delay_ms=delay)
        with patch.dict("os.environ", {"REFUND_CONVERSATION_WRONG_DAY30": "sometimes"}):
            with self.assertRaises(ValueError):
                self.client()


class ConversationDelayTest(unittest.IsolatedAsyncioTestCase):
    async def test_reply_is_durable_before_delayed_delivery_is_cancelled(self):
        with tempfile.TemporaryDirectory() as folder:
            store = ConversationStore(Path(folder) / "state.sqlite3")
            facts = initial()
            text = json.dumps(facts)
            incoming = new_text_message(text, context_id="episode-one")
            incoming.message_id = "first"
            context = SimpleNamespace(message=incoming, context_id="episode-one")
            queue = SimpleNamespace(enqueue_event=AsyncMock())
            reached_delay = asyncio.Event()

            async def wait_instead_of_sleep(_seconds):
                reached_delay.set()
                await asyncio.Event().wait()

            with patch("app.asyncio.sleep", side_effect=wait_instead_of_sleep):
                pending = asyncio.create_task(RefundAgent(
                    "conversation-reference", conversation_store=store, delay_ms=100,
                ).execute(context, queue))
                await asyncio.wait_for(reached_delay.wait(), timeout=5)
                pending.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await pending
            queue.enqueue_event.assert_not_awaited()
            replay = ConversationStore(store.database).reply("episode-one", "first", text, facts)
            await RefundAgent("conversation-reference", conversation_store=store).execute(context, queue)
            sent = queue.enqueue_event.call_args.args[0]
            self.assertEqual(replay.message_id, sent.message_id)
            self.assertEqual(replay.text, sent.parts[0].text)


if __name__ == "__main__":
    unittest.main()
