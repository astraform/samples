package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.EvaluationRunReport;
import ai.astraform.remote.domain.sdk.RegressionDeltaReport;
import ai.astraform.remote.domain.sdk.RegressionThresholdBreach;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

final class BankingPolicyScenarioLabSummaryAssembler {

    private final Path workspaceRoot;
    private final BankingPolicyScenarioLabSummaryArtifactWriter artifactWriter;
    private final BankingPolicyScenarioLabModifierProjector scenarioModifierProjector;
    private final BankingCustomerOutcomeDecisionCatalog decisionCatalog;
    private final BankingPolicyKpiEvaluator kpiEvaluator;
    private final BankingPolicyKpiEvidenceAdapter kpiEvidenceAdapter;

    BankingPolicyScenarioLabSummaryAssembler(ObjectMapper objectMapper, Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.artifactWriter = new BankingPolicyScenarioLabSummaryArtifactWriter(objectMapper, workspaceRoot);
        this.scenarioModifierProjector = new BankingPolicyScenarioLabModifierProjector();
        this.decisionCatalog = new BankingCustomerOutcomeDecisionCatalog(objectMapper, workspaceRoot);
        this.kpiEvaluator = new BankingPolicyKpiEvaluator();
        this.kpiEvidenceAdapter = new BankingPolicyKpiEvidenceAdapter();
    }

    SummaryArtifacts buildSummaryArtifacts(
            String runId,
            BankingPolicyScenarioLabRequest resolved,
            BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            Path baselineReportPath,
            Path candidateReportPath,
            Path comparisonReportPath,
            EvaluationRunReport baselineReport,
            EvaluationRunReport candidateReport,
            RegressionDeltaReport comparisonReport,
            Path summaryDir,
            Path baselineDir,
            Path candidateDir) throws IOException {
        BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata = executionPlan.productMetadata();
        PolicyType policyType = resolvePolicyType(resolved.parameters());
        BankingPolicyKpiEvidenceAdapter.AdaptedEvidence kpiEvidence = kpiEvidenceAdapter.adapt(
                executionPlan.seededAgents(),
                executionPlan.baselineEvents(),
                executionPlan.candidateEvents(),
                baseline.agentsAfter(),
                candidate.agentsAfter(),
                baseline.eventResults(),
                candidate.eventResults());
        EvaluationRunReport ownedBaselineReport = ownedEvaluationReport(baselineReport, baseline.suiteRunId());
        EvaluationRunReport ownedCandidateReport = ownedEvaluationReport(candidateReport, candidate.suiteRunId());
        Map<String, BankingPolicyScenarioAgentSnapshot> baselineAgents =
                indexAgents(baseline.agentsAfter(), kpiEvidence);
        Map<String, BankingPolicyScenarioAgentSnapshot> candidateAgents =
                indexAgents(candidate.agentsAfter(), kpiEvidence);
        HardshipImpact hardshipImpact = summarizeHardshipImpact(candidate.eventResults());
        List<AgentDiffRow> agentDiffs = buildAgentDiffs(
                baselineAgents,
                candidateAgents,
                productMetadata,
                resolved.parameters(),
                policyType,
                kpiEvidence);
        List<ImpactedSegment> impactedSegments = buildImpactedSegments(agentDiffs);
        List<MergedCheckpoint> checkpoints = mergeCheckpoints(
                baseline.checkpoints(),
                candidate.checkpoints());
        List<NotableAction> notableActions = buildNotableActions(agentDiffs, policyType);
        List<ScheduledFailureEvent> baselineFailureEvents = scheduledFailureEvents(baseline.agentsAfter());
        List<ScheduledFailureEvent> candidateFailureEvents = scheduledFailureEvents(candidate.agentsAfter());
        List<ScheduledWorkFailure> scheduledWorkFailures = buildScheduledWorkFailures(candidateFailureEvents);
        PaymentStressSummary paymentStressSummary = buildPaymentStressSummary(baselineFailureEvents, candidateFailureEvents);

        BankingPolicyKpiEvaluator.EvaluationResult kpiResult = kpiEvaluator.evaluate(buildKpiInput(
                runId,
                baseline,
                candidate,
                baselineAgents,
                candidateAgents,
                agentDiffs,
                kpiEvidence,
                productMetadata,
                resolved.parameters(),
                policyType));
        AggregateMetrics baselineMetrics = toAggregateMetrics(kpiResult.baseline());
        AggregateMetrics candidateMetrics = toAggregateMetrics(kpiResult.candidate());
        AggregateMetrics deltaMetrics = candidateMetrics.subtract(baselineMetrics);

        List<RegressionThresholdBreach> thresholdBreaches = comparisonReport.getThresholdBreaches() == null
                ? List.of()
                : List.copyOf(comparisonReport.getThresholdBreaches());
        HeadlineExtras headlineExtras = buildHeadlineExtras(
                policyType,
                agentDiffs,
                kpiResult.candidate(),
                paymentStressSummary,
                hardshipImpact,
                resolved.parameters());
        TradeoffSummary tradeoffSummary = buildTradeoffSummary(policyType, productMetadata);
        PressureMetricsSummary pressureMetrics = buildPressureMetrics(
                policyType,
                baselineMetrics,
                candidateMetrics,
                deltaMetrics,
                headlineExtras);
        SegmentImpactSummary segmentImpactSummary = buildSegmentImpactSummary(
                agentDiffs,
                policyType,
                hardshipImpact,
                resolved.parameters());
        DecisionGatesSummary decisionGates = buildDecisionGates(
                paymentStressSummary,
                thresholdBreaches,
                headlineExtras);
        ExposureSummary exposureSummary = buildExposureSummary(
                customerAgentCount(candidateAgents.values()),
                agentDiffs.size(),
                productMetadata,
                policyType);
        ArtifactSummary artifactSummary = artifactWriter.buildArtifactSummary(
                baselineDir,
                candidateDir,
                baselineReportPath,
                candidateReportPath,
                comparisonReportPath,
                summaryDir.resolve("impacted-cohorts.csv"),
                summaryDir.resolve("impacted-segments.json"),
                summaryDir.resolve("checkpoints.json"),
                summaryDir.resolve("agent-diffs.json"),
                summaryDir.resolve("notable-actions.json"),
                summaryDir.resolve("scheduled-work-failures.json"),
                summaryDir.resolve("simulation-timeline.json"),
                summaryDir.resolve("side-effect-ledger.json"),
                summaryDir.resolve("branch-state-projection.json"),
                summaryDir.resolve("experiment-card.md"));
        DecisionComparisonSummary decisionComparison = buildDecisionComparison(
                resolved.parameters(),
                policyType,
                baseline,
                candidate,
                baselineMetrics,
                candidateMetrics,
                deltaMetrics,
                ownedBaselineReport,
                ownedCandidateReport,
                comparisonReport,
                headlineExtras,
                artifactSummary);
        SideEffectLedgerSummary sideEffectLedger = buildSideEffectLedger(
                baseline,
                candidate,
                checkpoints,
                scheduledWorkFailures,
                decisionGates,
                decisionComparison);
        SimulationTimelineSummary simulationTimeline = buildSimulationTimeline(
                checkpoints,
                scheduledWorkFailures,
                sideEffectLedger,
                decisionComparison);
        BranchStateProjectionSummary branchStateProjection = buildBranchStateProjection(
                runId,
                resolved.parameters(),
                policyType,
                baseline,
                candidate,
                agentDiffs,
                sideEffectLedger,
                decisionComparison);

        BankingPolicyScenarioSummaryPayload summary = new BankingPolicyScenarioSummaryPayload(
                demoIdForPolicyType(policyType),
                new DemoPresetSummary(resolved.parameters().getPresetId()),
                "scenario_lab_kpi_v2",
                tradeoffSummary,
                pressureMetrics,
                segmentImpactSummary,
                scenarioIdForPreset(resolved.parameters().getPresetId()),
                decisionComparison,
                simulationTimeline,
                sideEffectLedger,
                branchStateProjection,
                buildPolicyToggle(productMetadata, resolved.parameters(), policyType),
                buildEconomics(productMetadata),
                new CompareModeStrategySummary(
                        resolved.parameters().getCompareMode(),
                        "seeded_compare_exact_delta",
                        "FORCE_DETERMINISTIC_REPLAY_SAFE",
                        "EXACT_DELTA",
                        "BASELINE_CANDIDATE_SINGLE_PAIR",
                        "PHASE2_PUBLIC"),
                new RunConfigSummary(
                        "DEMO_PERSONA_LIBRARY",
                        DEFAULT_COHORT_SIZE,
                        intValue(resolved.parameters().getSimulationHorizonDays(), 30),
                        intValue(resolved.parameters().getPrePolicyDays(), 10),
                        intValue(resolved.parameters().getPostPolicyDays(), 20),
                        intValue(resolved.parameters().getPrePolicyDays(), 10),
                        resolved.parameters().getRunMode(),
                        resolved.parameters().getCadenceProfile(),
                        resolved.parameters().getCompareMode(),
                        true),
                buildT0Snapshot(baseline, candidate),
                new ScenarioModifiersSummary(
                        scenarioModifierProjector.project(baseline.eventResults()),
                        scenarioModifierProjector.project(candidate.eventResults())),
                baselineMetrics.asSummarySection(ownedBaselineReport),
                candidateMetrics.asSummarySection(ownedCandidateReport),
                deltaMetrics.asDeltaSection(comparisonReport),
                checkpoints,
                agentDiffs,
                notableActions,
                scheduledWorkFailures,
                impactedSegments,
                exposureSummary,
                paymentStressSummary,
                new HeadlineSummary(
                        agentDiffs.size(),
                        round(candidateMetrics.exposedBalance()),
                        round(candidateMetrics.interestBearingBalance()),
                        round(candidateMetrics.totalCreditLimit()),
                        round(candidateMetrics.availableCredit()),
                        round(deltaMetrics.availableCredit()),
                        round4(candidateMetrics.averageCreditUtilisation()),
                        round(deltaMetrics.estimatedMonthlyInterest()),
                        round(deltaMetrics.estimatedMonthlyInterest()),
                        round(deltaMetrics.estimatedMonthlyBankMetric()),
                        round(deltaMetrics.estimatedMonthlyBankPnl()),
                        round(candidateMetrics.oneTimeCustomerLiquiditySupport()),
                        round(candidateMetrics.oneTimeBankCost()),
                        round(candidateMetrics.totalHouseholdLiquidity()),
                        round(deltaMetrics.totalHouseholdLiquidity()),
                        candidateMetrics.lowLiquidityAgents(),
                        headlineExtras.lowLiquidityImpactedAgents(),
                        round(candidateMetrics.retainedFundedBalanceProxy()),
                        round4(candidateMetrics.liquiditySupportRateProxy()),
                        paymentStressSummary.candidate().totalFailureCount(),
                        paymentStressSummary.candidate().insufficientFundsFailureCount(),
                        candidateMetrics.reliefRecipients(),
                        candidateMetrics.subsidiesIssued(),
                        round(candidateMetrics.policyFundBalance()),
                        headlineExtras.constrainedCustomers(),
                        round(headlineExtras.overLimitExposure()),
                        round(headlineExtras.hardshipBudgetBurn()),
                        headlineExtras.residualHardshipAgents(),
                        round4(headlineExtras.hardshipTargetingPrecisionProxy()),
                        headlineExtras.fraudAlertedAgents(),
                        headlineExtras.fraudAlertedAccounts(),
                        headlineExtras.fraudAlertWaveCount(),
                        round(headlineExtras.customerFrictionProxy()),
                        round(headlineExtras.operatorReviewHoursProxy()),
                        headlineExtras.fraudSeverity(),
                        headlineExtras.fraudChannel(),
                        headlineTopSegment(segmentImpactSummary, impactedSegments)),
                new EvaluatorSummary(
                        thresholdBreaches.size(),
                        thresholdBreaches.stream()
                                .filter(entry -> entry.getSeverity() == RegressionThresholdBreach.SeverityEnum.FAIL)
                                .count(),
                        relativizeToWorkspace(workspaceRoot, comparisonReportPath),
                        thresholdBreaches,
                        decisionGates),
                artifactSummary);
        return artifactWriter.writeSummaryArtifacts(
                summaryDir,
                runId,
                summary,
                baselineDir,
                candidateDir,
                baselineReportPath,
                candidateReportPath,
                comparisonReportPath);
    }

    private List<AgentDiffRow> buildAgentDiffs(
            Map<String, BankingPolicyScenarioAgentSnapshot> baselineAgents,
            Map<String, BankingPolicyScenarioAgentSnapshot> candidateAgents,
            BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata,
            ScenarioLabRunParameters parameters,
            PolicyType policyType,
            BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence) {
        Set<String> customerIds = new LinkedHashSet<>();
        customerIds.addAll(baselineAgents.keySet());
        customerIds.addAll(candidateAgents.keySet());

        List<AgentDiffRow> rows = new ArrayList<>();
        BigDecimal baselineRate = decimal(parameters.getBaselineRate());
        BigDecimal candidateRate = decimal(parameters.getCandidateRate());
        for (String customerId : customerIds) {
            BankingPolicyScenarioAgentSnapshot baseline = baselineAgents.getOrDefault(
                    customerId, BankingPolicyScenarioAgentSnapshot.empty(customerId));
            BankingPolicyScenarioAgentSnapshot candidate = candidateAgents.getOrDefault(
                    customerId, BankingPolicyScenarioAgentSnapshot.empty(customerId));
            String personaName = firstNonBlank(candidate.personaName(), baseline.personaName(), customerId);
            BigDecimal baselineCurrentBalance = baseline.currentBalance();
            BigDecimal candidateCurrentBalance = candidate.currentBalance();
            BigDecimal deltaCurrentBalance = candidateCurrentBalance.subtract(baselineCurrentBalance);

            BigDecimal baselineProductBalance = policyType == PolicyType.HARDSHIP
                    ? baselineCurrentBalance
                    : baseline.totalProductBalance(parameters.getProductCode());
            BigDecimal candidateProductBalance = policyType == PolicyType.HARDSHIP
                    ? candidateCurrentBalance
                    : candidate.totalProductBalance(parameters.getProductCode());
            BigDecimal productBalance = candidateProductBalance;
            if (policyType != PolicyType.HARDSHIP
                    && baselineProductBalance.compareTo(BigDecimal.ZERO) == 0
                    && candidateProductBalance.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            boolean subsidyReceived = evidence.affectedBy(
                    BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                    BankingPolicyKpiEvaluator.InterventionKind.HARDSHIP_RELIEF,
                    customerId);
            boolean hardshipScoped = "hardship".equalsIgnoreCase(candidate.segment())
                    || "hardship".equalsIgnoreCase(baseline.segment())
                    || candidate.hardshipFlag()
                    || baseline.hardshipFlag()
                    || subsidyReceived;
            if (policyType == PolicyType.HARDSHIP && !hardshipScoped) {
                continue;
            }
            // Fraud responses expose aggregate alerted-agent/account counts, not customer identities.
            boolean fraudAlerted = false;
            BigDecimal baselineCreditLimit = policyType == PolicyType.HARDSHIP
                    ? baseline.totalCreditLimit()
                    : baseline.totalProductLimit(parameters.getProductCode());
            BigDecimal candidateCreditLimit = policyType == PolicyType.HARDSHIP
                    ? candidate.totalCreditLimit()
                    : candidate.totalProductLimit(parameters.getProductCode());
            BigDecimal baselineAvailableCredit = policyType == PolicyType.HARDSHIP
                    ? baseline.availableCredit()
                    : baseline.availableCredit(parameters.getProductCode());
            BigDecimal candidateAvailableCredit = policyType == PolicyType.HARDSHIP
                    ? candidate.availableCredit()
                    : candidate.availableCredit(parameters.getProductCode());
            BigDecimal baselineUtilisation = policyType == PolicyType.HARDSHIP
                    ? baseline.creditUtilisation()
                    : baseline.creditUtilisation(parameters.getProductCode());
            BigDecimal candidateUtilisation = policyType == PolicyType.HARDSHIP
                    ? candidate.creditUtilisation()
                    : candidate.creditUtilisation(parameters.getProductCode());
            BigDecimal deltaMonthlyInterest = policyType == PolicyType.HARDSHIP
                    ? BigDecimal.ZERO
                    : monthlyInterest(candidateProductBalance, candidateRate)
                            .subtract(monthlyInterest(baselineProductBalance, baselineRate));
            BigDecimal deltaMonthlyBankMetric = policyType == PolicyType.HARDSHIP
                    ? BigDecimal.ZERO
                    : deltaMonthlyInterest;
            BigDecimal deltaMonthlyBankPnl = policyType == PolicyType.HARDSHIP
                    ? BigDecimal.ZERO
                    : ("LIABILITY".equals(productMetadata.balanceSheetSide()) ? deltaMonthlyInterest.negate() : deltaMonthlyInterest);

            rows.add(new AgentDiffRow(
                    personaName,
                    firstNonBlank(candidate.segment(), baseline.segment(), "unclassified"),
                    productMetadata.productCode(),
                    policyType == PolicyType.HARDSHIP ? "Household Liquidity" : productMetadata.productName(),
                    policyType == PolicyType.HARDSHIP ? "HARDSHIP_RELIEF" : productMetadata.productCategory(),
                    policyType == PolicyType.HARDSHIP ? "OFF_BALANCE" : productMetadata.balanceSheetSide(),
                    round(baselineProductBalance),
                    round(candidateProductBalance),
                    round(productBalance),
                    round(policyType == PolicyType.HARDSHIP ? BigDecimal.ZERO : productBalance.abs()),
                    round(candidateCurrentBalance),
                    round(baselineCurrentBalance),
                    round(candidateCurrentBalance),
                    round(deltaCurrentBalance),
                    round(baselineCreditLimit),
                    round(candidateCreditLimit),
                    round(candidateCreditLimit.subtract(baselineCreditLimit)),
                    round(baselineAvailableCredit),
                    round(candidateAvailableCredit),
                    round(candidateAvailableCredit.subtract(baselineAvailableCredit)),
                    round4(baselineUtilisation).doubleValue(),
                    round4(candidateUtilisation).doubleValue(),
                    round4(candidateUtilisation.subtract(baselineUtilisation)).doubleValue(),
                    candidate.hardshipFlag(),
                    baselineCurrentBalance.compareTo(BigDecimal.valueOf(LOW_LIQUIDITY_THRESHOLD)) < 0,
                    candidateCurrentBalance.compareTo(BigDecimal.valueOf(LOW_LIQUIDITY_THRESHOLD)) < 0,
                    subsidyReceived,
                    round(deltaMonthlyInterest),
                    round(deltaMonthlyBankMetric),
                    round(deltaMonthlyBankPnl),
                    fraudAlerted,
                    firstNonBlank(parameters.getCandidateFraudSeverity(), null),
                    firstNonBlank(parameters.getCandidateFraudChannel(), null)));
        }
        rows.sort(Comparator.comparing(AgentDiffRow::sortMagnitude).reversed()
                .thenComparing(AgentDiffRow::segment)
                .thenComparing(AgentDiffRow::personaName));
        return rows;
    }

    private BankingPolicyKpiEvaluator.EvaluationInput buildKpiInput(
            String runId,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            Map<String, BankingPolicyScenarioAgentSnapshot> baselineAgents,
            Map<String, BankingPolicyScenarioAgentSnapshot> candidateAgents,
            List<AgentDiffRow> agentDiffs,
            BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence,
            BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata,
            ScenarioLabRunParameters parameters,
            PolicyType policyType) {
        List<BankingPolicyKpiEvaluator.CustomerOutcome> outcomes = agentDiffs.stream()
                .map(row -> new BankingPolicyKpiEvaluator.CustomerOutcome(
                        evidence.canonicalCustomerId(row.personaName()),
                        evidence.policyAgent(row.personaName()),
                        BigDecimal.valueOf(row.baselineProductBalance()),
                        BigDecimal.valueOf(row.candidateProductBalance()),
                        BigDecimal.valueOf(row.baselineCurrentBalance()),
                        BigDecimal.valueOf(row.candidateCurrentBalance()),
                        BigDecimal.valueOf(row.baselineCreditLimit()),
                        BigDecimal.valueOf(row.candidateCreditLimit()),
                        BigDecimal.valueOf(row.baselineAvailableCredit()),
                        BigDecimal.valueOf(row.candidateAvailableCredit()),
                        BigDecimal.valueOf(row.baselineCreditUtilisation()),
                        BigDecimal.valueOf(row.candidateCreditUtilisation())))
                .toList();
        return new BankingPolicyKpiEvaluator.EvaluationInput(
                new BankingPolicyKpiEvaluator.BranchIdentity(runId, baseline.suiteRunId()),
                new BankingPolicyKpiEvaluator.BranchIdentity(runId, candidate.suiteRunId()),
                new BankingPolicyKpiEvaluator.ProductFacts(
                        productMetadata.productCode(), productMetadata.balanceSheetSide()),
                switch (policyType) {
                    case RATE -> BankingPolicyKpiEvaluator.PolicyKind.RATE;
                    case LIMIT -> BankingPolicyKpiEvaluator.PolicyKind.LIMIT;
                    case HARDSHIP -> BankingPolicyKpiEvaluator.PolicyKind.HARDSHIP;
                    case FRAUD -> BankingPolicyKpiEvaluator.PolicyKind.FRAUD;
                },
                decimal(parameters.getBaselineRate()),
                decimal(parameters.getCandidateRate()),
                toKpiCustomers(baselineAgents.values(), parameters.getProductCode(), evidence),
                toKpiCustomers(candidateAgents.values(), parameters.getProductCode(), evidence),
                outcomes,
                evidence.actions(),
                evidence.effects());
    }

    private List<BankingPolicyKpiEvaluator.CustomerSnapshot> toKpiCustomers(
            Iterable<BankingPolicyScenarioAgentSnapshot> agents,
            String productCode,
            BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence) {
        List<BankingPolicyKpiEvaluator.CustomerSnapshot> snapshots = new ArrayList<>();
        for (BankingPolicyScenarioAgentSnapshot agent : agents) {
            snapshots.add(new BankingPolicyKpiEvaluator.CustomerSnapshot(
                    evidence.canonicalCustomerId(firstNonBlank(agent.agentId(), agent.personaName())),
                    agent.policyAgent(),
                    agent.currentBalance(),
                    agent.creditUtilisation(),
                    agent.hardshipFlag(),
                    agent.totalProductBalance(productCode),
                    agent.totalBalance()));
        }
        return List.copyOf(snapshots);
    }

    private AggregateMetrics toAggregateMetrics(BankingPolicyKpiEvaluator.BranchKpis metrics) {
        return new AggregateMetrics(
                metrics.identity().branchId(),
                metrics.activeCustomers(),
                metrics.impactedCustomers(),
                metrics.impactedCustomerRatio().doubleValue(),
                round(metrics.exposedBalance()),
                round(metrics.interestBearingBalance()),
                round(metrics.estimatedMonthlyInterest()),
                round(metrics.estimatedMonthlyBankMetric()),
                round(metrics.estimatedMonthlyBankPnl()),
                round(metrics.oneTimeCustomerLiquiditySupport()),
                round(metrics.oneTimeBankCost()),
                round(metrics.totalCreditLimit()),
                round(metrics.availableCredit()),
                round4(metrics.averageCreditUtilisation()).doubleValue(),
                round(metrics.totalHouseholdLiquidity()),
                metrics.lowLiquidityCustomers(),
                metrics.reliefRecipients(),
                metrics.subsidiesIssued(),
                round(metrics.policyFundBalance()),
                round(metrics.retainedFundedBalanceProxy()),
                round4(metrics.liquiditySupportRateProxy()).doubleValue(),
                metrics.hardshipCustomers(),
                metrics.highUtilisationCustomers(),
                "");
    }

    private DecisionComparisonSummary buildDecisionComparison(
            ScenarioLabRunParameters parameters,
            PolicyType policyType,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            AggregateMetrics baselineMetrics,
            AggregateMetrics candidateMetrics,
            AggregateMetrics deltaMetrics,
            EvaluationRunReport baselineReport,
            EvaluationRunReport candidateReport,
            RegressionDeltaReport comparisonReport,
            HeadlineExtras headlineExtras,
            ArtifactSummary artifactSummary) {
        BankingCustomerOutcomeDecisionCatalog.DecisionComparisonDefinition definition =
                decisionCatalog.resolve(parameters, policyType);
        Map<String, String> baselineEvidence = new LinkedHashMap<>();
        baselineEvidence.put("evaluationReport", artifactSummary.baselineReport());
        baselineEvidence.put("artifactsDir", artifactSummary.baselineArtifactsDir());
        baselineEvidence.put("timeline", artifactSummary.simulationTimelineJson());
        baselineEvidence.put("sideEffectLedger", artifactSummary.sideEffectLedgerJson());
        baselineEvidence.put("branchStateProjection", artifactSummary.branchStateProjectionJson());
        Map<String, String> proposedEvidence = new LinkedHashMap<>();
        proposedEvidence.put("evaluationReport", artifactSummary.candidateReport());
        proposedEvidence.put("artifactsDir", artifactSummary.candidateArtifactsDir());
        proposedEvidence.put("timeline", artifactSummary.simulationTimelineJson());
        proposedEvidence.put("sideEffectLedger", artifactSummary.sideEffectLedgerJson());
        proposedEvidence.put("branchStateProjection", artifactSummary.branchStateProjectionJson());
        Map<String, String> sharedEvidence = new LinkedHashMap<>();
        sharedEvidence.put("comparisonReport", artifactSummary.comparisonReport());
        sharedEvidence.put("impactedCohortsCsv", artifactSummary.impactedCohortsCsv());
        sharedEvidence.put("impactedSegmentsJson", artifactSummary.impactedSegmentsJson());
        sharedEvidence.put("checkpointsJson", artifactSummary.checkpointsJson());
        sharedEvidence.put("agentDiffsJson", artifactSummary.agentDiffsJson());
        sharedEvidence.put("notableActionsJson", artifactSummary.notableActionsJson());
        sharedEvidence.put("scheduledWorkFailuresJson", artifactSummary.scheduledWorkFailuresJson());
        sharedEvidence.put("simulationTimelineJson", artifactSummary.simulationTimelineJson());
        sharedEvidence.put("sideEffectLedgerJson", artifactSummary.sideEffectLedgerJson());
        sharedEvidence.put("branchStateProjectionJson", artifactSummary.branchStateProjectionJson());
        sharedEvidence.put("experimentCard", artifactSummary.experimentCard());

        return new DecisionComparisonSummary(
                "customer_outcome_decision_comparison.v1",
                definition.comparisonId(),
                definition.scenarioId(),
                definition.title(),
                definition.decisionQuestion(),
                buildDecisionBranch(
                        definition.baseline(),
                        baseline.suiteRunId(),
                        parameters,
                        baselineEvidence,
                        false),
                buildDecisionBranch(
                        definition.proposed(),
                        candidate.suiteRunId(),
                        parameters,
                        proposedEvidence,
                        true),
                List.of(
                        metricDelta(
                                "overallScore",
                                "Evaluator Overall Score",
                                overallScore(baselineReport),
                                overallScore(candidateReport),
                                comparisonReport.getOverallDelta() == null
                                        ? 0.0
                                        : round4(comparisonReport.getOverallDelta()),
                                "score",
                                "comparisonReport"),
                        metricDelta(
                                "impactedAgents",
                                "Impacted Customers",
                                baselineMetrics.impactedAgents(),
                                candidateMetrics.impactedAgents(),
                                deltaMetrics.impactedAgents(),
                                "count",
                                "impactedCohortsCsv"),
                        metricDelta(
                                "exposedBalance",
                                "Exposed Balance",
                                baselineMetrics.exposedBalance(),
                                candidateMetrics.exposedBalance(),
                                deltaMetrics.exposedBalance(),
                                "AUD",
                                "impactedSegmentsJson"),
                        metricDelta(
                                "householdLiquidity",
                                "Household Liquidity",
                                baselineMetrics.totalHouseholdLiquidity(),
                                candidateMetrics.totalHouseholdLiquidity(),
                                deltaMetrics.totalHouseholdLiquidity(),
                                "AUD",
                                "branchStateProjectionJson"),
                        metricDelta(
                                "oneTimeCustomerLiquiditySupport",
                                "One-Time Customer Liquidity Support",
                                baselineMetrics.oneTimeCustomerLiquiditySupport(),
                                candidateMetrics.oneTimeCustomerLiquiditySupport(),
                                deltaMetrics.oneTimeCustomerLiquiditySupport(),
                                "AUD",
                                "sideEffectLedgerJson"),
                        metricDelta(
                                "oneTimeBankCost",
                                "One-Time Bank Cost",
                                baselineMetrics.oneTimeBankCost(),
                                candidateMetrics.oneTimeBankCost(),
                                deltaMetrics.oneTimeBankCost(),
                                "AUD",
                                "sideEffectLedgerJson"),
                        metricDelta(
                                "lowLiquidityAgents",
                                "Low-Liquidity Customers",
                                baselineMetrics.lowLiquidityAgents(),
                                candidateMetrics.lowLiquidityAgents(),
                                deltaMetrics.lowLiquidityAgents(),
                                "count",
                                "agentDiffsJson"),
                        metricDelta(
                                "reliefRecipients",
                                "Relief Recipients",
                                baselineMetrics.reliefRecipients(),
                                candidateMetrics.reliefRecipients(),
                                deltaMetrics.reliefRecipients(),
                                "count",
                                "branchStateProjectionJson"),
                        metricDelta(
                                "subsidiesIssued",
                                "Subsidies Issued",
                                baselineMetrics.subsidiesIssued(),
                                candidateMetrics.subsidiesIssued(),
                                deltaMetrics.subsidiesIssued(),
                                "count",
                                "branchStateProjectionJson"),
                        metricDelta(
                                "residualHardshipAgents",
                                "Residual Hardship Customers",
                                0,
                                headlineExtras.residualHardshipAgents(),
                                headlineExtras.residualHardshipAgents(),
                                "count",
                                "sideEffectLedgerJson")),
                new DecisionReplayEvidenceSummary(
                        baselineEvidence,
                        proposedEvidence,
                        sharedEvidence,
                        definition.requiredEvidence()));
    }

    private DecisionBranchSummary buildDecisionBranch(
            BankingCustomerOutcomeDecisionCatalog.DecisionBranchDefinition definition,
            String runId,
            ScenarioLabRunParameters parameters,
            Map<String, String> evidenceLinks,
            boolean proposed) {
        return new DecisionBranchSummary(
                definition.branchId(),
                definition.branchRole(),
                runId,
                definition.presetId(),
                definition.label(),
                definition.description(),
                branchParameters(definition.parameters(), parameters, proposed),
                evidenceLinks);
    }

    private Map<String, Object> branchParameters(
            Map<String, Object> configured,
            ScenarioLabRunParameters parameters,
            boolean proposed) {
        Map<String, Object> values = new LinkedHashMap<>(configured);
        values.put("executingPresetId", parameters.getPresetId());
        values.put("productCode", parameters.getProductCode());
        values.put("compareMode", parameters.getCompareMode());
        values.put("rate", proposed ? parameters.getCandidateRate() : parameters.getBaselineRate());
        values.put("limit", proposed ? parameters.getCandidateLimit() : parameters.getBaselineLimit());
        if (proposed) {
            values.put("candidateStimulusAmount", parameters.getCandidateStimulusAmount());
            values.put("candidateCrash", parameters.getCandidateCrash());
            values.put("candidateHardshipScan", parameters.getCandidateHardshipScan());
            values.put("candidateHardshipThreshold", parameters.getCandidateHardshipThreshold());
            values.put("candidateHardshipSubsidyAmount", parameters.getCandidateHardshipSubsidyAmount());
            values.put("candidateFraudAlert", parameters.getCandidateFraudAlert());
            values.put("candidateFraudSeverity", parameters.getCandidateFraudSeverity());
            values.put("candidateFraudChannel", parameters.getCandidateFraudChannel());
        }
        return values;
    }

    private DecisionMetricDeltaSummary metricDelta(
            String metricKey,
            String label,
            double baselineValue,
            double proposedValue,
            double deltaValue,
            String unit,
            String evidenceRef) {
        return new DecisionMetricDeltaSummary(
                metricKey,
                label,
                round4(baselineValue),
                round4(proposedValue),
                round4(deltaValue),
                unit,
                evidenceRef);
    }

    private SideEffectLedgerSummary buildSideEffectLedger(
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            List<MergedCheckpoint> checkpoints,
            List<ScheduledWorkFailure> scheduledWorkFailures,
            DecisionGatesSummary decisionGates,
            DecisionComparisonSummary decisionComparison) {
        List<SideEffectEntrySummary> entries = new ArrayList<>();
        addScenarioEventSideEffects(entries, baseline, decisionComparison.baseline());
        addScenarioEventSideEffects(entries, candidate, decisionComparison.proposed());
        for (MergedCheckpoint checkpoint : checkpoints) {
            entries.add(new SideEffectEntrySummary(
                    "state-checkpoint-" + normalizedId(checkpoint.label()),
                    "shared",
                    "shared",
                    "STATE_CHANGE",
                    "simulation-clock",
                    "capture-checkpoint:" + checkpoint.label(),
                    "CAPTURED",
                    checkpoint.elapsedSeconds(),
                    "checkpointsJson",
                    linkedMap("checkpointLabel", checkpoint.label()),
                    linkedMap(
                            "currentTime", checkpoint.currentTime(),
                            "baseline", checkpoint.baseline(),
                            "proposed", checkpoint.candidate(),
                            "delta", checkpoint.delta())));
        }
        if (decisionGates != null && decisionGates.gates() != null) {
            for (DecisionGateSummary gate : decisionGates.gates()) {
                String status = gate.fail() ? "FAIL" : gate.breached() ? "WARN" : "PASS";
                entries.add(new SideEffectEntrySummary(
                        "policy-check-" + normalizedId(gate.gateId()),
                        "shared",
                        "shared",
                        "POLICY_CHECK",
                        "evaluation-engine",
                        gate.metricKey(),
                        status,
                        terminalElapsedSeconds(checkpoints),
                        "comparisonReport",
                        linkedMap(
                                "gateId", gate.gateId(),
                                "metricKey", gate.metricKey(),
                                "observedValue", gate.observedValue(),
                                "reason", gate.reason()),
                        linkedMap(
                                "breached", gate.breached(),
                                "fail", gate.fail(),
                                "status", status,
                                "metricKey", gate.metricKey(),
                                "observedValue", gate.observedValue(),
                                "reason", gate.reason())));
            }
        }
        long terminalElapsedSeconds = terminalElapsedSeconds(checkpoints);
        for (int index = 0; index < scheduledWorkFailures.size(); index++) {
            ScheduledWorkFailure failure = scheduledWorkFailures.get(index);
            entries.add(new SideEffectEntrySummary(
                    "escalation-event-" + (index + 1),
                    branchId(decisionComparison.proposed(), "proposed-candidate-policy"),
                    branchRole(decisionComparison.proposed(), "proposed"),
                    "ESCALATION_EVENT",
                    failure.personaName(),
                    firstNonBlank(failure.failureKind(), "SCHEDULED_WORK_FAILURE"),
                    "ESCALATION_REQUIRED",
                    terminalElapsedSeconds,
                    "scheduledWorkFailuresJson",
                    linkedMap(
                            "personaName", failure.personaName(),
                            "segment", failure.segment(),
                            "paymentClass", failure.paymentClass()),
                    linkedMap(
                            "detail", failure.detail(),
                            "failureKind", failure.failureKind(),
                            "paymentClass", failure.paymentClass())));
        }
        entries.sort(Comparator
                .comparingLong(SideEffectEntrySummary::elapsedSeconds)
                .thenComparing(SideEffectEntrySummary::branchId)
                .thenComparing(SideEffectEntrySummary::effectId));
        return new SideEffectLedgerSummary("customer_outcome_side_effect_ledger.v1", List.copyOf(entries));
    }

    private void addScenarioEventSideEffects(
            List<SideEffectEntrySummary> entries,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult run,
            DecisionBranchSummary branch) {
        for (BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result : run.eventResults()) {
            entries.add(new SideEffectEntrySummary(
                    branchRole(branch, run.branchType()) + "-api-call-" + normalizedId(result.name()),
                    branchId(branch, run.branchType()),
                    branchRole(branch, run.branchType()),
                    inferSideEffectType(result),
                    result.service(),
                    result.method() + " " + result.path(),
                    result.ok() ? "APPLIED" : "FAILED",
                    Math.max(result.afterElapsedSeconds(), 0),
                    "sideEffectLedgerJson",
                    result.requestPayload(),
                    result.responsePayload()));
        }
    }

    private SimulationTimelineSummary buildSimulationTimeline(
            List<MergedCheckpoint> checkpoints,
            List<ScheduledWorkFailure> scheduledWorkFailures,
            SideEffectLedgerSummary sideEffectLedger,
            DecisionComparisonSummary decisionComparison) {
        List<TimelineEventSummary> events = new ArrayList<>();
        events.add(new TimelineEventSummary(
                "run-start-baseline",
                branchId(decisionComparison.baseline(), "baseline-current-policy"),
                branchRole(decisionComparison.baseline(), "baseline"),
                "RUN_START",
                firstNonBlank(decisionComparison.baseline().label(), "Baseline"),
                "Baseline branch initialized with current policy controls.",
                0,
                DEFAULT_SIMULATION_START_EPOCH_MS,
                "summary",
                linkedMap("runId", decisionComparison.baseline().runId())));
        events.add(new TimelineEventSummary(
                "run-start-proposed",
                branchId(decisionComparison.proposed(), "proposed-candidate-policy"),
                branchRole(decisionComparison.proposed(), "proposed"),
                "RUN_START",
                firstNonBlank(decisionComparison.proposed().label(), "Proposed"),
                "Proposed branch initialized with candidate policy controls.",
                0,
                DEFAULT_SIMULATION_START_EPOCH_MS,
                "summary",
                linkedMap("runId", decisionComparison.proposed().runId())));
        for (MergedCheckpoint checkpoint : checkpoints) {
            events.add(new TimelineEventSummary(
                    "state-checkpoint-" + normalizedId(checkpoint.label()),
                    "shared",
                    "shared",
                    "STATE_CHECKPOINT",
                    checkpoint.label(),
                    "Captured aggregate customer state for baseline and proposed branches.",
                    checkpoint.elapsedSeconds(),
                    checkpoint.currentTime(),
                    "checkpointsJson",
                    linkedMap(
                            "baseline", checkpoint.baseline(),
                            "proposed", checkpoint.candidate(),
                            "delta", checkpoint.delta())));
        }
        for (SideEffectEntrySummary entry : sideEffectLedger.entries()) {
            events.add(new TimelineEventSummary(
                    "effect-" + entry.effectId(),
                    entry.branchId(),
                    entry.branchRole(),
                    entry.effectType(),
                    humanLabel(entry.operation()),
                    entry.status() + " by " + entry.actor(),
                    entry.elapsedSeconds(),
                    DEFAULT_SIMULATION_START_EPOCH_MS + entry.elapsedSeconds() * 1000L,
                    entry.evidenceRef(),
                    linkedMap(
                            "effectId", entry.effectId(),
                            "operation", entry.operation(),
                            "status", entry.status(),
                            "request", entry.request(),
                            "response", entry.response())));
        }
        long terminalElapsedSeconds = terminalElapsedSeconds(checkpoints);
        for (int index = 0; index < scheduledWorkFailures.size(); index++) {
            ScheduledWorkFailure failure = scheduledWorkFailures.get(index);
            events.add(new TimelineEventSummary(
                    "replay-finding-" + (index + 1),
                    branchId(decisionComparison.proposed(), "proposed-candidate-policy"),
                    branchRole(decisionComparison.proposed(), "proposed"),
                    "REPLAY_FINDING",
                    failure.personaName(),
                    firstNonBlank(failure.detail(), "Replay-linked scheduled work failure."),
                    terminalElapsedSeconds,
                    DEFAULT_SIMULATION_START_EPOCH_MS + terminalElapsedSeconds * 1000L,
                    "scheduledWorkFailuresJson",
                    linkedMap(
                            "segment", failure.segment(),
                            "failureKind", failure.failureKind(),
                            "paymentClass", failure.paymentClass())));
        }
        events.sort(Comparator
                .comparingLong(TimelineEventSummary::elapsedSeconds)
                .thenComparing(TimelineEventSummary::branchId)
                .thenComparing(TimelineEventSummary::eventId));
        return new SimulationTimelineSummary("customer_outcome_simulation_timeline.v1", List.copyOf(events));
    }

    BranchStateProjectionSummary buildBranchStateProjection(
            String runId,
            ScenarioLabRunParameters parameters,
            PolicyType policyType,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            List<AgentDiffRow> agentDiffs,
            SideEffectLedgerSummary sideEffectLedger,
            DecisionComparisonSummary decisionComparison) {
        List<StateProjectionMutation> mutations = new ArrayList<>();
        String branchId = branchId(decisionComparison.proposed(), "proposed-candidate-policy");
        String branchRole = branchRole(decisionComparison.proposed(), "proposed");
        String sourceEffectId = sourceEffectIdForPolicy(policyType, sideEffectLedger);
        BigDecimal hardshipSubsidyAmount = hardshipSubsidyAmount(sideEffectLedger);
        for (AgentDiffRow row : agentDiffs) {
            StateProjectionMutation mutation = buildStateProjectionMutation(
                    mutations.size() + 1,
                    row,
                    parameters,
                    policyType,
                    branchId,
                    branchRole,
                    sourceEffectId,
                    hardshipSubsidyAmount);
            if (mutation != null) {
                mutations.add(mutation);
            }
        }

        List<StateProjectionMetricTrace> metricTraces = decisionComparison.metricDeltas().stream()
                .map(metric -> {
                    List<String> sourceMutationIds = sourceMutationsForMetric(metric.metricKey(), mutations);
                    return new StateProjectionMetricTrace(
                            metric.metricKey(),
                            metric.label(),
                            metric.baselineValue(),
                            metric.proposedValue(),
                            metric.deltaValue(),
                            metric.unit(),
                            "branchStateProjectionJson",
                            sourceMutationIds,
                            metricEvidenceChain(metric, sourceMutationIds, mutations, branchId, branchRole));
                })
                .toList();

        return new BranchStateProjectionSummary(
                "customer_outcome_branch_state_projection.v1",
                runId,
                parameters.getPresetId(),
                List.of(
                        buildBranchProjection(decisionComparison.baseline(), baseline),
                        buildBranchProjection(decisionComparison.proposed(), candidate)),
                List.copyOf(mutations),
                metricTraces);
    }

    private BranchProjectionSummary buildBranchProjection(
            DecisionBranchSummary branch,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult run) {
        int agentCount = 0;
        BigDecimal totalCurrentBalance = BigDecimal.ZERO;
        int hardshipAgents = 0;
        BigDecimal availableCredit = BigDecimal.ZERO;
        for (BankingPolicyScenarioAgentSnapshot agent : run.agentsAfter()) {
            if (agent.policyAgent()) {
                continue;
            }
            agentCount++;
            totalCurrentBalance = totalCurrentBalance.add(agent.currentBalance());
            if (agent.hardshipFlag()) {
                hardshipAgents++;
            }
            availableCredit = availableCredit.add(agent.availableCredit());
        }
        Map<String, Object> evidenceRefs = new LinkedHashMap<>();
        if (branch != null && branch.evidenceLinks() != null) {
            evidenceRefs.putAll(branch.evidenceLinks());
        }
        evidenceRefs.put("branchStateProjectionJson", "branchStateProjectionJson");
        return new BranchProjectionSummary(
                branchId(branch, run.branchType()),
                branchRole(branch, run.branchType()),
                run.suiteRunId(),
                agentCount,
                round(totalCurrentBalance),
                hardshipAgents,
                round(availableCredit),
                evidenceRefs);
    }

    private StateProjectionMutation buildStateProjectionMutation(
            int index,
            AgentDiffRow row,
            ScenarioLabRunParameters parameters,
            PolicyType policyType,
            String branchId,
            String branchRole,
            String sourceEffectId,
            BigDecimal hardshipSubsidyAmount) {
        BigDecimal baselineValue;
        BigDecimal projectedValue;
        BigDecimal deltaValue;
        String mutationType;
        Map<String, Object> rationale = new LinkedHashMap<>();
        switch (policyType) {
            case HARDSHIP -> {
                if (!row.subsidyReceived()) {
                    return null;
                }
                baselineValue = BigDecimal.valueOf(row.baselineCurrentBalance());
                deltaValue = hardshipSubsidyAmount;
                projectedValue = baselineValue.add(deltaValue);
                mutationType = "HARDSHIP_SUBSIDY_PROJECTION";
                rationale.put("stateBasis", "side-effect-response");
                rationale.put("subsidyReceived", true);
                rationale.put("observedFinalBalance", row.candidateCurrentBalance());
                rationale.put("hardshipFlag", row.hardshipFlag());
                rationale.put("lowLiquidityBefore", row.lowLiquidityBefore());
                rationale.put("lowLiquidityAfter", row.lowLiquidityAfter());
            }
            case RATE -> {
                if (Math.abs(row.deltaMonthlyInterest()) < 0.0001) {
                    return null;
                }
                baselineValue = monthlyInterest(
                        BigDecimal.valueOf(row.baselineProductBalance()),
                        decimal(parameters.getBaselineRate()));
                projectedValue = monthlyInterest(
                        BigDecimal.valueOf(row.candidateProductBalance()),
                        decimal(parameters.getCandidateRate()));
                deltaValue = projectedValue.subtract(baselineValue);
                mutationType = "RATE_POLICY_EXPOSURE";
                rationale.put("balanceBasis", "branchProductBalance");
                rationale.put("baselineProductBalance", row.baselineProductBalance());
                rationale.put("candidateProductBalance", row.candidateProductBalance());
                rationale.put("baselineRate", parameters.getBaselineRate());
                rationale.put("candidateRate", parameters.getCandidateRate());
                rationale.put("balanceSheetSide", row.balanceSheetSide());
            }
            case LIMIT -> {
                if (Math.abs(row.deltaCreditLimit()) < 0.0001 && Math.abs(row.deltaAvailableCredit()) < 0.0001) {
                    return null;
                }
                baselineValue = BigDecimal.valueOf(row.baselineCreditLimit());
                projectedValue = BigDecimal.valueOf(row.candidateCreditLimit());
                deltaValue = BigDecimal.valueOf(row.deltaCreditLimit());
                mutationType = "CREDIT_LIMIT_PROJECTION";
                rationale.put("baselineAvailableCredit", row.baselineAvailableCredit());
                rationale.put("projectedAvailableCredit", row.candidateAvailableCredit());
                rationale.put("deltaAvailableCredit", row.deltaAvailableCredit());
                rationale.put("baselineUtilisation", row.baselineCreditUtilisation());
                rationale.put("projectedUtilisation", row.candidateCreditUtilisation());
            }
            case FRAUD -> {
                if (!row.fraudAlerted()) {
                    return null;
                }
                baselineValue = BigDecimal.ZERO;
                projectedValue = BigDecimal.ONE;
                deltaValue = BigDecimal.ONE;
                mutationType = "FRAUD_ALERT_FRICTION";
                rationale.put("fraudSeverity", row.fraudSeverity());
                rationale.put("fraudChannel", row.fraudChannel());
                rationale.put("customerFrictionProxy", 1);
            }
            default -> {
                return null;
            }
        }
        return new StateProjectionMutation(
                "mutation-" + index + "-" + normalizedId(row.personaName()),
                branchId,
                branchRole,
                sourceEffectId,
                mutationType,
                row.personaName(),
                row.segment(),
                row.productCode(),
                round(baselineValue),
                round(projectedValue),
                round(deltaValue),
                "branchStateProjectionJson",
                rationale);
    }

    private String sourceEffectIdForPolicy(PolicyType policyType, SideEffectLedgerSummary sideEffectLedger) {
        String needle = switch (policyType) {
            case RATE -> "rate-change";
            case LIMIT -> "limit-change";
            case HARDSHIP -> "hardship-scan";
            case FRAUD -> "fraud-alert";
        };
        return sideEffectLedger.entries().stream()
                .filter(entry -> "proposed".equalsIgnoreCase(entry.branchRole()))
                .filter(entry -> entry.operation() != null && entry.operation().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .map(SideEffectEntrySummary::effectId)
                .findFirst()
                .orElse("proposed-policy-effect");
    }

    private BigDecimal hardshipSubsidyAmount(SideEffectLedgerSummary sideEffectLedger) {
        return sideEffectLedger.entries().stream()
                .filter(entry -> entry.response() != null && entry.response().containsKey("subsidyAmount"))
                .map(entry -> decimal(entry.response().get("subsidyAmount")))
                .filter(value -> value.compareTo(BigDecimal.ZERO) != 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private List<String> sourceMutationsForMetric(
            String metricKey,
            List<StateProjectionMutation> mutations) {
        String normalizedMetric = firstNonBlank(metricKey, "").toLowerCase(java.util.Locale.ROOT);
        return mutations.stream()
                .filter(mutation -> mutationSupportsMetric(normalizedMetric, mutation))
                .map(StateProjectionMutation::mutationId)
                .toList();
    }

    private Map<String, Object> metricEvidenceChain(
            DecisionMetricDeltaSummary metric,
            List<String> sourceMutationIds,
            List<StateProjectionMutation> mutations,
            String branchId,
            String branchRole) {
        Set<String> sourceMutationIdSet = new LinkedHashSet<>(sourceMutationIds);
        List<String> sourceEffectIds = mutations.stream()
                .filter(mutation -> sourceMutationIdSet.contains(mutation.mutationId()))
                .map(StateProjectionMutation::sourceEffectId)
                .filter(value -> !blank(value))
                .distinct()
                .toList();

        Map<String, Object> artifactRefs = new LinkedHashMap<>();
        artifactRefs.put("branchStateProjectionJson", "branchStateProjectionJson");
        artifactRefs.put("sideEffectLedgerJson", "sideEffectLedgerJson");
        artifactRefs.put("simulationTimelineJson", "simulationTimelineJson");
        artifactRefs.put("comparisonReport", "comparisonReport");
        if (!blank(metric.evidenceRef())) {
            artifactRefs.put("metricEvidenceRef", metric.evidenceRef());
        }

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("schemaVersion", "customer_outcome_metric_evidence_chain.v1");
        chain.put("metricKey", metric.metricKey());
        chain.put("branchId", branchId);
        chain.put("branchRole", branchRole);
        chain.put("supportLevel", sourceMutationIds.isEmpty() ? "BRANCH_AGGREGATE_ONLY" : "MUTATION_LINKED");
        chain.put("mutationCount", sourceMutationIds.size());
        chain.put("sourceMutationIds", sourceMutationIds);
        chain.put("sourceEffectIds", sourceEffectIds);
        chain.put("artifactRefs", artifactRefs);
        chain.put("assumption",
                "Stress-test projection from deterministic branch state and declared policy side effects; not a real-world forecast.");
        return chain;
    }

    private boolean mutationSupportsMetric(String normalizedMetric, StateProjectionMutation mutation) {
        if (normalizedMetric.isBlank() || "overallscore".equals(normalizedMetric)) {
            return true;
        }
        String mutationType = firstNonBlank(mutation.mutationType(), "").toLowerCase(java.util.Locale.ROOT);
        if (normalizedMetric.contains("onetimecustomerliquiditysupport")
                || normalizedMetric.contains("onetimebankcost")) {
            return mutationType.contains("hardship") || mutationType.contains("stimulus");
        }
        if (normalizedMetric.contains("liquidity")
                || normalizedMetric.contains("relief")
                || normalizedMetric.contains("subsid")) {
            return mutationType.contains("hardship");
        }
        if (normalizedMetric.contains("credit")
                || normalizedMetric.contains("limit")
                || normalizedMetric.contains("utilisation")) {
            return mutationType.contains("limit");
        }
        if (normalizedMetric.contains("balance")
                || normalizedMetric.contains("interest")
                || normalizedMetric.contains("pnl")) {
            return mutationType.contains("rate") || mutationType.contains("hardship");
        }
        if (normalizedMetric.contains("fraud") || normalizedMetric.contains("friction")) {
            return mutationType.contains("fraud");
        }
        return true;
    }

    private String inferSideEffectType(BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        String normalized = (result.name() + " " + result.path()).toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("hardship") || normalized.contains("fraud")) {
            return "POLICY_CHECK";
        }
        if (normalized.contains("rate-change") || normalized.contains("limit-change")
                || normalized.contains("stimulus") || normalized.contains("crash")) {
            return "API_CALL";
        }
        return "TOOL_CALL";
    }

    private String branchId(DecisionBranchSummary branch, String fallback) {
        return branch == null || blank(branch.branchId()) ? fallback : branch.branchId();
    }

    private String branchRole(DecisionBranchSummary branch, String fallback) {
        return branch == null || blank(branch.branchRole()) ? fallback : branch.branchRole();
    }

    private long terminalElapsedSeconds(List<MergedCheckpoint> checkpoints) {
        return checkpoints.stream()
                .mapToLong(MergedCheckpoint::elapsedSeconds)
                .max()
                .orElse(0L);
    }

    private String humanLabel(String value) {
        String normalized = firstNonBlank(value, "event")
                .replace("POST ", "")
                .replace("GET ", "")
                .replace("/policy/", "policy ")
                .replace("/", " ")
                .replace("-", " ")
                .replace("_", " ")
                .trim();
        if (normalized.isBlank()) {
            return "Event";
        }
        return normalized.substring(0, 1).toUpperCase() + normalized.substring(1);
    }

    private String normalizedId(String value) {
        String normalized = firstNonBlank(value, "event")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "event" : normalized;
    }

    private double overallScore(EvaluationRunReport report) {
        return report != null && report.getSummary() != null && report.getSummary().getOverallScore() != null
                ? report.getSummary().getOverallScore()
                : 0.0;
    }

    EvaluationRunReport ownedEvaluationReport(EvaluationRunReport report, String expectedRunId) {
        if (report == null
                || report.getRun() == null
                || !expectedRunId.equals(report.getRun().getRunId())
                || report.getSummary() == null
                || report.getSummary().getOverallScore() == null
                || report.getSummary().getOverallVerdict() == null) {
            return null;
        }
        return report;
    }

    private List<ImpactedSegment> buildImpactedSegments(List<AgentDiffRow> agentDiffs) {
        Map<String, List<AgentDiffRow>> grouped = new LinkedHashMap<>();
        for (AgentDiffRow row : agentDiffs) {
            grouped.computeIfAbsent(row.segment(), ignored -> new ArrayList<>()).add(row);
        }
        List<ImpactedSegment> segments = new ArrayList<>();
        for (Map.Entry<String, List<AgentDiffRow>> entry : grouped.entrySet()) {
            List<AgentDiffRow> rows = entry.getValue();
            BigDecimal totalLimit = rows.stream().map(AgentDiffRow::candidateCreditLimit).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal weightedUtil = rows.stream()
                    .map(row -> BigDecimal.valueOf(row.candidateCreditLimit()).multiply(BigDecimal.valueOf(row.candidateCreditUtilisation())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double averageUtil = totalLimit.compareTo(BigDecimal.ZERO) == 0
                    ? 0.0
                    : round4(weightedUtil.divide(totalLimit, 6, RoundingMode.HALF_UP)).doubleValue();
            segments.add(new ImpactedSegment(
                    entry.getKey(),
                    rows.size(),
                    round(rows.stream().map(AgentDiffRow::productBalance).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(rows.stream().map(AgentDiffRow::interestBearingBalance).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(totalLimit),
                    round(rows.stream().map(AgentDiffRow::candidateAvailableCredit).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(rows.stream().map(AgentDiffRow::deltaAvailableCredit).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    averageUtil,
                    round(rows.stream().map(AgentDiffRow::currentBalance).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(rows.stream().map(AgentDiffRow::deltaCurrentBalance).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    rows.stream().filter(AgentDiffRow::lowLiquidityAfter).count(),
                    rows.stream().filter(AgentDiffRow::subsidyReceived).count(),
                    rows.stream().filter(AgentDiffRow::subsidyReceived).count(),
                    round(rows.stream().map(AgentDiffRow::deltaMonthlyInterest).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(rows.stream().map(AgentDiffRow::deltaMonthlyBankMetric).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    round(rows.stream().map(AgentDiffRow::deltaMonthlyBankPnl).map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add))));
        }
        segments.sort(Comparator.comparingDouble(ImpactedSegment::sortMagnitude).reversed());
        return segments;
    }

    private List<MergedCheckpoint> mergeCheckpoints(
            List<BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot> baseline,
            List<BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot> candidate) {
        int max = Math.max(baseline.size(), candidate.size());
        List<MergedCheckpoint> merged = new ArrayList<>();
        for (int index = 0; index < max; index++) {
            BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot baselineCheckpoint = index < baseline.size() ? baseline.get(index) : null;
            BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot candidateCheckpoint = index < candidate.size() ? candidate.get(index) : null;
            AggregateSample baselineMetrics = aggregateCheckpoint(baselineCheckpoint);
            AggregateSample candidateMetrics = aggregateCheckpoint(candidateCheckpoint);
            merged.add(new MergedCheckpoint(
                    baselineCheckpoint != null ? baselineCheckpoint.label() : candidateCheckpoint.label(),
                    baselineCheckpoint != null ? baselineCheckpoint.elapsedSeconds() : candidateCheckpoint.elapsedSeconds(),
                    baselineCheckpoint != null ? baselineCheckpoint.currentTimeEpochMs() : candidateCheckpoint.currentTimeEpochMs(),
                    baselineMetrics,
                    candidateMetrics,
                    baselineMetrics.delta(candidateMetrics)));
        }
        return merged;
    }

    private AggregateSample aggregateCheckpoint(BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot checkpoint) {
        if (checkpoint == null) {
            return new AggregateSample(BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }
        BigDecimal totalCurrentBalance = checkpoint.agents().stream()
                .map(BankingPolicyScenarioAgentSnapshot::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int hardshipAgents = (int) checkpoint.agents().stream()
                .filter(BankingPolicyScenarioAgentSnapshot::hardshipFlag)
                .count();
        BigDecimal availableCredit = checkpoint.agents().stream()
                .flatMap(agent -> agent.accounts().stream())
                .map(BankingPolicyScenarioAgentSnapshot.BankingPolicyScenarioAccountSnapshot::availableCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AggregateSample(roundAmount(totalCurrentBalance), hardshipAgents, roundAmount(availableCredit));
    }

    private List<NotableAction> buildNotableActions(List<AgentDiffRow> rows, PolicyType policyType) {
        List<NotableAction> actions = new ArrayList<>();
        for (AgentDiffRow row : rows.subList(0, Math.min(10, rows.size()))) {
            String category = switch (policyType) {
                case RATE -> "INTEREST_DELTA";
                case LIMIT -> "CREDIT_HEADROOM";
                case HARDSHIP -> "RELIEF_DELTA";
                case FRAUD -> "FRAUD_ALERT";
            };
            String description = switch (policyType) {
                case RATE -> "Monthly interest shifted by " + row.deltaMonthlyInterest();
                case LIMIT -> "Available credit shifted by " + row.deltaAvailableCredit();
                case HARDSHIP -> row.subsidyReceived() ? "Received hardship support." : "Liquidity shifted by " + row.deltaCurrentBalance();
                case FRAUD -> "Fraud-alert evidence is aggregate-only; customer attribution is unavailable.";
            };
            actions.add(new NotableAction(row.personaName(), row.segment(), category, description));
        }
        return actions;
    }

    private List<ScheduledWorkFailure> buildScheduledWorkFailures(List<ScheduledFailureEvent> events) {
        return events.stream()
                .map(event -> new ScheduledWorkFailure(
                        event.personaName(),
                        event.segment(),
                        event.detail(),
                        event.failureKind(),
                        event.paymentClass()))
                .toList();
    }

    private HardshipImpact summarizeHardshipImpact(
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> candidateEventResults) {
        Set<String> reliefRecipients = new LinkedHashSet<>();
        int subsidiesIssued = 0;
        int hardshipDetections = 0;
        BigDecimal totalLiquidityDelta = BigDecimal.ZERO;
        BigDecimal hardshipThreshold = BigDecimal.ZERO;
        BigDecimal subsidyAmount = BigDecimal.ZERO;
        for (BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result : candidateEventResults) {
            if (!result.ok() || !"/policy/hardship-scan".equals(result.path())) {
                continue;
            }
            Map<String, Object> payload = objectMap(result.responsePayload());
            int issued = intValue(payload.get("subsidiesIssued"), 0);
            subsidiesIssued += issued;
            hardshipDetections += intValue(payload.get("hardshipDetections"), 0);
            reliefRecipients.addAll(stringList(payload.get("reliefRecipients")));
            subsidyAmount = decimal(payload.get("subsidyAmount"));
            hardshipThreshold = decimal(payload.get("hardshipThreshold"));
            totalLiquidityDelta = totalLiquidityDelta.add(subsidyAmount
                    .multiply(BigDecimal.valueOf(issued)));
        }
        return new HardshipImpact(
                totalLiquidityDelta,
                reliefRecipients.size(),
                subsidiesIssued,
                hardshipDetections,
                hardshipThreshold,
                subsidyAmount);
    }

    private record HardshipImpact(
            BigDecimal totalLiquidityDelta,
            int reliefRecipients,
            int subsidiesIssued,
            int hardshipDetections,
            BigDecimal hardshipThreshold,
            BigDecimal subsidyAmount) {
    }

    private PolicyToggleSummary buildPolicyToggle(BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata, ScenarioLabRunParameters parameters, PolicyType policyType) {
        BigDecimal baselineRate = decimal(parameters.getBaselineRate());
        BigDecimal candidateRate = decimal(parameters.getCandidateRate());
        BigDecimal baselineLimit = decimal(parameters.getBaselineLimit());
        BigDecimal candidateLimit = decimal(parameters.getCandidateLimit());
        return new PolicyToggleSummary(
                productMetadata.productCode(),
                productMetadata.productName(),
                productMetadata.productCategory(),
                policyType.name(),
                round4(baselineRate).doubleValue(),
                round4(candidateRate).doubleValue(),
                round(candidateRate.subtract(baselineRate)),
                blank(parameters.getBaselineLimit()) ? null : round(baselineLimit),
                blank(parameters.getCandidateLimit()) ? null : round(candidateLimit),
                blank(parameters.getCandidateLimit()) || blank(parameters.getBaselineLimit())
                        ? null
                        : round(candidateLimit.subtract(baselineLimit)));
    }

    private EconomicsSummary buildEconomics(BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata) {
        boolean liability = "LIABILITY".equals(productMetadata.balanceSheetSide());
        return new EconomicsSummary(
                productMetadata.productCode(),
                productMetadata.productName(),
                productMetadata.productCategory(),
                productMetadata.balanceSheetSide(),
                liability ? "Customer Interest Earned" : "Customer Borrowing Cost",
                liability ? "Customer Interest Delta" : "Customer Borrowing Cost Delta",
                liability
                        ? "Higher rates increase customer interest earned on funded balances."
                        : "Higher rates increase customer borrowing cost on utilised revolving balances.",
                liability ? "Bank Funding Cost" : "Bank Interest Income",
                liability
                        ? "Higher rates increase the bank's interest expense on the funded balance."
                        : "Higher rates increase the bank's interest income on utilised balances.",
                "Bank P&L Delta",
                "Positive improves bank P&L. Negative means the policy costs the bank more.",
                liability ? "Funded Balance" : "Utilised Balance",
                "The policy-exposed balance affected by the comparison.",
                "banking-domain-product-catalog");
    }

    private ExposureSummary buildExposureSummary(int cohortAgents, int eligibleAgents, BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata, PolicyType policyType) {
        return new ExposureSummary(
                "scenario_lab_exposure_v1",
                policyType == PolicyType.HARDSHIP ? "Hardship-Scope Households" : "Eligible Product Holders",
                policyType == PolicyType.HARDSHIP
                        ? "Only households already in hardship scope, flagged for hardship, or receiving relief are counted here."
                        : "Only customers holding `" + productMetadata.productCode() + "` are eligible for this policy comparison.",
                cohortAgents,
                eligibleAgents,
                Math.max(cohortAgents - eligibleAgents, 0),
                cohortAgents == 0 ? 0.0 : round4(BigDecimal.valueOf((double) eligibleAgents / cohortAgents)).doubleValue(),
                eligibleAgents,
                eligibleAgents == 0 ? 0.0 : 1.0,
                cohortAgents == 0 ? 0.0 : round4(BigDecimal.valueOf((double) eligibleAgents / cohortAgents)).doubleValue());
    }

    private int customerAgentCount(Iterable<BankingPolicyScenarioAgentSnapshot> agents) {
        int count = 0;
        for (BankingPolicyScenarioAgentSnapshot agent : agents) {
            if (!agent.policyAgent()) {
                count++;
            }
        }
        return count;
    }

    private List<ScheduledFailureEvent> scheduledFailureEvents(List<BankingPolicyScenarioAgentSnapshot> agents) {
        List<ScheduledFailureEvent> events = new ArrayList<>();
        for (BankingPolicyScenarioAgentSnapshot agent : agents) {
            List<Map<String, Object>> structuredFailures = listOfMaps(agent.rawPayload().get("scheduledWorkFailures"));
            if (!structuredFailures.isEmpty()) {
                for (Map<String, Object> failure : structuredFailures) {
                    events.add(new ScheduledFailureEvent(
                            agent.personaName(),
                            agent.segment(),
                            firstNonBlank(
                                    stringValue(failure.get("message")),
                                    stringValue(failure.get("detail")),
                                    stringValue(failure.get("workName")),
                                    "Scheduled work failure"),
                            firstNonBlank(
                                    stringValue(failure.get("failureKind")),
                                    inferFailureKind(stringValue(failure.get("message")))),
                            firstNonBlank(
                                    stringValue(failure.get("paymentClass")),
                                    inferPaymentClass(stringValue(failure.get("workName"))))));
                }
                continue;
            }
            for (String failure : agent.scheduledWorkFailures()) {
                events.add(new ScheduledFailureEvent(
                        agent.personaName(),
                        agent.segment(),
                        failure,
                        inferFailureKind(failure),
                        inferPaymentClass(failure)));
            }
        }
        return events;
    }

    private PaymentStressSummary buildPaymentStressSummary(
            List<ScheduledFailureEvent> baselineEvents,
            List<ScheduledFailureEvent> candidateEvents) {
        return new PaymentStressSummary(
                "scenario_lab_payment_stress_v1",
                candidateEvents.size(),
                summarizePaymentStressRun(baselineEvents),
                summarizePaymentStressRun(candidateEvents),
                candidateEvents.stream()
                        .collect(java.util.stream.Collectors.groupingBy(ScheduledFailureEvent::segment, LinkedHashMap::new, java.util.stream.Collectors.toList()))
                        .entrySet().stream()
                        .map(entry -> new StressSegmentSummary(
                                entry.getKey(),
                                entry.getValue().size(),
                                (int) entry.getValue().stream().filter(event -> "INSUFFICIENT_FUNDS".equals(event.failureKind())).count()))
                        .sorted(Comparator.comparingInt(StressSegmentSummary::insufficientFundsFailureCount).reversed()
                                .thenComparingInt(StressSegmentSummary::totalFailureCount).reversed()
                                .thenComparing(StressSegmentSummary::segment))
                        .limit(3)
                        .toList());
    }

    private PaymentStressRunSummary summarizePaymentStressRun(List<ScheduledFailureEvent> events) {
        Map<String, Long> failuresByPersona = events.stream()
                .collect(java.util.stream.Collectors.groupingBy(ScheduledFailureEvent::personaName, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        return new PaymentStressRunSummary(
                events.size(),
                (int) events.stream().filter(event -> "INSUFFICIENT_FUNDS".equals(event.failureKind())).count(),
                (int) failuresByPersona.values().stream().filter(count -> count >= 2).count(),
                (int) events.stream()
                        .filter(event -> "SUPPRESSED".equals(event.failureKind()) && "DISCRETIONARY".equals(event.paymentClass()))
                        .count(),
                (int) events.stream().filter(event -> "ESSENTIAL".equals(event.paymentClass())).count(),
                (int) events.stream().filter(event -> "OBLIGATION".equals(event.paymentClass())).count(),
                failuresByPersona.size());
    }

    private TradeoffSummary buildTradeoffSummary(
            PolicyType policyType,
            BankingPolicyScenarioExecutionRuntime.ProductMetadata productMetadata) {
        return switch (policyType) {
            case RATE -> new TradeoffSummary(
                    "LIABILITY".equals(productMetadata.balanceSheetSide())
                            ? "customer-yield-vs-bank-pnl"
                            : "customer-rate-vs-bank-pnl",
                    new TradeoffMetricSummary("estimatedMonthlyCustomerInterest", "Estimated Monthly Customer Interest"),
                    new TradeoffMetricSummary("estimatedMonthlyBankPnl", "Estimated Monthly Bank P&L"),
                    "BENEFIT_UP_COUNTER_WORSE");
            case LIMIT -> new TradeoffSummary(
                    "customer-headroom-vs-utilisation-pressure",
                    new TradeoffMetricSummary("availableCredit", "Available Credit"),
                    new TradeoffMetricSummary("averageCreditUtilisation", "Average Credit Utilisation"),
                    "BENEFIT_UP_COUNTER_WORSE");
            case HARDSHIP -> new TradeoffSummary(
                    "household-liquidity-vs-relief-budget",
                    new TradeoffMetricSummary("householdLiquidity", "Household Liquidity"),
                    new TradeoffMetricSummary("policyFundBalance", "Policy Fund Balance"),
                    "BENEFIT_UP_COUNTER_WORSE");
            case FRAUD -> new TradeoffSummary(
                    "alert-coverage-vs-customer-friction",
                    new TradeoffMetricSummary("fraudAlertedAgents", "Fraud Alerted Agents"),
                    new TradeoffMetricSummary("customerFrictionProxy", "Customer Friction Proxy"),
                    "BENEFIT_UP_COUNTER_WORSE");
        };
    }

    private PressureMetricsSummary buildPressureMetrics(
            PolicyType policyType,
            AggregateMetrics baselineMetrics,
            AggregateMetrics candidateMetrics,
            AggregateMetrics deltaMetrics,
            HeadlineExtras headlineExtras) {
        return switch (policyType) {
            case RATE -> new PressureMetricsSummary(
                    "impactedAgentPct",
                    List.of(new PressureMetricSummary(
                            "impactedAgentPct",
                            round4(baselineMetrics.impactedAgentPct()),
                            round4(candidateMetrics.impactedAgentPct()),
                            round4(deltaMetrics.impactedAgentPct()))));
            case LIMIT -> new PressureMetricsSummary(
                    "averageCreditUtilisation",
                    List.of(new PressureMetricSummary(
                            "averageCreditUtilisation",
                            round4(baselineMetrics.averageCreditUtilisation()),
                            round4(candidateMetrics.averageCreditUtilisation()),
                            round4(deltaMetrics.averageCreditUtilisation()))));
            case HARDSHIP -> new PressureMetricsSummary(
                    "hardshipBudgetBurn",
                    List.of(new PressureMetricSummary(
                            "hardshipBudgetBurn",
                            0.0,
                            round(headlineExtras.hardshipBudgetBurn()),
                            round(headlineExtras.hardshipBudgetBurn()))));
            case FRAUD -> new PressureMetricsSummary(
                    "operatorReviewHoursProxy",
                    List.of(new PressureMetricSummary(
                            "operatorReviewHoursProxy",
                            0.0,
                            round(headlineExtras.operatorReviewHoursProxy()),
                            round(headlineExtras.operatorReviewHoursProxy()))));
        };
    }

    private SegmentImpactSummary buildSegmentImpactSummary(
            List<AgentDiffRow> agentDiffs,
            PolicyType policyType,
            HardshipImpact hardshipImpact,
            ScenarioLabRunParameters parameters) {
        if (agentDiffs.isEmpty()) {
            return new SegmentImpactSummary(0, List.of(), List.of(), List.of());
        }
        Map<String, List<AgentDiffRow>> grouped = new LinkedHashMap<>();
        for (AgentDiffRow row : agentDiffs) {
            grouped.computeIfAbsent(row.segment(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, Long> impactedCounts = new LinkedHashMap<>();
        Map<String, Double> winners = new LinkedHashMap<>();
        Map<String, Double> losers = new LinkedHashMap<>();
        Map<String, Double> counterPressure = new LinkedHashMap<>();
        double hardshipThreshold = hardshipImpact.hardshipThreshold().compareTo(BigDecimal.ZERO) > 0
                ? hardshipImpact.hardshipThreshold().doubleValue()
                : LOW_LIQUIDITY_THRESHOLD;
        double fraudSeverityWeight = fraudSeverityWeight(parameters.getCandidateFraudSeverity());
        for (Map.Entry<String, List<AgentDiffRow>> entry : grouped.entrySet()) {
            String segment = entry.getKey();
            List<AgentDiffRow> rows = entry.getValue();
            impactedCounts.put(segment, (long) rows.size());
            switch (policyType) {
                case RATE -> {
                    winners.put(segment, rows.stream().mapToDouble(AgentDiffRow::deltaMonthlyInterest).sum());
                    losers.put(segment, (double) rows.stream().filter(row -> row.lowLiquidityBefore() || row.lowLiquidityAfter()).count());
                    counterPressure.put(segment, rows.stream().mapToDouble(row -> Math.abs(row.deltaMonthlyBankPnl())).sum());
                }
                case LIMIT -> {
                    winners.put(segment, rows.stream().mapToDouble(AgentDiffRow::deltaAvailableCredit).sum());
                    losers.put(segment, rows.stream().mapToDouble(row -> Math.max(-row.candidateAvailableCredit(), 0.0)).sum());
                    counterPressure.put(segment, rows.stream().mapToDouble(AgentDiffRow::candidateCreditUtilisation).average().orElse(0.0));
                }
                case HARDSHIP -> {
                    winners.put(segment, rows.stream().mapToDouble(AgentDiffRow::deltaCurrentBalance).sum());
                    losers.put(segment, (double) rows.stream().filter(row -> row.candidateCurrentBalance() < hardshipThreshold).count());
                    counterPressure.put(segment, rows.stream().mapToDouble(AgentDiffRow::deltaMonthlyBankMetric).sum());
                }
                case FRAUD -> {
                    double alertedAgents = rows.stream().filter(AgentDiffRow::fraudAlerted).count();
                    winners.put(segment, alertedAgents);
                    losers.put(segment, alertedAgents * fraudSeverityWeight);
                    counterPressure.put(segment, alertedAgents * Math.max(fraudSeverityWeight / 2.0, 1.0));
                }
            }
        }
        return new SegmentImpactSummary(
                agentDiffs.size(),
                rankSegments(winners, impactedCounts),
                rankSegments(losers, impactedCounts),
                rankSegments(counterPressure, impactedCounts));
    }

    private List<SegmentLeaderboardEntry> rankSegments(Map<String, Double> metricValues, Map<String, Long> impactedCounts) {
        List<SegmentLeaderboardEntry> ranked = metricValues.entrySet().stream()
                .map(entry -> new SegmentLeaderboardEntry(
                        entry.getKey(),
                        impactedCounts.getOrDefault(entry.getKey(), 0L).intValue(),
                        round(entry.getValue())))
                .sorted(Comparator.comparingDouble(SegmentLeaderboardEntry::metricValue).reversed()
                        .thenComparingInt(SegmentLeaderboardEntry::impactedAgents).reversed()
                        .thenComparing(SegmentLeaderboardEntry::segment))
                .toList();
        List<SegmentLeaderboardEntry> positive = ranked.stream()
                .filter(entry -> entry.metricValue() > 0)
                .limit(3)
                .toList();
        if (!positive.isEmpty()) {
            return positive;
        }
        return ranked.stream().limit(3).toList();
    }

    private DecisionGatesSummary buildDecisionGates(
            PaymentStressSummary paymentStressSummary,
            List<RegressionThresholdBreach> thresholdBreaches,
            HeadlineExtras headlineExtras) {
        PaymentStressRunSummary candidate = paymentStressSummary.candidate();
        boolean essentialBreached = candidate.failedEssentialPayments() > 0;
        boolean obligationBreached = candidate.failedObligationPayments() > 0
                || headlineExtras.overLimitExposure().compareTo(BigDecimal.ZERO) > 0;
        boolean stressedBreached = candidate.affectedPersonaCount() > 0
                || headlineExtras.constrainedCustomers() > 0
                || headlineExtras.residualHardshipAgents() > 0;
        boolean repeatedBreached = candidate.repeatedStressPersonaCount() > 0;
        long failBreaches = thresholdBreaches.stream()
                .filter(entry -> entry.getSeverity() == RegressionThresholdBreach.SeverityEnum.FAIL)
                .count();
        if (failBreaches > 0 && !(essentialBreached || obligationBreached || stressedBreached || repeatedBreached)) {
            stressedBreached = true;
        }
        List<String> breachedIds = new ArrayList<>();
        if (essentialBreached) {
            breachedIds.add("payment-stress-essential-failures-cap");
        }
        if (obligationBreached) {
            breachedIds.add("payment-stress-obligation-failures-cap");
        }
        if (stressedBreached) {
            breachedIds.add("payment-stress-stressed-personas-cap");
        }
        if (repeatedBreached) {
            breachedIds.add("payment-stress-repeated-personas-cap");
        }
        int remainingFailBudget = (int) Math.min(failBreaches, breachedIds.size());
        List<DecisionGateSummary> gates = new ArrayList<>();
        for (String gateId : List.of(
                "payment-stress-essential-failures-cap",
                "payment-stress-obligation-failures-cap",
                "payment-stress-stressed-personas-cap",
                "payment-stress-repeated-personas-cap")) {
            boolean breached = breachedIds.contains(gateId);
            boolean fail = breached && remainingFailBudget > 0;
            if (fail) {
                remainingFailBudget--;
            }
            GateSignal signal = gateSignal(gateId, candidate, headlineExtras);
            gates.add(new DecisionGateSummary(
                    gateId,
                    signal.metricKey(),
                    round(signal.observedValue()),
                    signal.reason(),
                    breached,
                    fail));
        }
        return new DecisionGatesSummary(
                (int) gates.stream().filter(DecisionGateSummary::breached).count(),
                (int) gates.stream().filter(DecisionGateSummary::fail).count(),
                gates);
    }

    private GateSignal gateSignal(
            String gateId,
            PaymentStressRunSummary candidate,
            HeadlineExtras headlineExtras) {
        return switch (gateId) {
            case "payment-stress-essential-failures-cap" -> new GateSignal(
                    "failedEssentialPayments",
                    candidate.failedEssentialPayments(),
                    "essential payment failures");
            case "payment-stress-obligation-failures-cap" -> {
                if (candidate.failedObligationPayments() > 0) {
                    yield new GateSignal(
                            "failedObligationPayments",
                            candidate.failedObligationPayments(),
                            "obligation payment failures");
                }
                yield new GateSignal(
                        "overLimitExposure",
                        headlineExtras.overLimitExposure().doubleValue(),
                        "over-limit exposure");
            }
            case "payment-stress-stressed-personas-cap" -> {
                if (candidate.affectedPersonaCount() > 0) {
                    yield new GateSignal(
                            "affectedPersonaCount",
                            candidate.affectedPersonaCount(),
                            "payment-stressed personas");
                }
                if (headlineExtras.residualHardshipAgents() > 0) {
                    yield new GateSignal(
                            "residualHardshipAgents",
                            headlineExtras.residualHardshipAgents(),
                            "customers still below the hardship threshold after proposed relief");
                }
                yield new GateSignal(
                        "constrainedCustomers",
                        headlineExtras.constrainedCustomers(),
                        "credit-constrained customers");
            }
            default -> new GateSignal(
                    "repeatedStressPersonaCount",
                    candidate.repeatedStressPersonaCount(),
                    "repeated payment-stress personas");
        };
    }

    private HeadlineExtras buildHeadlineExtras(
            PolicyType policyType,
            List<AgentDiffRow> agentDiffs,
            BankingPolicyKpiEvaluator.BranchKpis candidateMetrics,
            PaymentStressSummary paymentStressSummary,
            HardshipImpact hardshipImpact,
            ScenarioLabRunParameters parameters) {
        int lowLiquidityImpactedAgents = (int) agentDiffs.stream()
                .filter(row -> row.lowLiquidityBefore() || row.lowLiquidityAfter())
                .count();
        int constrainedCustomers = (int) agentDiffs.stream()
                .filter(row -> row.candidateAvailableCredit() <= 0.0 || row.candidateCreditUtilisation() >= 1.0)
                .count();
        BigDecimal overLimitExposure = agentDiffs.stream()
                .map(row -> BigDecimal.valueOf(Math.max(-row.candidateAvailableCredit(), 0.0)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double hardshipThreshold = hardshipImpact.hardshipThreshold().compareTo(BigDecimal.ZERO) > 0
                ? hardshipImpact.hardshipThreshold().doubleValue()
                : LOW_LIQUIDITY_THRESHOLD;
        int residualHardshipAgents = policyType == PolicyType.HARDSHIP
                ? (int) agentDiffs.stream().filter(row -> row.candidateCurrentBalance() < hardshipThreshold).count()
                : 0;
        double hardshipTargetingPrecisionProxy = policyType == PolicyType.HARDSHIP
                ? hardshipImpact.reliefRecipients() / Math.max((double) hardshipImpact.hardshipDetections(), 1.0)
                : 0.0;
        int fraudAlertedAgents = candidateMetrics.fraudAlertedCustomers();
        int fraudAlertedAccounts = candidateMetrics.fraudAffectedAccounts();
        int fraudAlertWaveCount = candidateMetrics.fraudAlertWaves();
        double fraudSeverityWeight = fraudSeverityWeight(parameters.getCandidateFraudSeverity());
        double customerFrictionProxy = policyType == PolicyType.FRAUD ? fraudAlertedAgents * fraudSeverityWeight : 0.0;
        double operatorReviewHoursProxy = policyType == PolicyType.FRAUD
                ? fraudAlertedAccounts * Math.max(fraudSeverityWeight / 2.0, 1.0)
                : 0.0;
        return new HeadlineExtras(
                lowLiquidityImpactedAgents,
                constrainedCustomers,
                overLimitExposure,
                policyType == PolicyType.HARDSHIP ? candidateMetrics.oneTimeBankCost() : BigDecimal.ZERO,
                residualHardshipAgents,
                hardshipTargetingPrecisionProxy,
                fraudAlertedAgents,
                fraudAlertedAccounts,
                fraudAlertWaveCount,
                customerFrictionProxy,
                operatorReviewHoursProxy,
                policyType == PolicyType.FRAUD ? firstNonBlank(parameters.getCandidateFraudSeverity(), "") : "",
                policyType == PolicyType.FRAUD ? firstNonBlank(parameters.getCandidateFraudChannel(), "") : "");
    }

    private double fraudSeverityWeight(String severity) {
        return switch (firstNonBlank(severity, "HIGH").toUpperCase()) {
            case "LOW" -> 1.0;
            case "MEDIUM" -> 2.0;
            case "CRITICAL" -> 4.0;
            default -> 3.0;
        };
    }

    private String inferFailureKind(String detail) {
        String normalized = stringValue(detail).toUpperCase();
        if (normalized.contains("INSUFFICIENT")) {
            return "INSUFFICIENT_FUNDS";
        }
        if (normalized.contains("SUPPRESS")) {
            return "SUPPRESSED";
        }
        return "";
    }

    private String inferPaymentClass(String detail) {
        String normalized = stringValue(detail).toUpperCase();
        if (normalized.contains("ESSENTIAL")) {
            return "ESSENTIAL";
        }
        if (normalized.contains("OBLIGATION")) {
            return "OBLIGATION";
        }
        if (normalized.contains("DISCRETIONARY")) {
            return "DISCRETIONARY";
        }
        return "";
    }

    private T0Snapshot buildT0Snapshot(
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate) {
        AggregateSample baselineInitial = aggregateCheckpoint(new BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot("t0", 0, DEFAULT_SIMULATION_START_EPOCH_MS, baseline.agentsInitial()));
        AggregateSample candidateInitial = aggregateCheckpoint(new BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot("t0", 0, DEFAULT_SIMULATION_START_EPOCH_MS, candidate.agentsInitial()));
        AggregateSample baselinePostEvents = aggregateCheckpoint(new BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot("t0", 0, DEFAULT_SIMULATION_START_EPOCH_MS, baseline.agentsPostEvents()));
        AggregateSample candidatePostEvents = aggregateCheckpoint(new BankingPolicyScenarioExecutionRuntime.CheckpointSnapshot("t0", 0, DEFAULT_SIMULATION_START_EPOCH_MS, candidate.agentsPostEvents()));
        return new T0Snapshot(baselineInitial, candidateInitial, baselinePostEvents, candidatePostEvents);
    }

    private String headlineTopSegment(SegmentImpactSummary segmentImpactSummary, List<ImpactedSegment> impactedSegments) {
        if (!segmentImpactSummary.winners().isEmpty()) {
            return segmentImpactSummary.winners().getFirst().segment();
        }
        return impactedSegments.isEmpty() ? "unclassified" : impactedSegments.getFirst().segment();
    }

    private PolicyType resolvePolicyType(ScenarioLabRunParameters parameters) {
        if (booleanValue(parameters.getCandidateHardshipScan(), false)) {
            return PolicyType.HARDSHIP;
        }
        if (!blank(parameters.getBaselineLimit()) || !blank(parameters.getCandidateLimit())) {
            return PolicyType.LIMIT;
        }
        if (booleanValue(parameters.getCandidateFraudAlert(), false)) {
            return PolicyType.FRAUD;
        }
        return PolicyType.RATE;
    }

    private String demoIdForPolicyType(PolicyType policyType) {
        return switch (policyType) {
            case RATE -> "banking-scenario-lab-rate";
            case LIMIT -> "banking-scenario-lab-limit";
            case HARDSHIP -> "banking-scenario-lab-hardship";
            case FRAUD -> "banking-scenario-lab-fraud";
        };
    }

    private Map<String, BankingPolicyScenarioAgentSnapshot> indexAgents(
            List<BankingPolicyScenarioAgentSnapshot> agents,
            BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence) {
        Map<String, BankingPolicyScenarioAgentSnapshot> indexed = new LinkedHashMap<>();
        for (BankingPolicyScenarioAgentSnapshot agent : agents) {
            String identity = firstNonBlank(agent.agentId(), agent.runtimeIdentity(), agent.personaName());
            String customerId = evidence.canonicalCustomerId(identity);
            if (indexed.putIfAbsent(customerId, agent) != null) {
                throw new BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException(
                        "KPI evidence is incomplete: duplicate customer snapshot " + customerId);
            }
        }
        return indexed;
    }

    private record ScheduledFailureEvent(
            String personaName,
            String segment,
            String detail,
            String failureKind,
            String paymentClass) {
    }

    private record HeadlineExtras(
            int lowLiquidityImpactedAgents,
            int constrainedCustomers,
            BigDecimal overLimitExposure,
            BigDecimal hardshipBudgetBurn,
            int residualHardshipAgents,
            double hardshipTargetingPrecisionProxy,
            int fraudAlertedAgents,
            int fraudAlertedAccounts,
            int fraudAlertWaveCount,
            double customerFrictionProxy,
            double operatorReviewHoursProxy,
            String fraudSeverity,
            String fraudChannel) {
    }

    private record GateSignal(
            String metricKey,
            double observedValue,
            String reason) {
    }
}
