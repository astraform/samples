package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.EvaluationRunReport;
import ai.astraform.remote.domain.sdk.EvaluationSummary;
import ai.astraform.remote.domain.sdk.RegressionDeltaReport;
import ai.astraform.remote.domain.sdk.RegressionThresholdBreach;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

enum PolicyType {
    RATE,
    LIMIT,
    HARDSHIP,
    FRAUD
}

record BankingPolicyScenarioSummaryPayload(
        String demoId,
        DemoPresetSummary demoPreset,
        String kpiSchemaVersion,
        TradeoffSummary tradeoffSummary,
        PressureMetricsSummary pressureMetrics,
        SegmentImpactSummary segmentImpactSummary,
        String scenarioId,
        DecisionComparisonSummary decisionComparison,
        SimulationTimelineSummary simulationTimeline,
        SideEffectLedgerSummary sideEffectLedger,
        BranchStateProjectionSummary branchStateProjection,
        PolicyToggleSummary policyToggle,
        EconomicsSummary economics,
        CompareModeStrategySummary compareModeStrategy,
        RunConfigSummary runConfig,
        T0Snapshot t0Snapshot,
        ScenarioModifiersSummary scenarioModifiers,
        MetricSummarySection baseline,
        MetricSummarySection candidate,
        MetricDeltaSection delta,
        List<MergedCheckpoint> checkpoints,
        List<AgentDiffRow> agentDiffs,
        List<NotableAction> notableActions,
        List<ScheduledWorkFailure> scheduledWorkFailures,
        List<ImpactedSegment> impactedSegments,
        ExposureSummary exposureSummary,
        PaymentStressSummary paymentStressSummary,
        HeadlineSummary headline,
        EvaluatorSummary evaluator,
        ArtifactSummary artifacts) {
    BankingPolicyScenarioSummaryPayload withArtifacts(ArtifactSummary artifactSummary) {
        return new BankingPolicyScenarioSummaryPayload(
                demoId,
                demoPreset,
                kpiSchemaVersion,
                tradeoffSummary,
                pressureMetrics,
                segmentImpactSummary,
                scenarioId,
                decisionComparison,
                simulationTimeline,
                sideEffectLedger,
                branchStateProjection,
                policyToggle,
                economics,
                compareModeStrategy,
                runConfig,
                t0Snapshot,
                scenarioModifiers,
                baseline,
                candidate,
                delta,
                checkpoints,
                agentDiffs,
                notableActions,
                scheduledWorkFailures,
                impactedSegments,
                exposureSummary,
                paymentStressSummary,
                headline,
                evaluator,
                artifactSummary);
    }
}

record DemoPresetSummary(String presetId) {
}

record DecisionComparisonSummary(
        String schemaVersion,
        String comparisonId,
        String scenarioId,
        String title,
        String decisionQuestion,
        DecisionBranchSummary baseline,
        DecisionBranchSummary proposed,
        List<DecisionMetricDeltaSummary> metricDeltas,
        DecisionReplayEvidenceSummary replayEvidence) {
}

record DecisionBranchSummary(
        String branchId,
        String branchRole,
        String runId,
        String presetId,
        String label,
        String description,
        Map<String, Object> parameters,
        Map<String, String> evidenceLinks) {
}

record DecisionMetricDeltaSummary(
        String metricKey,
        String label,
        double baselineValue,
        double proposedValue,
        double deltaValue,
        String unit,
        String evidenceRef) {
}

record DecisionReplayEvidenceSummary(
        Map<String, String> baseline,
        Map<String, String> proposed,
        Map<String, String> shared,
        List<String> requiredEvidence) {
}

record SimulationTimelineSummary(
        String schemaVersion,
        List<TimelineEventSummary> events) {
}

record TimelineEventSummary(
        String eventId,
        String branchId,
        String branchRole,
        String eventType,
        String label,
        String description,
        long elapsedSeconds,
        long currentTime,
        String evidenceRef,
        Map<String, Object> payload) {
}

record SideEffectLedgerSummary(
        String schemaVersion,
        List<SideEffectEntrySummary> entries) {
}

record SideEffectEntrySummary(
        String effectId,
        String branchId,
        String branchRole,
        String effectType,
        String actor,
        String operation,
        String status,
        long elapsedSeconds,
        String evidenceRef,
        Map<String, Object> request,
        Map<String, Object> response) {
}

record BranchStateProjectionSummary(
        String schemaVersion,
        String runId,
        String presetId,
        List<BranchProjectionSummary> branches,
        List<StateProjectionMutation> mutations,
        List<StateProjectionMetricTrace> metricTraces) {
}

record BranchProjectionSummary(
        String branchId,
        String branchRole,
        String runId,
        int agentCount,
        double totalCurrentBalance,
        int hardshipAgents,
        double availableCredit,
        Map<String, Object> evidenceRefs) {
}

record StateProjectionMutation(
        String mutationId,
        String branchId,
        String branchRole,
        String sourceEffectId,
        String mutationType,
        String personaName,
        String segment,
        String productCode,
        double baselineValue,
        double projectedValue,
        double deltaValue,
        String evidenceRef,
        Map<String, Object> rationale) {
}

record StateProjectionMetricTrace(
        String metricKey,
        String label,
        double baselineValue,
        double proposedValue,
        double deltaValue,
        String unit,
        String mutationEvidenceRef,
        List<String> sourceMutationIds,
        Map<String, Object> evidenceChain) {
}

record AggregateSample(
        BigDecimal totalCurrentBalance,
        int hardshipAgents,
        BigDecimal availableCredit) {
    AggregateSample delta(AggregateSample candidate) {
        return new AggregateSample(
                candidate.totalCurrentBalance.subtract(totalCurrentBalance),
                candidate.hardshipAgents - hardshipAgents,
                candidate.availableCredit.subtract(availableCredit));
    }
}

record PolicyToggleSummary(
        String productCode,
        String productName,
        String productCategory,
        String policyType,
        double baselineRate,
        double candidateRate,
        double rateDelta,
        Double baselineLimit,
        Double candidateLimit,
        Double limitDelta) {
}

record EconomicsSummary(
        String productCode,
        String productName,
        String productCategory,
        String balanceSheetSide,
        String customerMetricLabel,
        String customerDeltaLabel,
        String customerMetricHint,
        String bankMetricLabel,
        String bankMetricHint,
        String bankPnlLabel,
        String bankPnlHint,
        String balanceMetricLabel,
        String balanceMetricHint,
        String catalogSource) {
}

record CompareModeStrategySummary(
        String compareMode,
        String strategyId,
        String personaNormalizationPolicy,
        String resultAggregationPolicy,
        String runExecutionShape,
        String exposure) {
}

record RunConfigSummary(
        String cohortSource,
        int cohortSize,
        int simulationHorizonDays,
        int prePolicyDays,
        int postPolicyDays,
        int policyEventOffsetDays,
        String runMode,
        String cadenceProfile,
        String compareMode,
        boolean includeAgentDiffs) {
}

record T0Snapshot(
        AggregateSample baselineInitial,
        AggregateSample candidateInitial,
        AggregateSample baselinePostPolicy,
        AggregateSample candidatePostPolicy) {
}

record ScenarioModifiersSummary(
        ScenarioModifierSummary baseline,
        ScenarioModifierSummary candidate) {
}

record ScenarioModifierSummary(
        int eventCount,
        List<String> eventNames,
        int policyEventOffsetSeconds,
        List<String> overlayPaths,
        PolicyLeverEventSummary rateChange,
        PolicyLeverEventSummary limitChange,
        int stimulusEventCount,
        double totalStimulusAmount,
        boolean hardshipScanTriggered,
        int hardshipScanEventCount,
        boolean fraudAlertTriggered,
        int fraudAlertEventCount,
        boolean crashTriggered,
        int crashEventCount,
        List<String> newsMessages,
        HardshipScanSummary hardshipScan) {
}

record PolicyLeverEventSummary(
        String message,
        String productCode,
        int affectedAgents) {
}

record HardshipScanSummary(
        int policyAgentsScanned,
        int analysedAgents,
        int hardshipDetections,
        int subsidiesIssued,
        Double hardshipThreshold,
        Double subsidyAmount,
        List<String> reliefRecipients,
        int skippedCooldownCount,
        int insufficientFundCount) {
}

record ExposureSummary(
        String schemaVersion,
        String scopeLabel,
        String scopeNarrative,
        int cohortAgents,
        int eligibleAgents,
        int nonEligibleAgents,
        double eligibleAgentPct,
        int impactedAgents,
        double impactedOfEligiblePct,
        double impactedOfCohortPct) {
}

record TradeoffSummary(
        String tradeoffId,
        TradeoffMetricSummary benefitMetric,
        TradeoffMetricSummary counterMetric,
        String outcomeShape) {
}

record TradeoffMetricSummary(
        String metricKey,
        String metricLabel) {
}

record PressureMetricsSummary(
        String primaryMetricKey,
        List<PressureMetricSummary> metrics) {
}

record PressureMetricSummary(
        String metricKey,
        double baselineValue,
        double candidateValue,
        double deltaValue) {
}

record SegmentImpactSummary(
        int totalImpactedAgents,
        List<SegmentLeaderboardEntry> winners,
        List<SegmentLeaderboardEntry> losers,
        List<SegmentLeaderboardEntry> counterPressureLeaders) {
}

record SegmentLeaderboardEntry(
        String segment,
        int impactedAgents,
        double metricValue) {
}

record PaymentStressSummary(
        String schemaVersion,
        int totalFailureCount,
        PaymentStressRunSummary baseline,
        PaymentStressRunSummary candidate,
        List<StressSegmentSummary> topStressSegments) {
}

record PaymentStressRunSummary(
        int totalFailureCount,
        int insufficientFundsFailureCount,
        int repeatedStressPersonaCount,
        int suppressedDiscretionaryPayments,
        int failedEssentialPayments,
        int failedObligationPayments,
        int affectedPersonaCount) {
}

record StressSegmentSummary(
        String segment,
        int totalFailureCount,
        int insufficientFundsFailureCount) {
}

record HeadlineSummary(
        int impactedAgents,
        double exposedBalance,
        double interestBearingBalance,
        double totalCreditLimit,
        double availableCredit,
        double deltaAvailableCredit,
        double averageCreditUtilisation,
        double deltaMonthlyInterest,
        double deltaMonthlyCustomerInterest,
        double deltaMonthlyBankMetric,
        double deltaMonthlyBankPnl,
        double oneTimeCustomerLiquiditySupport,
        double oneTimeBankCost,
        double totalHouseholdLiquidity,
        double deltaHouseholdLiquidity,
        int lowLiquidityAgents,
        int lowLiquidityImpactedAgents,
        double retainedFundedBalanceProxy,
        double liquiditySupportRateProxy,
        int paymentStressFailures,
        int insufficientFundsFailures,
        int reliefRecipients,
        int subsidiesIssued,
        double policyFundBalance,
        int constrainedCustomers,
        double overLimitExposure,
        double hardshipBudgetBurn,
        int residualHardshipAgents,
        double hardshipTargetingPrecisionProxy,
        int fraudAlertedAgents,
        int fraudAlertedAccounts,
        int fraudAlertWaveCount,
        double customerFrictionProxy,
        double operatorReviewHoursProxy,
        String fraudSeverity,
        String fraudChannel,
        String topImpactedSegment) {
}

record EvaluatorSummary(
        int breachCount,
        long failBreaches,
        String comparisonReport,
        List<RegressionThresholdBreach> thresholdBreaches,
        DecisionGatesSummary decisionGates) {
}

record DecisionGatesSummary(
        int breachedGateCount,
        int failGateCount,
        List<DecisionGateSummary> gates) {
}

record DecisionGateSummary(
        String gateId,
        String metricKey,
        double observedValue,
        String reason,
        boolean breached,
        boolean fail) {
}

record ArtifactSummary(
        String baselineArtifactsDir,
        String candidateArtifactsDir,
        String baselineReport,
        String candidateReport,
        String comparisonReport,
        String impactedCohortsCsv,
        String impactedSegmentsJson,
        String checkpointsJson,
        String agentDiffsJson,
        String notableActionsJson,
        String scheduledWorkFailuresJson,
        String simulationTimelineJson,
        String sideEffectLedgerJson,
        String branchStateProjectionJson,
        String experimentCard,
        String productCatalogSource) {
}

record ImpactedSegment(
        String segment,
        int impactedAgents,
        double exposedBalance,
        double interestBearingBalance,
        double totalCreditLimit,
        double availableCredit,
        double deltaAvailableCredit,
        double averageCreditUtilisation,
        double currentBalance,
        double deltaCurrentBalance,
        long lowLiquidityAgents,
        long reliefRecipients,
        long subsidiesIssued,
        double deltaMonthlyInterest,
        double deltaMonthlyBankMetric,
        double deltaMonthlyBankPnl) {
    double sortMagnitude() {
        return Math.abs(deltaMonthlyInterest) + Math.abs(deltaAvailableCredit);
    }
}

record MergedCheckpoint(
        String label,
        long elapsedSeconds,
        long currentTime,
        AggregateSample baseline,
        AggregateSample candidate,
        AggregateSample delta) {
}

record NotableAction(
        String personaName,
        String segment,
        String category,
        String description) {
}

record ScheduledWorkFailure(
        String personaName,
        String segment,
        String detail,
        String failureKind,
        String paymentClass) {
}

record AgentDiffRow(
        String personaName,
        String segment,
        String productCode,
        String productName,
        String productCategory,
        String balanceSheetSide,
        double baselineProductBalance,
        double candidateProductBalance,
        double productBalance,
        double interestBearingBalance,
        double currentBalance,
        double baselineCurrentBalance,
        double candidateCurrentBalance,
        double deltaCurrentBalance,
        double baselineCreditLimit,
        double candidateCreditLimit,
        double deltaCreditLimit,
        double baselineAvailableCredit,
        double candidateAvailableCredit,
        double deltaAvailableCredit,
        double baselineCreditUtilisation,
        double candidateCreditUtilisation,
        double deltaCreditUtilisation,
        boolean hardshipFlag,
        boolean lowLiquidityBefore,
        boolean lowLiquidityAfter,
        boolean subsidyReceived,
        double deltaMonthlyInterest,
        double deltaMonthlyBankMetric,
        double deltaMonthlyBankPnl,
        boolean fraudAlerted,
        String fraudSeverity,
        String fraudChannel) {
    double sortMagnitude() {
        return Math.abs(deltaCurrentBalance) + Math.abs(productBalance) + Math.abs(deltaAvailableCredit)
                + Math.abs(deltaMonthlyInterest) * 10;
    }
}

record AggregateMetrics(
        String runId,
        int activeAgents,
        int impactedAgents,
        double impactedAgentPct,
        double exposedBalance,
        double interestBearingBalance,
        double estimatedMonthlyInterest,
        double estimatedMonthlyBankMetric,
        double estimatedMonthlyBankPnl,
        double oneTimeCustomerLiquiditySupport,
        double oneTimeBankCost,
        double totalCreditLimit,
        double availableCredit,
        double averageCreditUtilisation,
        double totalHouseholdLiquidity,
        int lowLiquidityAgents,
        int reliefRecipients,
        int subsidiesIssued,
        double policyFundBalance,
        double retainedFundedBalanceProxy,
        double liquiditySupportRateProxy,
        int hardshipAgents,
        int highUtilisationAgents,
        String scoreRationale) {

    AggregateMetrics subtract(AggregateMetrics baseline) {
        return new AggregateMetrics(
                runId,
                activeAgents - baseline.activeAgents,
                impactedAgents - baseline.impactedAgents,
                impactedAgentPct - baseline.impactedAgentPct,
                exposedBalance - baseline.exposedBalance,
                interestBearingBalance - baseline.interestBearingBalance,
                estimatedMonthlyInterest - baseline.estimatedMonthlyInterest,
                estimatedMonthlyBankMetric - baseline.estimatedMonthlyBankMetric,
                estimatedMonthlyBankPnl - baseline.estimatedMonthlyBankPnl,
                oneTimeCustomerLiquiditySupport - baseline.oneTimeCustomerLiquiditySupport,
                oneTimeBankCost - baseline.oneTimeBankCost,
                totalCreditLimit - baseline.totalCreditLimit,
                availableCredit - baseline.availableCredit,
                averageCreditUtilisation - baseline.averageCreditUtilisation,
                totalHouseholdLiquidity - baseline.totalHouseholdLiquidity,
                lowLiquidityAgents - baseline.lowLiquidityAgents,
                reliefRecipients - baseline.reliefRecipients,
                subsidiesIssued - baseline.subsidiesIssued,
                policyFundBalance - baseline.policyFundBalance,
                retainedFundedBalanceProxy - baseline.retainedFundedBalanceProxy,
                liquiditySupportRateProxy - baseline.liquiditySupportRateProxy,
                hardshipAgents - baseline.hardshipAgents,
                highUtilisationAgents - baseline.highUtilisationAgents,
                scoreRationale);
    }

    MetricSummarySection asSummarySection(EvaluationRunReport evaluationReport) {
        EvaluationSummary summary = evaluationReport == null ? null : evaluationReport.getSummary();
        boolean verified = summary != null
                && summary.getOverallScore() != null
                && summary.getOverallVerdict() != null;
        return new MetricSummarySection(
                runId,
                verified ? summary.getOverallScore() : 0.0,
                verified ? summary.getOverallVerdict().getValue() : "UNVERIFIED",
                activeAgents,
                impactedAgents,
                impactedAgentPct,
                exposedBalance,
                interestBearingBalance,
                estimatedMonthlyInterest,
                estimatedMonthlyInterest,
                estimatedMonthlyBankMetric,
                estimatedMonthlyBankPnl,
                oneTimeCustomerLiquiditySupport,
                oneTimeBankCost,
                totalCreditLimit,
                availableCredit,
                averageCreditUtilisation,
                totalHouseholdLiquidity,
                lowLiquidityAgents,
                reliefRecipients,
                subsidiesIssued,
                policyFundBalance,
                retainedFundedBalanceProxy,
                liquiditySupportRateProxy,
                hardshipAgents,
                highUtilisationAgents,
                verified
                        ? scoreRationale
                        : "Evaluation evidence is incomplete; domain KPIs are not proof of readiness.");
    }

    MetricDeltaSection asDeltaSection(RegressionDeltaReport comparisonReport) {
        return new MetricDeltaSection(
                comparisonReport.getOverallDelta() == null ? 0.0 : comparisonReport.getOverallDelta(),
                estimatedMonthlyInterest,
                estimatedMonthlyInterest,
                estimatedMonthlyBankMetric,
                estimatedMonthlyBankPnl,
                oneTimeCustomerLiquiditySupport,
                oneTimeBankCost,
                interestBearingBalance,
                totalCreditLimit,
                availableCredit,
                averageCreditUtilisation,
                totalHouseholdLiquidity,
                lowLiquidityAgents,
                reliefRecipients,
                subsidiesIssued,
                policyFundBalance,
                exposedBalance,
                hardshipAgents,
                highUtilisationAgents);
    }
}

record MetricSummarySection(
        String runId,
        double overallScore,
        String overallVerdict,
        int activeAgents,
        int impactedAgents,
        double impactedAgentPct,
        double exposedBalance,
        double interestBearingBalance,
        double estimatedMonthlyInterest,
        double estimatedMonthlyCustomerInterest,
        double estimatedMonthlyBankMetric,
        double estimatedMonthlyBankPnl,
        double oneTimeCustomerLiquiditySupport,
        double oneTimeBankCost,
        double totalCreditLimit,
        double availableCredit,
        double averageCreditUtilisation,
        double totalHouseholdLiquidity,
        int lowLiquidityAgents,
        int reliefRecipients,
        int subsidiesIssued,
        double policyFundBalance,
        double retainedFundedBalanceProxy,
        double liquiditySupportRateProxy,
        int hardshipAgents,
        int highUtilisationAgents,
        String scoreRationale) {
}

record MetricDeltaSection(
        double overallScore,
        double estimatedMonthlyInterest,
        double estimatedMonthlyCustomerInterest,
        double estimatedMonthlyBankMetric,
        double estimatedMonthlyBankPnl,
        double oneTimeCustomerLiquiditySupport,
        double oneTimeBankCost,
        double interestBearingBalance,
        double totalCreditLimit,
        double availableCredit,
        double averageCreditUtilisation,
        double totalHouseholdLiquidity,
        int lowLiquidityAgents,
        int reliefRecipients,
        int subsidiesIssued,
        double policyFundBalance,
        double exposedBalance,
        int hardshipAgents,
        int highUtilisationAgents) {
}

record SummaryArtifacts(
        Path summaryPath,
        Path impactedCohortsCsvPath,
        Path experimentCardPath,
        Path checkpointsJsonPath,
        Path agentDiffsJsonPath,
        Path notableActionsJsonPath,
        Path scheduledWorkFailuresJsonPath,
        Path simulationTimelineJsonPath,
        Path sideEffectLedgerJsonPath,
        Path branchStateProjectionJsonPath,
        Path impactedSegmentsJsonPath) {
}
