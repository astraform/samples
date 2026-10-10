#!/usr/bin/env python3
"""Check Java/Python banking remote-provider parity against live endpoints.

This is a local contract guard, not a production monitor. Run it when both
banking remote providers are up through Docker Compose or local dev servers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any


REMOTE_JSON = "application/vnd.astraform.remote-domain.v1+json"

REQUIRED_PRODUCTS = {
    "TS001",
    "SAV001",
    "CC001",
    "CC002",
    "HL001",
    "SL001",
    "CL001",
    "BL001",
    "TD001",
}

REQUIRED_TOOLS = {
    "get_product_list",
    "post_double_entry_transaction",
}

REQUIRED_DOMAIN_SYSTEM_WORK = {
    "monthly_interest_accrual",
    "loan_interest_accrual",
    "credit_card_interest_accrual",
    "credit_card_statement_close",
}

REQUIRED_AGENTIC_SUMMARY_FIELDS = {
    "schemaVersion",
    "executionMode",
    "agentDecisionMode",
    "decisionCadence",
    "decisionIntervalMinutes",
    "simulationHorizonDays",
    "playbackRate",
    "cohortSize",
    "expectedDecisionOpportunities",
    "observedDecisionOpportunities",
    "admittedDecisionOpportunities",
    "deferredDecisionOpportunities",
    "deferredDecisionReasons",
    "historicalDeferredDecisionTransitions",
    "historicalDeferredDecisionReasons",
    "actualDecisionCount",
    "auditedAgentCount",
    "fallbackCount",
    "evidenceIntegrityWarnings",
    "decisionCoverageRate",
    "decisionOpportunityCoverageRate",
    "decisionAdmissionRate",
    "decisionCoverageStatus",
    "decisionCoverageExplanation",
    "decisionOpportunitySource",
    "opportunityReconciliation",
    "opportunityTerminalCount",
    "opportunityRemainingBacklog",
    "opportunityStatusCounts",
    "opportunityTotalAttempts",
    "opportunityAdmissionCountVerified",
    "opportunityDecisionEvidenceGaps",
    "configuredDurationDays",
    "actualDurationDays",
    "horizonOvershootDays",
    "durationStatus",
    "evidenceRefs",
}

REQUIRED_REPORT_SECTIONS = {
    "decisionReadiness",
    "decisionComparison",
    "simulationTimeline",
    "sideEffectLedger",
    "branchStateProjection",
    "domainOutcomeScores",
    "agenticSimulation",
    "hybridAgentAudit",
}

VALID_RECOMMENDATIONS = {"LAUNCH", "WATCH", "BLOCK", "REVIEW"}
SCORECARD_METRIC_TOLERANCE_RATIO = Decimal("0.25")
SCORECARD_EVIDENCE_VOLUME_TOLERANCE_RATIO = Decimal("0.30")
SCORECARD_METRIC_ABSOLUTE_TOLERANCE = 10
SCORECARD_EVIDENCE_VOLUME_METRICS = {"runtimeEvidenceSignals", "evidenceSignals"}
DURATION_TOLERANCE_DAYS = Decimal("1.0")
DURATION_MATH_TOLERANCE_DAYS = Decimal("0.0001")
DURATION_OVERRUN_THRESHOLD_DAYS = Decimal("0.05")
VALID_DURATION_STATUSES = {"WITHIN_HORIZON", "HORIZON_OVERRUN"}
MIN_AGENTIC_ACCEPTANCE_COHORT_SIZE = 100
MIN_AGENTIC_ACCEPTANCE_DURATION_DAYS = Decimal("30")
MIN_AGENTIC_ACCEPTANCE_DURATION_TOLERANCE_DAYS = Decimal("0.05")
OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION = "customer_decision_opportunity_reconciliation.v1"
OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION = "customer_decision_opportunity_audit_totals.v1"
OPPORTUNITY_GENERATION_MODE = "EVENT_DRIVEN_DECISION_TRIGGERS"
OPPORTUNITY_LIFECYCLE_STATUSES = {
    "PENDING",
    "RUNNING",
    "SUCCEEDED",
    "DEFERRED",
    "BLOCKED",
    "FAILED",
    "CANCELLED",
}
# customer_decision_opportunity_lifecycle_event.v1 DEFERRED bindings.
OPPORTUNITY_DEFERRED_REASON_CODES_V1 = frozenset({
    "background_admission_limit",
    "background_turn_capacity_timeout",
    "background_turn_capacity_interrupted",
    "permit_timeout",
    "autonomous_backfill_window_limit",
    "agent_not_active",
    "worker_lease_expired",
    "worker_transient_error",
    "provider_pacing_limit",
    "provider_unavailable",
    "provider_rate_limited",
    "provider_timeout",
    "provider_transient_error",
    "opportunity_worker_authorization_unavailable",
})
LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE = "unspecified"

# These are the only decision-trigger payload fields whose provider-specific
# values may differ without representing domain-fact drift.
TRIGGER_PROVIDER_IDENTITY_FIELDS = frozenset({
    "billId",
    "domainId",
    "packId",
    "providerId",
    "providerLanguage",
    "runtimeAccountId",
    "runtimeIdentity",
})
TRIGGER_BUILD_IDENTITY_FIELDS = frozenset({
    "artifactDigest",
    "buildDigest",
    "buildId",
    "buildVersion",
    "providerArtifactDigest",
    "providerBuildId",
    "providerBuildIdentity",
    "providerRevision",
})
TRIGGER_DOMAIN_SCOPED_IDENTITY_FIELDS = frozenset({
    "businessEventId",
    "occurrenceKey",
})
TRIGGER_FORBIDDEN_FIELDS = frozenset({
    "prompt",
    "systemPrompt",
    "rawProviderText",
    "chainOfThought",
})
TRIGGER_SCHEDULING_PRIORITIES = frozenset({"CRITICAL", "HIGH", "NORMAL", "LOW"})


@dataclass(frozen=True)
class Provider:
    name: str
    base_url: str
    expected_domain_id: str
    expected_pack_id: str
    expected_language: str


def fail(message: str) -> None:
    print(f"banking remote provider parity failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_report(condition: bool, label: str, message: str) -> None:
    if not condition:
        fail(f"{label}: {message}")


def fetch_json(provider: Provider, path: str, timeout: float) -> dict[str, Any]:
    url = urllib.parse.urljoin(provider.base_url.rstrip("/") + "/", path.lstrip("/"))
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json, " + REMOTE_JSON,
            "User-Agent": "astraform-banking-remote-provider-parity/1",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        fail(f"{provider.name} returned HTTP {error.code} for {path}: {body}")
    except urllib.error.URLError as error:
        fail(f"{provider.name} is not reachable at {url}: {error.reason}")
    except TimeoutError:
        fail(f"{provider.name} timed out at {url}")
    except json.JSONDecodeError as error:
        fail(f"{provider.name} returned non-JSON for {path}: {error}")


def post_json(provider: Provider, path: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    url = urllib.parse.urljoin(provider.base_url.rstrip("/") + "/", path.lstrip("/"))
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Accept": "application/json, " + REMOTE_JSON,
            "Content-Type": REMOTE_JSON,
            "User-Agent": "astraform-banking-remote-provider-parity/1",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        response_body = error.read().decode("utf-8", errors="replace")
        fail(f"{provider.name} returned HTTP {error.code} for {path}: {response_body}")
    except urllib.error.URLError as error:
        fail(f"{provider.name} is not reachable at {url}: {error.reason}")
    except TimeoutError:
        fail(f"{provider.name} timed out at {url}")
    except json.JSONDecodeError as error:
        fail(f"{provider.name} returned non-JSON for {path}: {error}")


def nested_set(values: Any, key: str) -> set[str]:
    if not isinstance(values, list):
        return set()
    return {
        str(entry.get(key)).strip()
        for entry in values
        if isinstance(entry, dict) and str(entry.get(key, "")).strip()
    }


def product_codes(catalog: dict[str, Any]) -> set[str]:
    products = catalog.get("data", {}).get("products", [])
    return nested_set(products, "productCode")


def scorecard_ids(pack: dict[str, Any]) -> set[str]:
    scorecards = pack.get("outcomeScorecards")
    if not isinstance(scorecards, list):
        scorecard = pack.get("outcomeScorecard")
        scorecards = [scorecard] if isinstance(scorecard, dict) else []
    ids = set()
    for scorecard in scorecards:
        if not isinstance(scorecard, dict):
            continue
        scorecard_id = str(scorecard.get("scorecardId", "")).strip()
        if scorecard_id:
            ids.add(scorecard_id)
    return ids


def preset_ids(catalog: dict[str, Any]) -> set[str]:
    presets = catalog.get("presets", [])
    return nested_set(presets, "presetId")


def require(condition: bool, provider: Provider, message: str) -> None:
    if not condition:
        fail(f"{provider.name}: {message}")


def as_decimal(value: Any) -> Decimal:
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError) as error:
        raise AssertionError(f"expected decimal-compatible value, got {value!r}") from error


def money(value: Any) -> Decimal:
    return as_decimal(value).quantize(Decimal("0.01"))


def int_value(value: Any, default: int = 0) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str) and value.strip():
        try:
            return int(float(value.strip()))
        except ValueError:
            return default
    return default


def strict_non_negative_int(value: Any) -> int | None:
    if type(value) is not int or value < 0:
        return None
    return value


def strict_opportunity_deferred_reason_code(value: Any) -> bool:
    return isinstance(value, str) and value in OPPORTUNITY_DEFERRED_REASON_CODES_V1


def proof_safe_opportunity_deferred_reason_count(value: Any, count: Any) -> bool:
    if type(count) is not int or count < 0:
        return False
    if strict_opportunity_deferred_reason_code(value):
        return True
    return value == LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE and count == 0


def strict_iso_instant(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        return parsed.tzinfo is not None
    except ValueError:
        return False


def required_decimal(container: dict[str, Any], field: str, label: str, path: str) -> Decimal:
    if field not in container:
        fail(f"{label}: {path}.{field} is required")
    try:
        return as_decimal(container.get(field))
    except AssertionError:
        fail(f"{label}: {path}.{field} must be decimal-compatible")


def event_payload(event: dict[str, Any]) -> dict[str, Any]:
    payload = event.get("payload")
    if isinstance(payload, dict):
        merged = dict(payload)
        if "eventType" not in merged and event.get("eventType"):
            merged["eventType"] = event.get("eventType")
        return merged
    return event


def event_payloads(response: dict[str, Any]) -> list[dict[str, Any]]:
    events = response.get("result", {}).get("evidenceEvents", [])
    if not isinstance(events, list):
        return []
    return [event_payload(event) for event in events if isinstance(event, dict)]


def load_report_summary(path: str) -> dict[str, Any]:
    report_path = Path(path)
    try:
        payload = json.loads(report_path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"unable to read report summary {report_path}: {error}")
        raise AssertionError("unreachable")
    except json.JSONDecodeError as error:
        fail(f"report summary {report_path} is not valid JSON: {error}")
        raise AssertionError("unreachable")

    if payload.get("schemaVersion") == "policy_wind_tunnel_summary.v1":
        return payload
    nested_summary = payload.get("summary")
    if isinstance(nested_summary, dict) and nested_summary.get("schemaVersion") == "policy_wind_tunnel_summary.v1":
        return nested_summary
    fail(f"report summary {report_path} is not a policy_wind_tunnel_summary.v1 document or bundle")
    raise AssertionError("unreachable")


def report_run_config(summary: dict[str, Any]) -> dict[str, Any]:
    value = summary.get("runConfig")
    return value if isinstance(value, dict) else {}


def require_event_driven_opportunity_proof(
        label: str,
        agentic: dict[str, Any],
        hybrid_agent_audit: dict[str, Any]) -> dict[str, Any]:
    require_report(
        agentic.get("decisionOpportunitySource") == "CUSTOMER_DECISION_OPPORTUNITY_LEDGER",
        label,
        "finalized parity proof must use the customer decision opportunity ledger",
    )
    reconciliation = agentic.get("opportunityReconciliation")
    require_report(isinstance(reconciliation, dict), label,
                   "agenticSimulation.opportunityReconciliation must be an object")
    require_report(
        reconciliation.get("schemaVersion") == OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION,
        label,
        f"opportunityReconciliation schemaVersion must be {OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION}",
    )
    run_id = str(reconciliation.get("simulationRunId", "")).strip()
    require_report(bool(run_id), label, "opportunityReconciliation.simulationRunId is required")
    require_report(reconciliation.get("generationMode") == OPPORTUNITY_GENERATION_MODE, label,
                   f"opportunityReconciliation.generationMode must be {OPPORTUNITY_GENERATION_MODE}")
    require_report(reconciliation.get("status") == "RECONCILED", label,
                   "finalized parity proof requires RECONCILED opportunity status")
    require_report(reconciliation.get("ledgerReconciled") is True, label,
                   "opportunityReconciliation.ledgerReconciled must be true")
    require_report(reconciliation.get("generationSealed") is True, label,
                   "opportunity generation must be sealed")
    for field in ("generationObservedAt", "generationSealedAt", "asOf"):
        require_report(strict_iso_instant(reconciliation.get(field)), label,
                       f"opportunityReconciliation.{field} must be an ISO-8601 instant with an offset")
    sealed_version = strict_non_negative_int(reconciliation.get("generationSealedReplayContractVersion"))
    require_report(sealed_version is not None and sealed_version > 0, label,
                   "generationSealedReplayContractVersion must be positive")
    fence_digest = str(reconciliation.get("generationSealedReplayFenceDigest", "")).strip()
    require_report(
        len(fence_digest) == 64 and all(character in "0123456789abcdef" for character in fence_digest),
        label,
        "generationSealedReplayFenceDigest must be a lowercase SHA-256 digest",
    )
    require_report(bool(str(reconciliation.get("generationSealedReplayFenceLeaseOwner", "")).strip()), label,
                   "generationSealedReplayFenceLeaseOwner is required")
    fence_expiry = strict_non_negative_int(reconciliation.get("generationSealedReplayFenceExpiresAtEpochMs"))
    require_report(fence_expiry is not None and fence_expiry > 0, label,
                   "generationSealedReplayFenceExpiresAtEpochMs must be positive")

    numeric_fields = (
        "observedWorkWindows",
        "expectedGeneratedOpportunities",
        "rejectedTriggers",
        "migrationAnomalies",
        "generatedOpportunities",
        "terminalOpportunities",
        "remainingBacklog",
        "validDecisions",
        "recoverableRecordedDecisions",
        "succeededWithoutDecisionEvidence",
        "decisionEvidenceGaps",
    )
    counters: dict[str, int] = {}
    for field in numeric_fields:
        value = strict_non_negative_int(reconciliation.get(field))
        require_report(value is not None, label,
                       f"opportunityReconciliation.{field} must be a non-negative integer")
        counters[field] = value
    require_report(counters["observedWorkWindows"] > 0, label,
                   "opportunity reconciliation must include an observed domain work window")
    require_report(counters["expectedGeneratedOpportunities"] > 0, label,
                   "finalized parity proof requires at least one domain-generated decision opportunity")
    require_report(counters["rejectedTriggers"] == 0, label,
                   "finalized parity proof cannot contain rejected decision triggers")
    require_report(counters["migrationAnomalies"] == 0, label,
                   "finalized parity proof cannot contain opportunity migration anomalies")

    raw_status_counts = reconciliation.get("statusCounts")
    require_report(isinstance(raw_status_counts, dict), label,
                   "opportunityReconciliation.statusCounts must be an object")
    require_report(set(raw_status_counts) == OPPORTUNITY_LIFECYCLE_STATUSES, label,
                   "opportunityReconciliation.statusCounts must contain every lifecycle status exactly once")
    status_counts: dict[str, int] = {}
    for status in OPPORTUNITY_LIFECYCLE_STATUSES:
        value = strict_non_negative_int(raw_status_counts.get(status))
        require_report(value is not None, label,
                       f"opportunityReconciliation.statusCounts.{status} must be a non-negative integer")
        status_counts[status] = value

    generated = counters["generatedOpportunities"]
    terminal = counters["terminalOpportunities"]
    backlog = counters["remainingBacklog"]
    valid_decisions = counters["validDecisions"]
    succeeded_without_evidence = counters["succeededWithoutDecisionEvidence"]
    evidence_gaps = counters["decisionEvidenceGaps"]
    require_report(generated == sum(status_counts.values()), label,
                   "generatedOpportunities must equal the lifecycle status total")
    require_report(
        backlog == status_counts["PENDING"] + status_counts["RUNNING"] + status_counts["DEFERRED"],
        label,
        "remainingBacklog must equal pending + running + deferred opportunities",
    )
    require_report(terminal == generated - backlog, label,
                   "terminalOpportunities must equal generatedOpportunities - remainingBacklog")
    require_report(valid_decisions == status_counts["SUCCEEDED"] - succeeded_without_evidence, label,
                   "validDecisions must equal succeeded opportunities with valid decision evidence")
    require_report(
        evidence_gaps
        == status_counts["BLOCKED"]
        + status_counts["FAILED"]
        + status_counts["CANCELLED"]
        + succeeded_without_evidence,
        label,
        "decisionEvidenceGaps must account for every terminal non-decision outcome",
    )
    require_report(counters["recoverableRecordedDecisions"] <= backlog, label,
                   "recoverableRecordedDecisions cannot exceed remainingBacklog")
    require_report(counters["expectedGeneratedOpportunities"] == generated, label,
                   "expectedGeneratedOpportunities must equal the sealed generated watermark")
    require_report(backlog == 0 and terminal == generated, label,
                   "finalized parity proof requires an empty opportunity backlog")
    require_report(evidence_gaps == 0 and succeeded_without_evidence == 0, label,
                   "finalized parity proof cannot contain decision evidence gaps")
    require_report(valid_decisions == generated, label,
                   "every reconciled parity opportunity must have a valid customer decision")

    agentic_count_fields = (
        "expectedDecisionOpportunities",
        "observedDecisionOpportunities",
        "admittedDecisionOpportunities",
        "deferredDecisionOpportunities",
        "actualDecisionCount",
        "auditedAgentCount",
        "opportunityTerminalCount",
        "opportunityRemainingBacklog",
        "opportunityTotalAttempts",
        "opportunityDecisionEvidenceGaps",
    )
    agentic_counts: dict[str, int] = {}
    for field in agentic_count_fields:
        value = strict_non_negative_int(agentic.get(field))
        require_report(value is not None, label,
                       f"agenticSimulation.{field} must be a non-negative integer")
        agentic_counts[field] = value
    expected = agentic_counts["expectedDecisionOpportunities"]
    observed = agentic_counts["observedDecisionOpportunities"]
    admitted = agentic_counts["admittedDecisionOpportunities"]
    deferred = agentic_counts["deferredDecisionOpportunities"]
    actual = agentic_counts["actualDecisionCount"]
    audited_agents = agentic_counts["auditedAgentCount"]
    opportunity_terminal = agentic_counts["opportunityTerminalCount"]
    opportunity_backlog = agentic_counts["opportunityRemainingBacklog"]
    opportunity_attempts = agentic_counts["opportunityTotalAttempts"]
    opportunity_gaps = agentic_counts["opportunityDecisionEvidenceGaps"]
    require_report(expected == counters["expectedGeneratedOpportunities"], label,
                   "agentic expectedDecisionOpportunities must match reconciliation")
    require_report(observed == generated, label,
                   "agentic observedDecisionOpportunities must match reconciliation")
    require_report(actual == valid_decisions, label,
                   "agentic actualDecisionCount must match reconciliation")
    require_report(opportunity_terminal == terminal and opportunity_backlog == backlog, label,
                   "agentic terminal/backlog opportunity counts must match reconciliation")
    require_report(opportunity_gaps == evidence_gaps, label,
                   "agentic opportunityDecisionEvidenceGaps must match reconciliation")
    require_report(agentic.get("opportunityStatusCounts") == raw_status_counts, label,
                   "agentic opportunityStatusCounts must match reconciliation")
    require_report(agentic.get("opportunityAdmissionCountVerified") is True, label,
                   "per-agent opportunity admission totals must be verified")
    require_report(admitted == generated, label,
                   "every reconciled parity opportunity must have been admitted")
    require_report(opportunity_attempts >= admitted, label,
                   "opportunityTotalAttempts cannot be smaller than admitted opportunities")
    require_report(deferred == status_counts["DEFERRED"], label,
                   "deferredDecisionOpportunities must match DEFERRED lifecycle count")
    deferred_reasons = agentic.get("deferredDecisionReasons")
    require_report(isinstance(deferred_reasons, dict), label,
                   "deferredDecisionReasons must be an object")
    deferred_reason_total = 0
    for reason, raw_count in deferred_reasons.items():
        reason_count = strict_non_negative_int(raw_count)
        require_report(proof_safe_opportunity_deferred_reason_count(reason, reason_count), label,
                       "deferredDecisionReasons must use DEFERRED lifecycle reason codes and non-negative integer counts")
        deferred_reason_total += reason_count
    require_report(deferred_reason_total == deferred, label,
                   "deferredDecisionReasons must sum to deferredDecisionOpportunities")
    historical_deferrals = strict_non_negative_int(agentic.get("historicalDeferredDecisionTransitions"))
    require_report(historical_deferrals is not None, label,
                   "historicalDeferredDecisionTransitions must be a non-negative integer")
    historical_deferral_reasons = agentic.get("historicalDeferredDecisionReasons")
    require_report(isinstance(historical_deferral_reasons, dict), label,
                   "historicalDeferredDecisionReasons must be an object")
    historical_reason_total = 0
    for reason, raw_count in historical_deferral_reasons.items():
        reason_count = strict_non_negative_int(raw_count)
        require_report(proof_safe_opportunity_deferred_reason_count(reason, reason_count), label,
                       "historicalDeferredDecisionReasons must use DEFERRED lifecycle reason codes and non-negative counts")
        historical_reason_total += reason_count
    require_report(historical_reason_total == historical_deferrals, label,
                   "historicalDeferredDecisionReasons must sum to historicalDeferredDecisionTransitions")
    require_report(audited_agents > 0, label,
                   "finalized parity proof requires per-agent opportunity audit coverage")
    require_report(agentic.get("decisionCoverageStatus") == "CAPTURED", label,
                   "fully reconciled event-driven proof must report CAPTURED")
    explanation = str(agentic.get("decisionCoverageExplanation", ""))
    require_report("event-driven" in explanation and "durable" in explanation, label,
                   "decisionCoverageExplanation must identify durable event-driven opportunity evidence")
    for field in ("decisionCoverageRate", "decisionOpportunityCoverageRate", "decisionAdmissionRate"):
        require_report(required_decimal(agentic, field, label, "agenticSimulation") == Decimal("1"), label,
                       f"agenticSimulation.{field} must be 1 for reconciled parity proof")

    opportunity_ledger = hybrid_agent_audit.get("opportunityLedger")
    require_report(isinstance(opportunity_ledger, dict), label,
                   "hybridAgentAudit.opportunityLedger must be an object")
    require_report(opportunity_ledger.get("schemaVersion") == OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION, label,
                   f"hybrid opportunity ledger schemaVersion must be {OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION}")
    require_report(opportunity_ledger.get("consistentRunScope") is True, label,
                   "hybrid opportunity ledger run scope must be consistent")
    require_report(strict_non_negative_int(opportunity_ledger.get("invalidAgentAuditCount")) == 0, label,
                   "hybrid opportunity ledger cannot contain invalid agent audits")
    require_report(opportunity_ledger.get("simulationRunIds") == [run_id], label,
                   "hybrid opportunity ledger must be scoped to the reconciled simulation run")
    ledger_matches = {
        "generatedOpportunities": generated,
        "terminalOpportunities": terminal,
        "remainingBacklog": backlog,
        "admittedOpportunities": admitted,
        "totalAttempts": opportunity_attempts,
        "validDecisions": valid_decisions,
        "recoverableRecordedDecisions": counters["recoverableRecordedDecisions"],
        "succeededWithoutDecisionEvidence": succeeded_without_evidence,
        "decisionEvidenceGaps": evidence_gaps,
    }
    for field, expected_value in ledger_matches.items():
        require_report(strict_non_negative_int(opportunity_ledger.get(field)) == expected_value, label,
                       f"hybrid opportunity ledger {field} must match reconciliation")
    require_report(strict_non_negative_int(opportunity_ledger.get("agentCount")) == audited_agents, label,
                   "auditedAgentCount must match the hybrid opportunity ledger")
    require_report(opportunity_ledger.get("statusCounts") == raw_status_counts, label,
                   "hybrid opportunity status counts must match reconciliation")
    require_report(opportunity_ledger.get("deferredReasonCounts") == deferred_reasons, label,
                   "hybrid deferred reason counts must match agenticSimulation")
    require_report(strict_non_negative_int(opportunity_ledger.get("historicalDeferralCount"))
                   == historical_deferrals, label,
                   "hybrid historical deferral count must match agenticSimulation")
    require_report(opportunity_ledger.get("historicalDeferralReasonCounts")
                   == historical_deferral_reasons, label,
                   "hybrid historical deferral reasons must match agenticSimulation")

    raw_agents = hybrid_agent_audit.get("agents")
    require_report(isinstance(raw_agents, list), label,
                   "hybridAgentAudit.agents must be a complete list")
    audit_counter_fields = (
        "generatedOpportunities",
        "terminalOpportunities",
        "remainingBacklog",
        "admittedOpportunities",
        "totalAttempts",
        "validDecisions",
        "recoverableRecordedDecisions",
        "succeededWithoutDecisionEvidence",
        "decisionEvidenceGaps",
        "historicalDeferralCount",
    )
    aggregated_agent_counts = {field: 0 for field in audit_counter_fields}
    aggregated_status_counts = {status: 0 for status in raw_status_counts}
    aggregated_deferred_reasons: dict[str, int] = {}
    aggregated_historical_reasons: dict[str, int] = {}
    audited_agent_ids: set[str] = set()
    for index, raw_agent in enumerate(raw_agents):
        require_report(isinstance(raw_agent, dict), label,
                       f"hybridAgentAudit.agents[{index}] must be an object")
        if "opportunityAudit" not in raw_agent:
            continue
        audit = raw_agent.get("opportunityAudit")
        require_report(isinstance(audit, dict), label,
                       f"hybridAgentAudit.agents[{index}].opportunityAudit must be an object")
        require_report(audit.get("schemaVersion") == "customer_decision_opportunity_agent_audit.v1", label,
                       f"hybridAgentAudit.agents[{index}] has an invalid opportunity audit schema")
        require_report(audit.get("simulationRunId") == run_id, label,
                       f"hybridAgentAudit.agents[{index}] has the wrong simulationRunId")
        agent_id = str(audit.get("agentId", "")).strip()
        require_report(bool(agent_id) and agent_id not in audited_agent_ids, label,
                       f"hybridAgentAudit.agents[{index}] has a blank or duplicate agentId")
        audited_agent_ids.add(agent_id)
        for field in audit_counter_fields:
            count = strict_non_negative_int(audit.get(field))
            require_report(count is not None, label,
                           f"hybridAgentAudit.agents[{index}].{field} must be non-negative")
            aggregated_agent_counts[field] += count
        agent_status_counts = audit.get("statusCounts")
        require_report(isinstance(agent_status_counts, dict)
                       and set(agent_status_counts) == set(raw_status_counts), label,
                       f"hybridAgentAudit.agents[{index}].statusCounts is incomplete")
        for status in raw_status_counts:
            count = strict_non_negative_int(agent_status_counts.get(status))
            require_report(count is not None, label,
                           f"hybridAgentAudit.agents[{index}].statusCounts.{status} must be non-negative")
            aggregated_status_counts[status] += count
        for field, aggregate in (
            ("deferredReasonCounts", aggregated_deferred_reasons),
            ("historicalDeferralReasonCounts", aggregated_historical_reasons),
        ):
            reason_counts = audit.get(field)
            require_report(isinstance(reason_counts, dict), label,
                           f"hybridAgentAudit.agents[{index}].{field} must be an object")
            for reason, raw_count in reason_counts.items():
                count = strict_non_negative_int(raw_count)
                require_report(proof_safe_opportunity_deferred_reason_count(reason, count), label,
                               f"hybridAgentAudit.agents[{index}].{field} contains an invalid reason or count")
                aggregate[reason] = aggregate.get(reason, 0) + count
    require_report(len(audited_agent_ids) == audited_agents, label,
                   "hybridAgentAudit.agents is truncated or does not match auditedAgentCount")
    for field, count in aggregated_agent_counts.items():
        require_report(count == strict_non_negative_int(opportunity_ledger.get(field)), label,
                       f"per-agent {field} does not sum to the hybrid opportunity ledger")
    require_report(aggregated_status_counts == raw_status_counts, label,
                   "per-agent statusCounts do not sum to reconciliation")
    require_report(aggregated_deferred_reasons == deferred_reasons, label,
                   "per-agent deferredReasonCounts do not sum to the hybrid opportunity ledger")
    require_report(aggregated_historical_reasons == historical_deferral_reasons, label,
                   "per-agent historicalDeferralReasonCounts do not sum to the hybrid opportunity ledger")

    return {
        "simulationRunId": run_id,
        "generationMode": reconciliation.get("generationMode"),
        "reconciliationStatus": reconciliation.get("status"),
        "generatedOpportunities": generated,
        "terminalOpportunities": terminal,
        "remainingBacklog": backlog,
        "validDecisions": valid_decisions,
        "admittedOpportunities": admitted,
        "totalAttempts": opportunity_attempts,
        "auditedAgentCount": audited_agents,
        "statusCounts": dict(raw_status_counts),
        "deferredReasonCounts": dict(deferred_reasons),
        "historicalDeferralCount": historical_deferrals,
        "historicalDeferralReasonCounts": dict(historical_deferral_reasons),
    }


def require_agentic_report_summary(label: str, summary: dict[str, Any]) -> dict[str, Any]:
    require_report(summary.get("schemaVersion") == "policy_wind_tunnel_summary.v1", label,
                   "summary schemaVersion must be policy_wind_tunnel_summary.v1")
    missing_sections = {section for section in REQUIRED_REPORT_SECTIONS if section not in summary}
    require_report(not missing_sections, label, f"summary missing sections: {sorted(missing_sections)}")
    require_report(summary.get("summaryMode") == "CLOCK_DRIVEN_HYBRID_FINALIZED", label,
                   "summaryMode must be CLOCK_DRIVEN_HYBRID_FINALIZED")

    run_config = report_run_config(summary)
    agentic = summary.get("agenticSimulation")
    require_report(isinstance(agentic, dict), label, "agenticSimulation must be an object")
    missing_agentic = {field for field in REQUIRED_AGENTIC_SUMMARY_FIELDS if field not in agentic}
    require_report(not missing_agentic, label, f"agenticSimulation missing fields: {sorted(missing_agentic)}")
    require_report(agentic.get("schemaVersion") == "agentic_customer_simulation_summary.v1", label,
                   "agenticSimulation schemaVersion must be agentic_customer_simulation_summary.v1")
    require_report(agentic.get("executionMode") == "CLOCK_DRIVEN_HYBRID", label,
                   "agenticSimulation executionMode must be CLOCK_DRIVEN_HYBRID")
    require_report(agentic.get("agentDecisionMode") == "STUB_LLM", label,
                   "agenticSimulation agentDecisionMode must be STUB_LLM for no-token parity proof")

    cadence = str(agentic.get("decisionCadence", "")).strip().upper()
    cohort_size = int_value(agentic.get("cohortSize"))
    horizon_days = int_value(agentic.get("simulationHorizonDays"))
    interval_minutes = int_value(agentic.get("decisionIntervalMinutes"))
    expected = int_value(agentic.get("expectedDecisionOpportunities"))
    audited_agents = int_value(agentic.get("auditedAgentCount"))
    configured_duration = required_decimal(agentic, "configuredDurationDays", label, "agenticSimulation")
    actual_duration = required_decimal(agentic, "actualDurationDays", label, "agenticSimulation")
    overshoot_duration = required_decimal(agentic, "horizonOvershootDays", label, "agenticSimulation")
    duration_status = str(agentic.get("durationStatus", "")).strip().upper()

    require_report(cohort_size > 0, label, "cohortSize must be positive")
    require_report(horizon_days > 0, label, "simulationHorizonDays must be positive")
    require_report(bool(cadence), label, "decisionCadence must be present as run metadata")
    require_report(interval_minutes >= 0, label, "decisionIntervalMinutes must be non-negative")
    require_report(configured_duration > 0, label, "configuredDurationDays must be positive")
    require_report(actual_duration >= 0, label, "actualDurationDays must be non-negative")
    require_report(overshoot_duration >= 0, label, "horizonOvershootDays must be non-negative")
    require_report(duration_status in VALID_DURATION_STATUSES, label,
                   f"durationStatus must be one of {sorted(VALID_DURATION_STATUSES)}")
    require_duration_math(
        label,
        "agenticSimulation",
        configured_duration,
        actual_duration,
        overshoot_duration,
        duration_status,
    )
    require_report(cohort_size >= MIN_AGENTIC_ACCEPTANCE_COHORT_SIZE, label,
                   "finalized STUB_LLM parity requires at least "
                   f"{MIN_AGENTIC_ACCEPTANCE_COHORT_SIZE} customers")
    require_report(configured_duration >= MIN_AGENTIC_ACCEPTANCE_DURATION_DAYS, label,
                   "finalized STUB_LLM parity requires at least "
                   f"{MIN_AGENTIC_ACCEPTANCE_DURATION_DAYS} configured simulated days")
    require_report(
        actual_duration + MIN_AGENTIC_ACCEPTANCE_DURATION_TOLERANCE_DAYS >= configured_duration,
        label,
        "finalized STUB_LLM parity requires actualDurationDays to reach configuredDurationDays "
        f"within {MIN_AGENTIC_ACCEPTANCE_DURATION_TOLERANCE_DAYS} days: "
        f"configured={configured_duration} actual={actual_duration}",
    )
    require_report(horizon_days >= int(MIN_AGENTIC_ACCEPTANCE_DURATION_DAYS), label,
                   "finalized STUB_LLM parity requires at least "
                   f"{MIN_AGENTIC_ACCEPTANCE_DURATION_DAYS} simulation horizon days")
    integrity_warnings = agentic.get("evidenceIntegrityWarnings")
    require_report(isinstance(integrity_warnings, list), label,
                   "evidenceIntegrityWarnings must be a list")
    require_report(not integrity_warnings, label,
                   f"agenticSimulation has evidence-integrity warnings: {integrity_warnings}")
    hybrid_agent_audit = summary.get("hybridAgentAudit")
    require_report(isinstance(hybrid_agent_audit, dict), label,
                   "hybridAgentAudit must be an object")
    runtime_tick_drain = hybrid_agent_audit.get("runtimeTickDrain")
    require_report(isinstance(runtime_tick_drain, dict), label,
                   "hybridAgentAudit.runtimeTickDrain must be an object")
    require_report(runtime_tick_drain.get("verified") is True, label,
                   f"hybridAgentAudit.runtimeTickDrain.verified must be true: {runtime_tick_drain}")
    require_report(str(runtime_tick_drain.get("status", "")).strip().upper() == "DRAINED", label,
                   f"hybridAgentAudit.runtimeTickDrain.status must be DRAINED: {runtime_tick_drain}")
    opportunity_proof = require_event_driven_opportunity_proof(label, agentic, hybrid_agent_audit)
    require_report(0 < audited_agents <= cohort_size, label, "auditedAgentCount must be within cohort size")

    timeline = summary.get("simulationTimeline")
    require_report(isinstance(timeline, dict), label, "simulationTimeline must be an object")
    run_config_configured_duration = required_decimal(run_config, "configuredDurationDays", label, "runConfig")
    run_config_actual_duration = required_decimal(run_config, "actualDurationDays", label, "runConfig")
    run_config_overshoot_duration = required_decimal(run_config, "horizonOvershootDays", label, "runConfig")
    timeline_configured_duration = required_decimal(timeline, "configuredDurationDays", label, "simulationTimeline")
    timeline_actual_duration = required_decimal(timeline, "actualDurationDays", label, "simulationTimeline")
    timeline_overshoot_duration = required_decimal(timeline, "horizonOvershootDays", label, "simulationTimeline")
    timeline_duration_status = str(timeline.get("durationStatus", "")).strip().upper()
    require_report(run_config_configured_duration == configured_duration, label,
                   "runConfig.configuredDurationDays must match agenticSimulation.configuredDurationDays")
    require_report(run_config_actual_duration == actual_duration, label,
                   "runConfig.actualDurationDays must match agenticSimulation.actualDurationDays")
    require_report(run_config_overshoot_duration == overshoot_duration, label,
                   "runConfig.horizonOvershootDays must match agenticSimulation.horizonOvershootDays")
    require_report(str(run_config.get("durationStatus", "")).strip().upper() == duration_status, label,
                   "runConfig.durationStatus must match agenticSimulation.durationStatus")
    require_report(timeline_configured_duration == configured_duration, label,
                   "simulationTimeline.configuredDurationDays must match agenticSimulation.configuredDurationDays")
    require_report(timeline_actual_duration == actual_duration, label,
                   "simulationTimeline.actualDurationDays must match agenticSimulation.actualDurationDays")
    require_report(timeline_overshoot_duration == overshoot_duration, label,
                   "simulationTimeline.horizonOvershootDays must match agenticSimulation.horizonOvershootDays")
    require_report(timeline_duration_status == duration_status, label,
                   "simulationTimeline.durationStatus must match agenticSimulation.durationStatus")

    evidence_refs = agentic.get("evidenceRefs")
    require_report(isinstance(evidence_refs, dict), label, "agenticSimulation evidenceRefs must be an object")
    require_report("hybridAgentAuditJson" in evidence_refs, label,
                   "agenticSimulation evidenceRefs missing hybridAgentAuditJson")
    require_report("simulationRunEvidencePack" in evidence_refs, label,
                   "agenticSimulation evidenceRefs missing simulationRunEvidencePack")

    scorecards = summary.get("domainOutcomeScores")
    require_report(isinstance(scorecards, list) and scorecards, label,
                   "domainOutcomeScores must contain at least one scored scorecard")
    for index, score in enumerate(scorecards):
        require_report(isinstance(score, dict), label, f"domainOutcomeScores[{index}] must be an object")
        require_report(score.get("schemaVersion") == "domain_outcome_score.v1", label,
                       f"domainOutcomeScores[{index}] schemaVersion must be domain_outcome_score.v1")
        require_report(score.get("status") == "SCORED", label,
                       f"domainOutcomeScores[{index}] status must be SCORED")
        require_report(score.get("recommendation") in VALID_RECOMMENDATIONS, label,
                       f"domainOutcomeScores[{index}] recommendation is not recognized")

    return {
        "summaryMode": summary.get("summaryMode"),
        "executionMode": agentic.get("executionMode"),
        "agentDecisionMode": agentic.get("agentDecisionMode"),
        "decisionCadence": cadence,
        "decisionIntervalMinutes": interval_minutes,
        "simulationHorizonDays": horizon_days,
        "cohortSize": cohort_size,
        "expectedDecisionOpportunities": expected,
        "configuredDurationDays": configured_duration,
        "actualDurationDays": actual_duration,
        "horizonOvershootDays": overshoot_duration,
        "durationStatus": duration_status,
        "decisionOpportunitySource": agentic.get("decisionOpportunitySource"),
        "opportunityProof": opportunity_proof,
        "runConfigExecutionMode": run_config.get("executionMode"),
        "runConfigAgentDecisionMode": run_config.get("agentDecisionMode"),
    }


def require_duration_math(
        label: str,
        section: str,
        configured_duration: Decimal,
        actual_duration: Decimal,
        overshoot_duration: Decimal,
        duration_status: str) -> None:
    expected_overshoot = max(Decimal("0"), actual_duration - configured_duration)
    require_report(
        abs(overshoot_duration - expected_overshoot) <= DURATION_MATH_TOLERANCE_DAYS,
        label,
        f"{section}.horizonOvershootDays must equal max(0, actualDurationDays - configuredDurationDays): "
        f"configured={configured_duration} actual={actual_duration} "
        f"overshoot={overshoot_duration} expected={expected_overshoot}",
    )
    expected_status = (
        "HORIZON_OVERRUN"
        if expected_overshoot > DURATION_OVERRUN_THRESHOLD_DAYS
        else "WITHIN_HORIZON"
    )
    require_report(
        duration_status == expected_status,
        label,
        f"{section}.durationStatus must be {expected_status} for overshoot {expected_overshoot}",
    )


def compare_agentic_report_summaries(
        java_summary: dict[str, Any],
        python_summary: dict[str, Any],
        *,
        java_summary_path: str | None = None,
        python_summary_path: str | None = None) -> dict[str, Any]:
    java_signature = require_agentic_report_summary("java-agentic-report", java_summary)
    python_signature = require_agentic_report_summary("python-agentic-report", python_summary)
    cohort_parity = compare_cohort_contracts(
        java_summary,
        python_summary,
        java_summary_path=java_summary_path,
        python_summary_path=python_summary_path,
    )
    comparable_fields = [
        "summaryMode",
        "executionMode",
        "agentDecisionMode",
        "decisionCadence",
        "decisionIntervalMinutes",
        "simulationHorizonDays",
        "cohortSize",
        "expectedDecisionOpportunities",
        "configuredDurationDays",
        "durationStatus",
        "decisionOpportunitySource",
    ]
    mismatches = {
        field: {"java": java_signature[field], "python": python_signature[field]}
        for field in comparable_fields
        if java_signature[field] != python_signature[field]
    }
    if mismatches:
        fail(f"Java/Python agentic report configuration differs: {json.dumps(mismatches, sort_keys=True, default=str)}")
    opportunity_fields = [
        "generationMode",
        "reconciliationStatus",
        "generatedOpportunities",
        "terminalOpportunities",
        "remainingBacklog",
        "validDecisions",
        "admittedOpportunities",
        "totalAttempts",
        "auditedAgentCount",
        "statusCounts",
        "deferredReasonCounts",
    ]
    opportunity_mismatches = {
        field: {
            "java": java_signature["opportunityProof"][field],
            "python": python_signature["opportunityProof"][field],
        }
        for field in opportunity_fields
        if java_signature["opportunityProof"][field] != python_signature["opportunityProof"][field]
    }
    if opportunity_mismatches:
        fail(
            "Java/Python event-driven opportunity proof differs: "
            + json.dumps(opportunity_mismatches, sort_keys=True, default=str)
        )
    java_agentic = java_summary["agenticSimulation"]
    python_agentic = python_summary["agenticSimulation"]
    compare_duration_field("actualDurationDays", java_signature, python_signature)
    compare_duration_field("horizonOvershootDays", java_signature, python_signature)
    scorecard_metric_parity = compare_scorecards(java_summary, python_summary)
    return {
        "schemaVersion": "banking_agentic_report_parity.v1",
        "sharedConfiguration": {field: parity_json_value(java_signature[field]) for field in comparable_fields},
        "opportunityProof": {
            field: parity_json_value(java_signature["opportunityProof"][field])
            for field in opportunity_fields
        },
        "cohort": cohort_parity,
        "java": {
            "actualDecisionCount": int_value(java_summary["agenticSimulation"].get("actualDecisionCount")),
            "observedDecisionOpportunities": int_value(java_summary["agenticSimulation"].get("observedDecisionOpportunities")),
            "admittedDecisionOpportunities": int_value(java_summary["agenticSimulation"].get("admittedDecisionOpportunities")),
            "actualDurationDays": decimal_to_json(java_signature["actualDurationDays"]),
            "horizonOvershootDays": decimal_to_json(java_signature["horizonOvershootDays"]),
            "scorecardCount": len(java_summary.get("domainOutcomeScores", [])),
        },
        "python": {
            "actualDecisionCount": int_value(python_summary["agenticSimulation"].get("actualDecisionCount")),
            "observedDecisionOpportunities": int_value(python_summary["agenticSimulation"].get("observedDecisionOpportunities")),
            "admittedDecisionOpportunities": int_value(python_summary["agenticSimulation"].get("admittedDecisionOpportunities")),
            "actualDurationDays": decimal_to_json(python_signature["actualDurationDays"]),
            "horizonOvershootDays": decimal_to_json(python_signature["horizonOvershootDays"]),
            "scorecardCount": len(python_summary.get("domainOutcomeScores", [])),
        },
        "scorecardMetrics": scorecard_metric_parity,
    }


def compare_cohort_contracts(
        java_summary: dict[str, Any],
        python_summary: dict[str, Any],
        *,
        java_summary_path: str | None = None,
        python_summary_path: str | None = None) -> dict[str, Any] | None:
    if not java_summary_path and not python_summary_path:
        return None
    require_report(bool(java_summary_path), "java-agentic-report", "summary path is required for cohort parity")
    require_report(bool(python_summary_path), "python-agentic-report", "summary path is required for cohort parity")
    java_cohort_id = summary_cohort_id(java_summary)
    python_cohort_id = summary_cohort_id(python_summary)
    if not java_cohort_id and not python_cohort_id:
        return None
    require_report(bool(java_cohort_id), "java-agentic-report", "runConfig.cohortId is required for cohort parity")
    require_report(bool(python_cohort_id), "python-agentic-report", "runConfig.cohortId is required for cohort parity")

    java_bundle = load_generated_cohort_bundle("java-agentic-report", java_cohort_id, java_summary_path)
    python_bundle = load_generated_cohort_bundle("python-agentic-report", python_cohort_id, python_summary_path)
    java_signature = cohort_contract_signature(java_bundle)
    python_signature = cohort_contract_signature(python_bundle)
    mismatches = {
        field: {"java": java_signature[field], "python": python_signature[field]}
        for field in java_signature
        if java_signature[field] != python_signature.get(field)
    }
    if mismatches:
        fail(f"Java/Python generated cohort contract differs: {json.dumps(mismatches, sort_keys=True)}")
    return {
        "javaCohortId": java_cohort_id,
        "pythonCohortId": python_cohort_id,
        "recipeId": java_signature["recipeId"],
        "seed": java_signature["seed"],
        "targetSize": java_signature["targetSize"],
        "scheduledWorkItems": java_signature["scheduledWorkItems"],
        "customerFingerprint": java_signature["customerFingerprint"],
    }


def summary_cohort_id(summary: dict[str, Any]) -> str:
    run_config = report_run_config(summary)
    return str(run_config.get("cohortId", "")).strip()


def load_generated_cohort_bundle(label: str, cohort_id: str, summary_path: str | None) -> dict[str, Any]:
    root = workspace_root_for_summary(summary_path)
    path = root / "output" / "cohorts" / safe_cohort_id(cohort_id) / "bundle.json"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        fail(f"{label}: unable to read generated cohort bundle {path}: {error}")
    except json.JSONDecodeError as error:
        fail(f"{label}: generated cohort bundle {path} is invalid JSON: {error}")
    require_report(isinstance(payload, dict), label, "generated cohort bundle must be an object")
    return payload


def workspace_root_for_summary(summary_path: str | None) -> Path:
    candidates: list[Path] = []
    if summary_path:
        start = Path(summary_path).resolve()
        candidates.extend([start.parent, *start.parents])
    cwd = Path.cwd().resolve()
    candidates.extend([cwd, *cwd.parents])
    for candidate in candidates:
        if (candidate / "output" / "cohorts").exists():
            return candidate
    return cwd


def safe_cohort_id(cohort_id: str) -> str:
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
    if (
            not cohort_id
            or ".." in cohort_id
            or "/" in cohort_id
            or "\\" in cohort_id
            or any(ch not in allowed for ch in cohort_id)):
        fail(f"invalid cohort id in report: {cohort_id!r}")
    return cohort_id


def cohort_contract_signature(bundle: dict[str, Any]) -> dict[str, Any]:
    recipe = bundle.get("recipe")
    summary = bundle.get("summary")
    customers = bundle.get("customers")
    require_report(isinstance(recipe, dict), "cohort-contract", "bundle.recipe must be an object")
    require_report(isinstance(summary, dict), "cohort-contract", "bundle.summary must be an object")
    require_report(isinstance(customers, list), "cohort-contract", "bundle.customers must be an array")
    canonical_customers = [
        canonical_customer(customer)
        for customer in customers
        if isinstance(customer, dict)
    ]
    return {
        "recipeId": recipe.get("recipeId"),
        "seed": recipe.get("seed"),
        "targetSize": recipe.get("targetSize"),
        "segmentMix": canonical_json(recipe.get("segmentMix")),
        "generationControls": canonical_json(recipe.get("generationControls")),
        "scheduledWorkItems": summary.get("scheduledWorkItems"),
        "segmentCounts": canonical_json(summary.get("segmentCounts")),
        "archetypeCounts": canonical_json(summary.get("archetypeCounts")),
        "customerCount": len(canonical_customers),
        "customerFingerprint": hashlib.sha256(canonical_json(canonical_customers).encode("utf-8")).hexdigest(),
    }


def canonical_customer(customer: dict[str, Any]) -> dict[str, Any]:
    clone = json.loads(json.dumps(customer, sort_keys=True))
    clone["domainId"] = "<domain>"
    traits = clone.get("traits")
    if isinstance(traits, list):
        clone["traits"] = [
            "<cohort>" if isinstance(trait, str) and trait.startswith("cohort:") else trait
            for trait in traits
        ]
    domain_config = clone.get("domainConfig")
    if isinstance(domain_config, dict):
        factory = domain_config.get("cohortFactory")
        if isinstance(factory, dict):
            factory["cohortId"] = "<cohort>"
    return clone


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def compare_duration_field(field: str, java_signature: dict[str, Any], python_signature: dict[str, Any]) -> None:
    java_value = java_signature[field]
    python_value = python_signature[field]
    if abs(java_value - python_value) > DURATION_TOLERANCE_DAYS:
        fail(
            f"Java/Python agentic report duration differs for {field}: "
            f"java={java_value} python={python_value} toleranceDays={DURATION_TOLERANCE_DAYS}"
        )


def compare_scorecards(java_summary: dict[str, Any], python_summary: dict[str, Any]) -> dict[str, Any]:
    java_scorecards = scorecard_signature(java_summary)
    python_scorecards = scorecard_signature(python_summary)
    if java_scorecards != python_scorecards:
        fail(
            "Java/Python scorecard outcome differs: "
            f"java={json.dumps(java_scorecards, sort_keys=True)} "
            f"python={json.dumps(python_scorecards, sort_keys=True)}"
        )
    metric_parity: dict[str, dict[str, Any]] = {}
    java_by_id = scorecards_by_id(java_summary)
    python_by_id = scorecards_by_id(python_summary)
    for scorecard_id in sorted(java_by_id):
        java_metrics = scorecard_metrics(java_by_id[scorecard_id])
        python_metrics = scorecard_metrics(python_by_id.get(scorecard_id, {}))
        if set(java_metrics) != set(python_metrics):
            fail(
                f"Java/Python scorecard metric set differs for {scorecard_id}: "
                f"java={sorted(java_metrics)} python={sorted(python_metrics)}"
            )
        metric_parity[scorecard_id] = {}
        for metric_id in sorted(java_metrics):
            java_value = java_metrics[metric_id]
            python_value = python_metrics[metric_id]
            compare_scorecard_metric(scorecard_id, metric_id, java_value, python_value)
            metric_parity[scorecard_id][metric_id] = {
                "java": decimal_to_json(java_value),
                "python": decimal_to_json(python_value),
            }
    return metric_parity


def scorecard_signature(summary: dict[str, Any]) -> list[dict[str, Any]]:
    signature = []
    for score in summary.get("domainOutcomeScores", []):
        if not isinstance(score, dict):
            continue
        signature.append({
            "scorecardId": score.get("scorecardId"),
            "status": score.get("status"),
            "recommendation": score.get("recommendation"),
        })
    return sorted(signature, key=lambda entry: str(entry.get("scorecardId")))


def scorecards_by_id(summary: dict[str, Any]) -> dict[str, dict[str, Any]]:
    scorecards: dict[str, dict[str, Any]] = {}
    for score in summary.get("domainOutcomeScores", []):
        if not isinstance(score, dict):
            continue
        scorecard_id = str(score.get("scorecardId", "")).strip()
        if scorecard_id:
            scorecards[scorecard_id] = score
    return scorecards


def scorecard_metrics(scorecard: dict[str, Any]) -> dict[str, Decimal]:
    metrics: dict[str, Decimal] = {}
    for metric in list_values(scorecard.get("metrics")):
        if not isinstance(metric, dict):
            continue
        metric_id = str(metric.get("metricId", "")).strip()
        if not metric_id:
            continue
        value = metric.get("value")
        if isinstance(value, bool):
            continue
        try:
            metrics[metric_id] = Decimal(str(value))
        except Exception:
            continue
    return metrics


def compare_scorecard_metric(scorecard_id: str, metric_id: str, java_value: Decimal, python_value: Decimal) -> None:
    tolerance_ratio = (
        SCORECARD_EVIDENCE_VOLUME_TOLERANCE_RATIO
        if metric_id in SCORECARD_EVIDENCE_VOLUME_METRICS
        else SCORECARD_METRIC_TOLERANCE_RATIO
    )
    tolerance = max(
        Decimal(SCORECARD_METRIC_ABSOLUTE_TOLERANCE),
        max(abs(java_value), abs(python_value)) * tolerance_ratio,
    )
    if abs(java_value - python_value) > tolerance:
        fail(
            "Java/Python scorecard metric differs: "
            f"scorecardId={scorecard_id} metricId={metric_id} "
            f"java={java_value} python={python_value} tolerance={tolerance}"
        )


def decimal_to_json(value: Decimal) -> int | float:
    if value == value.to_integral_value():
        return int(value)
    return float(value)


def parity_json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return decimal_to_json(value)
    return value


def domain_system_work(response: dict[str, Any]) -> dict[str, Any]:
    value = response.get("result", {}).get("domainSystemWork")
    return value if isinstance(value, dict) else {}


def state_data(response: dict[str, Any]) -> dict[str, Any]:
    data = response.get("nextState", {}).get("data")
    return data if isinstance(data, dict) else {}


def list_values(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def find_account(response: dict[str, Any], account_id: str) -> dict[str, Any]:
    for account in list_values(state_data(response).get("accounts")):
        if isinstance(account, dict) and str(account.get("accountId", "")).strip() == account_id:
            return account
    raise AssertionError(f"account {account_id} was not found")


def require_payload_fields(provider: Provider, payload: dict[str, Any], fields: set[str], label: str) -> None:
    missing = {field for field in fields if field not in payload}
    require(not missing, provider, f"{label} missing fields: {sorted(missing)}")


def canonical_decision_trigger_payload(provider: Provider, payload: dict[str, Any]) -> dict[str, Any]:
    def canonicalize(value: Any, field: str | None = None) -> Any:
        if field in TRIGGER_PROVIDER_IDENTITY_FIELDS:
            require(isinstance(value, str) and bool(value.strip()), provider,
                    f"decision trigger payload identity field {field} must be a non-empty string")
            return f"<{field}>"
        if field in TRIGGER_BUILD_IDENTITY_FIELDS:
            require(isinstance(value, str) and bool(value.strip()), provider,
                    f"decision trigger payload build field {field} must be a non-empty string")
            return "<provider-build>"
        if isinstance(value, dict):
            return {key: canonicalize(item, key) for key, item in value.items()}
        if isinstance(value, list):
            return [canonicalize(item) for item in value]
        if field in TRIGGER_DOMAIN_SCOPED_IDENTITY_FIELDS:
            require(isinstance(value, str) and bool(value.strip()), provider,
                    f"decision trigger payload identity field {field} must be a non-empty string")
            domain_id = provider.expected_domain_id
            if value == domain_id:
                return "<domain>"
            for separator in ("|", "-", ":", "/"):
                prefix = domain_id + separator
                if value.startswith(prefix):
                    return "<domain>" + value[len(domain_id):]
        return value

    return canonicalize(payload)


def reject_forbidden_decision_trigger_fields(
        provider: Provider,
        value: Any,
        scenario_id: str,
        path: str = "trigger") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            require(key not in TRIGGER_FORBIDDEN_FIELDS, provider,
                    f"{scenario_id}: decision trigger contains forbidden field {key} at {path}")
            reject_forbidden_decision_trigger_fields(provider, item, scenario_id, f"{path}.{key}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            reject_forbidden_decision_trigger_fields(provider, item, scenario_id, f"{path}[{index}]")


def base_prepare_request(provider: Provider, scenario_id: str, accounts: list[dict[str, Any]], scheduled_work: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "protocolVersion": "remote-domain.v1",
        "requestId": str(uuid.uuid4()),
        "operation": "prepare",
        "idempotencyKey": f"parity-{provider.expected_domain_id}-{scenario_id}-prepare",
        "sentAtEpochMs": 1,
        "deadlineEpochMs": 30_000,
        "host": {"service": "parity-check", "instanceId": "local", "platformVersion": "0.1.1"},
        "domain": {"domainId": provider.expected_domain_id},
        "agent": {
            "agentId": f"{provider.expected_domain_id}::Parity",
            "personaName": "Parity",
            "interactionMode": "SIMULATION",
        },
        "payload": {
            "personaConfiguration": {
                "personaName": "Parity",
                "personaDescription": "Live banking remote-provider parity probe",
                "traits": ["parity-check"],
                "initialAccounts": accounts,
                "scheduledWork": scheduled_work,
            }
        },
    }


def projection_request(provider: Provider, scenario_id: str, state: dict[str, Any], runtime_identity: str) -> dict[str, Any]:
    return {
        "protocolVersion": "remote-domain.v1",
        "requestId": str(uuid.uuid4()),
        "operation": "domain-system-work-window",
        "idempotencyKey": f"parity-{provider.expected_domain_id}-{scenario_id}-domain-window",
        "sentAtEpochMs": 10,
        "deadlineEpochMs": 30_000,
        "host": {"service": "parity-check", "instanceId": "local", "platformVersion": "0.1.1"},
        "domain": {"domainId": provider.expected_domain_id},
        "agent": {
            "agentId": f"{provider.expected_domain_id}::Parity",
            "runtimeIdentity": runtime_identity,
            "personaName": "Parity",
        },
        "state": state,
    }


def execute_work_window(
        provider: Provider,
        timeout: float,
        scenario_id: str,
        accounts: list[dict[str, Any]],
        scheduled_work: list[dict[str, Any]],
        work_kinds: list[str]) -> dict[str, Any]:
    prepared = post_json(
        provider,
        "/api/remote-domain/v1/prepare",
        base_prepare_request(provider, scenario_id, accounts, scheduled_work),
        timeout,
    )
    state = prepared.get("nextState")
    runtime_identity = prepared.get("result", {}).get("runtimeIdentity")
    require(isinstance(state, dict), provider, f"{scenario_id}: prepare must return nextState")
    require(isinstance(runtime_identity, str) and runtime_identity.strip(), provider,
            f"{scenario_id}: prepare must return runtimeIdentity")
    request = projection_request(provider, scenario_id, state, runtime_identity)
    request["payload"] = {
        "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
        "workKinds": work_kinds,
        "maxWorkItems": 10,
        "executionMode": "EVALUATE_AND_EXECUTE",
    }
    return post_json(provider, "/api/remote-domain/v1/domain-system-work-window", request, timeout)


def require_work_window_basics(
        provider: Provider,
        scenario_id: str,
        response: dict[str, Any],
        expected_evaluated: int,
        expected_executed: int,
        expected_work_kinds: list[str],
        expected_event_types: list[str]) -> list[dict[str, Any]]:
    work = domain_system_work(response)
    events = event_payloads(response)
    work_results = list_values(work.get("workResults"))
    state_deltas = list_values(work.get("stateDeltas"))
    require(response.get("operation") == "domain-system-work-window", provider,
            f"{scenario_id}: response operation must be domain-system-work-window")
    require(work.get("evaluatedWorkCount") == expected_evaluated, provider,
            f"{scenario_id}: evaluatedWorkCount must be {expected_evaluated}")
    require(work.get("executedWorkCount") == expected_executed, provider,
            f"{scenario_id}: executedWorkCount must be {expected_executed}")
    require([item.get("kind") for item in work_results if isinstance(item, dict)] == expected_work_kinds, provider,
            f"{scenario_id}: workResults must preserve per-item kind ordering")
    require(all(isinstance(item, dict) and item.get("outcome") == "APPLIED" for item in work_results), provider,
            f"{scenario_id}: every expected work result must be APPLIED")
    require([event.get("eventType") for event in events] == expected_event_types, provider,
            f"{scenario_id}: evidence event types must be {expected_event_types}")
    require(len(state_deltas) == len(events), provider,
            f"{scenario_id}: stateDeltas must match real evidence events")
    return events


def decision_trigger_summary(
        provider: Provider,
        scenario_id: str,
        response: dict[str, Any],
        expected_types: list[str]) -> list[dict[str, Any]]:
    triggers = domain_system_work(response).get("decisionTriggers")
    require(isinstance(triggers, list), provider,
            f"{scenario_id}: decisionTriggers must be an array")
    require([item.get("triggerType") for item in triggers if isinstance(item, dict)] == expected_types, provider,
            f"{scenario_id}: decision trigger types must be {expected_types}")
    summary: list[dict[str, Any]] = []
    for index, trigger in enumerate(triggers):
        require(isinstance(trigger, dict), provider,
                f"{scenario_id}: decision trigger {index} must be an object")
        reject_forbidden_decision_trigger_fields(
            provider,
            trigger,
            scenario_id,
            f"decisionTriggers[{index}]",
        )
        require(trigger.get("schemaVersion") == "customer_decision_trigger.v1", provider,
                f"{scenario_id}: decision trigger {index} must use customer_decision_trigger.v1")
        require(isinstance(trigger.get("triggerId"), str) and trigger["triggerId"].strip(), provider,
                f"{scenario_id}: decision trigger {index} must have triggerId")
        require(isinstance(trigger.get("triggerRef"), str) and trigger["triggerRef"].strip(), provider,
                f"{scenario_id}: decision trigger {index} must have triggerRef")
        trigger_type = trigger.get("triggerType")
        trigger_ref = trigger["triggerRef"].strip()
        trigger_id = trigger["triggerId"].strip()
        require(trigger_ref.startswith(f"{provider.expected_domain_id}|"), provider,
                f"{scenario_id}: decision trigger {index} reference must use the provider domain namespace")
        require(trigger_id == f"{trigger_type}:{trigger_ref}", provider,
                f"{scenario_id}: decision trigger {index} id must bind its type and reference")
        reason = trigger.get("reason")
        require(isinstance(reason, str) and reason.strip() == reason and bool(reason), provider,
                f"{scenario_id}: decision trigger {index} must have a trimmed reason")
        evidence_refs = trigger.get("evidenceRefs")
        require(evidence_refs == [trigger_ref], provider,
                f"{scenario_id}: decision trigger {index} must reference its committed source event")
        require(trigger.get("scheduledForEpochMs") == 1_777_000_000_000, provider,
                f"{scenario_id}: decision trigger {index} must use the window simulation time")
        payload = trigger.get("payload")
        require(isinstance(payload, dict) and payload, provider,
                f"{scenario_id}: decision trigger {index} must have a fact payload")
        require(payload.get("policyVersion") == "banking.customer-decision-trigger-policy.v1", provider,
                f"{scenario_id}: decision trigger {index} must identify the policy version")
        require(payload.get("normalizedEventType") == trigger.get("triggerType"), provider,
                f"{scenario_id}: decision trigger {index} normalized event type must match")
        scheduling_priority = trigger.get("schedulingPriority")
        require(scheduling_priority in TRIGGER_SCHEDULING_PRIORITIES, provider,
                f"{scenario_id}: decision trigger {index} must have a valid schedulingPriority")
        canonical_ref = "<domain>|" + trigger_ref.split("|", 1)[1]
        canonical_payload = canonical_decision_trigger_payload(provider, payload)
        summary.append({
            "triggerId": f"{trigger_type}:{canonical_ref}",
            "triggerType": trigger_type,
            "triggerRef": canonical_ref,
            "priority": trigger.get("priority"),
            "schedulingPriority": scheduling_priority,
            "reason": reason,
            "evidenceRefs": [canonical_ref],
            "sourceEventType": payload.get("sourceEventType"),
            "normalizedEventType": payload.get("normalizedEventType"),
            "accountId": payload.get("accountId"),
            "payload": canonical_payload,
        })
    return summary


def check_deposit_work_window(provider: Provider, timeout: float) -> dict[str, Any]:
    response = execute_work_window(
        provider,
        timeout,
        "deposit-interest",
        [
            {
                "accountId": "acct-saver-1",
                "name": "Everyday Saver",
                "productCode": "TS001",
                "productCategory": "TRANS_AND_SAVINGS_ACCOUNTS",
                "balance": 1200,
                "currency": "AUD",
            },
            {
                "accountId": "acct-card-control",
                "name": "Control Card",
                "productCode": "CC001",
                "productCategory": "CRED_AND_CHRG_CARDS",
                "balance": -300,
                "currency": "AUD",
            },
        ],
        [{
            "workId": "savings-interest-work",
            "kind": "monthly_interest_accrual",
            "description": "Monthly savings interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.03},
        }],
        ["monthly_interest_accrual"],
    )
    events = require_work_window_basics(
        provider,
        "deposit-interest",
        response,
        1,
        1,
        ["monthly_interest_accrual"],
        ["remote_domain_interest_accrued"],
    )
    event = events[0]
    require_payload_fields(provider, event, {
        "occurrenceKey",
        "annualRate",
        "openingTotalBalance",
        "interestAmount",
        "newTotalBalance",
        "affectedAccountCount",
        "accountInterest",
    }, "deposit-interest evidence")
    account_entries = list_values(event.get("accountInterest"))
    require(len(account_entries) == 1 and isinstance(account_entries[0], dict), provider,
            "deposit-interest: exactly one deposit account should be affected")
    require(account_entries[0].get("accountId") == "acct-saver-1", provider,
            "deposit-interest: evidence must use external accountId")
    require(money(find_account(response, "acct-saver-1").get("balance")) == Decimal("1203.00"), provider,
            "deposit-interest: saver balance must be 1203.00")
    require(money(event.get("interestAmount")) == Decimal("3.00"), provider,
            "deposit-interest: interestAmount must be 3.00")
    triggers = decision_trigger_summary(provider, "deposit-interest", response, [])
    return {"eventTypes": [event.get("eventType")], "executedWorkCount": 1, "decisionTriggers": triggers}


def check_loan_work_window(provider: Provider, timeout: float) -> dict[str, Any]:
    response = execute_work_window(
        provider,
        timeout,
        "loan-interest",
        [
            {
                "accountId": "acct-home-loan-1",
                "name": "Home Mortgage",
                "productCode": "HL001",
                "productCategory": "RESIDENTIAL_MORTGAGES",
                "balance": -300000,
                "currency": "AUD",
            },
            {
                "accountId": "acct-student-loan-1",
                "name": "Student Loan",
                "productCode": "SL001",
                "productCategory": "PERS_LOANS",
                "balance": -24000,
                "currency": "AUD",
            },
            {
                "accountId": "acct-car-loan-control",
                "name": "Car Loan",
                "productCode": "CL001",
                "productCategory": "PERS_LOANS",
                "balance": -12000,
                "currency": "AUD",
            },
        ],
        [{
            "workId": "loan-interest-work",
            "kind": "loan_interest_accrual",
            "description": "Loan interest progression",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.06, "repaymentBaseAmount": 600},
        }],
        ["loan_interest_accrual"],
    )
    events = require_work_window_basics(
        provider,
        "loan-interest",
        response,
        1,
        1,
        ["loan_interest_accrual"],
        ["remote_domain_loan_interest_accrued", "remote_domain_loan_interest_accrued"],
    )
    require([event.get("loanKind") for event in events] == ["HOME_LOAN", "STUDENT_LOAN"], provider,
            "loan-interest: only home and student loan events should apply")
    home, student = events
    for label, event in (("home loan", home), ("student loan", student)):
        require_payload_fields(provider, event, {
            "occurrenceKey",
            "accountId",
            "runtimeAccountId",
            "openingBalance",
            "annualRate",
            "interestAmount",
            "repaymentAmount",
            "newBalance",
        }, f"loan-interest {label} evidence")
    require(home.get("accountId") == "acct-home-loan-1", provider,
            "loan-interest: home evidence must use external accountId")
    require(student.get("accountId") == "acct-student-loan-1", provider,
            "loan-interest: student evidence must use external accountId")
    require(money(home.get("newBalance")) == Decimal("-299400.00"), provider,
            "loan-interest: home loan new balance must be -299400.00")
    require(money(student.get("newBalance")) == Decimal("-24120.00"), provider,
            "loan-interest: student loan new balance must be -24120.00")
    require(money(find_account(response, "acct-car-loan-control").get("balance")) == Decimal("-12000.00"), provider,
            "loan-interest: unsupported control loan must not mutate")
    triggers = decision_trigger_summary(
        provider,
        "loan-interest",
        response,
        ["interest_charged", "interest_charged"],
    )
    return {
        "eventTypes": [event.get("eventType") for event in events],
        "executedWorkCount": 1,
        "decisionTriggers": triggers,
    }


def check_credit_card_work_window(provider: Provider, timeout: float) -> dict[str, Any]:
    response = execute_work_window(
        provider,
        timeout,
        "credit-card-work",
        [{
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }],
        [
            {
                "workId": "cc-interest-work",
                "kind": "credit_card_interest_accrual",
                "description": "Credit card interest",
                "frequency": "MONTHLY",
                "payload": {"annualRate": 0.24, "accountName": "Rewards Card"},
            },
            {
                "workId": "cc-statement-work",
                "kind": "credit_card_statement_close",
                "description": "Credit card statement close",
                "frequency": "MONTHLY",
                "payload": {
                    "accountName": "Rewards Card",
                    "statementStartDate": "2026-04-01",
                    "statementEndDate": "2026-04-30",
                    "dueDays": 14,
                    "minPaymentPercentage": 0.04,
                },
            },
        ],
        ["credit_card_interest_accrual", "credit_card_statement_close"],
    )
    events = require_work_window_basics(
        provider,
        "credit-card-work",
        response,
        2,
        2,
        ["credit_card_interest_accrual", "credit_card_statement_close"],
        ["remote_domain_credit_card_interest_charged", "remote_domain_credit_card_statement_closed"],
    )
    interest, statement = events
    for label, event in (("interest", interest), ("statement", statement)):
        require(event.get("accountId") == "acct-card-1", provider,
                f"credit-card {label}: evidence must use external accountId")
        require_payload_fields(provider, event, {
            "occurrenceKey",
            "accountId",
            "runtimeAccountId",
            "accountName",
            "productCode",
        }, f"credit-card {label} evidence")
    require(money(interest.get("openingBalance")) == Decimal("-1200.00"), provider,
            "credit-card: opening balance must be -1200.00")
    require(money(interest.get("interestAmount")) == Decimal("24.00"), provider,
            "credit-card: interestAmount must be 24.00")
    require(money(interest.get("minimumPayment")) == Decimal("48.96"), provider,
            "credit-card: minimumPayment must be 48.96")
    require(money(statement.get("closingAmount")) == Decimal("-1224.00"), provider,
            "credit-card: statement closingAmount must be -1224.00")
    require(money(statement.get("minimumAmountDue")) == Decimal("48.96"), provider,
            "credit-card: statement minimumAmountDue must be 48.96")
    require(statement.get("dueDate") == "2026-05-14", provider,
            "credit-card: statement dueDate must be 2026-05-14")
    require(money(find_account(response, "acct-card-1").get("balance")) == Decimal("-1224.00"), provider,
            "credit-card: account balance must be -1224.00")
    triggers = decision_trigger_summary(provider, "credit-card-work", response, ["statement_close"])
    return {
        "eventTypes": [event.get("eventType") for event in events],
        "executedWorkCount": 2,
        "decisionTriggers": triggers,
    }


def check_behavior(provider: Provider, timeout: float) -> dict[str, Any]:
    return {
        "depositInterest": check_deposit_work_window(provider, timeout),
        "loanInterest": check_loan_work_window(provider, timeout),
        "creditCard": check_credit_card_work_window(provider, timeout),
    }


def check_provider(provider: Provider, timeout: float) -> dict[str, Any]:
    manifest = fetch_json(provider, "/api/remote-domain/v1/manifest", timeout)
    products = fetch_json(provider, "/api/products", timeout)
    pack = fetch_json(provider, "/policy-wind-tunnel/pack", timeout)
    presets = fetch_json(provider, "/policy-wind-tunnel/presets", timeout)

    capabilities = manifest.get("capabilities", {})
    tools = nested_set(capabilities.get("tools"), "name")
    scheduled_work = nested_set(capabilities.get("scheduledWorkKinds"), "kind")
    domain_system_work = nested_set(capabilities.get("domainSystemWorkKinds"), "kind")
    codes = product_codes(products)
    scorecards = scorecard_ids(pack)
    preset_catalog = preset_ids(presets)

    require(manifest.get("protocolVersion") == "remote-domain.v1", provider, "manifest must use remote-domain.v1")
    require(manifest.get("domain", {}).get("domainId") == provider.expected_domain_id, provider,
            f"manifest domainId must be {provider.expected_domain_id}")
    require(manifest.get("transport", {}).get("contentType") == REMOTE_JSON, provider,
            f"transport contentType must be {REMOTE_JSON}")
    require(capabilities.get("supportsScheduledWork") is True, provider, "must support scheduled work")
    require(capabilities.get("supportsInitialAccounts") is True, provider, "must support initial accounts")
    require(capabilities.get("supportsDomainSystemWorkWindow") is True, provider,
            "must support domain-system work windows")
    require(capabilities.get("remoteProviderLanguage") == provider.expected_language, provider,
            f"remoteProviderLanguage must be {provider.expected_language}")
    require(REQUIRED_TOOLS.issubset(tools), provider,
            f"missing required tools: {sorted(REQUIRED_TOOLS - tools)}")
    require("transaction" in scheduled_work, provider, "scheduledWorkKinds must include transaction")
    require(REQUIRED_DOMAIN_SYSTEM_WORK.issubset(domain_system_work), provider,
            f"missing domain system work kinds: {sorted(REQUIRED_DOMAIN_SYSTEM_WORK - domain_system_work)}")
    require(REQUIRED_PRODUCTS.issubset(codes), provider,
            f"missing required product codes: {sorted(REQUIRED_PRODUCTS - codes)}")

    require(pack.get("domainId") == provider.expected_domain_id, provider,
            f"Wind Tunnel pack domainId must be {provider.expected_domain_id}")
    require(pack.get("packId") == provider.expected_pack_id, provider,
            f"Wind Tunnel packId must be {provider.expected_pack_id}")
    require(isinstance(pack.get("controlSchema"), dict), provider, "Wind Tunnel pack must publish controlSchema")
    require(isinstance(pack.get("uiSchema"), dict), provider, "Wind Tunnel pack must publish uiSchema")
    require(scorecards, provider, "Wind Tunnel pack must publish at least one outcome scorecard")
    require(preset_catalog, provider, "Wind Tunnel preset catalog must not be empty")

    return {
        "domainId": provider.expected_domain_id,
        "packId": provider.expected_pack_id,
        "products": sorted(codes),
        "tools": sorted(tools),
        "scheduledWorkKinds": sorted(scheduled_work),
        "domainSystemWorkKinds": sorted(domain_system_work),
        "scorecards": sorted(scorecards),
        "presets": sorted(preset_catalog),
        "behavior": check_behavior(provider, timeout),
    }


def compare(java_summary: dict[str, Any], python_summary: dict[str, Any]) -> None:
    java_products = set(java_summary["products"])
    python_products = set(python_summary["products"])
    if java_products != python_products:
        fail(
            "Java/Python product catalogs differ: "
            f"javaOnly={sorted(java_products - python_products)} "
            f"pythonOnly={sorted(python_products - java_products)}"
        )

    for field in ("tools", "domainSystemWorkKinds", "presets"):
        java_values = set(java_summary[field])
        python_values = set(python_summary[field])
        missing_from_python = java_values - python_values
        if missing_from_python:
            fail(f"Python provider is missing Java {field}: {sorted(missing_from_python)}")

    for scenario, java_behavior in java_summary["behavior"].items():
        python_behavior = python_summary["behavior"].get(scenario)
        if canonical_json(python_behavior) != canonical_json(java_behavior):
            fail(
                f"Java/Python behavior differs for {scenario}: "
                f"java={java_behavior} python={python_behavior}"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-base-url", default="http://localhost:8094")
    parser.add_argument("--python-base-url", default="http://localhost:8092")
    parser.add_argument("--timeout", type=float, default=5.0)
    parser.add_argument("--json", action="store_true", help="Print machine-readable parity summary")
    parser.add_argument("--skip-live-endpoints", action="store_true",
                        help="Skip live provider endpoint checks and only compare supplied report summaries")
    parser.add_argument("--java-summary", help="Java provider finalized scenario-lab-summary.json or bundle JSON")
    parser.add_argument("--python-summary", help="Python provider finalized scenario-lab-summary.json or bundle JSON")
    args = parser.parse_args()

    if args.skip_live_endpoints and not (args.java_summary and args.python_summary):
        fail("--skip-live-endpoints requires --java-summary and --python-summary")

    java = Provider(
        name="java-banking-remote",
        base_url=args.java_base_url,
        expected_domain_id="banking",
        expected_pack_id="banking-policy-pack",
        expected_language="java",
    )
    python = Provider(
        name="python-banking-remote",
        base_url=args.python_base_url,
        expected_domain_id="banking-python",
        expected_pack_id="banking-python-policy-pack",
        expected_language="python",
    )

    java_summary = None
    python_summary = None
    if not args.skip_live_endpoints:
        java_summary = check_provider(java, args.timeout)
        python_summary = check_provider(python, args.timeout)
        compare(java_summary, python_summary)

    report_parity = None
    if args.java_summary or args.python_summary:
        if not args.java_summary or not args.python_summary:
            fail("--java-summary and --python-summary must be supplied together")
        report_parity = compare_agentic_report_summaries(
            load_report_summary(args.java_summary),
            load_report_summary(args.python_summary),
            java_summary_path=args.java_summary,
            python_summary_path=args.python_summary,
        )

    summary = {
        "schemaVersion": "banking_remote_provider_parity.v1",
        "java": java_summary,
        "python": python_summary,
        "agenticReportParity": report_parity,
    }
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
    else:
        print("banking remote provider parity passed")
        if java_summary and python_summary:
            print(f"- Java: {java_summary['domainId']} / {java_summary['packId']}")
            print(f"- Python: {python_summary['domainId']} / {python_summary['packId']}")
            print(f"- Products: {len(java_summary['products'])} shared codes")
            print(f"- Scorecards: Java {len(java_summary['scorecards'])}, Python {len(python_summary['scorecards'])}")
            print("- Domain-system work-window behavior: deposit, loan, and credit-card parity passed")
        if report_parity:
            shared = report_parity["sharedConfiguration"]
            print("- Agentic report parity: "
                  f"{shared['cohortSize']} customers, {shared['simulationHorizonDays']} days, "
                  f"{shared['decisionCadence']} cadence, {shared['expectedDecisionOpportunities']} expected turns")


if __name__ == "__main__":
    main()
