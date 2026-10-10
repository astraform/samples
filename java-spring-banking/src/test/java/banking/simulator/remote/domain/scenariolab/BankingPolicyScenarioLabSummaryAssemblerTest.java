package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.EvaluationRunReport;
import ai.astraform.remote.domain.sdk.EvaluationSummary;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankingPolicyScenarioLabSummaryAssemblerTest {

    @Test
    void retainsPassOnlyWhenEvaluationReportBelongsToTheExactBranchRun() {
        BankingPolicyScenarioLabSummaryAssembler assembler =
                new BankingPolicyScenarioLabSummaryAssembler(new ObjectMapper(), Path.of("."));
        EvaluationRunReport owned = passingReport("run-1-candidate");
        EvaluationRunReport wrongRun = passingReport("run-2-candidate");
        EvaluationRunReport summaryOnly = new EvaluationRunReport();
        summaryOnly.setSummary(owned.getSummary());

        assertThat(assembler.ownedEvaluationReport(owned, "run-1-candidate")).isSameAs(owned);
        assertThat(assembler.ownedEvaluationReport(wrongRun, "run-1-candidate")).isNull();
        assertThat(assembler.ownedEvaluationReport(summaryOnly, "run-1-candidate")).isNull();

        AggregateMetrics metrics = new AggregateMetrics(
                "run-1-candidate",
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "");
        assertThat(metrics.asSummarySection(
                        assembler.ownedEvaluationReport(wrongRun, "run-1-candidate"))
                .overallVerdict()).isEqualTo("UNVERIFIED");
        assertThat(metrics.asSummarySection(
                        assembler.ownedEvaluationReport(summaryOnly, "run-1-candidate"))
                .overallVerdict()).isEqualTo("UNVERIFIED");
    }

    @Test
    void rateKpiMutationAndMetricTraceUseTheSameBranchBalances() {
        BankingPolicyScenarioLabSummaryAssembler assembler =
                new BankingPolicyScenarioLabSummaryAssembler(new ObjectMapper(), Path.of("."));
        BankingPolicyKpiEvaluator evaluator = new BankingPolicyKpiEvaluator();
        BankingPolicyKpiEvaluator.EvaluationResult kpis = evaluator.evaluate(
                new BankingPolicyKpiEvaluator.EvaluationInput(
                        new BankingPolicyKpiEvaluator.BranchIdentity("run-1", "run-1-baseline"),
                        new BankingPolicyKpiEvaluator.BranchIdentity("run-1", "run-1-candidate"),
                        new BankingPolicyKpiEvaluator.ProductFacts("CC001", "ASSET"),
                        BankingPolicyKpiEvaluator.PolicyKind.RATE,
                        new BigDecimal("0.19"),
                        new BigDecimal("0.24"),
                        List.of(customer("customer-1", "1000")),
                        List.of(customer("customer-1", "500")),
                        List.of(new BankingPolicyKpiEvaluator.CustomerOutcome(
                                "customer-1", false,
                                new BigDecimal("1000"), new BigDecimal("500"),
                                BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, BigDecimal.ZERO)),
                        List.of(),
                        List.of()));
        double kpiDelta = kpis.estimatedMonthlyInterestDelta().doubleValue();
        AgentDiffRow row = new AgentDiffRow(
                "Alice", "retail", "CC001", "Standard Credit Card", "CREDIT_CARD", "ASSET",
                1000, 500, 500, 500, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, false, false,
                kpiDelta, kpiDelta, kpiDelta,
                false, null, null);
        ScenarioLabRunParameters parameters = new ScenarioLabRunParameters()
                .presetId("rate-proof")
                .baselineRate("0.19")
                .candidateRate("0.24");
        DecisionComparisonSummary comparison = new DecisionComparisonSummary(
                "customer_outcome_decision_comparison.v1",
                "comparison-1",
                "scenario-1",
                "Rate",
                "What changed?",
                branch("baseline", "run-1-baseline"),
                branch("proposed", "run-1-candidate"),
                List.of(new DecisionMetricDeltaSummary(
                        "estimatedMonthlyInterest", "Monthly interest", 15.83, 10.00, kpiDelta,
                        "AUD", "comparisonReport")),
                new DecisionReplayEvidenceSummary(Map.of(), Map.of(), Map.of(), List.of()));
        SideEffectLedgerSummary ledger = new SideEffectLedgerSummary(
                "customer_outcome_side_effect_ledger.v1",
                List.of(new SideEffectEntrySummary(
                        "effect-1", "candidate", "proposed", "API_CALL", "policy",
                        "/policy/rate-change", "APPLIED", 0, "event", Map.of(), Map.of())));

        BranchStateProjectionSummary projection = assembler.buildBranchStateProjection(
                "run-1",
                parameters,
                PolicyType.RATE,
                run("baseline", "run-1-baseline"),
                run("candidate", "run-1-candidate"),
                List.of(row),
                ledger,
                comparison);

        StateProjectionMutation mutation = projection.mutations().getFirst();
        StateProjectionMetricTrace trace = projection.metricTraces().getFirst();
        assertThat(mutation.baselineValue()).isEqualTo(15.83);
        assertThat(mutation.projectedValue()).isEqualTo(10.00);
        assertThat(mutation.deltaValue()).isEqualTo(kpiDelta);
        assertThat(trace.deltaValue()).isEqualTo(kpiDelta);
        assertThat(trace.sourceMutationIds()).containsExactly(mutation.mutationId());
        assertThat(trace.evidenceChain()).containsEntry("supportLevel", "MUTATION_LINKED");
    }

    private static BankingPolicyKpiEvaluator.CustomerSnapshot customer(String id, String productBalance) {
        return new BankingPolicyKpiEvaluator.CustomerSnapshot(
                id, false, BigDecimal.ZERO, BigDecimal.ZERO, false,
                new BigDecimal(productBalance), new BigDecimal(productBalance));
    }

    private static DecisionBranchSummary branch(String role, String runId) {
        return new DecisionBranchSummary(
                role, role, runId, "rate-proof", role, role, Map.of(), Map.of());
    }

    private static BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult run(
            String branch,
            String runId) {
        return new BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult(
                branch,
                runId,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Path.of("."));
    }

    private static EvaluationRunReport passingReport(String runId) {
        EvaluationRunReport report = new EvaluationRunReport();
        EvaluationRunReport.EvaluationRunInfo run = new EvaluationRunReport.EvaluationRunInfo();
        run.setRunId(runId);
        report.setRun(run);
        EvaluationSummary summary = new EvaluationSummary();
        summary.setOverallScore(0.95);
        summary.setOverallVerdict(EvaluationSummary.OverallVerdictEnum.PASS);
        report.setSummary(summary);
        return report;
    }
}
