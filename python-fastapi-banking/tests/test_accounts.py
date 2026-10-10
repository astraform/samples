"""PostgreSQL checks for partner-owned banking data; no model behavior is stubbed."""

from __future__ import annotations

import calendar
import copy
import json
import os
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from banking_remote_domain_python.accounts import BankingAccountError, BankingAccountRepository, BankingTransition
from banking_remote_domain_python.service import close_credit_card_statement


START = int(datetime(2026, 1, 15, tzinfo=UTC).timestamp() * 1000)
FEBRUARY = int(datetime(2026, 2, 1, tzinfo=UTC).timestamp() * 1000)


@pytest.fixture
def bank():
    dsn = os.getenv("BANKING_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("Set BANKING_TEST_DATABASE_URL to an isolated PostgreSQL test database")
    repository = BankingAccountRepository(dsn, 4)
    context = "persistence-" + uuid.uuid4().hex
    yield repository, context, dsn
    repository.close()
    with psycopg.connect(dsn) as connection:
        connection.execute("DELETE FROM banking_native_operation WHERE context_id IN (SELECT context_id FROM banking_native_context WHERE context_ref=%s)", (context,))
        for table in ("banking_native_context", "banking_statement", "banking_account", "banking_customer"):
            connection.execute(f"DELETE FROM {table} WHERE context_ref=%s", (context,))


def seed(account="a-account"):
    return {"logicalTimeEpochMs": START, "accounts": [{"accountId": account, "name": "Everyday credit", "productCode": "CC001",
            "balance": "-1200", "currency": "AUD", "limit": "3000"}], "statements": []}


def metadata(resource, context, logical_time=START):
    return {"contextId": "native-" + context, "resourceVersion": resource.version + 1, "processedThroughEpochMs": logical_time}


def guard(record):
    return {"contextId": record["contextId"], "resourceVersion": record["resourceVersion"], "logicalTimeEpochMs": record["processedThroughEpochMs"]}


def future_deadline():
    return time.time_ns() // 1_000_000 + 60_000


def test_seed_ownership_and_latest_twelve_reads_survive_restart(bank):
    repository, context, dsn = bank
    original = repository.seed(context, "a", seed())
    repository.seed(context, "b", seed("b-account"))
    with pytest.raises(BankingAccountError, match="identity is already populated"):
        repository.seed(context, "c", seed())
    changed = seed()
    changed["accounts"][0]["balance"] = "-500"
    with pytest.raises(BankingAccountError, match="different banking data"):
        repository.seed(context, "a", changed)
    repository.close()
    restarted = BankingAccountRepository(dsn)
    try:
        assert restarted.seed(context, "a", seed()) == original
        with psycopg.connect(dsn) as connection:
            for index in range(30):
                year, month = 2021 + index // 12, index % 12 + 1
                end = datetime(year, month, calendar.monthrange(year, month)[1], tzinfo=UTC).date()
                statement = {"billId": f"bill-{index:02}", "accountId": "a-account", "currency": "AUD", "closingAmount": "-1200", "minimumAmountDue": "48",
                             "statementStartDate": end.replace(day=1).isoformat(), "statementEndDate": end.isoformat(), "dueDate": (end + timedelta(days=14)).isoformat()}
                connection.execute("INSERT INTO banking_statement(context_ref,subject_ref,bill_id,account_id,statement_data,statement_start_date,statement_end_date) VALUES(%s,%s,%s,%s,%s,%s,%s)",
                                   (context, "a", statement["billId"], "a-account", json.dumps(statement), statement["statementStartDate"], statement["statementEndDate"]))
        assert [item["billId"] for item in restarted.read(context, "a")["statements"]] == [f"bill-{index:02}" for index in range(18, 30)]
        assert [item["billId"] for item in restarted.read(context, "a", statement_period="2021-01")["statements"]] == ["bill-00"]
        assert restarted.read(context, "a", statement_period="9999-12")["statements"] == []
        assert restarted.read(context, "b")["statements"] == []
        with pytest.raises(ValueError, match="YYYY-MM"):
            restarted.read(context, "a", statement_period="2026-1")
        with pytest.raises(BankingAccountError, match="not found"):
            restarted.read(context, "c")
    finally:
        restarted.close()


def test_statement_and_receipt_commit_once_with_guarded_restart_replay(bank):
    repository, context, dsn = bank
    repository.seed(context, "a", seed())
    operation_key = "statement-" + context
    request = {"operation": "statement", "through": FEBRUARY}
    callback_count = []

    def close_statement(resource):
        callback_count.append(True)
        events = close_credit_card_statement(resource.state,
            {"workId": "january-statement", "occurrenceKey": "2026-01", "scheduledForEpochMs": FEBRUARY},
            {"statementStartDate": "2026-01-01", "statementEndDate": "2026-01-31"})
        resource.state["lastEvidenceEvents"] = events
        current = metadata(resource, context, FEBRUARY)
        return BankingTransition({"nativeSimulation": current}, resource.state, current, {"workKinds": ["credit_card_statement_close"]})

    with ThreadPoolExecutor(max_workers=4) as executor:
        responses = list(executor.map(lambda _: repository.transact(context, "a", operation_key, request, future_deadline(), close_statement), range(8)))
    assert len(callback_count) == 1
    current_guard = guard(responses[0]["nativeSimulation"])
    current = repository.read(context, "a", current_guard)
    assert len(current["statements"]) == 1
    assert {key: current["statements"][0][key] for key in ("minimumAmountDue", "dueDate", "statementEndDate")} == {
        "minimumAmountDue": "48", "dueDate": "2026-02-14", "statementEndDate": "2026-01-31"}
    assert all(response == responses[0] for response in responses)
    with pytest.raises(ValueError, match="Invalid banking fields"):
        repository.read(context, "a")
    stale_guard = {**current_guard, "resourceVersion": 1}
    with pytest.raises(BankingAccountError, match="does not match"):
        repository.read(context, "a", stale_guard)
    repository.close()
    restarted = BankingAccountRepository(dsn)
    try:
        assert restarted.transact(context, "a", operation_key, request, 1, close_statement) == responses[0]
        assert restarted.read(context, "a", current_guard) == current
        with pytest.raises(BankingAccountError, match="different request"):
            restarted.transact(context, "a", operation_key, {**request, "through": FEBRUARY + 1}, future_deadline(), close_statement)
        with pytest.raises(BankingAccountError, match="deadline"):
            restarted.transact(context, "a", "expired-" + context, request, 1, close_statement)
        assert len(callback_count) == 1
    finally:
        restarted.close()


def test_failed_statement_rolls_back_account_context_and_receipt(bank):
    repository, context, _ = bank
    original = repository.seed(context, "a", seed())
    repository.seed(context, "b", seed("b-account"))
    operation_key = "rollback-" + context

    def invalid_statement(resource):
        resource.state["accounts"][0]["balance"] = "-10"
        resource.state["lastEvidenceEvents"] = [{"eventType": "remote_domain_credit_card_statement_closed", "billId": "invalid",
            "accountId": "b-account", "currency": "AUD", "closingAmount": "-10", "minimumAmountDue": "1",
            "statementStartDate": "2026-01-01", "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}]
        return BankingTransition({"completed": True}, resource.state, metadata(resource, context, FEBRUARY), {})

    with pytest.raises(BankingAccountError, match="identity is already populated"):
        repository.transact(context, "a", operation_key, {"attempt": 1}, future_deadline(), invalid_statement)
    assert repository.read(context, "a") == original

    def valid(resource):
        return BankingTransition({"completed": True}, resource.state, metadata(resource, context), {})

    # The failed attempt did not reserve the operation key or native context.
    assert repository.transact(context, "a", operation_key, {"attempt": 2}, future_deadline(), valid) == {"completed": True}
    with pytest.raises(BankingAccountError, match="different request or customer"):
        repository.transact(context, "b", operation_key, {"attempt": 2}, future_deadline(), valid)


@pytest.mark.parametrize("mutate", [
    lambda value: value.update(logicalTimeEpochMs=True),
    lambda value: value["accounts"][0].update(balance="NaN"),
    lambda value: value.update(statements=[{"billId": "future", "accountId": "a-account", "currency": "AUD", "closingAmount": "-1", "minimumAmountDue": "0",
        "statementStartDate": "2026-01-01", "statementEndDate": "2026-01-31", "dueDate": "2026-02-14"}]),
])
def test_seed_rejects_invalid_or_future_financial_data_before_persistence(bank, mutate):
    repository, context, _ = bank
    request = copy.deepcopy(seed())
    mutate(request)
    with pytest.raises(ValueError):
        repository.seed(context, "a", request)
    with pytest.raises(BankingAccountError, match="not found"):
        repository.read(context, "a")
