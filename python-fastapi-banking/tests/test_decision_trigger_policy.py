from __future__ import annotations

import json
from pathlib import Path

import pytest

from banking_remote_domain_python.decision_trigger_policy import NUMERIC_ENCODING
from banking_remote_domain_python.decision_trigger_policy import POLICY_VERSION
from banking_remote_domain_python.decision_trigger_policy import decision_triggers_from_evidence
from banking_remote_domain_python.decision_trigger_policy import normalized_configuration


SCHEDULED_AT = 1_777_000_000_000


def test_maps_the_complete_banking_v1_event_set() -> None:
    fixture = json.loads(fixture_path().read_text(encoding="utf-8"))
    events = fixture["events"]
    expected = fixture["expected"]

    triggers = decision_triggers_from_evidence(events, SCHEDULED_AT)

    assert [trigger["triggerType"] for trigger in triggers] == [item["triggerType"] for item in expected]
    assert [trigger["priority"] for trigger in triggers] == [item["priority"] for item in expected]
    assert [trigger["reason"] for trigger in triggers] == [item["reason"] for item in expected]
    assert [trigger["payload"] for trigger in triggers] == [item["payload"] for item in expected]
    assert all(trigger["schemaVersion"] == "customer_decision_trigger.v1" for trigger in triggers)
    assert all(trigger["scheduledForEpochMs"] == SCHEDULED_AT for trigger in triggers)
    assert all(trigger["triggerRef"].endswith("|account:acct-1") for trigger in triggers)
    assert all(trigger["payload"]["policyVersion"] == POLICY_VERSION for trigger in triggers)
    assert all(trigger["payload"]["numericEncoding"] == NUMERIC_ENCODING for trigger in triggers)
    assert all("prompt" not in trigger["payload"] for trigger in triggers)
    assert triggers[2]["priority"] == "HIGH"
    assert triggers[7]["priority"] == "HIGH"


def test_defaults_suppress_routine_noise_and_optional_review() -> None:
    events = [
        event("remote_domain_interest_accrued", "saver-interest", "interestAmount", 3),
        event("remote_domain_credit_card_interest_charged", "card-interest", "interestAmount", 24),
        event("remote_domain_fee_charged", "small-fee", "feeAmount", 5),
        event("remote_domain_scheduled_review_due", "monthly-review", "currentBalance", 1000),
        event("remote_domain_hardship_reviewed", "hardship-declined", "outcome", "DECLINED"),
    ]

    assert decision_triggers_from_evidence(events, SCHEDULED_AT) == []


def test_domain_configuration_can_enable_review_and_change_materiality() -> None:
    configuration = {
        "interest_charged": {"minimumAmount": 20, "priority": "CRITICAL"},
        "monthly_review": {"enabled": True, "priority": "NORMAL"},
    }

    triggers = decision_triggers_from_evidence(
        [
            event("remote_domain_credit_card_interest_charged", "interest-1", "interestAmount", 24),
            event("remote_domain_scheduled_review_due", "review-1", "currentBalance", 1000),
        ],
        SCHEDULED_AT,
        configuration,
    )

    assert [trigger["triggerType"] for trigger in triggers] == ["interest_charged", "monthly_review"]
    assert [trigger["priority"] for trigger in triggers] == ["CRITICAL", "NORMAL"]


def test_account_identity_prevents_same_occurrence_from_colliding() -> None:
    first = event("remote_domain_credit_card_statement_closed", "statement-window", "minimumAmountDue", 45)
    second = {**first, "accountId": "acct-2"}

    triggers = decision_triggers_from_evidence([first, second], SCHEDULED_AT)

    assert [trigger["triggerId"] for trigger in triggers] == [
        "statement_close:statement-window|account:acct-1",
        "statement_close:statement-window|account:acct-2",
    ]


def test_malformed_domain_policy_fails_closed() -> None:
    with pytest.raises(ValueError, match="must be an object"):
        normalized_configuration("monthly_review")
    with pytest.raises(ValueError, match="must be boolean"):
        normalized_configuration({"monthly_review": {"enabled": "yes"}})
    with pytest.raises(ValueError, match="must be boolean"):
        normalized_configuration({"monthly_review": {"enabled": None}})
    with pytest.raises(ValueError, match="invalid priority"):
        normalized_configuration({"monthly_review": {"priority": ""}})
    with pytest.raises(ValueError, match="invalid priority"):
        normalized_configuration({"monthly_review": {"priority": "URGENT"}})
    with pytest.raises(ValueError, match="must be numeric"):
        normalized_configuration({"interest_charged": {"minimumAmount": "NaN"}})
    with pytest.raises(ValueError, match="Unknown banking decision trigger policy"):
        normalized_configuration({"made_up_policy": {"enabled": True}})


def test_malformed_or_prompt_like_facts_fail_at_the_provider_boundary() -> None:
    nested_prompt = event(
        "remote_domain_credit_card_statement_closed",
        "statement-nested-prompt",
        "minimumAmountDue",
        45,
    )
    nested_prompt["metadata"] = {"chainOfThought": "must not escape"}
    with pytest.raises(ValueError, match="forbidden field 'chainOfThought'"):
        decision_triggers_from_evidence([nested_prompt], SCHEDULED_AT)

    structured_account_id = event(
        "remote_domain_credit_card_statement_closed",
        "statement-structured-account",
        "minimumAmountDue",
        45,
    )
    structured_account_id["accountId"] = {"metadata": "acct-1"}
    with pytest.raises(ValueError, match="accountId.*text scalar"):
        decision_triggers_from_evidence([structured_account_id], SCHEDULED_AT)


def test_encodes_numeric_runtime_identifiers_as_canonical_decimal_strings() -> None:
    source = event(
        "remote_domain_credit_card_statement_closed",
        "statement-numeric-runtime-id",
        "minimumAmountDue",
        45,
    )
    source["runtimeAccountId"] = 1_896_391_701_000

    trigger = decision_triggers_from_evidence([source], SCHEDULED_AT)[0]

    assert trigger["payload"]["runtimeAccountId"] == "1896391701000"
    assert isinstance(trigger["payload"]["runtimeAccountId"], str)


def test_shared_adversarial_fixture_fails_identically_across_providers() -> None:
    fixture = json.loads(fixture_path().read_text(encoding="utf-8"))

    for vector in fixture["invalidEvents"]:
        with pytest.raises(ValueError, match=vector["expectedMessage"].replace("'", "\\'")):
            decision_triggers_from_evidence([vector["event"]], SCHEDULED_AT)


def event(event_type: str, occurrence_key: str, fact_key: str, fact_value: object) -> dict[str, object]:
    return {
        "eventType": event_type,
        "occurrenceKey": occurrence_key,
        "accountId": "acct-1",
        fact_key: fact_value,
    }


def fixture_path() -> Path:
    for candidate in [
        Path("test-fixtures/banking-decision-trigger-policy-v1.json"),
        Path("../test-fixtures/banking-decision-trigger-policy-v1.json"),
    ]:
        if candidate.is_file():
            return candidate
    raise RuntimeError("banking decision-trigger policy fixture was not found")
