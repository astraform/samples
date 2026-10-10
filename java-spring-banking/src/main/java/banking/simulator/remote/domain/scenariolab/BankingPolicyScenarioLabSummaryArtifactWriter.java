package banking.simulator.remote.domain.scenariolab;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.csv;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.blank;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.LOW_LIQUIDITY_THRESHOLD;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.relativizeToWorkspace;

final class BankingPolicyScenarioLabSummaryArtifactWriter {

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;

    BankingPolicyScenarioLabSummaryArtifactWriter(ObjectMapper objectMapper, Path workspaceRoot) {
        this.objectMapper = objectMapper;
        this.workspaceRoot = workspaceRoot;
    }

    SummaryArtifacts writeSummaryArtifacts(
            Path summaryDir,
            String runId,
            BankingPolicyScenarioSummaryPayload summary,
            Path baselineDir,
            Path candidateDir,
            Path baselineReportPath,
            Path candidateReportPath,
            Path comparisonReportPath) throws IOException {
        Path checkpointsJson = summaryDir.resolve("checkpoints.json");
        Path agentDiffsJson = summaryDir.resolve("agent-diffs.json");
        Path notableActionsJson = summaryDir.resolve("notable-actions.json");
        Path scheduledWorkFailuresJson = summaryDir.resolve("scheduled-work-failures.json");
        Path simulationTimelineJson = summaryDir.resolve("simulation-timeline.json");
        Path sideEffectLedgerJson = summaryDir.resolve("side-effect-ledger.json");
        Path branchStateProjectionJson = summaryDir.resolve("branch-state-projection.json");
        Path impactedSegmentsJson = summaryDir.resolve("impacted-segments.json");
        Path impactedCohortsCsv = summaryDir.resolve("impacted-cohorts.csv");
        Path experimentCard = summaryDir.resolve("experiment-card.md");
        Path summaryPath = summaryDir.resolve("scenario-lab-summary.json");

        SummaryArtifacts artifacts = new SummaryArtifacts(
                summaryPath,
                impactedCohortsCsv,
                experimentCard,
                checkpointsJson,
                agentDiffsJson,
                notableActionsJson,
                scheduledWorkFailuresJson,
                simulationTimelineJson,
                sideEffectLedgerJson,
                branchStateProjectionJson,
                impactedSegmentsJson);

        ArtifactSummary artifactSummary = buildArtifactSummary(
                baselineDir,
                candidateDir,
                baselineReportPath,
                candidateReportPath,
                comparisonReportPath,
                impactedCohortsCsv,
                impactedSegmentsJson,
                checkpointsJson,
                agentDiffsJson,
                notableActionsJson,
                scheduledWorkFailuresJson,
                simulationTimelineJson,
                sideEffectLedgerJson,
                branchStateProjectionJson,
                experimentCard);

        BankingPolicyScenarioSummaryPayload finalSummary = summary.withArtifacts(artifactSummary);

        writeJson(checkpointsJson, finalSummary.checkpoints());
        writeJson(agentDiffsJson, finalSummary.agentDiffs());
        writeJson(notableActionsJson, finalSummary.notableActions());
        writeJson(scheduledWorkFailuresJson, finalSummary.scheduledWorkFailures());
        writeJson(simulationTimelineJson, finalSummary.simulationTimeline());
        writeJson(sideEffectLedgerJson, finalSummary.sideEffectLedger());
        writeJson(branchStateProjectionJson, finalSummary.branchStateProjection());
        writeJson(impactedSegmentsJson, finalSummary.impactedSegments());
        Files.writeString(impactedCohortsCsv, buildImpactedCohortsCsv(finalSummary));
        Files.writeString(experimentCard, buildExperimentCard(runId, finalSummary));
        writeJson(summaryPath, finalSummary);
        return artifacts;
    }

    ArtifactSummary buildArtifactSummary(
            Path baselineDir,
            Path candidateDir,
            Path baselineReportPath,
            Path candidateReportPath,
            Path comparisonReportPath,
            Path impactedCohortsCsv,
            Path impactedSegmentsJson,
            Path checkpointsJson,
            Path agentDiffsJson,
            Path notableActionsJson,
            Path scheduledWorkFailuresJson,
            Path simulationTimelineJson,
            Path sideEffectLedgerJson,
            Path branchStateProjectionJson,
            Path experimentCard) {
        return new ArtifactSummary(
                baselineDir.toString(),
                candidateDir.toString(),
                relativizeToWorkspace(workspaceRoot, baselineReportPath),
                relativizeToWorkspace(workspaceRoot, candidateReportPath),
                relativizeToWorkspace(workspaceRoot, comparisonReportPath),
                relativizeToWorkspace(workspaceRoot, impactedCohortsCsv),
                relativizeToWorkspace(workspaceRoot, impactedSegmentsJson),
                relativizeToWorkspace(workspaceRoot, checkpointsJson),
                relativizeToWorkspace(workspaceRoot, agentDiffsJson),
                relativizeToWorkspace(workspaceRoot, notableActionsJson),
                relativizeToWorkspace(workspaceRoot, scheduledWorkFailuresJson),
                relativizeToWorkspace(workspaceRoot, simulationTimelineJson),
                relativizeToWorkspace(workspaceRoot, sideEffectLedgerJson),
                relativizeToWorkspace(workspaceRoot, branchStateProjectionJson),
                relativizeToWorkspace(workspaceRoot, experimentCard),
                "banking-domain-product-catalog");
    }

    private String buildImpactedCohortsCsv(BankingPolicyScenarioSummaryPayload summary) {
        List<AgentDiffRow> rows = summary.agentDiffs();
        Map<String, StateProjectionMutation> mutationsByPersona = mutationsByPersona(summary.branchStateProjection().mutations());
        double hardshipThreshold = hardshipThreshold(summary);
        StringBuilder builder = new StringBuilder();
        builder.append("personaName,segment,productCode,productName,productCategory,balanceSheetSide,productBalance,interestBearingBalance,currentBalance,baselineCurrentBalance,candidateCurrentBalance,deltaCurrentBalance,projectedLiquidity,subsidyAmount,deltaLiquidity,hardshipScopeReason,residualHardshipStatus,baselineCreditLimit,candidateCreditLimit,deltaCreditLimit,baselineAvailableCredit,candidateAvailableCredit,deltaAvailableCredit,baselineCreditUtilisation,candidateCreditUtilisation,deltaCreditUtilisation,hardshipFlag,lowLiquidityBefore,lowLiquidityAfter,subsidyReceived,deltaMonthlyInterest,deltaMonthlyBankMetric,deltaMonthlyBankPnl\n");
        for (AgentDiffRow row : rows) {
            StateProjectionMutation mutation = mutationsByPersona.get(row.personaName());
            double projectedLiquidity = mutation == null ? row.candidateCurrentBalance() : mutation.projectedValue();
            double subsidyAmount = row.subsidyReceived()
                    ? (mutation == null ? Math.max(row.deltaCurrentBalance(), 0.0) : Math.max(mutation.deltaValue(), 0.0))
                    : 0.0;
            double deltaLiquidity = mutation == null ? row.deltaCurrentBalance() : mutation.deltaValue();
            builder.append(csv(row.personaName())).append(',')
                    .append(csv(row.segment())).append(',')
                    .append(csv(row.productCode())).append(',')
                    .append(csv(row.productName())).append(',')
                    .append(csv(row.productCategory())).append(',')
                    .append(csv(row.balanceSheetSide())).append(',')
                    .append(csv(row.productBalance())).append(',')
                    .append(csv(row.interestBearingBalance())).append(',')
                    .append(csv(row.currentBalance())).append(',')
                    .append(csv(row.baselineCurrentBalance())).append(',')
                    .append(csv(row.candidateCurrentBalance())).append(',')
                    .append(csv(row.deltaCurrentBalance())).append(',')
                    .append(csv(projectedLiquidity)).append(',')
                    .append(csv(subsidyAmount)).append(',')
                    .append(csv(deltaLiquidity)).append(',')
                    .append(csv(hardshipScopeReason(row))).append(',')
                    .append(csv(residualHardshipStatus(row, projectedLiquidity, hardshipThreshold))).append(',')
                    .append(csv(row.baselineCreditLimit())).append(',')
                    .append(csv(row.candidateCreditLimit())).append(',')
                    .append(csv(row.deltaCreditLimit())).append(',')
                    .append(csv(row.baselineAvailableCredit())).append(',')
                    .append(csv(row.candidateAvailableCredit())).append(',')
                    .append(csv(row.deltaAvailableCredit())).append(',')
                    .append(csv(row.baselineCreditUtilisation())).append(',')
                    .append(csv(row.candidateCreditUtilisation())).append(',')
                    .append(csv(row.deltaCreditUtilisation())).append(',')
                    .append(csv(row.hardshipFlag())).append(',')
                    .append(csv(row.lowLiquidityBefore())).append(',')
                    .append(csv(row.lowLiquidityAfter())).append(',')
                    .append(csv(row.subsidyReceived())).append(',')
                    .append(csv(row.deltaMonthlyInterest())).append(',')
                    .append(csv(row.deltaMonthlyBankMetric())).append(',')
                    .append(csv(row.deltaMonthlyBankPnl())).append('\n');
        }
        return builder.toString();
    }

    private String buildExperimentCard(String runId, BankingPolicyScenarioSummaryPayload summary) {
        return """
                # Policy Wind Tunnel Customer Outcome Report

                ## Decision Under Test

                - Run ID: `%s`
                - Comparison: `%s`
                - Scenario: `%s`
                - Decision question: %s
                - Recommendation: `%s`

                ## System Under Test

                - Preset: `%s`
                - Product: `%s` (`%s`)
                - Policy Type: `%s`
                - Compare Mode: `%s`
                - Baseline branch: `%s`
                - Proposed branch: `%s`

                ## Customer Outcome Summary

                - Simulated cohort size: `%d`
                - Impacted customers: `%d`
                - Exposed balance: `%s`
                - Delta monthly customer metric: `%s`
                - Delta monthly bank P&L: `%s`
                - Regression threshold breaches: `%d`
                - Decision gate fail breaches: `%d`
                - Decision gate warnings: `%d`
                - Launch posture reason: %s

                ## Decision Gates

                | Gate | Trigger | Observed | Reason | Status |
                | --- | --- | ---: | --- | --- |
                %s

                ## Decision Metrics and Evidence

                | Metric | Baseline | Proposed | Delta | Evidence support | Evidence refs |
                | --- | ---: | ---: | ---: | --- | --- |
                %s

                ## Replay Evidence Chain

                - Timeline artifact: `%s`
                - Side-effect ledger: `%s`
                - Branch state projection: `%s`
                - Comparison report: `%s`
                - Required replay evidence: `%s`
                - Evidence assumption: stress-test projection from deterministic branch state and declared policy side effects; not a real-world forecast.

                ## Top Customer State Mutations

                %s

                ## Exposure Scope

                - Scope: `%s`
                - Cohort Agents: `%d`
                - Eligible Agents: `%d`
                - Non-Eligible Agents: `%d`

                ## Payment Stress Signals

                - Total Failures: `%d`
                - Insufficient Funds Failures: `%d`
                - Stressed Personas: `%d`

                ## Limitations

                - Treat this report as decision-grade stress-test evidence, not a claim that Astraform predicts production outcomes.
                - Metrics without `MUTATION_LINKED` support are branch aggregate signals and should not be used as headline buyer claims until replay evidence is strengthened.
                - A launch decision still needs human review of customer profiles, timeline events, tool/API calls, policy checks, and state changes.
                """.formatted(
                runId,
                summary.decisionComparison().comparisonId(),
                summary.decisionComparison().scenarioId(),
                summary.decisionComparison().decisionQuestion(),
                buildRecommendation(summary.evaluator()),
                summary.demoPreset().presetId(),
                summary.policyToggle().productName(),
                summary.policyToggle().productCode(),
                summary.policyToggle().policyType(),
                summary.runConfig().compareMode(),
                summary.decisionComparison().baseline().branchId(),
                summary.decisionComparison().proposed().branchId(),
                summary.exposureSummary().cohortAgents(),
                summary.candidate().impactedAgents(),
                formatMetricValue(summary.candidate().exposedBalance(), "currency"),
                formatMetricValue(summary.delta().estimatedMonthlyCustomerInterest(), "currency"),
                formatMetricValue(summary.delta().estimatedMonthlyBankPnl(), "currency"),
                summary.evaluator().breachCount(),
                decisionGateFailCount(summary.evaluator()),
                decisionGateWarningCount(summary.evaluator()),
                buildLaunchPostureReason(summary.evaluator()),
                buildDecisionGateTable(summary.evaluator()),
                buildDecisionMetricEvidenceTable(summary),
                portableArtifactRef(summary.artifacts().simulationTimelineJson(), "simulation-timeline.json"),
                portableArtifactRef(summary.artifacts().sideEffectLedgerJson(), "side-effect-ledger.json"),
                portableArtifactRef(summary.artifacts().branchStateProjectionJson(), "branch-state-projection.json"),
                portableArtifactRef(summary.artifacts().comparisonReport(), "regression-comparison.json"),
                String.join(", ", summary.decisionComparison().replayEvidence().requiredEvidence()),
                buildTopStateMutations(summary.branchStateProjection().mutations()),
                summary.exposureSummary().scopeLabel(),
                summary.exposureSummary().cohortAgents(),
                summary.exposureSummary().eligibleAgents(),
                summary.exposureSummary().nonEligibleAgents(),
                summary.paymentStressSummary().totalFailureCount(),
                summary.paymentStressSummary().candidate().insufficientFundsFailureCount(),
                summary.paymentStressSummary().candidate().affectedPersonaCount());
    }

    private String buildRecommendation(EvaluatorSummary evaluator) {
        if (evaluator.decisionGates() != null && evaluator.decisionGates().failGateCount() > 0) {
            return "BLOCK_FOR_REVIEW";
        }
        if (evaluator.failBreaches() > 0) {
            return "BLOCK_FOR_REVIEW";
        }
        if (evaluator.decisionGates() != null && evaluator.decisionGates().breachedGateCount() > 0) {
            return "WATCH";
        }
        if (evaluator.breachCount() > 0) {
            return "WATCH";
        }
        return "READY_FOR_HUMAN_REVIEW";
    }

    private int decisionGateFailCount(EvaluatorSummary evaluator) {
        if (evaluator == null || evaluator.decisionGates() == null) {
            return 0;
        }
        return evaluator.decisionGates().failGateCount();
    }

    private int decisionGateWarningCount(EvaluatorSummary evaluator) {
        if (evaluator == null || evaluator.decisionGates() == null) {
            return 0;
        }
        return Math.max(evaluator.decisionGates().breachedGateCount() - evaluator.decisionGates().failGateCount(), 0);
    }

    private String buildLaunchPostureReason(EvaluatorSummary evaluator) {
        if (evaluator == null || evaluator.decisionGates() == null || evaluator.decisionGates().gates() == null) {
            return "`READY_FOR_HUMAN_REVIEW` because no decision gates were reported.";
        }
        List<DecisionGateSummary> failed = evaluator.decisionGates().gates().stream()
                .filter(DecisionGateSummary::fail)
                .toList();
        if (!failed.isEmpty()) {
            return "`BLOCK_FOR_REVIEW` because " + gateList(failed) + " failed.";
        }
        List<DecisionGateSummary> warned = evaluator.decisionGates().gates().stream()
                .filter(gate -> gate.breached() && !gate.fail())
                .toList();
        if (!warned.isEmpty()) {
            return "`WATCH` because " + gateList(warned) + " warned.";
        }
        if (evaluator.breachCount() > 0) {
            return "`WATCH` because regression threshold breaches were reported.";
        }
        return "`READY_FOR_HUMAN_REVIEW` because no fail or warn gates fired.";
    }

    private String gateList(List<DecisionGateSummary> gates) {
        return gates.stream()
                .map(gate -> "`" + markdownCell(gate.gateId()) + "` on `"
                        + markdownCell(gate.metricKey()) + "="
                        + markdownCell(formatMetricValue(gate.observedValue(), "count")) + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElse("a decision gate");
    }

    private String buildDecisionGateTable(EvaluatorSummary evaluator) {
        if (evaluator == null || evaluator.decisionGates() == null
                || evaluator.decisionGates().gates() == null || evaluator.decisionGates().gates().isEmpty()) {
            return "| -- | -- | -- | -- | `NOT_REPORTED` |";
        }
        StringBuilder builder = new StringBuilder();
        for (DecisionGateSummary gate : evaluator.decisionGates().gates()) {
            String status = gate.fail() ? "FAIL" : gate.breached() ? "WARN" : "PASS";
            builder.append("| `")
                    .append(markdownCell(gate.gateId()))
                    .append("` | `")
                    .append(markdownCell(gate.metricKey()))
                    .append("` | ")
                    .append(markdownCell(formatMetricValue(gate.observedValue(), "count")))
                    .append(" | ")
                    .append(markdownCell(gate.reason()))
                    .append(" | `")
                    .append(status)
                    .append("` |\n");
        }
        return builder.toString().trim();
    }

    private String buildDecisionMetricEvidenceTable(BankingPolicyScenarioSummaryPayload summary) {
        StringBuilder builder = new StringBuilder();
        for (DecisionMetricDeltaSummary metric : summary.decisionComparison().metricDeltas()) {
            StateProjectionMetricTrace trace = findMetricTrace(summary.branchStateProjection().metricTraces(), metric.metricKey());
            Map<String, Object> chain = trace == null ? Map.of() : trace.evidenceChain();
            String supportLevel = String.valueOf(chain.getOrDefault("supportLevel", "BRANCH_AGGREGATE_ONLY"));
            String evidenceRefs = metric.evidenceRef();
            if (chain.containsKey("artifactRefs")) {
                evidenceRefs = evidenceRefs + "; " + compactArtifactRefs(chain.get("artifactRefs"));
            }
            if (trace != null && trace.sourceMutationIds() != null && !trace.sourceMutationIds().isEmpty()) {
                evidenceRefs = evidenceRefs + "; " + trace.sourceMutationIds().size() + " source mutations";
            }
            builder.append("| ")
                    .append(markdownCell(metric.label()))
                    .append(" | ")
                    .append(markdownCell(formatMetricValue(metric.baselineValue(), metric.unit())))
                    .append(" | ")
                    .append(markdownCell(formatMetricValue(metric.proposedValue(), metric.unit())))
                    .append(" | ")
                    .append(markdownCell(formatMetricValue(metric.deltaValue(), metric.unit())))
                    .append(" | `")
                    .append(markdownCell(supportLevel))
                    .append("` | ")
                    .append(markdownCell(evidenceRefs))
                    .append(" |\n");
        }
        return builder.toString().trim();
    }

    private StateProjectionMetricTrace findMetricTrace(List<StateProjectionMetricTrace> traces, String metricKey) {
        if (traces == null || metricKey == null) {
            return null;
        }
        return traces.stream()
                .filter(trace -> metricKey.equals(trace.metricKey()))
                .findFirst()
                .orElse(null);
    }

    private String compactArtifactRefs(Object artifactRefs) {
        if (!(artifactRefs instanceof Map<?, ?> map) || map.isEmpty()) {
            return "no artifact refs";
        }
        return map.keySet().stream()
                .map(String::valueOf)
                .limit(4)
                .reduce((left, right) -> left + ", " + right)
                .orElse("no artifact refs");
    }

    private Map<String, StateProjectionMutation> mutationsByPersona(List<StateProjectionMutation> mutations) {
        Map<String, StateProjectionMutation> indexed = new LinkedHashMap<>();
        if (mutations == null) {
            return indexed;
        }
        for (StateProjectionMutation mutation : mutations) {
            indexed.putIfAbsent(mutation.personaName(), mutation);
        }
        return indexed;
    }

    private double hardshipThreshold(BankingPolicyScenarioSummaryPayload summary) {
        if (summary.sideEffectLedger() == null || summary.sideEffectLedger().entries() == null) {
            return LOW_LIQUIDITY_THRESHOLD;
        }
        for (SideEffectEntrySummary entry : summary.sideEffectLedger().entries()) {
            Object threshold = entry.response() == null ? null : entry.response().get("hardshipThreshold");
            if (threshold instanceof Number number) {
                return number.doubleValue();
            }
            if (threshold instanceof String text && !blank(text)) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    return LOW_LIQUIDITY_THRESHOLD;
                }
            }
        }
        return LOW_LIQUIDITY_THRESHOLD;
    }

    private String hardshipScopeReason(AgentDiffRow row) {
        if (row.subsidyReceived()) {
            return "subsidy_received";
        }
        if (row.hardshipFlag()) {
            return "hardship_flag";
        }
        if ("hardship".equalsIgnoreCase(row.segment())) {
            return "hardship_segment";
        }
        if (row.lowLiquidityBefore() || row.lowLiquidityAfter()) {
            return "low_liquidity";
        }
        return "policy_impacted";
    }

    private String residualHardshipStatus(AgentDiffRow row, double projectedLiquidity, double hardshipThreshold) {
        if (!row.subsidyReceived() && !row.lowLiquidityBefore() && !row.lowLiquidityAfter() && !row.hardshipFlag()) {
            return "not_hardship_scope";
        }
        return projectedLiquidity < hardshipThreshold ? "residual_hardship" : "relieved_above_threshold";
    }

    private String portableArtifactRef(String value, String fallback) {
        if (blank(value)) {
            return fallback;
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }

    private String buildTopStateMutations(List<StateProjectionMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return "- No customer/account state mutations were projected.";
        }
        StringBuilder builder = new StringBuilder();
        mutations.stream().limit(5).forEach(mutation -> builder.append("- `")
                .append(markdownCell(mutation.mutationType()))
                .append("`: ")
                .append(markdownCell(mutation.personaName()))
                .append(" changed by `")
                .append(markdownCell(formatMetricValue(mutation.deltaValue(), "currency")))
                .append("` via `")
                .append(markdownCell(mutation.sourceEffectId()))
                .append("` (`")
                .append(markdownCell(mutation.evidenceRef()))
                .append("`).\n"));
        return builder.toString().trim();
    }

    private String formatMetricValue(double value, String unit) {
        if ("currency".equalsIgnoreCase(unit)) {
            return "$" + String.format(java.util.Locale.ROOT, "%,.2f", value);
        }
        return String.format(java.util.Locale.ROOT, "%,.4f", value).replaceAll("\\.?0+$", "");
    }

    private String markdownCell(String value) {
        if (blank(value)) {
            return "--";
        }
        return value.replace("|", "\\|").replace("\n", " ").trim();
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
