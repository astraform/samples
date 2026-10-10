"""Durable, read-only refund conversations; no models or payment systems are called."""

from dataclasses import dataclass
from contextlib import contextmanager
from datetime import date
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
from uuid import uuid4

from refund_policy import assess


INPUT_SCHEMA = "astraform.refund-conversation-input.v1"
OUTPUT_SCHEMA = "astraform.refund-conversation-output.v1"
MAX_DELAY_MS = 60_000


@dataclass(frozen=True)
class ConversationReply:
    message_id: str
    text: str


def _identity(value: str, name: str) -> None:
    if not isinstance(value, str) or not value.strip() or len(value) > 512:
        raise ValueError(f"{name} must be nonempty text of at most 512 characters")


def _validate(facts: dict) -> None:
    if not isinstance(facts, dict) or facts.get("schemaVersion") != INPUT_SCHEMA:
        raise ValueError(f"Conversation facts must use schemaVersion {INPUT_SCHEMA}")
    if facts.get("phase") not in ("request", "provide_information"):
        raise ValueError("phase must be request or provide_information")
    message = facts.get("customerMessage")
    if not isinstance(message, str) or not message.strip() or len(message) > 16_384:
        raise ValueError("customerMessage must be nonempty text of at most 16384 characters")
    allowed = {"schemaVersion", "phase", "customerMessage", "requestDate", "itemCondition"}
    if facts["phase"] == "request":
        if "purchaseDate" in facts:
            raise ValueError("The initial request must not contain purchaseDate")
        if not isinstance(facts.get("requestDate"), str) or facts.get("itemCondition") != "unopened":
            raise ValueError("The initial request requires requestDate and itemCondition unopened")
        assess({"requestDate": facts["requestDate"], "itemCondition": facts["itemCondition"]})
    else:
        allowed.add("purchaseDate")
        if not isinstance(facts.get("purchaseDate"), str):
            raise ValueError("provide_information requires purchaseDate")
    if set(facts) - allowed:
        raise ValueError("Conversation facts contain unsupported fields")


class ConversationStore:
    """Commit context and response together, serializing concurrent message handling.

    Each operation opens its own connection so the store can be used from worker
    threads. Replay identity is (contextId, messageId), with the exact input text
    fingerprinted. The recorded reply is returned even after a process restart
    or a deployment fault-setting change. This is local fixture idempotency, not
    a claim about exactly-once execution by arbitrary remote agents.
    """

    def __init__(self, database: str | Path, *, wrong_day30: bool = False):
        if type(wrong_day30) is not bool:
            raise ValueError("wrong_day30 must be a boolean")
        self.database = Path(database)
        if str(database) == ":memory:":
            raise ValueError("Conversation state requires a persistent SQLite file")
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self.wrong_day30 = wrong_day30
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS conversations (
                    context_id TEXT PRIMARY KEY,
                    request_date TEXT NOT NULL,
                    item_condition TEXT NOT NULL,
                    purchase_date TEXT
                );
                CREATE TABLE IF NOT EXISTS messages (
                    context_id TEXT NOT NULL,
                    message_id TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    reply_id TEXT NOT NULL,
                    reply_text TEXT NOT NULL,
                    PRIMARY KEY (context_id, message_id),
                    FOREIGN KEY (context_id) REFERENCES conversations(context_id)
                );
            """)

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.database, timeout=10)
        try:
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA synchronous=FULL")
            with connection:
                yield connection
        finally:
            connection.close()

    def reply(self, context_id: str, message_id: str, input_text: str, facts: dict) -> ConversationReply:
        _identity(context_id, "contextId")
        _identity(message_id, "messageId")
        if not isinstance(input_text, str):
            raise ValueError("Conversation input must be text")
        _validate(facts)
        fingerprint = sha256(input_text.encode("utf-8")).hexdigest()
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            previous = connection.execute(
                "SELECT input_digest, reply_id, reply_text FROM messages WHERE context_id=? AND message_id=?",
                (context_id, message_id),
            ).fetchone()
            if previous is not None:
                if previous[0] != fingerprint:
                    raise ValueError("messageId was already used with different content in this context")
                return ConversationReply(previous[1], previous[2])

            state = connection.execute(
                "SELECT request_date, item_condition, purchase_date FROM conversations WHERE context_id=?",
                (context_id,),
            ).fetchone()
            if facts["phase"] == "request":
                if state is not None:
                    raise ValueError("This context already has a request; continue it or use a new contextId")
                connection.execute(
                    "INSERT INTO conversations(context_id, request_date, item_condition) VALUES (?, ?, ?)",
                    (context_id, facts["requestDate"], facts["itemCondition"]),
                )
                answer = assess({"requestDate": facts["requestDate"], "itemCondition": facts["itemCondition"]})
                message = "Please provide your purchase date."
            else:
                if state is None:
                    raise ValueError("No initial request exists for this contextId")
                for field, original in (("requestDate", state[0]), ("itemCondition", state[1])):
                    if field in facts and facts[field] != original:
                        raise ValueError(f"{field} conflicts with the original request")
                if state[2] is not None and facts["purchaseDate"] != state[2]:
                    raise ValueError("purchaseDate conflicts with the information already supplied")
                answer = assess({"requestDate": state[0], "itemCondition": state[1],
                                 "purchaseDate": facts["purchaseDate"]})
                if self.wrong_day30 and answer["decision"] == "eligible":
                    if (date.fromisoformat(state[0]) - date.fromisoformat(facts["purchaseDate"])).days == 30:
                        answer.update(decision="ineligible", reasonCode="OUTSIDE_RETURN_WINDOW")
                message = ("Your return request is eligible under the original request date."
                           if answer["decision"] == "eligible" else
                           "Your return request is outside the return window.")
                connection.execute("UPDATE conversations SET purchase_date=? WHERE context_id=?",
                                   (facts["purchaseDate"], context_id))

            reply = ConversationReply(str(uuid4()), json.dumps({
                "schemaVersion": OUTPUT_SCHEMA, **answer, "message": message,
            }, separators=(",", ":")))
            connection.execute(
                "INSERT INTO messages(context_id, message_id, input_digest, reply_id, reply_text) VALUES (?, ?, ?, ?, ?)",
                (context_id, message_id, fingerprint, reply.message_id, reply.text),
            )
            return reply
