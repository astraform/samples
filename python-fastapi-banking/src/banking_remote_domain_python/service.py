from __future__ import annotations

import base64
import fcntl
import hashlib
import importlib.metadata
import io
import json
import logging
import math
import os
import tempfile
import re
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from contextlib import contextmanager
from contextvars import ContextVar, Token
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from importlib import resources
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

from astraform.remote_domain.author_kit.protocol import build_projection
from astraform.remote_domain.author_kit.protocol import build_manifest
from astraform.remote_domain.author_kit.protocol import BinaryPayloadMetadata
from astraform.remote_domain.author_kit.protocol import ProtocolError
from astraform.remote_domain.author_kit.protocol import build_domain_outcome_scorecard
from astraform.remote_domain.author_kit.protocol import build_outcome_recommendation_rule
from astraform.remote_domain.author_kit.protocol import build_outcome_score_gate
from astraform.remote_domain.author_kit.protocol import build_outcome_score_metric
from astraform.remote_domain.author_kit.protocol import build_policy_expression
from astraform.remote_domain.author_kit.protocol import build_policy_wind_tunnel_pack_metadata
from astraform.remote_domain.author_kit.protocol import load_population_catalog
from banking_remote_domain_python.domain_kpi import evaluate_fast_batch_kpis
from banking_remote_domain_python.decision_trigger_policy import (
    decision_triggers_from_evidence as build_decision_triggers_from_evidence,
)
from banking_remote_domain_python.decision_trigger_policy import normalized_configuration
from banking_remote_domain_python.rate_change_plan import ACTION_ID as RATE_CHANGE_PLAN_ACTION_ID
from banking_remote_domain_python.rate_change_plan import agent_identity_set_digest as rate_change_agent_identity_set_digest
from banking_remote_domain_python.rate_change_plan import eligible_account_counts_from_cohort_artifact as rate_change_eligible_account_counts_from_cohort_artifact
from banking_remote_domain_python.rate_change_plan import PLAN_SCHEMA_VERSION as RATE_CHANGE_PLAN_SCHEMA_VERSION
from banking_remote_domain_python.rate_change_plan import SUPPORTED_PRESET_ID as RATE_CHANGE_SUPPORTED_PRESET_ID
from banking_remote_domain_python.rate_change_plan import SUPPORTED_PRODUCT_CODE as RATE_CHANGE_SUPPORTED_PRODUCT_CODE
from banking_remote_domain_python.rate_change_plan import RateChangeInstallationCustomer
from banking_remote_domain_python.rate_change_plan import RateChangePlanSnapshot
from banking_remote_domain_python.rate_change_plan import WORK_KIND as RATE_CHANGE_WORK_KIND
from banking_remote_domain_python.rate_change_plan import canonical_installation_manifest_bytes
from banking_remote_domain_python.rate_change_plan import canonical_decimal as canonical_rate_change_decimal
from banking_remote_domain_python.rate_change_plan import canonical_snapshot_bytes as canonical_rate_change_plan_bytes
from banking_remote_domain_python.rate_change_plan import compile_for_launch as compile_rate_change_plan
from banking_remote_domain_python.rate_change_plan import installation_manifest as rate_change_installation_manifest
from banking_remote_domain_python.rate_change_plan import require_installation_manifest_bytes as require_rate_change_installation_manifest_bytes
from banking_remote_domain_python.rate_change_plan import install_operation_id as rate_change_plan_install_operation_id
from banking_remote_domain_python.rate_change_plan import require_snapshot_bytes as require_rate_change_plan_bytes
from banking_remote_domain_python.rate_change_plan import require_snapshot as require_rate_change_plan


LOGGER = logging.getLogger(__name__)
PROVIDER_ARTIFACT_DIGEST_ENV = "ASTRAFORM_PROVIDER_ARTIFACT_DIGEST"
PROVIDER_ARTIFACT_DIGEST_FILE_ENV = "ASTRAFORM_PROVIDER_ARTIFACT_DIGEST_FILE"
REQUIRE_OCI_PROVIDER_DIGEST_ENV = "ASTRAFORM_REQUIRE_OCI_PROVIDER_DIGEST"
SHARED_STORAGE_ENABLED_ENV = "ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED"
DEFAULT_PROVIDER_ARTIFACT_DIGEST_FILE = Path("/opt/astraform/provider-artifact-digest")


class _RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


_OPPORTUNITY_AUDIT_OPENER = build_opener(_RejectRedirects())


def resolve_wind_tunnel_provider_revision() -> str:
    configured = os.getenv(PROVIDER_ARTIFACT_DIGEST_ENV, "").strip()
    digest_file = Path(
        os.getenv(
            PROVIDER_ARTIFACT_DIGEST_FILE_ENV,
            str(DEFAULT_PROVIDER_ARTIFACT_DIGEST_FILE),
        )
    )
    if configured:
        revision = validate_provider_revision(configured)
    elif digest_file.is_file():
        revision = validate_provider_revision(digest_file.read_text(encoding="utf-8").strip())
    else:
        revision = local_provider_build_revision()
    if require_oci_provider_digest() and not revision.startswith("oci-sha256:"):
        raise RuntimeError(
            f"{REQUIRE_OCI_PROVIDER_DIGEST_ENV}=true requires an independently supplied "
            "oci-sha256 provider artifact digest"
        )
    return revision


def validate_provider_revision(value: str) -> str:
    revision = value.strip()
    if re.fullmatch(r"(?:build|oci)-sha256:[0-9a-f]{64}", revision) is None:
        raise RuntimeError(
            f"{PROVIDER_ARTIFACT_DIGEST_ENV} must be "
            "build-sha256:<64 lowercase hex> or oci-sha256:<64 lowercase hex>"
        )
    return revision


def require_oci_provider_digest() -> bool:
    configured = os.getenv(REQUIRE_OCI_PROVIDER_DIGEST_ENV, "").strip().lower()
    if configured in {"", "0", "false", "no", "off"}:
        return False
    if configured in {"1", "true", "yes", "on"}:
        return True
    raise RuntimeError(f"{REQUIRE_OCI_PROVIDER_DIGEST_ENV} must be true or false")


def local_provider_build_revision(
    package_root: Path | None = None,
    project_root: Path | None = None,
    resolved_distributions: dict[str, str] | None = None,
) -> str:
    digest = hashlib.sha256()
    package_root = package_root or Path(__file__).resolve().parent
    project_root = project_root or package_root.parent.parent
    artifact_files = [
        path
        for path in package_root.rglob("*")
        if path.is_file()
        and "__pycache__" not in path.parts
        and path.suffix not in {".pyc", ".pyo"}
    ]
    for metadata_file in (project_root / "pyproject.toml", project_root / "README.md"):
        if metadata_file.is_file():
            artifact_files.append(metadata_file)
    for artifact_file in sorted(set(artifact_files), key=lambda path: path.as_posix()):
        try:
            relative_path = artifact_file.relative_to(project_root).as_posix()
        except ValueError:
            relative_path = artifact_file.relative_to(package_root).as_posix()
        digest.update(relative_path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(artifact_file.read_bytes())
        digest.update(b"\0")
    distributions = resolved_distributions
    if distributions is None:
        distributions = {
            str(distribution.metadata.get("Name", "")).strip().lower(): distribution.version
            for distribution in importlib.metadata.distributions()
            if str(distribution.metadata.get("Name", "")).strip()
        }
    for name, version in sorted(distributions.items()):
        digest.update(f"distribution:{name}=={version}".encode("utf-8"))
        digest.update(b"\0")
    return f"build-sha256:{digest.hexdigest()}"


PROTOCOL_VERSION = "remote-domain.v1"
DOMAIN_ID = "banking-python"
DISPLAY_NAME = "Python Banking Reference Domain"
SCHEMA_VERSION = "banking-python.state.v1"
MEDIA_TYPE = "application/vnd.astraform.remote-domain.v1+json"
WIND_TUNNEL_PACK_ID = "banking-python-policy-pack"
WIND_TUNNEL_PRESET_ID = "hardship-relief"
WIND_TUNNEL_SUMMARY_SCHEMA_VERSION = "policy_wind_tunnel_summary.v1"
WIND_TUNNEL_PROVIDER_REVISION = resolve_wind_tunnel_provider_revision()
OPPORTUNITY_CORRELATION_CAPABILITY_HEADER = (
    "X-Astraform-Opportunity-Correlation-Capability"
)
_OPPORTUNITY_CORRELATION_CAPABILITY: ContextVar[str] = ContextVar(
    "opportunity_correlation_capability",
    default="",
)
CLOCK_DRIVEN_INTERVENTION_PLAN_FILE = "clock-driven-intervention-plan.json"
CLOCK_DRIVEN_INTERVENTION_INSTALLATION_FILE = "clock-driven-rate-change-installation.json"
CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD = "clockDrivenInterventionPlanDigest"
CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD = "clockDrivenInterventionPlanSchemaVersion"
OPPORTUNITY_CORRELATION_AUDIT_SCHEMA_VERSION = "customer_decision_opportunity_correlation_audit.v1"
RATE_CHANGE_PROOF_SCHEMA_VERSION = "banking.clock-driven-rate-change-proof.v1"
RATE_CHANGE_TRIGGER_TYPE = "rate_or_policy_change"
RATE_CHANGE_TRIGGER_PAYLOAD_FIELDS = {
    "policyVersion",
    "numericEncoding",
    "normalizedEventType",
    "sourceEventType",
    "eventType",
    "businessEventId",
    "simulationRunId",
    "customerId",
    "interventionId",
    "operationId",
    "planDigest",
    "occurrenceKey",
    "workId",
    "productCode",
    "scheduledForEpochMs",
    "effectiveAtEpochMs",
    "appliedAtEpochMs",
    "affectedAccountCount",
    "oldRate",
    "newRate",
    "outcome",
}


def bind_opportunity_correlation_capability(value: str | None) -> Token[str]:
    capability = value or ""
    if capability != capability.strip() or len(capability) > 4096:
        capability = ""
    return _OPPORTUNITY_CORRELATION_CAPABILITY.set(capability)


def reset_opportunity_correlation_capability(token: Token[str]) -> None:
    _OPPORTUNITY_CORRELATION_CAPABILITY.reset(token)


def current_opportunity_correlation_capability() -> str:
    capability = _OPPORTUNITY_CORRELATION_CAPABILITY.get()
    if not capability:
        raise RuntimeError(
            "Finalization request did not supply a private opportunity correlation capability"
        )
    return capability
OPPORTUNITY_CORRELATION_AUDIT_FIELDS = {
    "schemaVersion",
    "simulationRunId",
    "triggerType",
    "opportunityCount",
    "opportunities",
    "asOf",
}
OPPORTUNITY_CORRELATION_ROW_FIELDS = {
    "opportunityId",
    "agentId",
    "domainId",
    "triggerId",
    "triggerType",
    "triggerRef",
    "scheduledForEpochMs",
    "priority",
    "reason",
    "status",
    "attemptCount",
    "decisionMode",
    "triggerDigest",
    "payload",
    "evidenceRefs",
    "decisionEventId",
    "decisionAttempt",
    "decisionContractValid",
}
DEFAULT_PRODUCT_CODE = "TS001"
EXECUTION_MODE_FAST_BATCH_COMPARE = "FAST_BATCH_COMPARE"
EXECUTION_MODE_CLOCK_DRIVEN_HYBRID = "CLOCK_DRIVEN_HYBRID"
AGENT_DECISION_MODE_DETERMINISTIC = "DETERMINISTIC"
AGENT_DECISION_MODE_STUB_LLM = "STUB_LLM"
AGENT_DECISION_MODE_REAL_LLM = "REAL_LLM"
OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION = "customer_decision_opportunity_reconciliation.v1"
OPPORTUNITY_AGENT_AUDIT_SCHEMA_VERSION = "customer_decision_opportunity_agent_audit.v1"
OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION = "customer_decision_opportunity_audit_totals.v1"
OPPORTUNITY_GENERATION_MODE = "EVENT_DRIVEN_DECISION_TRIGGERS"
OPPORTUNITY_LIFECYCLE_STATUSES = {
    "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED"
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
OPPORTUNITY_RECONCILIATION_STATUSES = {
    "UNKNOWN_RUN",
    "GENERATION_FAILED",
    "INITIALIZATION_MANIFEST_OPEN",
    "INITIALIZATION_MANIFEST_UNVERIFIED",
    "INITIALIZATION_MANIFEST_INCONSISTENT",
    "INITIALIZATION_PENDING",
    "TICK_FAILURE_LEDGER_INCONSISTENT",
    "TICK_FAILURE_DEBT",
    "GENERATION_OPEN",
    "EMPTY",
    "DRAINING",
    "RECONCILED",
    "INCONSISTENT",
}
AGENT_DECISION_CADENCE_EVENT_ONLY = "EVENT_ONLY"
AGENT_DECISION_CADENCE_HOURLY = "HOURLY"
AGENT_DECISION_CADENCE_DAILY = "DAILY"
AGENT_DECISION_CADENCE_WEEKLY = "WEEKLY"
STATUS_QUEUED = "QUEUED"
STATUS_RUNNING = "RUNNING"
STATUS_PAUSED = "PAUSED"
STATUS_READY_TO_FINALIZE = "READY_TO_FINALIZE"
STATUS_COMPLETED = "COMPLETED"
STATUS_CANCELLED = "CANCELLED"
STATUS_FAILED = "FAILED"
PHASE_QUEUED = "QUEUED"
PHASE_PREPARING_CLOCK = "PREPARING_CLOCK"
PHASE_ACTIVATING_COHORT = "ACTIVATING_COHORT"
PHASE_CLOCK_RUNNING = "CLOCK_RUNNING"
PHASE_READY_TO_FINALIZE = "READY_TO_FINALIZE"
PHASE_FINALIZING_REPORT = "FINALIZING_REPORT"
PHASE_PAUSED = "PAUSED"
PHASE_COMPLETED = "COMPLETED"
PHASE_CANCELLED = "CANCELLED"
PHASE_FAILED = "FAILED"
TRANSITION_CANCEL = "CANCEL"
TRANSITION_PAUSE = "PAUSE"
TRANSITION_RESUME_SETUP = "RESUME_SETUP"
TRANSITION_RESUME_CLOCK = "RESUME_CLOCK"
TRANSITION_HORIZON_READY = "HORIZON_READY"
TRANSITION_FINALIZE_START = "FINALIZE_START"
TRANSITION_FINALIZE_COMPLETE = "FINALIZE_COMPLETE"
FINALIZATION_OPERATION_PREFIX = "scenario-lab-finalize:"
FINALIZATION_OPERATION_FIELD = "scenarioLabFinalizationOperation"
TRANSITION_WORKER_PREPARE = "WORKER_PREPARE"
TRANSITION_WORKER_ACTIVATE = "WORKER_ACTIVATE"
TRANSITION_WORKER_START_CLOCK = "WORKER_START_CLOCK"
TRANSITION_FAIL = "FAIL"
CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS: dict[str, dict[str, Any]] = {
    TRANSITION_WORKER_PREPARE: {
        "sources": {
            (STATUS_QUEUED, PHASE_QUEUED),
            (STATUS_RUNNING, PHASE_QUEUED),
        },
        "target": (STATUS_RUNNING, PHASE_PREPARING_CLOCK),
    },
    TRANSITION_WORKER_ACTIVATE: {
        "sources": {(STATUS_RUNNING, PHASE_PREPARING_CLOCK)},
        "target": (STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
    },
    TRANSITION_WORKER_START_CLOCK: {
        "sources": {(STATUS_RUNNING, PHASE_ACTIVATING_COHORT)},
        "target": (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
    },
    TRANSITION_PAUSE: {
        "sources": {
            (STATUS_RUNNING, PHASE_PREPARING_CLOCK),
            (STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
            (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
            (STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
        },
        "target": (STATUS_PAUSED, PHASE_PAUSED),
    },
    TRANSITION_RESUME_SETUP: {
        "sources": {(STATUS_PAUSED, PHASE_PAUSED)},
        "target": (STATUS_RUNNING, PHASE_PREPARING_CLOCK),
    },
    TRANSITION_RESUME_CLOCK: {
        "sources": {(STATUS_PAUSED, PHASE_PAUSED)},
        "target": (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
    },
    TRANSITION_HORIZON_READY: {
        "sources": {(STATUS_RUNNING, PHASE_CLOCK_RUNNING)},
        "target": (STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
    },
    TRANSITION_FINALIZE_START: {
        "sources": {
            (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
            (STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
            (STATUS_PAUSED, PHASE_PAUSED),
        },
        "target": (STATUS_RUNNING, PHASE_FINALIZING_REPORT),
    },
    TRANSITION_FINALIZE_COMPLETE: {
        "sources": {(STATUS_RUNNING, PHASE_FINALIZING_REPORT)},
        "target": (STATUS_COMPLETED, PHASE_COMPLETED),
    },
    TRANSITION_CANCEL: {
        "sources": {
            (STATUS_QUEUED, PHASE_QUEUED),
            (STATUS_RUNNING, PHASE_QUEUED),
            (STATUS_RUNNING, PHASE_PREPARING_CLOCK),
            (STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
            (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
            (STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE),
            (STATUS_RUNNING, PHASE_FINALIZING_REPORT),
            (STATUS_PAUSED, PHASE_PAUSED),
        },
        "target": (STATUS_CANCELLED, PHASE_CANCELLED),
    },
    TRANSITION_FAIL: {
        "sources": {
            (STATUS_QUEUED, PHASE_QUEUED),
            (STATUS_RUNNING, PHASE_QUEUED),
            (STATUS_RUNNING, PHASE_PREPARING_CLOCK),
            (STATUS_RUNNING, PHASE_ACTIVATING_COHORT),
            (STATUS_RUNNING, PHASE_CLOCK_RUNNING),
            (STATUS_RUNNING, PHASE_FINALIZING_REPORT),
        },
        "target": (STATUS_FAILED, PHASE_FAILED),
    },
}
HYBRID_ACTIVATION_CHUNK_SIZE = 50
HYBRID_REPLAY_SEED = 20_260_306
HYBRID_STATE_MUTATION_SAMPLE_LIMIT = 100
DEFAULT_SIMULATION_START_EPOCH_MS = 1767225600000
MILLIS_PER_SIMULATION_DAY = 86_400_000


def positive_int_env(name: str, default: int) -> int:
    try:
        return max(1, int(os.getenv(name, "").strip()))
    except ValueError:
        return default


def positive_float_env(name: str, default: float) -> float:
    try:
        return max(0.0001, float(os.getenv(name, "").strip()))
    except ValueError:
        return default


def nonblank_env(name: str, default: str) -> str:
    value = os.getenv(name, "").strip()
    return value or default


HYBRID_FAST_PLAYBACK_RATE = 1_800.0
HYBRID_NO_TOKEN_EVENT_ONLY_PLAYBACK_RATE = 7_200.0
HYBRID_REAL_LLM_MAX_PLAYBACK_RATE = positive_float_env("ASTRAFORM_REAL_LLM_CANARY_MAX_PLAYBACK_RATE", 600.0)
HYBRID_MIN_PLAYBACK_RATE = 0.1
REAL_LLM_CANARY_MAX_CUSTOMERS = positive_int_env("ASTRAFORM_REAL_LLM_CANARY_MAX_CUSTOMERS", 10)
REAL_LLM_CANARY_MAX_HORIZON_DAYS = positive_int_env("ASTRAFORM_REAL_LLM_CANARY_MAX_HORIZON_DAYS", 30)
REAL_LLM_CANARY_MAX_CALLS = positive_int_env("ASTRAFORM_REAL_LLM_CANARY_MAX_CALLS", 70)
REAL_LLM_CANARY_MAX_USD = positive_float_env("ASTRAFORM_REAL_LLM_CANARY_MAX_USD", 1.0)
REAL_LLM_CANARY_MAX_INPUT_TOKENS = positive_int_env("ASTRAFORM_REAL_LLM_CANARY_MAX_INPUT_TOKENS", 150_000)
REAL_LLM_CANARY_MAX_OUTPUT_TOKENS = positive_int_env("ASTRAFORM_REAL_LLM_CANARY_MAX_OUTPUT_TOKENS", 25_000)
REAL_LLM_DEFAULT_PROVIDER = nonblank_env("ASTRAFORM_REAL_LLM_CANARY_PROVIDER", "openai")
REAL_LLM_DEFAULT_MODEL = nonblank_env("ASTRAFORM_REAL_LLM_CANARY_MODEL", "gemini-2.5-flash-lite")
DEFAULT_PRODUCT_CATALOG = [
    {"productCode": "TS001", "name": "Standard Saving Product", "productCategory": "TRANS_AND_SAVINGS_ACCOUNTS", "category": "TRANS_AND_SAVINGS_ACCOUNTS"},
    {"productCode": "SAV001", "name": "NetBank Saver", "productCategory": "TRANS_AND_SAVINGS_ACCOUNTS", "category": "TRANS_AND_SAVINGS_ACCOUNTS"},
    {"productCode": "CC001", "name": "Standard Credit Card", "productCategory": "CRED_AND_CHRG_CARDS", "category": "CRED_AND_CHRG_CARDS"},
    {"productCode": "CC002", "name": "Low Rate credit card", "productCategory": "CRED_AND_CHRG_CARDS", "category": "CRED_AND_CHRG_CARDS"},
    {"productCode": "HL001", "name": "Standard Home Loan", "productCategory": "RESIDENTIAL_MORTGAGES", "category": "RESIDENTIAL_MORTGAGES"},
    {"productCode": "SL001", "name": "Student Loan", "productCategory": "PERS_LOANS", "category": "PERS_LOANS"},
    {"productCode": "CL001", "name": "Car Loan", "productCategory": "PERS_LOANS", "category": "PERS_LOANS"},
    {"productCode": "BL001", "name": "Business Loan", "productCategory": "BUSINESS_LOANS", "category": "BUSINESS_LOANS"},
    {"productCode": "TD001", "name": "Term Deposit", "productCategory": "TERM_DEPOSITS", "category": "TERM_DEPOSITS"},
]


def real_llm_canary_policy() -> dict[str, Any]:
    return {
        "schemaVersion": "real_llm_canary_policy.v1",
        "enabled": True,
        "provider": REAL_LLM_DEFAULT_PROVIDER,
        "model": REAL_LLM_DEFAULT_MODEL,
        "maxCustomers": REAL_LLM_CANARY_MAX_CUSTOMERS,
        "maxDays": REAL_LLM_CANARY_MAX_HORIZON_DAYS,
        "maxCalls": REAL_LLM_CANARY_MAX_CALLS,
        "maxUsd": REAL_LLM_CANARY_MAX_USD,
        "maxInputTokens": REAL_LLM_CANARY_MAX_INPUT_TOKENS,
        "maxOutputTokens": REAL_LLM_CANARY_MAX_OUTPUT_TOKENS,
        "allowedCadences": ["EVENT_ONLY", "DAILY", "WEEKLY"],
        "maxPlaybackRate": HYBRID_REAL_LLM_MAX_PLAYBACK_RATE,
    }
WIND_TUNNEL_PRESETS = [
    {
        "presetId": "saver-repricing",
        "label": "Saver Repricing",
        "description": "Deposit repricing story for TS001 with no extra regime overlays.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 6,
            "candidateReliefRate": 50,
            "reviewCapacity": 24,
            "averageExposure": 3000,
            "productCode": "TS001",
            "baselineRate": 0.03,
            "candidateRate": 0.08,
        },
    },
    {
        "presetId": "saver-stimulus",
        "label": "Saver + Stimulus",
        "description": "TS001 repricing plus a candidate-side stimulus injection to show macro support.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 6,
            "candidateReliefRate": 65,
            "reviewCapacity": 24,
            "averageExposure": 3000,
            "productCode": "TS001",
            "baselineRate": 0.03,
            "candidateRate": 0.08,
        },
    },
    {
        "presetId": "credit-apr-lift",
        "label": "Credit APR Lift",
        "description": "Credit-card APR change on CC001 with no extra overlays.",
        "certified": True,
        "deliveryStage": "pilot",
        "guidedTitle": "Credit APR Compare",
        "guidedCategory": "Credit Cards",
        "guidedAudience": "Product / Credit Policy",
        "guidedOutcome": "Compare a credit-card APR change on the certified card cohort without changing credit limits or hardship policy.",
        "guidedEditableFields": ["baselineRate", "candidateRate"],
        "guidedEditableLabels": ["Baseline APR", "Candidate APR"],
        "guidedEvidenceFocus": ["Charged interest delta", "Affected cardholders", "Domain scorecard recommendation"],
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 12,
            "candidateReliefRate": 48,
            "reviewCapacity": 15,
            "averageExposure": 6200,
            "productCode": "CC001",
            "baselineRate": 0.19,
            "candidateRate": 0.24,
        },
    },
    {
        "presetId": "credit-stress",
        "label": "Credit Stress Shock",
        "description": "CC001 APR lift with a candidate-side market-crash overlay.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 25,
            "candidateReliefRate": 42,
            "reviewCapacity": 12,
            "averageExposure": 9000,
            "productCode": "CC001",
            "baselineRate": 0.19,
            "candidateRate": 0.24,
        },
    },
    {
        "presetId": "credit-limit-lift",
        "label": "Credit Limit Lift",
        "description": "Increase the CC001 line to show available-credit and utilisation movement without repricing.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 8,
            "candidateReliefRate": 55,
            "reviewCapacity": 18,
            "averageExposure": 5200,
            "productCode": "CC001",
            "baselineRate": 0.19,
            "candidateRate": 0.19,
        },
    },
    {
        "presetId": "credit-limit-tighten",
        "label": "Credit Limit Tighten",
        "description": "Tighten the CC001 line to surface constrained customers, utilisation pressure, and fail-close guardrails.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 16,
            "candidateReliefRate": 45,
            "reviewCapacity": 10,
            "averageExposure": 7600,
            "productCode": "CC001",
            "baselineRate": 0.19,
            "candidateRate": 0.19,
        },
    },
    {
        "presetId": "low-rate-limit-tighten",
        "label": "Low Rate Card Tighten",
        "description": "Tighten the CC002 line to surface pressure on the broader low-rate card cohort.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 14,
            "candidateReliefRate": 44,
            "reviewCapacity": 12,
            "averageExposure": 6400,
            "productCode": "CC002",
            "baselineRate": 0.1399,
            "candidateRate": 0.1399,
        },
    },
    {
        "presetId": "hardship-relief",
        "label": "Hardship Relief",
        "description": "Run the hardship policy path against a low-liquidity cohort and show direct cash relief.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 18,
            "candidateReliefRate": 72,
            "reviewCapacity": 12,
            "averageExposure": 8500,
            "productCode": "TS001",
            "baselineRate": 0.03,
            "candidateRate": 0.03,
        },
    },
    {
        "presetId": "hardship-threshold-tradeoff",
        "label": "Hardship Threshold Tradeoff",
        "description": "Widen eligibility and compare relief coverage against budget burn and residual hardship.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 28,
            "candidateReliefRate": 80,
            "reviewCapacity": 16,
            "averageExposure": 9200,
            "productCode": "TS001",
            "baselineRate": 0.03,
            "candidateRate": 0.03,
        },
    },
    {
        "presetId": "fraud-alert-friction",
        "label": "Fraud Alert Friction",
        "description": "Trigger a structured fraud alert and show alert coverage, friction, and operator-load pressure.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 10,
            "candidateReliefRate": 50,
            "reviewCapacity": 12,
            "averageExposure": 7000,
            "productCode": "CC001",
            "baselineRate": 0.19,
            "candidateRate": 0.19,
        },
    },
    {
        "presetId": "hardship-boost",
        "label": "Hardship Boost (Legacy Alias)",
        "description": "Legacy alias for the stronger hardship threshold tradeoff preset.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 28,
            "candidateReliefRate": 80,
            "reviewCapacity": 16,
            "averageExposure": 9200,
            "productCode": "TS001",
        },
    },
    {
        "presetId": "fraud-card-alert",
        "label": "Fraud Card Alert (Legacy Alias)",
        "description": "Legacy alias for the fraud-alert-friction preset.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 10,
            "candidateReliefRate": 50,
            "reviewCapacity": 12,
            "averageExposure": 7000,
            "productCode": "CC001",
        },
    },
    {
        "presetId": "liquidity-buffer-python-smoke",
        "label": "Liquidity Buffer Python Smoke",
        "description": "Reference liquidity impact scenario for Python banking providers.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 10,
            "candidateReliefRate": 55,
            "reviewCapacity": 16,
            "averageExposure": 6200,
            "productCode": "TS001",
        },
    },
    {
        "presetId": "hardship-python-smoke",
        "label": "Hardship Python Smoke (Legacy Alias)",
        "description": "Legacy Python smoke preset retained for conformance and older local scripts.",
        "defaults": {
            "customerCount": 100,
            "hardshipRequestRate": 18,
            "candidateReliefRate": 72,
            "reviewCapacity": 12,
            "averageExposure": 8500,
            "productCode": "TS001",
        },
    },
]
WIND_TUNNEL_PRESET_DEFAULTS = {
    preset["presetId"]: dict(preset["defaults"])
    for preset in WIND_TUNNEL_PRESETS
}
_POLICY_WIND_TUNNEL_RUNS: dict[str, dict[str, Any]] = {}
_POLICY_WIND_TUNNEL_LOCK = threading.RLock()
_POLICY_RUN_STATE_LOCKS: dict[Path, threading.RLock] = {}
_POLICY_RUN_STATE_LOCKS_GUARD = threading.Lock()
_POLICY_EXECUTION_CONTEXT = threading.local()
_FINALIZATION_RECEIPT_LOCKS: dict[Path, threading.RLock] = {}
_FINALIZATION_RECEIPT_LOCKS_GUARD = threading.Lock()
FINALIZATION_MODE_FIELD = "opportunityGenerationFinalizationMode"
FINALIZATION_MODE_LEGACY_V1 = "LEGACY_V1"
FINALIZATION_MODE_PLATFORM_OWNED_V1 = "PLATFORM_OWNED_V1"
FINALIZATION_MODE_PLATFORM_OWNED_V2 = "PLATFORM_OWNED_V2"
FINALIZATION_RECEIPT_SCHEMA = "scenario_lab_provider_finalization_receipt.v2"
FINALIZATION_RECEIPT_PREPARED = "PREPARED"
FINALIZATION_RECEIPT_PUBLISHED = "PUBLISHED"
FINALIZATION_NOTIFICATION_PENDING = "PENDING"
FINALIZATION_NOTIFICATION_CONFIRMED = "CONFIRMED"
FINALIZATION_NOTIFICATION_NOT_REQUIRED = "NOT_REQUIRED"
FINALIZATION_RECEIPT_FILE = "finalization-receipt.json"
FINALIZATION_RECEIPT_LOCK_FILE = "finalization-receipt.lock"
POLICY_RUN_STATE_SCHEMA = "banking_python_policy_wind_tunnel_provider_state.v1"
POLICY_RUN_STATE_FILE = "provider-run-state.json"
POLICY_RUN_STATE_LOCK_FILE = "provider-run-state.lock"
MAX_POLICY_RUN_STATE_BYTES = 8 * 1024 * 1024
POLICY_LAUNCH_DIGEST_FIELD = "_astraformLaunchDigest"
POLICY_EXECUTION_CLAIM_OWNER_FIELD = "_astraformExecutionClaimOwner"
POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD = "_astraformExecutionClaimExpiresAtEpochMs"
POLICY_EXECUTION_CLAIM_LEASE_MS = 24 * 60 * 60 * 1000
POLICY_LAUNCH_ADMISSION_CREATED = "CREATED"
POLICY_LAUNCH_ADMISSION_REPLAY = "REPLAY"
POLICY_LAUNCH_ADMISSION_CONFLICT = "CONFLICT"
POLICY_LAUNCH_EXECUTION_FRESH_START = "FRESH_START"
POLICY_LAUNCH_EXECUTION_RECOVER_EXISTING = "RECOVER_EXISTING"
FINALIZATION_RECEIPT_FIELDS = {
    "schemaVersion",
    "runId",
    "finalizationMode",
    "providerRevision",
    "operationId",
    "inputDigest",
    "state",
    "completionNotificationState",
    "completionNotificationPayload",
    "runSnapshot",
    "artifactManifest",
    "updatedAt",
}
FINALIZATION_RUN_SNAPSHOT_FIELDS = {
    "status",
    "summaryPath",
    "bundlePath",
    "startedAtEpochMs",
    "completedAtEpochMs",
    "metadata",
}
FINALIZATION_NOTIFICATION_FIELDS = {
    "runId",
    "status",
    "source",
    "summaryPath",
    "bundlePath",
    "operationId",
    "inputDigest",
    "providerRevision",
    "completedAtEpochMs",
    "metadata",
}
FINALIZATION_ARTIFACT_SPECS = {
    "summary": ("scenario-lab-summary.json", "application/json"),
    "comparisonReport": ("decision-comparison.json", "application/json"),
    "simulationTimelineJson": ("simulation-timeline.json", "application/json"),
    "sideEffectLedgerJson": ("side-effect-ledger.json", "application/json"),
    "branchStateProjectionJson": ("branch-state-projection.json", "application/json"),
    "domainOutcomeScoreJson": ("domain-outcome-score.json", "application/json"),
    "domainOutcomeScoresJson": ("domain-outcome-scores.json", "application/json"),
    "hybridAgentAuditJson": ("hybrid-agent-audit.json", "application/json"),
}


@dataclass(frozen=True)
class PolicyLaunchAdmission:
    outcome: str
    run: dict[str, Any]
    execution_claim_owner: str | None = None
    takeover: bool = False

    @property
    def execution_path(self) -> str | None:
        if self.execution_claim_owner is None:
            return None
        if self.outcome == POLICY_LAUNCH_ADMISSION_CREATED and not self.takeover:
            return POLICY_LAUNCH_EXECUTION_FRESH_START
        if self.outcome == POLICY_LAUNCH_ADMISSION_REPLAY and self.takeover:
            return POLICY_LAUNCH_EXECUTION_RECOVER_EXISTING
        raise RuntimeError("Scenario Lab launch admission has an invalid execution path")


class StalePolicyExecutionClaim(RuntimeError):
    pass


# Native customer execution attaches to bank-owned records; legacy state-transform
# operations below remain available for existing SDK consumers.
REFERENCE_PROFILE = "remote-domain.native-reference-state.v1"
NATIVE_STATEMENT_KIND = "credit_card_statement_close"
MAX_NATIVE_TIME = 253402300799998
_ACCOUNT_REPOSITORY = None
_ACCOUNT_REPOSITORY_LOCK = threading.Lock()


def get_account_repository():
    global _ACCOUNT_REPOSITORY
    if os.getenv("BANKING_BACKEND_ENABLED", "false").lower() != "true":
        return None
    with _ACCOUNT_REPOSITORY_LOCK:
        if _ACCOUNT_REPOSITORY is None:
            from banking_remote_domain_python.accounts import BankingAccountRepository
            dsn = os.getenv("BANKING_BACKEND_DATABASE_URL", "").strip()
            if not dsn:
                raise ValueError("BANKING_BACKEND_DATABASE_URL is required when the bank backend is enabled")
            _ACCOUNT_REPOSITORY = BankingAccountRepository(
                dsn, max_pool_size=int_env("BANKING_BACKEND_MAX_POOL_SIZE", 8))
        return _ACCOUNT_REPOSITORY


def close_account_repository() -> None:
    global _ACCOUNT_REPOSITORY
    with _ACCOUNT_REPOSITORY_LOCK:
        if _ACCOUNT_REPOSITORY is not None:
            _ACCOUNT_REPOSITORY.close()
            _ACCOUNT_REPOSITORY = None


def native_reference_enabled() -> bool:
    enabled = os.getenv("ASTRAFORM_NATIVE_SIMULATION_ENABLED", "false").lower() == "true"
    if enabled and os.getenv("ASTRAFORM_NATIVE_SIMULATION_PROFILE", REFERENCE_PROFILE) != REFERENCE_PROFILE:
        raise ValueError("Python native simulation requires the transactional reference-state profile")
    if enabled and get_account_repository() is None:
        raise ValueError("Native reference-state execution requires the bank backend")
    return enabled


def native_integer(value: Any) -> int:
    if type(value) is not int or not 0 <= value <= 9_007_199_254_740_991:
        raise ValueError("Native values must be nonnegative exact JSON integers")
    return value


def native_fields(value: Any, fields: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError("Native reference fields do not match the contract")
    return value


def native_identity(hints: dict[str, Any]) -> dict[str, Any]:
    from banking_remote_domain_python.accounts import reference
    if (hints.get("profile") != REFERENCE_PROFILE
            or not isinstance(hints.get("contextId"), str)
            or not hints["contextId"].strip() or len(hints["contextId"]) > 256):
        raise ValueError("Invalid native reference context identity")
    customer = native_fields(hints.get("customerContext"), {"contextRef", "subjectRef"})
    reference(customer["contextRef"])
    reference(customer["subjectRef"])
    return customer


def native_metadata(context_id: str, customer: dict[str, Any], state_version: int,
                    resource_version: int, through: int, horizon: int,
                    state: dict[str, Any]) -> dict[str, Any]:
    native_integer(state_version)
    native_integer(resource_version)
    result = {
        "profile": REFERENCE_PROFILE, "contextId": context_id,
        "stateVersion": state_version, "resourceVersion": resource_version,
        "processedThroughEpochMs": through, "logicalHorizonEpochMs": horizon,
        "customerContext": customer,
    }
    current = datetime.fromtimestamp(through / 1000, tz=UTC)
    next_due = horizon + 1
    if (current.year, current.month) < (9999, 12):
        next_month = current.replace(year=current.year + (current.month == 12),
                                     month=1 if current.month == 12 else current.month + 1,
                                     day=1, hour=0, minute=0, second=0, microsecond=0)
        next_due = int(next_month.timestamp() * 1000)
    if next_due <= horizon and any(is_indebted_credit_card_account(a) for a in state.get("accounts", [])):
        result["nextDueEpochMs"] = next_due
    else:
        result["noWorkBeforeEpochMs"] = horizon + 1
    return result


def native_time_utc(epoch_ms: int) -> str:
    instant = datetime(1970, 1, 1, tzinfo=UTC) + timedelta(milliseconds=epoch_ms)
    precision = "milliseconds" if epoch_ms % 1000 else "seconds"
    return instant.isoformat(timespec=precision).replace("+00:00", "Z")


def native_projection(metadata: dict[str, Any], events: list[dict[str, Any]]) -> dict[str, Any]:
    visible = {"eventType", "accountId", "billId", "statementStartDate", "statementEndDate"}
    observation = {
        "domainId": DOMAIN_ID, "observedAtEpochMs": metadata["processedThroughEpochMs"],
        "observedAtUtc": native_time_utc(metadata["processedThroughEpochMs"]),
        "notifications": [{k: v for k, v in event.items() if k in visible} for event in events],
    }
    if "nextDueEpochMs" in metadata:
        observation["nextStatementAtEpochMs"] = metadata["nextDueEpochMs"]
        observation["nextStatementAtUtc"] = native_time_utc(metadata["nextDueEpochMs"])
    return build_projection(runtime_metadata={"nativeSimulation": {**metadata, "observation": observation}})


def native_response(payload: dict[str, Any], subject: str, metadata: dict[str, Any],
                    events: list[dict[str, Any]], work: dict[str, Any]) -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION, "requestId": payload["requestId"],
        "operation": payload["operation"],
        "result": {"runtimeIdentity": subject, "projection": native_projection(metadata, events),
                   "evidenceEvents": events, "domainSystemWork": work},
        "nextState": {"schemaVersion": SCHEMA_VERSION, "data": {"nativeSimulation": metadata}},
        "warnings": [],
    }


def native_reference_call(payload: dict[str, Any], callback):
    from banking_remote_domain_python.accounts import BankingAccountError
    try:
        if not native_reference_enabled():
            raise ValueError("Native reference-state simulation is not enabled")
        return callback()
    except BankingAccountError as error:
        unavailable = error.status_code == 503
        raise ProtocolError(error.status_code,
                            "banking_backend_unavailable" if unavailable else "banking_reference_rejected",
                            "transient_failure" if unavailable else "domain_rejected", error.detail,
                            request_id=payload.get("requestId"), operation=payload.get("operation"),
                            retryable=unavailable) from error
    except ValueError as error:
        raise ProtocolError(422, "banking_reference_rejected", "domain_rejected", str(error),
                            request_id=payload.get("requestId"), operation=payload.get("operation")) from error


def prepare_reference(payload: dict[str, Any], hints: dict[str, Any]) -> dict[str, Any]:
    from banking_remote_domain_python.accounts import BankingTransition
    native_fields(hints, {"profile", "contextId", "expectedStateVersion", "logicalTimeEpochMs",
                          "logicalHorizonEpochMs", "customerContext"})
    customer = native_identity(hints)

    def attach(resource):
        start = native_integer(hints["logicalTimeEpochMs"])
        horizon = native_integer(hints["logicalHorizonEpochMs"])
        if horizon < start or horizon > MAX_NATIVE_TIME:
            raise ValueError("Native horizon must be at or after start and within year 9999")
        if (native_integer(hints["expectedStateVersion"]) != 0 or payload.get("state") is not None
                or resource.metadata or resource.logical_time != start):
            raise ValueError("Prepare requires an unowned prepopulated customer at its inclusive logical time")
        configuration = payload["payload"].get("personaConfiguration", {})
        if not isinstance(configuration, dict) or set(configuration) - {"decisionTriggerPolicy"}:
            raise ValueError("Native preparation accepts notification policy only; prepopulate the bank separately")
        normalized_configuration(configuration.get("decisionTriggerPolicy"))
        metadata = native_metadata(hints["contextId"], customer, 1, resource.version, start, horizon, resource.state)
        work = {"windowStartEpochMs": start, "windowEndEpochMs": start,
                "evaluatedWorkCount": 0, "executedWorkCount": 0}
        response = native_response(payload, resource.subject_ref, metadata, [], work)
        return BankingTransition(response, resource.state, metadata,
                                 {"agentId": payload["agent"]["agentId"], "personaConfiguration": configuration})

    return get_account_repository().transact(customer["contextRef"], customer["subjectRef"],
        payload["idempotencyKey"], payload, payload["deadlineEpochMs"], attach)


def execute_reference_window(payload: dict[str, Any], hints: dict[str, Any]) -> dict[str, Any]:
    from banking_remote_domain_python.accounts import BankingTransition
    native_fields(hints, {"profile", "contextId", "expectedStateVersion", "logicalHorizonEpochMs",
                          "customerContext", "resourceVersion"})
    customer = native_identity(hints)

    def advance(resource):
        previous = resource.metadata
        window = payload["payload"]
        start = native_integer(window["timeWindow"]["previousTimeEpochMs"])
        through = native_integer(window["timeWindow"]["currentTimeEpochMs"])
        expected_checkpoint = {"schemaVersion": SCHEMA_VERSION, "data": {"nativeSimulation": previous}}
        if (not previous or hints["contextId"] != previous["contextId"]
                or customer != previous["customerContext"]
                or resource.configuration.get("agentId") != payload["agent"]["agentId"]
                or native_integer(hints["expectedStateVersion"]) != previous["stateVersion"]
                or native_integer(hints["resourceVersion"]) != resource.version
                or native_integer(hints["logicalHorizonEpochMs"]) != previous["logicalHorizonEpochMs"]
                or payload.get("state") != expected_checkpoint
                or start != resource.logical_time or not start < through <= previous["logicalHorizonEpochMs"]
                or through > previous.get("nextDueEpochMs", previous.get("noWorkBeforeEpochMs", -1))
                or window.get("workKinds") != [NATIVE_STATEMENT_KIND]
                or type(window.get("maxWorkItems")) is not int or window["maxWorkItems"] != 1
                or window.get("executionMode") != "EVALUATE_AND_EXECUTE"):
            raise ValueError("Window must match the authoritative customer, owner, versions and earliest logical boundary")
        state = resource.state
        # Only the next advertised monthly boundary can generate work. Existing
        # banking functions retain all calculations; no loop expands a horizon.
        due = domain_system_due_work(state, window, start, through, payload["requestId"]) if through == previous.get("nextDueEpochMs") else []
        events = []
        results = []
        for item in due:
            item_events = close_credit_card_statement(state, item, item["payload"])
            if any(event.get("outcome") == "REJECTED" for event in item_events):
                raise ValueError("Banking statement update rejected; transaction rolled back")
            events.extend(item_events)
            results.append({"workId": item["workId"], "kind": NATIVE_STATEMENT_KIND,
                "occurrenceKey": item["occurrenceKey"], "scheduledForEpochMs": item["scheduledForEpochMs"],
                "outcome": "APPLIED" if item_events else "SKIPPED", "details": {"evidenceEventCount": len(item_events)}})
        state["lastEvidenceEvents"] = events
        metadata = native_metadata(hints["contextId"], customer, previous["stateVersion"] + 1,
                                   resource.version + 1, through, previous["logicalHorizonEpochMs"], state)
        wrapped = [evidence_event(payload, event_type=e["eventType"], source=DOMAIN_ID,
                                 outcome=e["outcome"], payload=e) for e in events]
        triggers = build_decision_triggers_from_evidence(wrapped, through,
            resource.configuration.get("personaConfiguration", {}).get("decisionTriggerPolicy"))
        visible = {"eventType", "accountId", "billId", "statementStartDate", "statementEndDate"}
        for trigger in triggers:
            trigger["payload"] = {k: v for k, v in trigger.get("payload", {}).items() if k in visible}
        hint = ({"nextDueEpochMs": metadata["nextDueEpochMs"], "workKinds": [NATIVE_STATEMENT_KIND]}
                if "nextDueEpochMs" in metadata else {"noWorkBeforeEpochMs": metadata["noWorkBeforeEpochMs"]})
        work = {"windowStartEpochMs": start, "windowEndEpochMs": through,
                "evaluatedWorkCount": len(due), "executedWorkCount": sum(r["outcome"] == "APPLIED" for r in results),
                "workResults": results, "stateDeltas": events, "nextDueHints": [hint], "decisionTriggers": triggers}
        response = native_response(payload, resource.subject_ref, metadata, events, work)
        return BankingTransition(response, state, metadata, resource.configuration)

    return get_account_repository().transact(customer["contextRef"], customer["subjectRef"],
        payload["idempotencyKey"], payload, payload["deadlineEpochMs"], advance)


def reject_reference_mutation(payload: dict[str, Any]) -> None:
    if isinstance(payload.get("state", {}).get("data", {}).get("nativeSimulation"), dict):
        raise ProtocolError(422, "banking_reference_rejected", "domain_rejected",
                            "Reference-state work uses scheduled windows and customer reads use MCP",
                            request_id=payload.get("requestId"), operation=payload.get("operation"))


def manifest() -> dict[str, Any]:
    mutation_timeout_ms = max(1_000, int_env("BANKING_PYTHON_REMOTE_MUTATION_TIMEOUT_MS", 30_000))
    projection_timeout_ms = max(1_000, int_env("BANKING_PYTHON_REMOTE_PROJECTION_TIMEOUT_MS", 5_000))
    result = {
        "protocolVersion": PROTOCOL_VERSION,
        "domain": {
            "domainId": DOMAIN_ID,
            "displayName": DISPLAY_NAME,
            "description": "Python banking reference provider used to prove remote-domain parity without Java internals.",
            "schemaVersion": SCHEMA_VERSION,
        },
        "transport": {
            "mode": "SYNCHRONOUS_HTTP_JSON",
            "contentType": MEDIA_TYPE,
        },
        "operations": {
            "prepare": {
                "method": "POST",
                "path": "/api/remote-domain/v1/prepare",
                "timeoutMs": mutation_timeout_ms,
            },
            "executeWork": {
                "method": "POST",
                "path": "/api/remote-domain/v1/execute-work",
                "timeoutMs": mutation_timeout_ms,
            },
            "domainSystemWorkWindow": {
                "method": "POST",
                "path": "/api/remote-domain/v1/domain-system-work-window",
                "timeoutMs": mutation_timeout_ms,
            },
            "controlAction": {
                "method": "POST",
                "path": "/api/remote-domain/v1/control-actions:execute",
                "timeoutMs": mutation_timeout_ms,
            },
            "status": {"method": "POST", "path": "/api/remote-domain/v1/status", "timeoutMs": projection_timeout_ms},
            "inspection": {
                "method": "POST",
                "path": "/api/remote-domain/v1/inspection",
                "timeoutMs": projection_timeout_ms,
            },
            "shutdown": {
                "method": "POST",
                "path": "/api/remote-domain/v1/shutdown",
                "timeoutMs": projection_timeout_ms,
            },
        },
        "capabilities": {
            "statusProjection": True,
            "inspectionProjection": True,
            "shutdownNotification": True,
            "maxOpaqueStateBytes": 262144,
            "supportedAgentTypes": ["Customer", "Policy", "Operator"],
            "supportedInteractionModes": ["SIMULATION", "HYBRID", "A2A_SESSION"],
            "supportsInitialAccounts": True,
            "supportsScheduledWork": True,
            "scheduledWorkKinds": [
                {
                    "kind": "transaction",
                    "label": "Transaction",
                    "description": "Banking scheduled transaction work carried over the remote-domain.v1 boundary.",
                    "fields": [
                        {"id": "type", "path": "payload.type", "label": "Type", "control": "TEXT"},
                        {"id": "amount", "path": "payload.amount", "label": "Amount", "control": "NUMBER"},
                        {"id": "currency", "path": "payload.currency", "label": "Currency", "control": "TEXT"},
                        {"id": "details", "path": "payload.details", "label": "Details", "control": "OBJECT"},
                    ],
                },
                {
                    "kind": "post_double_entry_transaction",
                    "label": "Post Transaction",
                    "description": "Post a deterministic transaction into the Python-owned banking state.",
                    "fields": [
                        {"id": "amount", "path": "payload.amount", "label": "Amount", "control": "NUMBER"},
                        {"id": "currency", "path": "payload.currency", "label": "Currency", "control": "TEXT"},
                        {"id": "description", "path": "payload.description", "label": "Description", "control": "TEXT"},
                        {"id": "counterparty", "path": "payload.counterparty", "label": "Counterparty", "control": "TEXT"},
                    ],
                },
            ],
            "supportsDomainSystemWorkWindow": True,
            "domainSystemWorkKinds": [
                {
                    "kind": "monthly_interest_accrual",
                    "label": "Monthly Interest Accrual",
                    "description": "Apply simple interest to deposit balances from a domain-owned time window.",
                    "fields": [{"id": "annualRate", "path": "payload.annualRate", "label": "Annual Rate", "control": "NUMBER"}],
                },
                {
                    "kind": "loan_interest_accrual",
                    "label": "Loan interest accrual",
                    "description": "Apply monthly loan interest and optional home-loan repayment progression.",
                    "fields": [
                        {"id": "accountName", "path": "payload.accountName", "label": "Account", "control": "TEXT"},
                        {"id": "productCode", "path": "payload.productCode", "label": "Product", "control": "TEXT"},
                        {"id": "annualRate", "path": "payload.annualRate", "label": "Annual Rate", "control": "NUMBER"},
                        {
                            "id": "repaymentBaseAmount",
                            "path": "payload.repaymentBaseAmount",
                            "label": "Repayment Base",
                            "control": "NUMBER",
                        },
                    ],
                },
                {
                    "kind": "credit_card_interest_accrual",
                    "label": "Credit card interest accrual",
                    "description": "Apply monthly credit-card interest and minimum-payment calculation.",
                    "fields": [
                        {"id": "accountName", "path": "payload.accountName", "label": "Account", "control": "TEXT"},
                        {"id": "annualRate", "path": "payload.annualRate", "label": "Annual Rate", "control": "NUMBER"},
                        {
                            "id": "minPaymentPercentage",
                            "path": "payload.minPaymentPercentage",
                            "label": "Minimum Payment %",
                            "control": "NUMBER",
                        },
                    ],
                },
                {
                    "kind": "credit_card_statement_close",
                    "label": "Credit card statement close",
                    "description": "Close a credit-card statement, generate the bill, and roll the statement window.",
                    "fields": [
                        {"id": "accountName", "path": "payload.accountName", "label": "Account", "control": "TEXT"},
                        {
                            "id": "statementStartDate",
                            "path": "payload.statementStartDate",
                            "label": "Statement Start",
                            "control": "TEXT",
                        },
                        {
                            "id": "statementEndDate",
                            "path": "payload.statementEndDate",
                            "label": "Statement End",
                            "control": "TEXT",
                        },
                        {"id": "dueDays", "path": "payload.dueDays", "label": "Due Days", "control": "NUMBER"},
                    ],
                },
                {
                    "kind": RATE_CHANGE_WORK_KIND,
                    "label": "Credit card rate change",
                    "description": "Apply a launch-pinned one-time credit-card rate change at its scheduled simulation time.",
                    "fields": [],
                },
            ],
            "supportsControlActions": True,
            "controlActions": [
                {
                    "actionId": "broadcast-message",
                    "label": "Broadcast message",
                    "description": "Apply a domain-owned operator message to targeted banking customers.",
                    "category": "BROADCAST",
                    "supportedModes": ["CLOCK_DRIVEN_HYBRID"],
                    "targetScopes": ["BROADCAST", "SEGMENT", "AGENT"],
                    "inputSchema": {
                        "type": "object",
                        "required": ["message"],
                        "properties": {
                            "message": {"type": "string"},
                            "severity": {"type": "string"},
                        },
                    },
                    "uiSchema": {
                        "fields": [
                            {"id": "message", "label": "Message", "control": "textarea"},
                            {"id": "severity", "label": "Severity", "control": "select"},
                        ]
                    },
                    "sideEffectLevel": "LOW",
                    "evidenceRequirements": ["domain evidence event", "updated opaque state"],
                    "limitations": ["The host must fan out broadcast targets; the domain only applies the action it receives."],
                },
                {
                    "actionId": RATE_CHANGE_PLAN_ACTION_ID,
                    "label": "Install rate-change plan",
                    "description": "Install the launch-pinned clock-driven rate-change plan.",
                    "category": "POLICY_CHANGE",
                    "supportedModes": ["CLOCK_DRIVEN_HYBRID"],
                    "targetScopes": ["BROADCAST"],
                    "requiredCapabilities": [],
                    "inputSchema": {
                        "type": "object",
                        "required": ["schemaVersion", "planDigest", "plan"],
                        "additionalProperties": False,
                        "properties": {
                            "schemaVersion": {
                                "type": "string",
                                "const": "banking.clock-driven-intervention-plan-snapshot.v1",
                            },
                            "planDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                            "plan": {
                                "type": "object",
                                "additionalProperties": False,
                                "required": [
                                    "schemaVersion", "simulationRunId", "interventionId",
                                    "interventionType", "productCode", "expectedPreviousRate",
                                    "newRate", "scheduledOffsetDays", "simulationStartEpochMs",
                                    "scheduledForEpochMs", "effectiveAtEpochMs",
                                    "mutationOperationId", "workId",
                                ],
                                "properties": {
                                    "schemaVersion": {"type": "string", "const": RATE_CHANGE_PLAN_SCHEMA_VERSION},
                                    "simulationRunId": {"type": "string", "minLength": 1},
                                    "interventionId": {"type": "string", "const": "policy_rate_candidate_shift"},
                                    "interventionType": {"type": "string", "const": "RATE_CHANGE"},
                                    "productCode": {"type": "string", "const": RATE_CHANGE_SUPPORTED_PRODUCT_CODE},
                                    "expectedPreviousRate": {"type": "string", "const": "0.19"},
                                    "newRate": {"type": "string", "const": "0.24"},
                                    "scheduledOffsetDays": {"type": "integer", "const": 10},
                                    "simulationStartEpochMs": {"type": "integer", "minimum": 1, "maximum": 9_007_199_254_740_991},
                                    "scheduledForEpochMs": {"type": "integer", "minimum": 1, "maximum": 9_007_199_254_740_991},
                                    "effectiveAtEpochMs": {"type": "integer", "minimum": 1, "maximum": 9_007_199_254_740_991},
                                    "mutationOperationId": {"type": "string", "pattern": "^rate-change:[0-9a-f]{64}$"},
                                    "workId": {"type": "string", "pattern": "^rate-change-work:[0-9a-f]{32}$"},
                                },
                            },
                        },
                    },
                    "uiSchema": {"fields": [], "readOnly": True},
                    "sideEffectLevel": "HIGH",
                    "evidenceRequirements": ["updated opaque state", "installed plan digest"],
                    "limitations": ["This action accepts only the canonical day-10 CC001 plan."],
                }
            ],
            "tools": [
                {
                    "name": "get_product_list",
                    "description": "Retrieves the banking products available in the Python remote domain.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"productCategory": {"type": "string"}},
                    },
                },
                {
                    "name": "post_double_entry_transaction",
                    "description": "Posts a balanced double-entry transaction against Python-owned banking state.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "debits": {"type": "array"},
                            "credits": {"type": "array"},
                            "description": {"type": "string"},
                        },
                    },
                },
            ],
            "remoteProviderLanguage": "python",
        },
        "uiProfile": banking_ui_profile(),
    }

    if native_reference_enabled():
        native = {
            "profile": REFERENCE_PROFILE, "providerRevision": WIND_TUNNEL_PROVIDER_REVISION,
            "mutationMode": "TRANSACTIONAL_REFERENCE_STATE", "stateScope": "AGENT",
            "workKinds": [NATIVE_STATEMENT_KIND], "maxWindowWorkItems": 1,
            "initialBoundaryMode": "ATTACH_PREPOPULATED_STATE",
        }
        # Validate the opt-in declaration using the published SDK's canonical schema.
        declared = build_manifest(domain_id=DOMAIN_ID, display_name=DISPLAY_NAME,
            description=result["domain"]["description"], schema_version=SCHEMA_VERSION,
            native_simulation=native)
        result["capabilities"]["nativeSimulation"] = declared["capabilities"]["nativeSimulation"]
    return result


def population_catalog() -> dict[str, Any]:
    catalog_file = (
        resources.files("banking_remote_domain_python")
        .joinpath("population-catalogs")
        .joinpath("banking-python.population-catalog.json")
    )
    return load_population_catalog(catalog_file)


def prepare(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=False, require_idempotency=True)
    hints = payload.get("payload", {}).get("initialProjectionHints", {}).get("nativeSimulation")
    if hints is not None:
        return native_reference_call(payload, lambda: prepare_reference(payload, hints))
    persona = payload["payload"]["personaConfiguration"]
    persona_name = trim(persona.get("personaName"))
    if not persona_name:
        raise ProtocolError(
            422,
            "unsupported_persona_configuration",
            "domain_rejected",
            "banking-python requires personaConfiguration.personaName.",
            request_id=payload.get("requestId"),
            operation="prepare",
            details={"field": "payload.personaConfiguration.personaName"},
        )

    try:
        accounts = normalize_accounts(persona.get("initialAccounts"))
    except ValueError as error:
        raise ProtocolError(
            422,
            "duplicate_initial_account_id",
            "invalid_request",
            str(error),
            request_id=payload.get("requestId"),
            operation="prepare",
            details={"field": "payload.personaConfiguration.initialAccounts"},
        ) from error
    scheduled_work = normalize_scheduled_work(persona.get("scheduledWork"))
    balance_total = sum(Decimal(str(account["balance"])) for account in accounts)
    try:
        decision_trigger_policy = normalized_configuration(persona.get("decisionTriggerPolicy"))
    except ValueError as error:
        raise ProtocolError(
            422,
            "invalid_decision_trigger_policy",
            "invalid_request",
            str(error),
            request_id=payload.get("requestId"),
            operation="prepare",
            details={"field": "payload.personaConfiguration.decisionTriggerPolicy"},
        ) from error
    state = {
        "domainId": DOMAIN_ID,
        "schemaVersion": SCHEMA_VERSION,
        "agentId": trim(payload.get("agent", {}).get("agentId")),
        "personaName": persona_name,
        "customerId": safe_token(persona_name),
        "agentType": trim(persona.get("agentType")) or "Customer",
        "interactionMode": trim(persona.get("interactionMode")) or "SIMULATION",
        "segment": segment_from_traits(persona.get("traits", [])),
        "accounts": accounts,
        "scheduledWork": scheduled_work,
        "decisionTriggerPolicy": decision_trigger_policy,
        "executionLog": [],
        "executedWorkCount": 0,
        "appliedTransactionCount": 0,
        "rejectedTransactionCount": 0,
        "appliedDomainSystemWorkCount": 0,
        "transactions": [],
        "transactionPostings": [],
        "creditCardBills": [],
        "hardship": {
            "reviewCount": 0,
            "reliefGranted": Decimal("0"),
            "flagged": balance_total < Decimal("500"),
        },
        "lastAction": "prepared_python_banking_state",
    }
    return success_envelope(
        payload,
        runtime_identity=f"{DOMAIN_ID}::{state['customerId']}",
        state=state,
        evidence_events=[
            evidence_event(
                payload,
                event_type="python_banking_state_prepared",
                source="banking-python",
                outcome="SUCCESS",
                payload={
                    "accountCount": len(accounts),
                    "scheduledWorkCount": len(scheduled_work),
                    "segment": state["segment"],
                },
            )
        ],
    )


def execute_work(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=True)
    reject_reference_mutation(payload)
    state = read_state(payload)
    due_work = payload.get("payload", {}).get("turnContext", {}).get("dueWork", []) or []
    evidence_events = []
    response_tool_result: dict[str, Any] | None = None

    for work in due_work:
        if is_mutating_work(work) and has_applied_work(state, work):
            replay_result = idempotent_replay_result(work)
            response_tool_result = replay_result
            work_events = [idempotent_replay_event(work, replay_result)]
        else:
            work_payload = dict(work.get("payload", {}) or {})
            kind = (
                trim(work_payload.get("workKind"))
                or trim(work_payload.get("kind"))
                or trim(work.get("kind"))
                or trim(work.get("workType"))
            )
            if is_tool_call(work):
                work_events = [apply_tool_call(state, work)]
            elif kind in {"transaction", "post_double_entry_transaction"}:
                work_events = [post_transaction(state, work, work_payload)]
            elif kind == "monthly_interest_accrual":
                interest_event = accrue_interest(state, work, work_payload)
                work_events = [interest_event] if interest_event else []
            elif kind == "loan_interest_accrual":
                work_events = accrue_loan_interest(state, work, work_payload)
            elif kind == "credit_card_interest_accrual":
                work_events = accrue_credit_card_interest(state, work, work_payload)
            elif kind == "credit_card_statement_close":
                work_events = close_credit_card_statement(state, work, work_payload)
            elif kind == "hardship_review":
                work_events = [run_hardship_review(state, work, work_payload)]
            else:
                work_events = [record_generic_work(state, work, work_payload)]
            if is_mutating_work(work):
                record_successful_work_receipt(state, work, work_events)
        for event in work_events:
            if isinstance(event.get("toolResult"), dict):
                response_tool_result = dict(event["toolResult"])
            recorded_event = evidence_event(
                payload,
                event_type=event["eventType"],
                source="banking-python",
                outcome=event["outcome"],
                severity=event.get("severity", "INFO"),
                payload=event,
            )
            if trim(event.get("workId")):
                recorded_event["workId"] = trim(event.get("workId"))
            evidence_events.append(recorded_event)

    if not due_work:
        state["lastAction"] = "no_due_work_received"
        evidence_events.append(evidence_event(
            payload,
            event_type="python_banking_no_due_work",
            source="banking-python",
            outcome="NOOP",
            payload={"reason": "No due work supplied by host."},
        ))

    return success_envelope(
        payload,
        runtime_identity=payload.get("agent", {}).get("runtimeIdentity")
        or payload.get("agent", {}).get("agentId")
        or f"{DOMAIN_ID}::{state.get('customerId', 'unknown')}",
        state=state,
        evidence_events=evidence_events,
        tool_result=response_tool_result,
    )


def execute_domain_system_work_window(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=True)
    hints = payload.get("payload", {}).get("context", {}).get("nativeSimulation")
    if hints is not None:
        return native_reference_call(payload, lambda: execute_reference_window(payload, hints))
    reject_reference_mutation(payload)
    if trim(payload.get("operation")) != "domain-system-work-window":
        raise ProtocolError(
            400,
            "invalid_operation",
            "invalid_request",
            "Expected operation 'domain-system-work-window'.",
            request_id=payload.get("requestId"),
            operation=payload.get("operation"),
            details={"expected": "domain-system-work-window", "actual": payload.get("operation")},
        )
    window_payload = payload.get("payload", {}) or {}
    time_window = window_payload.get("timeWindow", {}) or {}
    current_epoch_ms = int(time_window.get("currentTimeEpochMs") or payload.get("sentAtEpochMs") or 0)
    previous_epoch_ms = int(time_window.get("previousTimeEpochMs") or current_epoch_ms)
    execution_mode = trim(window_payload.get("executionMode")) or "EVALUATE_AND_EXECUTE"
    evaluate_only = execution_mode.upper() == "EVALUATE_ONLY"
    state = read_state(payload)
    due_work = domain_system_due_work(
        state,
        window_payload,
        previous_epoch_ms,
        current_epoch_ms,
        trim(payload.get("requestId")),
    )
    evidence_events = []
    work_results = []

    if evaluate_only:
        for work in due_work:
            work_results.append({
                "workId": trim(work.get("workId")) or "domain-system-work",
                "kind": trim((work.get("payload") or {}).get("kind")) or trim(work.get("kind")),
                "occurrenceKey": trim(work.get("occurrenceKey")),
                "outcome": "EVALUATED",
                "scheduledForEpochMs": work.get("scheduledForEpochMs") or current_epoch_ms,
                "details": {
                    "description": trim(work.get("description")),
                    "evidenceEventCount": 0,
                },
            })
    else:
        for work in due_work:
            work_payload = dict(work.get("payload", {}) or {})
            kind = trim(work_payload.get("kind")) or trim(work.get("kind"))
            rejected = False
            if kind == "monthly_interest_accrual":
                interest_event = accrue_interest(state, work, work_payload)
                work_events = [interest_event] if interest_event else []
            elif kind == RATE_CHANGE_WORK_KIND:
                try:
                    work_events = apply_credit_card_rate_change(
                        state, work, work_payload, current_epoch_ms
                    )
                except ValueError:
                    rejected = True
                    work_events = []
                    state["rejectedTransactionCount"] = int(
                        state.get("rejectedTransactionCount", 0)
                    ) + 1
                    state.setdefault("executionLog", []).append(
                        {
                            **work,
                            "status": "REJECTED",
                            "failureCode": "RATE_CHANGE_WORK_CONTRADICTORY",
                        }
                    )
                    state["lastAction"] = "remote_domain_work_rejected"
            elif kind == "loan_interest_accrual":
                work_events = accrue_loan_interest(state, work, work_payload)
            elif kind == "credit_card_interest_accrual":
                work_events = accrue_credit_card_interest(state, work, work_payload)
            elif kind == "credit_card_statement_close":
                work_events = close_credit_card_statement(state, work, work_payload)
            else:
                work_events = [record_generic_work(state, work, work_payload)]
            work_evidence_events = []
            for event in work_events:
                wrapped_event = evidence_event(
                    payload,
                    event_type=event["eventType"],
                    source="banking-python",
                    outcome=event["outcome"],
                    severity=event.get("severity", "INFO"),
                    payload=event,
                )
                evidence_events.append(wrapped_event)
                work_evidence_events.append(wrapped_event)
            applied = any(trim(event.get("outcome")).upper() == "SUCCESS" for event in work_events)
            work_results.append({
                "workId": trim(work.get("workId")) or "domain-system-work",
                "kind": kind,
                "occurrenceKey": trim(work.get("occurrenceKey")),
                "outcome": "REJECTED" if rejected else ("APPLIED" if applied else "SKIPPED"),
                "scheduledForEpochMs": work.get("scheduledForEpochMs") or current_epoch_ms,
                "details": {
                    "description": trim(work.get("description")),
                    "evidenceEventCount": len(work_evidence_events),
                },
            })

    domain_system_work = {
        "windowStartEpochMs": previous_epoch_ms,
        "windowEndEpochMs": current_epoch_ms,
        "evaluatedWorkCount": len(due_work),
        "executedWorkCount": 0 if evaluate_only else sum(1 for result in work_results if result["outcome"] == "APPLIED"),
        "workResults": work_results,
        "stateDeltas": [event["payload"] for event in evidence_events],
        "checkpointRefs": [
            {
                "schemaVersion": SCHEMA_VERSION,
                "stateScope": "agent",
                "runtimeIdentity": payload.get("agent", {}).get("runtimeIdentity")
                or payload.get("agent", {}).get("agentId"),
            }
        ],
        "nextDueHints": domain_system_next_due_hints(
            current_epoch_ms,
            due_work,
            state,
            window_payload.get("workKinds", []) or [],
        ),
        "decisionTriggers": []
        if evaluate_only
        else build_decision_triggers_from_evidence(
            evidence_events,
            current_epoch_ms,
            state.get("decisionTriggerPolicy"),
        ),
    }
    return success_envelope(
        payload,
        runtime_identity=payload.get("agent", {}).get("runtimeIdentity")
        or payload.get("agent", {}).get("agentId")
        or f"{DOMAIN_ID}::{state.get('customerId', 'unknown')}",
        state=state,
        evidence_events=evidence_events,
        domain_system_work=domain_system_work,
    )


def execute_control_action(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=True)
    reject_reference_mutation(payload)
    if trim(payload.get("operation")) != "control-action":
        raise ProtocolError(
            400,
            "invalid_operation",
            "invalid_request",
            "Expected operation 'control-action'.",
            request_id=payload.get("requestId"),
            operation=payload.get("operation"),
            details={"expected": "control-action", "actual": payload.get("operation")},
        )
    action_payload = payload.get("payload", {}) or {}
    action_id = trim(action_payload.get("actionId"))
    operation_id = trim(action_payload.get("operationId"))
    if not trim(action_payload.get("operationId")):
        raise ProtocolError(
            400,
            "missing_operation_id",
            "invalid_request",
            "control-action payload.operationId is required.",
            request_id=payload.get("requestId"),
            operation="control-action",
            details={"field": "payload.operationId"},
        )
    if action_id not in {"broadcast-message", RATE_CHANGE_PLAN_ACTION_ID}:
        raise ProtocolError(
            422,
            "unsupported_control_action",
            "domain_rejected",
            f"Unsupported banking-python control action '{action_id}'.",
            request_id=payload.get("requestId"),
            operation="control-action",
            details={"actionId": action_id},
        )
    target = dict(action_payload.get("target", {}) or {})
    inputs = dict(action_payload.get("inputs", {}) or {})
    state = read_state(payload)
    existing = control_action_by_operation_id(state, operation_id)
    if existing:
        if not same_control_action(existing, action_id, target, inputs):
            raise ProtocolError(
                409,
                "control_action_idempotency_conflict",
                "invalid_request",
                "control-action operationId was already applied with different action, target, or inputs.",
                request_id=payload.get("requestId"),
                operation="control-action",
                details={"operationId": operation_id},
            )
        return success_envelope(
            payload,
            runtime_identity=payload.get("agent", {}).get("runtimeIdentity")
            or payload.get("agent", {}).get("agentId")
            or f"{DOMAIN_ID}::{state.get('customerId', 'unknown')}",
            state=state,
            evidence_events=[control_action_evidence_event(payload, existing)],
            control_action=existing,
        )
    rate_change_snapshot: RateChangePlanSnapshot | None = None
    if action_id == RATE_CHANGE_PLAN_ACTION_ID:
        rate_change_snapshot = require_rate_change_plan(inputs)
        target_scope = trim(target.get("scope")) or "AGENT"
        selector = target.get("selector") if isinstance(target.get("selector"), dict) else {}
        if (
            operation_id != rate_change_plan_install_operation_id(rate_change_snapshot)
            or target_scope.upper() != "BROADCAST"
            or trim(selector.get("simulationRunId"))
            != trim(rate_change_snapshot.plan.get("simulationRunId"))
        ):
            raise ProtocolError(
                400,
                "invalid_rate_change_plan_installation",
                "invalid_request",
                "Rate-change plan installation requires its digest-derived operationId and BROADCAST scope.",
                request_id=payload.get("requestId"),
                operation="control-action",
                details={"operationId": operation_id, "targetScope": target_scope},
            )
        install_rate_change_plan_in_state(
            state,
            rate_change_snapshot,
            int(payload.get("sentAtEpochMs") or 0),
        )
    elif not trim(inputs.get("message")):
        raise ProtocolError(
            422,
            "missing_control_action_message",
            "domain_rejected",
            "broadcast-message requires inputs.message.",
            request_id=payload.get("requestId"),
            operation="control-action",
            details={"field": "payload.inputs.message"},
        )
    applied = {
        "actionId": action_id,
        "operationId": operation_id,
        "idempotencyKey": payload.get("idempotencyKey"),
        "targetScope": trim(target.get("scope")) or "AGENT",
        "target": target,
        "inputs": inputs,
        "appliedAtEpochMs": payload.get("sentAtEpochMs"),
        "personaName": state.get("personaName"),
        "runtimeIdentity": payload.get("agent", {}).get("runtimeIdentity") or payload.get("agent", {}).get("agentId"),
    }
    if rate_change_snapshot is not None:
        applied.update(
            {
                "outcome": "PLAN_INSTALLED",
                "planDigest": rate_change_snapshot.plan_digest,
                "eligibleAccountCount": sum(
                    1
                    for account in state.get("accounts", []) or []
                    if trim(account.get("productCode")).upper()
                    == RATE_CHANGE_SUPPORTED_PRODUCT_CODE
                ),
            }
        )
    control_actions = list(state.get("controlActions", []) or [])
    control_actions.append(applied)
    state["controlActions"] = control_actions
    state["lastControlAction"] = applied
    state["lastAction"] = "control-action"
    evidence_events = [control_action_evidence_event(payload, applied)]
    return success_envelope(
        payload,
        runtime_identity=payload.get("agent", {}).get("runtimeIdentity")
        or payload.get("agent", {}).get("agentId")
        or f"{DOMAIN_ID}::{state.get('customerId', 'unknown')}",
        state=state,
        evidence_events=evidence_events,
        control_action=applied,
    )


def install_rate_change_plan_in_state(
    state: dict[str, Any],
    snapshot: RateChangePlanSnapshot,
    updated_at_epoch_ms: int,
) -> None:
    existing_raw = state.get("clockDrivenInterventionPlan")
    if existing_raw is not None:
        existing = require_rate_change_plan(existing_raw)
        if existing != snapshot:
            raise ValueError(
                "The banking state is already bound to a contradictory intervention plan"
            )
        return

    plan = snapshot.plan
    product_code = trim(plan.get("productCode"))
    baseline_rate = canonical_rate_change_decimal(
        plan.get("expectedPreviousRate"), "expectedPreviousRate"
    )
    accounts = [dict(account) for account in state.get("accounts", []) or []]
    eligible_accounts = [
        account
        for account in accounts
        if trim(account.get("productCode")).upper() == product_code.upper()
    ]
    for account in eligible_accounts:
        validate_account_rate(account, "interestRate", baseline_rate)
        validate_account_rate(account, "apr", baseline_rate)
    for account in eligible_accounts:
        account["interestRate"] = baseline_rate
        account["apr"] = baseline_rate
        account["ratePlanDigest"] = snapshot.plan_digest

    work_payload = {
        "kind": RATE_CHANGE_WORK_KIND,
        "scheduledForEpochMs": plan["scheduledForEpochMs"],
        "planSnapshot": snapshot.as_dict(),
    }
    scheduled_work = {
        "workId": plan["workId"],
        "kind": RATE_CHANGE_WORK_KIND,
        "frequency": "ONCE",
        "scheduledForEpochMs": plan["scheduledForEpochMs"],
        "occurrenceKey": f"banking|clock-driven-rate-change|{plan['mutationOperationId']}",
        "description": "Apply the launch-pinned CC001 rate change",
        "payload": work_payload,
    }
    scheduled = [dict(work) for work in state.get("scheduledWork", []) or []]
    for existing_work in scheduled:
        if trim(existing_work.get("workId")) != trim(plan.get("workId")):
            continue
        if existing_work != scheduled_work:
            raise ValueError(
                "Scheduled rate-change workId is already bound to contradictory facts"
            )
        state["accounts"] = accounts
        state["clockDrivenInterventionPlan"] = snapshot.as_dict()
        return
    scheduled.append(scheduled_work)
    state["accounts"] = accounts
    state["scheduledWork"] = scheduled
    state["clockDrivenInterventionPlan"] = snapshot.as_dict()
    state["lastAction"] = "clock-driven-intervention-plan-installed"
    state["lastUpdatedEpochMs"] = updated_at_epoch_ms


def validate_account_rate(
    account: dict[str, Any],
    field: str,
    expected_rate: str,
) -> None:
    raw = account.get(field)
    if raw is not None and canonical_rate_change_decimal(raw, f"account.{field}") != expected_rate:
        raise ValueError(
            f"Eligible account {field} contradicts the launch-pinned previous rate"
        )


def status(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=False)
    return projection_envelope(payload, read_state(payload))


def control_action_by_operation_id(state: dict[str, Any], operation_id: str) -> dict[str, Any] | None:
    if not operation_id:
        return None
    for action in state.get("controlActions", []) or []:
        if isinstance(action, dict) and trim(action.get("operationId")) == operation_id:
            return dict(action)
    return None


def same_control_action(
    existing: dict[str, Any],
    action_id: str,
    target: dict[str, Any],
    inputs: dict[str, Any],
) -> bool:
    existing_target = dict(existing.get("target", {}) or {})
    if not existing_target:
        existing_target = {"scope": trim(existing.get("targetScope")) or "AGENT"}
    return (
        trim(existing.get("actionId")) == trim(action_id)
        and existing_target == dict(target or {})
        and dict(existing.get("inputs", {}) or {}) == dict(inputs or {})
    )


def control_action_evidence_event(request: dict[str, Any], applied: dict[str, Any]) -> dict[str, Any]:
    operation_id = trim(applied.get("operationId")) or trim(request.get("idempotencyKey")) or trim(request.get("requestId"))
    event = evidence_event(
        request,
        event_type="python_banking_control_action_applied",
        source="banking-python",
        outcome="APPLIED",
        payload=applied,
    )
    event["eventId"] = f"control-action:{operation_id}:python_banking_control_action_applied"
    event["correlationId"] = operation_id
    event["simTimeEpochMs"] = applied.get("appliedAtEpochMs") or request.get("sentAtEpochMs")
    return event


def inspection(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=False)
    return projection_envelope(payload, read_state(payload))


def shutdown(payload: dict[str, Any]) -> dict[str, Any]:
    validate_base(payload, require_state=True, require_idempotency=False)
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "requestId": payload["requestId"],
        "operation": "shutdown",
        "acknowledged": True,
        "warnings": [],
    }


def product_catalog_response(product_category: str | None = None) -> dict[str, Any]:
    return {"data": {"products": product_catalog(product_category=product_category)}}


def banking_ui_profile() -> dict[str, Any]:
    return {
        "schemaVersion": "domain-ui-profile.v1",
        "kind": "SHARED_DEFAULT",
        "title": "Banking setup",
        "description": "Python banking remote-domain account seed metadata.",
        "creatorSections": [
            {
                "id": "initialAccountCatalog",
                "sectionId": "initialAccountCatalog",
                "title": "Initial account catalog",
                "description": "Shared account seeding choices published by the Python banking remote domain.",
                "fields": [
                    {
                        "id": "initialAccountProductCode",
                        "fieldId": "initialAccountProductCode",
                        "path": "initialAccounts.productCode",
                        "label": "Initial Account Product Code",
                        "control": "SELECT",
                        "required": True,
                        "helpText": "Product codes available when seeding initial banking accounts.",
                        "options": [
                            {"value": product["productCode"], "label": product["name"]}
                            for product in DEFAULT_PRODUCT_CATALOG
                        ],
                    }
                ],
            }
        ],
    }


def normalize_scheduled_work(raw_scheduled_work: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_scheduled_work, list):
        return []
    normalized: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_scheduled_work):
        if not isinstance(raw, dict):
            continue
        payload = flattened_payload(raw.get("payload"))
        kind = trim(raw.get("kind")) or trim(payload.get("kind")) or "transaction"
        item: dict[str, Any] = {
            "workId": trim(raw.get("workId")) or f"python-banking-work-{index + 1}",
            "kind": kind,
            "description": trim(raw.get("description")) or trim(payload.get("description")) or "Banking scheduled work",
            "payload": {
                key: value
                for key, value in payload.items()
                if key not in {"kind", "description", "frequency", "time", "startDate", "endDate"}
            },
        }
        for field in ("frequency", "time", "startDate", "endDate"):
            value = raw.get(field, payload.get(field))
            if trim(value):
                item[field] = value
        if not item["payload"]:
            item.pop("payload")
        normalized.append(item)
    return normalized


def domain_system_due_work(
    state: dict[str, Any],
    window_payload: dict[str, Any],
    previous_epoch_ms: int,
    current_epoch_ms: int,
    request_id: str,
) -> list[dict[str, Any]]:
    requested_kinds = {
        trim(kind).lower()
        for kind in window_payload.get("workKinds", []) or []
        if trim(kind)
    }
    max_work_items = max(1, int(window_payload.get("maxWorkItems") or 100))
    current_date = datetime.fromtimestamp(current_epoch_ms / 1000, tz=UTC).date()
    occurrence_period = current_date.replace(day=1).isoformat()
    scheduled_work = [
        dict(work)
        for work in state.get("scheduledWork", []) or []
        if isinstance(work, dict)
    ]
    due_work: list[dict[str, Any]] = []
    for work in scheduled_work:
        work_payload = dict(work.get("payload", {}) or {})
        kind = (trim(work.get("kind")) or trim(work_payload.get("kind"))).lower()
        if kind not in {
            "monthly_interest_accrual",
            "loan_interest_accrual",
            "credit_card_interest_accrual",
            "credit_card_statement_close",
            RATE_CHANGE_WORK_KIND,
        }:
            continue
        if requested_kinds and kind not in requested_kinds:
            continue
        if kind == RATE_CHANGE_WORK_KIND:
            work_item = rate_change_due_work_item(work, current_epoch_ms)
            if work_item is None or completed_domain_system_work(state, work_item):
                continue
            due_work.append(work_item)
            continue
        work_id = trim(work.get("workId")) or f"domain-system-{kind}"
        payload = dict(work_payload)
        payload.setdefault("kind", kind)
        work_item = {
            "workId": work_id,
            "kind": kind,
            "workType": "DOMAIN_SYSTEM_WORK",
            "occurrenceKey": f"{DOMAIN_ID}|domain-system|{work_id}|{occurrence_period}",
            "scheduledForEpochMs": current_epoch_ms,
            "attemptNumber": 1,
            "description": trim(work.get("description")) or kind.replace("_", " "),
            "payload": payload,
        }
        if completed_domain_system_work(state, work_item):
            continue
        due_work.append(work_item)
    default_statements = default_credit_card_statement_due_work(
        state,
        scheduled_work,
        requested_kinds,
        previous_epoch_ms,
        current_epoch_ms,
    )
    if len(due_work) + len(default_statements) > max_work_items:
        raise ProtocolError(
            422,
            "domain_system_work_window_capacity_exceeded",
            "domain_rejected",
            "The banking domain-system work window cannot publish every completed monthly statement "
            "inside maxWorkItems without losing evidence.",
            request_id=request_id,
            operation="domain-system-work-window",
            details={
                "maxWorkItems": max_work_items,
                "requiredWorkItems": len(due_work) + len(default_statements),
            },
        )
    due_work.extend(default_statements)
    due_work.sort(
        key=lambda work: (
            int(work["scheduledForEpochMs"]),
            0
            if (trim(work.get("kind")).lower() == RATE_CHANGE_WORK_KIND)
            else 1,
        )
    )
    return due_work


def rate_change_due_work_item(
    work: dict[str, Any],
    current_epoch_ms: int,
) -> dict[str, Any] | None:
    payload = dict(work.get("payload", {}) or {})
    scheduled_raw = work.get("scheduledForEpochMs", payload.get("scheduledForEpochMs"))
    if isinstance(scheduled_raw, bool) or not isinstance(scheduled_raw, int):
        raise ValueError("Scheduled rate-change time must be an exact integer")
    if current_epoch_ms < scheduled_raw:
        return None
    payload.setdefault("kind", RATE_CHANGE_WORK_KIND)
    work_id = trim(work.get("workId")) or "domain-system-credit-card-rate-change"
    return {
        "workId": work_id,
        "kind": RATE_CHANGE_WORK_KIND,
        "workType": "DOMAIN_SYSTEM_WORK",
        "occurrenceKey": trim(work.get("occurrenceKey"))
        or f"{DOMAIN_ID}|domain-system|{work_id}|{scheduled_raw}",
        "scheduledForEpochMs": scheduled_raw,
        "attemptNumber": 1,
        "description": trim(work.get("description")) or "Credit card rate change",
        "payload": payload,
    }


def default_credit_card_statement_due_work(
    state: dict[str, Any],
    scheduled_work: list[dict[str, Any]],
    requested_kinds: set[str],
    previous_epoch_ms: int,
    current_epoch_ms: int,
) -> list[dict[str, Any]]:
    if (
        (requested_kinds and "credit_card_statement_close" not in requested_kinds)
        or any(is_credit_card_statement_work(work) for work in scheduled_work)
        or previous_epoch_ms < 0
        or current_epoch_ms <= previous_epoch_ms
        or not any(
            is_indebted_credit_card_account(account)
            for account in state.get("accounts", []) or []
            if isinstance(account, dict)
        )
    ):
        return []
    previous_time = datetime.fromtimestamp(previous_epoch_ms / 1000, tz=UTC)
    current_time = datetime.fromtimestamp(current_epoch_ms / 1000, tz=UTC)
    next_month_year = previous_time.year + (1 if previous_time.month == 12 else 0)
    next_month = 1 if previous_time.month == 12 else previous_time.month + 1
    boundary = previous_time.replace(
        year=next_month_year,
        month=next_month,
        day=1,
        hour=0,
        minute=0,
        second=0,
        microsecond=0,
    )
    due_work: list[dict[str, Any]] = []
    while boundary <= current_time:
        statement_end = boundary.date() - timedelta(days=1)
        statement_start = statement_end.replace(day=1)
        payload = {
            "kind": "credit_card_statement_close",
            "statementStartDate": statement_start.isoformat(),
            "statementEndDate": statement_end.isoformat(),
            "dueDays": 14,
            "minPaymentPercentage": 0.04,
        }
        boundary_epoch_ms = int(boundary.timestamp() * 1000)
        work_id = "auto-credit-card-statements"
        work_item = {
            "workId": work_id,
            "kind": "credit_card_statement_close",
            "workType": "DOMAIN_SYSTEM_WORK",
            "occurrenceKey": f"{DOMAIN_ID}|domain-system|{work_id}|{boundary.date().isoformat()}",
            "scheduledForEpochMs": boundary_epoch_ms,
            "attemptNumber": 1,
            "description": "Monthly credit card statement close",
            "payload": payload,
        }
        if not completed_domain_system_work(state, work_item):
            due_work.append(work_item)
        if (boundary.year, boundary.month) == (9999, 12):
            break
        next_boundary_year = boundary.year + (1 if boundary.month == 12 else 0)
        next_boundary_month = 1 if boundary.month == 12 else boundary.month + 1
        boundary = boundary.replace(year=next_boundary_year, month=next_boundary_month, day=1)
    return due_work


def is_credit_card_statement_work(work: dict[str, Any]) -> bool:
    payload = work.get("payload") if isinstance(work.get("payload"), dict) else {}
    return (trim(work.get("kind")) or trim(payload.get("kind"))).lower() == "credit_card_statement_close"


def is_indebted_credit_card_account(account: dict[str, Any]) -> bool:
    return is_credit_card_account(account) and Decimal(str(account.get("balance", 0))) < 0


def domain_system_next_due_hints(
    current_epoch_ms: int,
    due_work: list[dict[str, Any]],
    state: dict[str, Any],
    requested_work_kinds: list[Any],
) -> list[dict[str, Any]]:
    current_date = datetime.fromtimestamp(current_epoch_ms / 1000, tz=UTC).date()
    next_month_year = current_date.year + (1 if current_date.month == 12 else 0)
    next_month = 1 if current_date.month == 12 else current_date.month + 1
    first_next_month = current_date.replace(year=next_month_year, month=next_month, day=1)
    next_due = datetime.combine(first_next_month, datetime.min.time(), tzinfo=UTC)
    work_kinds = {
        trim((work.get("payload") or {}).get("kind")) or trim(work.get("kind"))
        for work in due_work or []
        if trim((work.get("payload") or {}).get("kind")) or trim(work.get("kind"))
    }
    normalized_requested_kinds = {
        trim(kind).lower()
        for kind in requested_work_kinds
        if trim(kind)
    }
    statement_requested = not normalized_requested_kinds or "credit_card_statement_close" in normalized_requested_kinds
    if statement_requested and any(
        is_indebted_credit_card_account(account)
        for account in state.get("accounts", []) or []
        if isinstance(account, dict)
    ):
        work_kinds.add("credit_card_statement_close")
    hints = [
        {
            "cadence": "MONTHLY",
            "nextDueEpochMs": int(next_due.timestamp() * 1000),
            "nextDueAt": next_due.isoformat().replace("+00:00", "Z"),
            "reason": "banking-domain-system-work-window",
            "workKinds": sorted(work_kinds),
        }
    ]
    rate_change_requested = (
        not normalized_requested_kinds
        or RATE_CHANGE_WORK_KIND in normalized_requested_kinds
    )
    if rate_change_requested:
        pending_rate_changes: list[dict[str, Any]] = []
        for work in state.get("scheduledWork", []) or []:
            if not isinstance(work, dict):
                continue
            payload = work.get("payload") if isinstance(work.get("payload"), dict) else {}
            kind = (trim(work.get("kind")) or trim(payload.get("kind"))).lower()
            if kind != RATE_CHANGE_WORK_KIND:
                continue
            scheduled_raw = work.get("scheduledForEpochMs", payload.get("scheduledForEpochMs"))
            if isinstance(scheduled_raw, bool) or not isinstance(scheduled_raw, int):
                continue
            candidate = rate_change_due_work_item(work, scheduled_raw)
            if candidate is not None and not completed_domain_system_work(state, candidate):
                pending_rate_changes.append(candidate)
        if pending_rate_changes:
            next_rate_change = min(
                pending_rate_changes,
                key=lambda work: int(work["scheduledForEpochMs"]),
            )
            scheduled_for_epoch_ms = int(next_rate_change["scheduledForEpochMs"])
            hints.append(
                {
                    "cadence": "ONCE",
                    "nextDueEpochMs": scheduled_for_epoch_ms,
                    "nextDueAt": datetime.fromtimestamp(
                        scheduled_for_epoch_ms / 1000,
                        tz=UTC,
                    ).isoformat().replace("+00:00", "Z"),
                    "reason": "launch-pinned-rate-change",
                    "workKinds": [RATE_CHANGE_WORK_KIND],
                }
            )
    return hints


def completed_domain_system_work(state: dict[str, Any], work_item: dict[str, Any]) -> bool:
    occurrence_key = trim(work_item.get("occurrenceKey"))
    if not occurrence_key:
        return False
    kind = trim((work_item.get("payload") or {}).get("kind")) or trim(work_item.get("kind"))
    for log_item in state.get("executionLog", []) or []:
        if not isinstance(log_item, dict):
            continue
        if trim(log_item.get("occurrenceKey")) != occurrence_key:
            continue
        logged_kind = trim((log_item.get("payload") or {}).get("kind")) or trim(log_item.get("kind"))
        if not kind or not logged_kind or logged_kind.lower() == kind.lower():
            return True
    return False


def flattened_payload(raw_payload: Any) -> dict[str, Any]:
    flattened: dict[str, Any] = {}

    def merge(value: Any) -> None:
        if not isinstance(value, dict):
            return
        for key, entry in value.items():
            if key != "payload" and entry is not None:
                flattened.setdefault(str(key), entry)
        merge(value.get("payload"))

    merge(raw_payload)
    return flattened


def lifecycle_schedule(state: dict[str, Any]) -> list[dict[str, Any]]:
    scheduled = []
    for work in state.get("scheduledWork", []) or []:
        if not isinstance(work, dict):
            continue
        payload = dict(work.get("payload", {}) or {})
        scheduled.append(
            {
                "description": trim(work.get("description")) or "Banking scheduled work",
                "type": trim(payload.get("type")) or trim(work.get("kind")) or "transaction",
                "frequency": trim(work.get("frequency")) or "DAILY",
                "amount": decimal_string(abs(decimal_amount(payload.get("amount", 0))))
                if payload.get("amount") is not None
                else None,
            }
        )
    return scheduled


def scheduled_transactions(state: dict[str, Any]) -> list[dict[str, Any]]:
    transactions = []
    for work in state.get("scheduledWork", []) or []:
        if not isinstance(work, dict):
            continue
        payload = dict(work.get("payload", {}) or {})
        transactions.append(
            {
                "workId": trim(work.get("workId")),
                "kind": trim(work.get("kind")) or "transaction",
                "description": trim(work.get("description")) or trim(payload.get("description")),
                "frequency": trim(work.get("frequency")),
                "type": trim(payload.get("type")),
                "amount": decimal_string(abs(decimal_amount(payload.get("amount", 0))))
                if payload.get("amount") is not None
                else None,
                "day": trim(payload.get("day")),
                "time": trim(work.get("time")) or trim(payload.get("time")),
                "startDate": trim(work.get("startDate")) or trim(payload.get("startDate")),
                "endDate": trim(work.get("endDate")) or trim(payload.get("endDate")),
                "details": payload.get("details", {}),
            }
        )
    return transactions


def policy_wind_tunnel_pack() -> dict[str, Any]:
    platform_owned_v2_ready = platform_owned_v2_storage_ready()
    scorecards = [customer_impact_scorecard(), evidence_readiness_scorecard()]
    metadata = build_policy_wind_tunnel_pack_metadata(
        capabilities={
            "remoteProviderReady": True,
            "remoteProviderLanguage": "python",
            "evidenceExport": True,
            "scorecards": True,
            "runModes": ["quick", "standard"],
            "executionModes": ["CLOCK_DRIVEN_HYBRID"],
            "compareModes": ["baseline-vs-candidate"],
            "opportunityGenerationFinalizationMode": (
                FINALIZATION_MODE_PLATFORM_OWNED_V2
                if platform_owned_v2_ready
                else FINALIZATION_MODE_LEGACY_V1
            ),
            "platformOwnedV2StorageReady": platform_owned_v2_ready,
            "cohortActivationMode": "PLATFORM_PINNED_BYTES_V1",
            "realLlmCanaryPolicy": real_llm_canary_policy(),
            "domainOutcomeScorecardRoles": {
                "schemaVersion": "domain_outcome_scorecard_roles.v1",
                "roles": [
                    {
                        "scorecardId": "banking.customer-impact.v1",
                        "role": "BUSINESS_OUTCOME",
                    },
                    {
                        "scorecardId": "banking.evidence-readiness.v1",
                        "role": "PROOF_GATE",
                    },
                ],
            },
            "artifactTypes": [
                "summary",
                "experiment-card",
                "comparison-report",
                "simulation-timeline-json",
                "side-effect-ledger-json",
                "run-evidence-zip",
            ],
            "contractGapProbe": True,
        },
        control_schema=control_schema(),
        ui_schema=ui_schema(),
        outcome_schema=outcome_schema(),
        outcome_scorecards=scorecards,
        policy_expressions=[
            build_policy_expression(
                expression_id="banking.rate.candidate-rate-floor",
                expression="parameters.candidateRate >= parameters.baselineRate",
                stage="branch_guard",
                execution_target="rate-policy-execution",
                description="Candidate deposit rate must not fall below the baseline rate for rate-increase comparisons.",
            ),
            build_policy_expression(
                expression_id="banking.hardship.threshold-required",
                expression="!parameters.candidateHardshipScan || parameters.candidateHardshipThreshold > 0",
                stage="decision_gate",
                execution_target="hardship-policy-execution",
                description="Hardship scan runs must declare a positive hardship threshold.",
            ),
        ],
        domain_labels={
            "actorSingular": "customer",
            "actorPlural": "customers",
            "simulatedActorsLabel": "Simulated banking customers",
            "stateChangeLabel": "banking state changes",
            "externalActionLabel": "ledger and policy actions",
        },
        report_labels={
            "decisionQuestion": "Would this banking policy change leave vulnerable customers worse off?",
            "currentLabel": "Current hardship workflow",
            "candidateLabel": "Python candidate workflow",
            "recommendationLabel": "Domain scorecard recommendation",
        },
        summary_schema_version=WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
    )
    return {
        "packId": WIND_TUNNEL_PACK_ID,
        "providerRevision": WIND_TUNNEL_PROVIDER_REVISION,
        "domainId": DOMAIN_ID,
        "label": "Python Banking Policy Pack",
        "description": "Python remote banking reference pack for contract parity and scorecard validation.",
        "experimentId": "banking-python-scenario-lab",
        "scenarioId": "banking-python-policy-compare",
        **metadata,
    }


def platform_owned_v2_storage_ready() -> bool:
    configured = os.getenv(SHARED_STORAGE_ENABLED_ENV, "").strip().lower()
    if configured not in {"1", "true", "yes", "on"}:
        return False
    probe_root = finalization_artifact_root().joinpath(
        ".astraform-storage-probe", uuid.uuid4().hex
    )
    lock_path = probe_root.joinpath("probe.lock")
    published_path = probe_root.joinpath("probe.current")
    attempted_path = probe_root.joinpath("child.attempted")
    parent_marker = uuid.uuid4().hex
    child_marker = uuid.uuid4().hex
    pending_path: Path | None = None
    child: subprocess.Popen[bytes] | None = None
    try:
        probe_root.mkdir(parents=True, exist_ok=False)
        with lock_path.open("a+b") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                descriptor, pending_name = tempfile.mkstemp(
                    prefix="probe.", suffix=".pending", dir=probe_root
                )
                pending_path = Path(pending_name)
                with os.fdopen(descriptor, "wb") as pending_file:
                    pending_file.write(parent_marker.encode("ascii"))
                    pending_file.flush()
                    os.fsync(pending_file.fileno())
                os.replace(pending_path, published_path)
                pending_path = None
                fsync_directory(probe_root)
                child = subprocess.Popen(
                    [
                        sys.executable,
                        "-c",
                        _CROSS_PROCESS_STORAGE_PROBE,
                        str(lock_path),
                        str(published_path),
                        str(attempted_path),
                        parent_marker,
                        child_marker,
                    ],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                )
                attempt_deadline = time.monotonic() + 3.0
                while not attempted_path.exists() and child.poll() is None:
                    if time.monotonic() >= attempt_deadline:
                        return False
                    time.sleep(0.01)
                if (
                    not attempted_path.exists()
                    or child.poll() is not None
                    or published_path.read_text(encoding="ascii") != parent_marker
                ):
                    return False
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
        if child is None:
            return False
        try:
            child.wait(timeout=3.0)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=1.0)
            return False
        return (
            child.returncode == 0
            and published_path.read_text(encoding="ascii") == child_marker
        )
    except (OSError, RuntimeError, UnicodeError):
        return False
    finally:
        if child is not None and child.poll() is None:
            child.kill()
            try:
                child.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                pass
        if pending_path is not None:
            pending_path.unlink(missing_ok=True)
        attempted_path.unlink(missing_ok=True)
        published_path.unlink(missing_ok=True)
        lock_path.unlink(missing_ok=True)
        try:
            probe_root.rmdir()
            probe_root.parent.rmdir()
        except OSError:
            pass


_CROSS_PROCESS_STORAGE_PROBE = r"""
import fcntl
import os
import sys
import tempfile
from pathlib import Path

lock_path, published_path, attempted_path, expected_marker, replacement_marker = sys.argv[1:]
lock = Path(lock_path)
published = Path(published_path)
attempted = Path(attempted_path)
attempted.write_text("attempted", encoding="ascii")
with attempted.open("rb") as attempted_file:
    os.fsync(attempted_file.fileno())
with lock.open("a+b") as lock_file:
    fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
    if published.read_text(encoding="ascii") != expected_marker:
        raise SystemExit(2)
    descriptor, pending_name = tempfile.mkstemp(
        prefix="probe.", suffix=".pending", dir=published.parent
    )
    pending = Path(pending_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(replacement_marker.encode("ascii"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(pending, published)
        directory_fd = os.open(
            published.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
        )
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        pending.unlink(missing_ok=True)
    fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
"""


def policy_wind_tunnel_presets() -> dict[str, Any]:
    return {
        "schemaVersion": "banking_python_scenario_lab_presets.v1",
        "defaults": {
            "presetId": WIND_TUNNEL_PRESET_ID,
            "customerCount": 100,
            "hardshipRequestRate": 18,
            "candidateReliefRate": 72,
            "reviewCapacity": 12,
            "averageExposure": 8500,
        },
        "controls": control_schema(),
        "uiSchema": ui_schema(),
        "presets": [dict(preset) for preset in WIND_TUNNEL_PRESETS],
    }


def start_policy_wind_tunnel_run(payload: dict[str, Any], *, execution_role: str | None = None) -> dict[str, Any]:
    require_canonical_launch_execution_mode(payload)
    preset_id = trim(payload.get("presetId")) or WIND_TUNNEL_PRESET_ID
    normalized = normalized_wind_tunnel_parameters(payload, preset_id)
    require_supported_launch_finalization_mode(normalized)
    if not trim(normalized.get("simulationRunId")):
        normalized["simulationRunId"] = (
            "banking-python-wt-"
            + safe_token(preset_id)
            + "-"
            + str(int(time.time()))
            + "-"
            + uuid.uuid4().hex[:8]
        )
    rate_change_plan = compile_rate_change_plan(
        trim(normalized.get("simulationRunId")),
        preset_id,
        normalized,
    )
    if rate_change_plan is not None:
        normalized[CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD] = rate_change_plan.plan_digest
        normalized[CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD] = RATE_CHANGE_PLAN_SCHEMA_VERSION
    return start_clock_driven_policy_wind_tunnel_run(
        preset_id,
        normalized,
        execution_role=execution_role,
        launch_digest=policy_launch_digest(
            preset_id,
            normalized,
            payload,
            execution_role,
        ),
    )


def require_canonical_launch_execution_mode(payload: dict[str, Any]) -> None:
    mode = payload.get("executionMode") if isinstance(payload, dict) else None
    if not isinstance(mode, str) or mode != EXECUTION_MODE_CLOCK_DRIVEN_HYBRID:
        raise ProtocolError(
            400,
            "scenario_lab_execution_mode_retired",
            "invalid_request",
            "New Scenario Lab launches require executionMode=CLOCK_DRIVEN_HYBRID; "
            "FAST_BATCH_COMPARE supports historical reads and lifecycle cleanup only.",
            operation="policy-wind-tunnel-run",
        )


def policy_launch_digest(
    preset_id: str,
    normalized: dict[str, Any],
    request_payload: dict[str, Any],
    execution_role: str | None,
) -> str:
    resolved_parameters = deep_json_copy(normalized)
    if "simulationStartEpochMs" not in request_payload:
        # The durable run pins the actual defaulted timestamp. The launch digest
        # represents caller intent so an exact retry remains stable over wall time.
        resolved_parameters["simulationStartEpochMs"] = "DEFAULT_REQUEST_TIME"
    return "sha256:" + canonical_json_digest(
        {
            "packId": WIND_TUNNEL_PACK_ID,
            "providerRevision": WIND_TUNNEL_PROVIDER_REVISION,
            "presetId": preset_id,
            "executionRole": trim(execution_role) or "REMOTE_PROVIDER",
            "parameters": resolved_parameters,
        }
    )


def policy_wind_tunnel_run_status(run_id: str) -> dict[str, Any]:
    run = policy_run_record(run_id)
    return {
        "runId": run_id,
        "status": run["status"],
        "phase": run["phase"],
        "packId": WIND_TUNNEL_PACK_ID,
        "presetId": run["presetId"],
        "startedAt": run.get("startedAt"),
        "updatedAt": run.get("updatedAt"),
        "bundleReady": bool(run.get("bundleReady", True)),
        "progress": float(run.get("progress", 1.0)),
        "parameters": run["parameters"],
        "phaseTimings": list(run.get("phaseTimings", [])),
        "logTail": list(run.get("logTail", [])),
        "error": run.get("error") or {},
        "bundleUrl": run.get("bundleUrl") or "",
        "evidencePackUrl": run.get("evidencePackUrl") or "",
    }


def control_policy_wind_tunnel_run(run_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    action = trim(payload.get("action")) or trim(payload.get("lifecycleAction")) or "STATUS"
    run = policy_run_record(run_id)
    normalized_action = action.upper().replace("-", "_")
    if normalized_action != "FINALIZE" and trim(payload.get("operationId")):
        raise ProtocolError(
            400,
            "invalid_finalization_operation_action",
            "invalid_request",
            "Scenario Lab finalization operationId is only valid for FINALIZE",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id, "action": normalized_action},
        )
    clock_driven = is_clock_driven_parameters(run.get("parameters", {}))
    if normalized_action in {"PAUSE", "RESUME", "SET_PLAYBACK_RATE", "FINALIZE"} and not clock_driven:
        raise ProtocolError(
            400,
            "unsupported_lifecycle_action",
            "invalid_request",
            "Pause, resume, playback-rate changes, and finalize only apply to CLOCK_DRIVEN_HYBRID Scenario Lab runs.",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id, "action": normalized_action},
        )
    if normalized_action == "CANCEL":
        with finalization_receipt_lock(run_id):
            receipt = load_finalization_receipt(run_id)
            with _POLICY_WIND_TUNNEL_LOCK:
                current_run = policy_run_record(run_id)
                if clock_driven and not lifecycle_transition_allows(current_run, TRANSITION_CANCEL):
                    append_lifecycle_ignored_log(run_id, "Cancel")
                elif not policy_run_is_terminal(run_id):
                    if clock_driven:
                        try:
                            apply_playback_control_for_run(run_id, paused=True, playback_rate=0.0, action_label="Cancel requested")
                        except Exception as exc:  # pragma: no cover - depends on simulator availability
                            append_policy_log(run_id, f"Cancel requested. Clock pause was not confirmed: {exc}")
                    terminal_run = policy_run_snapshot_with_lifecycle(
                        current_run,
                        status="CANCELLED",
                        phase="CANCELLED",
                        progress=1.0,
                        bundle_ready=True,
                    )
                    terminal_run["logTail"] = (
                        list(terminal_run.get("logTail", []))
                        + ["Cancel requested. Captured evidence remains available."]
                    )[-40:]
                    persist_terminal_policy_run(run_id, terminal_run, receipt)
    elif normalized_action == "PAUSE":
        with _POLICY_WIND_TUNNEL_LOCK:
            if not policy_run_is_terminal(run_id):
                current_run = policy_run_record(run_id)
                if not clock_driven_run_can_pause(current_run):
                    append_lifecycle_ignored_log(run_id, "Pause")
                elif clock_driven_run_is_ready_to_finalize(current_run):
                    if apply_playback_control_for_run(
                        run_id,
                        paused=True,
                        playback_rate=0.0,
                        action_label="Platform finalization pause requested",
                    ):
                        update_policy_run(
                            run_id,
                            status=STATUS_READY_TO_FINALIZE,
                            phase=PHASE_READY_TO_FINALIZE,
                            bundle_ready=True,
                        )
                        append_policy_log(
                            run_id,
                            "Platform finalization pause confirmed. Run remains ready for a retryable platform seal.",
                        )
                elif clock_driven_clock_has_started(current_run):
                    if apply_playback_control_for_run(run_id, paused=True, playback_rate=0.0, action_label="Pause requested"):
                        update_policy_run(
                            run_id,
                            status="PAUSED",
                            phase="PAUSED",
                            bundle_ready=True,
                            paused_from_phase=trim(current_run.get("phase")) or "UNKNOWN",
                        )
                        append_policy_log(run_id, "Pause requested. Simulator playback rate set to 0.0.")
                else:
                    update_policy_run(
                        run_id,
                        status="PAUSED",
                        phase="PAUSED",
                        bundle_ready=True,
                        paused_from_phase=trim(current_run.get("phase")) or "UNKNOWN",
                    )
                    append_policy_log(run_id, "Pause requested. Run paused before simulator clock playback started.")
                    try:
                        apply_playback_control_for_run(run_id, paused=True, playback_rate=0.0, action_label="Pause requested")
                    except Exception as exc:  # pragma: no cover - depends on simulator availability
                        append_policy_log(run_id, f"Pause requested. Clock pause attempt was not confirmed: {exc}")
    elif normalized_action == "RESUME":
        restart_prestart_worker = False
        restart_horizon_monitor = False
        resume_execution_owner: str | None = None
        with _POLICY_WIND_TUNNEL_LOCK:
            if not policy_run_is_terminal(run_id):
                current_run = policy_run_record(run_id)
                if not clock_driven_run_can_resume(current_run):
                    append_lifecycle_ignored_log(run_id, "Resume")
                elif trim(current_run.get("pausedFromPhase")).upper() == "CLOCK_RUNNING":
                    playback_rate = negotiated_playback_rate(current_run.get("parameters", {}), payload)
                    if apply_playback_control_for_run(run_id, paused=False, playback_rate=playback_rate, action_label="Resume requested"):
                        resume_execution_owner = "resume:" + uuid.uuid4().hex
                        updated_parameters = dict(current_run.get("parameters", {}))
                        updated_parameters["playbackRate"] = playback_rate
                        update_policy_run(
                            run_id,
                            status="RUNNING",
                            phase="CLOCK_RUNNING",
                            bundle_ready=True,
                            fields={
                                "parameters": updated_parameters,
                                POLICY_EXECUTION_CLAIM_OWNER_FIELD: resume_execution_owner,
                                POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD: (
                                    int(time.time() * 1000)
                                    + POLICY_EXECUTION_CLAIM_LEASE_MS
                                ),
                            },
                        )
                        append_policy_log(run_id, f"Resume requested. Simulator playback rate set to {format_playback_rate(playback_rate)}.")
                        restart_horizon_monitor = True
                else:
                    paused_from_phase = trim(current_run.get("pausedFromPhase")) or "PREPARING_CLOCK"
                    resume_execution_owner = "resume:" + uuid.uuid4().hex
                    update_policy_run(
                        run_id,
                        status="RUNNING",
                        phase="PREPARING_CLOCK",
                        progress=0.05,
                        bundle_ready=False,
                        fields={
                            POLICY_EXECUTION_CLAIM_OWNER_FIELD: resume_execution_owner,
                            POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD: (
                                int(time.time() * 1000)
                                + POLICY_EXECUTION_CLAIM_LEASE_MS
                            ),
                        },
                        remove_fields={"activatedCount"},
                    )
                    append_policy_log(
                        run_id,
                        f"Resume requested. Restarting clock-driven setup from {paused_from_phase}.",
                    )
                    restart_prestart_worker = True
        if restart_prestart_worker:
            start_clock_driven_policy_wind_tunnel_worker(
                run_id,
                paused_from_phase,
                execution_claim_owner=resume_execution_owner,
            )
        elif restart_horizon_monitor:
            start_clock_horizon_monitor(run_id, resume_execution_owner)
    elif normalized_action == "SET_PLAYBACK_RATE":
        with _POLICY_WIND_TUNNEL_LOCK:
            if not policy_run_is_terminal(run_id):
                current_run = policy_run_record(run_id)
                if not clock_driven_worker_state_matches(run_id, "RUNNING", "CLOCK_RUNNING"):
                    append_lifecycle_ignored_log(run_id, "Playback rate change")
                else:
                    requested_rate = requested_playback_rate(current_run.get("parameters", {}), payload)
                    playback_rate = negotiated_playback_rate(current_run.get("parameters", {}), payload)
                    if apply_playback_control_for_run(
                        run_id,
                        paused=False,
                        playback_rate=playback_rate,
                        action_label="Playback rate change requested",
                    ):
                        updated_parameters = dict(current_run.get("parameters", {}))
                        updated_parameters["playbackRate"] = playback_rate
                        update_policy_run(run_id, fields={"parameters": updated_parameters})
                        if requested_rate > playback_rate:
                            append_policy_log(
                                run_id,
                                "Playback rate change requested at "
                                + format_playback_rate(requested_rate)
                                + "; simulator accepted "
                                + format_playback_rate(playback_rate)
                                + " after playback limit negotiation.",
                            )
                        else:
                            append_policy_log(run_id, f"Playback rate changed to {format_playback_rate(playback_rate)}.")
    elif normalized_action == "FINALIZE":
        finalize_clock_driven_policy_wind_tunnel_run(run_id, payload)
    elif normalized_action not in {"STATUS", ""}:
        raise ProtocolError(
            400,
            "unsupported_lifecycle_action",
            "invalid_request",
            f"Unsupported Scenario Lab lifecycle action: {action}",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id, "action": normalized_action},
        )
    run = policy_run_record(run_id)
    return {
        "runId": run_id,
        "status": run["status"],
        "phase": run["phase"],
        "packId": WIND_TUNNEL_PACK_ID,
        "presetId": run["presetId"],
        "startedAt": run.get("startedAt"),
        "updatedAt": run.get("updatedAt"),
        "lifecycleAction": normalized_action,
        "bundleReady": bool(run.get("bundleReady", True)),
        "progress": float(run.get("progress", 1.0)),
        "parameters": run["parameters"],
        "phaseTimings": list(run.get("phaseTimings", [])),
        "logTail": list(run.get("logTail", [])),
        "error": run.get("error") or {},
        "bundleUrl": run.get("bundleUrl") or "",
        "evidencePackUrl": run.get("evidencePackUrl") or "",
    }


def policy_wind_tunnel_bundle(run_id: str) -> dict[str, Any]:
    run = policy_run_record(run_id)
    if is_clock_driven_parameters(run.get("parameters", {})):
        if is_terminal_phase(run.get("status")):
            if (
                trim(run.get("status")) == "COMPLETED"
                and policy_run_finalization_mode(run) == FINALIZATION_MODE_PLATFORM_OWNED_V2
            ):
                require_finalized_artifact_receipt(run_id)
            persisted_bundle = persisted_clock_driven_policy_wind_tunnel_bundle(run_id, run)
            if persisted_bundle is not None:
                return persisted_bundle
        bundle = clock_driven_policy_wind_tunnel_bundle(run_id)
        summary = dict(bundle.get("summary", {}) or {})
        if summary.get("summaryMode") == "CLOCK_DRIVEN_HYBRID_FINALIZED":
            write_clock_driven_artifact_files(run_id, summary)
        return bundle
    preset_id = run["presetId"]
    parameters = run["parameters"]
    metrics = wind_tunnel_metrics(parameters)
    summary = {
        "schemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        "domainId": DOMAIN_ID,
        "packId": WIND_TUNNEL_PACK_ID,
        "runId": run_id,
        "presetId": preset_id,
        "branches": {
            "baseline": {
                "label": "Current hardship workflow",
                "approvedRelief": metrics["baselineApprovedRelief"],
                "unresolvedHardshipRequests": metrics["baselineUnresolvedHardshipRequests"],
            },
            "candidate": {
                "label": "Python candidate workflow",
                "approvedRelief": metrics["approvedRelief"],
                "unresolvedHardshipRequests": metrics["unresolvedHardshipRequests"],
            },
            "delta": {
                "approvedReliefDelta": metrics["approvedRelief"] - metrics["baselineApprovedRelief"],
                "unresolvedHardshipDelta": metrics["unresolvedHardshipRequests"]
                - metrics["baselineUnresolvedHardshipRequests"],
            },
        },
        "metrics": metrics,
        "segments": [
            {
                "segmentId": "hardship",
                "label": "Hardship pressure customers",
                "subjectCount": metrics["hardshipRequests"],
                "riskScore": metrics["customerImpactRisk"],
            },
            {
                "segmentId": "everyday",
                "label": "Everyday banking customers",
                "subjectCount": metrics["customerCount"] - metrics["hardshipRequests"],
                "riskScore": 8,
            },
        ],
        "timeline": [
            {
                "eventId": "python-banking-run-started",
                "label": "Python banking Wind Tunnel run started",
                "time": iso_now_plus(minutes=0),
                "evidenceRef": "summaryJson",
            },
            {
                "eventId": "python-banking-scorecards-applied",
                "label": "Domain-owned Python scorecards applied",
                "time": iso_now_plus(minutes=1),
                "evidenceRef": "scorecardsJson",
            },
            {
                "eventId": "python-banking-evidence-pack-ready",
                "label": "Evidence pack exported by Python provider",
                "time": iso_now_plus(minutes=2),
                "evidenceRef": "evidencePack",
            },
        ],
        "decisionGates": {
            "hardshipCapacityGate": {
                "label": "Hardship capacity gate",
                "status": "WATCH" if metrics["unresolvedHardshipRequests"] > metrics["reviewCapacity"] else "LAUNCH",
                "evidenceRef": "summaryJson",
            },
            "customerImpactGate": {
                "label": "Customer impact gate",
                "status": "WATCH" if metrics["customerImpactRisk"] >= 35 else "LAUNCH",
                "evidenceRef": "summaryJson",
            },
        },
        "artifacts": {
            "summaryJson": f"/policy-wind-tunnel/runs/{run_id}/artifacts/summary",
            "scorecardsJson": f"/policy-wind-tunnel/runs/{run_id}/artifacts/scorecards",
            "evidencePack": f"/policy-wind-tunnel/runs/{run_id}/evidence-pack",
        },
        "domainPayload": {
            "providerLanguage": "python",
            "contractGapProbe": True,
            "limitations": [
                "Reference provider uses deterministic aggregate banking math.",
                "It proves remote scorecard and evidence shape, not full Java banking parity.",
            ],
        },
    }
    bundle = dict(summary)
    bundle["schemaVersion"] = "policy_wind_tunnel_bundle.v1"
    bundle["summarySchemaVersion"] = WIND_TUNNEL_SUMMARY_SCHEMA_VERSION
    bundle["summary"] = summary
    return bundle


def persisted_clock_driven_policy_wind_tunnel_bundle(run_id: str, run: dict[str, Any]) -> dict[str, Any] | None:
    summary_path = persisted_clock_driven_summary_path(run_id, run)
    if summary_path is None or not summary_path.exists():
        return None
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(summary, dict):
        raise RuntimeError(f"Persisted Scenario Lab summary for {run_id} is not an object.")
    artifact_paths = summary.get("artifacts") if isinstance(summary.get("artifacts"), dict) else clock_driven_artifact_paths(run_id)
    artifact_downloads = (
        summary.get("artifactDownloads")
        if isinstance(summary.get("artifactDownloads"), dict)
        else clock_driven_artifact_downloads(run_id)
    )
    hybrid = summary.get("hybrid") if isinstance(summary.get("hybrid"), dict) else {}
    return {
        "schemaVersion": "policy_wind_tunnel_bundle.v1",
        "summarySchemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        "domainId": summary.get("domainId") or DOMAIN_ID,
        "packId": summary.get("packId") or WIND_TUNNEL_PACK_ID,
        "experimentId": "banking-python-scenario-lab",
        "runId": summary.get("runId") or run_id,
        "presetId": dict(summary.get("demoPreset", {}) or {}).get("presetId") or run.get("presetId"),
        "lookupMode": "python-remote-clock-driven-finalized-summary",
        "loadedAt": utc_now_iso(),
        "decisionReadiness": summary.get("decisionReadiness", {}),
        "decisionComparison": summary.get("decisionComparison", {}),
        "simulationTimeline": summary.get("simulationTimeline", {}),
        "sideEffectLedger": summary.get("sideEffectLedger", {}),
        "branchStateProjection": summary.get("branchStateProjection", {}),
        "branches": {
            "policyToggle": summary.get("policyToggle", {}),
            "baseline": dict(summary.get("decisionComparison", {}) or {}).get("baseline", {}),
            "candidate": dict(summary.get("decisionComparison", {}) or {}).get("proposed", {}),
            "delta": {},
        },
        "metrics": {
            "headline": summary.get("headline", {}),
            "evidence": {
                "status": "FINALIZED",
                "simulationRunEvidencePack": artifact_downloads.get("simulationRunEvidence"),
            },
        },
        "segments": {
            "exposure": summary.get("exposureSummary", {}),
            "impacted": [],
            "impactSummary": {},
        },
        "timeline": {
            "events": dict(summary.get("simulationTimeline", {}) or {}).get("events", []),
            "sideEffects": dict(summary.get("sideEffectLedger", {}) or {}).get("entries", []),
            "stateProjection": summary.get("branchStateProjection", {}),
        },
        "decisionGates": dict(summary.get("evaluator", {}) or {}).get("decisionGates", {}),
        "agenticSimulation": summary.get("agenticSimulation", {}),
        "artifacts": {
            "paths": artifact_paths,
            "downloads": artifact_downloads,
        },
        "summary": summary,
        "domainPayload": summary,
        "experimentCardMarkdown": build_clock_driven_experiment_card(summary),
        "artifactPaths": artifact_paths,
        "artifactDownloads": artifact_downloads,
        "hybrid": {
            **hybrid,
            "available": True,
            "live": False,
            "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
            "agenticSimulation": summary.get("agenticSimulation", {}),
            "decisionReadiness": summary.get("decisionReadiness", {}),
        },
    }


def persisted_clock_driven_summary_path(run_id: str, run: dict[str, Any]) -> Path | None:
    summary_ref = trim(run.get("summaryPath"))
    if summary_ref:
        summary_path = Path(summary_ref)
        if not summary_path.is_absolute():
            summary_path = workspace_root().joinpath(summary_path)
        return summary_path.resolve()
    default_path = workspace_root().joinpath(
        "output",
        "experiments",
        "banking-python-scenario-lab",
        "runs",
        run_id,
        "summary",
        "scenario-lab-summary.json",
    )
    return default_path.resolve() if default_path.exists() else None


def policy_wind_tunnel_artifact(run_id: str, artifact_type: str) -> bytes:
    normalized_type = trim(artifact_type).lower()
    receipt = load_finalization_receipt(run_id)
    if receipt.get("state") == FINALIZATION_RECEIPT_PUBLISHED:
        if normalized_type == "finalization-receipt":
            return finalization_receipt_path(run_id).read_bytes()
        entry = finalization_artifact_entry(receipt, normalized_type)
        if entry:
            return resolve_finalization_artifact_path(trim(entry.get("artifactRef"))).read_bytes()
    run = policy_run_record(run_id)
    if is_clock_driven_parameters(run.get("parameters", {})):
        bundle = policy_wind_tunnel_bundle(run_id)
        summary = dict(bundle.get("summary", {}) or {})
        payload_by_type = {
            "summary": summary,
            "simulation-timeline": summary.get("simulationTimeline", {}),
            "simulation-timeline-json": summary.get("simulationTimeline", {}),
            "side-effect-ledger": summary.get("sideEffectLedger", {}),
            "side-effect-ledger-json": summary.get("sideEffectLedger", {}),
            "branch-state-projection": summary.get("branchStateProjection", {}),
            "branch-state-projection-json": summary.get("branchStateProjection", {}),
            "comparison": summary.get("decisionComparison", {}),
            "comparison-report": summary.get("decisionComparison", {}),
            "domain-outcome-score": summary.get("domainOutcomeScore", {}),
            "domain-outcome-score-json": summary.get("domainOutcomeScore", {}),
            "domain-outcome-scores": summary.get("domainOutcomeScores", []),
            "domain-outcome-scores-json": summary.get("domainOutcomeScores", []),
            "hybrid-agent-audit": dict(bundle.get("hybrid", {}) or {}).get("agentAudit", {}),
            "hybrid-agent-audit-json": dict(bundle.get("hybrid", {}) or {}).get("agentAudit", {}),
            "scorecards": {"outcomeScorecards": policy_wind_tunnel_pack()["outcomeScorecards"]},
        }
        if normalized_type in {"experiment-card", "markdown", "md"}:
            return trim(bundle.get("experimentCardMarkdown")).encode("utf-8")
        if normalized_type not in payload_by_type:
            raise ProtocolError(
                404,
                "unknown_policy_wind_tunnel_artifact",
                "invalid_request",
                f"Unknown Policy Wind Tunnel artifact type '{artifact_type}'.",
                operation="policy-wind-tunnel-artifact",
                details={"supportedArtifactTypes": sorted(payload_by_type.keys())},
            )
        return json.dumps(payload_by_type[normalized_type], indent=2, sort_keys=True).encode("utf-8")

    if normalized_type == "summary":
        payload = policy_wind_tunnel_bundle(run_id)["summary"]
    elif normalized_type == "scorecards":
        payload = {"outcomeScorecards": policy_wind_tunnel_pack()["outcomeScorecards"]}
    else:
        raise ProtocolError(
            404,
            "unknown_policy_wind_tunnel_artifact",
            "invalid_request",
            f"Unknown Policy Wind Tunnel artifact type '{artifact_type}'.",
            operation="policy-wind-tunnel-artifact",
            details={"supportedArtifactTypes": ["summary", "scorecards"]},
        )
    return json.dumps(payload, indent=2, sort_keys=True).encode("utf-8")


def policy_wind_tunnel_artifact_metadata(run_id: str, artifact_type: str) -> BinaryPayloadMetadata:
    normalized_type = trim(artifact_type).lower()
    receipt = load_finalization_receipt(run_id)
    if receipt.get("state") == FINALIZATION_RECEIPT_PUBLISHED:
        if normalized_type == "finalization-receipt":
            return BinaryPayloadMetadata(
                media_type="application/json",
                filename=FINALIZATION_RECEIPT_FILE,
            )
        entry = finalization_artifact_entry(receipt, normalized_type)
        if entry:
            artifact_path = resolve_finalization_artifact_path(trim(entry.get("artifactRef")))
            return BinaryPayloadMetadata(
                media_type=trim(entry.get("contentType")),
                filename=artifact_path.name,
            )
    run = policy_run_record(run_id)
    if is_clock_driven_parameters(run.get("parameters", {})):
        json_artifact_types = {
            "summary",
            "simulation-timeline",
            "simulation-timeline-json",
            "side-effect-ledger",
            "side-effect-ledger-json",
            "branch-state-projection",
            "branch-state-projection-json",
            "comparison",
            "comparison-report",
            "domain-outcome-score",
            "domain-outcome-score-json",
            "domain-outcome-scores",
            "domain-outcome-scores-json",
            "hybrid-agent-audit",
            "hybrid-agent-audit-json",
            "scorecards",
        }
        if normalized_type in {"experiment-card", "markdown", "md"}:
            return BinaryPayloadMetadata(media_type="text/markdown", filename="experiment-card.md")
        if normalized_type not in json_artifact_types:
            raise ProtocolError(
                404,
                "unknown_policy_wind_tunnel_artifact",
                "invalid_request",
                f"Unknown Policy Wind Tunnel artifact type '{artifact_type}'.",
                operation="policy-wind-tunnel-artifact-readiness",
                details={"supportedArtifactTypes": sorted(json_artifact_types | {"experiment-card", "markdown", "md"})},
            )
        filename = "summary.json" if normalized_type == "summary" else f"{normalized_type}.json"
        return BinaryPayloadMetadata(media_type="application/json", filename=filename)

    if normalized_type not in {"summary", "scorecards"}:
        raise ProtocolError(
            404,
            "unknown_policy_wind_tunnel_artifact",
            "invalid_request",
            f"Unknown Policy Wind Tunnel artifact type '{artifact_type}'.",
            operation="policy-wind-tunnel-artifact-readiness",
            details={"supportedArtifactTypes": ["summary", "scorecards"]},
        )
    return BinaryPayloadMetadata(media_type="application/json", filename=f"{normalized_type}.json")


def finalization_artifact_entry(
    receipt: dict[str, Any],
    normalized_type: str,
) -> dict[str, Any]:
    manifest = receipt.get("artifactManifest")
    if not isinstance(manifest, dict):
        return {}
    for artifact_type, raw_entry in manifest.items():
        route_type = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "-", str(artifact_type))
        route_type = route_type.replace("_", "-").lower()
        if route_type == normalized_type and isinstance(raw_entry, dict):
            return dict(raw_entry)
    return {}


def resolve_finalization_artifact_path(artifact_ref: str) -> Path:
    root = workspace_root()
    artifact_path = root.joinpath(artifact_ref).resolve()
    if root not in artifact_path.parents or not artifact_path.is_file():
        raise RuntimeError(
            f"Scenario Lab finalization artifact was not durably published: {artifact_ref}"
        )
    return artifact_path


def policy_wind_tunnel_evidence_pack(run_id: str) -> bytes:
    buffer = io.BytesIO()
    bundle = policy_wind_tunnel_bundle(run_id)
    timeline_events = (
        dict(bundle.get("summary", {}) or {})
        .get("simulationTimeline", {})
        .get("events", [])
        if is_clock_driven_parameters(policy_run_record(run_id).get("parameters", {}))
        else bundle["summary"].get("timeline", [])
    )
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "manifest.json",
            json.dumps(
                {
                    "schemaVersion": "policy_wind_tunnel_evidence_pack.v1",
                    "domainId": DOMAIN_ID,
                    "packId": WIND_TUNNEL_PACK_ID,
                    "runId": run_id,
                    "providerLanguage": "python",
                    "files": ["summary.json", "scorecards.json", "events.jsonl"],
                },
                indent=2,
                sort_keys=True,
            ),
        )
        archive.writestr("summary.json", json.dumps(bundle["summary"], indent=2, sort_keys=True))
        if is_clock_driven_parameters(policy_run_record(run_id).get("parameters", {})):
            archive.writestr(
                "simulation-timeline.json",
                json.dumps(bundle["summary"].get("simulationTimeline", {}), indent=2, sort_keys=True),
            )
            archive.writestr(
                "side-effect-ledger.json",
                json.dumps(bundle["summary"].get("sideEffectLedger", {}), indent=2, sort_keys=True),
            )
            archive.writestr(
                "branch-state-projection.json",
                json.dumps(bundle["summary"].get("branchStateProjection", {}), indent=2, sort_keys=True),
            )
        archive.writestr(
            "scorecards.json",
            json.dumps(policy_wind_tunnel_pack()["outcomeScorecards"], indent=2, sort_keys=True),
        )
        archive.writestr(
            "events.jsonl",
            "\n".join(json.dumps(event, sort_keys=True) for event in timeline_events),
        )
    return buffer.getvalue()


def policy_wind_tunnel_evidence_pack_metadata(run_id: str) -> BinaryPayloadMetadata:
    policy_run_record(run_id)
    return BinaryPayloadMetadata(media_type="application/zip", filename=f"{run_id}-evidence-pack.zip")


def start_clock_driven_policy_wind_tunnel_run(
    preset_id: str,
    normalized: dict[str, Any],
    *,
    execution_role: str | None = None,
    launch_digest: str,
) -> dict[str, Any]:
    run_id = trim(normalized.get("simulationRunId"))
    if not run_id:
        raise ValueError("Clock-driven launch requires a resolved simulationRunId")
    rate_change_plan = compile_rate_change_plan(run_id, preset_id, normalized)
    cohort_snapshot = capture_platform_cohort_snapshot(normalized)
    if rate_change_plan is not None and cohort_snapshot is None:
        raise ValueError(
            "Clock-driven rate-change launch requires a platform-pinned cohort snapshot."
        )
    activation_settings_snapshot(normalized)
    normalized.pop("cohortArtifactSnapshot", None)
    execution_claim_owner = "launch:" + uuid.uuid4().hex
    admission = admit_clock_driven_policy_launch(
        run_id,
        preset_id,
        normalized,
        launch_digest,
        execution_claim_owner,
    )
    if admission.outcome == POLICY_LAUNCH_ADMISSION_CONFLICT:
        raise ProtocolError(
            409,
            "scenario_lab_launch_conflict",
            "invalid_request",
            "Scenario Lab runId is already bound to a different launch request.",
            operation="policy-wind-tunnel-run",
            details={"runId": run_id},
        )
    if admission.execution_claim_owner is None:
        if rate_change_plan is not None:
            persist_clock_driven_intervention_plan(run_id, rate_change_plan)
        return {
            **policy_wind_tunnel_run_status(run_id),
            "executionRole": execution_role or "REMOTE_PROVIDER",
            "summarySchemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        }
    if cohort_snapshot is not None:
        persist_cohort_artifact_snapshot(run_id, cohort_snapshot)
    if rate_change_plan is not None:
        persist_clock_driven_intervention_plan(run_id, rate_change_plan)
    try:
        renew_policy_execution_claim(run_id, admission.execution_claim_owner)
    except StalePolicyExecutionClaim:
        return {
            **policy_wind_tunnel_run_status(run_id),
            "executionRole": execution_role or "REMOTE_PROVIDER",
            "summarySchemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        }
    if admission.execution_path == POLICY_LAUNCH_EXECUTION_FRESH_START:
        start_clock_driven_policy_wind_tunnel_worker(
            run_id,
            execution_claim_owner=admission.execution_claim_owner,
        )
    elif admission.execution_path == POLICY_LAUNCH_EXECUTION_RECOVER_EXISTING:
        start_clock_driven_policy_wind_tunnel_recovery(
            run_id,
            execution_claim_owner=admission.execution_claim_owner,
        )
    else:  # pragma: no cover - admission cannot grant an owner on a live replay
        raise RuntimeError("Scenario Lab launch admission returned an invalid execution path")
    return {
        **policy_wind_tunnel_run_status(run_id),
        "executionRole": execution_role or "REMOTE_PROVIDER",
        "summarySchemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
    }


def admit_clock_driven_policy_launch(
    run_id: str,
    preset_id: str,
    normalized: dict[str, Any],
    launch_digest: str,
    requested_owner: str,
    *,
    now_epoch_ms: int | None = None,
) -> PolicyLaunchAdmission:
    admitted_at = int(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
    with finalization_receipt_lock(run_id):
        with _POLICY_WIND_TUNNEL_LOCK:
            with policy_run_state_lock(run_id):
                existing = load_policy_run_state_unlocked(run_id)
                existing = restore_published_terminal_receipt_before_launch_unlocked(
                    run_id,
                    existing,
                )
                if existing is None:
                    accepted_run = new_policy_run(
                        run_id,
                        preset_id,
                        normalized,
                        status="RUNNING",
                        phase="QUEUED",
                        progress=0.0,
                        bundle_ready=False,
                        log_tail=["Run accepted by Python remote provider."],
                    )
                    accepted_run[POLICY_LAUNCH_DIGEST_FIELD] = launch_digest
                    accepted_run[POLICY_EXECUTION_CLAIM_OWNER_FIELD] = requested_owner
                    accepted_run[POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = (
                        admitted_at + POLICY_EXECUTION_CLAIM_LEASE_MS
                    )
                    write_policy_run_state_unlocked(run_id, accepted_run)
                    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(accepted_run)
                    return PolicyLaunchAdmission(
                        POLICY_LAUNCH_ADMISSION_CREATED,
                        deep_json_copy(accepted_run),
                        requested_owner,
                    )
                if existing.get(POLICY_LAUNCH_DIGEST_FIELD) != launch_digest:
                    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(existing)
                    return PolicyLaunchAdmission(
                        POLICY_LAUNCH_ADMISSION_CONFLICT,
                        deep_json_copy(existing),
                    )
                if is_terminal_phase(existing.get("status")):
                    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(existing)
                    return PolicyLaunchAdmission(
                        POLICY_LAUNCH_ADMISSION_REPLAY,
                        deep_json_copy(existing),
                    )
                if not policy_launch_phase_requires_recovery(existing):
                    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(existing)
                    return PolicyLaunchAdmission(
                        POLICY_LAUNCH_ADMISSION_REPLAY,
                        deep_json_copy(existing),
                    )
                claim_expiry = exact_ijson_integer(
                    existing.get(POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD),
                    default=0,
                )
                if claim_expiry <= admitted_at:
                    existing[POLICY_EXECUTION_CLAIM_OWNER_FIELD] = requested_owner
                    existing[POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = (
                        admitted_at + POLICY_EXECUTION_CLAIM_LEASE_MS
                    )
                    write_policy_run_state_unlocked(run_id, existing)
                    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(existing)
                    return PolicyLaunchAdmission(
                        POLICY_LAUNCH_ADMISSION_REPLAY,
                        deep_json_copy(existing),
                        requested_owner,
                        takeover=True,
                    )
                _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(existing)
                return PolicyLaunchAdmission(
                    POLICY_LAUNCH_ADMISSION_REPLAY,
                    deep_json_copy(existing),
                )


def policy_launch_phase_requires_recovery(run: dict[str, Any]) -> bool:
    if trim(run.get("status")).upper() != STATUS_RUNNING:
        return False
    return trim(run.get("phase")).upper() in {
        PHASE_QUEUED,
        PHASE_PREPARING_CLOCK,
        PHASE_ACTIVATING_COHORT,
        PHASE_CLOCK_RUNNING,
    }


def restore_published_terminal_receipt_before_launch_unlocked(
    run_id: str,
    existing: dict[str, Any] | None,
) -> dict[str, Any] | None:
    if not finalization_receipt_path(run_id).exists():
        return existing
    receipt = load_finalization_receipt(run_id)
    if receipt.get("state") != FINALIZATION_RECEIPT_PUBLISHED:
        return existing
    snapshot = receipt.get("runSnapshot")
    metadata = snapshot.get("metadata") if isinstance(snapshot, dict) else None
    provider_run = metadata.get("providerRun") if isinstance(metadata, dict) else None
    if not isinstance(provider_run, dict) or not is_terminal_phase(provider_run.get("status")):
        raise RuntimeError("Scenario Lab published finalization receipt is not terminal")
    terminal_run = deep_json_copy(provider_run)
    write_policy_run_state_unlocked(run_id, terminal_run)
    _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(terminal_run)
    return terminal_run


def start_clock_driven_policy_wind_tunnel_worker(
    run_id: str,
    resume_source_phase: str | None = None,
    *,
    execution_claim_owner: str | None = None,
) -> None:
    worker = threading.Thread(
        target=execute_clock_driven_policy_wind_tunnel_run,
        args=(run_id, resume_source_phase, execution_claim_owner),
        name=f"banking-python-wt-{safe_token(run_id)}",
        daemon=True,
    )
    worker.start()


def start_clock_driven_policy_wind_tunnel_recovery(
    run_id: str,
    *,
    execution_claim_owner: str,
) -> None:
    worker = threading.Thread(
        target=recover_existing_clock_driven_policy_wind_tunnel_run,
        args=(run_id, execution_claim_owner),
        name=f"banking-python-wt-recovery-{safe_token(run_id)}",
        daemon=True,
    )
    worker.start()


def recover_existing_clock_driven_policy_wind_tunnel_run(
    run_id: str,
    execution_claim_owner: str,
) -> None:
    with policy_execution_claim_scope(run_id, execution_claim_owner):
        try:
            renew_policy_execution_claim(run_id, execution_claim_owner)
            run = policy_run_record(run_id)
            status = trim(run.get("status")).upper()
            phase = trim(run.get("phase")).upper()
            if is_terminal_phase(status):
                return
            if phase == PHASE_QUEUED and status in {STATUS_QUEUED, STATUS_RUNNING}:
                execute_clock_driven_policy_wind_tunnel_run(
                    run_id,
                    PHASE_PREPARING_CLOCK,
                    execution_claim_owner,
                )
                return
            if phase == PHASE_PREPARING_CLOCK and status == STATUS_RUNNING:
                execute_clock_driven_policy_wind_tunnel_run(
                    run_id,
                    PHASE_PREPARING_CLOCK,
                    execution_claim_owner,
                )
                return
            if phase == PHASE_CLOCK_RUNNING and status == STATUS_RUNNING:
                start_clock_horizon_monitor(run_id, execution_claim_owner)
                return
            if phase == PHASE_ACTIVATING_COHORT and status == STATUS_RUNNING:
                handle_clock_driven_worker_failure(
                    run_id,
                    RuntimeError(
                        "Provider ownership expired during cohort activation; activation was not replayed because its committed boundary is ambiguous."
                    ),
                )
                return
            if (
                (phase == PHASE_PAUSED and status == STATUS_PAUSED)
                or (
                    phase == PHASE_READY_TO_FINALIZE
                    and status == STATUS_READY_TO_FINALIZE
                )
                or (
                    phase == PHASE_FINALIZING_REPORT
                    and status == STATUS_RUNNING
                )
            ):
                return
            handle_clock_driven_worker_failure(
                run_id,
                RuntimeError(
                    f"Expired provider ownership found an invalid lifecycle state {status or 'UNKNOWN'}/{phase or 'UNKNOWN'}."
                ),
            )
        except StalePolicyExecutionClaim:
            return


def execute_clock_driven_policy_wind_tunnel_run(
    run_id: str,
    resume_source_phase: str | None = None,
    execution_claim_owner: str | None = None,
) -> None:
    with policy_execution_claim_scope(run_id, execution_claim_owner):
        try:
            if execution_claim_owner is not None:
                renew_policy_execution_claim(run_id, execution_claim_owner)
            run = policy_run_record(run_id)
            parameters = dict(run.get("parameters", {}) or {})
            if not ensure_clock_driven_preparing_phase(run_id):
                return
            append_policy_log(run_id, "Preparing Clock-Driven Simulation run with explicit E24 evidence scope.")

            cohort_id = selected_cohort_id(parameters)
            if not cohort_id:
                raise ValueError("Clock-Driven Simulation requires a generated Cohort Factory cohort.")
            agent_decision_mode = normalize_agent_decision_mode(parameters.get("agentDecisionMode"))
            playback_rate = negotiated_playback_rate(parameters, {})
            rate_change_plan = resolve_clock_driven_intervention_plan(run_id, parameters)
            parameters["agentDecisionMode"] = agent_decision_mode
            parameters["playbackRate"] = playback_rate
            update_policy_run(run_id, fields={"parameters": parameters})

            append_policy_log(run_id, f"Loading generated cohort {cohort_id} for streaming activation.")
            platform_pinned_activation = bool(trim(parameters.get("cohortArtifactDigest")))
            cohort_snapshot = (
                load_durable_cohort_artifact_snapshot(run_id, parameters)
                if platform_pinned_activation
                else None
            )
            if platform_pinned_activation:
                if cohort_snapshot is None:
                    raise ValueError("Platform-owned run has no pinned cohort artifact snapshot.")
                agents = load_pinned_cohort_agents_for_streaming(
                    cohort_id,
                    cohort_snapshot,
                    agent_decision_mode,
                    parameters,
                )
            else:
                agents = load_generated_cohort_agents_for_streaming(cohort_id, agent_decision_mode, parameters)
            enforce_real_llm_canary(parameters, agent_decision_mode, len(agents))
            enforce_hybrid_cohort_limit(cohort_id, len(agents))
            update_policy_run(run_id, fields={"cohortSize": len(agents)})

            with _POLICY_WIND_TUNNEL_LOCK:
                if not clock_driven_worker_state_matches(run_id, "RUNNING", "PREPARING_CLOCK"):
                    append_clock_driven_start_stopped_log(run_id, "before preparing simulator clock")
                    return
                if not apply_hybrid_replay_contract(run_id, parameters, playback_rate, resume_source_phase):
                    paused_from_phase = trim(resume_source_phase) or "PREPARING_CLOCK"
                    update_policy_run(
                        run_id,
                        status="PAUSED",
                        phase="PAUSED",
                        progress=0.05,
                        bundle_ready=False,
                        paused_from_phase=paused_from_phase,
                    )
                    action_label = "Start requested" if not trim(resume_source_phase) else "Resume requested"
                    append_policy_log(
                        run_id,
                        f"{action_label}. Setup remains paused because simulator replay ownership was not empty or owned by this run.",
                    )
                    return
            with _POLICY_WIND_TUNNEL_LOCK:
                if not clock_driven_worker_state_matches(run_id, "RUNNING", "PREPARING_CLOCK"):
                    append_clock_driven_start_stopped_log(run_id, "after preparing simulator clock")
                    return
                if not platform_pinned_activation:
                    clear_active_agents(run_id)

            if not transition_clock_driven_worker_phase(
                run_id,
                expected_status="RUNNING",
                expected_phase="PREPARING_CLOCK",
                next_status="RUNNING",
                next_phase="ACTIVATING_COHORT",
                progress=0.15,
                checkpoint="before activating cohort",
            ):
                return
            append_policy_log(run_id, f"Activating {len(agents)} customers in chunks of {HYBRID_ACTIVATION_CHUNK_SIZE}.")
            append_policy_log(run_id, f"Agent Decision Mode: {agent_decision_mode}.")

            if cohort_snapshot is not None:
                with _POLICY_WIND_TUNNEL_LOCK:
                    if stop_if_clock_driven_activation_should_stop(run_id, "before pinned cohort activation"):
                        return
                activate_pinned_cohort(
                    run_id,
                    cohort_id,
                    cohort_snapshot,
                    agent_decision_mode,
                    parameters,
                    len(agents),
                )
                with _POLICY_WIND_TUNNEL_LOCK:
                    if stop_if_clock_driven_activation_should_stop(run_id, "after pinned cohort activation"):
                        return
                    update_policy_run(run_id, status="RUNNING", phase="ACTIVATING_COHORT", progress=0.50, bundle_ready=False)
            else:
                chunk_count = max(1, (len(agents) + HYBRID_ACTIVATION_CHUNK_SIZE - 1) // HYBRID_ACTIVATION_CHUNK_SIZE)
                for chunk_index in range(chunk_count):
                    with _POLICY_WIND_TUNNEL_LOCK:
                        if stop_if_clock_driven_activation_should_stop(run_id, f"before activating chunk {chunk_index + 1}"):
                            return
                    start = chunk_index * HYBRID_ACTIVATION_CHUNK_SIZE
                    end = min(len(agents), start + HYBRID_ACTIVATION_CHUNK_SIZE)
                    chunk = agents[start:end]
                    activate_agent_chunk(
                        run_id,
                        cohort_id,
                        "",
                        chunk_index + 1,
                        chunk_count,
                        chunk,
                    )
                    progress = 0.15 + (0.35 * ((chunk_index + 1) / chunk_count))
                    with _POLICY_WIND_TUNNEL_LOCK:
                        if stop_if_clock_driven_activation_should_stop(run_id, f"after activating chunk {chunk_index + 1}"):
                            return
                        update_policy_run(run_id, status="RUNNING", phase="ACTIVATING_COHORT", progress=progress, bundle_ready=False)

            if rate_change_plan is not None:
                expected_rate_change_agent_ids = {
                    trim(agent.get("personaName")) or trim(agent.get("agentId"))
                    for agent in agents
                }
                if "" in expected_rate_change_agent_ids or len(expected_rate_change_agent_ids) != len(agents):
                    raise RuntimeError("Activated cohort has invalid rate-change customer identities.")
                expected_identity_set_digest = rate_change_agent_identity_set_digest(
                    list(expected_rate_change_agent_ids)
                )
                if (
                    cohort_snapshot is not None
                    and expected_identity_set_digest
                    != trim(cohort_snapshot.get("identitySetDigest"))
                ):
                    raise RuntimeError(
                        "Activated rate-change cohort does not match the platform-pinned identity set."
                    )
                install_clock_driven_rate_change_plan(
                    run_id,
                    rate_change_plan,
                    expected_rate_change_agent_ids,
                    expected_identity_set_digest,
                    trim(cohort_snapshot.get("artifactDigest")),
                )

            if not start_simulator_clock_after_activation(run_id, playback_rate):
                return
            horizon_days = max(1, int_or_default(parameters.get("simulationHorizonDays"), 30))
            append_policy_log(
                run_id,
                "Clock-driven cohort activated. Simulator clock now owns progression; E24 evidence records runtime activity.",
            )
            append_policy_log(
                run_id,
                "Clock target: "
                + str(horizon_days)
                + " simulated day(s) at "
                + format_playback_rate(playback_rate)
                + " playback (~"
                + estimate_wall_clock_duration(horizon_days, playback_rate)
                + "). At the horizon, the run enters the platform-owned pause, drain, and finalization handoff; use Finalize early when evidence is sufficient.",
            )
            start_clock_horizon_monitor(run_id, execution_claim_owner)
        except StalePolicyExecutionClaim:
            return
        except Exception as exc:
            try:
                handle_clock_driven_worker_failure(run_id, exc)
            except StalePolicyExecutionClaim:
                return


def handle_clock_driven_worker_failure(run_id: str, exc: Exception) -> None:
    execution_claim_owner = current_policy_execution_claim_owner(run_id)
    if execution_claim_owner is not None:
        renew_policy_execution_claim(run_id, execution_claim_owner)
    with finalization_receipt_lock(run_id):
        renew_current_policy_execution_claim(run_id)
        receipt = load_finalization_receipt(run_id)
        with _POLICY_WIND_TUNNEL_LOCK:
            current_run = policy_run_record(run_id)
            if not lifecycle_transition_allows(current_run, TRANSITION_FAIL):
                append_policy_log(
                    run_id,
                    "Run worker failed after lifecycle moved away from a failable state; preserving current run state. Error: "
                    + (str(exc) or exc.__class__.__name__),
                )
                return
            try:
                apply_playback_control_for_run(
                    run_id,
                    paused=True,
                    playback_rate=0.0,
                    action_label="Run failure pause requested",
                )
            except Exception as pause_exc:  # pragma: no cover - depends on simulator availability
                append_policy_log(
                    run_id,
                    "Run failed, but simulator clock pause was not confirmed: "
                    + (str(pause_exc) or pause_exc.__class__.__name__),
                )
            clear_hybrid_runtime_cohort(run_id)
            terminal_run = policy_run_snapshot_with_lifecycle(
                current_run,
                status="FAILED",
                phase="FAILED",
                progress=1.0,
                bundle_ready=True,
            )
            terminal_run["error"] = {
                "message": str(exc) or exc.__class__.__name__,
                "type": exc.__class__.__name__,
            }
            terminal_run["logTail"] = (
                list(terminal_run.get("logTail", []))
                + ["Run failed: " + (str(exc) or exc.__class__.__name__)]
            )[-40:]
            persist_terminal_policy_run(run_id, terminal_run, receipt)


def start_clock_horizon_monitor(
    run_id: str,
    execution_claim_owner: str | None = None,
) -> None:
    worker = threading.Thread(
        target=monitor_clock_driven_horizon,
        args=(run_id, execution_claim_owner),
        name=f"banking-python-wt-horizon-{safe_token(run_id)}",
        daemon=True,
    )
    worker.start()


def monitor_clock_driven_horizon(
    run_id: str,
    execution_claim_owner: str | None = None,
) -> None:
    with policy_execution_claim_scope(run_id, execution_claim_owner):
        while True:
            try:
                if execution_claim_owner is not None:
                    renew_policy_execution_claim(run_id, execution_claim_owner)
                run = policy_run_record(run_id)
                if run.get("status") != "RUNNING" or run.get("phase") != "CLOCK_RUNNING":
                    return
                if not is_clock_driven_parameters(run.get("parameters", {})):
                    return
                maybe_advance_clock_driven_lifecycle(run_id)
                run = policy_run_record(run_id)
                if run.get("status") != "RUNNING" or run.get("phase") != "CLOCK_RUNNING":
                    return
                time.sleep(clock_horizon_monitor_poll_seconds(dict(run.get("parameters", {}) or {})))
            except StalePolicyExecutionClaim:
                return
            except Exception as exc:  # pragma: no cover - defensive monitor guard
                try:
                    append_policy_log(
                        run_id,
                        "Clock horizon monitor stopped before finalization: "
                        + (str(exc) or exc.__class__.__name__),
                    )
                except (ProtocolError, StalePolicyExecutionClaim):
                    pass
                return


def clock_horizon_monitor_poll_seconds(parameters: dict[str, Any]) -> float:
    rate = max(0.1, float_or_default(parameters.get("playbackRate"), 1.0))
    # Keep automatic horizon overshoot below roughly 0.02 simulated days for common playback rates.
    return max(0.25, min(2.0, (0.02 * 86_400.0) / rate))


def finalize_clock_driven_policy_wind_tunnel_run(
    run_id: str,
    payload: dict[str, Any] | None = None,
) -> None:
    request_payload = dict(payload or {})
    operation = finalization_operation(request_payload)
    initial_run = policy_run_record(run_id)
    initial_mode = policy_run_finalization_mode(initial_run)
    require_finalization_operation(run_id, initial_mode, operation)
    if is_explicit_legacy_finalization_mode(initial_mode):
        finalize_legacy_clock_driven_policy_wind_tunnel_run(run_id)
        return
    with finalization_receipt_lock(run_id):
        receipt = load_finalization_receipt(run_id)
        run = policy_run_record(run_id)
        finalization_mode = (
            trim(receipt.get("finalizationMode"))
            if receipt
            else policy_run_finalization_mode(run)
        )
        require_finalization_operation(run_id, finalization_mode, operation)
        input_digest = (
            operation["inputDigest"]
            if operation is not None
            else hashlib.sha256(canonical_finalization_bytes(request_payload)).hexdigest()
        )

        if receipt:
            validate_finalization_receipt(
                receipt,
                run_id,
                finalization_mode,
                operation,
                input_digest,
            )
            require_published_finalization_artifacts(receipt["artifactManifest"])
            current_run = policy_run_record(run_id)
            if policy_run_is_terminal(run_id):
                receipt_status = trim(receipt.get("runSnapshot", {}).get("status"))
                if (
                    receipt["state"] == FINALIZATION_RECEIPT_PREPARED
                    or receipt_status != trim(current_run.get("status"))
                ):
                    receipt = finalization_receipt(
                        run_id,
                        finalization_mode,
                        operation,
                        input_digest,
                        FINALIZATION_RECEIPT_PUBLISHED,
                        current_run,
                        receipt["artifactManifest"],
                    )
                    attach_completion_notification_payload(receipt)
                    write_finalization_receipt(run_id, receipt)
                confirm_finalization_notification(receipt)
                return
            restore_policy_run_from_finalization_receipt(receipt, replace_existing=True)
            if receipt["state"] == FINALIZATION_RECEIPT_PUBLISHED:
                confirm_finalization_notification(receipt)
                return
        else:
            validate_finalization_operation(run, operation, run_id)
            if policy_run_is_terminal(run_id):
                terminal_status = trim(run.get("status")).upper()
                if terminal_status in {"FAILED", "CANCELLED"}:
                    if input_digest != expected_finalization_input_digest(run_id):
                        raise ProtocolError(
                            409,
                            "finalization_operation_conflict",
                            "conflict",
                            "Scenario Lab terminal no-effect replay had contradictory input",
                            operation="policy-wind-tunnel-lifecycle",
                            details={"runId": run_id},
                        )
                    correlation_audit = prefetch_rate_change_correlation_audit(
                        run_id,
                        dict(run.get("parameters", {}) or {}),
                    )
                    finalized_bundle = clock_driven_policy_wind_tunnel_bundle(
                        run_id,
                        finalized=True,
                        correlation_audit=correlation_audit,
                    )
                    write_clock_driven_artifact_files(run_id, finalized_bundle["summary"])
                    artifact_manifest = finalization_artifact_manifest(run_id)
                    require_published_finalization_artifacts(artifact_manifest)
                    terminal_receipt = finalization_receipt(
                        run_id,
                        finalization_mode,
                        operation,
                        input_digest,
                        FINALIZATION_RECEIPT_PUBLISHED,
                        run,
                        artifact_manifest,
                    )
                    write_finalization_receipt(run_id, terminal_receipt)
                    store_policy_run_snapshot(run_id, run)
                    return
                raise ProtocolError(
                    409,
                    "finalization_receipt_missing",
                    "conflict",
                    "A platform-owned terminal run has no durable keyed finalization receipt",
                    operation="policy-wind-tunnel-lifecycle",
                    details={"runId": run_id},
                )
            if not clock_driven_run_can_finalize(run):
                append_lifecycle_ignored_log(run_id, "Finalize")
                return
            reconciliation = fetch_decision_opportunity_reconciliation(run_id)
            if (
                not opportunity_reconciliation_is_valid(reconciliation, run_id)
                or reconciliation.get("generationSealed") is not True
            ):
                append_policy_log(
                    run_id,
                    "Finalize deferred because platform-owned opportunity generation sealing is not verified.",
                )
                return

            correlation_audit = prefetch_rate_change_correlation_audit(
                run_id,
                dict(run.get("parameters", {}) or {}),
            )
            finalized_bundle = clock_driven_policy_wind_tunnel_bundle(
                run_id,
                finalized=True,
                correlation_audit=correlation_audit,
            )
            write_clock_driven_artifact_files(run_id, finalized_bundle["summary"])
            artifact_manifest = finalization_artifact_manifest(run_id)
            require_published_finalization_artifacts(artifact_manifest)
            with _POLICY_WIND_TUNNEL_LOCK:
                publication_state = deep_json_copy(policy_run_record(run_id))
                if is_terminal_phase(publication_state.get("status")):
                    publication_state["logTail"] = (
                        list(publication_state.get("logTail", []))
                        + [
                            "Finalize completion was not applied because run state changed to "
                            + f"{trim(publication_state.get('status'))}/{trim(publication_state.get('phase'))}."
                        ]
                    )[-40:]
                    receipt = finalization_receipt(
                        run_id,
                        finalization_mode,
                        operation,
                        input_digest,
                        FINALIZATION_RECEIPT_PUBLISHED,
                        publication_state,
                        artifact_manifest,
                    )
                    attach_completion_notification_payload(receipt)
                    write_finalization_receipt(run_id, receipt)
                    store_policy_run_snapshot(run_id, publication_state)
                else:
                    prepared_run = policy_run_snapshot_with_lifecycle(
                        publication_state,
                        status="RUNNING",
                        phase="FINALIZING_REPORT",
                        progress=0.92,
                        bundle_ready=True,
                    )
                    if operation is not None:
                        prepared_run[FINALIZATION_OPERATION_FIELD] = dict(operation)
                    receipt = finalization_receipt(
                        run_id,
                        finalization_mode,
                        operation,
                        input_digest,
                        FINALIZATION_RECEIPT_PREPARED,
                        prepared_run,
                        artifact_manifest,
                    )
                    write_finalization_receipt(run_id, receipt)
                    store_policy_run_snapshot(run_id, prepared_run)
            if receipt["state"] == FINALIZATION_RECEIPT_PUBLISHED:
                confirm_finalization_notification(receipt)
                return

        artifact_manifest = receipt["artifactManifest"]
        require_matching_artifact_manifest(receipt, finalization_artifact_manifest(run_id))
        require_published_finalization_artifacts(artifact_manifest)
        finalized_summary = read_finalized_summary(artifact_manifest)

        with _POLICY_WIND_TUNNEL_LOCK:
            run = deep_json_copy(policy_run_record(run_id))
            if not lifecycle_transition_allows(run, TRANSITION_FINALIZE_COMPLETE):
                published_run = run
                published_run["logTail"] = (
                    list(published_run.get("logTail", []))
                    + [
                        "Finalize completion was not applied because run state changed to "
                        f"{trim(run.get('status'))}/{trim(run.get('phase'))}."
                    ]
                )[-40:]
            else:
                published_run = policy_run_snapshot_with_lifecycle(
                    run,
                    status="COMPLETED",
                    phase="COMPLETED",
                    progress=1.0,
                    bundle_ready=True,
                )
                summary_path = str(
                    finalization_summary_directory(run_id).joinpath("scenario-lab-summary.json")
                )
                published_run["summaryPath"] = relativize_workspace_path(Path(summary_path))
                published_run["bundleUrl"] = (
                    f"/api/experiment-runs/{quote(run_id)}/scenario-lab/bundle"
                )
                published_run["evidencePackUrl"] = (
                    f"/api/experiment-runs/{quote(run_id)}/scenario-lab/evidence-pack"
                )
                published_run["logTail"] = (
                    list(published_run.get("logTail", []))
                    + [
                        "Clock-Driven Simulation evidence finalized into outcome comparison report.",
                        "Runtime cohort retained for post-run inspection. Use Runtime Overview Reset Run "
                        "when you want to clear active agents and the live feed.",
                    ]
                )[-40:]
                published_run["completedAtEpochMs"] = int(time.time() * 1000)
            published_receipt = finalization_receipt(
                run_id,
                finalization_mode,
                operation,
                input_digest,
                FINALIZATION_RECEIPT_PUBLISHED,
                published_run,
                artifact_manifest,
            )
            attach_completion_notification_payload(published_receipt, finalized_summary)
            write_finalization_receipt(run_id, published_receipt)
            store_policy_run_snapshot(run_id, published_run)
        confirm_finalization_notification(published_receipt)


def finalize_legacy_clock_driven_policy_wind_tunnel_run(run_id: str) -> None:
    with _POLICY_WIND_TUNNEL_LOCK:
        run = policy_run_record(run_id)
        if policy_run_is_terminal(run_id):
            return
        if not clock_driven_run_can_finalize(run):
            append_lifecycle_ignored_log(run_id, "Finalize")
            return
    reconciliation = fetch_decision_opportunity_reconciliation(run_id)
    if (
        not opportunity_reconciliation_is_valid(reconciliation, run_id)
        or reconciliation.get("generationSealed") is not True
    ):
        append_policy_log(
            run_id,
            "Legacy finalize deferred because platform-owned opportunity generation sealing is not verified.",
        )
        return
    finalized_bundle = clock_driven_policy_wind_tunnel_bundle(run_id, finalized=True)
    write_clock_driven_artifact_files(run_id, finalized_bundle["summary"])
    with _POLICY_WIND_TUNNEL_LOCK:
        run = policy_run_record(run_id)
        if not lifecycle_transition_allows(run, TRANSITION_FINALIZE_COMPLETE):
            return
        published_run = policy_run_snapshot_with_lifecycle(
            run,
            status="COMPLETED",
            phase="COMPLETED",
            progress=1.0,
            bundle_ready=True,
        )
        summary_path = finalization_summary_directory(run_id).joinpath(
            "scenario-lab-summary.json"
        )
        published_run["summaryPath"] = relativize_workspace_path(summary_path)
        published_run["bundleUrl"] = (
            f"/api/experiment-runs/{quote(run_id)}/scenario-lab/bundle"
        )
        published_run["evidencePackUrl"] = (
            f"/api/experiment-runs/{quote(run_id)}/scenario-lab/evidence-pack"
        )
        published_run["completedAtEpochMs"] = int(time.time() * 1000)
        store_policy_run_snapshot(run_id, published_run)
    parameters = dict(published_run.get("parameters", {}) or {})
    notify_simulation_run_completed(
        {
            "runId": run_id,
            "status": "COMPLETED",
            "source": "banking-python-remote",
            "summaryPath": published_run["summaryPath"],
            "bundlePath": published_run["bundleUrl"],
            "completedAtEpochMs": published_run["completedAtEpochMs"],
            "metadata": {
                "source": "banking-python-remote",
                "domainId": DOMAIN_ID,
                "cohortId": trim(parameters.get("cohortId")) or trim(parameters.get("cohort")),
                "scenarioLab": {
                    "phase": "COMPLETED",
                    "progress": 1.0,
                    "bundleReady": True,
                    "summaryMode": trim(finalized_bundle["summary"].get("summaryMode")),
                    "parameters": parameters,
                    "agenticSimulation": dict(
                        finalized_bundle["summary"].get("agenticSimulation", {}) or {}
                    ),
                },
            },
        }
    )


@contextmanager
def finalization_receipt_lock(run_id: str):
    run_root = finalization_run_directory(run_id)
    mkdirs_durably(run_root)
    lock_path = run_root.joinpath(FINALIZATION_RECEIPT_LOCK_FILE)
    with _FINALIZATION_RECEIPT_LOCKS_GUARD:
        process_lock = _FINALIZATION_RECEIPT_LOCKS.setdefault(lock_path, threading.RLock())
    with process_lock:
        with lock_path.open("a+b") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def finalization_artifact_root() -> Path:
    return workspace_root().joinpath(
        "output", "experiments", "banking-python-scenario-lab", "runs"
    ).resolve()


def finalization_run_directory(run_id: str) -> Path:
    artifact_root = finalization_artifact_root()
    run_root = artifact_root.joinpath(run_id).resolve()
    if artifact_root not in run_root.parents:
        raise ProtocolError(
            400,
            "invalid_run_id",
            "invalid_request",
            "Scenario Lab runId escapes the artifact root",
            operation="policy-wind-tunnel-lifecycle",
        )
    return run_root


def finalization_summary_directory(run_id: str) -> Path:
    return finalization_run_directory(run_id).joinpath("summary")


@contextmanager
def policy_run_state_lock(run_id: str):
    run_root = finalization_run_directory(run_id)
    mkdirs_durably(run_root)
    lock_path = run_root.joinpath(POLICY_RUN_STATE_LOCK_FILE)
    with _POLICY_RUN_STATE_LOCKS_GUARD:
        process_lock = _POLICY_RUN_STATE_LOCKS.setdefault(lock_path, threading.RLock())
    with process_lock:
        with lock_path.open("a+b") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


def policy_run_state_path(run_id: str) -> Path:
    return finalization_run_directory(run_id).joinpath(POLICY_RUN_STATE_FILE)


def strict_json_object(content: bytes, error_message: str) -> dict[str, Any]:
    def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON property: {key}")
            result[key] = value
        return result

    def reject_non_finite(value: str) -> Any:
        raise ValueError(f"non-finite JSON number: {value}")

    def parse_finite_float(value: str) -> float:
        resolved = float(value)
        if not math.isfinite(resolved):
            raise ValueError(f"non-finite JSON number: {value}")
        return resolved

    try:
        decoded = content.decode("utf-8", errors="strict")
        value = json.loads(
            decoded,
            object_pairs_hook=reject_duplicate_pairs,
            parse_constant=reject_non_finite,
            parse_float=parse_finite_float,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise RuntimeError(error_message) from exc
    if not isinstance(value, dict):
        raise RuntimeError(error_message)
    return value


def exact_ijson_integer(value: Any, *, default: int) -> int:
    if type(value) is not int or value < 0 or value > 9_007_199_254_740_991:
        return default
    return value


@contextmanager
def policy_execution_claim_scope(run_id: str, execution_claim_owner: str | None):
    previous = getattr(_POLICY_EXECUTION_CONTEXT, "claim", None)
    if execution_claim_owner:
        _POLICY_EXECUTION_CONTEXT.claim = (run_id, execution_claim_owner)
    else:
        _POLICY_EXECUTION_CONTEXT.claim = None
    try:
        yield
    finally:
        _POLICY_EXECUTION_CONTEXT.claim = previous


def current_policy_execution_claim_owner(run_id: str) -> str | None:
    claim = getattr(_POLICY_EXECUTION_CONTEXT, "claim", None)
    if not isinstance(claim, tuple) or len(claim) != 2 or claim[0] != run_id:
        return None
    return trim(claim[1]) or None


def require_current_policy_execution_claim_unlocked(
    run_id: str,
    run: dict[str, Any],
    *,
    now_epoch_ms: int | None = None,
) -> None:
    owner = current_policy_execution_claim_owner(run_id)
    if owner is None:
        return
    now = int(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
    expires_at = exact_ijson_integer(
        run.get(POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD),
        default=0,
    )
    if (
        is_terminal_phase(run.get("status"))
        or trim(run.get(POLICY_EXECUTION_CLAIM_OWNER_FIELD)) != owner
        or expires_at <= now
    ):
        raise StalePolicyExecutionClaim(
            "Scenario Lab worker no longer owns the durable execution claim"
        )
    run[POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = (
        now + POLICY_EXECUTION_CLAIM_LEASE_MS
    )


def renew_policy_execution_claim(
    run_id: str,
    execution_claim_owner: str,
    *,
    now_epoch_ms: int | None = None,
) -> None:
    now = int(time.time() * 1000) if now_epoch_ms is None else now_epoch_ms
    with _POLICY_WIND_TUNNEL_LOCK:
        with policy_run_state_lock(run_id):
            run = load_policy_run_state_unlocked(run_id)
            if run is None:
                raise StalePolicyExecutionClaim(
                    "Scenario Lab worker execution claim has no durable run"
                )
            expires_at = exact_ijson_integer(
                run.get(POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD),
                default=0,
            )
            if (
                is_terminal_phase(run.get("status"))
                or trim(run.get(POLICY_EXECUTION_CLAIM_OWNER_FIELD))
                != execution_claim_owner
                or expires_at <= now
            ):
                raise StalePolicyExecutionClaim(
                    "Scenario Lab worker no longer owns the durable execution claim"
                )
            run[POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = (
                now + POLICY_EXECUTION_CLAIM_LEASE_MS
            )
            write_policy_run_state_unlocked(run_id, run)
            _POLICY_WIND_TUNNEL_RUNS[run_id] = deep_json_copy(run)


def renew_current_policy_execution_claim(run_id: str) -> None:
    owner = current_policy_execution_claim_owner(run_id)
    if owner is not None:
        renew_policy_execution_claim(run_id, owner)


def load_policy_run_state_unlocked(run_id: str) -> dict[str, Any] | None:
    path = policy_run_state_path(run_id)
    if not path.exists():
        return None
    content = path.read_bytes()
    if not content or len(content) > MAX_POLICY_RUN_STATE_BYTES:
        raise RuntimeError("Scenario Lab provider run state size is invalid")
    document = strict_json_object(content, "Scenario Lab provider run state could not be read")
    run = document.get("run")
    if (
        set(document) != {"schemaVersion", "providerRevision", "run"}
        or document.get("schemaVersion") != POLICY_RUN_STATE_SCHEMA
        or document.get("providerRevision") != WIND_TUNNEL_PROVIDER_REVISION
        or not isinstance(run, dict)
        or run.get("runId") != run_id
    ):
        raise RuntimeError("Scenario Lab provider run state is invalid")
    return deep_json_copy(run)


def load_policy_run_state(run_id: str) -> dict[str, Any] | None:
    if not policy_run_state_path(run_id).exists():
        return None
    with policy_run_state_lock(run_id):
        return load_policy_run_state_unlocked(run_id)


def write_policy_run_state_unlocked(run_id: str, run: dict[str, Any]) -> None:
    if not isinstance(run, dict) or run.get("runId") != run_id:
        raise RuntimeError("Scenario Lab provider run state is invalid")
    document = {
        "schemaVersion": POLICY_RUN_STATE_SCHEMA,
        "providerRevision": WIND_TUNNEL_PROVIDER_REVISION,
        "run": deep_json_copy(run),
    }
    atomic_write_bytes(
        policy_run_state_path(run_id),
        json.dumps(
            document,
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8"),
    )


def store_policy_run_snapshot(run_id: str, run: dict[str, Any]) -> None:
    snapshot = deep_json_copy(run)
    with _POLICY_WIND_TUNNEL_LOCK:
        with policy_run_state_lock(run_id):
            if current_policy_execution_claim_owner(run_id) is not None:
                current = load_policy_run_state_unlocked(run_id)
                if current is None:
                    raise StalePolicyExecutionClaim(
                        "Scenario Lab worker execution claim has no durable run"
                    )
                require_current_policy_execution_claim_unlocked(run_id, current)
                if not is_terminal_phase(snapshot.get("status")):
                    snapshot[POLICY_EXECUTION_CLAIM_OWNER_FIELD] = current[
                        POLICY_EXECUTION_CLAIM_OWNER_FIELD
                    ]
                    snapshot[POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = current[
                        POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD
                    ]
            write_policy_run_state_unlocked(run_id, snapshot)
            _POLICY_WIND_TUNNEL_RUNS[run_id] = snapshot


def finalization_receipt_path(run_id: str) -> Path:
    return finalization_run_directory(run_id).joinpath(FINALIZATION_RECEIPT_FILE)


def load_finalization_receipt(run_id: str) -> dict[str, Any]:
    path = finalization_receipt_path(run_id)
    if not path.exists():
        return {}
    content = path.read_bytes()
    if not content or len(content) > 1024 * 1024:
        raise RuntimeError("Scenario Lab finalization receipt size is invalid")
    receipt = strict_json_object(content, "Scenario Lab finalization receipt could not be read")
    validate_stored_finalization_receipt(receipt, run_id)
    require_published_finalization_artifacts(receipt["artifactManifest"])
    require_matching_artifact_manifest(receipt, finalization_artifact_manifest(run_id))
    return dict(receipt)


def write_finalization_receipt(run_id: str, receipt: dict[str, Any]) -> None:
    renew_current_policy_execution_claim(run_id)
    validate_stored_finalization_receipt(receipt, run_id)
    require_matching_artifact_manifest(receipt, finalization_artifact_manifest(run_id))
    atomic_write_bytes(
        finalization_receipt_path(run_id),
        json.dumps(
            receipt,
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8"),
    )


def validate_stored_finalization_receipt(receipt: dict[str, Any], run_id: str) -> None:
    state = trim(receipt.get("state"))
    mode = trim(receipt.get("finalizationMode"))
    operation_id = trim(receipt.get("operationId"))
    input_digest = trim(receipt.get("inputDigest"))
    provider_revision = trim(receipt.get("providerRevision"))
    notification_state = trim(receipt.get("completionNotificationState"))
    run_snapshot = receipt.get("runSnapshot")
    notification_payload = receipt.get("completionNotificationPayload")
    if (
        set(receipt) != FINALIZATION_RECEIPT_FIELDS
        or receipt.get("schemaVersion") != FINALIZATION_RECEIPT_SCHEMA
        or receipt.get("runId") != run_id
        or not valid_receipt_run_id(run_id)
        or receipt.get("state") != state
        or state not in {FINALIZATION_RECEIPT_PREPARED, FINALIZATION_RECEIPT_PUBLISHED}
        or receipt.get("finalizationMode") != FINALIZATION_MODE_PLATFORM_OWNED_V2
        or receipt.get("providerRevision") != WIND_TUNNEL_PROVIDER_REVISION
        or not isinstance(receipt.get("operationId"), str)
        or not isinstance(receipt.get("inputDigest"), str)
        or re.fullmatch(r"scenario-lab-finalize:[0-9a-f]{64}", operation_id) is None
        or re.fullmatch(r"[0-9a-f]{64}", input_digest) is None
        or receipt.get("completionNotificationState") != notification_state
        or notification_state
        not in {
            FINALIZATION_NOTIFICATION_PENDING,
            FINALIZATION_NOTIFICATION_CONFIRMED,
            FINALIZATION_NOTIFICATION_NOT_REQUIRED,
        }
        or not isinstance(run_snapshot, dict)
        or not valid_finalization_run_snapshot(run_snapshot, run_id, mode, state)
        or not isinstance(receipt.get("artifactManifest"), dict)
        or not receipt.get("artifactManifest")
        or not valid_finalization_artifact_manifest(receipt["artifactManifest"])
        or not valid_iso_datetime(receipt.get("updatedAt"))
    ):
        raise RuntimeError("Scenario Lab finalization receipt is invalid")
    if (
        operation_id != expected_finalization_operation_id(run_id)
    ):
        raise RuntimeError("Scenario Lab V2 finalization receipt is not operation-keyed")
    published = state == FINALIZATION_RECEIPT_PUBLISHED
    completed = trim(run_snapshot.get("status")) == "COMPLETED"
    if not published and (
        notification_state != FINALIZATION_NOTIFICATION_PENDING
        or notification_payload != {}
    ):
        raise RuntimeError("Scenario Lab PREPARED receipt notification state is invalid")
    if published and completed:
        if (
            notification_state == FINALIZATION_NOTIFICATION_NOT_REQUIRED
            or not valid_completion_notification_payload(
                notification_payload,
                run_id,
                operation_id,
                input_digest,
                provider_revision,
                run_snapshot,
            )
        ):
            raise RuntimeError("Scenario Lab completion notification receipt is invalid")
    elif published and (
        notification_state != FINALIZATION_NOTIFICATION_NOT_REQUIRED
        or notification_payload != {}
    ):
        raise RuntimeError("Scenario Lab terminal receipt notification state is invalid")


def validate_finalization_receipt(
    receipt: dict[str, Any],
    run_id: str,
    finalization_mode: str,
    operation: dict[str, str] | None,
    input_digest: str,
) -> None:
    validate_stored_finalization_receipt(receipt, run_id)
    requested_operation_id = "" if operation is None else operation["operationId"]
    if (
        receipt.get("finalizationMode") != finalization_mode
        or receipt.get("operationId") != requested_operation_id
        or receipt.get("inputDigest") != input_digest
    ):
        raise ProtocolError(
            409,
            "finalization_operation_conflict",
            "conflict",
            "Scenario Lab finalization operationId was reused with contradictory input",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id},
        )


def finalization_receipt(
    run_id: str,
    finalization_mode: str,
    operation: dict[str, str] | None,
    input_digest: str,
    state: str,
    run_snapshot: dict[str, Any],
    artifact_manifest: dict[str, Any],
) -> dict[str, Any]:
    if finalization_mode != FINALIZATION_MODE_PLATFORM_OWNED_V2 or operation is None:
        raise RuntimeError("Scenario Lab V2 finalization receipt requires a pinned V2 operation")
    closed_run_snapshot = finalization_run_snapshot(run_id, run_snapshot)
    notification_required = (
        state == FINALIZATION_RECEIPT_PUBLISHED
        and trim(closed_run_snapshot.get("status")) == "COMPLETED"
    )
    return {
        "schemaVersion": FINALIZATION_RECEIPT_SCHEMA,
        "runId": run_id,
        "finalizationMode": finalization_mode,
        "providerRevision": WIND_TUNNEL_PROVIDER_REVISION,
        "operationId": operation["operationId"],
        "inputDigest": input_digest,
        "state": state,
        "completionNotificationState": (
            FINALIZATION_NOTIFICATION_PENDING
            if notification_required or state == FINALIZATION_RECEIPT_PREPARED
            else FINALIZATION_NOTIFICATION_NOT_REQUIRED
        ),
        "completionNotificationPayload": {},
        "runSnapshot": closed_run_snapshot,
        "artifactManifest": deep_json_copy(artifact_manifest),
        "updatedAt": utc_now_iso(),
    }


def finalization_run_snapshot(run_id: str, run: dict[str, Any]) -> dict[str, Any]:
    provider_run = deep_json_copy(run)
    status = trim(provider_run.get("status")).upper()
    if status not in {"RUNNING", "COMPLETED", "FAILED", "CANCELLED"}:
        raise RuntimeError("Scenario Lab finalization run status is invalid")
    if trim(provider_run.get("runId")) != run_id:
        raise RuntimeError("Scenario Lab finalization run snapshot has the wrong runId")
    started_at_epoch_ms = finalization_epoch_ms(
        provider_run.get("startedAtEpochMs"), provider_run.get("startedAt")
    )
    snapshot: dict[str, Any] = {
        "status": status,
        "summaryPath": trim(provider_run.get("summaryPath")) or None,
        "bundlePath": (
            trim(provider_run.get("bundlePath"))
            or trim(provider_run.get("bundleUrl"))
            or None
        ),
        "startedAtEpochMs": started_at_epoch_ms,
        "metadata": {
            "phase": trim(provider_run.get("phase")) or status,
            "providerRun": provider_run,
        },
    }
    if status in {"COMPLETED", "FAILED", "CANCELLED"}:
        snapshot["completedAtEpochMs"] = finalization_epoch_ms(
            provider_run.get("completedAtEpochMs"), provider_run.get("updatedAt")
        )
    return snapshot


def finalization_epoch_ms(explicit_value: Any, iso_value: Any) -> int:
    if (
        isinstance(explicit_value, int)
        and not isinstance(explicit_value, bool)
        and 1 <= explicit_value <= 9_007_199_254_740_991
    ):
        return explicit_value
    try:
        parsed = datetime.fromisoformat(trim(iso_value).replace("Z", "+00:00"))
        epoch_ms = int(parsed.timestamp() * 1000)
    except (TypeError, ValueError, OverflowError) as exc:
        raise RuntimeError("Scenario Lab finalization timestamp is invalid") from exc
    if epoch_ms < 1 or epoch_ms > 9_007_199_254_740_991:
        raise RuntimeError("Scenario Lab finalization timestamp is outside I-JSON bounds")
    return epoch_ms


def valid_finalization_run_snapshot(
    snapshot: dict[str, Any], run_id: str, finalization_mode: str, receipt_state: str
) -> bool:
    if (
        not {"status", "metadata"}.issubset(snapshot)
        or not set(snapshot).issubset(FINALIZATION_RUN_SNAPSHOT_FIELDS)
    ):
        return False
    status = snapshot.get("status")
    if receipt_state == FINALIZATION_RECEIPT_PREPARED and status != "RUNNING":
        return False
    if receipt_state == FINALIZATION_RECEIPT_PUBLISHED and status not in {
        "COMPLETED",
        "FAILED",
        "CANCELLED",
    }:
        return False
    for path_field in ("summaryPath", "bundlePath"):
        value = snapshot.get(path_field)
        if value is not None and not isinstance(value, str):
            return False
    started_at = snapshot.get("startedAtEpochMs")
    if not valid_ijson_positive_int(started_at):
        return False
    completed_at = snapshot.get("completedAtEpochMs")
    if status in {"COMPLETED", "FAILED", "CANCELLED"}:
        if not valid_ijson_positive_int(completed_at):
            return False
    elif completed_at is not None:
        return False
    metadata = snapshot.get("metadata")
    provider_run = metadata.get("providerRun") if isinstance(metadata, dict) else None
    parameters = provider_run.get("parameters") if isinstance(provider_run, dict) else None
    return (
        isinstance(metadata, dict)
        and isinstance(provider_run, dict)
        and trim(provider_run.get("runId")) == run_id
        and trim(provider_run.get("status")).upper() == status
        and trim(metadata.get("phase")) == trim(provider_run.get("phase"))
        and isinstance(parameters, dict)
        and trim(parameters.get(FINALIZATION_MODE_FIELD)) == finalization_mode
    )


def valid_ijson_positive_int(value: Any) -> bool:
    return (
        isinstance(value, int)
        and not isinstance(value, bool)
        and 1 <= value <= 9_007_199_254_740_991
    )


def valid_receipt_run_id(value: Any) -> bool:
    return (
        isinstance(value, str)
        and 1 <= len(value) <= 256
        and all(0x20 <= ord(character) <= 0x7E for character in value)
    )


def valid_iso_datetime(value: Any) -> bool:
    try:
        parsed = datetime.fromisoformat(trim(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return False
    return parsed.tzinfo is not None


def policy_run_finalization_mode(run: dict[str, Any]) -> str:
    direct = trim(run.get(FINALIZATION_MODE_FIELD))
    if direct:
        return direct
    parameters = run.get("parameters") if isinstance(run.get("parameters"), dict) else {}
    return trim(parameters.get(FINALIZATION_MODE_FIELD))


def is_explicit_legacy_finalization_mode(mode: str) -> bool:
    return mode in {FINALIZATION_MODE_LEGACY_V1, FINALIZATION_MODE_PLATFORM_OWNED_V1}


def require_finalization_operation(
    run_id: str,
    finalization_mode: str,
    operation: dict[str, str] | None,
) -> None:
    if is_explicit_legacy_finalization_mode(finalization_mode) and operation is not None:
        raise ProtocolError(
            400,
            "legacy_finalization_operation_not_supported",
            "invalid_request",
            "Legacy V1 finalization does not accept a V2 operationId.",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id},
        )
    if finalization_mode not in {
        FINALIZATION_MODE_LEGACY_V1,
        FINALIZATION_MODE_PLATFORM_OWNED_V1,
        FINALIZATION_MODE_PLATFORM_OWNED_V2,
    }:
        raise ProtocolError(
            409,
            "finalization_mode_unverified",
            "conflict",
            "Scenario Lab finalization mode is missing or unsupported.",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id},
        )
    if operation is None and not is_explicit_legacy_finalization_mode(finalization_mode):
        raise ProtocolError(
            400,
            "finalization_operation_required",
            "invalid_request",
            "PLATFORM_OWNED_V2 finalization requires an operationId before publication",
            operation="policy-wind-tunnel-lifecycle",
        )
    if (
        finalization_mode == FINALIZATION_MODE_PLATFORM_OWNED_V2
        and operation is not None
        and operation["operationId"] != expected_finalization_operation_id(run_id)
    ):
        raise ProtocolError(
            400,
            "invalid_finalization_operation_id",
            "invalid_request",
            "PLATFORM_OWNED_V2 finalization operationId did not match this run and provider revision",
            operation="policy-wind-tunnel-lifecycle",
        )


def canonical_finalization_bytes(payload: dict[str, Any]) -> bytes:
    canonical_input = dict(payload)
    canonical_input["action"] = "FINALIZE"
    try:
        return json.dumps(
            canonical_input,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ProtocolError(
            400,
            "invalid_finalization_input",
            "invalid_request",
            "Scenario Lab finalization input must be valid JSON",
            operation="policy-wind-tunnel-lifecycle",
        ) from exc


def expected_finalization_operation_id(run_id: str) -> str:
    digest = hashlib.sha256((run_id + WIND_TUNNEL_PROVIDER_REVISION).encode("utf-8")).hexdigest()
    return "scenario-lab-finalize:" + digest


def expected_finalization_input_digest(run_id: str) -> str:
    return hashlib.sha256(
        canonical_finalization_bytes(
            {
                "action": "FINALIZE",
                "operationId": expected_finalization_operation_id(run_id),
            }
        )
    ).hexdigest()


def policy_run_snapshot_with_lifecycle(
    run: dict[str, Any],
    *,
    status: str,
    phase: str,
    progress: float,
    bundle_ready: bool,
) -> dict[str, Any]:
    snapshot = deep_json_copy(run)
    previous_phase = trim(snapshot.get("phase")) or "UNKNOWN"
    snapshot["phaseTimings"] = transition_phase_timings(
        snapshot.get("phaseTimings", []), previous_phase, phase
    )
    snapshot["status"] = status
    snapshot["phase"] = phase
    snapshot["progress"] = progress
    snapshot["bundleReady"] = bundle_ready
    snapshot["updatedAt"] = utc_now_iso()
    if phase != "PAUSED":
        snapshot.pop("pausedFromPhase", None)
    return snapshot


def finalization_artifact_manifest(run_id: str) -> dict[str, Any]:
    base = finalization_summary_directory(run_id)
    manifest: dict[str, Any] = {}
    for artifact_type, (filename, content_type) in FINALIZATION_ARTIFACT_SPECS.items():
        artifact_path = base.joinpath(filename).resolve()
        try:
            content = artifact_path.read_bytes()
        except OSError as exc:
            raise RuntimeError(
                "Scenario Lab finalization artifact was not durably published: "
                + relativize_workspace_path(artifact_path)
            ) from exc
        manifest[artifact_type] = {
            "artifactRef": relativize_workspace_path(artifact_path),
            "contentType": content_type,
            "sha256": hashlib.sha256(content).hexdigest(),
            "byteLength": len(content),
        }
    return manifest


def valid_finalization_artifact_manifest(artifact_manifest: dict[str, Any]) -> bool:
    if set(artifact_manifest) != set(FINALIZATION_ARTIFACT_SPECS):
        return False
    for artifact_type, (_, expected_content_type) in FINALIZATION_ARTIFACT_SPECS.items():
        entry = artifact_manifest.get(artifact_type)
        if not isinstance(entry, dict):
            return False
        byte_length = entry.get("byteLength")
        if (
            set(entry) != {"artifactRef", "contentType", "sha256", "byteLength"}
            or not valid_relative_artifact_ref(entry.get("artifactRef"))
            or not 1 <= len(trim(entry.get("contentType"))) <= 128
            or trim(entry.get("contentType")) != expected_content_type
            or re.fullmatch(r"[0-9a-f]{64}", trim(entry.get("sha256"))) is None
            or isinstance(byte_length, bool)
            or not isinstance(byte_length, int)
            or byte_length < 0
            or byte_length > 9_007_199_254_740_991
        ):
            return False
    return True


def valid_relative_artifact_ref(value: Any) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= 1024 or "\\" in value:
        return False
    try:
        artifact_path = Path(value)
    except (TypeError, ValueError):
        return False
    return (
        not artifact_path.is_absolute()
        and artifact_path == Path(os.path.normpath(value))
        and ".." not in artifact_path.parts
    )


def require_matching_artifact_manifest(
    receipt: dict[str, Any], expected_manifest: dict[str, Any]
) -> None:
    if canonical_json_digest(receipt.get("artifactManifest")) != canonical_json_digest(
        expected_manifest
    ):
        raise RuntimeError("Scenario Lab finalization receipt artifact manifest is contradictory")


def require_published_finalization_artifacts(artifact_manifest: dict[str, Any]) -> None:
    root = workspace_root()
    for entry in artifact_manifest.values():
        artifact_ref = trim(entry.get("artifactRef")) if isinstance(entry, dict) else ""
        artifact_path = root.joinpath(artifact_ref).resolve()
        if root not in artifact_path.parents or not artifact_path.is_file():
            raise RuntimeError(
                f"Scenario Lab finalization artifact was not durably published: {artifact_ref}"
            )
        content = artifact_path.read_bytes()
        if (
            len(content) != entry.get("byteLength")
            or hashlib.sha256(content).hexdigest() != trim(entry.get("sha256"))
        ):
            raise RuntimeError(
                f"Scenario Lab finalization artifact integrity did not match its receipt: {artifact_ref}"
            )


def require_finalized_artifact_receipt(run_id: str) -> dict[str, Any]:
    receipt = load_finalization_receipt(run_id)
    if not receipt or receipt.get("state") != FINALIZATION_RECEIPT_PUBLISHED:
        raise RuntimeError("Scenario Lab finalized artifacts are not backed by a published receipt")
    require_published_finalization_artifacts(receipt["artifactManifest"])
    return receipt


def persist_terminal_policy_run(
    run_id: str, terminal_run: dict[str, Any], receipt: dict[str, Any]
) -> None:
    if not is_terminal_phase(terminal_run.get("status")):
        raise RuntimeError("Scenario Lab terminal run persistence requires terminal state")
    if receipt:
        operation_id = trim(receipt.get("operationId"))
        operation = {
            "operationId": operation_id,
            "inputDigest": trim(receipt.get("inputDigest")),
        }
        terminal_receipt = finalization_receipt(
            run_id,
            trim(receipt.get("finalizationMode")),
            operation,
            trim(receipt.get("inputDigest")),
            FINALIZATION_RECEIPT_PUBLISHED,
            terminal_run,
            receipt["artifactManifest"],
        )
        write_finalization_receipt(run_id, terminal_receipt)
    store_policy_run_snapshot(run_id, terminal_run)


def preserve_terminal_finalization_receipt(run_id: str) -> None:
    receipt = load_finalization_receipt(run_id)
    if not receipt:
        return
    current_run = deep_json_copy(_POLICY_WIND_TUNNEL_RUNS.get(run_id, {}))
    if not current_run or not is_terminal_phase(current_run.get("status")):
        return
    persist_terminal_policy_run(run_id, current_run, receipt)


def read_finalized_summary(artifact_manifest: dict[str, Any]) -> dict[str, Any]:
    entry = artifact_manifest.get("summary")
    if not isinstance(entry, dict):
        raise RuntimeError("Scenario Lab finalized summary receipt is missing")
    root = workspace_root().resolve()
    summary_path = root.joinpath(trim(entry.get("artifactRef"))).resolve()
    if root not in summary_path.parents:
        raise RuntimeError("Scenario Lab finalized summary escapes the artifact root")
    try:
        summary = strict_json_object(
            summary_path.read_bytes(),
            "Scenario Lab finalized summary could not be read",
        )
    except (OSError, RuntimeError) as exc:
        raise RuntimeError("Scenario Lab finalized summary could not be read") from exc
    return summary


def attach_completion_notification_payload(
    receipt: dict[str, Any], summary: dict[str, Any] | None = None
) -> None:
    snapshot = receipt.get("runSnapshot")
    if (
        receipt.get("state") != FINALIZATION_RECEIPT_PUBLISHED
        or not isinstance(snapshot, dict)
        or trim(snapshot.get("status")) != "COMPLETED"
    ):
        return
    artifact_manifest = receipt.get("artifactManifest")
    if not isinstance(artifact_manifest, dict):
        raise RuntimeError("Scenario Lab finalization receipt artifact manifest is invalid")
    finalized_summary = summary if isinstance(summary, dict) else read_finalized_summary(artifact_manifest)
    completed_at = snapshot.get("completedAtEpochMs")
    if not valid_ijson_positive_int(completed_at):
        raise RuntimeError("Scenario Lab completion timestamp is invalid")
    run_id = trim(receipt.get("runId"))
    metadata = snapshot.get("metadata") if isinstance(snapshot.get("metadata"), dict) else {}
    provider_run = metadata.get("providerRun") if isinstance(metadata.get("providerRun"), dict) else {}
    parameters = dict(provider_run.get("parameters", {}) or {})
    summary_entry = artifact_manifest.get("summary")
    if not isinstance(summary_entry, dict):
        raise RuntimeError("Scenario Lab finalization summary receipt is invalid")
    receipt["completionNotificationPayload"] = {
        "runId": run_id,
        "status": "COMPLETED",
        "source": "banking-python-remote",
        "summaryPath": trim(snapshot.get("summaryPath"))
        or trim(summary_entry.get("artifactRef")),
        "bundlePath": trim(snapshot.get("bundlePath"))
        or f"/api/experiment-runs/{quote(run_id)}/scenario-lab/bundle",
        "operationId": trim(receipt.get("operationId")),
        "inputDigest": trim(receipt.get("inputDigest")),
        "providerRevision": trim(receipt.get("providerRevision")),
        "completedAtEpochMs": completed_at,
        "metadata": {
            "source": "banking-python-remote",
            "domainId": DOMAIN_ID,
            "cohortId": trim(parameters.get("cohortId")) or trim(parameters.get("cohort")),
            "scenarioLab": {
                "phase": "COMPLETED",
                "progress": 1.0,
                "bundleReady": True,
                "summaryMode": trim(finalized_summary.get("summaryMode")),
                "parameters": parameters,
                "agenticSimulation": dict(finalized_summary.get("agenticSimulation", {}) or {}),
            },
        },
    }


def valid_completion_notification_payload(
    payload: Any,
    run_id: str,
    operation_id: str,
    input_digest: str,
    provider_revision: str,
    run_snapshot: dict[str, Any],
) -> bool:
    if not isinstance(payload, dict):
        return False
    completed_at = payload.get("completedAtEpochMs")
    snapshot_completed_at = run_snapshot.get("completedAtEpochMs")
    return (
        set(payload) == FINALIZATION_NOTIFICATION_FIELDS
        and payload.get("runId") == run_id
        and payload.get("status") == "COMPLETED"
        and isinstance(payload.get("source"), str)
        and bool(payload.get("source"))
        and payload.get("operationId") == operation_id
        and payload.get("inputDigest") == input_digest
        and payload.get("providerRevision") == provider_revision
        and isinstance(payload.get("summaryPath"), str)
        and bool(trim(payload.get("summaryPath")))
        and isinstance(payload.get("bundlePath"), str)
        and bool(trim(payload.get("bundlePath")))
        and isinstance(completed_at, int)
        and not isinstance(completed_at, bool)
        and 1 <= completed_at <= 9_007_199_254_740_991
        and completed_at == snapshot_completed_at
        and isinstance(payload.get("metadata"), dict)
        and bool(payload.get("metadata"))
    )


def confirm_finalization_notification(receipt: dict[str, Any]) -> None:
    state = trim(receipt.get("completionNotificationState"))
    if (
        receipt.get("state") != FINALIZATION_RECEIPT_PUBLISHED
        or state
        in {
            FINALIZATION_NOTIFICATION_CONFIRMED,
            FINALIZATION_NOTIFICATION_NOT_REQUIRED,
        }
    ):
        return
    payload = receipt.get("completionNotificationPayload")
    if not isinstance(payload, dict) or not notify_simulation_run_completed(payload):
        return
    updated = deep_json_copy(receipt)
    updated["completionNotificationState"] = FINALIZATION_NOTIFICATION_CONFIRMED
    updated["updatedAt"] = utc_now_iso()
    write_finalization_receipt(trim(receipt.get("runId")), updated)


def restore_policy_run_from_finalization_receipt(
    receipt: dict[str, Any], *, replace_existing: bool
) -> None:
    run_id = trim(receipt.get("runId"))
    snapshot = receipt.get("runSnapshot")
    if not isinstance(snapshot, dict):
        raise RuntimeError("Scenario Lab finalization receipt run snapshot is invalid")
    metadata = snapshot.get("metadata")
    provider_run = metadata.get("providerRun") if isinstance(metadata, dict) else None
    if (
        not isinstance(provider_run, dict)
        or trim(provider_run.get("runId")) != run_id
        or trim(provider_run.get("status")).upper() != trim(snapshot.get("status"))
    ):
        raise RuntimeError("Scenario Lab finalization receipt provider run is invalid")
    with _POLICY_WIND_TUNNEL_LOCK:
        current = _POLICY_WIND_TUNNEL_RUNS.get(run_id)
        if isinstance(current, dict) and is_terminal_phase(current.get("status")):
            return
        if replace_existing or current is None:
            store_policy_run_snapshot(run_id, provider_run)


def deep_json_copy(value: Any) -> Any:
    return json.loads(json.dumps(value, ensure_ascii=False, allow_nan=False))


def canonical_json_digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()


def atomic_write_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, pending_name = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".pending", dir=path.parent
    )
    pending = Path(pending_name)
    try:
        with os.fdopen(descriptor, "wb") as pending_file:
            pending_file.write(content)
            pending_file.flush()
            os.fsync(pending_file.fileno())
        os.replace(pending, path)
        directory_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        pending.unlink(missing_ok=True)


def finalization_operation(payload: dict[str, Any]) -> dict[str, str] | None:
    operation_id = trim(payload.get("operationId"))
    if not operation_id:
        return None
    if re.fullmatch(r"scenario-lab-finalize:[0-9a-f]{64}", operation_id) is None:
        raise ProtocolError(
            400,
            "invalid_finalization_operation_id",
            "invalid_request",
            "Scenario Lab finalization operationId must use scenario-lab-finalize:<sha256>",
            operation="policy-wind-tunnel-lifecycle",
        )
    canonical_input = dict(payload)
    canonical_input["action"] = "FINALIZE"
    try:
        canonical_bytes = json.dumps(
            canonical_input,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ProtocolError(
            400,
            "invalid_finalization_input",
            "invalid_request",
            "Scenario Lab finalization input must be valid JSON",
            operation="policy-wind-tunnel-lifecycle",
        ) from exc
    return {
        "operationId": operation_id,
        "inputDigest": hashlib.sha256(canonical_bytes).hexdigest(),
    }


def validate_finalization_operation(
    run: dict[str, Any],
    requested: dict[str, str] | None,
    run_id: str,
) -> None:
    existing = run.get(FINALIZATION_OPERATION_FIELD)
    if not isinstance(existing, dict):
        return
    if (
        requested is None
        or trim(existing.get("operationId")) != requested["operationId"]
        or trim(existing.get("inputDigest")) != requested["inputDigest"]
    ):
        raise ProtocolError(
            409,
            "finalization_operation_conflict",
            "conflict",
            "Scenario Lab finalization operationId was reused with contradictory input",
            operation="policy-wind-tunnel-lifecycle",
            details={"runId": run_id},
        )


def notify_simulation_run_completed(payload: dict[str, Any]) -> bool:
    run_id = trim(payload.get("runId"))
    operation_id = trim(payload.get("operationId"))
    try:
        http_json(
            "PATCH",
            experiment_service_url() + "/api/simulation-runs/" + quote(run_id, safe=""),
            payload,
            headers={"Idempotency-Key": operation_id} if operation_id else None,
            timeout=10.0,
        )
        return True
    except Exception as exc:  # pragma: no cover - best-effort ledger closure
        append_policy_log(
            run_id,
            "Completed run, but E24 simulation-run ledger closure was not confirmed: "
            + (str(exc) or exc.__class__.__name__),
        )
        return False


def maybe_advance_clock_driven_lifecycle(run_id: str) -> None:
    run = policy_run_record(run_id)
    if run.get("status") != "RUNNING" or run.get("phase") != "CLOCK_RUNNING":
        return
    if not is_clock_driven_parameters(run.get("parameters", {})):
        return
    replay = fetch_simulator_replay_context()
    execution_context = replay.get("executionContext") if isinstance(replay.get("executionContext"), dict) else {}
    active_run_id = trim(execution_context.get("runId"))
    if active_run_id != run_id:
        append_policy_log_once(
            run_id,
            "Clock horizon check skipped because simulator replay ownership could not be verified for this run."
            if not active_run_id
            else f"Clock horizon check skipped because {active_run_id} is now the active replay owner.",
        )
        return
    simulation_epoch_ms = fetch_simulation_time_epoch_ms()
    if simulation_epoch_ms is None:
        return
    parameters = dict(run.get("parameters", {}) or {})
    horizon_days = max(1, int_or_default(parameters.get("simulationHorizonDays"), 30))
    elapsed_ms = max(0, int(simulation_epoch_ms) - simulation_start_epoch_ms(parameters))
    completion = min(1.0, elapsed_ms / max(1, horizon_days * MILLIS_PER_SIMULATION_DAY))
    progress = min(0.90, 0.55 + (0.35 * completion))
    if completion >= 1.0:
        with _POLICY_WIND_TUNNEL_LOCK:
            run = policy_run_record(run_id)
            if not lifecycle_transition_allows(run, TRANSITION_HORIZON_READY):
                return
            if not apply_playback_control_for_run(
                run_id,
                paused=True,
                playback_rate=0.0,
                action_label="Simulation horizon reached",
            ):
                append_policy_log_once(
                    run_id,
                    "Simulation horizon reached, but platform-owned replay pause was not confirmed. Retrying before publishing finalization readiness.",
                )
                return
            update_policy_run(
                run_id,
                status=STATUS_READY_TO_FINALIZE,
                phase=PHASE_READY_TO_FINALIZE,
                progress=0.91,
                bundle_ready=True,
            )
            append_policy_log(
                run_id,
                "Clock-driven horizon reached after "
                + format_decimal(elapsed_ms / MILLIS_PER_SIMULATION_DAY)
                + " simulated day(s). Playback is paused; awaiting platform drain and generation seal before report finalization.",
            )
    elif progress - float(run.get("progress", 0.0)) >= 0.01:
        update_policy_run(run_id, status="RUNNING", phase="CLOCK_RUNNING", progress=progress, bundle_ready=True)


def assess_clock_driven_rate_change_proof(
    run_id: str,
    preset_id: str,
    parameters: dict[str, Any],
    *,
    finalized: bool,
    correlation_audit: dict[str, Any] | None = None,
) -> tuple[bool, bool, str, dict[str, Any]]:
    stored_plan_digest = trim(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD))
    if not stored_plan_digest:
        if preset_id == RATE_CHANGE_SUPPORTED_PRESET_ID:
            return (
                True,
                False,
                "The rate-change run is missing its launch-pinned intervention plan digest.",
                {
                    "schemaVersion": RATE_CHANGE_PROOF_SCHEMA_VERSION,
                    "status": "UNVERIFIED",
                    "chainIntegrityVerified": False,
                    "realizedReactionVerified": False,
                    "generatedOpportunities": 0,
                    "correlatedDecisions": 0,
                    "realizedCustomerReactions": 0,
                    "technicalValidationDecisions": 0,
                },
            )
        return (
            False,
            True,
            "No launch-pinned rate-change plan applies to this run.",
            {
                "schemaVersion": RATE_CHANGE_PROOF_SCHEMA_VERSION,
                "status": "NOT_APPLICABLE",
                "chainIntegrityVerified": True,
                "realizedReactionVerified": False,
            },
        )
    if not finalized:
        return (
            True,
            True,
            "The launch-pinned day-10 intervention chain is still capturing evidence.",
            {
                "schemaVersion": RATE_CHANGE_PROOF_SCHEMA_VERSION,
                "status": "CAPTURING",
                "chainIntegrityVerified": False,
                "realizedReactionVerified": False,
                "planDigest": stored_plan_digest,
            },
        )
    try:
        plan = compile_rate_change_plan(run_id, preset_id, parameters)
        if (
            plan is None
            or plan.plan_digest != stored_plan_digest
            or trim(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD))
            != RATE_CHANGE_PLAN_SCHEMA_VERSION
        ):
            raise ValueError("Run metadata contradicts the launch-pinned rate-change plan")
        persisted_plan = require_rate_change_plan_bytes(
            clock_driven_intervention_plan_path(run_id).read_bytes()
        )
        if persisted_plan != plan:
            raise ValueError("Persisted rate-change plan is contradictory")
        installation = require_rate_change_installation_manifest_bytes(
            clock_driven_intervention_installation_path(run_id).read_bytes(),
            plan,
        )
        cohort_snapshot = load_durable_cohort_artifact_snapshot(run_id, parameters)
        require_installation_matches_pinned_cohort(
            installation,
            parameters,
            cohort_snapshot["content"],
        )
        audit = (
            correlation_audit
            if correlation_audit is not None
            else fetch_decision_opportunity_correlation_audit(
                run_id,
                RATE_CHANGE_TRIGGER_TYPE,
            )
        )
        evidence = verify_clock_driven_rate_change_proof(
            plan,
            installation,
            audit,
            normalize_agent_decision_mode(parameters.get("agentDecisionMode")),
        )
        reason = (
            "Every eligible customer has a fenced Real LLM decision correlated to the committed day-10 rate change."
            if evidence["reactionEvidenceType"] == "REMOTE_AUTHORIZED_REAL_LLM"
            else "Every eligible customer has a fenced no-token decision for technical validation; no realized customer reaction is claimed."
        )
        return True, True, reason, evidence
    except OpportunityCorrelationAuditUnavailableError:
        raise
    except Exception as invalid_proof:
        LOGGER.warning(
            "Clock-driven rate-change proof is incomplete for run %s: %s",
            run_id,
            invalid_proof,
        )
        return (
            True,
            False,
            "The launch-pinned rate-change mutation, opportunity, and decision chain was not fully verified.",
            {
                "schemaVersion": RATE_CHANGE_PROOF_SCHEMA_VERSION,
                "status": "UNVERIFIED",
                "chainIntegrityVerified": False,
                "realizedReactionVerified": False,
                "planDigest": stored_plan_digest,
                "generatedOpportunities": 0,
                "correlatedDecisions": 0,
                "realizedCustomerReactions": 0,
                "technicalValidationDecisions": 0,
            },
        )


def verify_clock_driven_rate_change_proof(
    plan: RateChangePlanSnapshot,
    installation: Any,
    audit: dict[str, Any],
    decision_mode: str,
) -> dict[str, Any]:
    if set(audit) != OPPORTUNITY_CORRELATION_AUDIT_FIELDS:
        raise ValueError("Opportunity correlation audit shape is invalid")
    exact_proof_text(audit.get("asOf"), "asOf")
    run_id = exact_proof_text(audit.get("simulationRunId"), "simulationRunId")
    if (
        exact_proof_text(audit.get("schemaVersion"), "schemaVersion")
        != OPPORTUNITY_CORRELATION_AUDIT_SCHEMA_VERSION
        or run_id != plan.plan["simulationRunId"]
        or exact_proof_text(audit.get("triggerType"), "triggerType")
        != RATE_CHANGE_TRIGGER_TYPE
    ):
        raise ValueError("Opportunity correlation audit ownership is invalid")
    opportunities = exact_proof_object_list(audit.get("opportunities"), "opportunities")
    if exact_proof_integer(audit.get("opportunityCount"), "opportunityCount") != len(
        opportunities
    ):
        raise ValueError("Opportunity correlation audit count is inconsistent")

    expected_customers = {
        customer.opportunity_agent_id: customer for customer in installation.customers
    }
    if len(expected_customers) != len(installation.customers):
        raise ValueError("Duplicate installed customer identity")
    observed_customers: set[str] = set()
    opportunity_ids: set[str] = set()
    terminal_opportunities = 0
    correlated_decisions = 0
    operation_id = exact_proof_text(plan.plan.get("mutationOperationId"), "mutationOperationId")
    scheduled_for_epoch_ms = exact_proof_integer(
        plan.plan.get("scheduledForEpochMs"), "scheduledForEpochMs"
    )
    for opportunity in opportunities:
        if set(opportunity) != OPPORTUNITY_CORRELATION_ROW_FIELDS:
            raise ValueError("Opportunity correlation row shape is invalid")
        opportunity_id = exact_proof_uuid(opportunity.get("opportunityId"), "opportunityId")
        opportunity_agent_id = exact_proof_text(opportunity.get("agentId"), "agentId")
        installed = expected_customers.get(opportunity_agent_id)
        if (
            installed is None
            or installed.eligible_account_count <= 0
            or opportunity_agent_id in observed_customers
            or opportunity_id in opportunity_ids
        ):
            raise ValueError(
                "Rate-change opportunity ownership is missing, duplicated, or ineligible"
            )
        observed_customers.add(opportunity_agent_id)
        opportunity_ids.add(opportunity_id)
        trigger_ref = f"{operation_id}|customer:{installed.cohort_agent_id}"
        opportunity_scheduled_for_epoch_ms = exact_proof_integer(
            opportunity.get("scheduledForEpochMs"), "scheduledForEpochMs"
        )
        if (
            exact_proof_text(opportunity.get("domainId"), "domainId") != DOMAIN_ID
            or exact_proof_text(opportunity.get("triggerType"), "triggerType")
            != RATE_CHANGE_TRIGGER_TYPE
            or exact_proof_text(opportunity.get("triggerId"), "triggerId")
            != f"{RATE_CHANGE_TRIGGER_TYPE}:{trigger_ref}"
            or exact_proof_text(opportunity.get("triggerRef"), "triggerRef") != trigger_ref
            or opportunity_scheduled_for_epoch_ms < scheduled_for_epoch_ms
            or exact_proof_text(opportunity.get("priority"), "priority") != "NORMAL"
            or exact_proof_text(opportunity.get("reason"), "reason")
            != "Rate or policy changed"
            or exact_proof_text_list(opportunity.get("evidenceRefs"), "evidenceRefs")
            != [trigger_ref]
            or not re.fullmatch(
                r"[0-9a-f]{64}",
                exact_proof_text(opportunity.get("triggerDigest"), "triggerDigest"),
            )
        ):
            raise ValueError("Rate-change trigger correlation is contradictory")
        verify_rate_change_trigger_payload(
            exact_proof_object(opportunity.get("payload"), "payload"),
            plan,
            installed.cohort_agent_id,
            installed.eligible_account_count,
            trigger_ref,
            opportunity_scheduled_for_epoch_ms,
        )
        attempt_count = exact_proof_integer(opportunity.get("attemptCount"), "attemptCount")
        status = exact_proof_text(opportunity.get("status"), "status")
        if status == "SUCCEEDED":
            terminal_opportunities += 1
        if (
            status != "SUCCEEDED"
            or attempt_count < 1
            or exact_proof_text(opportunity.get("decisionMode"), "decisionMode")
            != decision_mode
            or opportunity.get("decisionContractValid") is not True
            or exact_proof_integer(opportunity.get("decisionAttempt"), "decisionAttempt")
            != attempt_count
            or not exact_proof_uuid(opportunity.get("decisionEventId"), "decisionEventId")
        ):
            raise ValueError(
                "Rate-change opportunity does not have fenced valid decision evidence"
            )
        correlated_decisions += 1

    expected_eligible_customers = sum(
        1 for customer in installation.customers if customer.eligible_account_count > 0
    )
    expected_affected_accounts = sum(
        customer.eligible_account_count for customer in installation.customers
    )
    if (
        expected_eligible_customers <= 0
        or expected_affected_accounts <= 0
        or not opportunities
        or terminal_opportunities <= 0
        or correlated_decisions <= 0
        or len(opportunities) != expected_eligible_customers
        or len(observed_customers) != expected_eligible_customers
    ):
        raise ValueError(
            "Rate-change opportunity coverage does not match installed eligibility"
        )
    real_llm = decision_mode == AGENT_DECISION_MODE_REAL_LLM
    return {
        "schemaVersion": RATE_CHANGE_PROOF_SCHEMA_VERSION,
        "status": "VERIFIED" if real_llm else "TECHNICAL_VALIDATION_ONLY",
        "chainIntegrityVerified": True,
        "realizedReactionVerified": real_llm,
        "planDigest": plan.plan_digest,
        "installationManifestDigest": installation.manifest_digest,
        "interventionId": plan.plan["interventionId"],
        "mutationOperationId": operation_id,
        "scheduledForEpochMs": scheduled_for_epoch_ms,
        "installedCustomers": installation.installed_agent_count,
        "eligibleCustomers": installation.eligible_customer_count,
        "generatedOpportunities": len(opportunities),
        "terminalOpportunities": terminal_opportunities,
        "correlatedDecisions": correlated_decisions,
        "reactionEvidenceType": (
            "REMOTE_AUTHORIZED_REAL_LLM"
            if real_llm
            else "TECHNICAL_NO_TOKEN_VALIDATION"
        ),
        "realizedCustomerReactions": correlated_decisions if real_llm else 0,
        "technicalValidationDecisions": 0 if real_llm else correlated_decisions,
    }


def verify_rate_change_trigger_payload(
    payload: dict[str, Any],
    plan: RateChangePlanSnapshot,
    cohort_agent_id: str,
    affected_account_count: int,
    trigger_ref: str,
    opportunity_scheduled_for_epoch_ms: int,
) -> None:
    expected = {
        "policyVersion": "banking.customer-decision-trigger-policy.v1",
        "numericEncoding": "canonical_decimal_string.v1",
        "normalizedEventType": RATE_CHANGE_TRIGGER_TYPE,
        "sourceEventType": "remote_domain_rate_changed",
        "eventType": "remote_domain_rate_changed",
        "businessEventId": trigger_ref,
        "simulationRunId": plan.plan["simulationRunId"],
        "customerId": cohort_agent_id,
        "interventionId": plan.plan["interventionId"],
        "operationId": plan.plan["mutationOperationId"],
        "planDigest": plan.plan_digest,
        "occurrenceKey": f"banking|clock-driven-rate-change|{plan.plan['mutationOperationId']}",
        "workId": plan.plan["workId"],
        "productCode": plan.plan["productCode"],
        "scheduledForEpochMs": str(plan.plan["scheduledForEpochMs"]),
        "effectiveAtEpochMs": str(plan.plan["effectiveAtEpochMs"]),
        "appliedAtEpochMs": str(opportunity_scheduled_for_epoch_ms),
        "affectedAccountCount": str(affected_account_count),
        "oldRate": plan.plan["expectedPreviousRate"],
        "newRate": plan.plan["newRate"],
        "outcome": "SUCCESS",
    }
    if set(payload) != RATE_CHANGE_TRIGGER_PAYLOAD_FIELDS:
        raise ValueError("Rate-change trigger payload shape is contradictory")
    if any(exact_proof_text(payload.get(key), key) != value for key, value in expected.items()):
        raise ValueError("Rate-change trigger payload is contradictory")


def fetch_decision_opportunity_correlation_audit(
    run_id: str,
    trigger_type: str,
) -> dict[str, Any]:
    try:
        capability = current_opportunity_correlation_capability()
    except RuntimeError as unavailable:
        raise OpportunityCorrelationAuditUnavailableError(str(unavailable)) from unavailable
    provider_revision = WIND_TUNNEL_PROVIDER_REVISION
    url = (
        f"{agent_runtime_url()}/api/agents/simulation-runs/{quote(run_id, safe='')}"
        "/decision-opportunities/correlation-audit?triggerType="
        + quote(trigger_type, safe="")
        + "&domainId="
        + quote(DOMAIN_ID, safe="")
        + "&providerRevision="
        + quote(provider_revision, safe="")
    )
    request = Request(
        url,
        headers={
            "Accept": "application/json",
            OPPORTUNITY_CORRELATION_CAPABILITY_HEADER: capability,
        },
        method="GET",
    )
    try:
        with _OPPORTUNITY_AUDIT_OPENER.open(request, timeout=30.0) as response:
            if response.status != 200:
                raise OpportunityCorrelationAuditUnavailableError(
                    "Opportunity correlation audit did not return 200"
                )
            media_type, _, parameters = (response.headers.get("Content-Type") or "").partition(";")
            if media_type.strip().lower() != "application/json":
                raise OpportunityCorrelationAuditUnavailableError(
                    "Opportunity correlation audit did not return application/json"
                )
            if parameters:
                parameter = parameters.strip().lower().replace(" ", "")
                if parameter not in {"charset=utf-8", "charset=\"utf-8\""}:
                    raise OpportunityCorrelationAuditUnavailableError(
                        "Opportunity correlation audit used a non-UTF-8 charset"
                    )
            content = response.read(4 * 1024 * 1024 + 1)
            if not content or len(content) > 4 * 1024 * 1024:
                raise OpportunityCorrelationAuditUnavailableError(
                    "Opportunity correlation audit exceeded its response bound"
                )
            try:
                return strict_json_object(content, "Opportunity correlation audit is invalid JSON")
            except RuntimeError as invalid_json:
                raise OpportunityCorrelationAuditUnavailableError(
                    "Opportunity correlation audit is not strict UTF-8 JSON"
                ) from invalid_json
    except HTTPError as exc:
        raise OpportunityCorrelationAuditUnavailableError(
            f"Opportunity correlation audit failed with status {exc.code}"
        ) from exc
    except (URLError, TimeoutError, OSError) as exc:
        raise OpportunityCorrelationAuditUnavailableError(
            "Opportunity correlation audit is unavailable"
        ) from exc


class OpportunityCorrelationAuditUnavailableError(RuntimeError):
    pass


def prefetch_rate_change_correlation_audit(
    run_id: str,
    parameters: dict[str, Any],
) -> dict[str, Any] | None:
    if not trim(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD)):
        return None
    return fetch_decision_opportunity_correlation_audit(
        run_id,
        RATE_CHANGE_TRIGGER_TYPE,
    )


def require_installation_matches_pinned_cohort(
    installation: Any,
    parameters: dict[str, Any],
    cohort_artifact_content: bytes,
) -> None:
    cohort_artifact_digest = trim(parameters.get("cohortArtifactDigest"))
    cohort_identity_digest = trim(parameters.get("cohortAgentIdentitySetDigest"))
    cohort_target_size = exact_proof_integer(
        parameters.get("cohortTargetSize"),
        "cohortTargetSize",
    )
    pinned_eligibility = rate_change_eligible_account_counts_from_cohort_artifact(
        cohort_artifact_content
    )
    installed_eligibility = {
        customer.cohort_agent_id: customer.eligible_account_count
        for customer in installation.customers
    }
    if len(installed_eligibility) != len(installation.customers):
        raise ValueError("Rate-change installation contains duplicate cohort identities")
    if (
        re.fullmatch(r"[0-9a-f]{64}", cohort_artifact_digest) is None
        or re.fullmatch(r"[0-9a-f]{64}", cohort_identity_digest) is None
        or installation.cohort_artifact_digest != cohort_artifact_digest
        or installation.agent_identity_set_digest != cohort_identity_digest
        or installation.installed_agent_count != cohort_target_size
        or installed_eligibility != pinned_eligibility
        or rate_change_agent_identity_set_digest(list(pinned_eligibility))
        != cohort_identity_digest
    ):
        raise ValueError(
            "Rate-change installation manifest does not match the launch-pinned cohort"
        )


def exact_proof_text(value: Any, field: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > 512
    ):
        raise ValueError(f"{field} must be exact bounded text")
    return value


def exact_proof_integer(value: Any, field: str) -> int:
    if type(value) is not int or value < 0 or value > 9_007_199_254_740_991:
        raise ValueError(f"{field} must be an exact I-JSON integer")
    return value


def exact_proof_uuid(value: Any, field: str) -> str:
    text_value = exact_proof_text(value, field)
    try:
        parsed = uuid.UUID(text_value)
    except ValueError as invalid:
        raise ValueError(f"{field} must be a canonical UUID") from invalid
    if str(parsed) != text_value:
        raise ValueError(f"{field} must be a canonical UUID")
    return text_value


def exact_proof_object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise ValueError(f"{field} must be an object")
    return dict(value)


def exact_proof_object_list(value: Any, field: str) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise ValueError(f"{field} must be an array")
    return [exact_proof_object(item, f"{field}[]") for item in value]


def exact_proof_text_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list):
        raise ValueError(f"{field} must be an array")
    return [exact_proof_text(item, f"{field}[]") for item in value]


def clock_driven_policy_wind_tunnel_bundle(
    run_id: str,
    *,
    finalized: bool | None = None,
    correlation_audit: dict[str, Any] | None = None,
) -> dict[str, Any]:
    run = policy_run_record(run_id)
    parameters = dict(run.get("parameters", {}) or {})
    status = trim(run.get("status")).upper() or "RUNNING"
    phase = trim(run.get("phase")).upper() or status
    is_finalized = finalized if finalized is not None else status in {"COMPLETED", "CANCELLED", "FAILED"}
    report_status = "COMPLETED" if is_finalized and status == "RUNNING" and phase == "FINALIZING_REPORT" else status
    report_phase = "COMPLETED" if is_finalized and status == "RUNNING" and phase == "FINALIZING_REPORT" else phase
    summary_mode = "CLOCK_DRIVEN_HYBRID_FINALIZED" if is_finalized else "CLOCK_DRIVEN_HYBRID_LIVE"
    preset_id = trim(parameters.get("presetId")) or run["presetId"]
    cohort_id = selected_cohort_id(parameters)
    cohort_size = int_or_default(run.get("cohortSize"), int_or_default(parameters.get("cohortTargetSize"), 0))
    if cohort_size <= 0 and cohort_id:
        cohort_size = count_generated_cohort_customers(cohort_id)
    run_record = fetch_simulation_run_record(run_id)
    ledger = fetch_simulation_run_ledger(run_id)
    ledger_counts = simulation_ledger_counts(run_record, ledger)
    simulation_window = clock_driven_simulation_window(run_id, parameters)
    agent_audit = (
        finalized_runtime_decision_audit(run_id, parameters)
        if is_finalized
        else load_runtime_decision_audit(run_id, include_decisions=False)
    )
    opportunity_reconciliation = (
        fetch_decision_opportunity_reconciliation(run_id)
        if is_finalized
        else unavailable_opportunity_reconciliation(run_id)
    )
    partial_opportunity_evidence, opportunity_agent_audit_verified, opportunity_proof_reason = assess_opportunity_evidence(
        is_finalized,
        agent_audit,
        run_id,
        opportunity_reconciliation,
    )
    (
        rate_change_proof_required,
        rate_change_proof_verified,
        rate_change_proof_reason,
        rate_change_proof,
    ) = assess_clock_driven_rate_change_proof(
        run_id,
        preset_id,
        parameters,
        finalized=is_finalized,
        correlation_audit=correlation_audit,
    )
    technical_validation_only = (
        rate_change_proof_required
        and rate_change_proof_verified
        and rate_change_proof.get("realizedReactionVerified") is not True
    )
    partial_rate_change_evidence = rate_change_proof_required and not rate_change_proof_verified
    partial_opportunity_evidence = partial_opportunity_evidence or partial_rate_change_evidence
    if partial_rate_change_evidence:
        opportunity_proof_reason = rate_change_proof_reason
    timeline = build_clock_driven_timeline(
        run,
        finalized=is_finalized,
        ledger=ledger,
        simulation_window=simulation_window,
    )
    side_effect_ledger = build_clock_driven_side_effect_ledger(
        run_id,
        report_status,
        report_phase,
        finalized=is_finalized,
        ledger=ledger,
        ledger_counts=ledger_counts,
    )
    state_mutations = build_clock_driven_state_mutations(run_id, cohort_size, finalized=is_finalized, ledger=ledger)
    rejected_work_count = count_rejected_checkpoint_work(run_id, state_mutations)
    evidence_signal_count = (
        ledger_counts["eventCount"]
        + ledger_counts["checkpointCount"]
        + len(timeline["events"])
        + len(side_effect_ledger["entries"])
        + len(state_mutations)
    )
    domain_scores = build_clock_driven_domain_scores(
        cohort_size,
        evidence_signal_count,
        timeline_event_count=len(timeline["events"]),
        state_change_count=len(state_mutations),
        rejected_work_count=rejected_work_count,
        finalized=is_finalized,
    )
    primary_score = domain_scores[0] if domain_scores else {}
    recommendation = trim(primary_score.get("recommendation")) or ("WATCH" if is_finalized else "WATCH")
    effective_recommendation = (
        "WATCH"
        if partial_opportunity_evidence or technical_validation_only
        else recommendation
    )
    artifact_paths = clock_driven_artifact_paths(run_id)
    artifact_downloads = clock_driven_artifact_downloads(run_id)
    decision_readiness = {
        "status": (
            "PARTIAL_EVIDENCE"
            if partial_opportunity_evidence
            else "TECHNICAL_VALIDATION_ONLY"
            if technical_validation_only
            else ("READY_FOR_HUMAN_REVIEW" if is_finalized else "CAPTURING_EVIDENCE")
        ),
        "recommendation": effective_recommendation,
        "phase": report_phase,
        "reason": (
            opportunity_proof_reason
            if partial_opportunity_evidence
            else "The no-token run verifies the intervention, opportunity, and decision machinery, but it contains no realized customer reaction evidence."
            if technical_validation_only
            else "The Python clock-driven run has been frozen into replay-linked evidence for human review."
            if is_finalized
            else "The Python clock-driven run is still collecting evidence under simulator clock control."
        ),
        "scorecard": {
            "scorecardId": primary_score.get("scorecardId"),
            "version": primary_score.get("version"),
            "status": primary_score.get("status"),
        },
        "scorecards": domain_scores,
        "nextStep": "Resolve the opportunity ledger and regenerate a complete per-agent audit before review."
        if partial_opportunity_evidence
        else "Rerun with an authorized Real LLM worker before using customer reactions or launch readiness as business evidence."
        if technical_validation_only
        else "Review the E24 evidence export and domain scorecard before treating this as a launch decision."
        if is_finalized
        else "Let the run reach its horizon or finalize when evidence is sufficient.",
    }
    decision_comparison = {
        "schemaVersion": "customer_outcome_decision_comparison.v1",
        "comparisonId": preset_id + ("-clock-driven-simulation-finalized" if is_finalized else "-clock-driven-simulation-live"),
        "scenarioId": "banking-python-policy-compare",
        "title": "Clock-Driven Simulation Outcome Review"
        if is_finalized
        else "Live Clock-Driven Simulation Decision Review",
        "decisionQuestion": "What did the selected synthetic customer cohort do under clock-driven execution, and is the evidence ready for a launch decision?",
        "baseline": {
            "branchId": "baseline-current-policy",
            "status": "REFERENCE_ONLY" if is_finalized else "PENDING_FINAL_COMPARISON",
            "evidenceLinks": {"note": "Clock-driven baseline branch scoring is not produced by the Python reference provider yet."},
        },
        "proposed": {
            "branchId": "clock-driven-simulation-run",
                "status": "PARTIAL"
                if partial_opportunity_evidence
                else "TECHNICAL_VALIDATION_ONLY"
                if technical_validation_only
                else "FINALIZED"
                if is_finalized
                else status,
                "ledgerCounts": ledger_counts,
                "evidenceLinks": {
                "simulationRunEvidencePack": artifact_downloads["simulationRunEvidence"],
                "simulationTimelineJson": artifact_paths["simulationTimelineJson"],
                "sideEffectLedgerJson": artifact_paths["sideEffectLedgerJson"],
            },
        },
        "metricDeltas": [
            {
                "metricKey": "activatedSyntheticCustomers",
                "label": "Activated synthetic customers",
                "baselineValue": 0,
                "proposedValue": cohort_size,
                "deltaValue": cohort_size,
                "unit": "count",
                "evidenceRef": "simulationRunEvidencePack",
            },
            {
                "metricKey": "runtimeEvidenceSignals",
                "label": "Runtime evidence signals captured",
                "baselineValue": 0,
                "proposedValue": evidence_signal_count,
                "deltaValue": evidence_signal_count,
                "unit": "count",
                "evidenceRef": "sideEffectLedgerJson",
            },
        ],
        "replayEvidence": {
            "requiredEvidence": [
                "customerProfile",
                "simulationTimeline",
                "sideEffectLedger",
                "domainCheckpoints",
                "simulationRunEvidencePack",
            ],
            "shared": {
                "simulationTimelineJson": artifact_paths["simulationTimelineJson"],
                "sideEffectLedgerJson": artifact_paths["sideEffectLedgerJson"],
                "branchStateProjectionJson": artifact_paths["branchStateProjectionJson"],
                "comparisonReport": artifact_paths["comparisonReport"],
                "simulationRunEvidencePack": artifact_downloads["simulationRunEvidence"],
            },
        },
    }
    branch_state_projection = {
        "schemaVersion": "customer_outcome_branch_state_projection.v1",
        "branches": [
            {
                "branchId": "baseline-current-policy",
                "branchRole": "baseline",
                "agentCount": cohort_size,
                "status": "REFERENCE_ONLY" if is_finalized else "PENDING_FINAL_COMPARISON",
            },
            {
                "branchId": "clock-driven-hybrid-run",
                "branchRole": "proposed",
                "agentCount": cohort_size,
                "status": "OBSERVED" if is_finalized else status,
            },
        ],
        "mutations": state_mutations,
        "metricTraces": [
            {
                "metricKey": "activatedSyntheticCustomers",
                "supportLevel": "RUN_LEDGER_LINKED",
                "sourceMutationIds": [mutation["mutationId"] for mutation in state_mutations[:12]],
                "evidenceChain": {
                    "sourceMutationIds": [mutation["mutationId"] for mutation in state_mutations[:12]],
                    "sourceEffectIds": ["e24-evidence-spine-" + run_id],
                    "artifactRefs": {
                        "simulationTimelineJson": artifact_paths["simulationTimelineJson"],
                        "simulationRunEvidencePack": artifact_downloads["simulationRunEvidence"],
                    },
                },
            }
        ],
    }
    evaluator = {
        "status": effective_recommendation,
        "verdict": effective_recommendation,
        "breachCount": 0 if effective_recommendation == "LAUNCH" else 1,
        "failBreaches": 1 if effective_recommendation == "BLOCK" else 0,
        "domainOutcomeScore": primary_score,
        "domainOutcomeScores": domain_scores,
        "decisionGates": {
            "breachedGateCount": 0 if recommendation == "LAUNCH" else 1,
            "failGateCount": 1 if recommendation == "BLOCK" else 0,
            "gates": primary_score.get("gates", []),
        },
    }
    run_config = dict(parameters)
    run_config.update(
        {
            "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
            "lifecycleState": report_status,
            "phase": report_phase,
            "cohortSize": cohort_size,
            "cohortId": cohort_id,
            "providerLanguage": "python",
            "ledgerCounts": ledger_counts,
            "opportunityReconciliationStatus": opportunity_reconciliation.get("status"),
            "opportunityLedgerReconciled": opportunity_reconciliation.get("ledgerReconciled"),
            "opportunityRemainingBacklog": opportunity_reconciliation.get("remainingBacklog"),
            "opportunityAgentAuditVerified": opportunity_agent_audit_verified,
            "clockDrivenInterventionProofVerified": bool(
                rate_change_proof.get("realizedReactionVerified")
            ),
            "clockDrivenInterventionChainIntegrityVerified": bool(
                rate_change_proof.get("chainIntegrityVerified")
            ),
            "clockDrivenInterventionRealizedReactionVerified": bool(
                rate_change_proof.get("realizedReactionVerified")
            ),
            **simulation_window,
        }
    )
    agentic_simulation = build_agentic_simulation_summary(
        parameters,
        cohort_size,
        agent_audit,
        artifact_downloads,
        simulation_window,
        finalized=is_finalized,
        simulation_run_id=run_id,
        opportunity_reconciliation=opportunity_reconciliation,
    )
    summary = {
        "schemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        "domainId": DOMAIN_ID,
        "packId": WIND_TUNNEL_PACK_ID,
        "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
        "summaryMode": summary_mode,
        "status": report_status,
        "phase": report_phase,
        "runId": run_id,
        "scenarioId": "banking-python-policy-compare",
        "demoPreset": {"presetId": preset_id},
        "decisionReadiness": decision_readiness,
        "decisionComparison": decision_comparison,
        "simulationTimeline": timeline,
        "sideEffectLedger": side_effect_ledger,
        "branchStateProjection": branch_state_projection,
        "domainOutcomeScore": primary_score,
        "domainOutcomeScores": domain_scores,
        "runConfig": run_config,
        "policyToggle": {
            "productCode": trim(parameters.get("productCode")) or DEFAULT_PRODUCT_CODE,
            "productName": product_name_for(trim(parameters.get("productCode")) or DEFAULT_PRODUCT_CODE),
            "policyType": "HARDSHIP",
            "baselineRate": parameters.get("baselineRate"),
            "candidateRate": parameters.get("candidateRate"),
        },
        "hybridAgentAudit": agent_audit,
        "agenticSimulation": agentic_simulation,
        "opportunityReconciliation": opportunity_reconciliation,
        "clockDrivenInterventionProof": rate_change_proof,
        "headline": {
            "title": "Clock-Driven Simulation Outcome Review"
            if is_finalized
            else "Live Clock-Driven Simulation Evidence",
            "summary": "The simulation horizon completed, but customer decision opportunity evidence remains partial."
            if partial_opportunity_evidence
            else "The no-token run verified the day-10 intervention chain, but it did not observe realized customer reactions and is not launch-ready business evidence."
            if technical_validation_only
            else "The selected Python banking cohort ran under simulator-clock control and produced replay-linked evidence."
            if is_finalized
            else "The selected Python banking cohort is running under simulator-clock control.",
            "verdict": effective_recommendation,
            "simulatedSubjects": cohort_size,
            "impactedAgents": 0,
            "phase": report_phase,
        },
        "candidate": {
            "activeAgents": cohort_size,
            "impactedAgents": 0,
            "exposureValue": cohort_size,
            "exposureUnit": "count",
            "evidenceState": "PARTIAL"
            if partial_opportunity_evidence
            else "TECHNICAL_VALIDATION_ONLY"
            if technical_validation_only
            else "FINALIZED"
            if is_finalized
            else "CAPTURING",
        },
        "delta": {
            "primaryOutcomeDelta": evidence_signal_count,
            "primaryOutcomeUnit": "count",
            "counterMetricDelta": 0,
            "counterMetricUnit": "count",
        },
        "evaluator": evaluator,
        "metrics": {
            "customerCount": cohort_size,
            "activatedSyntheticCustomers": cohort_size,
            "runtimeEvidenceSignals": evidence_signal_count,
            "ledgerEventCount": ledger_counts["eventCount"],
            "ledgerCheckpointCount": ledger_counts["checkpointCount"],
            "ledgerAgentCount": ledger_counts["agentCount"],
            "sideEffectCount": len(side_effect_ledger["entries"]),
            "stateMutationCount": len(state_mutations),
        },
        "exposureSummary": {
            "scopeLabel": cohort_id or "Generated Python customer cohort",
            "cohortAgents": cohort_size,
            "eligibleAgents": cohort_size,
            "nonEligibleAgents": 0,
            "impactedAgents": 0,
            "exposureValue": cohort_size,
            "exposureUnit": "count",
        },
        "artifacts": artifact_paths,
        "artifactDownloads": artifact_downloads,
        "hybrid": {
            "available": True,
            "live": not is_finalized,
            "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
            "agentDecisionMode": normalize_agent_decision_mode(parameters.get("agentDecisionMode")),
            "ledgerCounts": ledger_counts,
            "agentAudit": agent_audit,
            "agenticSimulation": agentic_simulation,
        },
        "limitations": [
            "This Python reference provider uses public remote-domain APIs only; no Java in-process Scenario Lab SPI is used.",
            "The platform captures core-mediated activity. Provider-internal calls require domain-emitted evidence events.",
            "The Python scorecard is a reference proof and must be replaced by bank-owned calibration before partner use.",
        ] + ([
            "The simulation horizon completed before the customer decision opportunity ledger was fully reconciled; backlog and evidence gaps remain explicit in opportunityReconciliation."
            if opportunity_agent_audit_verified
            else "The run-level opportunity ledger and durable per-agent audit were not jointly verified; this report is partial and cannot support human review readiness."
        ] if partial_opportunity_evidence and not partial_rate_change_evidence else []) + ([
            "The launch-pinned day-10 rate change was not fully correlated across committed customer state, admitted opportunities, and fenced decisions; this report is capped at WATCH."
        ] if partial_rate_change_evidence else []),
    }
    if technical_validation_only:
        summary["limitations"].append(
            "No-token decisions validate execution plumbing only. They do not prove realized customer behavior and cannot support a LAUNCH recommendation."
        )
    bundle = {
        "schemaVersion": "policy_wind_tunnel_bundle.v1",
        "summarySchemaVersion": WIND_TUNNEL_SUMMARY_SCHEMA_VERSION,
        "domainId": DOMAIN_ID,
        "packId": WIND_TUNNEL_PACK_ID,
        "experimentId": "banking-python-scenario-lab",
        "runId": run_id,
        "presetId": preset_id,
        "lookupMode": "python-remote-clock-driven-run-record",
        "loadedAt": utc_now_iso(),
        "decisionReadiness": decision_readiness,
        "decisionComparison": decision_comparison,
        "simulationTimeline": timeline,
        "sideEffectLedger": side_effect_ledger,
        "branchStateProjection": branch_state_projection,
        "branches": {
            "policyToggle": summary["policyToggle"],
            "baseline": {"branchId": "baseline-current-policy", "status": decision_comparison["baseline"]["status"]},
            "candidate": {"branchId": "clock-driven-hybrid-run", "status": decision_comparison["proposed"]["status"]},
            "delta": {},
        },
        "metrics": {
            "headline": {
                "simulatedSubjects": cohort_size,
                "decisionReadiness": decision_readiness["status"],
                "phase": report_phase,
                "ledgerEventCount": ledger_counts["eventCount"],
                "ledgerCheckpointCount": ledger_counts["checkpointCount"],
            },
            "evidence": {
                "status": "FINALIZED" if is_finalized else "CAPTURING",
                "simulationRunEvidencePack": artifact_downloads["simulationRunEvidence"],
            },
        },
        "segments": {
            "exposure": summary["exposureSummary"],
            "impacted": [],
            "impactSummary": {},
        },
        "timeline": {
            "events": timeline["events"],
            "sideEffects": side_effect_ledger["entries"],
            "stateProjection": branch_state_projection,
        },
        "decisionGates": evaluator["decisionGates"],
        "agenticSimulation": agentic_simulation,
        "opportunityReconciliation": opportunity_reconciliation,
        "clockDrivenInterventionProof": rate_change_proof,
        "artifacts": {
            "paths": artifact_paths,
            "downloads": artifact_downloads,
        },
        "summary": summary,
        "domainPayload": summary,
        "experimentCardMarkdown": build_clock_driven_experiment_card(summary),
        "artifactPaths": artifact_paths,
        "artifactDownloads": artifact_downloads,
        "hybrid": {
            "available": True,
            "live": not is_finalized,
            "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
            "agentAudit": agent_audit,
            "agenticSimulation": agentic_simulation,
            "decisionReadiness": decision_readiness,
        },
    }
    return bundle


def projection_envelope(payload: dict[str, Any], state: dict[str, Any]) -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "requestId": payload["requestId"],
        "operation": payload["operation"],
        "result": {
            "runtimeIdentity": payload.get("agent", {}).get("runtimeIdentity")
            or payload.get("agent", {}).get("agentId")
            or f"{DOMAIN_ID}::{state.get('customerId', 'unknown')}",
            "projection": projection(state),
            "evidenceEvents": [
                evidence_event(
                    payload,
                    event_type="python_banking_projection_returned",
                    source="banking-python",
                    outcome="SUCCESS",
                    payload={
                        "accountCount": len(state.get("accounts", [])),
                        "transactionCount": len(state.get("transactions", [])),
                    },
                )
            ],
        },
        "warnings": [],
    }


def success_envelope(
    payload: dict[str, Any],
    *,
    runtime_identity: str,
    state: dict[str, Any],
    evidence_events: list[dict[str, Any]] | None = None,
    domain_system_work: dict[str, Any] | None = None,
    control_action: dict[str, Any] | None = None,
    tool_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    result = {
        "runtimeIdentity": runtime_identity,
        "projection": projection(state),
        "evidenceEvents": evidence_events or [],
    }
    if domain_system_work:
        result["domainSystemWork"] = domain_system_work
    if control_action:
        result["controlAction"] = control_action
    if tool_result:
        result["toolResult"] = encode_state(tool_result)
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "requestId": payload["requestId"],
        "operation": payload["operation"],
        "result": result,
        "nextState": {
            "schemaVersion": SCHEMA_VERSION,
            "data": encode_state(state),
        },
        "warnings": [],
    }


def projection(state: dict[str, Any]) -> dict[str, Any]:
    if "nativeSimulation" in state:
        return native_projection(state["nativeSimulation"], [])
    accounts = state.get("accounts", [])
    transactions = state.get("transactions", [])
    total_balance = sum(Decimal(str(account.get("balance", 0))) for account in accounts)
    hardship = state.get("hardship", {})
    return {
        "runtimeMetadata": {
            "domainId": DOMAIN_ID,
            "schemaVersion": SCHEMA_VERSION,
            "domainProfile": "banking-python-remote",
            "providerLanguage": "python",
            "accountCount": len(accounts),
            "transactionCount": len(transactions),
            "segment": state.get("segment", "unknown"),
        },
        "lifecycleSchedule": lifecycle_schedule(state),
        "inspectionSchedule": [
            {
                "description": "Inspect Python banking state",
                "type": "PROMPT",
                "nextExecution": iso_now_plus(minutes=5),
            }
        ],
        "statusView": {
            "totalBalance": decimal_string(total_balance),
            "primaryProductCode": accounts[0]["productCode"] if accounts else DEFAULT_PRODUCT_CODE,
            "accounts": encode_state({"accounts": accounts})["accounts"],
            "scheduledTransactions": scheduled_transactions(state),
            "transactionCount": len(transactions),
            "transactionPostingCount": len(state.get("transactionPostings", [])),
            "executedWorkCount": int(state.get("executedWorkCount", 0)),
            "appliedTransactionCount": int(state.get("appliedTransactionCount", 0)),
            "rejectedTransactionCount": int(state.get("rejectedTransactionCount", 0)),
            "appliedDomainSystemWorkCount": int(state.get("appliedDomainSystemWorkCount", 0)),
            "recentTransactions": encode_state(
                {"transactions": state.get("transactionPostings", [])[-100:]}
            )["transactions"],
            "creditCardBills": encode_state({"creditCardBills": state.get("creditCardBills", [])[-100:]})[
                "creditCardBills"
            ],
            "hardshipFlagged": bool(hardship.get("flagged", False)),
            "reliefGranted": decimal_string(Decimal(str(hardship.get("reliefGranted", 0)))),
            "lastToolResult": encode_state(state.get("lastToolResult", {})),
            "lastControlAction": encode_state(state.get("lastControlAction", {})),
            "lastAction": state.get("lastAction", "unknown"),
        },
        "inspectionView": {
            "accounts": encode_state({"accounts": accounts})["accounts"],
            "recentTransactions": encode_state({"transactions": transactions[-10:]})["transactions"],
            "recentTransactionPostings": encode_state(
                {"transactionPostings": state.get("transactionPostings", [])[-10:]}
            )["transactionPostings"],
            "creditCardBills": encode_state({"creditCardBills": state.get("creditCardBills", [])[-10:]})[
                "creditCardBills"
            ],
            "hardship": encode_state({"hardship": hardship})["hardship"],
            "controlActions": encode_state({"controlActions": state.get("controlActions", [])[-20:]})[
                "controlActions"
            ],
            "scheduledWork": scheduled_transactions(state),
            "productCatalog": product_catalog(accounts=accounts),
            "stateKeys": list(state.keys()),
        },
    }


def validate_base(payload: dict[str, Any], *, require_state: bool, require_idempotency: bool) -> None:
    protocol_version = trim(payload.get("protocolVersion"))
    request_id = payload.get("requestId")
    operation = payload.get("operation")
    domain_id = trim(payload.get("domain", {}).get("domainId"))

    if protocol_version != PROTOCOL_VERSION:
        raise ProtocolError(
            400,
            "unsupported_protocol_version",
            "unsupported_protocol_version",
            f"Unsupported protocolVersion '{protocol_version}'.",
            request_id=request_id,
            operation=operation,
            details={"expected": PROTOCOL_VERSION},
        )
    if domain_id != DOMAIN_ID:
        raise ProtocolError(
            400,
            "invalid_domain",
            "invalid_request",
            f"banking-python only serves domainId '{DOMAIN_ID}'.",
            request_id=request_id,
            operation=operation,
            details={"expected": DOMAIN_ID, "actual": domain_id},
        )
    if not request_id:
        raise ProtocolError(
            400,
            "missing_request_id",
            "invalid_request",
            "requestId is required.",
            request_id=request_id,
            operation=operation,
        )
    if require_idempotency and not trim(payload.get("idempotencyKey")):
        raise ProtocolError(
            400,
            "missing_idempotency_key",
            "invalid_request",
            "Mutating requests must include idempotencyKey.",
            request_id=request_id,
            operation=operation,
        )
    if require_state and not payload.get("state"):
        raise ProtocolError(
            400,
            "missing_state",
            "invalid_request",
            "State is required for this operation.",
            request_id=request_id,
            operation=operation,
        )


def read_state(payload: dict[str, Any]) -> dict[str, Any]:
    state = payload["state"]
    schema_version = trim(state.get("schemaVersion"))
    if schema_version != SCHEMA_VERSION:
        raise ProtocolError(
            422,
            "unsupported_state_schema",
            "domain_rejected",
            f"Unsupported state schemaVersion '{schema_version}'.",
            request_id=payload.get("requestId"),
            operation=payload.get("operation"),
            details={"expected": SCHEMA_VERSION},
        )
    return decode_state(dict(state.get("data", {}) or {}))


def normalize_accounts(raw_accounts: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_accounts, list) or not raw_accounts:
        raw_accounts = [
            {
                "name": "python-banking-saver",
                "productCode": DEFAULT_PRODUCT_CODE,
                "balance": 1250,
                "limit": 0,
                "currency": "AUD",
            }
        ]
    accounts = []
    seen_account_ids: set[str] = set()
    for index, raw in enumerate(raw_accounts):
        account = dict(raw or {})
        account_id = trim(account.get("accountId")) or f"acct-{index + 1}"
        if account_id in seen_account_ids:
            raise ValueError("Banking initialAccounts must not contain duplicate accountId values.")
        seen_account_ids.add(account_id)
        normalized = {
            "id": int_or_default(account.get("id"), index + 1),
            "accountId": account_id,
            "name": trim(account.get("name")) or f"Python Account {index + 1}",
            "productCode": trim(account.get("productCode")) or DEFAULT_PRODUCT_CODE,
            "balance": Decimal(str(account.get("balance", 0))),
            "limit": Decimal(str(account.get("limit", 0))),
            "currency": trim(account.get("currency")) or "AUD",
        }
        for field in (
            "productCategory",
            "category",
            "interestRate",
            "apr",
            "minPaymentPercentage",
            "minimumPaymentPercentage",
            "statementStartDate",
            "statementEndDate",
            "customerId",
        ):
            if account.get(field) is not None:
                normalized[field] = account[field]
        product_category = product_category_for_code(normalized["productCode"])
        normalized.setdefault("productCategory", product_category)
        normalized.setdefault("category", product_category)
        accounts.append(normalized)
    return accounts


def post_transaction(state: dict[str, Any], work: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    transaction_type = normalize_token(payload.get("type") or payload.get("transactionType") or "PAYMENT")
    amount = abs(Decimal(str(payload.get("amount", payload.get("transactionAmount", 25)))))
    description = trim(payload.get("description")) or trim(work.get("description")) or "Python banking transaction"
    accounts = state.get("accounts", [])
    if not accounts:
        state["accounts"] = normalize_accounts([])
        accounts = state["accounts"]
    details = dict(payload.get("details", {}) or {})
    from_account = resolve_account(accounts, first_reference(details.get("from"))) or accounts[0]
    to_account = resolve_account(accounts, first_reference(details.get("to"))) or accounts[0]
    applied = True
    failure_message = ""
    if transaction_type == "DEPOSIT":
        to_account["balance"] = Decimal(str(to_account.get("balance", 0))) + amount
    elif transaction_type == "TRANSFER":
        if not can_debit(from_account, amount):
            applied = False
            failure_message = "insufficient funds for transfer"
        else:
            from_account["balance"] = Decimal(str(from_account.get("balance", 0))) - amount
            to_account["balance"] = Decimal(str(to_account.get("balance", 0))) + amount
    else:
        if not can_debit(from_account, amount):
            applied = False
            failure_message = "insufficient funds for payment"
        else:
            from_account["balance"] = Decimal(str(from_account.get("balance", 0))) - amount

    signed_amount = amount if transaction_type == "DEPOSIT" else -amount
    transaction = {
        "transactionId": f"txn-{len(state.get('transactions', [])) + 1}",
        "amount": signed_amount,
        "type": transaction_type,
        "currency": trim(payload.get("currency")) or from_account.get("currency", "AUD"),
        "description": description,
        "counterparty": trim(payload.get("counterparty")) or "python-banking-counterparty",
        "postedAt": iso_now_plus(minutes=0),
        "workId": trim(work.get("workId")),
        "occurrenceKey": trim(work.get("occurrenceKey")),
        "status": "APPLIED" if applied else "REJECTED",
    }
    log_item = {**work, "status": transaction["status"]}
    if applied:
        state.setdefault("transactions", []).append(transaction)
        state.setdefault("transactionPostings", []).append(
            {
                "workId": transaction["workId"],
                "description": description,
                "occurrenceKey": transaction["occurrenceKey"],
                "dateTime": transaction["postedAt"],
                "debits": [{"accountName": from_account.get("name"), "amount": decimal_string(amount)}]
                if transaction_type != "DEPOSIT"
                else [],
                "credits": [{"accountName": to_account.get("name"), "amount": decimal_string(amount)}]
                if transaction_type in {"DEPOSIT", "TRANSFER"}
                else [],
                "type": transaction_type,
            }
        )
        state["appliedTransactionCount"] = int(state.get("appliedTransactionCount", 0)) + 1
        state["lastAction"] = "remote_domain_transaction_posted"
    else:
        log_item["failureMessage"] = failure_message
        transaction["failureMessage"] = failure_message
        state["rejectedTransactionCount"] = int(state.get("rejectedTransactionCount", 0)) + 1
        state["lastAction"] = "remote_domain_work_rejected"
    state.setdefault("executionLog", []).append(log_item)
    state["executedWorkCount"] = int(state.get("executedWorkCount", 0)) + 1
    return {
        "eventType": "remote_domain_transaction_posted" if applied else "remote_domain_work_rejected",
        "outcome": "SUCCESS" if applied else "REJECTED",
        "severity": "INFO" if applied else "WARN",
        "accountId": from_account["accountId"],
        "transactionId": transaction["transactionId"],
        "amount": decimal_string(signed_amount),
        "transactionType": transaction_type,
        "newBalance": decimal_string(from_account["balance"]),
        "failureMessage": failure_message,
    }


def accrue_interest(state: dict[str, Any], work: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any] | None:
    annual_rate = Decimal(str(payload.get("annualRate", "0.03")))
    monthly_rate = annual_rate / Decimal("12")
    total_interest = Decimal("0")
    opening_total_balance = Decimal("0")
    account_entries: list[dict[str, Any]] = []
    for account in state.get("accounts", []):
        if not is_deposit_interest_account(account):
            continue
        balance = Decimal(str(account.get("balance", 0)))
        if balance <= Decimal("0"):
            continue
        opening_total_balance += balance
        interest = (balance * monthly_rate).quantize(Decimal("0.01"))
        if interest == Decimal("0"):
            continue
        new_balance = balance + interest
        account["balance"] = new_balance
        total_interest += interest
        account_entries.append({
            "accountId": account.get("accountId") or account.get("id"),
            "accountName": account.get("name"),
            "productCode": account.get("productCode"),
            "productCategory": account.get("productCategory") or account.get("category"),
            "openingBalance": decimal_string(balance),
            "interestAmount": decimal_string(interest),
            "newBalance": decimal_string(new_balance),
            "currency": trim(account.get("currency")) or "AUD",
        })
    if not account_entries:
        state["lastAction"] = "remote_domain_interest_checked_noop"
        return None
    event = {
        "eventType": "remote_domain_interest_accrued",
        "outcome": "SUCCESS",
        "severity": "INFO",
        "workId": trim(work.get("workId")),
        "occurrenceKey": trim(work.get("occurrenceKey")) or trim(work.get("workId")),
        "annualRate": decimal_string(annual_rate),
        "monthlyRate": str(monthly_rate),
        "openingTotalBalance": decimal_string(opening_total_balance),
        "interestAmount": decimal_string(total_interest),
        "newTotalBalance": decimal_string(opening_total_balance + total_interest),
        "affectedAccountCount": len(account_entries),
        "accountInterest": account_entries,
    }
    state["executedWorkCount"] = int(state.get("executedWorkCount", 0)) + 1
    state["appliedDomainSystemWorkCount"] = int(state.get("appliedDomainSystemWorkCount", 0)) + 1
    state.setdefault("executionLog", []).append(
        {
            **work,
            "status": "APPLIED",
            "domainSystemEvents": [event],
        }
    )
    state["lastAction"] = "remote_domain_interest_accrued"
    return event


def accrue_credit_card_interest(
    state: dict[str, Any],
    work: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    accounts = state.setdefault("accounts", normalize_accounts([]))
    postings = state.setdefault("transactionPostings", [])
    occurrence_key = trim(work.get("occurrenceKey")) or trim(work.get("workId"))
    for account in accounts:
        if not matches_credit_card_work_target(account, payload):
            continue
        opening_balance = Decimal(str(account.get("balance", 0)))
        annual_rate = first_positive_decimal(
            account.get("interestRate"),
            account.get("apr"),
            payload.get("annualRate"),
            payload.get("apr"),
        )
        if opening_balance >= Decimal("-0.01") or annual_rate <= Decimal("0"):
            continue
        if has_credit_card_posting(postings, occurrence_key, "remote_domain_credit_card_interest_charged", account):
            continue
        interest_charge = round_money(abs(opening_balance) * annual_rate / Decimal("12"))
        if interest_charge < Decimal("0.01"):
            continue
        new_balance = round_money(opening_balance - interest_charge)
        min_payment_percentage = first_positive_decimal(
            payload.get("minPaymentPercentage"),
            payload.get("minimumPaymentPercentage"),
            account.get("minPaymentPercentage"),
            account.get("minimumPaymentPercentage"),
            Decimal("0.04"),
        )
        minimum_payment = round_money(abs(new_balance) * min_payment_percentage)
        account["balance"] = new_balance
        account["currentMinPayment"] = minimum_payment
        account["lastCreditCardInterestPostedAt"] = iso_now_plus(minutes=0)
        record = {
            "eventType": "remote_domain_credit_card_interest_charged",
            "outcome": "SUCCESS",
            "severity": "INFO",
            "workId": trim(work.get("workId")),
            "occurrenceKey": occurrence_key,
            "description": trim(work.get("description")) or "Credit Card Monthly Interest",
            "dateTime": iso_now_plus(minutes=0),
            "accountId": account.get("accountId") or account.get("id"),
            "runtimeAccountId": account.get("id"),
            "accountName": account.get("name"),
            "productCode": account.get("productCode"),
            "amount": decimal_string(-interest_charge),
            "currency": trim(account.get("currency")) or "AUD",
            "openingBalance": decimal_string(opening_balance),
            "annualRate": str(annual_rate),
            "monthlyRate": str(annual_rate / Decimal("12")),
            "interestAmount": decimal_string(interest_charge),
            "newBalance": decimal_string(new_balance),
            "minimumPayment": decimal_string(minimum_payment),
        }
        postings.append(record)
        events.append(record)
    record_domain_system_work(state, work, events, "remote_domain_credit_card_interest_checked")
    return events


def apply_credit_card_rate_change(
    state: dict[str, Any],
    work: dict[str, Any],
    payload: dict[str, Any],
    applied_at_epoch_ms: int,
) -> list[dict[str, Any]]:
    scheduled_snapshot = require_rate_change_plan(payload.get("planSnapshot"))
    installed_snapshot = require_rate_change_plan(state.get("clockDrivenInterventionPlan"))
    if scheduled_snapshot != installed_snapshot:
        raise ValueError(
            "Scheduled rate-change facts contradict the installed intervention plan"
        )
    plan = installed_snapshot.plan
    work_id = trim(work.get("workId"))
    occurrence_key = trim(work.get("occurrenceKey"))
    scheduled_for_epoch_ms = work.get("scheduledForEpochMs")
    if (
        isinstance(scheduled_for_epoch_ms, bool)
        or not isinstance(scheduled_for_epoch_ms, int)
        or work_id != trim(plan.get("workId"))
        or occurrence_key
        != f"banking|clock-driven-rate-change|{trim(plan.get('mutationOperationId'))}"
        or scheduled_for_epoch_ms != plan.get("scheduledForEpochMs")
        or plan.get("effectiveAtEpochMs") != scheduled_for_epoch_ms
        or isinstance(applied_at_epoch_ms, bool)
        or not isinstance(applied_at_epoch_ms, int)
        or applied_at_epoch_ms < scheduled_for_epoch_ms
    ):
        raise ValueError(
            "Scheduled rate-change identity contradicts the installed intervention plan"
        )

    operation_id = trim(plan.get("mutationOperationId"))
    product_code = trim(plan.get("productCode"))
    old_rate = canonical_rate_change_decimal(
        plan.get("expectedPreviousRate"), "expectedPreviousRate"
    )
    new_rate = canonical_rate_change_decimal(plan.get("newRate"), "newRate")
    accounts = state.setdefault("accounts", [])
    eligible_accounts = [
        account
        for account in accounts
        if isinstance(account, dict)
        and trim(account.get("productCode")).upper() == product_code.upper()
    ]
    affected_account_ids = [
        trim(account.get("accountId") or account.get("id"))
        for account in eligible_accounts
    ]
    operation = {
        "operationId": operation_id,
        "planDigest": installed_snapshot.plan_digest,
        "interventionId": plan["interventionId"],
        "workId": work_id,
        "occurrenceKey": occurrence_key,
        "scheduledForEpochMs": scheduled_for_epoch_ms,
        "effectiveAtEpochMs": plan["effectiveAtEpochMs"],
        "appliedAtEpochMs": applied_at_epoch_ms,
        "affectedAccountCount": len(affected_account_ids),
        "affectedAccountIds": affected_account_ids,
        "outcome": "APPLIED" if affected_account_ids else "SKIPPED",
    }
    applied_operations = [
        dict(applied_operation)
        for applied_operation in state.get("appliedInterventionOperations", []) or []
        if isinstance(applied_operation, dict)
    ]
    for existing in applied_operations:
        if trim(existing.get("operationId")) != operation_id:
            continue
        existing_applied_at = existing.get("appliedAtEpochMs")
        replay_operation = {**operation, "appliedAtEpochMs": existing_applied_at}
        if (
            isinstance(existing_applied_at, bool)
            or not isinstance(existing_applied_at, int)
            or existing_applied_at < plan["effectiveAtEpochMs"]
            or existing_applied_at > applied_at_epoch_ms
            or existing != replay_operation
        ):
            raise ValueError(
                "Rate-change operationId was already used with contradictory facts"
            )
        for account in eligible_accounts:
            validate_applied_rate_change_account(
                account,
                new_rate,
                installed_snapshot.plan_digest,
                operation_id,
                existing_applied_at,
            )
        return rate_change_events(
            state,
            plan,
            installed_snapshot.plan_digest,
            replay_operation,
            existing_applied_at,
        )

    for account in eligible_accounts:
        if (
            canonical_rate_change_decimal(
                account.get("interestRate"), "account.interestRate"
            )
            != old_rate
            or canonical_rate_change_decimal(account.get("apr"), "account.apr")
            != old_rate
            or trim(account.get("ratePlanDigest")) != installed_snapshot.plan_digest
        ):
            raise ValueError(
                "Eligible account rate does not match the plan's expected previous rate"
            )

    for account in eligible_accounts:
        account["interestRate"] = new_rate
        account["apr"] = new_rate
        account["rateChangedAtEpochMs"] = applied_at_epoch_ms
        account["rateChangeOperationId"] = operation_id

    applied_operations.append(operation)
    state["appliedInterventionOperations"] = applied_operations
    work_events = rate_change_events(
        state,
        plan,
        installed_snapshot.plan_digest,
        operation,
        applied_at_epoch_ms,
    )
    state["executedWorkCount"] = int(state.get("executedWorkCount", 0)) + (
        1 if work_events else 0
    )
    state["appliedDomainSystemWorkCount"] = int(
        state.get("appliedDomainSystemWorkCount", 0)
    ) + (1 if work_events else 0)
    state.setdefault("executionLog", []).append(
        {
            **work,
            "status": "APPLIED" if work_events else "SKIPPED",
            "domainSystemEvents": work_events,
        }
    )
    state["lastAction"] = (
        "remote_domain_rate_changed"
        if work_events
        else "remote_domain_rate_change_skipped"
    )
    return work_events


def rate_change_events(
    state: dict[str, Any],
    plan: dict[str, Any],
    plan_digest: str,
    operation: dict[str, Any],
    applied_at_epoch_ms: int,
) -> list[dict[str, Any]]:
    affected_account_ids = operation["affectedAccountIds"]
    if not affected_account_ids:
        return []
    customer_id = trim(state.get("agentId")) or trim(state.get("customerId"))
    operation_id = operation["operationId"]
    event_time = datetime.fromtimestamp(applied_at_epoch_ms / 1000, tz=UTC)
    return [{
        "eventType": "remote_domain_rate_changed",
        "businessEventId": f"{operation_id}|customer:{customer_id}",
        "occurrenceKey": operation["occurrenceKey"],
        "workId": operation["workId"],
        "simulationRunId": plan["simulationRunId"],
        "customerId": customer_id,
        "interventionId": plan["interventionId"],
        "operationId": operation_id,
        "planDigest": plan_digest,
        "productCode": plan["productCode"],
        "oldRate": plan["expectedPreviousRate"],
        "newRate": plan["newRate"],
        "scheduledForEpochMs": operation["scheduledForEpochMs"],
        "effectiveAtEpochMs": operation["effectiveAtEpochMs"],
        "appliedAtEpochMs": applied_at_epoch_ms,
        "dateTime": event_time.isoformat().replace("+00:00", "Z"),
        "affectedAccountCount": operation["affectedAccountCount"],
        "affectedAccountIds": list(affected_account_ids),
        "outcome": "SUCCESS",
        "severity": "INFO",
    }]


def validate_applied_rate_change_account(
    account: dict[str, Any],
    expected_rate: str,
    plan_digest: str,
    operation_id: str,
    scheduled_for_epoch_ms: int,
) -> None:
    if (
        canonical_rate_change_decimal(
            account.get("interestRate"), "account.interestRate"
        )
        != expected_rate
        or canonical_rate_change_decimal(account.get("apr"), "account.apr")
        != expected_rate
        or trim(account.get("ratePlanDigest")) != plan_digest
        or trim(account.get("rateChangeOperationId")) != operation_id
        or account.get("rateChangedAtEpochMs") != scheduled_for_epoch_ms
    ):
        raise ValueError(
            "Applied rate-change receipt contradicts the committed customer state"
        )


def accrue_loan_interest(
    state: dict[str, Any],
    work: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    accounts = state.setdefault("accounts", normalize_accounts([]))
    postings = state.setdefault("transactionPostings", [])
    occurrence_key = trim(work.get("occurrenceKey")) or trim(work.get("workId"))
    for account in accounts:
        if not matches_loan_work_target(account, payload):
            continue
        opening_balance = Decimal(str(account.get("balance", 0)))
        annual_rate = first_positive_decimal(
            payload.get("annualRate"),
            payload.get("apr"),
            account.get("interestRate"),
            account.get("apr"),
        )
        if opening_balance >= Decimal("-0.01") or annual_rate <= Decimal("0"):
            continue
        if has_credit_card_posting(postings, occurrence_key, "remote_domain_loan_interest_accrued", account):
            continue
        interest_charge = round_money(abs(opening_balance) * annual_rate / Decimal("12"))
        if interest_charge < Decimal("0.01"):
            continue
        repayment_amount = Decimal("0")
        if is_home_loan_account(account):
            repayment_base = first_positive_decimal(
                payload.get("repaymentBaseAmount"),
                payload.get("baseRepaymentAmount"),
                payload.get("repaymentAmount"),
                account.get("repaymentBaseAmount"),
                Decimal("600"),
            )
            repayment_amount = round_money(repayment_base + interest_charge)
        new_balance = round_money(opening_balance - interest_charge + repayment_amount)
        account["balance"] = new_balance
        account["lastLoanInterestAccruedAt"] = iso_now_plus(minutes=0)
        if repayment_amount > Decimal("0"):
            account["lastLoanRepaymentAppliedAt"] = iso_now_plus(minutes=0)
        event = {
            "eventType": "remote_domain_loan_interest_accrued",
            "outcome": "SUCCESS",
            "severity": "INFO",
            "workId": trim(work.get("workId")),
            "occurrenceKey": occurrence_key,
            "description": trim(work.get("description")) or "Loan monthly interest",
            "dateTime": iso_now_plus(minutes=0),
            "accountId": account.get("accountId") or account.get("id"),
            "runtimeAccountId": account.get("id"),
            "accountName": account.get("name"),
            "productCode": account.get("productCode"),
            "productCategory": account.get("productCategory") or account.get("category"),
            "loanKind": "HOME_LOAN" if is_home_loan_account(account) else "STUDENT_LOAN",
            "currency": trim(account.get("currency")) or "AUD",
            "openingBalance": decimal_string(opening_balance),
            "annualRate": str(annual_rate),
            "monthlyRate": str(annual_rate / Decimal("12")),
            "interestAmount": decimal_string(interest_charge),
            "repaymentAmount": decimal_string(repayment_amount),
            "principalReductionAmount": decimal_string(max(Decimal("0"), repayment_amount - interest_charge)),
            "newBalance": decimal_string(new_balance),
        }
        postings.append(
            {
                **event,
                "amount": decimal_string(-interest_charge),
                "repaymentAmount": decimal_string(repayment_amount),
            }
        )
        events.append(event)
    record_domain_system_work(state, work, events, "remote_domain_loan_interest_checked")
    return events


def close_credit_card_statement(
    state: dict[str, Any],
    work: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    accounts = state.setdefault("accounts", normalize_accounts([]))
    bills = state.setdefault("creditCardBills", [])
    occurrence_key = trim(work.get("occurrenceKey")) or trim(work.get("workId"))
    for account in accounts:
        if not matches_credit_card_work_target(account, payload):
            continue
        if has_credit_card_bill(bills, occurrence_key, account):
            continue
        closing_balance = round_money(Decimal(str(account.get("balance", 0))))
        min_payment_percentage = first_positive_decimal(
            payload.get("minPaymentPercentage"),
            payload.get("minimumPaymentPercentage"),
            account.get("minPaymentPercentage"),
            account.get("minimumPaymentPercentage"),
            Decimal("0.04"),
        )
        minimum_payment = round_money(max(Decimal("0"), -closing_balance) * min_payment_percentage)
        statement_end = first_date(payload.get("statementEndDate"), account.get("statementEndDate")) or datetime.now(tz=UTC).date()
        statement_start = (
            first_date(payload.get("statementStartDate"), account.get("statementStartDate"))
            or statement_end - timedelta(days=29)
        )
        due_date = statement_end + timedelta(days=max(1, int_or_default(payload.get("dueDays"), 14)))
        scheduled_for_epoch_ms = int_or_default(work.get("scheduledForEpochMs"), 0)
        closed_at = (
            datetime.fromtimestamp(scheduled_for_epoch_ms / 1000, tz=UTC)
            .isoformat()
            .replace("+00:00", "Z")
            if scheduled_for_epoch_ms > 0
            else iso_now_plus(minutes=0)
        )
        event = {
            "eventType": "remote_domain_credit_card_statement_closed",
            "outcome": "SUCCESS",
            "severity": "INFO",
            "billId": safe_bill_id(occurrence_key, account),
            "workId": trim(work.get("workId")),
            "occurrenceKey": occurrence_key,
            "scheduledForEpochMs": scheduled_for_epoch_ms or None,
            "customerId": account.get("customerId"),
            "accountId": account.get("accountId") or account.get("id"),
            "runtimeAccountId": account.get("id"),
            "accountName": account.get("name"),
            "productCode": account.get("productCode"),
            "closingAmount": decimal_string(closing_balance),
            "minimumAmountDue": decimal_string(minimum_payment),
            "currency": trim(account.get("currency")) or "AUD",
            "statementStartDate": statement_start.isoformat(),
            "statementEndDate": statement_end.isoformat(),
            "dueDate": due_date.isoformat(),
            "closedAt": closed_at,
        }
        if closing_balance < Decimal("-0.01"):
            bills.append(event)
        account["currentMinPayment"] = minimum_payment
        account["lastStatementClosedAt"] = event["closedAt"]
        next_statement_start = statement_end + timedelta(days=1)
        account["statementStartDate"] = next_statement_start.isoformat()
        account["statementEndDate"] = (next_statement_start + timedelta(days=29)).isoformat()
        events.append(event)
    record_domain_system_work(state, work, events, "remote_domain_credit_card_statement_checked")
    return events


def record_domain_system_work(
    state: dict[str, Any],
    work: dict[str, Any],
    events: list[dict[str, Any]],
    no_event_action: str,
) -> None:
    if events:
        state["executedWorkCount"] = int(state.get("executedWorkCount", 0)) + 1
        state["appliedDomainSystemWorkCount"] = int(state.get("appliedDomainSystemWorkCount", 0)) + 1
        state.setdefault("executionLog", []).append(
            {
                **work,
                "status": "APPLIED",
                "domainSystemEvents": events,
            }
        )
    state["lastAction"] = events[-1]["eventType"] if events else no_event_action


def run_hardship_review(state: dict[str, Any], work: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    threshold = Decimal(str(payload.get("threshold", "500")))
    relief_amount = Decimal(str(payload.get("reliefAmount", "75")))
    accounts = state.get("accounts", [])
    total_balance = sum(Decimal(str(account.get("balance", 0))) for account in accounts)
    hardship = state.setdefault("hardship", {"reviewCount": 0, "reliefGranted": Decimal("0"), "flagged": False})
    hardship["reviewCount"] = int(hardship.get("reviewCount", 0)) + 1
    approved = total_balance < threshold
    hardship["flagged"] = approved
    if approved and accounts:
        accounts[0]["balance"] = Decimal(str(accounts[0].get("balance", 0))) + relief_amount
        hardship["reliefGranted"] = Decimal(str(hardship.get("reliefGranted", 0))) + relief_amount
    state["lastAction"] = "remote_domain_hardship_reviewed"
    return {
        "eventType": "remote_domain_hardship_reviewed",
        "outcome": "APPROVED" if approved else "DECLINED",
        "severity": "WARN" if approved else "INFO",
        "workId": trim(work.get("workId")),
        "threshold": decimal_string(threshold),
        "reliefAmount": decimal_string(relief_amount if approved else Decimal("0")),
        "balanceAtReview": decimal_string(total_balance),
    }


def record_generic_work(state: dict[str, Any], work: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    state["lastAction"] = "remote_domain_generic_work_recorded"
    return {
        "eventType": "remote_domain_generic_work_recorded",
        "outcome": "RECORDED",
        "workId": trim(work.get("workId")),
        "workKind": trim(payload.get("workKind")) or trim(work.get("workType")) or "unknown",
    }


def has_applied_work(state: dict[str, Any], work: dict[str, Any]) -> bool:
    if not trim(work.get("workId")):
        return False
    if any(
        successful_work_receipt(candidate) and same_work_identity(work, candidate)
        for candidate in state.get("executionLog", []) or []
        if isinstance(candidate, dict)
    ):
        return True
    return any(
        same_work_identity(work, candidate)
        for candidate in state.get("transactionPostings", []) or []
        if isinstance(candidate, dict)
    )


def same_work_identity(requested: dict[str, Any], applied: dict[str, Any]) -> bool:
    requested_work_id = trim(requested.get("workId"))
    if not requested_work_id or trim(applied.get("workId")) != requested_work_id:
        return False
    if trim(requested.get("workType")).upper() != "DOMAIN_SYSTEM_WORK":
        return True
    requested_occurrence = trim(requested.get("occurrenceKey"))
    return not requested_occurrence or trim(applied.get("occurrenceKey")) == requested_occurrence


def successful_work_receipt(candidate: dict[str, Any]) -> bool:
    return trim(candidate.get("status")).upper() in {"APPLIED", "RECORDED", "ALREADY_APPLIED"}


def record_successful_work_receipt(
    state: dict[str, Any],
    work: dict[str, Any],
    work_events: list[dict[str, Any]],
) -> None:
    if not work_events or any(
        trim(event.get("outcome")).upper() in {"REJECTED", "FAILED", "ERROR"}
        for event in work_events
    ):
        return
    execution_log = state.setdefault("executionLog", [])
    if any(
        isinstance(candidate, dict)
        and successful_work_receipt(candidate)
        and same_work_identity(work, candidate)
        for candidate in execution_log
    ):
        return
    recorded_only = all(trim(event.get("outcome")).upper() == "RECORDED" for event in work_events)
    execution_log.append({**work, "status": "RECORDED" if recorded_only else "APPLIED"})
    if not recorded_only:
        state["executedWorkCount"] = int(state.get("executedWorkCount", 0)) + 1


def idempotent_replay_result(work: dict[str, Any]) -> dict[str, Any]:
    payload = dict(work.get("payload", {}) or {})
    operation_name = trim(payload.get("toolName")) or trim(work.get("workType")) or "execute-work"
    values: dict[str, Any] = {
        "idempotentReplay": True,
        "workId": trim(work.get("workId")),
    }
    if trim(work.get("occurrenceKey")):
        values["occurrenceKey"] = trim(work.get("occurrenceKey"))
    return tool_result(operation_name, True, "Work already applied.", values)


def idempotent_replay_event(
    work: dict[str, Any],
    replay_result: dict[str, Any],
) -> dict[str, Any]:
    return {
        "eventType": "remote_domain_tool_effect_replayed",
        "outcome": "NOOP",
        "severity": "INFO",
        "workId": trim(work.get("workId")),
        "toolResult": replay_result,
    }


def is_tool_call(work: dict[str, Any]) -> bool:
    payload = dict(work.get("payload", {}) or {})
    return trim(work.get("workType")).upper() == "TOOL_CALL" or trim(payload.get("kind")).lower() == "tool-call"


def is_mutating_work(work: dict[str, Any]) -> bool:
    return not is_read_only_tool_call(work)


def is_read_only_tool_call(work: dict[str, Any]) -> bool:
    payload = dict(work.get("payload", {}) or {})
    return is_tool_call(work) and trim(payload.get("toolName")).lower() == "get_product_list"


def apply_tool_call(state: dict[str, Any], work: dict[str, Any]) -> dict[str, Any]:
    payload = dict(work.get("payload", {}) or {})
    tool_name = trim(payload.get("toolName"))
    arguments = dict(payload.get("arguments", {}) or {})
    accounts = state.get("accounts", [])
    if tool_name.lower() == "get_product_list":
        result = tool_result(
            tool_name,
            True,
            "Product list returned.",
            {"products": product_catalog(accounts=accounts, product_category=arguments.get("productCategory"))},
        )
        return {
            "eventType": "remote_domain_product_list_returned",
            "outcome": "SUCCESS",
            "toolResult": result,
            "productCount": len(result["products"]),
        }
    if tool_name.lower() == "post_double_entry_transaction":
        result = post_double_entry_tool_call(state, work, arguments)
        state["lastAction"] = "remote_domain_double_entry_tool_call"
        return {
            "eventType": "remote_domain_double_entry_tool_call",
            "outcome": "SUCCESS" if result["success"] else "REJECTED",
            "severity": "INFO" if result["success"] else "WARN",
            "toolResult": result,
        }
    result = tool_result(tool_name, False, f"Unsupported remote banking tool: {tool_name}", {})
    state["lastAction"] = "remote_domain_tool_rejected"
    return {
        "eventType": "remote_domain_tool_rejected",
        "outcome": "REJECTED",
        "severity": "WARN",
        "toolResult": result,
    }


def post_double_entry_tool_call(state: dict[str, Any], work: dict[str, Any], arguments: dict[str, Any]) -> dict[str, Any]:
    try:
        debits = map_list(arguments.get("debits"))
        credits = map_list(arguments.get("credits"))
        if not debits or not credits:
            raise ValueError("debits and credits must be non-empty lists")
        debit_total = sum(decimal_amount(leg.get("amount")) for leg in debits)
        credit_total = sum(decimal_amount(leg.get("amount")) for leg in credits)
        if debit_total != credit_total:
            raise ValueError("double-entry transaction is not balanced")
        current_accounts = state.get("accounts")
        accounts = current_accounts if isinstance(current_accounts, list) else normalize_accounts([])
        staged_accounts = [dict(account) for account in accounts]
        for debit in debits:
            apply_tool_leg(staged_accounts, debit, Decimal("-1"))
        for credit in credits:
            apply_tool_leg(staged_accounts, credit, Decimal("1"))
        record = {
            "workId": trim(work.get("workId")),
            "occurrenceKey": trim(work.get("occurrenceKey")),
            "description": trim(work.get("description")) or "Python double-entry transaction",
            "toolName": "post_double_entry_transaction",
            "dateTime": iso_now_plus(minutes=0),
            "debits": debits,
            "credits": credits,
        }
        state["accounts"] = staged_accounts
        state.setdefault("transactionPostings", []).append(record)
        return tool_result(
            "post_double_entry_transaction",
            True,
            "Transaction posted.",
            {"transaction": record, "accounts": encode_state({"accounts": staged_accounts})["accounts"]},
        )
    except ValueError as exc:
        return tool_result("post_double_entry_transaction", False, str(exc), {})


def apply_tool_leg(accounts: list[dict[str, Any]], leg: dict[str, Any], direction: Decimal) -> None:
    amount = decimal_amount(leg.get("amount"))
    if amount < 0:
        raise ValueError("transaction leg amount must be a positive number")
    account_reference = trim(leg.get("accountId")) or trim(leg.get("id")) or trim(leg.get("accountName")) or trim(leg.get("name"))
    if not account_reference:
        raise ValueError("transaction leg account reference is required")
    for account in accounts:
        candidates = {
            trim(account.get("accountId")),
            trim(account.get("id")),
            trim(account.get("name")),
        }
        if account_reference in candidates:
            account["balance"] = Decimal(str(account.get("balance", 0))) + (direction * amount)
            return
    raise ValueError(f"transaction leg account was not found: {account_reference}")


def first_reference(value: Any) -> str:
    if isinstance(value, list) and value:
        return trim(value[0])
    return trim(value)


def resolve_account(accounts: list[dict[str, Any]], account_reference: str) -> dict[str, Any] | None:
    if not account_reference:
        return None
    for account in accounts:
        candidates = {
            trim(account.get("accountId")),
            trim(account.get("id")),
            trim(account.get("name")),
        }
        if account_reference in candidates:
            return account
    return None


def matches_credit_card_work_target(account: dict[str, Any], payload: dict[str, Any]) -> bool:
    if not is_credit_card_account(account):
        return False
    account_id = trim(payload.get("accountId")) or trim(payload.get("id"))
    account_name = trim(payload.get("accountName")) or trim(payload.get("name"))
    product_code = trim(payload.get("productCode"))
    if account_id and account_id not in {trim(account.get("id")), trim(account.get("accountId"))}:
        return False
    if account_name and not matches_account_reference(account, account_name):
        return False
    return not product_code or product_code.upper() == trim(account.get("productCode")).upper()


def is_credit_card_account(account: dict[str, Any]) -> bool:
    product_code = trim(account.get("productCode")).upper()
    category = (trim(account.get("productCategory")) or trim(account.get("category"))).upper()
    return product_code.startswith("CC") or category == "CRED_AND_CHRG_CARDS"


def is_deposit_interest_account(account: dict[str, Any]) -> bool:
    if is_credit_card_account(account):
        return False
    product_code = trim(account.get("productCode")).upper()
    category = (trim(account.get("productCategory")) or trim(account.get("category"))).upper()
    if (
        product_code.startswith(("HL", "SL", "CL", "BL"))
        or category in {"RESIDENTIAL_MORTGAGES", "PERS_LOANS", "BUSINESS_LOANS"}
    ):
        return False
    return (
        product_code.startswith("TS")
        or product_code.startswith("SAV")
        or product_code.startswith("TD")
        or category in {"TRANS_AND_SAVINGS_ACCOUNTS", "TERM_DEPOSITS"}
    )


def is_home_loan_account(account: dict[str, Any]) -> bool:
    product_code = trim(account.get("productCode")).upper()
    category = (trim(account.get("productCategory")) or trim(account.get("category"))).upper()
    return product_code == "HL001" or category == "RESIDENTIAL_MORTGAGES"


def is_student_loan_account(account: dict[str, Any]) -> bool:
    product_code = trim(account.get("productCode")).upper()
    return product_code == "SL001"


def is_loan_interest_account(account: dict[str, Any]) -> bool:
    return is_home_loan_account(account) or is_student_loan_account(account)


def matches_loan_work_target(account: dict[str, Any], payload: dict[str, Any]) -> bool:
    if not is_loan_interest_account(account):
        return False
    account_name = trim(payload.get("accountName")) or trim(payload.get("name"))
    account_id = trim(payload.get("accountId")) or trim(payload.get("id"))
    product_code = trim(payload.get("productCode"))
    if account_id and account_id not in {trim(account.get("id")), trim(account.get("accountId"))}:
        return False
    if account_name and not matches_account_reference(account, account_name):
        return False
    return not product_code or product_code.upper() == trim(account.get("productCode")).upper()


def matches_account_reference(account: dict[str, Any], account_reference: str) -> bool:
    reference = trim(account_reference)
    if not reference:
        return False
    return reference in {
        trim(account.get("id")),
        trim(account.get("accountId")),
        trim(account.get("name")),
        trim(account.get("productCode")),
    }


def has_credit_card_posting(
    postings: list[dict[str, Any]],
    occurrence_key: str,
    event_type: str,
    account: dict[str, Any],
) -> bool:
    if not occurrence_key:
        return False
    for posting in postings:
        if (
            trim(posting.get("occurrenceKey")) == occurrence_key
            and trim(posting.get("eventType")) == event_type
            and posting_matches_account(posting, account)
        ):
            return True
    return False


def posting_matches_account(posting: dict[str, Any], account: dict[str, Any]) -> bool:
    candidates = {trim(account.get("accountId")), trim(account.get("id"))}
    posting_ids = {trim(posting.get("accountId")), trim(posting.get("runtimeAccountId"))}
    return any(posting_id and posting_id in candidates for posting_id in posting_ids)


def has_credit_card_bill(bills: list[dict[str, Any]], occurrence_key: str, account: dict[str, Any]) -> bool:
    if not occurrence_key:
        return False
    for bill in bills:
        if trim(bill.get("occurrenceKey")) == occurrence_key and posting_matches_account(bill, account):
            return True
    return False


def safe_bill_id(occurrence_key: str, account: dict[str, Any]) -> str:
    return safe_token(f"{occurrence_key or 'credit-card-statement'}-{trim(account.get('id'))}")


def can_debit(account: dict[str, Any], amount: Decimal) -> bool:
    balance = Decimal(str(account.get("balance", 0)))
    limit = Decimal(str(account.get("limit", 0)))
    return balance - amount >= -abs(limit)


def product_catalog(accounts: list[dict[str, Any]] | None = None, product_category: Any = None) -> list[dict[str, Any]]:
    products = {normalize_token(product["productCode"]): dict(product) for product in DEFAULT_PRODUCT_CATALOG}
    for account in accounts or []:
        product_code = trim(account.get("productCode"))
        if product_code:
            products.setdefault(
                normalize_token(product_code),
                {
                    "productCode": product_code,
                    "name": trim(account.get("name")) or product_code,
                    "productCategory": "REMOTE_ACCOUNT_PRODUCT",
                    "category": "REMOTE_ACCOUNT_PRODUCT",
                },
            )
    normalized_category = normalize_token(product_category)
    values = list(products.values())
    if not normalized_category:
        return values
    return [
        product
        for product in values
        if normalized_category == normalize_token(product.get("productCategory"))
        or normalized_category == normalize_token(product.get("category"))
    ]


def tool_result(tool_name: str, success: bool, message: str, values: dict[str, Any]) -> dict[str, Any]:
    return {
        "toolName": tool_name or "remote-domain-tool",
        "success": success,
        "message": message,
        **values,
    }


def evidence_event(
    request: dict[str, Any],
    *,
    event_type: str,
    source: str,
    outcome: str,
    payload: dict[str, Any],
    severity: str = "INFO",
) -> dict[str, Any]:
    return {
        "eventId": f"{trim(request.get('requestId'))}:{event_type}",
        "category": "domain",
        "eventType": event_type,
        "simTimeEpochMs": request.get("sentAtEpochMs"),
        "correlationId": request.get("idempotencyKey") or request.get("requestId"),
        "source": source,
        "outcome": outcome,
        "severity": severity,
        "payload": payload,
    }


def control_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "presetId": {"type": "string", "title": "Preset", "default": WIND_TUNNEL_PRESET_ID},
            "customerCount": {"type": "integer", "title": "Customer count", "minimum": 1, "default": 100},
            "hardshipRequestRate": {
                "type": "integer",
                "title": "Hardship request rate",
                "minimum": 0,
                "maximum": 100,
                "default": 18,
            },
            "candidateReliefRate": {
                "type": "integer",
                "title": "Candidate relief rate",
                "minimum": 0,
                "maximum": 100,
                "default": 72,
            },
            "reviewCapacity": {"type": "integer", "title": "Review capacity", "minimum": 0, "default": 12},
            "averageExposure": {"type": "integer", "title": "Average exposure", "minimum": 0, "default": 8500},
            "baselineRate": {"type": "number", "title": "Baseline rate", "minimum": 0, "maximum": 1, "default": 0.03},
            "candidateRate": {"type": "number", "title": "Candidate rate", "minimum": 0, "maximum": 1, "default": 0.08},
        },
        "required": [
            "presetId",
            "customerCount",
            "hardshipRequestRate",
            "candidateReliefRate",
            "reviewCapacity",
            "baselineRate",
            "candidateRate",
        ],
    }


def ui_schema() -> dict[str, Any]:
    return {
        "layout": "preset-first",
        "fields": [
            {"name": "presetId", "widget": "preset-select", "label": "Preset"},
            {"name": "customerCount", "widget": "number", "label": "Customers"},
            {"name": "hardshipRequestRate", "widget": "slider", "label": "Hardship requests %"},
            {"name": "candidateReliefRate", "widget": "slider", "label": "Relief approval %"},
            {"name": "reviewCapacity", "widget": "number", "label": "Review capacity"},
            {"name": "averageExposure", "widget": "number", "label": "Average exposure"},
            {"name": "baselineRate", "widget": "number", "label": "Baseline rate", "unit": "decimal"},
            {"name": "candidateRate", "widget": "number", "label": "Candidate rate", "unit": "decimal"},
        ],
    }


def outcome_schema() -> dict[str, Any]:
    return {
        "schemaVersion": "domain_outcome_schema.v1",
        "outcomeEnvelope": "domain_outcome_score.v1",
        "actorPlural": "customers",
        "policyCategories": ["deposit-rate", "credit-limit", "hardship", "fraud"],
        "recommendations": ["LAUNCH", "WATCH", "BLOCK", "REVIEW"],
        "metricDefinitions": [
            {"metricId": "simulatedSubjects", "label": "Simulated customers", "unit": "count", "evidence": "cohort"},
            {"metricId": "impactedSubjects", "label": "Impacted customers", "unit": "count", "evidence": "branch-state"},
            {"metricId": "exposure", "label": "Balance or limit exposure", "unit": "money", "evidence": "domain-state"},
            {
                "metricId": "thresholdBreaches",
                "label": "Policy threshold breaches",
                "unit": "count",
                "evidence": "decision-gates",
            },
            {
                "metricId": "outcomeDelta",
                "label": "Customer outcome delta",
                "unit": "domain-defined",
                "evidence": "metric-trace",
            },
            {
                "metricId": "counterMetricDelta",
                "label": "Business counter-metric delta",
                "unit": "domain-defined",
                "evidence": "metric-trace",
            },
        ],
    }


def customer_impact_scorecard() -> dict[str, Any]:
    scorecard = build_domain_outcome_scorecard(
        scorecard_id="banking.customer-impact.v1",
        label="Banking customer-impact scorecard",
        version="0.1.0",
        metrics=[
            build_outcome_score_metric(
                metric_id="activatedSyntheticCustomers",
                label="Activated synthetic customers",
                value_expression="metrics.cohortSize",
                evidence_ref="simulationRunEvidencePack",
                description="Number of synthetic customers included in this decision run.",
            ),
            build_outcome_score_metric(
                metric_id="runtimeEvidenceSignals",
                label="Runtime evidence signals",
                value_expression="metrics.evidenceSignalCount",
                evidence_ref="simulationRunEvidencePack",
                description="Core-mediated evidence signals captured for audit.",
            ),
            build_outcome_score_metric(
                metric_id="affectedStateChanges",
                label="Affected state changes",
                value_expression="metrics.stateChangeCount",
                evidence_ref="branchStateProjectionJson",
                description="Domain state changes projected or observed during the run.",
            ),
            build_outcome_score_metric(
                metric_id="rejectedWorkItems",
                label="Rejected work items",
                value_expression="metrics.rejectedWorkCount",
                evidence_ref="branchStateProjectionJson",
                description="Domain work items that were rejected or failed validation.",
            ),
        ],
        gates=[
            build_outcome_score_gate(
                gate_id="banking.evidence.runtime-linked",
                label="Runtime evidence linked",
                expression="signals.hasRuntimeEvidence && metrics.evidenceSignalCount > 0",
                evidence_ref="simulationRunEvidencePack",
                description="The run has durable runtime evidence.",
            ),
            build_outcome_score_gate(
                gate_id="banking.evidence.state-linked",
                label="State evidence linked",
                expression="signals.hasStateEvidence && metrics.stateChangeCount >= 0",
                evidence_ref="branchStateProjectionJson",
                description="The run has branch state projection evidence.",
            ),
        ],
        recommendation_rules=[
            build_outcome_recommendation_rule(
                recommendation="BLOCK",
                expression="metrics.rejectedWorkCount >= 25",
                evidence_ref="branchStateProjectionJson",
                reason="Too many rejected domain actions to treat the candidate as launch-ready.",
            ),
            build_outcome_recommendation_rule(
                recommendation="WATCH",
                expression="metrics.rejectedWorkCount > 0 || metrics.stateChangeCount > 0",
                evidence_ref="branchStateProjectionJson",
                reason="The candidate produced observable domain impact and needs human review.",
            ),
            build_outcome_recommendation_rule(
                recommendation="REVIEW",
                expression="true",
                evidence_ref="simulationRunEvidencePack",
                reason="Evidence is captured, but the domain scorecard does not claim an automatic launch decision.",
            ),
        ],
        limitations=[
            "This is stress-test evidence under configured assumptions, not a production forecast.",
            "Provider-internal banking actions require emitted evidence events to be scored.",
        ],
    )
    scorecard["appliesTo"] = {
        "domainId": DOMAIN_ID,
        "packId": WIND_TUNNEL_PACK_ID,
        "executionModes": ["CLOCK_DRIVEN_HYBRID"],
        "presets": ["*"],
    }
    scorecard["headlineClaims"] = [
        "Scores are based on replay-linked runtime evidence and banking state projections.",
        "A WATCH recommendation means banking reviewers should inspect affected customers before launch.",
    ]
    return scorecard


def evidence_readiness_scorecard() -> dict[str, Any]:
    scorecard = build_domain_outcome_scorecard(
        scorecard_id="banking.evidence-readiness.v1",
        label="Banking evidence-readiness scorecard",
        version="0.1.0",
        metrics=[
            build_outcome_score_metric(
                metric_id="timelineEvents",
                label="Timeline events",
                value_expression="metrics.timelineEventCount",
                evidence_ref="simulationTimelineJson",
                description="Replayable simulated-time events captured for the run.",
            ),
            build_outcome_score_metric(
                metric_id="stateChanges",
                label="Domain state changes",
                value_expression="metrics.stateChangeCount",
                evidence_ref="branchStateProjectionJson",
                description="Projected or observed domain state changes linked to the run.",
            ),
            build_outcome_score_metric(
                metric_id="evidenceSignals",
                label="Evidence signals",
                value_expression="metrics.evidenceSignalCount",
                evidence_ref="simulationRunEvidencePack",
                description="Total platform-mediated evidence signals available for review.",
            )
        ],
        gates=[
            build_outcome_score_gate(
                gate_id="banking.evidence.minimum-signal-volume",
                label="Minimum evidence volume",
                expression="metrics.evidenceSignalCount >= 1",
                evidence_ref="simulationRunEvidencePack",
                description="The run captured at least one durable evidence signal.",
            ),
            build_outcome_score_gate(
                gate_id="banking.evidence.timeline-linked",
                label="Timeline linked",
                expression="metrics.timelineEventCount >= 1",
                evidence_ref="simulationTimelineJson",
                description="The run has a replayable timeline.",
            )
        ],
        recommendation_rules=[
            build_outcome_recommendation_rule(
                recommendation="BLOCK",
                expression="metrics.evidenceSignalCount == 0",
                evidence_ref="simulationRunEvidencePack",
                reason="No evidence signals were captured, so the report cannot support a decision.",
            ),
            build_outcome_recommendation_rule(
                recommendation="WATCH",
                expression="metrics.evidenceSignalCount > 0",
                evidence_ref="simulationRunEvidencePack",
                reason="Evidence exists and should be reviewed before any launch decision.",
            ),
            build_outcome_recommendation_rule(
                recommendation="REVIEW",
                expression="true",
                evidence_ref="simulationRunEvidencePack",
                reason="Evidence readiness could not produce a stronger recommendation.",
            ),
        ],
        limitations=[
            "This scorecard validates evidence availability, not banking business risk.",
            "Use it alongside a customer-impact scorecard before making product decisions.",
        ],
    )
    scorecard["appliesTo"] = {
        "domainId": DOMAIN_ID,
        "packId": WIND_TUNNEL_PACK_ID,
        "executionModes": ["CLOCK_DRIVEN_HYBRID"],
        "presets": ["*"],
    }
    scorecard["headlineClaims"] = [
        "Evidence readiness only scores whether the run is reviewable.",
        "It does not score customer harm or launch safety.",
    ]
    return scorecard


def normalized_wind_tunnel_parameters(payload: dict[str, Any], preset_id: str) -> dict[str, Any]:
    defaults = dict(WIND_TUNNEL_PRESET_DEFAULTS.get(
        preset_id,
        WIND_TUNNEL_PRESET_DEFAULTS[WIND_TUNNEL_PRESET_ID],
    ))
    execution_mode = trim(payload.get("executionMode")).upper().replace("-", "_")
    if execution_mode != EXECUTION_MODE_CLOCK_DRIVEN_HYBRID:
        execution_mode = EXECUTION_MODE_FAST_BATCH_COMPARE
    cohort_selection = dict(payload.get("cohortSelection", {}) or {})
    cohort_id = trim(payload.get("cohortId")) or trim(cohort_selection.get("cohortId"))
    agent_decision_mode = normalize_agent_decision_mode(payload.get("agentDecisionMode"))
    normalized = {
        "presetId": preset_id,
        "customerCount": int_or_default(payload.get("customerCount"), defaults["customerCount"]),
        "hardshipRequestRate": int_or_default(payload.get("hardshipRequestRate"), defaults["hardshipRequestRate"]),
        "candidateReliefRate": int_or_default(payload.get("candidateReliefRate"), defaults["candidateReliefRate"]),
        "reviewCapacity": int_or_default(payload.get("reviewCapacity"), defaults["reviewCapacity"]),
        "averageExposure": int_or_default(payload.get("averageExposure"), defaults["averageExposure"]),
        "baselineRate": float_or_default(payload.get("baselineRate"), defaults.get("baselineRate", 0.03)),
        "candidateRate": float_or_default(payload.get("candidateRate"), defaults.get("candidateRate", 0.08)),
        "executionMode": execution_mode,
        "agentDecisionMode": agent_decision_mode,
        "runPath": trim(payload.get("runPath")),
        "compareMode": trim(payload.get("compareMode")) or "SEEDED_DETERMINISTIC",
        "simulationHorizonDays": int_or_default(payload.get("simulationHorizonDays"), 30),
        "prePolicyDays": int_or_default(payload.get("prePolicyDays"), 10),
        "postPolicyDays": int_or_default(
            payload.get("postPolicyDays"),
            max(
                int_or_default(payload.get("simulationHorizonDays"), 30)
                - int_or_default(payload.get("prePolicyDays"), 10),
                0,
            ),
        ),
        "simulationStartEpochMs": normalize_simulation_start_epoch_ms(payload.get("simulationStartEpochMs")),
        "playbackRate": float_or_default(payload.get("playbackRate"), 0.0),
        "realLlmOptIn": bool(payload.get("realLlmOptIn", False)),
        "realLlmBudget": real_llm_budget(payload) if agent_decision_mode == AGENT_DECISION_MODE_REAL_LLM else {},
        "llmSamplingPolicy": dict(payload.get("llmSamplingPolicy", {}) or {}),
        "cohortTargetSize": int_or_default(payload.get("cohortTargetSize"), int_or_default(payload.get("customerCount"), defaults["customerCount"])),
        "productCode": trim(payload.get("productCode")) or trim(defaults.get("productCode")) or DEFAULT_PRODUCT_CODE,
        "providerLanguage": "python",
    }
    if FINALIZATION_MODE_FIELD in payload:
        normalized[FINALIZATION_MODE_FIELD] = validated_finalization_mode(
            payload.get(FINALIZATION_MODE_FIELD)
        )
    simulation_run_id = trim(payload.get("simulationRunId"))
    if simulation_run_id:
        normalized["simulationRunId"] = validated_platform_run_id(simulation_run_id)
    activation_id = trim(payload.get("activationId"))
    if activation_id:
        normalized["activationId"] = activation_id
    for pinned_field in (
        "cohortArtifactDigest",
        "cohortAgentIdentitySetDigest",
        "cohortArtifactSnapshot",
        "activationSettingsDigest",
        "activationSettingsSnapshot",
    ):
        if pinned_field in payload:
            normalized[pinned_field] = payload[pinned_field]
    if (
        agent_decision_mode in {AGENT_DECISION_MODE_STUB_LLM, AGENT_DECISION_MODE_REAL_LLM}
        or "agentDecisionCadence" in payload
    ):
        cadence = normalize_agent_decision_cadence(payload.get("agentDecisionCadence"))
        normalized["agentDecisionCadence"] = cadence
        normalized["agentDecisionIntervalMinutes"] = agent_decision_interval_minutes(cadence)
    if cohort_id:
        normalized["cohortId"] = cohort_id
        normalized["cohortSelection"] = cohort_selection or {"cohortId": cohort_id}
    return normalized


def validated_finalization_mode(value: Any) -> str:
    mode = value if isinstance(value, str) else ""
    if mode not in {
        FINALIZATION_MODE_LEGACY_V1,
        FINALIZATION_MODE_PLATFORM_OWNED_V1,
        FINALIZATION_MODE_PLATFORM_OWNED_V2,
    }:
        raise ProtocolError(
            400,
            "invalid_opportunity_generation_finalization_mode",
            "invalid_request",
            "opportunityGenerationFinalizationMode must be LEGACY_V1, PLATFORM_OWNED_V1, or PLATFORM_OWNED_V2.",
            operation="policy-wind-tunnel-run",
        )
    return mode


def require_supported_launch_finalization_mode(parameters: dict[str, Any]) -> None:
    raw_mode = parameters.get(FINALIZATION_MODE_FIELD)
    clock_driven = parameters.get("executionMode") == EXECUTION_MODE_CLOCK_DRIVEN_HYBRID
    if raw_mode is None or raw_mode == "":
        if clock_driven:
            raise ProtocolError(
                400,
                "platform_owned_v2_finalization_required",
                "invalid_request",
                "CLOCK_DRIVEN_HYBRID requires an explicitly pinned PLATFORM_OWNED_V2 finalization mode.",
                operation="policy-wind-tunnel-run",
            )
        raw_mode = FINALIZATION_MODE_LEGACY_V1
    mode = validated_finalization_mode(raw_mode)
    parameters[FINALIZATION_MODE_FIELD] = mode
    cadence = trim(parameters.get("agentDecisionCadence")).upper()
    if mode == FINALIZATION_MODE_PLATFORM_OWNED_V2 and not platform_owned_v2_storage_ready():
        raise ProtocolError(
            503,
            "platform_owned_v2_storage_unavailable",
            "invalid_request",
            "PLATFORM_OWNED_V2 launch requires verified shared durable provider storage.",
            operation="policy-wind-tunnel-run",
        )
    if clock_driven and mode != FINALIZATION_MODE_PLATFORM_OWNED_V2:
        raise ProtocolError(
            400,
            "platform_owned_v2_finalization_required",
            "invalid_request",
            "New CLOCK_DRIVEN_HYBRID runs require PLATFORM_OWNED_V2 finalization; V1 modes are recovery-only.",
            operation="policy-wind-tunnel-run",
        )
    if cadence != AGENT_DECISION_CADENCE_EVENT_ONLY:
        return
    if (
        not clock_driven
        or mode != FINALIZATION_MODE_PLATFORM_OWNED_V2
    ):
        raise ProtocolError(
            400,
            "event_only_finalization_lifecycle_required",
            "invalid_request",
            "EVENT_ONLY requires CLOCK_DRIVEN_HYBRID with PLATFORM_OWNED_V2 finalization.",
            operation="policy-wind-tunnel-run",
        )


def normalize_simulation_start_epoch_ms(value: Any) -> int:
    if value is None:
        return int(time.time() * 1000)
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise ProtocolError(
            400,
            "invalid_simulation_start_epoch_ms",
            "invalid_request",
            "simulationStartEpochMs must be a JSON number.",
            operation="policy-wind-tunnel-run",
        )
    if isinstance(value, float) and (
        not math.isfinite(value) or abs(value) > 9_007_199_254_740_991
    ):
        raise ProtocolError(
            400,
            "invalid_simulation_start_epoch_ms",
            "invalid_request",
            "simulationStartEpochMs decimal/exponent values must be within the exact IEEE-754 integer range.",
            operation="policy-wind-tunnel-run",
        )
    decimal_value = Decimal(str(value))
    if (
        not decimal_value.is_finite()
        or decimal_value != decimal_value.to_integral_value()
        or decimal_value <= 0
        or decimal_value > 9_223_372_036_854_775_807
    ):
        raise ProtocolError(
            400,
            "invalid_simulation_start_epoch_ms",
            "invalid_request",
            "simulationStartEpochMs must be a positive signed-64-bit epoch-millisecond integer.",
            operation="policy-wind-tunnel-run",
        )
    return int(decimal_value)


def validated_platform_run_id(value: Any) -> str:
    run_id = trim(value)
    if len(run_id) > 255 or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]*", run_id) is None:
        raise ProtocolError(
            400,
            "invalid_simulation_run_id",
            "invalid_request",
            "simulationRunId is not a valid platform run identifier.",
            operation="policy-wind-tunnel-run",
        )
    return run_id


def simulation_start_epoch_ms(parameters: dict[str, Any]) -> int:
    stored = int_or_default(parameters.get("simulationStartEpochMs"), DEFAULT_SIMULATION_START_EPOCH_MS)
    return stored if stored > 0 else DEFAULT_SIMULATION_START_EPOCH_MS


def wind_tunnel_metrics(parameters: dict[str, Any]) -> dict[str, int]:
    return evaluate_fast_batch_kpis(parameters)


def new_policy_run(
    run_id: str,
    preset_id: str,
    parameters: dict[str, Any],
    *,
    status: str,
    phase: str,
    progress: float,
    bundle_ready: bool,
    log_tail: list[str],
) -> dict[str, Any]:
    now = utc_now_iso()
    return {
        "runId": run_id,
        "presetId": preset_id,
        "parameters": dict(parameters),
        "status": status,
        "phase": phase,
        "progress": progress,
        "bundleReady": bundle_ready,
        "startedAt": now,
        "updatedAt": now,
        "phaseTimings": start_phase_timing(phase),
        "logTail": list(log_tail),
        "error": {},
        "bundleUrl": "",
        "evidencePackUrl": "",
    }


def update_policy_run(
    run_id: str,
    *,
    status: str | None = None,
    phase: str | None = None,
    progress: float | None = None,
    bundle_ready: bool | None = None,
    error: dict[str, Any] | None = None,
    paused_from_phase: str | None = None,
    fields: dict[str, Any] | None = None,
    remove_fields: set[str] | None = None,
    increment_fields: dict[str, int] | None = None,
) -> None:
    policy_run_record(run_id)
    with _POLICY_WIND_TUNNEL_LOCK:
        with policy_run_state_lock(run_id):
            run = load_policy_run_state_unlocked(run_id)
            if run is None:
                run = deep_json_copy(_POLICY_WIND_TUNNEL_RUNS.get(run_id, {}))
            if not run:
                raise ProtocolError(
                    404,
                    "unknown_policy_wind_tunnel_run",
                    "not_found",
                    f"Unknown Policy Wind Tunnel runId: {run_id}",
                    operation="policy-wind-tunnel-status",
                    details={"runId": run_id},
                )
            require_current_policy_execution_claim_unlocked(run_id, run)
            previous_phase = trim(run.get("phase")) or "UNKNOWN"
            next_phase = trim(phase) or previous_phase
            if phase:
                run["phaseTimings"] = transition_phase_timings(run.get("phaseTimings", []), previous_phase, next_phase)
                run["phase"] = next_phase
                if next_phase != "PAUSED":
                    run.pop("pausedFromPhase", None)
            if paused_from_phase is not None:
                if trim(paused_from_phase):
                    run["pausedFromPhase"] = trim(paused_from_phase)
                else:
                    run.pop("pausedFromPhase", None)
            if status:
                run["status"] = status
            if progress is not None:
                run["progress"] = max(0.0, min(1.0, float(progress)))
            if bundle_ready is not None:
                run["bundleReady"] = bool(bundle_ready)
            if error is not None:
                run["error"] = dict(error)
            for key, value in (fields or {}).items():
                run[key] = deep_json_copy(value)
            for key in remove_fields or set():
                run.pop(key, None)
            for key, delta in (increment_fields or {}).items():
                run[key] = int_or_default(run.get(key), 0) + int(delta)
            run["updatedAt"] = utc_now_iso()
            write_policy_run_state_unlocked(run_id, run)
            _POLICY_WIND_TUNNEL_RUNS[run_id] = run


def append_policy_log(run_id: str, line: str) -> None:
    policy_run_record(run_id)
    with _POLICY_WIND_TUNNEL_LOCK:
        with policy_run_state_lock(run_id):
            run = load_policy_run_state_unlocked(run_id) or deep_json_copy(
                _POLICY_WIND_TUNNEL_RUNS[run_id]
            )
            require_current_policy_execution_claim_unlocked(run_id, run)
            log_tail = list(run.get("logTail", []))
            log_tail.append(line)
            run["logTail"] = log_tail[-40:]
            run["updatedAt"] = utc_now_iso()
            write_policy_run_state_unlocked(run_id, run)
            _POLICY_WIND_TUNNEL_RUNS[run_id] = run


def append_policy_log_once(run_id: str, line: str) -> None:
    policy_run_record(run_id)
    with _POLICY_WIND_TUNNEL_LOCK:
        with policy_run_state_lock(run_id):
            run = load_policy_run_state_unlocked(run_id) or deep_json_copy(
                _POLICY_WIND_TUNNEL_RUNS[run_id]
            )
            require_current_policy_execution_claim_unlocked(run_id, run)
            log_tail = list(run.get("logTail", []))
            if line in log_tail:
                return
            log_tail.append(line)
            run["logTail"] = log_tail[-40:]
            run["updatedAt"] = utc_now_iso()
            write_policy_run_state_unlocked(run_id, run)
            _POLICY_WIND_TUNNEL_RUNS[run_id] = run


def start_phase_timing(phase: str) -> list[dict[str, Any]]:
    now = utc_now_iso()
    timing = {"phase": phase or "UNKNOWN", "status": "RUNNING", "startedAt": now}
    if is_terminal_phase(phase):
        close_phase_timing(timing, now)
    return [timing]


def transition_phase_timings(existing: Any, previous_phase: str, next_phase: str) -> list[dict[str, Any]]:
    now = utc_now_iso()
    normalized_next = next_phase or previous_phase or "UNKNOWN"
    timings: list[dict[str, Any]] = []
    has_open_next = False
    for raw in existing if isinstance(existing, list) else []:
        if not isinstance(raw, dict):
            continue
        timing = dict(raw)
        timing_phase = trim(timing.get("phase")) or previous_phase or "UNKNOWN"
        completed = bool(trim(timing.get("completedAt")))
        if not completed and timing_phase != normalized_next:
            close_phase_timing(timing, now)
        if not completed and timing_phase == normalized_next:
            has_open_next = True
        timings.append(timing)
    if not has_open_next:
        next_timing = {"phase": normalized_next, "status": "RUNNING", "startedAt": now}
        if is_terminal_phase(normalized_next):
            close_phase_timing(next_timing, now)
        timings.append(next_timing)
    elif is_terminal_phase(normalized_next):
        for timing in timings:
            if trim(timing.get("phase")) == normalized_next and not trim(timing.get("completedAt")):
                close_phase_timing(timing, now)
    return timings


def close_phase_timing(timing: dict[str, Any], completed_at: str) -> None:
    timing["completedAt"] = completed_at
    timing["status"] = "COMPLETED"
    try:
        started = datetime.fromisoformat(trim(timing.get("startedAt")).replace("Z", "+00:00"))
        completed = datetime.fromisoformat(completed_at.replace("Z", "+00:00"))
        timing["durationMs"] = max(0, int((completed - started).total_seconds() * 1000))
    except Exception:
        timing["durationMs"] = 0


def is_terminal_phase(phase: Any) -> bool:
    return trim(phase).upper() in {"COMPLETED", "FAILED", "CANCELLED"}


def policy_run_is_terminal(run_id: str) -> bool:
    return is_terminal_phase(policy_run_record(run_id).get("status"))


def lifecycle_state(run: dict[str, Any]) -> tuple[str, str]:
    return (trim(run.get("status")).upper(), trim(run.get("phase")).upper())


def lifecycle_transition_allows(run: dict[str, Any], transition_name: str) -> bool:
    transition = CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS.get(transition_name)
    if not transition:
        return False
    return lifecycle_state(run) in transition["sources"]


def lifecycle_transition_name(
    expected_status: str,
    expected_phase: str,
    next_status: str,
    next_phase: str,
) -> str:
    source = (expected_status.upper(), expected_phase.upper())
    target = (next_status.upper(), next_phase.upper())
    for name, transition in CLOCK_DRIVEN_LIFECYCLE_TRANSITIONS.items():
        if source in transition["sources"] and transition["target"] == target:
            return name
    return ""


def clock_driven_run_can_pause(run: dict[str, Any]) -> bool:
    return lifecycle_transition_allows(run, TRANSITION_PAUSE)


def clock_driven_run_is_ready_to_finalize(run: dict[str, Any]) -> bool:
    return lifecycle_state(run) == (STATUS_READY_TO_FINALIZE, PHASE_READY_TO_FINALIZE)


def clock_driven_clock_has_started(run: dict[str, Any]) -> bool:
    return trim(run.get("phase")).upper() == "CLOCK_RUNNING"


def clock_driven_run_can_resume(run: dict[str, Any]) -> bool:
    return lifecycle_transition_allows(run, clock_driven_resume_transition(run.get("pausedFromPhase")))


def clock_driven_resume_transition(paused_from_phase: Any) -> str:
    source_phase = trim(paused_from_phase).upper()
    if source_phase == PHASE_CLOCK_RUNNING:
        return TRANSITION_RESUME_CLOCK
    if source_phase in {PHASE_PREPARING_CLOCK, PHASE_ACTIVATING_COHORT}:
        return TRANSITION_RESUME_SETUP
    return ""


def clock_driven_run_can_finalize(run: dict[str, Any]) -> bool:
    return lifecycle_transition_allows(run, TRANSITION_FINALIZE_START) and (
        trim(run.get("status")).upper() != STATUS_PAUSED
        or trim(run.get("pausedFromPhase")).upper() == PHASE_CLOCK_RUNNING
    )


def append_lifecycle_ignored_log(run_id: str, action: str) -> None:
    run = policy_run_record(run_id)
    status = trim(run.get("status")) or "UNKNOWN"
    phase = trim(run.get("phase")) or "UNKNOWN"
    append_policy_log(run_id, f"{action} ignored because run state is {status}/{phase}.")


def is_clock_driven_parameters(parameters: dict[str, Any]) -> bool:
    return trim(parameters.get("executionMode")).upper().replace("-", "_") == EXECUTION_MODE_CLOCK_DRIVEN_HYBRID


def selected_cohort_id(parameters: dict[str, Any]) -> str:
    return trim(parameters.get("cohortId")) or trim(dict(parameters.get("cohortSelection", {}) or {}).get("cohortId"))


def normalize_agent_decision_mode(value: Any) -> str:
    normalized = trim(value).upper().replace("-", "_") or AGENT_DECISION_MODE_DETERMINISTIC
    if normalized == AGENT_DECISION_MODE_STUB_LLM:
        return AGENT_DECISION_MODE_STUB_LLM
    if normalized == AGENT_DECISION_MODE_REAL_LLM:
        return AGENT_DECISION_MODE_REAL_LLM
    return AGENT_DECISION_MODE_DETERMINISTIC


def real_llm_budget(payload: dict[str, Any] | None) -> dict[str, Any]:
    supplied = dict((payload or {}).get("realLlmBudget", {}) or {})
    require_real_llm_canary_model(supplied)
    max_calls = int_or_default(supplied.get("maxCalls"), REAL_LLM_CANARY_MAX_CALLS)
    max_usd = float_or_default(supplied.get("maxUsd"), REAL_LLM_CANARY_MAX_USD)
    max_input_tokens = int_or_default(supplied.get("maxInputTokens"), REAL_LLM_CANARY_MAX_INPUT_TOKENS)
    max_output_tokens = int_or_default(supplied.get("maxOutputTokens"), REAL_LLM_CANARY_MAX_OUTPUT_TOKENS)
    if max_calls <= 0 or max_calls > REAL_LLM_CANARY_MAX_CALLS:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_too_large",
            "invalid_request",
            f"REAL_LLM canary maxCalls must be between 1 and {REAL_LLM_CANARY_MAX_CALLS}.",
            operation="policy-wind-tunnel-run",
        )
    if max_usd <= 0 or max_usd > REAL_LLM_CANARY_MAX_USD:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_too_large",
            "invalid_request",
            f"REAL_LLM canary maxUsd must be between 0 and {REAL_LLM_CANARY_MAX_USD}.",
            operation="policy-wind-tunnel-run",
        )
    if max_input_tokens <= 0 or max_output_tokens <= 0:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_invalid",
            "invalid_request",
            "REAL_LLM canary token budgets must be positive.",
            operation="policy-wind-tunnel-run",
        )
    if max_input_tokens > REAL_LLM_CANARY_MAX_INPUT_TOKENS:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_too_large",
            "invalid_request",
            f"REAL_LLM canary maxInputTokens must be between 1 and {REAL_LLM_CANARY_MAX_INPUT_TOKENS}.",
            operation="policy-wind-tunnel-run",
        )
    if max_output_tokens > REAL_LLM_CANARY_MAX_OUTPUT_TOKENS:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_too_large",
            "invalid_request",
            f"REAL_LLM canary maxOutputTokens must be between 1 and {REAL_LLM_CANARY_MAX_OUTPUT_TOKENS}.",
            operation="policy-wind-tunnel-run",
        )
    return {
        "provider": REAL_LLM_DEFAULT_PROVIDER,
        "model": REAL_LLM_DEFAULT_MODEL,
        "maxCalls": max_calls,
        "maxUsd": max_usd,
        "maxInputTokens": max_input_tokens,
        "maxOutputTokens": max_output_tokens,
        "stopPolicy": trim(supplied.get("stopPolicy")) or "STOP_AT_BUDGET",
    }


def require_real_llm_canary_model(supplied_budget: dict[str, Any] | None) -> None:
    supplied_budget = supplied_budget or {}
    provider = trim(supplied_budget.get("provider")) or REAL_LLM_DEFAULT_PROVIDER
    model = trim(supplied_budget.get("model")) or REAL_LLM_DEFAULT_MODEL
    if provider != REAL_LLM_DEFAULT_PROVIDER or model != REAL_LLM_DEFAULT_MODEL:
        raise ProtocolError(
            400,
            "real_llm_canary_model_not_allowed",
            "invalid_request",
            f"REAL_LLM canary runs are restricted to {REAL_LLM_DEFAULT_PROVIDER}/{REAL_LLM_DEFAULT_MODEL}.",
            operation="policy-wind-tunnel-run",
        )


def expected_decision_opportunities_for_canary(agent_count: int, horizon_days: int, interval_minutes: int) -> int:
    if agent_count <= 0 or horizon_days <= 0 or interval_minutes <= 0:
        return 0
    slots = max(1, (horizon_days * 24 * 60) // interval_minutes)
    return slots * agent_count


def enforce_real_llm_canary(parameters: dict[str, Any], agent_decision_mode: str, agent_count: int) -> None:
    if agent_decision_mode != AGENT_DECISION_MODE_REAL_LLM:
        return
    if parameters.get("realLlmOptIn") is not True:
        raise ProtocolError(
            400,
            "real_llm_opt_in_required",
            "invalid_request",
            "Agent Decision Mode REAL_LLM requires explicit realLlmOptIn=true and budget limits.",
            operation="policy-wind-tunnel-run",
        )
    supplied_budget = dict(parameters.get("realLlmBudget", {}) or {})
    require_real_llm_canary_model(supplied_budget)
    horizon_days = max(1, int_or_default(parameters.get("simulationHorizonDays"), 30))
    cadence = normalize_agent_decision_cadence(parameters.get("agentDecisionCadence"))
    max_calls = int_or_default(supplied_budget.get("maxCalls"), REAL_LLM_CANARY_MAX_CALLS)
    max_usd = float_or_default(supplied_budget.get("maxUsd"), REAL_LLM_CANARY_MAX_USD)
    max_input_tokens = int_or_default(supplied_budget.get("maxInputTokens"), REAL_LLM_CANARY_MAX_INPUT_TOKENS)
    max_output_tokens = int_or_default(supplied_budget.get("maxOutputTokens"), REAL_LLM_CANARY_MAX_OUTPUT_TOKENS)

    if agent_count > REAL_LLM_CANARY_MAX_CUSTOMERS:
        raise ProtocolError(
            400,
            "real_llm_canary_cohort_too_large",
            "invalid_request",
            f"REAL_LLM canary runs are capped at {REAL_LLM_CANARY_MAX_CUSTOMERS} customers. Use Stub LLM for full-scale E27 proof.",
            operation="policy-wind-tunnel-run",
        )
    if horizon_days > REAL_LLM_CANARY_MAX_HORIZON_DAYS:
        raise ProtocolError(
            400,
            "real_llm_canary_horizon_too_large",
            "invalid_request",
            f"REAL_LLM canary runs are capped at {REAL_LLM_CANARY_MAX_HORIZON_DAYS} simulated days by the banking domain policy.",
            operation="policy-wind-tunnel-run",
        )
    if cadence == AGENT_DECISION_CADENCE_HOURLY:
        raise ProtocolError(
            400,
            "real_llm_canary_hourly_rejected",
            "invalid_request",
            "REAL_LLM canary runs must use EVENT_ONLY, DAILY, or WEEKLY cadence; hourly real-token loops are not product-safe.",
            operation="policy-wind-tunnel-run",
        )
    if max_calls <= 0 or max_calls > REAL_LLM_CANARY_MAX_CALLS:
        raise ProtocolError(
            400,
            "real_llm_canary_call_cap_invalid",
            "invalid_request",
            f"REAL_LLM canary maxCalls must be between 1 and {REAL_LLM_CANARY_MAX_CALLS}.",
            operation="policy-wind-tunnel-run",
        )
    if max_usd <= 0 or max_usd > REAL_LLM_CANARY_MAX_USD:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_invalid",
            "invalid_request",
            f"REAL_LLM canary maxUsd must be between 0 and {REAL_LLM_CANARY_MAX_USD}.",
            operation="policy-wind-tunnel-run",
        )
    if max_input_tokens <= 0 or max_output_tokens <= 0:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_invalid",
            "invalid_request",
            "REAL_LLM canary token budgets must be positive.",
            operation="policy-wind-tunnel-run",
        )
    if max_input_tokens > REAL_LLM_CANARY_MAX_INPUT_TOKENS:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_invalid",
            "invalid_request",
            f"REAL_LLM canary maxInputTokens must be between 1 and {REAL_LLM_CANARY_MAX_INPUT_TOKENS}.",
            operation="policy-wind-tunnel-run",
        )
    if max_output_tokens > REAL_LLM_CANARY_MAX_OUTPUT_TOKENS:
        raise ProtocolError(
            400,
            "real_llm_canary_budget_invalid",
            "invalid_request",
            f"REAL_LLM canary maxOutputTokens must be between 1 and {REAL_LLM_CANARY_MAX_OUTPUT_TOKENS}.",
            operation="policy-wind-tunnel-run",
        )

def requested_playback_rate(parameters: dict[str, Any], override_payload: dict[str, Any]) -> float:
    default_rate = playback_rate_policy_max(parameters)
    if "playbackRate" in override_payload:
        requested = positive_playback_rate_or_zero(override_payload.get("playbackRate"))
        if requested > 0:
            return requested
    stored = positive_playback_rate_or_zero(parameters.get("playbackRate"))
    return stored if stored > 0 else default_rate


def positive_playback_rate_or_zero(value: Any) -> float:
    parsed = float_or_default(value, 0.0)
    return parsed if math.isfinite(parsed) and parsed > 0 else 0.0


def negotiated_playback_rate(parameters: dict[str, Any], override_payload: dict[str, Any]) -> float:
    requested = requested_playback_rate(parameters, override_payload)
    mode_max = playback_rate_policy_max(parameters)
    effective_max = min(mode_max, simulator_max_playback_rate())
    return max(HYBRID_MIN_PLAYBACK_RATE, min(requested, effective_max))


def playback_rate_policy_max(parameters: dict[str, Any]) -> float:
    agent_decision_mode = normalize_agent_decision_mode(parameters.get("agentDecisionMode"))
    if agent_decision_mode == AGENT_DECISION_MODE_REAL_LLM:
        return HYBRID_REAL_LLM_MAX_PLAYBACK_RATE
    cadence = normalize_agent_decision_cadence(parameters.get("agentDecisionCadence"))
    if cadence == AGENT_DECISION_CADENCE_EVENT_ONLY:
        return HYBRID_NO_TOKEN_EVENT_ONLY_PLAYBACK_RATE
    return HYBRID_FAST_PLAYBACK_RATE


def simulator_max_playback_rate() -> float:
    try:
        payload = get_json(simulator_url() + "/api/clock/rate/info")
        if isinstance(payload, dict):
            max_rate = float_or_default(payload.get("max"), HYBRID_FAST_PLAYBACK_RATE)
            return max_rate if max_rate > 0 else HYBRID_FAST_PLAYBACK_RATE
    except Exception:
        return HYBRID_FAST_PLAYBACK_RATE
    return HYBRID_FAST_PLAYBACK_RATE


def apply_hybrid_replay_contract(
    run_id: str,
    parameters: dict[str, Any],
    playback_rate: float,
    resume_source_phase: str | None = None,
) -> bool:
    preset_id = trim(parameters.get("presetId")) or WIND_TUNNEL_PRESET_ID
    for expected_run_id in replay_claim_expected_run_ids(run_id, resume_source_phase):
        replay = {
            "seed": HYBRID_REPLAY_SEED,
            "simulationStartEpochMs": simulation_start_epoch_ms(parameters),
            "playback": {"rate": 0.0, "paused": True},
            "deterministic": {"enabled": True, "resetStateOnClockRewind": True, "strictClock": True},
            "executionContext": {
                "experimentId": "banking-python-scenario-lab",
                "runId": run_id,
                "scenarioId": "banking-python-policy-compare",
                "branchType": "CANDIDATE",
                "compareMode": trim(parameters.get("compareMode")) or "SEEDED_DETERMINISTIC",
                "presetId": preset_id,
                "startedAtEpochMs": int(time.time() * 1000),
            },
        }
        if expected_run_id is not None:
            replay["expectedRunId"] = expected_run_id
        try:
            post_json(simulator_url() + "/api/clock/replay", replay)
            append_policy_log(run_id, "Applied deterministic replay contract to simulator clock.")
            return True
        except RuntimeError as exc:
            if "status 409" in str(exc):
                continue
            raise
    append_policy_log(
        run_id,
        "Replay contract was not applied because simulator replay ownership was neither empty nor owned by this run.",
    )
    return False


def replay_claim_expected_run_ids(run_id: str, resume_source_phase: str | None) -> list[str | None]:
    source = trim(resume_source_phase).upper()
    if not source:
        return [""]
    if source == "PREPARING_CLOCK":
        return [run_id, ""]
    return [run_id]


def release_hybrid_replay_contract(run_id: str) -> None:
    try:
        post_json(
            simulator_url() + "/api/clock/replay",
            {
                "expectedRunId": run_id,
                "playback": {"paused": True, "rate": 0.0},
                "executionContext": {},
            },
        )
        append_policy_log(run_id, "Released Scenario Lab simulator clock ownership.")
    except RuntimeError as exc:
        if "status 409" in str(exc):
            append_policy_log(
                run_id,
                "Simulator clock ownership release requested. Simulator playback was not changed because replay ownership changed before the simulator accepted the update.",
            )
            return
        raise


def clear_hybrid_runtime_cohort(run_id: str) -> None:
    try:
        clear_active_agents(run_id)
        append_policy_log(run_id, "Cleared active Scenario Lab runtime cohort.")
    except Exception as exc:  # pragma: no cover - depends on runtime availability
        append_policy_log(run_id, "Active runtime cohort cleanup was not confirmed: " + (str(exc) or exc.__class__.__name__))


def start_simulator_clock_after_activation(run_id: str, playback_rate: float) -> bool:
    with _POLICY_WIND_TUNNEL_LOCK:
        if not clock_driven_run_ready_to_start(run_id):
            append_clock_driven_start_stopped_log(run_id, "before starting simulator clock")
            return False
        if not apply_playback_control_for_run(
            run_id,
            paused=False,
            playback_rate=playback_rate,
            action_label="Start requested",
        ):
            return False
        if not clock_driven_run_ready_to_start(run_id):
            append_clock_driven_start_stopped_log(run_id, "after simulator accepted start")
            apply_playback_control_for_run(
                run_id,
                paused=True,
                playback_rate=0.0,
                action_label="Start rollback requested",
            )
            return False
        update_policy_run(run_id, status="RUNNING", phase="CLOCK_RUNNING", progress=0.55, bundle_ready=True)
        return True


def stop_if_clock_driven_activation_should_stop(run_id: str, checkpoint: str) -> bool:
    if clock_driven_run_ready_to_start(run_id):
        return False
    append_clock_driven_start_stopped_log(run_id, checkpoint)
    return True


def ensure_clock_driven_preparing_phase(run_id: str) -> bool:
    with _POLICY_WIND_TUNNEL_LOCK:
        if clock_driven_worker_state_matches(run_id, "RUNNING", "PREPARING_CLOCK"):
            return True
        if lifecycle_transition_allows(policy_run_record(run_id), TRANSITION_WORKER_PREPARE):
            update_policy_run(
                run_id,
                status="RUNNING",
                phase="PREPARING_CLOCK",
                progress=0.05,
                bundle_ready=False,
            )
            return True
        append_clock_driven_start_stopped_log(run_id, "before preparing simulator clock")
        return False


def transition_clock_driven_worker_phase(
    run_id: str,
    *,
    expected_status: str,
    expected_phase: str,
    next_status: str,
    next_phase: str,
    progress: float,
    checkpoint: str,
) -> bool:
    renew_current_policy_execution_claim(run_id)
    with _POLICY_WIND_TUNNEL_LOCK:
        transition_name = lifecycle_transition_name(expected_status, expected_phase, next_status, next_phase)
        allowed = (
            lifecycle_transition_allows(policy_run_record(run_id), transition_name)
            if transition_name
            else clock_driven_worker_state_matches(run_id, expected_status, expected_phase)
        )
        if not allowed:
            append_clock_driven_start_stopped_log(run_id, checkpoint)
            return False
        update_policy_run(run_id, status=next_status, phase=next_phase, progress=progress, bundle_ready=False)
        return True


def clock_driven_worker_state_matches(run_id: str, expected_status: str, expected_phase: str) -> bool:
    renew_current_policy_execution_claim(run_id)
    run = policy_run_record(run_id)
    return trim(run.get("status")).upper() == expected_status.upper() and trim(run.get("phase")).upper() == expected_phase.upper()


def clock_driven_run_ready_to_start(run_id: str) -> bool:
    renew_current_policy_execution_claim(run_id)
    return lifecycle_transition_allows(policy_run_record(run_id), TRANSITION_WORKER_START_CLOCK)


def append_clock_driven_start_stopped_log(run_id: str, checkpoint: str) -> None:
    run = policy_run_record(run_id)
    status = trim(run.get("status")) or "UNKNOWN"
    phase = trim(run.get("phase")) or "UNKNOWN"
    if is_terminal_phase(status):
        clear_hybrid_runtime_cohort(run_id)
    append_policy_log(
        run_id,
        f"Clock-driven start worker stopped {checkpoint} because run state is {status}/{phase}.",
    )


def apply_playback_control_for_run(
    run_id: str,
    *,
    paused: bool,
    playback_rate: float,
    action_label: str,
) -> bool:
    try:
        post_json(
            simulator_url() + "/api/clock/replay",
            {
                "expectedRunId": run_id,
                "playback": {"paused": paused, "rate": 0.0 if paused else playback_rate},
            },
        )
        return True
    except RuntimeError as exc:
        if "status 409" in str(exc):
            append_policy_log(
                run_id,
                action_label
                + ". Simulator playback was not changed because replay ownership changed before the simulator accepted the update.",
            )
            return False
        raise


def clear_active_agents(run_id: str) -> None:
    http_json("DELETE", agent_runtime_url() + "/api/agents/simulation-runs/" + quote(run_id, safe=""))


def load_generated_cohort_agents_for_streaming(
    cohort_id: str,
    agent_decision_mode: str,
    agent_decision_settings: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    customers_path = generated_cohort_customers_path(cohort_id)
    if not customers_path.exists():
        raise FileNotFoundError(str(customers_path))
    raw_agents = json.loads(customers_path.read_text(encoding="utf-8"))
    if not isinstance(raw_agents, list) or not raw_agents:
        raise ValueError(f"Generated cohort {cohort_id} has no customers.")
    return [
        normalize_generated_agent(agent, agent_decision_mode, agent_decision_settings or {})
        for agent in raw_agents
        if isinstance(agent, dict)
    ]


def capture_platform_cohort_snapshot(parameters: dict[str, Any]) -> dict[str, Any] | None:
    if not is_clock_driven_parameters(parameters) or parameters.get("cohortArtifactSnapshot") is None:
        return None
    envelope = parameters.get("cohortArtifactSnapshot")
    artifact_digest = trim(parameters.get("cohortArtifactDigest"))
    identity_set_digest = trim(parameters.get("cohortAgentIdentitySetDigest"))
    if (
        not isinstance(envelope, dict)
        or trim(envelope.get("schemaVersion")) != "scenario_lab_cohort_artifact_snapshot.v1"
        or trim(envelope.get("contentType")) != "application/json"
        or trim(envelope.get("encoding")) != "base64"
        or re.fullmatch(r"[0-9a-f]{64}", artifact_digest) is None
        or re.fullmatch(r"[0-9a-f]{64}", identity_set_digest) is None
        or trim(envelope.get("sha256")) != artifact_digest
    ):
        raise ValueError("Platform-owned activation requires a valid pinned cohort snapshot.")
    try:
        content = base64.b64decode(trim(envelope.get("content")), validate=True)
    except Exception as exc:
        raise ValueError("Platform-owned cohort snapshot is not valid base64.") from exc
    if not content or len(content) > 16 * 1024 * 1024 or hashlib.sha256(content).hexdigest() != artifact_digest:
        raise ValueError("Platform-owned cohort snapshot does not match its pinned digest.")
    raw_agents = strict_cohort_snapshot_json(content)
    if not isinstance(raw_agents, list) or not raw_agents:
        raise ValueError("Platform-owned cohort snapshot must contain customers.")
    identities: set[str] = set()
    for raw_agent in raw_agents:
        identity = trim(raw_agent.get("personaName")) if isinstance(raw_agent, dict) else ""
        if not identity or identity in identities:
            raise ValueError("Platform-owned cohort snapshot contains invalid persona identities.")
        identities.add(identity)
    canonical_identities = json.dumps(
        sorted(identities),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    if hashlib.sha256(canonical_identities).hexdigest() != identity_set_digest:
        raise ValueError("Platform-owned cohort identity set does not match the pinned manifest.")
    return {
        "artifactDigest": artifact_digest,
        "identitySetDigest": identity_set_digest,
        "content": content,
    }


def activation_settings_snapshot(parameters: dict[str, Any]) -> dict[str, Any]:
    envelope = parameters.get("activationSettingsSnapshot")
    digest = trim(parameters.get("activationSettingsDigest"))
    if (
        not isinstance(envelope, dict)
        or trim(envelope.get("schemaVersion"))
        != "scenario_lab_pinned_activation_settings_snapshot.v1"
        or trim(envelope.get("contentType")) != "application/json"
        or trim(envelope.get("encoding")) != "base64url"
        or re.fullmatch(r"[0-9a-f]{64}", digest) is None
        or trim(envelope.get("sha256")) != digest
    ):
        raise ValueError("Platform-owned activation requires pinned activation settings.")
    encoded_content = trim(envelope.get("content"))
    try:
        content = base64.urlsafe_b64decode(encoded_content + "=" * (-len(encoded_content) % 4))
    except Exception as exc:
        raise ValueError("Platform-owned activation settings are not valid base64url.") from exc
    if not content or len(content) > 16 * 1024 or hashlib.sha256(content).hexdigest() != digest:
        raise ValueError("Platform-owned activation settings do not match the platform digest.")
    settings = strict_cohort_snapshot_json(content)
    if (
        not isinstance(settings, dict)
        or trim(settings.get("schemaVersion")) != "scenario_lab_pinned_cohort_activation.v1"
        or trim(settings.get("agentDecisionCadence"))
        != normalize_agent_decision_cadence(parameters.get("agentDecisionCadence"))
    ):
        raise ValueError("Platform-owned activation settings have an invalid contract.")
    return {
        "digest": digest,
        "encodedContent": encoded_content,
        "settings": settings,
    }


def persist_cohort_artifact_snapshot(run_id: str, snapshot: dict[str, Any]) -> None:
    path = cohort_artifact_snapshot_path(run_id)
    content = snapshot.get("content")
    if not isinstance(content, bytes) or not content:
        raise ValueError("Platform-owned cohort snapshot content is unavailable.")
    mkdirs_durably(path.parent)
    pending_path: Path | None = None
    try:
        descriptor, pending_name = tempfile.mkstemp(
            prefix=f"{path.name}.",
            suffix=".pending",
            dir=path.parent,
        )
        pending_path = Path(pending_name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(pending_path, path)
        except FileExistsError:
            if path.read_bytes() != content:
                raise ValueError("Platform-owned run already has a different pinned cohort snapshot.")
        fsync_directory(path.parent)
    finally:
        if pending_path is not None:
            pending_path.unlink(missing_ok=True)


def persist_clock_driven_intervention_plan(
    run_id: str,
    snapshot: RateChangePlanSnapshot,
) -> None:
    path = clock_driven_intervention_plan_path(run_id)
    content = canonical_rate_change_plan_bytes(snapshot)
    mkdirs_durably(path.parent)
    pending_path: Path | None = None
    try:
        descriptor, pending_name = tempfile.mkstemp(
            prefix=f"{path.name}.",
            suffix=".pending",
            dir=path.parent,
        )
        pending_path = Path(pending_name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(pending_path, path)
        except FileExistsError:
            if path.read_bytes() != content:
                raise ValueError(
                    "Scenario Lab run already has a contradictory clock-driven intervention plan."
                )
        fsync_directory(path.parent)
    finally:
        if pending_path is not None:
            pending_path.unlink(missing_ok=True)


def resolve_clock_driven_intervention_plan(
    run_id: str,
    parameters: dict[str, Any],
) -> RateChangePlanSnapshot | None:
    preset_id = trim(parameters.get("presetId"))
    compiled = compile_rate_change_plan(run_id, preset_id, parameters)
    stored_digest = trim(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD))
    if compiled is None:
        if stored_digest:
            raise ValueError(
                "Run metadata declares an intervention plan that the resolved preset cannot reproduce."
            )
        return None
    if (
        compiled.plan_digest != stored_digest
        or trim(parameters.get(CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD))
        != RATE_CHANGE_PLAN_SCHEMA_VERSION
    ):
        raise ValueError(
            "Run metadata does not match the compiled clock-driven intervention plan."
        )
    path = clock_driven_intervention_plan_path(run_id)
    if not path.exists():
        persist_clock_driven_intervention_plan(run_id, compiled)
    persisted = require_rate_change_plan_bytes(path.read_bytes())
    if persisted != compiled:
        raise ValueError(
            "Durable clock-driven intervention plan contradicts the launch metadata."
        )
    return persisted


def install_clock_driven_rate_change_plan(
    run_id: str,
    snapshot: RateChangePlanSnapshot,
    expected_agent_ids: set[str],
    expected_identity_set_digest: str,
    cohort_artifact_digest: str,
) -> None:
    expected_agent_count = len(expected_agent_ids)
    operation_id = rate_change_plan_install_operation_id(snapshot)
    response = post_json(
        agent_runtime_url() + "/api/domain-control-actions/execute",
        {
            "domainId": DOMAIN_ID,
            "actionId": RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "simulationRunId": run_id,
            "target": {
                "scope": "BROADCAST",
                "selector": {"simulationRunId": run_id},
            },
            "inputs": snapshot.as_dict(),
            "requestedBy": "banking-python-policy-wind-tunnel-provider",
            "reason": "Install the launch-pinned day-10 credit-card rate-change plan",
            "simulationTimeEpochMs": snapshot.plan["simulationStartEpochMs"],
        },
    )
    if not isinstance(response, dict):
        raise RuntimeError(
            "Agent runtime did not return an object for intervention plan installation."
        )
    matched = exact_nonnegative_response_count(response.get("matchedAgents"), "matchedAgents")
    succeeded = exact_nonnegative_response_count(response.get("succeeded"), "succeeded")
    failed = exact_nonnegative_response_count(response.get("failed"), "failed")
    if (
        trim(response.get("simulationRunId")) != run_id
        or trim(response.get("actionId")) != RATE_CHANGE_PLAN_ACTION_ID
        or trim(response.get("operationId")) != operation_id
        or matched != expected_agent_count
        or succeeded != expected_agent_count
        or failed != 0
    ):
        raise RuntimeError(
            "Agent runtime did not prove exact installation of the clock-driven intervention plan."
        )
    raw_results = response.get("results")
    if not isinstance(raw_results, list) or len(raw_results) != expected_agent_count:
        raise RuntimeError(
            "Agent runtime omitted per-agent rate-change installation evidence."
        )
    customers: list[RateChangeInstallationCustomer] = []
    cohort_agent_ids: set[str] = set()
    provider_runtime_agent_ids: set[str] = set()
    opportunity_agent_ids: set[str] = set()
    for raw_result in raw_results:
        if not isinstance(raw_result, dict):
            raise RuntimeError(
                "Agent runtime returned malformed per-agent rate-change installation evidence."
            )
        cohort_agent_id = raw_result.get("cohortAgentId")
        provider_runtime_agent_id = raw_result.get("providerRuntimeAgentId")
        opportunity_agent_id = raw_result.get("opportunityAgentId")
        control_action = raw_result.get("controlAction")
        identities = (
            cohort_agent_id,
            provider_runtime_agent_id,
            opportunity_agent_id,
        )
        if any(
            not isinstance(identity, str)
            or not identity
            or identity != identity.strip()
            for identity in identities
        ):
            raise RuntimeError(
                "Agent runtime returned an invalid installed customer identity."
            )
        if (
            cohort_agent_id in cohort_agent_ids
            or provider_runtime_agent_id in provider_runtime_agent_ids
            or opportunity_agent_id in opportunity_agent_ids
            or not isinstance(control_action, dict)
        ):
            raise RuntimeError(
                "Agent runtime returned contradictory per-agent rate-change installation evidence."
            )
        cohort_agent_ids.add(cohort_agent_id)
        provider_runtime_agent_ids.add(provider_runtime_agent_id)
        opportunity_agent_ids.add(opportunity_agent_id)
        eligible_account_count = exact_nonnegative_response_count(
            control_action.get("eligibleAccountCount"),
            "eligibleAccountCount",
        )
        if (
            trim(control_action.get("actionId")) != RATE_CHANGE_PLAN_ACTION_ID
            or trim(control_action.get("operationId")) != operation_id
            or trim(control_action.get("outcome")) != "PLAN_INSTALLED"
            or trim(control_action.get("planDigest")) != snapshot.plan_digest
        ):
            raise RuntimeError(
                "Agent runtime returned contradictory per-agent rate-change installation evidence."
            )
        customers.append(
            RateChangeInstallationCustomer(
                cohort_agent_id=cohort_agent_id,
                provider_runtime_agent_id=provider_runtime_agent_id,
                opportunity_agent_id=opportunity_agent_id,
                eligible_account_count=eligible_account_count,
            )
        )
    if cohort_agent_ids != expected_agent_ids:
        raise RuntimeError(
            "Agent runtime installed the rate-change plan for a different customer set."
        )
    installation = rate_change_installation_manifest(
        snapshot,
        customers,
        cohort_artifact_digest,
    )
    if installation.agent_identity_set_digest != expected_identity_set_digest:
        raise RuntimeError(
            "Rate-change installation identity digest contradicts the pinned cohort."
        )
    persist_clock_driven_intervention_installation(run_id, installation)
    append_policy_log(
        run_id,
        f"Pinned rate-change plan installed for {succeeded} customers; "
        "CC001 changes from 19% to 24% at simulated day 10.",
    )


def persist_clock_driven_intervention_installation(
    run_id: str,
    manifest: Any,
) -> None:
    path = clock_driven_intervention_installation_path(run_id)
    content = canonical_installation_manifest_bytes(manifest)
    mkdirs_durably(path.parent)
    pending_path: Path | None = None
    try:
        descriptor, pending_name = tempfile.mkstemp(
            prefix=f"{path.name}.",
            suffix=".pending",
            dir=path.parent,
        )
        pending_path = Path(pending_name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(pending_path, path)
        except FileExistsError:
            if path.read_bytes() != content:
                raise ValueError(
                    "Scenario Lab run already has contradictory rate-change installation evidence."
                )
        fsync_directory(path.parent)
    finally:
        if pending_path is not None:
            pending_path.unlink(missing_ok=True)


def clock_driven_intervention_plan_path(run_id: str) -> Path:
    return finalization_run_directory(run_id).joinpath(CLOCK_DRIVEN_INTERVENTION_PLAN_FILE)


def clock_driven_intervention_installation_path(run_id: str) -> Path:
    return finalization_run_directory(run_id).joinpath(
        CLOCK_DRIVEN_INTERVENTION_INSTALLATION_FILE
    )


def exact_nonnegative_response_count(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RuntimeError(f"{field} must be an exact non-negative response integer")
    return value


def mkdirs_durably(directory: Path) -> None:
    missing_directories: list[Path] = []
    candidate = directory
    while not candidate.exists():
        missing_directories.append(candidate)
        parent = candidate.parent
        if parent == candidate:
            break
        candidate = parent
    directory.mkdir(parents=True, exist_ok=True)
    for created_directory in reversed(missing_directories):
        fsync_directory(created_directory.parent)


def fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def load_durable_cohort_artifact_snapshot(
    run_id: str,
    parameters: dict[str, Any],
) -> dict[str, Any]:
    artifact_digest = trim(parameters.get("cohortArtifactDigest"))
    identity_set_digest = trim(parameters.get("cohortAgentIdentitySetDigest"))
    if (
        re.fullmatch(r"[0-9a-f]{64}", artifact_digest) is None
        or re.fullmatch(r"[0-9a-f]{64}", identity_set_digest) is None
    ):
        raise ValueError("Platform-owned run metadata is missing its pinned cohort identity.")
    try:
        content = cohort_artifact_snapshot_path(run_id).read_bytes()
    except OSError as exc:
        raise ValueError("Platform-owned durable cohort snapshot is unavailable.") from exc
    if (
        not content
        or len(content) > 16 * 1024 * 1024
        or hashlib.sha256(content).hexdigest() != artifact_digest
    ):
        raise ValueError("Platform-owned durable cohort snapshot no longer matches the pinned digest.")
    return {
        "artifactDigest": artifact_digest,
        "identitySetDigest": identity_set_digest,
        "content": content,
    }


def cohort_artifact_snapshot_path(run_id: str) -> Path:
    run_key = hashlib.sha256(trim(run_id).encode("utf-8")).hexdigest()
    root = workspace_root().joinpath(
        "output",
        "experiments",
        "banking-python-scenario-lab",
        "cohort-snapshots",
    ).resolve()
    path = root.joinpath(f"{run_key}.customers.json").resolve()
    if not path.is_relative_to(root):
        raise ValueError("simulationRunId resolved outside the cohort snapshot root")
    return path


def strict_cohort_snapshot_json(content: bytes) -> Any:
    try:
        text = content.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValueError("EVENT_ONLY cohort snapshot must be UTF-8 JSON.") from exc

    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"EVENT_ONLY cohort snapshot contains duplicate field: {key}")
            result[key] = value
        return result

    try:
        return json.loads(text, object_pairs_hook=reject_duplicate_keys)
    except (json.JSONDecodeError, ValueError) as exc:
        raise ValueError("EVENT_ONLY cohort snapshot must contain one strict JSON value.") from exc


def load_pinned_cohort_agents_for_streaming(
    cohort_id: str,
    snapshot: dict[str, Any],
    agent_decision_mode: str,
    agent_decision_settings: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    content = snapshot.get("content")
    if not isinstance(content, bytes) or hashlib.sha256(content).hexdigest() != trim(snapshot.get("artifactDigest")):
        raise ValueError("EVENT_ONLY cohort snapshot digest changed before activation.")
    raw_agents = strict_cohort_snapshot_json(content)
    if not isinstance(raw_agents, list) or not raw_agents:
        raise ValueError(f"Generated cohort {cohort_id} has no customers.")
    return [
        normalize_generated_agent(agent, agent_decision_mode, agent_decision_settings or {})
        for agent in raw_agents
        if isinstance(agent, dict)
    ]


def count_generated_cohort_customers(cohort_id: str) -> int:
    try:
        customers_path = generated_cohort_customers_path(cohort_id)
        raw_agents = json.loads(customers_path.read_text(encoding="utf-8"))
        return len(raw_agents) if isinstance(raw_agents, list) else 0
    except Exception:
        return 0


def generated_cohort_customers_path(cohort_id: str) -> Path:
    safe_id = safe_cohort_id(cohort_id)
    root = workspace_root()
    path = root.joinpath("output", "cohorts", safe_id, "customers.json").resolve()
    allowed = root.joinpath("output", "cohorts").resolve()
    if not path.is_relative_to(allowed):
        raise ValueError(f"Invalid generated cohort path for cohortId: {cohort_id}")
    return path


def safe_cohort_id(cohort_id: str) -> str:
    normalized = trim(cohort_id)
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
    if not normalized or ".." in normalized or "/" in normalized or "\\" in normalized or any(ch not in allowed for ch in normalized):
        raise ValueError(f"Invalid generated cohort id: {cohort_id}")
    return normalized


def normalize_generated_agent(
    raw_agent: dict[str, Any],
    agent_decision_mode: str,
    agent_decision_settings: dict[str, Any] | None = None,
) -> dict[str, Any]:
    agent = json.loads(json.dumps(raw_agent))
    agent["domainId"] = DOMAIN_ID
    agent["provider"] = trim(agent.get("provider")) or "stub"
    agent["model"] = trim(agent.get("model")) or "stub"
    agent["interactionMode"] = trim(agent.get("interactionMode")) or "SIMULATION"
    agent["autonomousDecisionEnabled"] = bool(agent.get("autonomousDecisionEnabled", False))
    agent.setdefault("autonomousDecisionIntervalMinutes", 60)
    if agent_decision_mode in {AGENT_DECISION_MODE_STUB_LLM, AGENT_DECISION_MODE_REAL_LLM}:
        settings = agent_decision_settings or {}
        cadence = normalize_agent_decision_cadence(settings.get("agentDecisionCadence"))
        if agent_decision_mode == AGENT_DECISION_MODE_REAL_LLM:
            budget = real_llm_budget(settings)
            agent["provider"] = budget["provider"]
            agent["model"] = budget["model"]
            agent["realLlmMaxDecisionCalls"] = budget["maxCalls"]
            agent["realLlmBudget"] = budget
        else:
            agent["provider"] = "stub"
            agent["model"] = "stub"
        agent["interactionMode"] = "HYBRID"
        agent["autonomousDecisionEnabled"] = True
        agent["autonomousDecisionCadence"] = cadence
        agent["autonomousDecisionIntervalMinutes"] = agent_decision_interval_minutes(cadence)
        agent.pop("autonomousDecisionPhaseOffsetMinutes", None)
        traits = list(dict.fromkeys([str(trait) for trait in agent.get("traits", [])] + [
            "scenario-lab:hybrid-eligible",
            "scenario-lab:decision-policy:bounded-savings-micro-v1",
            "agent-decision-mode:real-llm-canary" if agent_decision_mode == AGENT_DECISION_MODE_REAL_LLM else "agent-decision-mode:stub-llm",
        ]))
        agent["traits"] = traits
    agent["goals"] = map_list(agent.get("goals"))
    agent["initialAccounts"] = map_list(agent.get("initialAccounts"))
    return agent


def normalize_agent_decision_cadence(value: Any) -> str:
    raw = trim(value)
    if not raw:
        return AGENT_DECISION_CADENCE_DAILY
    normalized = raw.upper().replace("-", "_")
    if normalized in {
        AGENT_DECISION_CADENCE_EVENT_ONLY,
        AGENT_DECISION_CADENCE_HOURLY,
        AGENT_DECISION_CADENCE_DAILY,
        AGENT_DECISION_CADENCE_WEEKLY,
    }:
        return normalized
    raise ProtocolError(
        400,
        "invalid_agent_decision_cadence",
        "invalid_request",
        "agentDecisionCadence must be one of EVENT_ONLY, HOURLY, DAILY, WEEKLY.",
        operation="policy-wind-tunnel-run",
    )


def agent_decision_interval_minutes(cadence: str) -> int:
    if cadence == AGENT_DECISION_CADENCE_EVENT_ONLY:
        return 0
    if cadence == AGENT_DECISION_CADENCE_HOURLY:
        return 60
    if cadence == AGENT_DECISION_CADENCE_WEEKLY:
        return 7 * 24 * 60
    return 24 * 60


def enforce_hybrid_cohort_limit(cohort_id: str, agent_count: int) -> None:
    limit = int_env("ASTRAFORM_HYBRID_CLOCK_DRIVEN_MAX_COHORT_SIZE", 100)
    if agent_count <= limit:
        return
    raise ValueError(
        f"Clock-Driven Simulation is currently capped at {limit} customers for the Python remote-domain path. "
        f"Cohort {cohort_id} has {agent_count}. Use a validated cohort within the configured "
        "Clock-Driven Simulation limit."
    )


def activate_agent_chunk(
    run_id: str,
    cohort_id: str,
    cohort_artifact_digest: str,
    chunk_number: int,
    chunk_count: int,
    agents: list[dict[str, Any]],
) -> None:
    headers = {
        "X-Astraform-Run-Id": run_id,
        "X-Astraform-Cohort-Id": cohort_id,
        "X-Astraform-Activation-Id": f"{run_id}-chunk-{chunk_number}",
        "X-Astraform-Init-Max-Concurrency": str(int_env("ASTRAFORM_HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY", 4)),
    }
    if trim(cohort_artifact_digest):
        headers["X-Astraform-Cohort-Artifact-Digest"] = trim(cohort_artifact_digest)
    post_json(agent_runtime_url() + "/api/agents/init", agents, headers=headers)
    with _POLICY_WIND_TUNNEL_LOCK:
        update_policy_run(run_id, increment_fields={"activatedCount": len(agents)})
    append_policy_log(run_id, f"Activated chunk {chunk_number}/{chunk_count} ({len(agents)} customers).")


def activate_pinned_cohort(
    run_id: str,
    cohort_id: str,
    snapshot: dict[str, Any],
    agent_decision_mode: str,
    parameters: dict[str, Any],
    agent_count: int,
) -> None:
    content = snapshot.get("content")
    if not isinstance(content, bytes):
        raise ValueError("Platform-owned pinned cohort bytes are unavailable.")
    activation_settings = activation_settings_snapshot(parameters)
    if trim(activation_settings["settings"].get("agentDecisionMode")) != agent_decision_mode:
        raise ValueError("Platform-owned activation settings do not match the accepted run mode.")
    activation_id = trim(parameters.get("activationId"))
    if not activation_id:
        raise ValueError("Platform-owned activation requires the platform-owned activationId.")
    headers = {
        "X-Astraform-Run-Id": run_id,
        "X-Astraform-Cohort-Id": cohort_id,
        "X-Astraform-Cohort-Artifact-Digest": trim(snapshot.get("artifactDigest")),
        "X-Astraform-Activation-Id": activation_id,
        "X-Astraform-Init-Max-Concurrency": str(int_env("ASTRAFORM_HYBRID_ACTIVATION_INIT_MAX_CONCURRENCY", 4)),
        "X-Astraform-Activation-Settings": activation_settings["encodedContent"],
    }
    post_bytes(
        agent_runtime_url() + "/api/agents/init/pinned-cohort",
        content,
        headers=headers,
    )
    append_policy_log(run_id, f"Activated pinned cohort ({agent_count} customers).")


def fetch_simulation_time_epoch_ms() -> int | None:
    try:
        payload = get_json(simulator_url() + "/api/clock/time")
        if isinstance(payload, (int, float)):
            return int(payload)
        if isinstance(payload, dict):
            for key in ("epochMs", "currentTimeEpochMs", "simulationTimeEpochMs", "time"):
                value = payload.get(key)
                if isinstance(value, (int, float, str)) and trim(value):
                    return int(float(value))
    except Exception:
        return None
    return None


def fetch_simulator_replay_context() -> dict[str, Any]:
    try:
        payload = get_json(simulator_url() + "/api/clock/replay")
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def clock_driven_simulation_window(run_id: str, parameters: dict[str, Any]) -> dict[str, Any]:
    configured_days = max(1, int_or_default(parameters.get("simulationHorizonDays"), 30))
    start_epoch_ms = simulation_start_epoch_ms(parameters)
    window: dict[str, Any] = {
        "configuredDurationDays": configured_days,
        "horizonDays": configured_days,
        "simulationStartEpochMs": start_epoch_ms,
    }
    replay = fetch_simulator_replay_context()
    execution_context = replay.get("executionContext") if isinstance(replay.get("executionContext"), dict) else {}
    active_run_id = trim(execution_context.get("runId"))
    if active_run_id != run_id:
        window["durationStatus"] = "UNVERIFIED_CLOCK_OWNERSHIP"
        window["durationProofVerified"] = False
        window["durationUnverifiedReason"] = (
            "Simulator replay ownership was missing or unavailable."
            if not active_run_id
            else f"Simulator replay is owned by {active_run_id}."
        )
        return window
    simulation_epoch_ms = fetch_simulation_time_epoch_ms()
    if simulation_epoch_ms is None:
        window["durationStatus"] = "UNVERIFIED_CLOCK_TIME"
        window["durationProofVerified"] = False
        window["durationUnverifiedReason"] = "Simulator clock time was unavailable after replay ownership was verified."
        return window
    elapsed_ms = max(0, int(simulation_epoch_ms) - start_epoch_ms)
    actual_days = round(elapsed_ms / MILLIS_PER_SIMULATION_DAY, 4)
    overshoot_days = round(max(0.0, actual_days - configured_days), 4)
    window.update(
        {
            "simulationTimeEpochMs": int(simulation_epoch_ms),
            "actualDurationDays": actual_days,
            "elapsedDays": actual_days,
            "horizonOvershootDays": overshoot_days,
            "durationStatus": "HORIZON_OVERRUN" if overshoot_days > 0.05 else "WITHIN_HORIZON",
            "durationProofVerified": True,
        }
    )
    return window


def fetch_simulation_run_record(run_id: str) -> dict[str, Any]:
    try:
        payload = get_json(experiment_service_url() + "/api/simulation-runs/" + quote(run_id))
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def fetch_simulation_run_ledger(run_id: str) -> dict[str, Any]:
    try:
        payload = get_json(experiment_service_url() + "/api/simulation-runs/" + quote(run_id) + "/ledger?limit=1000")
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def simulation_ledger_counts(run_record: dict[str, Any], ledger: dict[str, Any]) -> dict[str, int]:
    return {
        "agentCount": int_or_default(run_record.get("agentCount"), len(list_items(ledger.get("agents")))),
        "eventCount": int_or_default(run_record.get("eventCount"), len(list_items(ledger.get("events")))),
        "checkpointCount": int_or_default(run_record.get("checkpointCount"), len(list_items(ledger.get("checkpoints")))),
    }


def clock_driven_artifact_paths(run_id: str) -> dict[str, str]:
    base = f"output/experiments/banking-python-scenario-lab/runs/{run_id}/summary"
    return {
        "summary": base + "/scenario-lab-summary.json",
        "comparisonReport": base + "/decision-comparison.json",
        "simulationTimelineJson": base + "/simulation-timeline.json",
        "sideEffectLedgerJson": base + "/side-effect-ledger.json",
        "branchStateProjectionJson": base + "/branch-state-projection.json",
        "domainOutcomeScoreJson": base + "/domain-outcome-score.json",
        "domainOutcomeScoresJson": base + "/domain-outcome-scores.json",
        "hybridAgentAuditJson": base + "/hybrid-agent-audit.json",
        "simulationRunEvidence": "output/simulation-runs/" + run_id,
    }


def clock_driven_artifact_downloads(run_id: str) -> dict[str, str]:
    return {
        "simulationRunEvidence": f"/api/dashboard/simulation-runs/{quote(run_id)}/export",
        "domainOutcomeScoreJson": f"/api/dashboard/scenario-lab/artifact?runId={quote(run_id)}&type=domain-outcome-score-json",
        "domainOutcomeScoresJson": f"/api/dashboard/scenario-lab/artifact?runId={quote(run_id)}&type=domain-outcome-scores-json",
        "hybridAgentAuditJson": f"/api/dashboard/scenario-lab/artifact?runId={quote(run_id)}&type=hybrid-agent-audit-json",
    }


def build_agentic_simulation_summary(
    parameters: dict[str, Any],
    cohort_size: int,
    decision_audit: dict[str, Any],
    artifact_downloads: dict[str, str],
    simulation_window: dict[str, Any],
    *,
    finalized: bool,
    simulation_run_id: str = "",
    opportunity_reconciliation: dict[str, Any] | None = None,
) -> dict[str, Any]:
    decision_mode = normalize_agent_decision_mode(parameters.get("agentDecisionMode"))
    cadence = trim(parameters.get("agentDecisionCadence")).upper() or "NOT_APPLICABLE"
    interval_minutes = interval_minutes_for_cadence(
        cadence,
        int_or_default(parameters.get("agentDecisionIntervalMinutes"), 0),
    )
    horizon_days = max(0, int_or_default(parameters.get("simulationHorizonDays"), 0))
    playback_rate = float_or_default(parameters.get("playbackRate"), 0.0)
    audit_summary = decision_audit.get("summary") if isinstance(decision_audit.get("summary"), dict) else {}
    audit_totals = decision_audit.get("totals") if isinstance(decision_audit.get("totals"), dict) else {}
    audited_agent_count = int_or_default(audit_summary.get("agentCount"), int_or_default(audit_totals.get("agentCount"), 0))
    actual_decision_count = int_or_default(
        audit_summary.get("decisionCount"),
        int_or_default(audit_totals.get("decisionCount"), 0),
    )
    fallback_count = int_or_default(audit_summary.get("fallbackCount"), int_or_default(audit_totals.get("fallbackCount"), 0))
    observed_decision_opportunities = int_or_default(
        audit_summary.get("decisionOpportunityCount"),
        int_or_default(audit_totals.get("decisionOpportunityCount"), 0),
    )
    admitted_decision_opportunities = int_or_default(
        audit_summary.get("decisionAdmittedCount"),
        int_or_default(audit_totals.get("decisionAdmittedCount"), 0),
    )
    deferred_decision_opportunities = int_or_default(
        audit_summary.get("decisionDeferredCount"),
        int_or_default(
            audit_totals.get("decisionDeferredCount"),
            max(0, observed_decision_opportunities - admitted_decision_opportunities),
        ),
    )
    deferred_decision_reasons = (
        audit_summary.get("decisionDeferredByReason")
        if isinstance(audit_summary.get("decisionDeferredByReason"), dict)
        else audit_totals.get("decisionDeferredByReason")
        if isinstance(audit_totals.get("decisionDeferredByReason"), dict)
        else {}
    )
    evidence_integrity_warnings = agentic_evidence_integrity_warnings(
        observed_decision_opportunities,
        admitted_decision_opportunities,
        deferred_decision_opportunities,
        actual_decision_count,
        decision_audit.get("runtimeTickDrain") if isinstance(decision_audit.get("runtimeTickDrain"), dict) else {},
        simulation_window,
    )
    expected_opportunities = expected_decision_opportunities(
        decision_mode,
        cohort_size,
        horizon_days,
        interval_minutes,
    )
    coverage_rate = round(actual_decision_count / expected_opportunities, 4) if expected_opportunities > 0 else 0.0
    opportunity_coverage_rate = (
        round(observed_decision_opportunities / expected_opportunities, 4)
        if expected_opportunities > 0
        else 0.0
    )
    admission_rate = (
        round(admitted_decision_opportunities / observed_decision_opportunities, 4)
        if observed_decision_opportunities > 0
        else 0.0
    )
    summary = {
        "schemaVersion": "agentic_customer_simulation_summary.v1",
        "executionMode": EXECUTION_MODE_CLOCK_DRIVEN_HYBRID,
        "agentDecisionMode": decision_mode,
        "decisionCadence": cadence,
        "decisionIntervalMinutes": interval_minutes,
        "simulationHorizonDays": horizon_days,
        "configuredDurationDays": simulation_window.get("configuredDurationDays", horizon_days),
        "actualDurationDays": simulation_window.get("actualDurationDays"),
        "horizonOvershootDays": simulation_window.get("horizonOvershootDays"),
        "durationStatus": simulation_window.get("durationStatus"),
        "durationProofVerified": simulation_window.get("durationProofVerified"),
        "durationUnverifiedReason": simulation_window.get("durationUnverifiedReason"),
        "playbackRate": playback_rate,
        "realLlmOptIn": decision_mode == AGENT_DECISION_MODE_REAL_LLM and parameters.get("realLlmOptIn") is True,
        "realLlmBudget": real_llm_budget(parameters) if decision_mode == AGENT_DECISION_MODE_REAL_LLM else {},
        "cohortSize": cohort_size,
        "expectedDecisionOpportunities": expected_opportunities,
        "observedDecisionOpportunities": observed_decision_opportunities,
        "admittedDecisionOpportunities": admitted_decision_opportunities,
        "deferredDecisionOpportunities": deferred_decision_opportunities,
        "deferredDecisionReasons": deferred_decision_reasons,
        "actualDecisionCount": actual_decision_count,
        "auditedAgentCount": audited_agent_count,
        "fallbackCount": fallback_count,
        "evidenceIntegrityWarnings": evidence_integrity_warnings,
        "decisionCoverageRate": coverage_rate,
        "decisionOpportunityCoverageRate": opportunity_coverage_rate,
        "decisionAdmissionRate": admission_rate,
        "decisionCoverageStatus": agentic_coverage_status(
            decision_mode,
            expected_opportunities,
            actual_decision_count,
            finalized,
        ),
        "decisionCoverageExplanation": agentic_coverage_explanation(
            decision_mode,
            cadence,
            expected_opportunities,
            observed_decision_opportunities,
            admitted_decision_opportunities,
            actual_decision_count,
            deferred_decision_opportunities,
            deferred_decision_reasons,
            evidence_integrity_warnings,
            finalized,
        ),
        "evidenceRefs": {
            "hybridAgentAuditJson": artifact_downloads.get("hybridAgentAuditJson"),
            "simulationRunEvidencePack": artifact_downloads.get("simulationRunEvidence"),
        },
    }
    apply_opportunity_reconciliation_to_agentic_summary(
        summary,
        decision_audit,
        simulation_run_id,
        opportunity_reconciliation,
        finalized=finalized,
    )
    return summary


def apply_opportunity_reconciliation_to_agentic_summary(
    summary: dict[str, Any],
    decision_audit: dict[str, Any],
    simulation_run_id: str,
    opportunity_reconciliation: dict[str, Any] | None,
    *,
    finalized: bool,
) -> None:
    reconciliation = opportunity_reconciliation if isinstance(opportunity_reconciliation, dict) else {}
    summary["opportunityReconciliation"] = reconciliation
    if (
        not finalized
        or not opportunity_reconciliation_is_valid(reconciliation, simulation_run_id)
        or reconciliation.get("generationMode") != OPPORTUNITY_GENERATION_MODE
    ):
        summary["decisionOpportunitySource"] = "LEGACY_CADENCE_AUDIT"
        return

    status_counts = reconciliation["statusCounts"]
    assert isinstance(status_counts, dict)
    opportunity_ledger = opportunity_ledger_from_decision_audit(decision_audit)
    opportunity_audit_verified = opportunity_audit_matches_reconciliation(
        decision_audit,
        simulation_run_id,
        reconciliation,
    )
    expected = int(reconciliation["expectedGeneratedOpportunities"])
    generated = int(reconciliation["generatedOpportunities"])
    terminal = int(reconciliation["terminalOpportunities"])
    backlog = int(reconciliation["remainingBacklog"])
    valid_decisions = int(reconciliation["validDecisions"])
    deferred = int(status_counts["DEFERRED"])
    admitted = (
        int(opportunity_ledger["admittedOpportunities"])
        if opportunity_audit_verified
        else int(status_counts["SUCCEEDED"])
        + deferred
        + int(status_counts["RUNNING"])
        + int(status_counts["BLOCKED"])
        + int(status_counts["FAILED"])
    )
    total_attempts = (
        int(opportunity_ledger["totalAttempts"])
        if opportunity_audit_verified
        else admitted
    )
    deferred_reasons = (
        dict(opportunity_ledger["deferredReasonCounts"])
        if opportunity_audit_verified
        else {}
    )
    historical_deferrals = (
        int(opportunity_ledger["historicalDeferralCount"])
        if opportunity_audit_verified
        else 0
    )
    historical_deferral_reasons = (
        dict(opportunity_ledger["historicalDeferralReasonCounts"])
        if opportunity_audit_verified
        else {}
    )
    warnings = [
        dict(warning)
        for warning in summary.get("evidenceIntegrityWarnings", [])
        if isinstance(warning, dict)
        and warning.get("code") in {"SIMULATION_DURATION_UNVERIFIED", "RUNTIME_TICK_DRAIN_UNVERIFIED"}
    ]
    if not opportunity_audit_verified:
        warnings.append(
            {
                "code": "OPPORTUNITY_AGENT_AUDIT_UNVERIFIED",
                "message": (
                    "Per-agent opportunity audit totals were unavailable or did not match the run reconciliation "
                    "snapshot. Admission and attempt counts are conservative lower bounds."
                ),
            }
        )
    if reconciliation.get("ledgerReconciled") is not True or backlog > 0:
        warnings.append(
            {
                "code": "OPPORTUNITY_LEDGER_NOT_RECONCILED",
                "message": (
                    "The simulation horizon completed before the customer decision opportunity ledger reconciled."
                ),
                "status": reconciliation.get("status"),
                "remainingBacklog": backlog,
            }
        )
    evidence_gaps = int(reconciliation["decisionEvidenceGaps"])
    if evidence_gaps > 0:
        warnings.append(
            {
                "code": "OPPORTUNITY_DECISION_EVIDENCE_GAPS",
                "message": (
                    "Terminal customer decision opportunities include explicit blocked, failed, cancelled, or "
                    "missing-evidence outcomes."
                ),
                "decisionEvidenceGaps": evidence_gaps,
            }
        )
    reconciliation_status = str(reconciliation["status"])
    coverage_status = {
        "EMPTY": "NO_CUSTOMER_DECISIONS_TRIGGERED",
        "RECONCILED": "RECONCILED_WITH_EVIDENCE_GAPS" if evidence_gaps > 0 else "CAPTURED",
        "DRAINING": "PARTIAL_COVERAGE",
        "INCONSISTENT": "EVIDENCE_INCONSISTENT",
        "GENERATION_OPEN": "GENERATION_OPEN",
    }.get(reconciliation_status, "OPPORTUNITY_LEDGER_UNVERIFIED")
    summary.update(
        {
            "decisionOpportunitySource": "CUSTOMER_DECISION_OPPORTUNITY_LEDGER",
            "expectedDecisionOpportunities": expected,
            "observedDecisionOpportunities": generated,
            "admittedDecisionOpportunities": admitted,
            "deferredDecisionOpportunities": deferred,
            "deferredDecisionReasons": deferred_reasons,
            "historicalDeferredDecisionTransitions": historical_deferrals,
            "historicalDeferredDecisionReasons": historical_deferral_reasons,
            "actualDecisionCount": valid_decisions,
            "auditedAgentCount": int(opportunity_ledger.get("agentCount", 0))
            if opportunity_audit_verified
            else 0,
            "opportunityTerminalCount": terminal,
            "opportunityRemainingBacklog": backlog,
            "opportunityStatusCounts": dict(status_counts),
            "opportunityTotalAttempts": total_attempts,
            "opportunityAdmissionCountVerified": opportunity_audit_verified,
            "opportunityDecisionEvidenceGaps": evidence_gaps,
            "evidenceIntegrityWarnings": warnings,
            "decisionCoverageRate": round(valid_decisions / expected, 4) if expected > 0 else 0.0,
            "decisionOpportunityCoverageRate": round(generated / expected, 4) if expected > 0 else 0.0,
            "decisionAdmissionRate": round(admitted / generated, 4) if generated > 0 else 0.0,
            "decisionCoverageStatus": coverage_status,
            "decisionCoverageExplanation": (
                f"The finalized event-driven run generated {generated} durable customer decision "
                f"opportunity/opportunities, produced {valid_decisions} valid decision(s), left {backlog} in "
                f"backlog, and recorded {evidence_gaps} explicit evidence gap(s). Ledger status: "
                f"{reconciliation_status}."
            ),
        }
    )


def opportunity_audit_totals_match_reconciliation(
    totals: Any,
    simulation_run_id: str,
    reconciliation: dict[str, Any],
) -> bool:
    if not isinstance(totals, dict):
        return False
    reconciled_generated = strict_non_negative_int(reconciliation.get("generatedOpportunities"))
    simulation_run_ids = totals.get("simulationRunIds")
    run_scope_matches = (
        simulation_run_ids == []
        if reconciled_generated == 0
        else simulation_run_ids == [simulation_run_id]
    )
    if (
        totals.get("schemaVersion") != OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION
        or totals.get("consistentRunScope") is not True
        or strict_non_negative_int(totals.get("invalidAgentAuditCount")) != 0
        or not run_scope_matches
    ):
        return False
    compared_fields = (
        "generatedOpportunities",
        "terminalOpportunities",
        "remainingBacklog",
        "validDecisions",
        "recoverableRecordedDecisions",
        "succeededWithoutDecisionEvidence",
        "decisionEvidenceGaps",
    )
    return (
        all(
            strict_non_negative_int(totals.get(field))
            == strict_non_negative_int(reconciliation.get(field))
            for field in compared_fields
        )
        and totals.get("statusCounts") == reconciliation.get("statusCounts")
        and strict_non_negative_int(totals.get("admittedOpportunities")) is not None
        and strict_non_negative_int(totals.get("totalAttempts")) is not None
        and isinstance(totals.get("deferredReasonCounts"), dict)
        and strict_non_negative_int(totals.get("historicalDeferralCount")) is not None
        and isinstance(totals.get("historicalDeferralReasonCounts"), dict)
    )


def opportunity_ledger_from_decision_audit(decision_audit: dict[str, Any]) -> dict[str, Any]:
    audit_summary = decision_audit.get("summary") if isinstance(decision_audit.get("summary"), dict) else {}
    if isinstance(audit_summary.get("opportunityLedger"), dict):
        return dict(audit_summary["opportunityLedger"])
    if isinstance(decision_audit.get("opportunityLedger"), dict):
        return dict(decision_audit["opportunityLedger"])
    return {}


def opportunity_audit_matches_reconciliation(
    decision_audit: dict[str, Any],
    simulation_run_id: str,
    reconciliation: dict[str, Any],
) -> bool:
    totals = opportunity_ledger_from_decision_audit(decision_audit)
    if not opportunity_audit_totals_match_reconciliation(totals, simulation_run_id, reconciliation):
        return False
    agents = decision_audit.get("agents")
    if not isinstance(agents, list):
        return False
    audited_agent_ids: set[str] = set()
    for agent in agents:
        if not isinstance(agent, dict):
            return False
        if "opportunityAudit" not in agent:
            continue
        audit = agent.get("opportunityAudit")
        if (
            not opportunity_agent_audit_is_valid(audit)
            or not isinstance(audit, dict)
            or trim(audit.get("simulationRunId")) != simulation_run_id
        ):
            return False
        agent_id = trim(audit.get("agentId"))
        if agent_id in audited_agent_ids:
            return False
        audited_agent_ids.add(agent_id)
    return len(audited_agent_ids) == strict_non_negative_int(totals.get("agentCount"))


def interval_minutes_for_cadence(cadence: str, fallback: int) -> int:
    normalized = trim(cadence).upper()
    if normalized == "EVENT_ONLY":
        return 0
    if normalized == "HOURLY":
        return 60
    if normalized == "DAILY":
        return 24 * 60
    if normalized == "WEEKLY":
        return 7 * 24 * 60
    return max(0, fallback)


def expected_decision_opportunities(
    decision_mode: str,
    cohort_size: int,
    horizon_days: int,
    interval_minutes: int,
) -> int:
    if decision_mode not in {"STUB_LLM", "REAL_LLM"}:
        return 0
    if cohort_size <= 0 or horizon_days <= 0 or interval_minutes <= 0:
        return 0
    slots = max(1, (horizon_days * 24 * 60) // interval_minutes)
    return min(2_147_483_647, slots * cohort_size)


def agentic_coverage_status(
    decision_mode: str,
    expected_opportunities: int,
    actual_decision_count: int,
    finalized: bool,
) -> str:
    if decision_mode not in {"STUB_LLM", "REAL_LLM"}:
        return "NO_CUSTOMER_DECISIONS_EXPECTED"
    if expected_opportunities <= 0:
        return "NOT_CONFIGURED"
    if actual_decision_count <= 0:
        return "NO_DECISIONS_CAPTURED" if finalized else "WAITING_FOR_DECISIONS"
    if actual_decision_count < expected_opportunities:
        return "PARTIAL_COVERAGE" if finalized else "CAPTURING"
    return "CAPTURED"


def agentic_evidence_integrity_warnings(
    observed_decision_opportunities: int,
    admitted_decision_opportunities: int,
    deferred_decision_opportunities: int,
    actual_decision_count: int,
    runtime_tick_drain: dict[str, Any] | None = None,
    simulation_window: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    warnings = []
    window = simulation_window if isinstance(simulation_window, dict) else {}
    if window.get("durationProofVerified") is False:
        warnings.append(
            {
                "code": "SIMULATION_DURATION_UNVERIFIED",
                "message": (
                    trim(window.get("durationUnverifiedReason"))
                    or "Simulation duration could not be verified because replay clock ownership was not proven for this run."
                ),
                "durationStatus": trim(window.get("durationStatus")) or "UNVERIFIED_CLOCK_OWNERSHIP",
            }
        )
    drain = runtime_tick_drain if isinstance(runtime_tick_drain, dict) else {}
    if drain and drain.get("verified") is not True:
        warnings.append(
            {
                "code": "RUNTIME_TICK_DRAIN_UNVERIFIED",
                "message": (
                    trim(drain.get("message"))
                    or "Runtime tick drain was not verified for this simulation run before finalization; decision audit may still be catching up."
                ),
                "drainStatus": trim(drain.get("status")) or "UNVERIFIED",
            }
        )
    if actual_decision_count > admitted_decision_opportunities:
        warnings.append(
            {
                "code": "AUDITED_DECISIONS_EXCEED_ADMITTED_TURNS",
                "message": (
                    "Audited decision events exceed admitted runtime turns. "
                    "Decision-window telemetry may be missing, delayed, or inconsistent."
                ),
                "observedDecisionOpportunities": observed_decision_opportunities,
                "admittedDecisionOpportunities": admitted_decision_opportunities,
                "actualDecisionCount": actual_decision_count,
            }
        )
    if admitted_decision_opportunities > observed_decision_opportunities:
        warnings.append(
            {
                "code": "ADMITTED_TURNS_EXCEED_OBSERVED_OPPORTUNITIES",
                "message": (
                    "Admitted runtime turns exceed observed decision opportunities. "
                    "The report preserved raw counters instead of normalizing them."
                ),
                "observedDecisionOpportunities": observed_decision_opportunities,
                "admittedDecisionOpportunities": admitted_decision_opportunities,
            }
        )
    expected_deferred = max(0, observed_decision_opportunities - admitted_decision_opportunities)
    if deferred_decision_opportunities != expected_deferred:
        warnings.append(
            {
                "code": "DEFERRED_OPPORTUNITY_COUNT_MISMATCH",
                "message": (
                    "Deferred decision opportunities do not match observed minus admitted opportunities. "
                    "The report preserved raw runtime counters."
                ),
                "observedDecisionOpportunities": observed_decision_opportunities,
                "admittedDecisionOpportunities": admitted_decision_opportunities,
                "deferredDecisionOpportunities": deferred_decision_opportunities,
                "expectedDeferredDecisionOpportunities": expected_deferred,
            }
        )
    return warnings


def agentic_coverage_explanation(
    decision_mode: str,
    cadence: str,
    expected_opportunities: int,
    observed_decision_opportunities: int,
    admitted_decision_opportunities: int,
    actual_decision_count: int,
    deferred_decision_opportunities: int,
    deferred_decision_reasons: dict[str, Any],
    evidence_integrity_warnings: list[dict[str, Any]],
    finalized: bool,
) -> str:
    if decision_mode not in {"STUB_LLM", "REAL_LLM"}:
        return (
            "Deterministic mode does not schedule customer LLM turns. "
            "It exercises activation, scheduled work, remote-domain operations, and evidence capture."
        )
    mode_label = (
        "Stub LLM mode schedules no-token customer decision turns"
        if decision_mode == "STUB_LLM"
        else "Real LLM mode schedules provider-backed customer decision turns"
    )
    cadence_label = cadence.lower().replace("_", " ")
    integrity_clause = ""
    if evidence_integrity_warnings:
        first_warning = evidence_integrity_warnings[0]
        integrity_clause = (
            " Evidence integrity warning: "
            + (trim(first_warning.get("message")) or trim(first_warning.get("code")) or "raw decision counters disagree")
        )
    if expected_opportunities <= 0:
        return (
            mode_label
            + ", but this run did not publish enough horizon/cadence metadata to estimate opportunities."
            + integrity_clause
        )
    if observed_decision_opportunities > 0:
        deferred_clause = ""
        if deferred_decision_opportunities > 0:
            deferred_clause = (
                f" and deferred {deferred_decision_opportunities} because "
                f"{deferred_reason_summary(deferred_decision_reasons)}"
            )
        return (
            f"{mode_label} on the {cadence_label} cadence. The runtime checked "
            f"{observed_decision_opportunities} of about {expected_opportunities} configured opportunities, "
            f"admitted {admitted_decision_opportunities} customer decision turn(s), and captured "
            f"{actual_decision_count} audited decision event(s){deferred_clause}."
            f"{integrity_clause}"
        )
    if actual_decision_count <= 0:
        suffix = "." if finalized else " yet."
        return f"{mode_label} on the {cadence_label} cadence, but no audited decisions have been captured{suffix}{integrity_clause}"
    return (
        f"{mode_label} on the {cadence_label} cadence. The audit captured "
        f"{actual_decision_count} of about {expected_opportunities} configured decision opportunities."
        f"{integrity_clause}"
    )


def deferred_reason_summary(reasons: dict[str, Any]) -> str:
    if not isinstance(reasons, dict) or not reasons:
        return "runtime gating"
    ranked = sorted(
        ((trim(reason).replace("_", " "), int_or_default(count, 0)) for reason, count in reasons.items()),
        key=lambda item: item[1],
        reverse=True,
    )
    visible = [f"{reason} ({count})" for reason, count in ranked[:2] if reason and count > 0]
    return ", ".join(visible) if visible else "runtime gating"


def write_clock_driven_artifact_files(run_id: str, summary: dict[str, Any]) -> None:
    base = finalization_summary_directory(run_id)
    base.mkdir(parents=True, exist_ok=True)
    hybrid = summary.get("hybrid") if isinstance(summary.get("hybrid"), dict) else {}
    hybrid_agent_audit = hybrid.get("agentAudit")
    if hybrid_agent_audit is None:
        hybrid_agent_audit = summary.get("hybridAgentAudit")
    if hybrid_agent_audit is None:
        hybrid_agent_audit = load_runtime_decision_audit(run_id, include_decisions=False)
    files = {
        "scenario-lab-summary.json": summary,
        "decision-comparison.json": summary.get("decisionComparison", {}),
        "simulation-timeline.json": summary.get("simulationTimeline", {}),
        "side-effect-ledger.json": summary.get("sideEffectLedger", {}),
        "branch-state-projection.json": summary.get("branchStateProjection", {}),
        "domain-outcome-score.json": summary.get("domainOutcomeScore", {}),
        "domain-outcome-scores.json": summary.get("domainOutcomeScores", []),
        "hybrid-agent-audit.json": hybrid_agent_audit,
    }
    for filename, payload in files.items():
        atomic_write_bytes(
            base.joinpath(filename),
            json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False).encode("utf-8"),
        )


def build_clock_driven_timeline(
    run: dict[str, Any],
    *,
    finalized: bool,
    ledger: dict[str, Any] | None = None,
    simulation_window: dict[str, Any] | None = None,
) -> dict[str, Any]:
    events = []
    for index, timing in enumerate(run.get("phaseTimings", []) if isinstance(run.get("phaseTimings"), list) else []):
        phase = trim(timing.get("phase")) or "UNKNOWN"
        events.append(
            {
                "eventId": f"phase-{index + 1}-{safe_token(phase)}",
                "label": phase.replace("_", " ").title(),
                "time": trim(timing.get("startedAt")) or utc_now_iso(),
                "eventType": "RUN_PHASE",
                "status": trim(timing.get("status")) or "RUNNING",
                "evidenceRef": "simulationRunEvidencePack",
            }
        )
    if finalized:
        events.append(
            {
                "eventId": "python-finalized-report",
                "label": "Python Clock-Driven Simulation report finalized",
                "time": utc_now_iso(),
                "eventType": "REPORT_FINALIZED",
                "status": "COMPLETED",
                "evidenceRef": "comparisonReport",
            }
        )
    for index, event in enumerate(list_items((ledger or {}).get("events"))[:8]):
        events.append(
            {
                "eventId": trim(event.get("eventId")) or f"ledger-event-{index + 1}",
                "label": humanize_label(trim(event.get("eventType")) or "Runtime evidence event"),
                "time": trim(event.get("createdAt")) or trim(event.get("timestamp")) or utc_now_iso(),
                "eventType": trim(event.get("eventType")) or "RUNTIME_EVIDENCE",
                "status": trim(event.get("outcome")).upper() or "RECORDED",
                "evidenceRef": "simulationRunEvidencePack",
                "source": trim(event.get("source")) or trim(event.get("category")) or "experiment-service",
            }
        )
    timeline = dict(simulation_window or {})
    timeline.update({
        "schemaVersion": "customer_outcome_simulation_timeline.v1",
        "mode": "CLOCK_DRIVEN_HYBRID_FINALIZED" if finalized else "CLOCK_DRIVEN_HYBRID_LIVE",
        "events": events,
    })
    return timeline


def build_clock_driven_side_effect_ledger(
    run_id: str,
    status: str,
    phase: str,
    *,
    finalized: bool,
    ledger: dict[str, Any] | None = None,
    ledger_counts: dict[str, int] | None = None,
) -> dict[str, Any]:
    counts = ledger_counts or {"agentCount": 0, "eventCount": 0, "checkpointCount": 0}
    entries = [
        {
            "effectId": "e24-evidence-spine-" + run_id,
            "effectType": "EVIDENCE_CAPTURE",
            "operation": "E24 run ledger export",
            "status": "FINALIZED" if finalized else "CAPTURING",
            "evidenceRef": "simulationRunEvidencePack",
            "note": (
                f"Experiment-service recorded {counts['agentCount']} agents, "
                f"{counts['eventCount']} events, and {counts['checkpointCount']} checkpoints."
            ),
        },
        {
            "effectId": "python-scenario-lab-" + run_id,
            "effectType": "REMOTE_PROVIDER_LIFECYCLE",
            "operation": "Python remote provider clock-driven lifecycle",
            "status": status,
            "phase": phase,
            "evidenceRef": "simulationTimelineJson",
            "note": "Python provider activated the cohort through agent-runtime using public HTTP contract headers.",
        },
    ]
    for index, checkpoint in enumerate(list_items((ledger or {}).get("checkpoints"))[:8]):
        checkpoint_id = trim(checkpoint.get("checkpointId")) or f"checkpoint-{index + 1}"
        entries.append(
            {
                "effectId": "checkpoint-" + safe_token(checkpoint_id),
                "effectType": trim(checkpoint.get("checkpointType")).upper() or "DOMAIN_CHECKPOINT",
                "operation": "Remote-domain checkpoint persisted",
                "status": "RECORDED",
                "agentId": trim(checkpoint.get("agentId")),
                "domainId": trim(checkpoint.get("domainId")) or DOMAIN_ID,
                "evidenceRef": trim(checkpoint.get("artifactRef")) or "simulationRunEvidencePack",
                "note": "A domain state checkpoint was persisted by experiment-service.",
            }
        )
    return {
        "schemaVersion": "customer_outcome_side_effect_ledger.v1",
        "mode": "CLOCK_DRIVEN_HYBRID_FINALIZED" if finalized else "CLOCK_DRIVEN_HYBRID_LIVE",
        "counts": counts,
        "entries": entries,
    }


def build_clock_driven_state_mutations(
    run_id: str,
    cohort_size: int,
    *,
    finalized: bool,
    ledger: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    checkpoint_mutations = build_checkpoint_state_mutations(run_id)
    if checkpoint_mutations:
        return checkpoint_mutations
    mutations = []
    for index, checkpoint in enumerate(list_items((ledger or {}).get("checkpoints"))[:HYBRID_STATE_MUTATION_SAMPLE_LIMIT]):
        agent_id = trim(checkpoint.get("agentId"))
        mutations.append(
            {
                "mutationId": "state-checkpoint-" + safe_token(trim(checkpoint.get("checkpointId")) or str(index + 1)),
                "mutationType": trim(checkpoint.get("checkpointType")).upper() or "DOMAIN_STATE_CHECKPOINT",
                "label": "Remote-domain state checkpoint",
                "description": "Captured remote-domain state checkpoint for "
                + (readable_agent_label(agent_id) or "Python banking customer")
                + ".",
                "branchId": "clock-driven-hybrid-run",
                "branchRole": "proposed",
                "personaName": readable_agent_label(agent_id) or "Python banking customer",
                "segment": trim(checkpoint.get("domainId")) or DOMAIN_ID,
                "deltaValue": 1,
                "unit": "events",
                "severity": "Observed",
                "status": "OBSERVED" if finalized else "CAPTURING",
                "sourceEffectId": "e24-evidence-spine-" + run_id,
                "evidenceRef": "simulationRunEvidencePack",
                "artifactRef": trim(checkpoint.get("artifactRef")) or "simulationRunEvidencePack",
                "checkpointRef": trim(checkpoint.get("checkpointId")) or f"checkpoint-{index + 1}",
                "stateSummary": {
                    "domainId": trim(checkpoint.get("domainId")) or DOMAIN_ID,
                    "operation": "checkpoint",
                    "executedWorkCount": 0,
                    "appliedTransactionCount": 0,
                    "rejectedTransactionCount": 0,
                },
            }
        )
    return mutations


def build_checkpoint_state_mutations(run_id: str) -> list[dict[str, Any]]:
    checkpoint_dir = workspace_root() / "output" / "simulation-runs" / run_id / "checkpoints"
    if not checkpoint_dir.is_dir():
        return []
    mutations = []
    for index, checkpoint_path in enumerate(sorted(checkpoint_dir.glob("*.json"))[:HYBRID_STATE_MUTATION_SAMPLE_LIMIT]):
        checkpoint = read_json_file(checkpoint_path)
        if not checkpoint:
            continue
        mutations.append(build_checkpoint_state_mutation(run_id, checkpoint_path, checkpoint, index))
    return mutations


def build_checkpoint_state_mutation(
    run_id: str,
    checkpoint_path: Path,
    checkpoint: dict[str, Any],
    index: int,
) -> dict[str, Any]:
    next_state = checkpoint.get("nextState") if isinstance(checkpoint.get("nextState"), dict) else {}
    data = next_state.get("data") if isinstance(next_state.get("data"), dict) else {}
    runtime_metadata = checkpoint.get("runtimeMetadata") if isinstance(checkpoint.get("runtimeMetadata"), dict) else {}
    status_view = checkpoint.get("statusView") if isinstance(checkpoint.get("statusView"), dict) else {}
    customer_state = status_view.get("customerState") if isinstance(status_view.get("customerState"), dict) else {}
    execution_log = list_items(data.get("executionLog"))
    applied_transactions = int_or_default(data.get("appliedTransactionCount"), 0)
    rejected_transactions = int_or_default(data.get("rejectedTransactionCount"), 0)
    executed_work = int_or_default(data.get("executedWorkCount"), len(execution_log))
    rejected_work = sum(1 for entry in execution_log if trim(entry.get("status")).upper() == "REJECTED")
    action_count = max(1, executed_work, applied_transactions + rejected_transactions)
    persona_name = (
        trim(data.get("personaName"))
        or trim((data.get("customerProfile") if isinstance(data.get("customerProfile"), dict) else {}).get("fullName"))
        or trim(data.get("agentId"))
        or trim(customer_state.get("agentId"))
        or f"Synthetic customer {index + 1}"
    )
    domain_id = trim(data.get("domainId")) or trim(runtime_metadata.get("domainId")) or DOMAIN_ID
    mutation_type = checkpoint_mutation_type(
        applied_transactions,
        rejected_transactions,
        rejected_work,
        executed_work,
    )
    return {
        "mutationId": f"state-{index + 1}-" + safe_token(persona_name),
        "mutationType": mutation_type,
        "label": checkpoint_mutation_label(mutation_type),
        "description": checkpoint_mutation_description(
            persona_name,
            applied_transactions,
            rejected_transactions,
            executed_work,
        ),
        "branchId": "clock-driven-hybrid-run",
        "branchRole": "proposed",
        "personaName": persona_name,
        "segment": trim(data.get("segment")) or domain_id + "-runtime-state",
        "deltaValue": action_count,
        "unit": "events",
        "severity": "Review" if rejected_transactions > 0 or rejected_work > 0 else "Observed",
        "status": "REVIEW" if rejected_transactions > 0 or rejected_work > 0 else "OBSERVED",
        "sourceEffectId": "e24-evidence-spine-" + run_id,
        "evidenceRef": "simulationRunEvidencePack",
        "artifactRef": "/api/dashboard/simulation-runs/" + quote(run_id, safe="") + "/export",
        "checkpointRef": checkpoint_path.name,
        "stateSummary": {
            "domainId": domain_id,
            "operation": trim(data.get("lastAction")) or trim(checkpoint.get("operation")) or "checkpoint",
            "executedWorkCount": executed_work,
            "appliedTransactionCount": applied_transactions,
            "rejectedTransactionCount": rejected_transactions,
            "currentBalance": customer_state.get("currentBalance") or status_view.get("totalBalance"),
        },
    }


def checkpoint_mutation_type(
    applied_transactions: int,
    rejected_transactions: int,
    rejected_work: int,
    executed_work: int,
) -> str:
    if rejected_transactions > 0 or rejected_work > 0:
        return "REMOTE_DOMAIN_WORK_REJECTED"
    if applied_transactions > 0:
        return "REMOTE_DOMAIN_TRANSACTION_POSTED"
    if executed_work > 0:
        return "REMOTE_DOMAIN_WORK_EXECUTED"
    return "REMOTE_DOMAIN_STATE_CHECKPOINT"


def checkpoint_mutation_label(mutation_type: str) -> str:
    if mutation_type == "REMOTE_DOMAIN_WORK_REJECTED":
        return "Remote-domain work rejected"
    if mutation_type == "REMOTE_DOMAIN_TRANSACTION_POSTED":
        return "Remote-domain transaction posted"
    if mutation_type == "REMOTE_DOMAIN_WORK_EXECUTED":
        return "Remote-domain work executed"
    return "Remote-domain state checkpoint"


def checkpoint_mutation_description(
    persona_name: str,
    applied_transactions: int,
    rejected_transactions: int,
    executed_work: int,
) -> str:
    if rejected_transactions > 0:
        return (
            f"Captured {rejected_transactions} rejected transaction(s) for {persona_name} "
            "during clock-driven scheduled work."
        )
    if applied_transactions > 0:
        return (
            f"Captured {applied_transactions} posted transaction(s) for {persona_name} "
            "during clock-driven scheduled work."
        )
    if executed_work > 0:
        return f"Captured {executed_work} executed work item(s) for {persona_name}."
    return f"Captured remote-domain state checkpoint for {persona_name}."


def count_rejected_checkpoint_work(run_id: str, state_mutations: list[dict[str, Any]]) -> int:
    checkpoint_dir = workspace_root() / "output" / "simulation-runs" / run_id / "checkpoints"
    if not checkpoint_dir.is_dir():
        return sum(
            1
            for mutation in state_mutations
            if trim(mutation.get("mutationType")).upper() == "REMOTE_DOMAIN_WORK_REJECTED"
        )
    rejected_work_keys_by_subject: dict[str, set[str]] = {}
    rejected_transaction_count_by_subject: dict[str, int] = {}
    for checkpoint_path in sorted(checkpoint_dir.glob("*.json")):
        checkpoint = read_json_file(checkpoint_path)
        next_state = checkpoint.get("nextState") if isinstance(checkpoint.get("nextState"), dict) else {}
        data = next_state.get("data") if isinstance(next_state.get("data"), dict) else {}
        subject_key = rejected_work_subject_key(checkpoint, data)
        execution_log = list_items(data.get("executionLog"))
        rejected_transactions = int_or_default(data.get("rejectedTransactionCount"), 0)
        if rejected_transactions > 0:
            rejected_transaction_count_by_subject[subject_key] = max(
                rejected_transaction_count_by_subject.get(subject_key, 0),
                rejected_transactions,
            )
        for index, entry in enumerate(execution_log):
            if trim(entry.get("status")).upper() != "REJECTED":
                continue
            rejected_work_keys_by_subject.setdefault(subject_key, set()).add(
                rejected_work_key(entry, checkpoint_path.name, index)
            )
    subjects = set(rejected_work_keys_by_subject) | set(rejected_transaction_count_by_subject)
    return sum(
        max(
            len(rejected_work_keys_by_subject.get(subject, set())),
            rejected_transaction_count_by_subject.get(subject, 0),
        )
        for subject in subjects
    )


def rejected_work_subject_key(checkpoint: dict[str, Any], data: dict[str, Any]) -> str:
    runtime_metadata = checkpoint.get("runtimeMetadata") if isinstance(checkpoint.get("runtimeMetadata"), dict) else {}
    status_view = checkpoint.get("statusView") if isinstance(checkpoint.get("statusView"), dict) else {}
    customer_state = status_view.get("customerState") if isinstance(status_view.get("customerState"), dict) else {}
    domain_id = trim(data.get("domainId")) or trim(runtime_metadata.get("domainId")) or DOMAIN_ID
    subject_id = (
        trim(data.get("agentId"))
        or trim(customer_state.get("agentId"))
        or trim(data.get("personaName"))
        or trim((data.get("customerProfile") or {}).get("fullName") if isinstance(data.get("customerProfile"), dict) else "")
        or "run"
    )
    return f"{domain_id}:{subject_id}"


def rejected_work_key(entry: dict[str, Any], checkpoint_ref: str, index: int) -> str:
    work_id = trim(entry.get("workId"))
    scheduled_for = (
        trim(entry.get("occurrencePeriod"))
        or trim(entry.get("scheduledForEpochMs"))
        or trim(entry.get("scheduledAtEpochMs"))
        or trim(entry.get("appliedAtEpochMs"))
    )
    scheduled_work_id = f"{work_id}@{scheduled_for}" if work_id and scheduled_for else ""
    stable_key = (
        trim(entry.get("occurrenceKey"))
        or trim(entry.get("idempotencyKey"))
        or trim(entry.get("operationId"))
        or scheduled_work_id
        or work_id
    )
    if stable_key:
        return stable_key
    description_key = trim(entry.get("workKind")) or trim(entry.get("description")) or trim(entry.get("failureMessage"))
    if description_key:
        return f"anonymous:{checkpoint_ref}:{index}:{description_key}"
    return f"anonymous-rejected-entry-{checkpoint_ref}:{index}"


def read_json_file(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        return dict(payload) if isinstance(payload, dict) else {}
    except Exception:
        return {}


def build_clock_driven_domain_scores(
    cohort_size: int,
    evidence_signal_count: int,
    *,
    timeline_event_count: int,
    state_change_count: int,
    rejected_work_count: int,
    finalized: bool,
) -> list[dict[str, Any]]:
    customer_status = "SCORED" if finalized else "CAPTURING"
    if not finalized:
        customer_recommendation = "WATCH"
        customer_reason = "Evidence is still being captured; finalize the run before treating this as a decision score."
    elif rejected_work_count >= 25:
        customer_recommendation = "BLOCK"
        customer_reason = "Too many rejected domain actions to treat the candidate as launch-ready."
    elif rejected_work_count > 0 or state_change_count > 0:
        customer_recommendation = "WATCH"
        customer_reason = "The candidate produced observable domain impact and needs human review."
    else:
        customer_recommendation = "REVIEW"
        customer_reason = "Evidence is captured, but the domain scorecard does not claim an automatic launch decision."
    evidence_recommendation = "WATCH" if evidence_signal_count > 0 else "BLOCK"
    evidence_reason = (
        "Evidence exists and should be reviewed before any launch decision."
        if evidence_signal_count > 0
        else "No evidence signals were captured, so the report cannot support a decision."
    )
    return [
        {
            "schemaVersion": "domain_outcome_score.v1",
            "scorecardId": "banking.customer-impact.v1",
            "label": "Banking customer-impact scorecard",
            "version": "0.1.0",
            "status": customer_status,
            "recommendation": customer_recommendation,
            "reason": customer_reason,
            "supportLevel": "EVIDENCE_LINKED",
            "metrics": [
                {
                    "metricId": "activatedSyntheticCustomers",
                    "label": "Activated synthetic customers",
                    "value": cohort_size,
                    "unit": "count",
                    "evidenceRef": "simulationRunEvidencePack",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "metricId": "runtimeEvidenceSignals",
                    "label": "Runtime evidence signals",
                    "value": evidence_signal_count,
                    "unit": "count",
                    "evidenceRef": "simulationRunEvidencePack",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "metricId": "affectedStateChanges",
                    "label": "Affected state changes",
                    "value": state_change_count,
                    "unit": "count",
                    "evidenceRef": "branchStateProjectionJson",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "metricId": "rejectedWorkItems",
                    "label": "Rejected work items",
                    "value": rejected_work_count,
                    "unit": "count",
                    "evidenceRef": "branchStateProjectionJson",
                    "supportLevel": "EVIDENCE_LINKED",
                },
            ],
            "gates": [
                {
                    "gateId": "banking.evidence.runtime-linked",
                    "label": "Runtime evidence linked",
                    "status": "PASS" if evidence_signal_count > 0 else "WATCH",
                    "evidenceRef": "simulationRunEvidencePack",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "gateId": "banking.evidence.state-linked",
                    "label": "State evidence linked",
                    "status": "PASS" if state_change_count >= 0 else "WATCH",
                    "evidenceRef": "branchStateProjectionJson",
                    "supportLevel": "EVIDENCE_LINKED",
                }
            ],
            "limitations": [
                "This is stress-test evidence under configured assumptions, not a production forecast.",
                "Provider-internal banking actions require emitted evidence events to be scored.",
            ],
        },
        {
            "schemaVersion": "domain_outcome_score.v1",
            "scorecardId": "banking.evidence-readiness.v1",
            "label": "Banking evidence-readiness scorecard",
            "version": "0.1.0",
            "status": customer_status,
            "recommendation": evidence_recommendation,
            "reason": evidence_reason,
            "supportLevel": "EVIDENCE_LINKED",
            "metrics": [
                {
                    "metricId": "timelineEvents",
                    "label": "Timeline events",
                    "value": timeline_event_count,
                    "unit": "count",
                    "evidenceRef": "simulationTimelineJson",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "metricId": "stateChanges",
                    "label": "Domain state changes",
                    "value": state_change_count,
                    "unit": "count",
                    "evidenceRef": "branchStateProjectionJson",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "metricId": "evidenceSignals",
                    "label": "Evidence signals",
                    "value": evidence_signal_count,
                    "unit": "count",
                    "evidenceRef": "simulationRunEvidencePack",
                    "supportLevel": "EVIDENCE_LINKED",
                },
            ],
            "gates": [
                {
                    "gateId": "banking.evidence.minimum-signal-volume",
                    "label": "Minimum evidence volume",
                    "status": "PASS" if evidence_signal_count >= 1 else "FAIL",
                    "evidenceRef": "simulationRunEvidencePack",
                    "supportLevel": "EVIDENCE_LINKED",
                },
                {
                    "gateId": "banking.evidence.timeline-linked",
                    "label": "Timeline linked",
                    "status": "PASS" if timeline_event_count >= 1 else "FAIL",
                    "evidenceRef": "simulationTimelineJson",
                    "supportLevel": "EVIDENCE_LINKED",
                },
            ],
            "limitations": [
                "This scorecard validates evidence availability, not banking business risk.",
                "Use it alongside a customer-impact scorecard before making product decisions.",
            ],
        },
    ]


def load_runtime_decision_audit(run_id: str | None = None, *, include_decisions: bool = True) -> dict[str, Any]:
    try:
        url = agent_runtime_url() + "/api/agents/hybrid-decisions"
        query: list[str] = []
        normalized_run_id = trim(run_id)
        if normalized_run_id:
            query.append("simulationRunId=" + quote(normalized_run_id, safe=""))
        if not include_decisions:
            query.append("includeDecisions=false")
        if query:
            url += "?" + "&".join(query)
        payload = get_json(url)
        agents = [dict(item) for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []
    except Exception:
        agents = []
    decision_count = 0
    fallback_count = 0
    decision_window_count = 0
    decision_opportunity_count = 0
    decision_admitted_count = 0
    decision_deferred_count = 0
    deferred_by_reason: dict[str, int] = {}
    for agent in agents:
        decision_count += int_or_default(agent.get("totalDecisionCount"), int_or_default(agent.get("eventCount"), 0))
        fallback_count += int_or_default(agent.get("fallbackCount"), 0)
        window_summary = agent.get("decisionWindowSummary") if isinstance(agent.get("decisionWindowSummary"), dict) else {}
        decision_window_count += int_or_default(agent.get("decisionWindowCount"), int_or_default(window_summary.get("windowCount"), 0))
        agent_opportunities = int_or_default(agent.get("decisionOpportunityCount"), int_or_default(window_summary.get("opportunityCount"), 0))
        agent_admitted = int_or_default(agent.get("decisionAdmittedCount"), int_or_default(window_summary.get("admittedCount"), int_or_default(agent.get("totalDecisionCount"), int_or_default(agent.get("eventCount"), 0))))
        agent_deferred = int_or_default(agent.get("decisionDeferredCount"), int_or_default(window_summary.get("deferredCount"), max(0, agent_opportunities - agent_admitted)))
        decision_opportunity_count += agent_opportunities
        decision_admitted_count += agent_admitted
        decision_deferred_count += agent_deferred
        reason_counts = agent.get("decisionDeferredByReason")
        if not isinstance(reason_counts, dict):
            reason_counts = window_summary.get("deferredByReason") if isinstance(window_summary.get("deferredByReason"), dict) else {}
        for reason, count in reason_counts.items():
            normalized_reason = trim(reason) or "unknown"
            deferred_by_reason[normalized_reason] = deferred_by_reason.get(normalized_reason, 0) + int_or_default(count, 0)
    opportunity_ledger = build_opportunity_ledger_totals(agents)
    return {
        "schemaVersion": "hybrid_decision_audit_live.v1",
        "source": "agent-runtime",
        "totals": {
            "agentCount": len(agents),
            "decisionCount": decision_count,
            "fallbackCount": fallback_count,
            "decisionWindowCount": decision_window_count,
            "decisionOpportunityCount": decision_opportunity_count,
            "decisionAdmittedCount": decision_admitted_count,
            "decisionDeferredCount": decision_deferred_count,
            "decisionDeferredByReason": deferred_by_reason,
            "opportunityLedger": opportunity_ledger,
        },
        "summary": {
            "agentCount": len(agents),
            "decisionCount": decision_count,
            "fallbackCount": fallback_count,
            "fallbackRateP50": fallback_count / max(1, decision_count),
            "dominantDecisionAction": "--",
            "dominantResponseFormat": "--",
            "decisionWindowCount": decision_window_count,
            "decisionOpportunityCount": decision_opportunity_count,
            "decisionAdmittedCount": decision_admitted_count,
            "decisionDeferredCount": decision_deferred_count,
            "decisionDeferredByReason": deferred_by_reason,
            "opportunityLedger": opportunity_ledger,
        },
        "opportunityLedger": opportunity_ledger,
        "agents": agents,
    }


def build_opportunity_ledger_totals(agents: list[dict[str, Any]]) -> dict[str, Any]:
    status_counts = {status: 0 for status in sorted(OPPORTUNITY_LIFECYCLE_STATUSES)}
    deferred_reason_counts: dict[str, int] = {}
    historical_deferral_reason_counts: dict[str, int] = {}
    agent_ids: set[str] = set()
    simulation_run_ids: set[str] = set()
    counters = {
        "generatedOpportunities": 0,
        "terminalOpportunities": 0,
        "remainingBacklog": 0,
        "admittedOpportunities": 0,
        "totalAttempts": 0,
        "validDecisions": 0,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "historicalDeferralCount": 0,
    }
    invalid_agent_audit_count = 0
    for agent in agents:
        if "opportunityAudit" not in agent:
            continue
        audit = agent.get("opportunityAudit")
        if not opportunity_agent_audit_is_valid(audit):
            invalid_agent_audit_count += 1
            continue
        assert isinstance(audit, dict)
        agent_id = trim(audit.get("agentId"))
        if agent_id in agent_ids:
            invalid_agent_audit_count += 1
            continue
        agent_ids.add(agent_id)
        simulation_run_ids.add(trim(audit.get("simulationRunId")))
        for field in counters:
            counters[field] += int(audit[field])
        raw_status_counts = audit["statusCounts"]
        assert isinstance(raw_status_counts, dict)
        for status in OPPORTUNITY_LIFECYCLE_STATUSES:
            status_counts[status] += int(raw_status_counts[status])
        raw_deferred_reasons = audit["deferredReasonCounts"]
        assert isinstance(raw_deferred_reasons, dict)
        for reason, count in raw_deferred_reasons.items():
            deferred_reason_counts[str(reason)] = deferred_reason_counts.get(str(reason), 0) + int(count)
        raw_historical_deferral_reasons = audit["historicalDeferralReasonCounts"]
        assert isinstance(raw_historical_deferral_reasons, dict)
        for reason, count in raw_historical_deferral_reasons.items():
            historical_deferral_reason_counts[str(reason)] = (
                historical_deferral_reason_counts.get(str(reason), 0) + int(count)
            )
    return {
        "schemaVersion": OPPORTUNITY_AUDIT_TOTALS_SCHEMA_VERSION,
        "agentCount": len(agent_ids),
        "invalidAgentAuditCount": invalid_agent_audit_count,
        "simulationRunIds": sorted(simulation_run_ids),
        "consistentRunScope": len(simulation_run_ids) <= 1,
        **counters,
        "statusCounts": status_counts,
        "deferredReasonCounts": dict(sorted(deferred_reason_counts.items())),
        "historicalDeferralReasonCounts": dict(sorted(historical_deferral_reason_counts.items())),
    }


def opportunity_agent_audit_is_valid(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    if (
        value.get("schemaVersion") != OPPORTUNITY_AGENT_AUDIT_SCHEMA_VERSION
        or not trim(value.get("simulationRunId"))
        or not trim(value.get("agentId"))
    ):
        return False
    counter_fields = (
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
    counters = {field: strict_non_negative_int(value.get(field)) for field in counter_fields}
    if any(counter is None for counter in counters.values()):
        return False
    raw_status_counts = value.get("statusCounts")
    if not isinstance(raw_status_counts, dict) or set(raw_status_counts) != OPPORTUNITY_LIFECYCLE_STATUSES:
        return False
    status_counts = {
        status: strict_non_negative_int(raw_status_counts.get(status))
        for status in OPPORTUNITY_LIFECYCLE_STATUSES
    }
    if any(count is None for count in status_counts.values()):
        return False
    deferred_reason_counts = strict_opportunity_reason_counts(value.get("deferredReasonCounts"))
    historical_deferral_reason_counts = strict_opportunity_reason_counts(
        value.get("historicalDeferralReasonCounts")
    )
    if deferred_reason_counts is None or historical_deferral_reason_counts is None:
        return False
    generated = counters["generatedOpportunities"]
    terminal = counters["terminalOpportunities"]
    backlog = counters["remainingBacklog"]
    admitted = counters["admittedOpportunities"]
    attempts = counters["totalAttempts"]
    valid_decisions = counters["validDecisions"]
    recoverable = counters["recoverableRecordedDecisions"]
    succeeded_without_evidence = counters["succeededWithoutDecisionEvidence"]
    evidence_gaps = counters["decisionEvidenceGaps"]
    historical_deferral_count = counters["historicalDeferralCount"]
    assert all(counter is not None for counter in counters.values())
    assert all(count is not None for count in status_counts.values())
    expected_backlog = status_counts["PENDING"] + status_counts["RUNNING"] + status_counts["DEFERRED"]
    return (
        generated == sum(status_counts.values())
        and terminal == generated - expected_backlog
        and backlog == expected_backlog
        and admitted <= generated
        and attempts >= admitted
        and succeeded_without_evidence <= status_counts["SUCCEEDED"]
        and valid_decisions == status_counts["SUCCEEDED"] - succeeded_without_evidence
        and recoverable <= backlog
        and evidence_gaps
        == status_counts["BLOCKED"]
        + status_counts["FAILED"]
        + status_counts["CANCELLED"]
        + succeeded_without_evidence
        and sum(deferred_reason_counts.values()) == status_counts["DEFERRED"]
        and sum(historical_deferral_reason_counts.values()) == historical_deferral_count
    )


def strict_opportunity_reason_counts(value: Any) -> dict[str, int] | None:
    if not isinstance(value, dict):
        return None
    counts: dict[str, int] = {}
    for reason_code, raw_count in value.items():
        count = strict_non_negative_int(raw_count)
        proof_safe_reason = (
            isinstance(reason_code, str)
            and (
                reason_code in OPPORTUNITY_DEFERRED_REASON_CODES_V1
                or (reason_code == LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE and count == 0)
            )
        )
        if count is None or not proof_safe_reason:
            return None
        counts[reason_code] = count
    return counts


def wait_for_runtime_decision_audit_to_settle(run_id: str) -> dict[str, Any]:
    drain_result = wait_for_runtime_tick_drain(run_id)
    latest = with_runtime_tick_drain(load_runtime_decision_audit(run_id, include_decisions=False), drain_result)
    last_signature = decision_audit_signature(latest)
    stable_samples = 0
    timeout_ms = max(1_000, int_or_default(HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MS, 15_000))
    poll_ms = max(0, int_or_default(HYBRID_DECISION_AUDIT_SETTLE_POLL_MS, 250))
    required_stable_samples = max(1, int_or_default(HYBRID_DECISION_AUDIT_STABLE_SAMPLES, 4))
    deadline = time.monotonic() + (timeout_ms / 1000)
    while time.monotonic() < deadline:
        if poll_ms > 0:
            time.sleep(poll_ms / 1000)
        current = with_runtime_tick_drain(load_runtime_decision_audit(run_id, include_decisions=False), drain_result)
        current_signature = decision_audit_signature(current)
        latest = current
        if current_signature == last_signature:
            stable_samples += 1
            if stable_samples >= required_stable_samples and decision_audit_has_settled_window_telemetry(latest):
                return latest
        else:
            stable_samples = 0
            last_signature = current_signature
    return latest


def finalized_runtime_decision_audit(run_id: str, parameters: dict[str, Any]) -> dict[str, Any]:
    compact_audit = wait_for_runtime_decision_audit_to_settle(run_id)
    decision_mode = normalize_agent_decision_mode(parameters.get("agentDecisionMode"))
    decision_cadence = normalize_agent_decision_cadence(parameters.get("agentDecisionCadence"))
    if decision_mode != AGENT_DECISION_MODE_REAL_LLM and decision_cadence != AGENT_DECISION_CADENCE_EVENT_ONLY:
        return compact_audit
    full_audit = load_runtime_decision_audit(run_id, include_decisions=True)
    full_audit["runtimeTickDrain"] = dict(compact_audit.get("runtimeTickDrain", {}) or {})
    return full_audit


def wait_for_runtime_tick_drain(run_id: str | None = None) -> dict[str, Any]:
    normalized_run_id = trim(run_id)
    if not normalized_run_id:
        return runtime_tick_drain_result(
            False,
            "MISSING_RUN_ID",
            "Runtime tick drain could not be verified because the simulation run id was missing.",
        )
    timeout_ms = max(1_000, int_or_default(RUNTIME_TICK_DRAIN_TIMEOUT_MS, 60_000))
    poll_ms = max(0, int_or_default(HYBRID_DECISION_AUDIT_SETTLE_POLL_MS, 250))
    required_stable_samples = max(1, int_or_default(RUNTIME_TICK_DRAIN_STABLE_SAMPLES, 20))
    deadline = time.monotonic() + (timeout_ms / 1000)
    last_signature: tuple[int, ...] | None = None
    stable_samples = 0
    while time.monotonic() < deadline:
        try:
            telemetry = http_json(
                "GET",
                agent_runtime_url() + "/api/agents/runtime-state?simulationRunId=" + quote(normalized_run_id, safe=""),
                timeout=2.0,
            )
        except Exception:
            return runtime_tick_drain_result(
                False,
                "UNAVAILABLE",
                "Runtime tick drain could not be verified because agent-runtime runtime-state telemetry was unavailable.",
            )
        payload = telemetry if isinstance(telemetry, dict) else {}
        if not runtime_tick_drain_response_matches_run(payload, normalized_run_id):
            return runtime_tick_drain_result(
                False,
                "UNVERIFIED_SCOPE",
                "Runtime tick drain could not be verified because agent-runtime returned an unscoped or mismatched runtime-state payload.",
            )
        signature = runtime_tick_drain_signature(payload)
        if runtime_tick_drain_complete(payload):
            if signature == last_signature:
                stable_samples += 1
            else:
                last_signature = signature
                stable_samples = 1
            if stable_samples >= required_stable_samples:
                return runtime_tick_drain_result(
                    True,
                    "DRAINED",
                    "Runtime tick drain was verified for this simulation run before finalization.",
                )
        else:
            last_signature = signature
            stable_samples = 0
        if poll_ms > 0:
            time.sleep(poll_ms / 1000)
    return runtime_tick_drain_result(
        False,
        "TIMEOUT",
        "Runtime tick drain could not be verified before the finalization drain timeout.",
    )


def runtime_tick_drain_result(verified: bool, status: str, message: str) -> dict[str, Any]:
    return {
        "verified": bool(verified),
        "status": trim(status) or "UNVERIFIED",
        "message": trim(message),
    }


def with_runtime_tick_drain(decision_audit: dict[str, Any], drain_result: dict[str, Any] | None) -> dict[str, Any]:
    payload = dict(decision_audit or {})
    payload["runtimeTickDrain"] = (
        drain_result
        if isinstance(drain_result, dict)
        else runtime_tick_drain_result(
            False,
            "UNVERIFIED",
            "Runtime tick drain verification did not return a structured result.",
        )
    )
    return payload


def runtime_tick_drain_response_matches_run(telemetry: dict[str, Any], run_id: str) -> bool:
    return (
        trim(telemetry.get("scope")) == "simulationRun"
        and trim(telemetry.get("simulationRunId")) == trim(run_id)
    )


def runtime_tick_drain_complete(telemetry: dict[str, Any]) -> bool:
    tick_execution = telemetry.get("tickExecution") if isinstance(telemetry.get("tickExecution"), dict) else {}
    if not tick_execution:
        return (
            int_or_default(telemetry.get("activeTickTasks"), 0) <= 0
            and int_or_default(telemetry.get("runningAgents"), 0) <= 0
            and int_or_default(telemetry.get("pendingAgents"), 0) <= 0
        )
    submitted_tasks = int_or_default(tick_execution.get("submittedTasks"), 0)
    completed_tasks = int_or_default(tick_execution.get("completedTasks"), 0)
    return (
        int_or_default(tick_execution.get("activeTasks"), 0) <= 0
        and int_or_default(tick_execution.get("runningAgents"), 0) <= 0
        and int_or_default(tick_execution.get("pendingAgents"), 0) <= 0
        and completed_tasks >= submitted_tasks
    )


def runtime_tick_drain_signature(telemetry: dict[str, Any]) -> tuple[int, ...]:
    tick_execution = telemetry.get("tickExecution") if isinstance(telemetry.get("tickExecution"), dict) else {}
    if not tick_execution:
        return (
            int_or_default(telemetry.get("activeTickTasks"), 0),
            int_or_default(telemetry.get("runningAgents"), 0),
            int_or_default(telemetry.get("pendingAgents"), 0),
            int_or_default(telemetry.get("activeAgents"), 0),
            int_or_default(telemetry.get("oldestBacklogMs"), 0),
        )
    return (
        int_or_default(tick_execution.get("activeTasks"), 0),
        int_or_default(tick_execution.get("runningAgents"), 0),
        int_or_default(tick_execution.get("pendingAgents"), 0),
        int_or_default(tick_execution.get("submittedTasks"), 0),
        int_or_default(tick_execution.get("completedTasks"), 0),
        int_or_default(tick_execution.get("failedTasks"), 0),
    )


def decision_audit_signature(decision_audit: dict[str, Any]) -> tuple[int, int, int, int, int, int, int]:
    summary = decision_audit.get("summary") if isinstance(decision_audit.get("summary"), dict) else {}
    totals = decision_audit.get("totals") if isinstance(decision_audit.get("totals"), dict) else {}
    return (
        int_or_default(summary.get("agentCount"), int_or_default(totals.get("agentCount"), 0)),
        int_or_default(summary.get("decisionCount"), int_or_default(totals.get("decisionCount"), 0)),
        int_or_default(summary.get("decisionWindowCount"), int_or_default(totals.get("decisionWindowCount"), 0)),
        int_or_default(summary.get("decisionOpportunityCount"), int_or_default(totals.get("decisionOpportunityCount"), 0)),
        int_or_default(summary.get("decisionAdmittedCount"), int_or_default(totals.get("decisionAdmittedCount"), 0)),
        int_or_default(summary.get("decisionDeferredCount"), int_or_default(totals.get("decisionDeferredCount"), 0)),
        int_or_default(summary.get("fallbackCount"), int_or_default(totals.get("fallbackCount"), 0)),
    )


def decision_audit_has_settled_window_telemetry(decision_audit: dict[str, Any]) -> bool:
    summary = decision_audit.get("summary") if isinstance(decision_audit.get("summary"), dict) else {}
    totals = decision_audit.get("totals") if isinstance(decision_audit.get("totals"), dict) else {}
    decision_count = int_or_default(summary.get("decisionCount"), int_or_default(totals.get("decisionCount"), 0))
    if decision_count <= 0:
        return True
    opportunity_count = int_or_default(
        summary.get("decisionOpportunityCount"),
        int_or_default(totals.get("decisionOpportunityCount"), 0),
    )
    admitted_count = int_or_default(
        summary.get("decisionAdmittedCount"),
        int_or_default(totals.get("decisionAdmittedCount"), 0),
    )
    window_count = int_or_default(
        summary.get("decisionWindowCount"),
        int_or_default(totals.get("decisionWindowCount"), 0),
    )
    if opportunity_count <= 0 and admitted_count <= 0 and window_count <= 0:
        return False
    return admitted_count >= decision_count


def build_clock_driven_experiment_card(summary: dict[str, Any]) -> str:
    headline = dict(summary.get("headline", {}) or {})
    readiness = dict(summary.get("decisionReadiness", {}) or {})
    run_config = dict(summary.get("runConfig", {}) or {})
    return "\n".join(
        [
            "# " + (trim(headline.get("title")) or "Python Clock-Driven Simulation"),
            "",
            "- Domain: banking-python",
            "- Execution mode: " + trim(run_config.get("executionMode")),
            "- Cohort: " + trim(run_config.get("cohortId")),
            "- Customers: " + str(run_config.get("cohortSize", 0)),
            "- Recommendation: " + trim(readiness.get("recommendation")),
            "",
            trim(readiness.get("reason")),
        ]
    )


def product_name_for(product_code: str) -> str:
    normalized = normalize_token(product_code)
    for product in DEFAULT_PRODUCT_CATALOG:
        if normalize_token(product.get("productCode")) == normalized:
            return trim(product.get("name")) or normalized
    return normalized or DEFAULT_PRODUCT_CODE


def product_category_for_code(product_code: str) -> str:
    normalized = normalize_token(product_code)
    for product in DEFAULT_PRODUCT_CATALOG:
        if normalize_token(product.get("productCode")) == normalized:
            return trim(product.get("productCategory")) or "REMOTE_ACCOUNT_PRODUCT"
    return "REMOTE_ACCOUNT_PRODUCT"


def encode_state(state: dict[str, Any]) -> dict[str, Any]:
    def encode(value: Any) -> Any:
        if isinstance(value, Decimal):
            return decimal_string(value)
        if isinstance(value, list):
            return [encode(item) for item in value]
        if isinstance(value, dict):
            return {key: encode(item) for key, item in value.items()}
        return value

    return encode(state)


def decode_state(state: dict[str, Any]) -> dict[str, Any]:
    decoded = dict(state)
    decoded["accounts"] = [
        {**account, "balance": Decimal(str(account.get("balance", 0))), "limit": Decimal(str(account.get("limit", 0)))}
        for account in decoded.get("accounts", [])
    ]
    hardship = dict(decoded.get("hardship", {}) or {})
    hardship["reliefGranted"] = Decimal(str(hardship.get("reliefGranted", 0)))
    decoded["hardship"] = hardship
    decoded["transactions"] = [
        {**transaction, "amount": Decimal(str(transaction.get("amount", 0)))}
        for transaction in decoded.get("transactions", [])
    ]
    return decoded


def segment_from_traits(raw_traits: Any) -> str:
    traits = [str(trait).lower() for trait in raw_traits or []]
    for segment in ("hardship", "fraud", "high-value", "escalation", "everyday"):
        if any(segment in trait for trait in traits):
            return segment
    return "everyday"


def trim(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def iso_now_plus(*, minutes: int) -> str:
    return (datetime.now(tz=UTC) + timedelta(minutes=minutes)).isoformat().replace("+00:00", "Z")


def utc_now_iso() -> str:
    return datetime.now(tz=UTC).isoformat().replace("+00:00", "Z")


def int_or_default(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def int_env(name: str, default: int) -> int:
    return int_or_default(os.getenv(name), default)


HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MS = max(
    1_000,
    int_env("ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MILLIS", 120_000),
)
HYBRID_DECISION_AUDIT_SETTLE_POLL_MS = max(
    100,
    int_env("ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS", 250),
)
HYBRID_DECISION_AUDIT_STABLE_SAMPLES = max(
    1,
    int_env("ASTRAFORM_HYBRID_DECISION_AUDIT_STABLE_SAMPLES", 4),
)
RUNTIME_TICK_DRAIN_STABLE_SAMPLES = max(
    1,
    int_env("ASTRAFORM_RUNTIME_TICK_DRAIN_STABLE_SAMPLES", 20),
)
RUNTIME_TICK_DRAIN_TIMEOUT_MS = max(
    1_000,
    int_env("ASTRAFORM_RUNTIME_TICK_DRAIN_TIMEOUT_MILLIS", 180_000),
)


def float_or_default(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def workspace_root() -> Path:
    configured = trim(os.getenv("ASTRAFORM_WORKSPACE_ROOT"))
    if configured:
        return Path(configured).resolve()
    return Path.cwd().resolve()


def agent_runtime_url() -> str:
    return strip_trailing_slash(os.getenv("AGENT_RUNTIME_URL") or "http://agent-runtime:8082")


def fetch_decision_opportunity_reconciliation(run_id: str) -> dict[str, Any]:
    try:
        reconciliation_base_url = (
            agent_runtime_url()
            + "/api/agents/simulation-runs/"
            + quote(run_id, safe="")
            + "/decision-opportunities"
        )
        response = get_json(
            reconciliation_base_url + "/reconciliation"
        )
        if not opportunity_reconciliation_is_valid(response, run_id):
            return unavailable_opportunity_reconciliation(run_id)
        return dict(response)
    except Exception:
        return unavailable_opportunity_reconciliation(run_id)


def unavailable_opportunity_reconciliation(run_id: str) -> dict[str, Any]:
    return {
        "schemaVersion": OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION,
        "simulationRunId": run_id,
        "status": "UNAVAILABLE",
        "ledgerReconciled": False,
        "generationMode": None,
        "generationSealed": False,
        "observedWorkWindows": 0,
        "generationObservedAt": None,
        "generationSealedAt": None,
        "generationSealedReplayContractVersion": None,
        "generationSealedReplayFenceDigest": None,
        "generationSealedReplayFenceLeaseOwner": None,
        "generationSealedReplayFenceExpiresAtEpochMs": None,
        "initializationManifestStatus": None,
        "expectedInitializationAgents": None,
        "registeredInitializationAgents": None,
        "resolvedInitializationAgents": None,
        "tickFailureCount": None,
        "tickFailureLedgerCount": None,
        "expectedGeneratedOpportunities": None,
        "rejectedTriggers": None,
        "migrationAnomalies": None,
        "generatedOpportunities": None,
        "terminalOpportunities": None,
        "remainingBacklog": None,
        "validDecisions": None,
        "recoverableRecordedDecisions": None,
        "succeededWithoutDecisionEvidence": None,
        "decisionEvidenceGaps": None,
        "statusCounts": {},
    }


def opportunity_reconciliation_is_partial(reconciliation: dict[str, Any]) -> bool:
    run_id = trim(reconciliation.get("simulationRunId")) if isinstance(reconciliation, dict) else ""
    return (
        not opportunity_reconciliation_is_valid(reconciliation, run_id)
        or reconciliation.get("ledgerReconciled") is not True
        or int_or_default(reconciliation.get("remainingBacklog"), 0) > 0
        or int_or_default(reconciliation.get("decisionEvidenceGaps"), 0) > 0
        or int_or_default(reconciliation.get("succeededWithoutDecisionEvidence"), 0) > 0
    )


def opportunity_reconciliation_is_valid(value: Any, expected_run_id: str) -> bool:
    if not isinstance(value, dict) or not expected_run_id:
        return False
    if (
        value.get("schemaVersion") != OPPORTUNITY_RECONCILIATION_SCHEMA_VERSION
        or trim(value.get("simulationRunId")) != expected_run_id
        or value.get("status") not in OPPORTUNITY_RECONCILIATION_STATUSES
        or type(value.get("ledgerReconciled")) is not bool
        or type(value.get("generationSealed")) is not bool
    ):
        return False

    numeric_fields = (
        "observedWorkWindows",
        "expectedInitializationAgents",
        "registeredInitializationAgents",
        "resolvedInitializationAgents",
        "tickFailureCount",
        "tickFailureLedgerCount",
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
    counters = {field: strict_non_negative_int(value.get(field)) for field in numeric_fields}
    if any(counter is None for counter in counters.values()) or not strict_iso_instant(value.get("asOf")):
        return False

    raw_status_counts = value.get("statusCounts")
    if not isinstance(raw_status_counts, dict) or set(raw_status_counts) != OPPORTUNITY_LIFECYCLE_STATUSES:
        return False
    status_counts = {
        status: strict_non_negative_int(raw_status_counts.get(status))
        for status in OPPORTUNITY_LIFECYCLE_STATUSES
    }
    if any(count is None for count in status_counts.values()):
        return False

    generated = counters["generatedOpportunities"]
    expected_generated = counters["expectedGeneratedOpportunities"]
    rejected_triggers = counters["rejectedTriggers"]
    migration_anomalies = counters["migrationAnomalies"]
    terminal = counters["terminalOpportunities"]
    backlog = counters["remainingBacklog"]
    valid_decisions = counters["validDecisions"]
    recoverable = counters["recoverableRecordedDecisions"]
    succeeded_without_evidence = counters["succeededWithoutDecisionEvidence"]
    evidence_gaps = counters["decisionEvidenceGaps"]
    status_total = sum(status_counts.values())
    expected_backlog = (
        status_counts["PENDING"] + status_counts["RUNNING"] + status_counts["DEFERRED"]
    )
    expected_terminal = status_total - expected_backlog
    if (
        generated != status_total
        or backlog != expected_backlog
        or terminal != expected_terminal
        or succeeded_without_evidence > status_counts["SUCCEEDED"]
        or valid_decisions != status_counts["SUCCEEDED"] - succeeded_without_evidence
        or recoverable > backlog
        or evidence_gaps
        != status_counts["BLOCKED"]
        + status_counts["FAILED"]
        + status_counts["CANCELLED"]
        + succeeded_without_evidence
    ):
        return False

    status = value["status"]
    ledger_reconciled = value["ledgerReconciled"]
    generation_sealed = value["generationSealed"]
    observed_windows = counters["observedWorkWindows"]
    expected_initialization_agents = counters["expectedInitializationAgents"]
    registered_initialization_agents = counters["registeredInitializationAgents"]
    resolved_initialization_agents = counters["resolvedInitializationAgents"]
    tick_failure_count = counters["tickFailureCount"]
    tick_failure_ledger_count = counters["tickFailureLedgerCount"]
    initialization_manifest_status = trim(value.get("initializationManifestStatus"))
    if status == "UNKNOWN_RUN":
        return (
            not ledger_reconciled
            and not generation_sealed
            and observed_windows == 0
            and expected_generated == 0
            and rejected_triggers == 0
            and migration_anomalies == 0
            and generated == 0
            and not initialization_manifest_status
            and expected_initialization_agents == 0
            and registered_initialization_agents == 0
            and resolved_initialization_agents == 0
            and tick_failure_count == 0
            and tick_failure_ledger_count == 0
            and value.get("generationMode") is None
            and value.get("generationObservedAt") is None
            and value.get("generationSealedAt") is None
            and value.get("generationSealedReplayContractVersion") is None
            and value.get("generationSealedReplayFenceDigest") is None
            and value.get("generationSealedReplayFenceLeaseOwner") is None
            and value.get("generationSealedReplayFenceExpiresAtEpochMs") is None
        )
    if (
        value.get("generationMode") != OPPORTUNITY_GENERATION_MODE
        or not strict_iso_instant(value.get("generationObservedAt"))
    ):
        return False
    seal_proof_absent = (
        not generation_sealed
        and value.get("generationSealedAt") is None
        and value.get("generationSealedReplayContractVersion") is None
        and value.get("generationSealedReplayFenceDigest") is None
        and value.get("generationSealedReplayFenceLeaseOwner") is None
        and value.get("generationSealedReplayFenceExpiresAtEpochMs") is None
    )
    sealed_replay_contract_version = strict_non_negative_int(
        value.get("generationSealedReplayContractVersion")
    )
    sealed_fence_digest = trim(value.get("generationSealedReplayFenceDigest"))
    sealed_fence_lease_owner = trim(value.get("generationSealedReplayFenceLeaseOwner"))
    sealed_fence_expires_at = strict_non_negative_int(
        value.get("generationSealedReplayFenceExpiresAtEpochMs")
    )
    seal_proof_present = (
        generation_sealed
        and strict_iso_instant(value.get("generationSealedAt"))
        and sealed_replay_contract_version is not None
        and sealed_replay_contract_version > 0
        and re.fullmatch(r"[0-9a-f]{64}", sealed_fence_digest) is not None
        and bool(sealed_fence_lease_owner)
        and sealed_fence_expires_at is not None
        and sealed_fence_expires_at > 0
    )
    closed_initialization_complete = (
        initialization_manifest_status == "CLOSED"
        and expected_initialization_agents > 0
        and registered_initialization_agents == expected_initialization_agents
        and resolved_initialization_agents == expected_initialization_agents
    )
    tick_failure_ledger_consistent = tick_failure_count == tick_failure_ledger_count
    if status == "TICK_FAILURE_LEDGER_INCONSISTENT":
        return (
            not ledger_reconciled
            and not tick_failure_ledger_consistent
            and (seal_proof_present if generation_sealed else seal_proof_absent)
        )
    if not tick_failure_ledger_consistent:
        return False
    if (
        generation_sealed
        and status == "INITIALIZATION_MANIFEST_UNVERIFIED"
        and initialization_manifest_status == "LEGACY_UNVERIFIED"
    ):
        return (
            not ledger_reconciled
            and expected_initialization_agents == 0
            and registered_initialization_agents == 0
            and resolved_initialization_agents == 0
            and tick_failure_count == 0
            and seal_proof_present
        )
    if not generation_sealed:
        if not seal_proof_absent or ledger_reconciled:
            return False
        if status == "GENERATION_FAILED":
            if initialization_manifest_status in {"UNDECLARED", "LEGACY_UNVERIFIED"}:
                return (
                    expected_initialization_agents == 0
                    and registered_initialization_agents == 0
                    and resolved_initialization_agents == 0
                )
            return (
                initialization_manifest_status in {"OPEN", "CLOSED"}
                and expected_initialization_agents > 0
                and resolved_initialization_agents <= registered_initialization_agents
            )
        if status == "INITIALIZATION_MANIFEST_UNVERIFIED":
            return (
                initialization_manifest_status in {"UNDECLARED", "LEGACY_UNVERIFIED"}
                and expected_initialization_agents == 0
                and registered_initialization_agents == 0
                and resolved_initialization_agents == 0
                and tick_failure_count == 0
            )
        if status == "INITIALIZATION_MANIFEST_OPEN":
            return (
                initialization_manifest_status == "OPEN"
                and expected_initialization_agents > 0
                and registered_initialization_agents < expected_initialization_agents
                and resolved_initialization_agents == 0
                and tick_failure_count == 0
            )
        if status == "INITIALIZATION_MANIFEST_INCONSISTENT":
            return (
                initialization_manifest_status == "CLOSED"
                and expected_initialization_agents > 0
                and resolved_initialization_agents <= registered_initialization_agents
            )
        if status == "INITIALIZATION_PENDING":
            return (
                initialization_manifest_status == "CLOSED"
                and expected_initialization_agents > 0
                and registered_initialization_agents == expected_initialization_agents
                and resolved_initialization_agents < expected_initialization_agents
                and tick_failure_count == 0
            )
        if status == "TICK_FAILURE_DEBT":
            return closed_initialization_complete and tick_failure_count > 0
        if status == "GENERATION_OPEN":
            return closed_initialization_complete and tick_failure_count == 0
        return False
    if (
        not closed_initialization_complete
        or tick_failure_count != 0
        or not seal_proof_present
    ):
        return False
    inconsistent_generation = (
        expected_generated != generated
        or rejected_triggers > 0
        or migration_anomalies > 0
    )
    if status == "EMPTY":
        return (
            ledger_reconciled
            and generated == 0
            and not inconsistent_generation
            and succeeded_without_evidence == 0
        )
    if status == "DRAINING":
        return (
            not ledger_reconciled
            and backlog > 0
            and not inconsistent_generation
            and succeeded_without_evidence == 0
        )
    if status == "INCONSISTENT":
        return not ledger_reconciled and (
            inconsistent_generation or succeeded_without_evidence > 0
        )
    if status == "RECONCILED":
        return (
            ledger_reconciled
            and generated > 0
            and backlog == 0
            and not inconsistent_generation
            and succeeded_without_evidence == 0
        )
    return False


def strict_non_negative_int(value: Any) -> int | None:
    return value if type(value) is int and value >= 0 else None


def strict_iso_instant(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
        return parsed.tzinfo is not None
    except ValueError:
        return False


def opportunity_reconciliation_reason(reconciliation: dict[str, Any]) -> str:
    status = trim(reconciliation.get("status")) or "UNAVAILABLE"
    if status.upper() == "UNAVAILABLE":
        return (
            "The simulation horizon completed, but the customer decision opportunity "
            "ledger could not be verified."
        )
    backlog = int_or_default(reconciliation.get("remainingBacklog"), 0)
    gaps = int_or_default(reconciliation.get("decisionEvidenceGaps"), 0)
    rejected_triggers = int_or_default(reconciliation.get("rejectedTriggers"), 0)
    migration_anomalies = int_or_default(reconciliation.get("migrationAnomalies"), 0)
    return (
        "The simulation horizon completed, but opportunity reconciliation is "
        f"{status} with {backlog} remaining, {gaps} explicit evidence gap(s), "
        f"{rejected_triggers} rejected trigger(s), and {migration_anomalies} migration anomaly/anomalies."
    )


def opportunity_evidence_reason(
    reconciliation: dict[str, Any],
    opportunity_agent_audit_verified: bool,
) -> str:
    reconciliation_reason = (
        opportunity_reconciliation_reason(reconciliation)
        if opportunity_reconciliation_is_partial(reconciliation)
        else ""
    )
    if opportunity_agent_audit_verified:
        return reconciliation_reason
    audit_reason = (
        "The run-level opportunity ledger could not be matched to a complete, run-scoped per-agent audit artifact."
    )
    return audit_reason if not reconciliation_reason else reconciliation_reason + " " + audit_reason


def assess_opportunity_evidence(
    finalized: bool,
    decision_audit: dict[str, Any],
    simulation_run_id: str,
    reconciliation: dict[str, Any],
) -> tuple[bool, bool, str]:
    if not finalized:
        return False, False, ""
    agent_audit_verified = opportunity_audit_matches_reconciliation(
        decision_audit,
        simulation_run_id,
        reconciliation,
    )
    partial = opportunity_reconciliation_is_partial(reconciliation) or not agent_audit_verified
    return (
        partial,
        agent_audit_verified,
        opportunity_evidence_reason(reconciliation, agent_audit_verified),
    )


def simulator_url() -> str:
    return strip_trailing_slash(os.getenv("SIMULATOR_URL") or "http://sim:8083")


def experiment_service_url() -> str:
    return strip_trailing_slash(os.getenv("EXPERIMENT_SERVICE_URL") or "http://experiment-service:7457")


def strip_trailing_slash(value: str) -> str:
    return trim(value).rstrip("/")


def http_json(
    method: str,
    url: str,
    payload: Any | None = None,
    *,
    headers: dict[str, str] | None = None,
    timeout: float = 30.0,
) -> Any:
    request_headers = {"Accept": "application/json", **(headers or {})}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(url, data=data, headers=request_headers, method=method.upper())
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            if not body:
                return {}
            text = body.decode("utf-8")
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return text
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method.upper()} {url} failed with status {exc.code}: {body}") from exc
    except URLError as exc:
        raise RuntimeError(f"{method.upper()} {url} failed: {exc.reason}") from exc


def post_json(url: str, payload: Any, *, headers: dict[str, str] | None = None) -> Any:
    return http_json("POST", url, payload, headers=headers)


def post_bytes(
    url: str,
    payload: bytes,
    *,
    headers: dict[str, str] | None = None,
    timeout: float = 30.0,
) -> bytes:
    request_headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        **(headers or {}),
    }
    request = Request(url, data=payload, headers=request_headers, method="POST")
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.read()
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"POST {url} failed with status {exc.code}: {body}") from exc
    except URLError as exc:
        raise RuntimeError(f"POST {url} failed: {exc.reason}") from exc


def get_json(url: str) -> Any:
    return http_json("GET", url)


def decimal_amount(value: Any) -> Decimal:
    try:
        return Decimal(str(value))
    except Exception as exc:
        raise ValueError("transaction leg amount must be a number") from exc


def first_positive_decimal(*values: Any) -> Decimal:
    for value in values:
        if value is None:
            continue
        if isinstance(value, Decimal):
            parsed = value
        else:
            try:
                parsed = Decimal(str(value))
            except Exception:
                continue
        if parsed > Decimal("0"):
            return parsed
    return Decimal("0")


def round_money(value: Decimal) -> Decimal:
    return Decimal(str(value)).quantize(Decimal("0.01"))


def first_date(*values: Any):
    for value in values:
        if value is None:
            continue
        if hasattr(value, "isoformat") and not isinstance(value, str):
            return value
        text = trim(value)
        if not text:
            continue
        try:
            return datetime.fromisoformat(text[:10]).date()
        except ValueError:
            continue
    return None


def map_list(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    result = []
    for item in value:
        if isinstance(item, dict):
            result.append(dict(item))
    return result


def list_items(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [dict(item) for item in value if isinstance(item, dict)]


def normalize_token(value: Any) -> str:
    return trim(value).upper()


def humanize_label(value: Any) -> str:
    token = trim(value).replace("_", " ").replace("-", " ").replace(".", " ")
    return " ".join(token.split()).title() if token else ""


def readable_agent_label(agent_id: Any) -> str:
    token = trim(agent_id)
    if not token:
        return ""
    if "::" in token:
        token = token.rsplit("::", 1)[-1]
    if ":" in token:
        token = token.rsplit(":", 1)[-1]
    return humanize_label(token)


def safe_token(value: str) -> str:
    token = "".join(character if character.isalnum() else "-" for character in value.lower())
    return "-".join(part for part in token.split("-") if part) or "run"


def preset_from_run_id(run_id: str) -> str:
    prefix = "banking-python-wt-"
    token = trim(run_id)
    if token.startswith(prefix) and len(token) > len(prefix):
        return token[len(prefix):]
    return WIND_TUNNEL_PRESET_ID


def policy_run_record(run_id: str, *, allow_legacy_synthesis: bool = False) -> dict[str, Any]:
    durable_run = load_policy_run_state(run_id)
    if durable_run is not None:
        with _POLICY_WIND_TUNNEL_LOCK:
            _POLICY_WIND_TUNNEL_RUNS[run_id] = durable_run
    receipt = load_finalization_receipt(run_id)
    if receipt:
        require_published_finalization_artifacts(receipt["artifactManifest"])
        restore_policy_run_from_finalization_receipt(
            receipt,
            replace_existing=receipt.get("state") == FINALIZATION_RECEIPT_PUBLISHED,
        )
    if run_id not in _POLICY_WIND_TUNNEL_RUNS:
        if not allow_legacy_synthesis:
            raise ProtocolError(
                404,
                "unknown_policy_wind_tunnel_run",
                "not_found",
                f"Unknown Policy Wind Tunnel runId: {run_id}",
                operation="policy-wind-tunnel-status",
                details={"runId": run_id},
            )
        preset_id = preset_from_run_id(run_id)
        synthesized = new_policy_run(
            run_id,
            preset_id,
            normalized_wind_tunnel_parameters({}, preset_id),
            status="COMPLETED",
            phase="COMPLETED",
            progress=1.0,
            bundle_ready=True,
            log_tail=["Synthetic Python remote provider run reconstructed from run id."],
        )
        store_policy_run_snapshot(run_id, synthesized)
    return _POLICY_WIND_TUNNEL_RUNS[run_id]


def decimal_string(value: Decimal) -> str:
    return format(value.quantize(Decimal("0.01")), "f")


def format_playback_rate(playback_rate: float) -> str:
    return f"{int(playback_rate)}x" if float(playback_rate).is_integer() else f"{format_decimal(playback_rate)}x"


def estimate_wall_clock_duration(horizon_days: int, playback_rate: float) -> str:
    wall_seconds = (max(1, horizon_days) * 86_400.0) / max(0.1, playback_rate)
    if wall_seconds < 60:
        return format_decimal(wall_seconds) + " seconds"
    wall_minutes = wall_seconds / 60
    if wall_minutes < 60:
        return format_decimal(wall_minutes) + " minutes"
    return format_decimal(wall_minutes / 60) + " hours"


def format_decimal(value: float) -> str:
    return f"{value:.1f}"


def relativize_workspace_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(workspace_root()))
    except ValueError:
        return str(path)
