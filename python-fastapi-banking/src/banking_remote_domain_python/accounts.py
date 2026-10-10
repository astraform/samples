"""Partner-owned banking records and atomic native-operation receipts.

Only references to these records cross the Astraform boundary. Native callbacks
run bounded local banking calculations while holding one customer's row lock.
"""

from __future__ import annotations

import calendar
import hashlib
import json
import re
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any, Callable, Iterator

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool, PoolTimeout, TooManyRequests


class BankingAccountError(RuntimeError):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


@dataclass(frozen=True)
class BankingResource:
    context_ref: str
    subject_ref: str
    version: int
    logical_time: int
    state: dict[str, Any]
    metadata: dict[str, Any]
    configuration: dict[str, Any]


@dataclass(frozen=True)
class BankingTransition:
    response: dict[str, Any]
    state: dict[str, Any]
    metadata: dict[str, Any]
    configuration: dict[str, Any]


_ACCOUNT_FIELDS = {"accountId", "name", "productCode", "balance", "currency", "limit", "currentMinPayment"}
_STATEMENT_FIELDS = {"billId", "accountId", "closingAmount", "minimumAmountDue", "currency", "statementStartDate", "statementEndDate", "dueDate"}
_AMOUNT_FIELDS = {"balance", "limit", "currentMinPayment", "closingAmount", "minimumAmountDue"}
_MAX_TIME = 253402300799998


def reference(value: Any) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value) is None:
        raise ValueError("Banking references must be 1-128 letters, digits or _.:-")
    return value


def exact_integer(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not -(2**63) <= value < 2**63:
        raise ValueError("An exact 64-bit integer is required")
    return value


def _fields(value: Any, allowed: set[str], required: set[str]) -> None:
    if not isinstance(value, dict) or not required <= value.keys() <= allowed:
        raise ValueError("Invalid banking fields")


def _records(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ValueError("Expected an array of banking records")
    return value


def _amount(value: Any, nonnegative: bool = False) -> str:
    if not isinstance(value, str) or len(value) > 40 or re.fullmatch(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?", value) is None:
        raise ValueError("Amounts must be bounded decimal strings")
    number = Decimal(value)
    if len(number.as_tuple().digits) > 28 or number.as_tuple().exponent < -10 or (nonnegative and number < 0):
        raise ValueError("Amount outside supported precision or range")
    return _decimal_string(number)


def _decimal_string(value: Any) -> str:
    number = Decimal(str(value))
    if not number.is_finite():
        raise ValueError("Banking amounts must be finite")
    if number == 0:
        return "0"
    text = format(number, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def _currency(value: Any) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[A-Z]{3}", value) is None:
        raise ValueError("Invalid currency")
    return value


def _date(value: Any) -> date:
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value) is None:
        raise ValueError("Statement dates must be YYYY-MM-DD")
    return date.fromisoformat(value)


def _validate_seed(request: dict[str, Any]) -> dict[str, Any]:
    fields = {"logicalTimeEpochMs", "accounts", "statements"}
    _fields(request, fields, fields)
    logical_time = exact_integer(request["logicalTimeEpochMs"])
    if not 0 <= logical_time <= _MAX_TIME:
        raise ValueError("logicalTimeEpochMs is outside the supported simulation range")
    accounts, statements = _records(request["accounts"]), _records(request["statements"])
    if not 1 <= len(accounts) <= 100 or len(statements) > 100:
        raise ValueError("A customer requires 1-100 accounts and at most 100 initial statements")
    currencies: dict[str, str] = {}
    normalized_accounts, normalized_statements = [], []
    for account in accounts:
        _fields(account, _ACCOUNT_FIELDS, {"accountId", "name", "productCode", "balance", "currency"})
        account_id = reference(account["accountId"])
        if account_id in currencies:
            raise ValueError("Duplicate accountId")
        currencies[account_id] = _currency(account["currency"])
        for field in ("name", "productCode"):
            if not isinstance(account[field], str) or not account[field].strip() or len(account[field]) > 256:
                raise ValueError(f"Invalid account {field}")
        normalized = dict(account)
        normalized["balance"] = _amount(account["balance"])
        for field in ("limit", "currentMinPayment"):
            if field in account:
                normalized[field] = _amount(account[field], True)
        normalized_accounts.append(normalized)
    bill_ids, periods = set(), set()
    seeded_date = datetime.fromtimestamp(logical_time / 1000, tz=UTC).date()
    for statement in statements:
        _fields(statement, _STATEMENT_FIELDS, _STATEMENT_FIELDS)
        bill_id = reference(statement["billId"])
        if bill_id in bill_ids:
            raise ValueError("Duplicate billId")
        bill_ids.add(bill_id)
        account_id = reference(statement["accountId"])
        if _currency(statement["currency"]) != currencies.get(account_id):
            raise ValueError("Statement must belong to this customer's account and currency")
        start, end, due = (_date(statement[field]) for field in ("statementStartDate", "statementEndDate", "dueDate"))
        if not start <= end <= due or end > seeded_date:
            raise ValueError("Statement dates must be ordered and already closed at the seeded logical time")
        period = (account_id, start, end)
        if period in periods:
            raise ValueError("Duplicate account statement period")
        periods.add(period)
        normalized = dict(statement)
        normalized["closingAmount"] = _amount(statement["closingAmount"])
        normalized["minimumAmountDue"] = _amount(statement["minimumAmountDue"], True)
        normalized_statements.append(normalized)
    return {"logicalTimeEpochMs": logical_time, "accounts": normalized_accounts, "statements": normalized_statements}


def _json(value: Any) -> str:
    from banking_remote_domain_python.service import encode_state

    return json.dumps(encode_state(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _statement_view(statement: dict[str, Any]) -> dict[str, Any]:
    return {key: _decimal_string(statement[key]) if key in _AMOUNT_FIELDS else statement[key] for key in sorted(_STATEMENT_FIELDS)}


class BankingAccountRepository:
    def __init__(self, dsn: str, max_pool_size: int = 8):
        if not isinstance(max_pool_size, int) or isinstance(max_pool_size, bool) or not 1 <= max_pool_size <= 64:
            raise ValueError("Banking maximum pool size must be between 1 and 64")
        self.pool = ConnectionPool(
            conninfo=dsn,
            min_size=1,
            max_size=max_pool_size,
            max_waiting=64,
            timeout=10,
            kwargs={"row_factory": dict_row, "connect_timeout": 5},
            open=True,
            name="python-banking-account-db",
        )
        try:
            self.pool.wait(timeout=10)
            with self._connection() as connection:
                self._initialize(connection)
        except (psycopg.OperationalError, PoolTimeout) as error:
            self.pool.close()
            raise BankingAccountError(503, "Banking database is unavailable") from error
        except Exception:
            self.pool.close()
            raise

    @contextmanager
    def _connection(self) -> Iterator[psycopg.Connection]:
        try:
            with self.pool.connection() as connection:
                yield connection
        except psycopg.IntegrityError as error:
            raise BankingAccountError(409, "Banking customer, account, statement or operation identity is already populated") from error
        except (psycopg.OperationalError, PoolTimeout, TooManyRequests) as error:
            raise BankingAccountError(503, "Banking database is unavailable") from error

    @staticmethod
    def _initialize(connection: psycopg.Connection) -> None:
        connection.execute("""
            CREATE TABLE IF NOT EXISTS banking_customer (
                context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                version BIGINT NOT NULL, logical_time_epoch_ms BIGINT NOT NULL,
                seed_request TEXT NOT NULL, PRIMARY KEY (context_ref,subject_ref));
            CREATE TABLE IF NOT EXISTS banking_account (
                context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                account_id VARCHAR(128) NOT NULL, account_data TEXT NOT NULL,
                PRIMARY KEY (context_ref,account_id), UNIQUE(context_ref,subject_ref,account_id),
                FOREIGN KEY (context_ref,subject_ref) REFERENCES banking_customer(context_ref,subject_ref));
            CREATE TABLE IF NOT EXISTS banking_statement (
                context_ref VARCHAR(128) NOT NULL, subject_ref VARCHAR(128) NOT NULL,
                bill_id VARCHAR(128) NOT NULL, account_id VARCHAR(128) NOT NULL,
                statement_data TEXT NOT NULL, statement_start_date VARCHAR(10) NOT NULL,
                statement_end_date VARCHAR(10) NOT NULL, PRIMARY KEY(context_ref,bill_id),
                FOREIGN KEY (context_ref,subject_ref,account_id) REFERENCES banking_account(context_ref,subject_ref,account_id));
            CREATE TABLE IF NOT EXISTS banking_native_context (
                context_id VARCHAR(256) PRIMARY KEY, context_ref VARCHAR(128) NOT NULL,
                subject_ref VARCHAR(128) NOT NULL, metadata_json TEXT NOT NULL,
                configuration_json TEXT NOT NULL, UNIQUE(context_ref,subject_ref),
                FOREIGN KEY (context_ref,subject_ref) REFERENCES banking_customer(context_ref,subject_ref));
            CREATE TABLE IF NOT EXISTS banking_native_operation (
                operation_key VARCHAR(512) PRIMARY KEY, request_digest VARCHAR(71) NOT NULL,
                response_json TEXT NOT NULL, context_id VARCHAR(256) NOT NULL,
                FOREIGN KEY (context_id) REFERENCES banking_native_context(context_id));
            CREATE UNIQUE INDEX IF NOT EXISTS banking_statement_period_idx
                ON banking_statement(context_ref,account_id,statement_start_date,statement_end_date);
            CREATE INDEX IF NOT EXISTS banking_account_customer_idx
                ON banking_account(context_ref,subject_ref);
            CREATE INDEX IF NOT EXISTS banking_statement_customer_period_idx
                ON banking_statement(context_ref,subject_ref,statement_end_date DESC,bill_id DESC);
        """)

    def seed(self, context_ref: str, subject_ref: str, request: dict[str, Any]) -> dict[str, Any]:
        reference(context_ref)
        reference(subject_ref)
        normalized = _validate_seed(request)
        with self._connection() as connection:
            # A concurrent identical seed waits for the original transaction, then reads its result.
            inserted = connection.execute(
                "INSERT INTO banking_customer(context_ref,subject_ref,version,logical_time_epoch_ms,seed_request) "
                "VALUES(%s,%s,1,%s,%s) ON CONFLICT(context_ref,subject_ref) DO NOTHING RETURNING version",
                (context_ref, subject_ref, normalized["logicalTimeEpochMs"], _json(normalized)),
            ).fetchone()
            if not inserted:
                existing = connection.execute(
                    "SELECT seed_request FROM banking_customer WHERE context_ref=%s AND subject_ref=%s FOR UPDATE",
                    (context_ref, subject_ref),
                ).fetchone()
                if json.loads(existing["seed_request"]) != normalized:
                    raise BankingAccountError(409, "Customer is already populated with different banking data")
            else:
                for account in normalized["accounts"]:
                    connection.execute(
                        "INSERT INTO banking_account(context_ref,subject_ref,account_id,account_data) VALUES(%s,%s,%s,%s)",
                        (context_ref, subject_ref, account["accountId"], _json(account)),
                    )
                for statement in normalized["statements"]:
                    self._insert_statement(connection, context_ref, subject_ref, statement)
            return self._view(self._locked_resource(connection, context_ref, subject_ref, statement_limit=12))

    def read(self, context_ref: str, subject_ref: str, guard: dict[str, Any] | None = None,
             statement_period: str | None = None) -> dict[str, Any]:
        reference(context_ref)
        reference(subject_ref)
        if statement_period is not None and (
            not isinstance(statement_period, str)
            or re.fullmatch(r"(?!0000)[0-9]{4}-(0[1-9]|1[0-2])", statement_period) is None
        ):
            raise ValueError("statementPeriod must be YYYY-MM with a year from 0001 to 9999")
        with self._connection() as connection:
            resource = self._locked_resource(connection, context_ref, subject_ref, statement_period, 12)
            if resource.metadata or guard is not None:
                fields = {"contextId", "resourceVersion", "logicalTimeEpochMs"}
                _fields(guard, fields, fields)
                if (guard["contextId"] != resource.metadata.get("contextId")
                        or exact_integer(guard["resourceVersion"]) != resource.version
                        or exact_integer(guard["logicalTimeEpochMs"]) != resource.logical_time):
                    raise BankingAccountError(409, "Banking read context/version/logical time does not match the authoritative resource")
            if statement_period is not None and len(resource.state["creditCardBills"]) > 100:
                raise BankingAccountError(422, "Requested statementPeriod has more than 100 statements; no partial result was returned")
            result = self._view(resource)
            if statement_period is not None:
                result["statementPeriod"] = statement_period
            return result

    def transact(self, context_ref: str, subject_ref: str, operation_key: str, request: Any,
                 deadline: int, callback: Callable[[BankingResource], BankingTransition]) -> dict[str, Any]:
        reference(context_ref)
        reference(subject_ref)
        if not isinstance(operation_key, str) or not operation_key.strip() or len(operation_key) > 512:
            raise ValueError("A bounded idempotency key is required")
        digest = "sha256:" + hashlib.sha256(_json(request).encode("utf-8")).hexdigest()
        with self._connection() as connection:
            resource = self._locked_resource(connection, context_ref, subject_ref)
            receipt = connection.execute(
                "SELECT o.request_digest,o.response_json,c.context_ref,c.subject_ref FROM banking_native_operation o "
                "JOIN banking_native_context c ON c.context_id=o.context_id WHERE o.operation_key=%s",
                (operation_key,),
            ).fetchone()
            if receipt:
                if (receipt["request_digest"] != digest or receipt["context_ref"] != context_ref
                        or receipt["subject_ref"] != subject_ref):
                    raise BankingAccountError(409, "Idempotency key was already used with a different request or customer")
                return json.loads(receipt["response_json"])
            if exact_integer(deadline) <= time.time_ns() // 1_000_000:
                raise BankingAccountError(409, "New banking work is past its accepted deadline")
            expected_accounts = {account["accountId"] for account in resource.state["accounts"]}
            original_configuration = _json(resource.configuration)
            transition = callback(resource)
            metadata = transition.metadata
            context_id = metadata.get("contextId")
            if not isinstance(context_id, str) or not context_id.strip() or len(context_id) > 256:
                raise ValueError("A bounded native contextId is required")
            version = exact_integer(metadata.get("resourceVersion"))
            processed_through = exact_integer(metadata.get("processedThroughEpochMs"))
            if version < resource.version or not resource.logical_time <= processed_through <= _MAX_TIME:
                raise BankingAccountError(409, "Banking resource version and logical time cannot move backwards")
            if not resource.metadata:
                connection.execute(
                    "INSERT INTO banking_native_context(context_id,context_ref,subject_ref,metadata_json,configuration_json) VALUES(%s,%s,%s,%s,%s)",
                    (context_id, context_ref, subject_ref, _json(metadata), _json(transition.configuration)),
                )
            else:
                if context_id != resource.metadata["contextId"]:
                    raise BankingAccountError(409, "Banking customer already has a simulation authority")
                if _json(transition.configuration) != original_configuration:
                    raise BankingAccountError(409, "Banking simulation configuration is immutable")
                connection.execute("UPDATE banking_native_context SET metadata_json=%s WHERE context_id=%s", (_json(metadata), context_id))
            accounts = _records(transition.state.get("accounts"))
            if len(accounts) != len(expected_accounts) or {account.get("accountId") for account in accounts} != expected_accounts:
                raise BankingAccountError(409, "Native work cannot reassign customer accounts")
            for account in accounts:
                connection.execute(
                    "UPDATE banking_account SET account_data=%s WHERE context_ref=%s AND subject_ref=%s AND account_id=%s",
                    (_json(account), context_ref, subject_ref, account["accountId"]),
                )
            for event in transition.state.get("lastEvidenceEvents", []):
                if event.get("eventType") == "remote_domain_credit_card_statement_closed" and Decimal(str(event["closingAmount"])) < Decimal("-0.01"):
                    self._insert_statement(connection, context_ref, subject_ref, _statement_view(event))
            connection.execute(
                "UPDATE banking_customer SET version=%s,logical_time_epoch_ms=%s WHERE context_ref=%s AND subject_ref=%s",
                (version, processed_through, context_ref, subject_ref),
            )
            connection.execute(
                "INSERT INTO banking_native_operation(operation_key,request_digest,response_json,context_id) VALUES(%s,%s,%s,%s)",
                (operation_key, digest, _json(transition.response), context_id),
            )
            return transition.response

    @staticmethod
    def _insert_statement(connection: psycopg.Connection, context_ref: str, subject_ref: str, statement: dict[str, Any]) -> None:
        connection.execute(
            "INSERT INTO banking_statement(context_ref,subject_ref,bill_id,account_id,statement_data,statement_start_date,statement_end_date) "
            "VALUES(%s,%s,%s,%s,%s,%s,%s)",
            (context_ref, subject_ref, statement["billId"], statement["accountId"], _json(statement), statement["statementStartDate"], statement["statementEndDate"]),
        )

    @staticmethod
    def _locked_resource(connection: psycopg.Connection, context_ref: str, subject_ref: str,
                         statement_period: str | None = None, statement_limit: int = 100) -> BankingResource:
        from banking_remote_domain_python.service import decode_state

        customer = connection.execute(
            "SELECT version,logical_time_epoch_ms FROM banking_customer WHERE context_ref=%s AND subject_ref=%s FOR UPDATE",
            (context_ref, subject_ref),
        ).fetchone()
        if not customer:
            raise BankingAccountError(404, "Banking customer context not found")
        accounts = [json.loads(row["account_data"]) for row in connection.execute(
            "SELECT account_data FROM banking_account WHERE context_ref=%s AND subject_ref=%s ORDER BY account_id", (context_ref, subject_ref)
        )]
        for account in accounts:
            identity = f"{context_ref}:{subject_ref}:{account['accountId']}".encode("utf-8")
            # Decision evidence uses interoperable exact JSON integers.
            account["id"] = int.from_bytes(hashlib.sha256(identity).digest()[:8], "big") >> 11
        query = "SELECT statement_data FROM banking_statement WHERE context_ref=%s AND subject_ref=%s"
        parameters: list[Any] = [context_ref, subject_ref]
        if statement_period is not None:
            year, month = (int(part) for part in statement_period.split("-"))
            query += " AND statement_end_date >= %s AND statement_end_date <= %s"
            parameters.extend([f"{statement_period}-01", f"{statement_period}-{calendar.monthrange(year, month)[1]:02}"])
            statement_limit = 101
        query += " ORDER BY statement_end_date DESC,bill_id DESC LIMIT %s"
        parameters.append(statement_limit)
        statements = [json.loads(row["statement_data"]) for row in connection.execute(query, parameters)]
        statements.reverse()
        native = connection.execute(
            "SELECT metadata_json,configuration_json FROM banking_native_context WHERE context_ref=%s AND subject_ref=%s", (context_ref, subject_ref)
        ).fetchone()
        state = decode_state({"accounts": accounts, "creditCardBills": statements})
        for stored, decoded in zip(accounts, state["accounts"]):
            if "limit" not in stored:
                decoded.pop("limit", None)
        return BankingResource(
            context_ref, subject_ref, customer["version"], customer["logical_time_epoch_ms"],
            state,
            json.loads(native["metadata_json"]) if native else {},
            json.loads(native["configuration_json"]) if native else {},
        )

    @staticmethod
    def _view(resource: BankingResource) -> dict[str, Any]:
        return {
            "contextRef": resource.context_ref,
            "subjectRef": resource.subject_ref,
            "version": resource.version,
            "logicalTimeEpochMs": resource.logical_time,
            "accounts": [{key: _decimal_string(value) if key in _AMOUNT_FIELDS else value
                          for key, value in account.items() if key in _ACCOUNT_FIELDS}
                         for account in resource.state["accounts"]],
            "statements": [_statement_view(statement) for statement in resource.state["creditCardBills"]],
        }

    def close(self) -> None:
        self.pool.close()
