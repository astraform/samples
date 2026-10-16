from __future__ import annotations

import json
import hashlib
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

import pytest

from banking_remote_domain_python.rate_change_plan import canonical_snapshot_bytes
from banking_remote_domain_python.rate_change_plan import canonical_json_bytes
from banking_remote_domain_python.rate_change_plan import canonical_decimal
from banking_remote_domain_python.rate_change_plan import compile_for_launch
from banking_remote_domain_python.rate_change_plan import install_operation_id
from banking_remote_domain_python.rate_change_plan import eligible_account_counts_from_cohort_artifact
from banking_remote_domain_python.rate_change_plan import installation_manifest
from banking_remote_domain_python.rate_change_plan import RateChangeInstallationCustomer
from banking_remote_domain_python.rate_change_plan import canonical_installation_manifest_bytes
from banking_remote_domain_python.rate_change_plan import require_installation_manifest
from banking_remote_domain_python.rate_change_plan import require_installation_manifest_bytes
from banking_remote_domain_python.rate_change_plan import require_snapshot
from banking_remote_domain_python.rate_change_plan import require_snapshot_bytes
from banking_remote_domain_python.decision_trigger_policy import decision_triggers_from_evidence
from banking_remote_domain_python import service


def test_compiles_the_shared_canonical_day_ten_plan() -> None:
    fixture = load_fixture()
    source = fixture["input"]

    snapshot = compile_for_launch(
        source["simulationRunId"],
        source["presetId"],
        source["parameters"],
    )

    assert snapshot is not None
    assert snapshot.as_dict() == fixture["expectedSnapshot"]
    assert install_operation_id(snapshot) == fixture["expectedInstallOperationId"]
    assert require_snapshot_bytes(canonical_snapshot_bytes(snapshot)) == snapshot




def test_does_not_compile_other_presets_and_rejects_excluded_overlays() -> None:
    fixture = load_fixture()
    source = fixture["input"]

    assert compile_for_launch(
        source["simulationRunId"],
        "credit-stress",
        source["parameters"],
    ) is None

    with pytest.raises(ValueError, match="only the day-10 CC001 rate change"):
        compile_for_launch(
            source["simulationRunId"],
            source["presetId"],
            {**source["parameters"], "candidateStimulusAmount": "1"},
        )


def test_rejects_tampered_duplicate_and_trailing_snapshots() -> None:
    expected = load_fixture()["expectedSnapshot"]
    tampered = {
        **expected,
        "plan": {**expected["plan"], "newRate": "0.25"},
    }
    with pytest.raises(ValueError):
        require_snapshot(tampered)

    duplicate = (
        '{"schemaVersion":"banking.clock-driven-intervention-plan-snapshot.v1",'
        '"schemaVersion":"banking.clock-driven-intervention-plan-snapshot.v1",'
        '"planDigest":"' + expected["planDigest"] + '","plan":{}}'
    ).encode()
    with pytest.raises(ValueError, match="strict UTF-8 JSON"):
        require_snapshot_bytes(duplicate)

    with pytest.raises(ValueError, match="strict UTF-8 JSON"):
        require_snapshot_bytes(canonical_snapshot_bytes(require_snapshot(expected)) + b" {}")

    coercible = deepcopy(expected)
    coercible["plan"]["scheduledOffsetDays"] = "10"
    coercible["planDigest"] = hashlib.sha256(
        canonical_json_bytes(coercible["plan"])
    ).hexdigest()
    with pytest.raises(ValueError, match="exact JSON integer"):
        require_snapshot(coercible)

    lossless_transport = deepcopy(expected)
    lossless_transport["plan"]["scheduledOffsetDays"] = Decimal("1E+1")
    assert require_snapshot(lossless_transport).plan["scheduledOffsetDays"] == 10

    spring_bound_transport = deepcopy(expected)
    spring_bound_transport["plan"]["scheduledOffsetDays"] = 10.0
    assert require_snapshot(spring_bound_transport).plan["scheduledOffsetDays"] == 10
    spring_bound_transport["plan"]["scheduledOffsetDays"] = 10.5
    with pytest.raises(ValueError, match="I-JSON-safe integer"):
        require_snapshot(spring_bound_transport)

    with pytest.raises(ValueError, match="supported decimal bounds"):
        canonical_decimal("1e100000", "rate")


def test_emits_the_complete_shared_rate_change_trigger_payload() -> None:
    fixture = load_fixture()
    event = fixture["event"]

    triggers = decision_triggers_from_evidence(
        [event],
        event["scheduledForEpochMs"],
    )

    assert triggers == [fixture["expectedTrigger"]]


def test_persists_the_shared_exact_installation_denominator() -> None:
    fixture = load_fixture()
    plan = require_snapshot(fixture["expectedSnapshot"])
    manifest = installation_manifest(
        plan,
        [
            RateChangeInstallationCustomer(
                "customer-002", "provider-customer-002", "opportunity-customer-002", 0
            ),
            RateChangeInstallationCustomer(
                "customer-001", "provider-customer-001", "opportunity-customer-001", 1
            ),
        ],
        fixture["input"]["parameters"]["cohortArtifactDigest"],
    )

    assert manifest.as_dict() == fixture["expectedInstallationManifest"]
    assert (
        require_installation_manifest_bytes(
            canonical_installation_manifest_bytes(manifest),
            plan,
        )
        == manifest
    )

    with pytest.raises(ValueError):
        require_installation_manifest(
            {**fixture["expectedInstallationManifest"], "eligibleCustomerCount": 2},
            plan,
        )


def test_reconstructs_eligibility_with_execution_product_case_semantics() -> None:
    cohort = canonical_json_bytes(
        [
            {
                "personaName": "customer-lowercase-product",
                "initialAccounts": [{"productCode": "cc001"}],
            }
        ]
    )

    assert eligible_account_counts_from_cohort_artifact(cohort) == {
        "customer-lowercase-product": 1
    }


def test_shared_no_token_audit_proves_technical_decisions_without_realized_reactions() -> None:
    fixture = load_fixture()
    plan = require_snapshot(fixture["expectedSnapshot"])
    manifest = require_installation_manifest(
        fixture["expectedInstallationManifest"],
        plan,
    )

    proof = service.verify_clock_driven_rate_change_proof(
        plan,
        manifest,
        python_correlation_audit(fixture),
        "STUB_LLM",
    )

    assert proof["status"] == "TECHNICAL_VALIDATION_ONLY"
    assert proof["chainIntegrityVerified"] is True
    assert proof["realizedReactionVerified"] is False
    assert proof["reactionEvidenceType"] == "TECHNICAL_NO_TOKEN_VALIDATION"
    assert proof["generatedOpportunities"] == 1
    assert proof["correlatedDecisions"] == 1
    assert proof["realizedCustomerReactions"] == 0
    assert proof["technicalValidationDecisions"] == 1


def test_shared_remote_authorized_audit_requires_fenced_real_llm_ownership() -> None:
    fixture = load_fixture()
    plan = require_snapshot(fixture["expectedSnapshot"])
    manifest = require_installation_manifest(
        fixture["expectedInstallationManifest"],
        plan,
    )
    audit = python_correlation_audit(fixture)
    audit["opportunities"][0]["decisionMode"] = "REAL_LLM"

    proof = service.verify_clock_driven_rate_change_proof(
        plan,
        manifest,
        audit,
        "REAL_LLM",
    )

    assert proof["reactionEvidenceType"] == "REMOTE_AUTHORIZED_REAL_LLM"
    assert proof["status"] == "VERIFIED"
    assert proof["chainIntegrityVerified"] is True
    assert proof["realizedReactionVerified"] is True
    assert proof["realizedCustomerReactions"] == 1
    assert proof["technicalValidationDecisions"] == 0

    audit["opportunities"][0]["decisionAttempt"] = 2
    with pytest.raises(ValueError, match="fenced valid decision evidence"):
        service.verify_clock_driven_rate_change_proof(
            plan,
            manifest,
            audit,
            "REAL_LLM",
        )


def test_missing_or_contradictory_customer_coverage_fails_closed() -> None:
    fixture = load_fixture()
    plan = require_snapshot(fixture["expectedSnapshot"])
    manifest = require_installation_manifest(
        fixture["expectedInstallationManifest"],
        plan,
    )
    missing = python_correlation_audit(fixture)
    missing["opportunityCount"] = 0
    missing["opportunities"] = []

    with pytest.raises(ValueError, match="coverage"):
        service.verify_clock_driven_rate_change_proof(
            plan,
            manifest,
            missing,
            "STUB_LLM",
        )

    contradictory = python_correlation_audit(fixture)
    contradictory["opportunities"][0]["payload"]["operationId"] = "contradictory-operation"
    with pytest.raises(ValueError, match="payload is contradictory"):
        service.verify_clock_driven_rate_change_proof(
            plan,
            manifest,
            contradictory,
            "STUB_LLM",
        )

    zero_eligible_manifest = installation_manifest(
        plan,
        [
            RateChangeInstallationCustomer(
                "customer-001", "provider-customer-001", "opportunity-customer-001", 0
            ),
            RateChangeInstallationCustomer(
                "customer-002", "provider-customer-002", "opportunity-customer-002", 0
            ),
        ],
        fixture["input"]["parameters"]["cohortArtifactDigest"],
    )
    with pytest.raises(ValueError, match="coverage"):
        service.verify_clock_driven_rate_change_proof(
            plan,
            zero_eligible_manifest,
            missing,
            "STUB_LLM",
        )


def test_supported_rate_change_preset_cannot_lose_its_pinned_plan_and_become_not_applicable() -> None:
    fixture = load_fixture()
    source = fixture["input"]

    required, verified, _, evidence = service.assess_clock_driven_rate_change_proof(
        source["simulationRunId"],
        source["presetId"],
        source["parameters"],
        finalized=True,
    )

    assert required is True
    assert verified is False
    assert evidence["status"] == "UNVERIFIED"


def test_rewritten_installation_denominator_downgrades_finalized_proof(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = load_fixture()
    source = fixture["input"]
    plan = require_snapshot(fixture["expectedSnapshot"])
    cohort_artifact = canonical_rate_change_cohort_artifact()
    cohort_artifact_digest = hashlib.sha256(cohort_artifact).hexdigest()
    parameters = {
        **source["parameters"],
        service.CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD: plan.plan_digest,
        service.CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD: service.RATE_CHANGE_PLAN_SCHEMA_VERSION,
        "cohortArtifactDigest": cohort_artifact_digest,
    }
    rewritten = installation_manifest(
        plan,
        [
            RateChangeInstallationCustomer(
                "customer-001", "provider-customer-001", "opportunity-customer-001", 0
            ),
            RateChangeInstallationCustomer(
                "customer-002", "provider-customer-002", "opportunity-customer-002", 1
            ),
        ],
        cohort_artifact_digest,
    )
    plan_path = tmp_path / "plan.json"
    installation_path = tmp_path / "installation.json"
    plan_path.write_bytes(canonical_snapshot_bytes(plan))
    installation_path.write_bytes(canonical_installation_manifest_bytes(rewritten))
    monkeypatch.setattr(service, "clock_driven_intervention_plan_path", lambda _run_id: plan_path)
    monkeypatch.setattr(
        service,
        "clock_driven_intervention_installation_path",
        lambda _run_id: installation_path,
    )
    monkeypatch.setattr(
        service,
        "load_durable_cohort_artifact_snapshot",
        lambda *_args: {
            "artifactDigest": cohort_artifact_digest,
            "identitySetDigest": parameters["cohortAgentIdentitySetDigest"],
            "content": cohort_artifact,
        },
    )
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_correlation_audit",
        lambda *_args: pytest.fail("rewritten cohort must fail before audit access"),
    )

    required, verified, _, evidence = service.assess_clock_driven_rate_change_proof(
        source["simulationRunId"],
        source["presetId"],
        parameters,
        finalized=True,
    )

    assert required is True
    assert verified is False
    assert evidence["status"] == "UNVERIFIED"


def test_unavailable_correlation_audit_aborts_finalization(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = load_fixture()
    source = fixture["input"]
    plan = require_snapshot(fixture["expectedSnapshot"])
    cohort_artifact = canonical_rate_change_cohort_artifact()
    cohort_artifact_digest = hashlib.sha256(cohort_artifact).hexdigest()
    parameters = {
        **source["parameters"],
        service.CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD: plan.plan_digest,
        service.CLOCK_DRIVEN_INTERVENTION_PLAN_SCHEMA_FIELD: service.RATE_CHANGE_PLAN_SCHEMA_VERSION,
        "cohortArtifactDigest": cohort_artifact_digest,
    }
    installation = installation_manifest(
        plan,
        [
            RateChangeInstallationCustomer(
                "customer-001", "provider-customer-001", "opportunity-customer-001", 1
            ),
            RateChangeInstallationCustomer(
                "customer-002", "provider-customer-002", "opportunity-customer-002", 0
            ),
        ],
        cohort_artifact_digest,
    )
    plan_path = tmp_path / "plan.json"
    installation_path = tmp_path / "installation.json"
    plan_path.write_bytes(canonical_snapshot_bytes(plan))
    installation_path.write_bytes(canonical_installation_manifest_bytes(installation))
    monkeypatch.setattr(service, "clock_driven_intervention_plan_path", lambda _run_id: plan_path)
    monkeypatch.setattr(
        service,
        "clock_driven_intervention_installation_path",
        lambda _run_id: installation_path,
    )
    monkeypatch.setattr(
        service,
        "load_durable_cohort_artifact_snapshot",
        lambda *_args: {
            "artifactDigest": cohort_artifact_digest,
            "identitySetDigest": parameters["cohortAgentIdentitySetDigest"],
            "content": cohort_artifact,
        },
    )

    def unavailable_audit(*_args: object) -> dict[str, object]:
        raise service.OpportunityCorrelationAuditUnavailableError(
            "Opportunity correlation audit failed with status 403"
        )

    monkeypatch.setattr(service, "fetch_decision_opportunity_correlation_audit", unavailable_audit)

    with pytest.raises(service.OpportunityCorrelationAuditUnavailableError, match="403"):
        service.assess_clock_driven_rate_change_proof(
            source["simulationRunId"],
            source["presetId"],
            parameters,
            finalized=True,
        )


def test_correlation_capability_is_not_forwarded_across_redirects(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import urllib.request
    requests = []
    def unexpected(*args, **kwargs):
        requests.append((args, kwargs))
        raise AssertionError("Retired Runtime audit must not construct or send HTTP")
    monkeypatch.setattr(urllib.request, "Request", unexpected)
    monkeypatch.setattr(urllib.request, "urlopen", unexpected)
    with pytest.raises(service.OpportunityCorrelationAuditUnavailableError, match="private opportunity correlation capability"):
        service.fetch_decision_opportunity_correlation_audit("run-retired", service.RATE_CHANGE_TRIGGER_TYPE)
    token = service.bind_opportunity_correlation_capability("private-capability")
    try:
        with pytest.raises(service.OpportunityCorrelationAuditUnavailableError, match="API was retired"):
            service.fetch_decision_opportunity_correlation_audit("run-retired", service.RATE_CHANGE_TRIGGER_TYPE)
    finally:
        service.reset_opportunity_correlation_capability(token)
    assert requests == []


def test_malformed_success_audit_remains_retryable_unavailability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import urllib.request
    requests = []
    def unexpected(*args, **kwargs):
        requests.append((args, kwargs))
        raise AssertionError("Retired Runtime audit must not construct or send HTTP")
    monkeypatch.setattr(urllib.request, "Request", unexpected)
    monkeypatch.setattr(urllib.request, "urlopen", unexpected)
    with pytest.raises(service.OpportunityCorrelationAuditUnavailableError, match="private opportunity correlation capability"):
        service.fetch_decision_opportunity_correlation_audit("run-retired", service.RATE_CHANGE_TRIGGER_TYPE)
    token = service.bind_opportunity_correlation_capability("private-capability")
    try:
        with pytest.raises(service.OpportunityCorrelationAuditUnavailableError, match="API was retired"):
            service.fetch_decision_opportunity_correlation_audit("run-retired", service.RATE_CHANGE_TRIGGER_TYPE)
    finally:
        service.reset_opportunity_correlation_capability(token)
    assert requests == []


def canonical_rate_change_cohort_artifact() -> bytes:
    return canonical_json_bytes(
        [
            {
                "personaName": "customer-001",
                "initialAccounts": [{"productCode": "CC001"}],
            },
            {
                "personaName": "customer-002",
                "initialAccounts": [{"productCode": "TS001"}],
            },
        ]
    )


def load_fixture() -> dict[str, object]:
    for candidate in [
        Path("test-fixtures/banking-clock-driven-rate-change-v1.json"),
        Path("../test-fixtures/banking-clock-driven-rate-change-v1.json"),
    ]:
        if candidate.is_file():
            return json.loads(candidate.read_text(encoding="utf-8"))
    raise RuntimeError("rate-change fixture was not found")


def python_correlation_audit(fixture: dict[str, object]) -> dict[str, object]:
    audit = deepcopy(fixture["expectedCorrelationAudit"])
    audit["opportunities"][0]["domainId"] = service.DOMAIN_ID
    return audit
