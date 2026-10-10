"""Read-only, deterministic implementation of the fixed refund-policy-v1 sample.

This is an eligibility calculator, not a general policy interpreter or a refund
executor. Dates are supplied calendar dates, never the current clock. An optional
policy object must identify this sample's version and 30-day window; additional
descriptive policy metadata is not executable. Unknown correlation fields are
ignored. No platform fixtures, expected answers, model, or external service are
consulted.
"""

from datetime import date
import re


POLICY_VERSION = "refund-policy-v1"
MAXIMUM_CALENDAR_DAYS = 30
_ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")


def _read_date(facts: dict, field: str) -> date | None:
    value = facts.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not _ISO_DATE.fullmatch(value):
        raise ValueError(f"{field} must be a valid YYYY-MM-DD string or null")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{field} must be a valid YYYY-MM-DD string or null") from exc


def _validate_policy(facts: dict) -> None:
    if "policy" not in facts:
        return
    policy = facts["policy"]
    if not isinstance(policy, dict):
        raise ValueError("policy must be an object when supplied")
    if policy.get("policyVersion") != POLICY_VERSION:
        raise ValueError(f"Only policyVersion {POLICY_VERSION} is supported")
    days = policy.get("maximumCalendarDays")
    if type(days) is not int or days != MAXIMUM_CALENDAR_DAYS:
        raise ValueError("Only integer maximumCalendarDays 30 is supported")


def assess(facts: dict) -> dict:
    """Assess fixed sample eligibility using validated facts without side effects.

    Missing or null business fields are unknown, not malformed. All present
    business fields must be valid even if another field establishes an exclusion.
    Exclusions take precedence in this order: outside the inclusive 30-day window,
    then opened item. Otherwise unknown fields require more information; an
    unopened item within the window is eligible. Malformed inputs raise ValueError.
    """
    if not isinstance(facts, dict):
        raise ValueError("facts must be an object")
    _validate_policy(facts)
    purchase_date = _read_date(facts, "purchaseDate")
    request_date = _read_date(facts, "requestDate")
    condition = facts.get("itemCondition")
    if condition is not None and condition not in ("unopened", "opened"):
        raise ValueError("itemCondition must be unopened, opened, or null")

    elapsed = None
    if purchase_date is not None and request_date is not None:
        elapsed = (request_date - purchase_date).days
        if elapsed < 0:
            raise ValueError("requestDate must not precede purchaseDate")

    if elapsed is not None and elapsed > MAXIMUM_CALENDAR_DAYS:
        decision, reason = "ineligible", "OUTSIDE_RETURN_WINDOW"
    elif condition == "opened":
        decision, reason = "ineligible", "ITEM_OPENED"
    elif elapsed is None or condition is None:
        decision, reason = "needs_information", "MISSING_REQUIRED_INFORMATION"
    else:
        decision, reason = "eligible", "WITHIN_WINDOW_UNOPENED"

    return {"decision": decision, "reasonCode": reason, "policyVersion": POLICY_VERSION}
