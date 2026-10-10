from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any


PLAN_SCHEMA_VERSION = "banking.clock-driven-rate-change-plan.v1"
SNAPSHOT_SCHEMA_VERSION = "banking.clock-driven-intervention-plan-snapshot.v1"
INSTALLATION_MANIFEST_SCHEMA_VERSION = "banking.clock-driven-rate-change-installation.v1"
ACTION_ID = "schedule-rate-change-plan"
WORK_KIND = "credit_card_rate_change"
INTERVENTION_ID = "policy_rate_candidate_shift"
INTERVENTION_TYPE = "RATE_CHANGE"
SUPPORTED_PRESET_ID = "credit-apr-lift"
SUPPORTED_PRODUCT_CODE = "CC001"
SUPPORTED_PREVIOUS_RATE = "0.19"
SUPPORTED_NEW_RATE = "0.24"
SCHEDULED_OFFSET_DAYS = 10
MAX_IJSON_INTEGER = 9_007_199_254_740_991
DAY_MS = 86_400_000

PLAN_FIELDS = {
    "schemaVersion",
    "simulationRunId",
    "interventionId",
    "interventionType",
    "productCode",
    "expectedPreviousRate",
    "newRate",
    "scheduledOffsetDays",
    "simulationStartEpochMs",
    "scheduledForEpochMs",
    "effectiveAtEpochMs",
    "mutationOperationId",
    "workId",
}
SNAPSHOT_FIELDS = {"schemaVersion", "planDigest", "plan"}
INSTALLATION_MANIFEST_FIELDS = {
    "schemaVersion",
    "manifestDigest",
    "simulationRunId",
    "planDigest",
    "installOperationId",
    "cohortArtifactDigest",
    "agentIdentitySetDigest",
    "installedAgentCount",
    "eligibleCustomerCount",
    "customers",
}
INSTALLATION_CUSTOMER_FIELDS = {
    "cohortAgentId",
    "providerRuntimeAgentId",
    "opportunityAgentId",
    "eligibleAccountCount",
}


@dataclass(frozen=True)
class RateChangePlanSnapshot:
    plan_digest: str
    plan: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": SNAPSHOT_SCHEMA_VERSION,
            "planDigest": self.plan_digest,
            "plan": dict(self.plan),
        }


@dataclass(frozen=True)
class RateChangeInstallationCustomer:
    cohort_agent_id: str
    provider_runtime_agent_id: str
    opportunity_agent_id: str
    eligible_account_count: int

    def as_dict(self) -> dict[str, Any]:
        return {
            "cohortAgentId": self.cohort_agent_id,
            "providerRuntimeAgentId": self.provider_runtime_agent_id,
            "opportunityAgentId": self.opportunity_agent_id,
            "eligibleAccountCount": self.eligible_account_count,
        }


@dataclass(frozen=True)
class RateChangeInstallationManifest:
    manifest_digest: str
    simulation_run_id: str
    plan_digest: str
    install_operation_id: str
    cohort_artifact_digest: str
    agent_identity_set_digest: str
    installed_agent_count: int
    eligible_customer_count: int
    customers: tuple[RateChangeInstallationCustomer, ...]

    def as_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": INSTALLATION_MANIFEST_SCHEMA_VERSION,
            "manifestDigest": self.manifest_digest,
            "simulationRunId": self.simulation_run_id,
            "planDigest": self.plan_digest,
            "installOperationId": self.install_operation_id,
            "cohortArtifactDigest": self.cohort_artifact_digest,
            "agentIdentitySetDigest": self.agent_identity_set_digest,
            "installedAgentCount": self.installed_agent_count,
            "eligibleCustomerCount": self.eligible_customer_count,
            "customers": [customer.as_dict() for customer in self.customers],
        }


def compile_for_launch(
    simulation_run_id: str,
    preset_id: str,
    parameters: dict[str, Any],
) -> RateChangePlanSnapshot | None:
    if _text(preset_id) != SUPPORTED_PRESET_ID:
        return None
    _require_exact_text(parameters, "productCode", SUPPORTED_PRODUCT_CODE)
    _require_exact_decimal(parameters, "baselineRate", SUPPORTED_PREVIOUS_RATE)
    _require_exact_decimal(parameters, "candidateRate", SUPPORTED_NEW_RATE)
    _require_exact_integer(parameters, "prePolicyDays", SCHEDULED_OFFSET_DAYS)
    _require_no_excluded_overlay(parameters)

    start_epoch_ms = _require_ijson_integer(
        parameters.get("simulationStartEpochMs"),
        "simulationStartEpochMs",
        minimum=1,
    )
    scheduled_for_epoch_ms = start_epoch_ms + SCHEDULED_OFFSET_DAYS * DAY_MS
    if scheduled_for_epoch_ms > MAX_IJSON_INTEGER:
        raise ValueError("Rate-change scheduled time exceeds the I-JSON integer range")
    run_id = _require_text(simulation_run_id, "simulationRunId")
    identity_digest = _sha256_text(
        "|".join(
            [
                run_id,
                INTERVENTION_ID,
                SUPPORTED_PRODUCT_CODE,
                SUPPORTED_PREVIOUS_RATE,
                SUPPORTED_NEW_RATE,
                str(scheduled_for_epoch_ms),
            ]
        )
    )
    plan: dict[str, Any] = {
        "schemaVersion": PLAN_SCHEMA_VERSION,
        "simulationRunId": run_id,
        "interventionId": INTERVENTION_ID,
        "interventionType": INTERVENTION_TYPE,
        "productCode": SUPPORTED_PRODUCT_CODE,
        "expectedPreviousRate": SUPPORTED_PREVIOUS_RATE,
        "newRate": SUPPORTED_NEW_RATE,
        "scheduledOffsetDays": SCHEDULED_OFFSET_DAYS,
        "simulationStartEpochMs": start_epoch_ms,
        "scheduledForEpochMs": scheduled_for_epoch_ms,
        "effectiveAtEpochMs": scheduled_for_epoch_ms,
        "mutationOperationId": f"rate-change:{identity_digest}",
        "workId": f"rate-change-work:{identity_digest[:32]}",
    }
    return _snapshot(plan)


def require_snapshot(raw_snapshot: Any) -> RateChangePlanSnapshot:
    snapshot = _require_mapping(raw_snapshot, "rate-change plan snapshot")
    _require_exact_fields(snapshot, SNAPSHOT_FIELDS, "rate-change plan snapshot")
    _require_exact_text(snapshot, "schemaVersion", SNAPSHOT_SCHEMA_VERSION)
    plan_digest = _require_sha256(snapshot.get("planDigest"), "planDigest")
    plan = _require_mapping(snapshot.get("plan"), "rate-change plan")
    _validate_plan(plan)
    normalized_plan = _normalized_plan(plan)
    computed_digest = hashlib.sha256(canonical_json_bytes(normalized_plan)).hexdigest()
    if not hmac.compare_digest(computed_digest, plan_digest):
        raise ValueError("Rate-change plan digest does not match its canonical plan")
    return RateChangePlanSnapshot(plan_digest=plan_digest, plan=normalized_plan)


def require_snapshot_bytes(content: bytes) -> RateChangePlanSnapshot:
    if not content or len(content) > 65_536:
        raise ValueError("Rate-change plan snapshot must be between 1 and 65536 bytes")
    try:
        text = content.decode("utf-8", errors="strict")
        decoder = json.JSONDecoder(
            object_pairs_hook=_reject_duplicate_keys,
            parse_float=Decimal,
            parse_int=int,
            parse_constant=_reject_non_finite,
        )
        raw, end = decoder.raw_decode(text)
        if text[end:].strip():
            raise ValueError("trailing JSON content")
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError("Rate-change plan snapshot must be strict UTF-8 JSON") from exc
    return require_snapshot(raw)


def install_operation_id(snapshot: RateChangePlanSnapshot) -> str:
    return f"install-rate-change:{snapshot.plan_digest}"


def canonical_snapshot_bytes(snapshot: RateChangePlanSnapshot) -> bytes:
    return canonical_json_bytes(snapshot.as_dict())


def installation_manifest(
    snapshot: RateChangePlanSnapshot,
    raw_customers: list[RateChangeInstallationCustomer],
    cohort_artifact_digest: str,
) -> RateChangeInstallationManifest:
    customers = _normalized_installation_customers(raw_customers)
    pinned_cohort_artifact_digest = _require_sha256(
        cohort_artifact_digest,
        "cohortArtifactDigest",
    )
    facts = _installation_facts(snapshot, customers, pinned_cohort_artifact_digest)
    return RateChangeInstallationManifest(
        manifest_digest=hashlib.sha256(canonical_json_bytes(facts)).hexdigest(),
        simulation_run_id=_require_text(snapshot.plan.get("simulationRunId"), "simulationRunId"),
        plan_digest=snapshot.plan_digest,
        install_operation_id=install_operation_id(snapshot),
        cohort_artifact_digest=pinned_cohort_artifact_digest,
        agent_identity_set_digest=agent_identity_set_digest(
            [customer.cohort_agent_id for customer in customers]
        ),
        installed_agent_count=len(customers),
        eligible_customer_count=sum(
            1 for customer in customers if customer.eligible_account_count > 0
        ),
        customers=customers,
    )


def require_installation_manifest(
    raw_manifest: Any,
    expected_plan: RateChangePlanSnapshot,
) -> RateChangeInstallationManifest:
    manifest = _require_mapping(raw_manifest, "rate-change installation manifest")
    _require_exact_fields(
        manifest,
        INSTALLATION_MANIFEST_FIELDS,
        "rate-change installation manifest",
    )
    _require_exact_text(
        manifest,
        "schemaVersion",
        INSTALLATION_MANIFEST_SCHEMA_VERSION,
    )
    manifest_digest = _require_sha256(manifest.get("manifestDigest"), "manifestDigest")
    simulation_run_id = _require_text(manifest.get("simulationRunId"), "simulationRunId")
    plan_digest = _require_sha256(manifest.get("planDigest"), "planDigest")
    operation_id = _require_text(manifest.get("installOperationId"), "installOperationId")
    cohort_artifact_digest = _require_sha256(
        manifest.get("cohortArtifactDigest"), "cohortArtifactDigest"
    )
    identity_set_digest = _require_sha256(
        manifest.get("agentIdentitySetDigest"), "agentIdentitySetDigest"
    )
    installed_agent_count = _require_exact_ijson_integer(
        manifest.get("installedAgentCount"), "installedAgentCount", minimum=0
    )
    eligible_customer_count = _require_exact_ijson_integer(
        manifest.get("eligibleCustomerCount"), "eligibleCustomerCount", minimum=0
    )
    raw_customers = manifest.get("customers")
    if not isinstance(raw_customers, list):
        raise ValueError("rate-change installation customers must be an array")
    customers: list[RateChangeInstallationCustomer] = []
    for raw_customer in raw_customers:
        customer = _require_mapping(raw_customer, "rate-change installation customer")
        _require_exact_fields(
            customer,
            INSTALLATION_CUSTOMER_FIELDS,
            "rate-change installation customer",
        )
        customers.append(
            RateChangeInstallationCustomer(
                cohort_agent_id=_require_text(
                    customer.get("cohortAgentId"), "cohortAgentId"
                ),
                provider_runtime_agent_id=_require_text(
                    customer.get("providerRuntimeAgentId"), "providerRuntimeAgentId"
                ),
                opportunity_agent_id=_require_text(
                    customer.get("opportunityAgentId"), "opportunityAgentId"
                ),
                eligible_account_count=_require_exact_ijson_integer(
                    customer.get("eligibleAccountCount"),
                    "eligibleAccountCount",
                    minimum=0,
                ),
            )
        )
    normalized_customers = _normalized_installation_customers(customers)
    if (
        tuple(customers) != normalized_customers
        or installed_agent_count != len(customers)
        or eligible_customer_count
        != sum(1 for customer in customers if customer.eligible_account_count > 0)
        or simulation_run_id != _require_text(expected_plan.plan.get("simulationRunId"), "simulationRunId")
        or plan_digest != expected_plan.plan_digest
        or operation_id != install_operation_id(expected_plan)
        or identity_set_digest
        != agent_identity_set_digest(
            [customer.cohort_agent_id for customer in customers]
        )
    ):
        raise ValueError(
            "Rate-change installation manifest contradicts the launch-pinned plan"
        )
    normalized = RateChangeInstallationManifest(
        manifest_digest=manifest_digest,
        simulation_run_id=simulation_run_id,
        plan_digest=plan_digest,
        install_operation_id=operation_id,
        cohort_artifact_digest=cohort_artifact_digest,
        agent_identity_set_digest=identity_set_digest,
        installed_agent_count=installed_agent_count,
        eligible_customer_count=eligible_customer_count,
        customers=normalized_customers,
    )
    facts = normalized.as_dict()
    facts.pop("manifestDigest")
    computed = hashlib.sha256(canonical_json_bytes(facts)).hexdigest()
    if not hmac.compare_digest(computed, manifest_digest):
        raise ValueError(
            "Rate-change installation manifest digest does not match its canonical facts"
        )
    return normalized


def require_installation_manifest_bytes(
    content: bytes,
    expected_plan: RateChangePlanSnapshot,
) -> RateChangeInstallationManifest:
    if not content or len(content) > 262_144:
        raise ValueError(
            "Rate-change installation manifest must be between 1 and 262144 bytes"
        )
    try:
        text = content.decode("utf-8", errors="strict")
        decoder = json.JSONDecoder(
            object_pairs_hook=_reject_duplicate_keys,
            parse_float=Decimal,
            parse_int=int,
            parse_constant=_reject_non_finite,
        )
        raw, end = decoder.raw_decode(text)
        if text[end:].strip():
            raise ValueError("trailing JSON content")
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError(
            "Rate-change installation manifest must be strict UTF-8 JSON"
        ) from exc
    return require_installation_manifest(raw, expected_plan)


def canonical_installation_manifest_bytes(
    manifest: RateChangeInstallationManifest,
) -> bytes:
    return canonical_json_bytes(manifest.as_dict())


def _installation_facts(
    snapshot: RateChangePlanSnapshot,
    customers: tuple[RateChangeInstallationCustomer, ...],
    cohort_artifact_digest: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": INSTALLATION_MANIFEST_SCHEMA_VERSION,
        "simulationRunId": snapshot.plan["simulationRunId"],
        "planDigest": snapshot.plan_digest,
        "installOperationId": install_operation_id(snapshot),
        "cohortArtifactDigest": cohort_artifact_digest,
        "agentIdentitySetDigest": agent_identity_set_digest(
            [customer.cohort_agent_id for customer in customers]
        ),
        "installedAgentCount": len(customers),
        "eligibleCustomerCount": sum(
            1 for customer in customers if customer.eligible_account_count > 0
        ),
        "customers": [customer.as_dict() for customer in customers],
    }


def _normalized_installation_customers(
    raw_customers: list[RateChangeInstallationCustomer],
) -> tuple[RateChangeInstallationCustomer, ...]:
    if not raw_customers or len(raw_customers) > 1_000:
        raise ValueError(
            "Rate-change installation requires between 1 and 1000 customers"
        )
    customers = tuple(
        sorted(
            (
                RateChangeInstallationCustomer(
                    cohort_agent_id=_require_text(
                        customer.cohort_agent_id, "cohortAgentId"
                    ),
                    provider_runtime_agent_id=_require_text(
                        customer.provider_runtime_agent_id, "providerRuntimeAgentId"
                    ),
                    opportunity_agent_id=_require_text(
                        customer.opportunity_agent_id, "opportunityAgentId"
                    ),
                    eligible_account_count=customer.eligible_account_count,
                )
                for customer in raw_customers
            ),
            key=lambda customer: customer.cohort_agent_id,
        )
    )
    if (
        len({customer.cohort_agent_id for customer in customers}) != len(customers)
        or len({customer.provider_runtime_agent_id for customer in customers})
        != len(customers)
        or len({customer.opportunity_agent_id for customer in customers})
        != len(customers)
    ):
        raise ValueError("Rate-change installation customer identities must be unique")
    for customer in customers:
        if customer.eligible_account_count < 0 or customer.eligible_account_count > 1_000:
            raise ValueError("eligibleAccountCount must be between 0 and 1000")
    return customers


def eligible_account_counts_from_cohort_artifact(content: bytes) -> dict[str, int]:
    if not content or len(content) > 16 * 1024 * 1024:
        raise ValueError("Pinned cohort artifact must be between 1 byte and 16 MiB")
    try:
        text = content.decode("utf-8", errors="strict")
        decoder = json.JSONDecoder(
            object_pairs_hook=_reject_duplicate_keys,
            parse_float=Decimal,
            parse_int=int,
            parse_constant=_reject_non_finite,
        )
        raw, end = decoder.raw_decode(text)
        if text[end:].strip():
            raise ValueError("trailing JSON content")
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise ValueError("Pinned cohort artifact must be strict UTF-8 JSON") from exc
    if not isinstance(raw, list) or not raw or len(raw) > 1_000:
        raise ValueError("Pinned cohort artifact must contain between 1 and 1000 agents")
    eligibility: dict[str, int] = {}
    for raw_agent in raw:
        agent = _require_mapping(raw_agent, "pinned cohort agent")
        cohort_agent_id = _require_text(agent.get("personaName"), "personaName")
        accounts = agent.get("initialAccounts")
        if not isinstance(accounts, list):
            raise ValueError("Pinned cohort initialAccounts must be an array")
        eligible_account_count = 0
        for raw_account in accounts:
            account = _require_mapping(raw_account, "pinned cohort account")
            if _text(account.get("productCode")).casefold() == SUPPORTED_PRODUCT_CODE.casefold():
                eligible_account_count += 1
                if eligible_account_count > 1_000:
                    raise ValueError("eligibleAccountCount must be between 0 and 1000")
        if cohort_agent_id in eligibility:
            raise ValueError("Pinned cohort contains duplicate personaName identities")
        eligibility[cohort_agent_id] = eligible_account_count
    return dict(sorted(eligibility.items()))


def agent_identity_set_digest(raw_agent_ids: list[str]) -> str:
    if not raw_agent_ids or len(raw_agent_ids) > 1_000:
        raise ValueError("Agent identity set must contain between 1 and 1000 identities")
    agent_ids = sorted(_require_text(agent_id, "agentId") for agent_id in raw_agent_ids)
    if len(set(agent_ids)) != len(agent_ids):
        raise ValueError("Agent identity set must contain unique identities")
    return hashlib.sha256(canonical_json_bytes(agent_ids)).hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=True,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def canonical_decimal(value: Any, field: str) -> str:
    if value is None or isinstance(value, (bool, dict, list, tuple, set)):
        raise ValueError(f"{field} must be a finite decimal")
    try:
        decimal = Decimal(str(value).strip())
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"{field} must be a finite decimal") from exc
    if not decimal.is_finite() or len(decimal.as_tuple().digits) > 38:
        raise ValueError(f"{field} exceeds the supported decimal bounds")
    trailing_zeros = 0
    if not decimal.is_zero():
        for digit in reversed(decimal.as_tuple().digits):
            if digit:
                break
            trailing_zeros += 1
    effective_exponent = decimal.as_tuple().exponent + trailing_zeros
    if effective_exponent < -38 or effective_exponent > 38:
        raise ValueError(f"{field} exceeds the supported decimal bounds")
    plain = format(decimal, "f")
    if "." in plain:
        plain = plain.rstrip("0").rstrip(".")
    return "0" if plain in {"", "-0"} else plain


def _snapshot(plan: dict[str, Any]) -> RateChangePlanSnapshot:
    _validate_plan(plan)
    return RateChangePlanSnapshot(
        plan_digest=hashlib.sha256(canonical_json_bytes(plan)).hexdigest(),
        plan=dict(plan),
    )


def _validate_plan(plan: dict[str, Any]) -> None:
    _require_exact_fields(plan, PLAN_FIELDS, "rate-change plan")
    _require_exact_text(plan, "schemaVersion", PLAN_SCHEMA_VERSION)
    run_id = _require_text(plan.get("simulationRunId"), "simulationRunId")
    _require_exact_text(plan, "interventionId", INTERVENTION_ID)
    _require_exact_text(plan, "interventionType", INTERVENTION_TYPE)
    _require_exact_text(plan, "productCode", SUPPORTED_PRODUCT_CODE)
    _require_exact_decimal_text(plan, "expectedPreviousRate", SUPPORTED_PREVIOUS_RATE)
    _require_exact_decimal_text(plan, "newRate", SUPPORTED_NEW_RATE)
    _require_exact_json_integer(plan, "scheduledOffsetDays", SCHEDULED_OFFSET_DAYS)
    start = _require_exact_ijson_integer(
        plan.get("simulationStartEpochMs"), "simulationStartEpochMs", minimum=1
    )
    scheduled = _require_exact_ijson_integer(
        plan.get("scheduledForEpochMs"),
        "scheduledForEpochMs",
        minimum=start + 1,
    )
    effective = _require_exact_ijson_integer(
        plan.get("effectiveAtEpochMs"),
        "effectiveAtEpochMs",
        minimum=start + 1,
    )
    if scheduled != start + SCHEDULED_OFFSET_DAYS * DAY_MS or effective != scheduled:
        raise ValueError("Rate-change scheduled time does not match the day-10 offset")
    identity_digest = _sha256_text(
        "|".join(
            [
                run_id,
                INTERVENTION_ID,
                SUPPORTED_PRODUCT_CODE,
                SUPPORTED_PREVIOUS_RATE,
                SUPPORTED_NEW_RATE,
                str(scheduled),
            ]
        )
    )
    _require_exact_text(plan, "mutationOperationId", f"rate-change:{identity_digest}")
    _require_exact_text(plan, "workId", f"rate-change-work:{identity_digest[:32]}")


def _normalized_plan(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": _require_text(plan.get("schemaVersion"), "schemaVersion"),
        "simulationRunId": _require_text(plan.get("simulationRunId"), "simulationRunId"),
        "interventionId": _require_text(plan.get("interventionId"), "interventionId"),
        "interventionType": _require_text(plan.get("interventionType"), "interventionType"),
        "productCode": _require_text(plan.get("productCode"), "productCode"),
        "expectedPreviousRate": canonical_decimal(
            plan.get("expectedPreviousRate"), "expectedPreviousRate"
        ),
        "newRate": canonical_decimal(plan.get("newRate"), "newRate"),
        "scheduledOffsetDays": _require_ijson_integer(
            plan.get("scheduledOffsetDays"), "scheduledOffsetDays", minimum=0
        ),
        "simulationStartEpochMs": _require_ijson_integer(
            plan.get("simulationStartEpochMs"), "simulationStartEpochMs", minimum=1
        ),
        "scheduledForEpochMs": _require_ijson_integer(
            plan.get("scheduledForEpochMs"), "scheduledForEpochMs", minimum=1
        ),
        "effectiveAtEpochMs": _require_ijson_integer(
            plan.get("effectiveAtEpochMs"), "effectiveAtEpochMs", minimum=1
        ),
        "mutationOperationId": _require_text(
            plan.get("mutationOperationId"), "mutationOperationId"
        ),
        "workId": _require_text(plan.get("workId"), "workId"),
    }


def _require_no_excluded_overlay(parameters: dict[str, Any]) -> None:
    if (
        _text(parameters.get("baselineLimit"))
        or _text(parameters.get("candidateLimit"))
        or Decimal(_text(parameters.get("candidateStimulusAmount")) or "0") != 0
        or _boolean_value(parameters.get("candidateCrash"))
        or _boolean_value(parameters.get("candidateHardshipScan"))
        or _boolean_value(parameters.get("candidateFraudAlert"))
    ):
        raise ValueError(
            "credit-apr-lift clock execution supports only the day-10 CC001 rate change"
        )


def _boolean_value(value: Any) -> bool:
    return value is True or _text(value).lower() == "true"


def _require_exact_decimal(values: dict[str, Any], field: str, expected: str) -> None:
    if canonical_decimal(values.get(field), field) != expected:
        raise ValueError(f"{field} must equal {expected} for the rate-change slice")


def _require_exact_decimal_text(
    values: dict[str, Any], field: str, expected: str
) -> None:
    raw = values.get(field)
    if not isinstance(raw, str) or raw != expected or canonical_decimal(raw, field) != expected:
        raise ValueError(f"{field} must be the canonical decimal string {expected}")


def _require_exact_integer(values: dict[str, Any], field: str, expected: int) -> None:
    value = _require_ijson_integer(values.get(field), field, minimum=0)
    if value != expected:
        raise ValueError(f"{field} must equal {expected} for the rate-change slice")


def _require_exact_json_integer(
    values: dict[str, Any], field: str, expected: int
) -> None:
    raw = values.get(field)
    if isinstance(raw, bool) or not isinstance(raw, (int, float, Decimal)):
        raise ValueError(f"{field} must be an exact JSON integer")
    value = _require_ijson_integer(raw, field, minimum=0)
    if value != expected:
        raise ValueError(f"{field} must equal {expected} for the rate-change slice")


def _require_ijson_integer(raw: Any, field: str, *, minimum: int) -> int:
    if raw is None or isinstance(raw, bool):
        raise ValueError(f"{field} must be an I-JSON-safe integer")
    try:
        decimal = Decimal(str(raw).strip())
    except (InvalidOperation, ValueError, OverflowError) as exc:
        raise ValueError(f"{field} must be an I-JSON-safe integer") from exc
    if (
        not decimal.is_finite()
        or decimal != decimal.to_integral_value()
        or decimal < minimum
        or decimal > MAX_IJSON_INTEGER
    ):
        raise ValueError(f"{field} must be an I-JSON-safe integer")
    return int(decimal)


def _require_exact_ijson_integer(raw: Any, field: str, *, minimum: int) -> int:
    if type(raw) is not int:
        raise ValueError(f"{field} must be an exact JSON integer")
    return _require_ijson_integer(raw, field, minimum=minimum)


def _require_sha256(raw: Any, field: str) -> str:
    value = _require_text(raw, field)
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise ValueError(f"{field} must be a lowercase SHA-256 digest")
    return value


def _require_exact_text(values: dict[str, Any], field: str, expected: str) -> None:
    value = _require_text(values.get(field), field)
    if value != expected:
        raise ValueError(f"{field} must equal {expected}")


def _require_text(raw: Any, field: str) -> str:
    if not isinstance(raw, str) or not raw or raw != raw.strip() or len(raw) > 256:
        raise ValueError(f"{field} must be a trimmed nonblank string of at most 256 characters")
    return raw


def _require_exact_fields(values: dict[str, Any], expected: set[str], label: str) -> None:
    if set(values) != expected:
        raise ValueError(f"{label} must contain exactly {sorted(expected)}")


def _require_mapping(raw: Any, label: str) -> dict[str, Any]:
    if not isinstance(raw, dict) or any(not isinstance(key, str) for key in raw):
        raise ValueError(f"{label} must be an object with string keys")
    return dict(raw)


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate field: {key}")
        result[key] = value
    return result


def _reject_non_finite(value: str) -> Any:
    raise ValueError(f"non-finite JSON number: {value}")


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()
