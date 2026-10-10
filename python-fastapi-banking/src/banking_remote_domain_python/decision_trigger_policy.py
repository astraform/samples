from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any


POLICY_VERSION = "banking.customer-decision-trigger-policy.v1"
NUMERIC_ENCODING = "canonical_decimal_string.v1"
ALLOWED_PRIORITIES = {"LOW", "NORMAL", "HIGH", "CRITICAL"}
FORBIDDEN_FACT_FIELDS = {"prompt", "systemPrompt", "rawProviderText", "chainOfThought"}
NUMERIC_FACT_FIELDS = {
    "amount",
    "appliedAtEpochMs",
    "affectedAccountCount",
    "balanceAtReview",
    "closingAmount",
    "currentBalance",
    "feeAmount",
    "interestAmount",
    "effectiveAtEpochMs",
    "minimumAmountDue",
    "newCreditLimit",
    "newRate",
    "oldCreditLimit",
    "oldRate",
    "outstandingAmount",
    "scheduledForEpochMs",
    "threshold",
    "thresholdAmount",
}
IDENTIFIER_FACT_FIELDS = {
    "accountId",
    "billId",
    "businessEventId",
    "customerId",
    "interventionId",
    "occurrenceKey",
    "operationId",
    "planDigest",
    "policyId",
    "runtimeAccountId",
    "simulationRunId",
    "transactionId",
    "workId",
}
MAX_FACT_DEPTH = 8
MAX_FACT_TEXT_LENGTH = 512
MAX_FACT_DECIMAL_SCALE = 38
MAX_ABSOLUTE_FACT_NUMBER = Decimal("1000000000000000000")

SAFE_FACT_FIELDS = [
    "eventType",
    "businessEventId",
    "simulationRunId",
    "customerId",
    "interventionId",
    "operationId",
    "planDigest",
    "occurrenceKey",
    "workId",
    "billId",
    "transactionId",
    "accountId",
    "runtimeAccountId",
    "accountName",
    "productCode",
    "productCategory",
    "scheduledForEpochMs",
    "effectiveAtEpochMs",
    "appliedAtEpochMs",
    "affectedAccountCount",
    "transactionType",
    "amount",
    "closingAmount",
    "minimumAmountDue",
    "outstandingAmount",
    "dueDate",
    "interestAmount",
    "feeAmount",
    "oldCreditLimit",
    "newCreditLimit",
    "oldRate",
    "newRate",
    "policyId",
    "threshold",
    "thresholdAmount",
    "balanceAtReview",
    "currentBalance",
    "statementStartDate",
    "statementEndDate",
    "currency",
    "outcome",
]

DEFINITIONS: dict[str, dict[str, Any]] = {
    "statement_close": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("0.01"),
        "reason": "Credit card statement closed",
    },
    "payment_due": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("0.01"),
        "reason": "Payment is due",
    },
    "payment_missed": {
        "enabled": True,
        "priority": "HIGH",
        "minimumAmount": Decimal("0.01"),
        "reason": "Payment was missed",
    },
    "interest_charged": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("25.00"),
        "reason": "Material interest was charged",
    },
    "fee_charged": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("10.00"),
        "reason": "Material fee was charged",
    },
    "credit_limit_change": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("0"),
        "reason": "Credit limit changed",
    },
    "rate_or_policy_change": {
        "enabled": True,
        "priority": "NORMAL",
        "minimumAmount": Decimal("0"),
        "reason": "Rate or policy changed",
    },
    "hardship_threshold_crossed": {
        "enabled": True,
        "priority": "HIGH",
        "minimumAmount": Decimal("0"),
        "reason": "Hardship threshold crossed",
    },
    "liquidity_signal": {
        "enabled": True,
        "priority": "HIGH",
        "minimumAmount": Decimal("0"),
        "reason": "Liquidity threshold crossed",
    },
    "monthly_review": {
        "enabled": False,
        "priority": "LOW",
        "minimumAmount": Decimal("0"),
        "reason": "Scheduled monthly review is due",
    },
}

EVENT_TRIGGER_TYPES = {
    "remote_domain_credit_card_statement_closed": "statement_close",
    "remote_domain_payment_due": "payment_due",
    "remote_domain_payment_missed": "payment_missed",
    "remote_domain_credit_card_interest_charged": "interest_charged",
    "remote_domain_loan_interest_accrued": "interest_charged",
    "remote_domain_fee_charged": "fee_charged",
    "remote_domain_credit_limit_changed": "credit_limit_change",
    "remote_domain_rate_changed": "rate_or_policy_change",
    "remote_domain_policy_changed": "rate_or_policy_change",
    "remote_domain_hardship_reviewed": "hardship_threshold_crossed",
    "remote_domain_hardship_threshold_crossed": "hardship_threshold_crossed",
    "remote_domain_liquidity_threshold_crossed": "liquidity_signal",
    "remote_domain_scheduled_review_due": "monthly_review",
}


def default_configuration() -> dict[str, dict[str, Any]]:
    return {
        trigger_type: {
            "enabled": definition["enabled"],
            "priority": definition["priority"],
            "minimumAmount": definition["minimumAmount"],
        }
        for trigger_type, definition in DEFINITIONS.items()
    }


def normalized_configuration(raw_configuration: Any) -> dict[str, dict[str, Any]]:
    if raw_configuration is None:
        raw_configuration = {}
    if not isinstance(raw_configuration, dict):
        raise ValueError("Banking decisionTriggerPolicy must be an object")
    unknown = set(raw_configuration) - set(DEFINITIONS)
    if unknown:
        raise ValueError(f"Unknown banking decision trigger policy: {sorted(unknown)[0]}")

    resolved = default_configuration()
    for trigger_type, override in raw_configuration.items():
        if not isinstance(override, dict):
            raise ValueError(f"Banking decision trigger policy '{trigger_type}' must be an object")
        allowed_fields = {"enabled", "priority", "minimumAmount"}
        unknown_fields = set(override) - allowed_fields
        if unknown_fields:
            raise ValueError(
                f"Banking decision trigger policy '{trigger_type}' has unknown field "
                f"'{sorted(unknown_fields)[0]}'"
            )
        if "enabled" in override:
            if not isinstance(override["enabled"], bool):
                raise ValueError(f"Banking decision trigger policy '{trigger_type}.enabled' must be boolean")
            resolved[trigger_type]["enabled"] = override["enabled"]
        if "priority" in override:
            priority = _text(override["priority"]).upper()
            if priority not in ALLOWED_PRIORITIES:
                raise ValueError(f"Banking decision trigger policy '{trigger_type}' has invalid priority")
            resolved[trigger_type]["priority"] = priority
        if "minimumAmount" in override:
            minimum_amount = _decimal(override["minimumAmount"], f"{trigger_type}.minimumAmount")
            if minimum_amount < Decimal("0"):
                raise ValueError(
                    f"Banking decision trigger policy '{trigger_type}' minimumAmount must not be negative"
                )
            resolved[trigger_type]["minimumAmount"] = minimum_amount
    return resolved


def decision_triggers_from_evidence(
    evidence_events: list[dict[str, Any]],
    scheduled_for_epoch_ms: int,
    raw_configuration: Any = None,
) -> list[dict[str, Any]]:
    configuration = normalized_configuration(raw_configuration)
    triggers: list[dict[str, Any]] = []
    for event in evidence_events or []:
        _reject_forbidden_fields(event, "event", 0)
        raw_facts = _event_facts(event)
        facts = _sanitized_facts(raw_facts)
        source_event_type = _text(facts.get("eventType"))
        trigger_type = EVENT_TRIGGER_TYPES.get(source_event_type)
        if not trigger_type:
            continue
        definition = DEFINITIONS[trigger_type]
        policy = configuration[trigger_type]
        if not policy["enabled"] or not _matches(facts, trigger_type, policy):
            continue
        trigger_ref = _trigger_reference(event, facts)
        if not trigger_ref:
            continue
        trigger_payload: dict[str, Any] = {
            "policyVersion": POLICY_VERSION,
            "numericEncoding": NUMERIC_ENCODING,
            "normalizedEventType": trigger_type,
            "sourceEventType": source_event_type,
        }
        for key in SAFE_FACT_FIELDS:
            if key in facts and facts[key] is not None:
                trigger_payload[key] = facts[key]
        triggers.append(
            {
                "schemaVersion": "customer_decision_trigger.v1",
                "triggerId": f"{trigger_type}:{trigger_ref}",
                "triggerType": trigger_type,
                "triggerRef": trigger_ref,
                "scheduledForEpochMs": _event_scheduled_for_epoch_ms(raw_facts, scheduled_for_epoch_ms),
                "priority": policy["priority"],
                "schedulingPriority": policy["priority"],
                "reason": definition["reason"],
                "payload": trigger_payload,
                "evidenceRefs": [trigger_ref],
            }
        )
    return triggers


def _event_scheduled_for_epoch_ms(facts: dict[str, Any], fallback: int) -> int:
    raw = None
    if isinstance(facts, dict):
        raw = next(
            (
                facts.get(field)
                for field in (
                    "appliedAtEpochMs",
                    "effectiveAtEpochMs",
                    "scheduledForEpochMs",
                )
                if facts.get(field) is not None
            ),
            None,
        )
    if isinstance(raw, bool):
        return fallback
    try:
        value = int(raw)
    except (TypeError, ValueError, OverflowError):
        return fallback
    return value if value >= 0 else fallback


def _matches(facts: dict[str, Any], trigger_type: str, policy: dict[str, Any]) -> bool:
    if (
        trigger_type == "hardship_threshold_crossed"
        and _text(facts.get("eventType")) == "remote_domain_hardship_reviewed"
    ):
        return _text(facts.get("outcome")).upper() == "APPROVED"
    amount_fields = {
        "statement_close": ["minimumAmountDue", "outstandingAmount", "closingAmount"],
        "payment_due": ["outstandingAmount", "minimumAmountDue", "amount"],
        "payment_missed": ["outstandingAmount", "minimumAmountDue", "amount"],
        "interest_charged": ["interestAmount", "amount"],
        "fee_charged": ["feeAmount", "amount"],
    }.get(trigger_type, [])
    if not amount_fields:
        return True
    amount = _first_amount(facts, amount_fields)
    return amount is not None and abs(amount) >= policy["minimumAmount"]


def _event_facts(event: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(event, dict):
        return {}
    payload = event.get("payload")
    return dict(payload) if isinstance(payload, dict) and payload else dict(event)


def _trigger_reference(event: dict[str, Any], facts: dict[str, Any]) -> str:
    base = _first_text(
        facts.get("businessEventId"),
        facts.get("occurrenceKey"),
        facts.get("transactionId"),
        facts.get("billId"),
        facts.get("workId"),
        _event_text(event, "eventId"),
        _event_text(event, "correlationId"),
    )
    if not base:
        return ""
    subject = _first_text(facts.get("accountId"), facts.get("runtimeAccountId"))
    return f"{base}|account:{subject}" if subject else base


def _sanitized_facts(facts: dict[str, Any]) -> dict[str, Any]:
    if not facts:
        return {}
    _reject_forbidden_fields(facts, "event facts", 0)
    sanitized: dict[str, Any] = {}
    for field in SAFE_FACT_FIELDS:
        if field not in facts or facts[field] is None:
            continue
        sanitized[field] = (
            _canonical_decimal_string(_numeric_fact(facts[field], field))
            if field in NUMERIC_FACT_FIELDS
            else _text_fact(facts[field], field)
        )
    return sanitized


def _reject_forbidden_fields(value: Any, path: str, depth: int) -> None:
    if value is None:
        return
    if depth > MAX_FACT_DEPTH:
        raise ValueError(f"Banking decision trigger facts exceed the maximum nesting depth at {path}")
    if isinstance(value, dict):
        for raw_key, nested in value.items():
            key = str(raw_key) if raw_key is not None else ""
            if key in FORBIDDEN_FACT_FIELDS:
                raise ValueError(f"Banking decision trigger facts contain forbidden field '{key}' at {path}")
            _reject_forbidden_fields(nested, f"{path}.{key}", depth + 1)
        return
    if isinstance(value, (list, tuple)):
        for index, nested in enumerate(value):
            _reject_forbidden_fields(nested, f"{path}[{index}]", depth + 1)


def _text_fact(value: Any, field: str) -> str:
    if field in IDENTIFIER_FACT_FIELDS and not isinstance(value, bool) and isinstance(value, (int, float, Decimal)):
        identifier = _numeric_fact(value, field)
        if identifier != identifier.to_integral_value():
            raise ValueError(f"Banking decision trigger fact '{field}' must be an integral identifier")
        return _canonical_decimal_string(identifier)
    if not isinstance(value, str):
        raise ValueError(f"Banking decision trigger fact '{field}' must be a text scalar")
    resolved = value.strip()
    if not resolved or len(resolved) > MAX_FACT_TEXT_LENGTH:
        raise ValueError(
            f"Banking decision trigger fact '{field}' must be nonblank and at most "
            f"{MAX_FACT_TEXT_LENGTH} characters"
        )
    return resolved


def _numeric_fact(value: Any, field: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        raise ValueError(f"Banking decision trigger fact '{field}' must be numeric")
    try:
        resolved = Decimal(str(value).strip())
    except (InvalidOperation, ValueError) as error:
        raise ValueError(f"Banking decision trigger fact '{field}' must be numeric") from error
    if (
        not resolved.is_finite()
        or len(resolved.as_tuple().digits) > 38
        or abs(resolved) > MAX_ABSOLUTE_FACT_NUMBER
    ):
        raise ValueError(f"Banking decision trigger fact '{field}' exceeds the supported numeric bounds")
    digits = resolved.as_tuple().digits
    trailing_zeros = 0
    if not resolved.is_zero():
        for digit in reversed(digits):
            if digit != 0:
                break
            trailing_zeros += 1
    effective_exponent = resolved.as_tuple().exponent + trailing_zeros
    if max(0, -effective_exponent) > MAX_FACT_DECIMAL_SCALE:
        raise ValueError(f"Banking decision trigger fact '{field}' exceeds the supported numeric bounds")
    return resolved


def _canonical_decimal_string(value: Decimal) -> str:
    plain = format(value, "f")
    if "." in plain:
        plain = plain.rstrip("0").rstrip(".")
    return "0" if plain in {"", "-0"} else plain


def _event_text(event: dict[str, Any], field: str) -> str:
    if not isinstance(event, dict) or event.get(field) is None:
        return ""
    return str(_text_fact(event[field], field))


def _first_amount(facts: dict[str, Any], fields: list[str]) -> Decimal | None:
    for field in fields:
        if facts.get(field) is None:
            continue
        try:
            amount = Decimal(str(facts[field]).strip())
            return amount if amount.is_finite() else None
        except (InvalidOperation, ValueError):
            return None
    return None


def _decimal(value: Any, field: str) -> Decimal:
    try:
        resolved = Decimal(str(value).strip())
        if not resolved.is_finite():
            raise InvalidOperation
        return resolved
    except (InvalidOperation, ValueError) as error:
        raise ValueError(f"Banking decision trigger policy '{field}' must be numeric") from error


def _first_text(*values: Any) -> str:
    for value in values:
        resolved = _text(value)
        if resolved:
            return resolved
    return ""


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()
