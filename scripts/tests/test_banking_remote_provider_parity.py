import copy
import contextlib
import io
import importlib.util
import json
import sys
import tempfile
from unittest import TestCase, main, mock
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "scripts" / "check_banking_remote_provider_parity.py"


def load_module():
    spec = importlib.util.spec_from_file_location("check_banking_remote_provider_parity", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def agentic_summary(domain_id):
    cohort_id = "banking-balanced-banking-100-test" if domain_id == "banking" else "banking-python-balanced-banking-100-test"
    run_id = f"{domain_id}-run-1"
    status_counts = {
        "PENDING": 0,
        "RUNNING": 0,
        "SUCCEEDED": 100,
        "DEFERRED": 0,
        "BLOCKED": 0,
        "FAILED": 0,
        "CANCELLED": 0,
    }
    opportunity_reconciliation = {
        "schemaVersion": "customer_decision_opportunity_reconciliation.v1",
        "simulationRunId": run_id,
        "status": "RECONCILED",
        "ledgerReconciled": True,
        "generationMode": "EVENT_DRIVEN_DECISION_TRIGGERS",
        "generationSealed": True,
        "observedWorkWindows": 30,
        "generationObservedAt": "2026-01-01T00:00:00Z",
        "generationSealedAt": "2026-01-31T00:00:00Z",
        "generationSealedReplayContractVersion": 1,
        "generationSealedReplayFenceDigest": "a" * 64,
        "generationSealedReplayFenceLeaseOwner": "parity-finalizer",
        "generationSealedReplayFenceExpiresAtEpochMs": 1_800_000_000_000,
        "expectedGeneratedOpportunities": 100,
        "rejectedTriggers": 0,
        "migrationAnomalies": 0,
        "generatedOpportunities": 100,
        "terminalOpportunities": 100,
        "remainingBacklog": 0,
        "validDecisions": 100,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": status_counts,
        "asOf": "2026-01-31T00:00:01Z",
    }
    opportunity_ledger = {
        "schemaVersion": "customer_decision_opportunity_audit_totals.v1",
        "agentCount": 100,
        "invalidAgentAuditCount": 0,
        "simulationRunIds": [run_id],
        "consistentRunScope": True,
        "generatedOpportunities": 100,
        "terminalOpportunities": 100,
        "remainingBacklog": 0,
        "admittedOpportunities": 100,
        "totalAttempts": 100,
        "validDecisions": 100,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": status_counts,
        "deferredReasonCounts": {},
        "historicalDeferralCount": 0,
        "historicalDeferralReasonCounts": {},
    }
    return {
        "schemaVersion": "policy_wind_tunnel_summary.v1",
        "summaryMode": "CLOCK_DRIVEN_HYBRID_FINALIZED",
        "domainId": domain_id,
        "packId": f"{domain_id}-policy-pack",
        "runConfig": {
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            "agentDecisionIntervalMinutes": 1440,
            "simulationHorizonDays": 30,
            "configuredDurationDays": 30,
            "actualDurationDays": 30.5,
            "horizonOvershootDays": 0.5,
            "durationStatus": "HORIZON_OVERRUN",
            "cohortSize": 100,
            "cohortId": cohort_id,
        },
        "decisionReadiness": {"status": "READY_FOR_HUMAN_REVIEW"},
        "decisionComparison": {"schemaVersion": "customer_outcome_decision_comparison.v1"},
        "simulationTimeline": {
            "configuredDurationDays": 30,
            "actualDurationDays": 30.5,
            "horizonOvershootDays": 0.5,
            "durationStatus": "HORIZON_OVERRUN",
            "events": [],
        },
        "sideEffectLedger": {"entries": []},
        "branchStateProjection": {"mutations": []},
        "domainOutcomeScores": [{
            "schemaVersion": "domain_outcome_score.v1",
            "status": "SCORED",
            "recommendation": "WATCH",
            "scorecardId": "banking.customer-impact.v1",
            "metrics": [
                {"metricId": "activatedSyntheticCustomers", "value": 100},
                {"metricId": "runtimeEvidenceSignals", "value": 15000},
                {"metricId": "affectedStateChanges", "value": 100},
                {"metricId": "rejectedWorkItems", "value": 12},
            ],
        }],
        "agenticSimulation": {
            "schemaVersion": "agentic_customer_simulation_summary.v1",
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            "agentDecisionMode": "STUB_LLM",
            "decisionCadence": "DAILY",
            "decisionIntervalMinutes": 1440,
            "simulationHorizonDays": 30,
            "configuredDurationDays": 30,
            "actualDurationDays": 30.5,
            "horizonOvershootDays": 0.5,
            "durationStatus": "HORIZON_OVERRUN",
            "playbackRate": 14400.0,
            "cohortSize": 100,
            "expectedDecisionOpportunities": 100,
            "observedDecisionOpportunities": 100,
            "admittedDecisionOpportunities": 100,
            "deferredDecisionOpportunities": 0,
            "deferredDecisionReasons": {},
            "historicalDeferredDecisionTransitions": 0,
            "historicalDeferredDecisionReasons": {},
            "actualDecisionCount": 100,
            "auditedAgentCount": 100,
            "fallbackCount": 0,
            "evidenceIntegrityWarnings": [],
            "decisionCoverageRate": 1.0,
            "decisionOpportunityCoverageRate": 1.0,
            "decisionAdmissionRate": 1.0,
            "decisionCoverageStatus": "CAPTURED",
            "decisionCoverageExplanation": (
                "The finalized event-driven run generated 100 durable customer decision opportunities, "
                "produced 100 valid decisions, and left no backlog."
            ),
            "decisionOpportunitySource": "CUSTOMER_DECISION_OPPORTUNITY_LEDGER",
            "opportunityReconciliation": opportunity_reconciliation,
            "opportunityTerminalCount": 100,
            "opportunityRemainingBacklog": 0,
            "opportunityStatusCounts": status_counts,
            "opportunityTotalAttempts": 100,
            "opportunityAdmissionCountVerified": True,
            "opportunityDecisionEvidenceGaps": 0,
            "evidenceRefs": {
                "hybridAgentAuditJson": "/artifact?type=hybrid-agent-audit-json",
                "simulationRunEvidencePack": "/simulation-runs/run-1/export",
            },
        },
        "hybridAgentAudit": {
            "schemaVersion": "hybrid_decision_audit_live.v1",
            "opportunityLedger": opportunity_ledger,
            "agents": [
                {
                    "opportunityAudit": {
                        "schemaVersion": "customer_decision_opportunity_agent_audit.v1",
                        "simulationRunId": run_id,
                        "agentId": f"agent-{index + 1}",
                        "generatedOpportunities": 1,
                        "terminalOpportunities": 1,
                        "remainingBacklog": 0,
                        "admittedOpportunities": 1,
                        "totalAttempts": 1,
                        "validDecisions": 1,
                        "recoverableRecordedDecisions": 0,
                        "succeededWithoutDecisionEvidence": 0,
                        "decisionEvidenceGaps": 0,
                        "statusCounts": {
                            "PENDING": 0,
                            "RUNNING": 0,
                            "SUCCEEDED": 1,
                            "DEFERRED": 0,
                            "BLOCKED": 0,
                            "FAILED": 0,
                            "CANCELLED": 0,
                        },
                        "deferredReasonCounts": {},
                        "historicalDeferralCount": 0,
                        "historicalDeferralReasonCounts": {},
                    }
                }
                for index in range(100)
            ],
            "runtimeTickDrain": {
                "verified": True,
                "status": "DRAINED",
                "message": "Runtime tick drain was verified for this simulation run before finalization.",
            },
        },
    }


def write_summary_and_cohort(root: Path, summary: dict, *, seed: str = "shared-seed") -> Path:
    cohort_id = summary["runConfig"]["cohortId"]
    summary_path = root / "output" / "experiments" / summary["domainId"] / "runs" / "run-1" / "summary" / "scenario-lab-summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary), encoding="utf-8")

    cohort_dir = root / "output" / "cohorts" / cohort_id
    cohort_dir.mkdir(parents=True, exist_ok=True)
    domain_id = summary["domainId"]
    customers = [{
        "domainId": domain_id,
        "personaName": "Shared Customer",
        "personaDescription": "Same customer through different providers.",
        "traits": ["synthetic-customer", f"cohort:{cohort_id}", "cohort-segment:everyday"],
        "domainConfig": {
            "cohortFactory": {
                "cohortId": cohort_id,
                "segmentId": "everyday",
            },
        },
        "scheduledWork": [{
            "workId": "cf-1-1",
            "kind": "transaction",
            "frequency": "WEEKLY",
            "payload": {"type": "DEPOSIT", "amount": 1000},
        }],
    }]
    bundle = {
        "schemaVersion": "synthetic_cohort_bundle.v1",
        "cohortId": cohort_id,
        "domainId": domain_id,
        "recipe": {
            "schemaVersion": "synthetic_cohort_recipe.v1",
            "domainId": domain_id,
            "recipeId": "balanced-banking-100",
            "seed": seed,
            "targetSize": 1,
            "segmentMix": [{"segmentId": "everyday", "weight": 100}],
            "generationControls": {
                "provider": "stub",
                "model": "stub",
                "scheduledWorkDensity": "normal",
            },
        },
        "summary": {
            "scheduledWorkItems": 1,
            "segmentCounts": {"everyday": 1},
            "archetypeCounts": {"steady-income-family": 1},
        },
        "customers": customers,
    }
    (cohort_dir / "bundle.json").write_text(json.dumps(bundle), encoding="utf-8")
    return summary_path


def set_duration(summary: dict, *, configured: float, actual: float, overshoot: float, status: str) -> None:
    for section_name in ("runConfig", "simulationTimeline", "agenticSimulation"):
        summary[section_name]["configuredDurationDays"] = configured
        summary[section_name]["actualDurationDays"] = actual
        summary[section_name]["horizonOvershootDays"] = overshoot
        summary[section_name]["durationStatus"] = status


def set_reconciled_opportunity_count(summary: dict, count: int) -> None:
    assert count > 0
    run_id = summary["agenticSimulation"]["opportunityReconciliation"]["simulationRunId"]
    agent_count = min(count, summary["agenticSimulation"]["cohortSize"])
    status_counts = {
        "PENDING": 0,
        "RUNNING": 0,
        "SUCCEEDED": count,
        "DEFERRED": 0,
        "BLOCKED": 0,
        "FAILED": 0,
        "CANCELLED": 0,
    }
    summary["agenticSimulation"].update({
        "expectedDecisionOpportunities": count,
        "observedDecisionOpportunities": count,
        "admittedDecisionOpportunities": count,
        "deferredDecisionOpportunities": 0,
        "deferredDecisionReasons": {},
        "actualDecisionCount": count,
        "auditedAgentCount": agent_count,
        "decisionCoverageRate": 1.0,
        "decisionOpportunityCoverageRate": 1.0,
        "decisionAdmissionRate": 1.0,
        "decisionCoverageStatus": "CAPTURED",
        "decisionCoverageExplanation": (
            f"The finalized event-driven run generated {count} durable customer decision "
            f"opportunity/opportunities and produced {count} valid decision(s)."
        ),
        "opportunityTerminalCount": count,
        "opportunityRemainingBacklog": 0,
        "opportunityStatusCounts": status_counts,
        "opportunityTotalAttempts": count,
        "opportunityAdmissionCountVerified": True,
        "opportunityDecisionEvidenceGaps": 0,
    })
    summary["agenticSimulation"]["opportunityReconciliation"].update({
        "expectedGeneratedOpportunities": count,
        "generatedOpportunities": count,
        "terminalOpportunities": count,
        "remainingBacklog": 0,
        "validDecisions": count,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": status_counts,
    })
    summary["hybridAgentAudit"]["opportunityLedger"].update({
        "agentCount": agent_count,
        "simulationRunIds": [run_id],
        "generatedOpportunities": count,
        "terminalOpportunities": count,
        "remainingBacklog": 0,
        "admittedOpportunities": count,
        "totalAttempts": count,
        "validDecisions": count,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": status_counts,
        "deferredReasonCounts": {},
        "historicalDeferralCount": 0,
        "historicalDeferralReasonCounts": {},
    })
    summary["hybridAgentAudit"]["agents"] = [
        {
            "opportunityAudit": {
                "schemaVersion": "customer_decision_opportunity_agent_audit.v1",
                "simulationRunId": run_id,
                "agentId": f"agent-{index + 1}",
                "generatedOpportunities": count // agent_count,
                "terminalOpportunities": count // agent_count,
                "remainingBacklog": 0,
                "admittedOpportunities": count // agent_count,
                "totalAttempts": count // agent_count,
                "validDecisions": count // agent_count,
                "recoverableRecordedDecisions": 0,
                "succeededWithoutDecisionEvidence": 0,
                "decisionEvidenceGaps": 0,
                "statusCounts": {
                    "PENDING": 0,
                    "RUNNING": 0,
                    "SUCCEEDED": count // agent_count,
                    "DEFERRED": 0,
                    "BLOCKED": 0,
                    "FAILED": 0,
                    "CANCELLED": 0,
                },
                "deferredReasonCounts": {},
                "historicalDeferralCount": 0,
                "historicalDeferralReasonCounts": {},
            }
        }
        for index in range(agent_count)
    ]


def live_provider(module, domain_id: str, language: str):
    return module.Provider(
        name=f"{language}-banking-remote",
        base_url="http://unused",
        expected_domain_id=domain_id,
        expected_pack_id=f"{domain_id}-policy-pack",
        expected_language=language,
    )


def credit_card_endpoint_response(
        provider,
        *,
        trigger_payload_updates: dict | None = None,
        trigger_updates: dict | None = None) -> dict:
    occurrence_key = f"{provider.expected_domain_id}|domain-system|cc-statement-work|2026-05-01"
    bill_id = f"{provider.expected_domain_id}-domain-system-cc-statement-work-2026-05-01-1"
    interest_event = {
        "eventType": "remote_domain_credit_card_interest_charged",
        "occurrenceKey": occurrence_key,
        "accountId": "acct-card-1",
        "runtimeAccountId": 1,
        "accountName": "Rewards Card",
        "productCode": "CC001",
        "openingBalance": -1200,
        "interestAmount": 24,
        "minimumPayment": 48.96,
    }
    statement_event = {
        "eventType": "remote_domain_credit_card_statement_closed",
        "occurrenceKey": occurrence_key,
        "workId": "cc-statement-work",
        "billId": bill_id,
        "accountId": "acct-card-1",
        "runtimeAccountId": 1,
        "accountName": "Rewards Card",
        "productCode": "CC001",
        "closingAmount": -1224,
        "minimumAmountDue": 48.96,
        "statementStartDate": "2026-04-01",
        "statementEndDate": "2026-04-30",
        "dueDate": "2026-05-14",
        "currency": "AUD",
    }
    trigger_ref = f"{occurrence_key}|account:acct-card-1"
    trigger_payload = {
        "policyVersion": "banking.customer-decision-trigger-policy.v1",
        "numericEncoding": "canonical_decimal_string.v1",
        "normalizedEventType": "statement_close",
        "sourceEventType": "remote_domain_credit_card_statement_closed",
        "eventType": "remote_domain_credit_card_statement_closed",
        "occurrenceKey": occurrence_key,
        "workId": "cc-statement-work",
        "billId": bill_id,
        "accountId": "acct-card-1",
        "runtimeAccountId": "1924617475000" if provider.expected_language == "java" else "1",
        "accountName": "Rewards Card",
        "productCode": "CC001",
        "closingAmount": "-1224",
        "minimumAmountDue": "48.96",
        "statementStartDate": "2026-04-01",
        "statementEndDate": "2026-04-30",
        "dueDate": "2026-05-14",
        "currency": "AUD",
        "providerRevision": f"oci-sha256:{provider.expected_language}-build",
    }
    trigger_payload.update(copy.deepcopy(trigger_payload_updates or {}))
    return {
        "operation": "domain-system-work-window",
        "nextState": {
            "data": {
                "accounts": [{"accountId": "acct-card-1", "balance": -1224}],
            }
        },
        "result": {
            "evidenceEvents": [interest_event, statement_event],
            "domainSystemWork": {
                "evaluatedWorkCount": 2,
                "executedWorkCount": 2,
                "workResults": [
                    {"kind": "credit_card_interest_accrual", "outcome": "APPLIED"},
                    {"kind": "credit_card_statement_close", "outcome": "APPLIED"},
                ],
                "stateDeltas": [interest_event, statement_event],
                "decisionTriggers": [{
                    "schemaVersion": "customer_decision_trigger.v1",
                    "triggerId": f"statement_close:{trigger_ref}",
                    "triggerType": "statement_close",
                    "triggerRef": trigger_ref,
                    "scheduledForEpochMs": 1_777_000_000_000,
                    "priority": "NORMAL",
                    "schedulingPriority": "NORMAL",
                    "reason": "Credit card statement closed",
                    "payload": trigger_payload,
                    "evidenceRefs": [trigger_ref],
                } | copy.deepcopy(trigger_updates or {})],
            },
        },
    }


def credit_card_endpoint_behavior(module, provider, response: dict) -> dict:
    prepared = {
        "nextState": {"data": {"accounts": []}},
        "result": {"runtimeIdentity": f"{provider.expected_domain_id}::Parity"},
    }
    with mock.patch.object(module, "post_json", side_effect=[prepared, response]):
        return module.check_credit_card_work_window(provider, 1.0)


def compare_credit_card_endpoint_behavior(module, java_behavior: dict, python_behavior: dict) -> None:
    module.compare(
        {
            "products": [],
            "tools": [],
            "domainSystemWorkKinds": [],
            "presets": [],
            "behavior": {"creditCard": java_behavior},
        },
        {
            "products": [],
            "tools": [],
            "domainSystemWorkKinds": [],
            "presets": [],
            "behavior": {"creditCard": python_behavior},
        },
    )


class BankingRemoteProviderParityTest(TestCase):

    def setUp(self):
        self.module = load_module()

    def test_agentic_report_parity_accepts_matching_java_python_contracts(self):
        java = agentic_summary("banking")
        python = agentic_summary("banking-python")

        result = self.module.compare_agentic_report_summaries(java, python)

        self.assertEqual("banking_agentic_report_parity.v1", result["schemaVersion"])
        self.assertEqual(100, result["sharedConfiguration"]["cohortSize"])
        self.assertEqual(100, result["sharedConfiguration"]["expectedDecisionOpportunities"])
        self.assertEqual(
            "CUSTOMER_DECISION_OPPORTUNITY_LEDGER",
            result["sharedConfiguration"]["decisionOpportunitySource"],
        )
        self.assertEqual(100, result["opportunityProof"]["generatedOpportunities"])
        json.dumps(result)
        self.assertEqual(100, result["java"]["actualDecisionCount"])
        self.assertEqual(100, result["python"]["actualDecisionCount"])

    def test_agentic_report_parity_compares_generated_cohort_contracts_when_paths_are_supplied(self):
        java = agentic_summary("banking")
        python = agentic_summary("banking-python")
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            java_path = write_summary_and_cohort(root, java)
            python_path = write_summary_and_cohort(root, python)

            result = self.module.compare_agentic_report_summaries(
                java,
                python,
                java_summary_path=str(java_path),
                python_summary_path=str(python_path),
            )

        self.assertEqual("shared-seed", result["cohort"]["seed"])
        self.assertEqual(1, result["cohort"]["scheduledWorkItems"])

    def test_agentic_report_parity_rejects_generated_cohort_seed_drift(self):
        java = agentic_summary("banking")
        python = agentic_summary("banking-python")
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            java_path = write_summary_and_cohort(root, java, seed="seed-a")
            python_path = write_summary_and_cohort(root, python, seed="seed-b")

            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.module.compare_agentic_report_summaries(
                    java,
                    python,
                    java_summary_path=str(java_path),
                    python_summary_path=str(python_path),
                )

    def test_agentic_report_parity_requires_observed_and_admitted_fields(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        del python["agenticSimulation"]["observedDecisionOpportunities"]

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_requires_duration_proof_fields(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        del python["agenticSimulation"]["actualDurationDays"]

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_duration_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["runConfig"]["actualDurationDays"] = 33.0
        python["runConfig"]["horizonOvershootDays"] = 3.0
        python["simulationTimeline"]["actualDurationDays"] = 33.0
        python["simulationTimeline"]["horizonOvershootDays"] = 3.0
        python["agenticSimulation"]["actualDurationDays"] = 33.0
        python["agenticSimulation"]["horizonOvershootDays"] = 3.0

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_inconsistent_duration_math_even_when_providers_match(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        set_duration(java, configured=30, actual=30, overshoot=0.5, status="HORIZON_OVERRUN")
        set_duration(python, configured=30, actual=30, overshoot=0.5, status="HORIZON_OVERRUN")

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_duration_status_that_disagrees_with_overshoot(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        set_duration(java, configured=30, actual=30, overshoot=0, status="HORIZON_OVERRUN")
        set_duration(python, configured=30, actual=30, overshoot=0, status="HORIZON_OVERRUN")

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_actual_duration_below_acceptance_window(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        for summary in (java, python):
            set_duration(summary, configured=30, actual=1, overshoot=0, status="WITHIN_HORIZON")

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_short_stub_acceptance_window(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        for summary in (java, python):
            summary["runConfig"]["simulationHorizonDays"] = 1
            summary["agenticSimulation"]["simulationHorizonDays"] = 1
            set_duration(summary, configured=1, actual=1.1, overshoot=0.1, status="HORIZON_OVERRUN")

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_accepts_domain_cadence_as_metadata(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))

        result = self.module.compare_agentic_report_summaries(java, python)

        self.assertEqual("DAILY", result["sharedConfiguration"]["decisionCadence"])
        self.assertEqual(100, result["sharedConfiguration"]["expectedDecisionOpportunities"])

    def test_agentic_report_parity_rejects_expected_opportunity_reconciliation_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["expectedDecisionOpportunities"] = 99

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_fractional_opportunity_counter(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["observedDecisionOpportunities"] = 100.9

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_invalid_reconciliation_timestamp(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["opportunityReconciliation"]["generationSealedAt"] = "not-a-time"

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_legacy_cadence_source(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["decisionOpportunitySource"] = "LEGACY_CADENCE_AUDIT"

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_unreconciled_opportunity_backlog(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["opportunityReconciliation"].update({
            "status": "DRAINING",
            "ledgerReconciled": False,
            "remainingBacklog": 1,
        })

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_rejected_trigger_watermark(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["opportunityReconciliation"]["rejectedTriggers"] = 1

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_per_agent_audit_mismatch(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["hybridAgentAudit"]["opportunityLedger"]["generatedOpportunities"] = 99

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_truncated_per_agent_artifact(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["hybridAgentAudit"]["agents"].pop()

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_non_deferred_lifecycle_reason_codes(self):
        for invalid_reason in (
            "",
            " provider_unavailable ",
            "x" * 129,
            "valid_customer_decision_turn",
            "future_provider_pause",
            "provider_unavailable_\u00e9",
        ):
            with self.subTest(invalid_reason=invalid_reason):
                java = agentic_summary("banking")
                python = copy.deepcopy(agentic_summary("banking-python"))
                python["agenticSimulation"]["historicalDeferredDecisionTransitions"] = 1
                python["agenticSimulation"]["historicalDeferredDecisionReasons"] = {
                    invalid_reason: 1,
                }
                python["hybridAgentAudit"]["opportunityLedger"]["historicalDeferralCount"] = 1
                python["hybridAgentAudit"]["opportunityLedger"]["historicalDeferralReasonCounts"] = {
                    invalid_reason: 1,
                }
                python["hybridAgentAudit"]["agents"][0]["opportunityAudit"][
                    "historicalDeferralCount"
                ] = 1
                python["hybridAgentAudit"]["agents"][0]["opportunityAudit"][
                    "historicalDeferralReasonCounts"
                ] = {invalid_reason: 1}

                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    self.module.compare_agentic_report_summaries(java, python)

    def test_deferred_reason_vocabulary_keeps_deliberate_legacy_sentinel(self):
        self.assertTrue(self.module.strict_opportunity_deferred_reason_code("provider_unavailable"))
        self.assertFalse(self.module.strict_opportunity_deferred_reason_code("unspecified"))
        self.assertTrue(
            self.module.proof_safe_opportunity_deferred_reason_count("unspecified", 0)
        )
        self.assertFalse(
            self.module.proof_safe_opportunity_deferred_reason_count("unspecified", 1)
        )
        self.assertFalse(
            self.module.strict_opportunity_deferred_reason_code("valid_customer_decision_turn")
        )

    def test_agentic_report_parity_rejects_positive_legacy_unspecified_debt(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        for summary in (java, python):
            summary["agenticSimulation"]["historicalDeferredDecisionTransitions"] = 1
            summary["agenticSimulation"]["historicalDeferredDecisionReasons"] = {
                "unspecified": 1,
            }
            summary["hybridAgentAudit"]["opportunityLedger"]["historicalDeferralCount"] = 1
            summary["hybridAgentAudit"]["opportunityLedger"][
                "historicalDeferralReasonCounts"
            ] = {"unspecified": 1}
            summary["hybridAgentAudit"]["agents"][0]["opportunityAudit"][
                "historicalDeferralCount"
            ] = 1
            summary["hybridAgentAudit"]["agents"][0]["opportunityAudit"][
                "historicalDeferralReasonCounts"
            ] = {"unspecified": 1}
            self.assertEqual(
                summary["agenticSimulation"]["opportunityReconciliation"]["migrationAnomalies"],
                0,
            )
            self.assertEqual(summary["decisionReadiness"]["status"], "READY_FOR_HUMAN_REVIEW")

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_requires_exact_cross_provider_trigger_count(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        set_reconciled_opportunity_count(python, 99)

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_zero_decision_stub_proof(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"].update({
            "observedDecisionOpportunities": 0,
            "admittedDecisionOpportunities": 0,
            "deferredDecisionOpportunities": 0,
            "actualDecisionCount": 0,
            "auditedAgentCount": 0,
            "decisionCoverageRate": 0.0,
            "decisionOpportunityCoverageRate": 0.0,
            "decisionAdmissionRate": 0.0,
            "decisionCoverageExplanation": "The runtime admitted 0 turns and captured 0 audited decision event(s).",
        })

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_accepts_small_complete_event_driven_proof(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        for summary in (java, python):
            set_reconciled_opportunity_count(summary, 1)

        result = self.module.compare_agentic_report_summaries(java, python)

        self.assertEqual(1, result["opportunityProof"]["generatedOpportunities"])

    def test_agentic_report_parity_rejects_tiny_nonzero_decision_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"].update({
            "observedDecisionOpportunities": 1,
            "admittedDecisionOpportunities": 1,
            "deferredDecisionOpportunities": 0,
            "deferredDecisionReasons": {},
            "actualDecisionCount": 1,
            "auditedAgentCount": 1,
            "decisionCoverageRate": 0.0003,
            "decisionOpportunityCoverageRate": 0.0003,
            "decisionAdmissionRate": 1.0,
            "decisionCoverageExplanation": "The runtime admitted 1 turn and captured 1 audited decision event.",
        })

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_evidence_integrity_warnings(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["evidenceIntegrityWarnings"] = [{
            "code": "AUDITED_DECISIONS_EXCEED_ADMITTED_TURNS",
            "message": "Audited decisions exceed admitted turns.",
        }]

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_requires_verified_runtime_drain_proof(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        del python["hybridAgentAudit"]["runtimeTickDrain"]

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_unverified_runtime_drain_proof(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["hybridAgentAudit"]["runtimeTickDrain"] = {
            "verified": False,
            "status": "UNVERIFIED_SCOPE",
            "message": "Runtime tick drain could not be verified.",
        }

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_calendar_counts_laundered_as_captured(self):
        java = agentic_summary("banking")
        python = agentic_summary("banking-python")
        for summary in (java, python):
            summary["agenticSimulation"].update({
                "observedDecisionOpportunities": 72000,
                "admittedDecisionOpportunities": 72000,
                "deferredDecisionOpportunities": 0,
                "deferredDecisionReasons": {},
                "actualDecisionCount": 72000,
                "auditedAgentCount": 100,
                "decisionCoverageRate": 1.0,
                "decisionOpportunityCoverageRate": 1.0,
                "decisionAdmissionRate": 1.0,
                "decisionCoverageStatus": "CAPTURED",
                "decisionCoverageExplanation": (
                    "The finalized event-driven run generated 72000 durable customer decision opportunities."
                ),
            })

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_live_coverage_status_for_finalized_summary(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["agenticSimulation"]["decisionCoverageStatus"] = "CAPTURING"

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_scorecard_recommendation_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        java["domainOutcomeScores"][0]["recommendation"] = "LAUNCH"
        python["domainOutcomeScores"][0]["recommendation"] = "BLOCK"

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_scorecard_id_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        python["domainOutcomeScores"][0]["scorecardId"] = "banking-python.customer-impact.v1"

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_rejects_scorecard_metric_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        java["domainOutcomeScores"][0]["metrics"][1]["value"] = 120
        python["domainOutcomeScores"][0]["metrics"][1]["value"] = 19794

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.compare_agentic_report_summaries(java, python)

    def test_agentic_report_parity_accepts_bounded_evidence_volume_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        java["domainOutcomeScores"][0]["metrics"][1]["value"] = 3664
        python["domainOutcomeScores"][0]["metrics"][1]["value"] = 4940

        result = self.module.compare_agentic_report_summaries(java, python)

        self.assertEqual(
            {"java": 3664, "python": 4940},
            result["scorecardMetrics"]["banking.customer-impact.v1"]["runtimeEvidenceSignals"],
        )

    def test_agentic_report_parity_accepts_small_rejected_work_metric_drift(self):
        java = agentic_summary("banking")
        python = copy.deepcopy(agentic_summary("banking-python"))
        java["domainOutcomeScores"][0]["metrics"][3]["value"] = 9
        python["domainOutcomeScores"][0]["metrics"][3]["value"] = 18

        result = self.module.compare_agentic_report_summaries(java, python)

        self.assertEqual(
            {"java": 9, "python": 18},
            result["scorecardMetrics"]["banking.customer-impact.v1"]["rejectedWorkItems"],
        )

    def test_load_report_summary_accepts_bundle_shape(self):
        summary = agentic_summary("banking")
        bundle = {
            "schemaVersion": "policy_wind_tunnel_bundle.v1",
            "summary": summary,
        }

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "bundle.json"
            path.write_text(json.dumps(bundle), encoding="utf-8")

            loaded = self.module.load_report_summary(str(path))

        self.assertEqual(summary, loaded)

    def test_skip_live_endpoints_requires_both_summary_paths(self):
        with mock.patch.object(sys, "argv", ["check", "--skip-live-endpoints"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.module.main()

    def test_decision_trigger_summary_binds_and_canonicalizes_provider_identity(self):
        provider = self.module.Provider(
            name="java-banking-remote",
            base_url="http://unused",
            expected_domain_id="banking",
            expected_pack_id="banking-policy-pack",
            expected_language="java",
        )
        trigger_ref = "banking|domain-system|statement-1|account:acct-1"
        response = {
            "result": {
                "domainSystemWork": {
                    "decisionTriggers": [{
                        "schemaVersion": "customer_decision_trigger.v1",
                        "triggerId": f"statement_close:{trigger_ref}",
                        "triggerType": "statement_close",
                        "triggerRef": trigger_ref,
                        "scheduledForEpochMs": 1_777_000_000_000,
                        "priority": "NORMAL",
                        "schedulingPriority": "NORMAL",
                        "reason": "Credit card statement closed",
                        "payload": {
                            "policyVersion": "banking.customer-decision-trigger-policy.v1",
                            "normalizedEventType": "statement_close",
                            "sourceEventType": "remote_domain_credit_card_statement_closed",
                            "occurrenceKey": "banking|domain-system|statement-1",
                            "accountId": "acct-1",
                            "providerRevision": "oci-sha256:java-build",
                        },
                        "evidenceRefs": [trigger_ref],
                    }]
                }
            }
        }

        summary = self.module.decision_trigger_summary(
            provider,
            "statement-close",
            response,
            ["statement_close"],
        )

        self.assertEqual("<domain>|domain-system|statement-1|account:acct-1", summary[0]["triggerRef"])
        self.assertEqual("Credit card statement closed", summary[0]["reason"])
        self.assertEqual(
            "<domain>|domain-system|statement-1",
            summary[0]["payload"]["occurrenceKey"],
        )
        self.assertEqual("<provider-build>", summary[0]["payload"]["providerRevision"])
        self.assertEqual("acct-1", summary[0]["payload"]["accountId"])
        self.assertEqual("NORMAL", summary[0]["schedulingPriority"])

    def test_live_endpoint_parity_compares_complete_trigger_payload(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(java),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(python),
        )

        compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

        payload = java_behavior["decisionTriggers"][0]["payload"]
        self.assertEqual("48.96", payload["minimumAmountDue"])
        self.assertEqual("<provider-build>", payload["providerRevision"])
        self.assertEqual("<runtimeAccountId>", payload["runtimeAccountId"])
        self.assertTrue(payload["occurrenceKey"].startswith("<domain>|"))
        self.assertEqual("<billId>", payload["billId"])

    def test_live_endpoint_parity_rejects_trigger_domain_fact_drift(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(java),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(
                python,
                trigger_payload_updates={"minimumAmountDue": "49.00"},
            ),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_live_endpoint_parity_rejects_scheduling_priority_drift(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(java, trigger_updates={"schedulingPriority": "CRITICAL"}),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(python, trigger_updates={"schedulingPriority": "LOW"}),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_decision_trigger_summary_rejects_forbidden_fields_in_nested_objects_and_arrays(self):
        provider = live_provider(self.module, "banking", "java")
        nested_vectors = {
            "prompt": {"metadata": {"prompt": "hidden"}},
            "systemPrompt": {"metadata": [{"systemPrompt": "hidden"}]},
            "rawProviderText": {"metadata": {"items": [{"rawProviderText": "hidden"}]}},
            "chainOfThought": {"metadata": [[{"chainOfThought": "hidden"}]]},
        }

        for forbidden_field, payload_update in nested_vectors.items():
            with self.subTest(forbidden_field=forbidden_field):
                response = credit_card_endpoint_response(
                    provider,
                    trigger_payload_updates=payload_update,
                )
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    self.module.decision_trigger_summary(
                        provider,
                        "credit-card-work",
                        response,
                        ["statement_close"],
                    )

    def test_live_endpoint_parity_rejects_trigger_numeric_type_drift(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(java),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(
                python,
                trigger_payload_updates={"minimumAmountDue": 48.96},
            ),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_live_endpoint_parity_rejects_missing_trigger_payload_fact(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(java),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(
                python,
                trigger_payload_updates={"outcome": "SUCCESS"},
            ),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_live_endpoint_parity_rejects_deep_trigger_payload_drift(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(
                java,
                trigger_payload_updates={
                    "decisionContext": {"riskSignals": [{"code": "late-payment", "score": "0.42"}]},
                },
            ),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(
                python,
                trigger_payload_updates={
                    "decisionContext": {"riskSignals": [{"code": "late-payment", "score": "0.43"}]},
                },
            ),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_live_endpoint_parity_does_not_normalize_provider_text_in_business_facts(self):
        java = live_provider(self.module, "banking", "java")
        python = live_provider(self.module, "banking-python", "python")
        java_behavior = credit_card_endpoint_behavior(
            self.module,
            java,
            credit_card_endpoint_response(
                java,
                trigger_payload_updates={"accountName": "banking rewards"},
            ),
        )
        python_behavior = credit_card_endpoint_behavior(
            self.module,
            python,
            credit_card_endpoint_response(
                python,
                trigger_payload_updates={"accountName": "banking-python rewards"},
            ),
        )

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            compare_credit_card_endpoint_behavior(self.module, java_behavior, python_behavior)

    def test_decision_trigger_summary_rejects_unbound_identity_or_evidence(self):
        provider = self.module.Provider(
            name="java-banking-remote",
            base_url="http://unused",
            expected_domain_id="banking",
            expected_pack_id="banking-policy-pack",
            expected_language="java",
        )
        trigger_ref = "banking|domain-system|statement-1|account:acct-1"
        base_trigger = {
            "schemaVersion": "customer_decision_trigger.v1",
            "triggerId": f"statement_close:{trigger_ref}",
            "triggerType": "statement_close",
            "triggerRef": trigger_ref,
            "scheduledForEpochMs": 1_777_000_000_000,
            "priority": "NORMAL",
            "schedulingPriority": "NORMAL",
            "reason": "Credit card statement closed",
            "payload": {
                "policyVersion": "banking.customer-decision-trigger-policy.v1",
                "normalizedEventType": "statement_close",
                "sourceEventType": "remote_domain_credit_card_statement_closed",
            },
            "evidenceRefs": [trigger_ref],
        }
        for field, invalid_value in (
            ("triggerId", "statement_close:other"),
            ("evidenceRefs", ["other"]),
        ):
            with self.subTest(field=field):
                trigger = copy.deepcopy(base_trigger)
                trigger[field] = invalid_value
                response = {"result": {"domainSystemWork": {"decisionTriggers": [trigger]}}}
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    self.module.decision_trigger_summary(
                        provider,
                        "statement-close",
                        response,
                        ["statement_close"],
                    )


if __name__ == "__main__":
    main()
