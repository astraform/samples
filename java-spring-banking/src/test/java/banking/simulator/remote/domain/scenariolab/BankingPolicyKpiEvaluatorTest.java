package banking.simulator.remote.domain.scenariolab;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankingPolicyKpiEvaluatorTest {
    private final BankingPolicyKpiEvaluator evaluator = new BankingPolicyKpiEvaluator();

    @Test
    void comparesRateOutcomesUsingDistinctBranchBalancesAndRealRunIdentity() {
        BankingPolicyKpiEvaluator.CustomerOutcome outcome = outcome(
                "customer-1", "1000", "500", "200", "100", "0", "0", "0", "0", "0", "0");
        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "run-1",
                BankingPolicyKpiEvaluator.PolicyKind.RATE,
                "0.19",
                "0.24",
                List.of(customer("customer-1", false, "200", "0", "1000", "1000"),
                        customer("policy-fund", true, "9999", "0", "0", "9999")),
                List.of(customer("customer-1", false, "100", "0", "500", "500"),
                        customer("policy-fund", true, "9999", "0", "0", "9999")),
                List.of(outcome),
                List.of(),
                List.of()));

        assertThat(result.baseline()).isEqualTo(new BankingPolicyKpiEvaluator.BranchKpis(
                new BankingPolicyKpiEvaluator.BranchIdentity("run-1", "run-1-baseline"),
                1, 1, decimal("1.0000"), decimal("1000.00"), decimal("1000.00"),
                decimal("15.83"), decimal("15.83"), decimal("15.83"),
                decimal("0.00"), decimal("0.00"),
                decimal("0.00"), decimal("0.00"), decimal("0.0000"), decimal("200.00"),
                0, 0, 0, decimal("9999.00"), decimal("0.00"), decimal("0.0000"),
                0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(result.candidate()).isEqualTo(new BankingPolicyKpiEvaluator.BranchKpis(
                new BankingPolicyKpiEvaluator.BranchIdentity("run-1", "run-1-candidate"),
                1, 1, decimal("1.0000"), decimal("500.00"), decimal("500.00"),
                decimal("10.00"), decimal("10.00"), decimal("10.00"),
                decimal("0.00"), decimal("0.00"),
                decimal("0.00"), decimal("0.00"), decimal("0.0000"), decimal("100.00"),
                0, 0, 0, decimal("9999.00"), decimal("0.00"), decimal("0.0000"),
                0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(result.estimatedMonthlyInterestDelta()).isEqualByComparingTo("-5.83");

        BankingPolicyKpiEvaluator.EvaluationResult secondRun = evaluator.evaluate(input(
                "run-2",
                BankingPolicyKpiEvaluator.PolicyKind.RATE,
                "0.19",
                "0.24",
                List.of(customer("customer-1", false, "200", "0", "1000", "1000")),
                List.of(customer("customer-1", false, "100", "0", "500", "500")),
                List.of(outcome),
                List.of(),
                List.of()));
        assertThat(secondRun.baseline().identity().branchId()).isEqualTo("run-2-baseline");
        assertThat(secondRun.baseline().identity()).isNotEqualTo(result.baseline().identity());
    }

    @Test
    void evaluatesLimitHeadroomFromTypedCustomerOutcomes() {
        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "limit-run",
                BankingPolicyKpiEvaluator.PolicyKind.LIMIT,
                "0.20",
                "0.20",
                List.of(customer("customer-1", false, "500", "0.8", "-800", "-800")),
                List.of(customer("customer-1", false, "500", "0.5333", "-800", "-800")),
                List.of(outcome(
                        "customer-1", "-800", "-800", "500", "500", "1000", "1500",
                        "200", "700", "0.8", "0.5333")),
                List.of(new BankingPolicyKpiEvaluator.RealizedAction(
                        BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                        "customer-1",
                        BankingPolicyKpiEvaluator.RealizedActionKind.ACCOUNT_MUTATION,
                        "LIMIT_CHANGE",
                        true)),
                List.of(effect(
                        BankingPolicyKpiEvaluator.InterventionKind.LIMIT_CHANGE,
                        List.of("customer-1"),
                        "0",
                        1))));

        assertThat(result.candidate()).isEqualTo(new BankingPolicyKpiEvaluator.BranchKpis(
                new BankingPolicyKpiEvaluator.BranchIdentity("limit-run", "limit-run-candidate"),
                1, 1, decimal("1.0000"), decimal("-800.00"), decimal("800.00"),
                decimal("13.33"), decimal("13.33"), decimal("13.33"),
                decimal("0.00"), decimal("0.00"),
                decimal("1500.00"), decimal("700.00"), decimal("0.5333"), decimal("500.00"),
                0, 0, 0, decimal("0.00"), decimal("0.00"), decimal("0.0000"),
                0, 0, 0, 0, 0, 0, 1, 1));
    }

    @Test
    void evaluatesHardshipFromTypedRealizedEffect() {
        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "hardship-run",
                BankingPolicyKpiEvaluator.PolicyKind.HARDSHIP,
                "0",
                "0",
                List.of(customer("customer-1", false, "50", "0", "0", "50")),
                List.of(customer("customer-1", false, "100", "0", "0", "100")),
                List.of(outcome(
                        "customer-1", "50", "100", "50", "100", "0", "0", "0", "0", "0", "0")),
                List.of(),
                List.of(effect(
                        BankingPolicyKpiEvaluator.InterventionKind.HARDSHIP_RELIEF,
                        List.of("customer-1"),
                        "75",
                        1))));

        assertThat(result.candidate()).isEqualTo(new BankingPolicyKpiEvaluator.BranchKpis(
                new BankingPolicyKpiEvaluator.BranchIdentity("hardship-run", "hardship-run-candidate"),
                1, 1, decimal("1.0000"), decimal("100.00"), decimal("0.00"),
                decimal("0.00"), decimal("0.00"), decimal("0.00"),
                decimal("75.00"), decimal("75.00"),
                decimal("0.00"), decimal("0.00"), decimal("0.0000"), decimal("100.00"),
                0, 1, 1, decimal("0.00"), decimal("0.00"), decimal("0.0000"),
                0, 0, 0, 0, 0, 0, 0, 1));
    }

    @Test
    void doesNotInferHardshipReliefOrActionsFromBalanceMovement() {
        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "hardship-without-evidence",
                BankingPolicyKpiEvaluator.PolicyKind.HARDSHIP,
                "0",
                "0",
                List.of(customer("customer-1", false, "50", "0", "0", "50")),
                List.of(customer("customer-1", false, "125", "0", "0", "125")),
                List.of(outcome(
                        "customer-1", "50", "125", "50", "125", "0", "0", "0", "0", "0", "0")),
                List.of(),
                List.of()));

        assertThat(result.candidate().estimatedMonthlyInterest()).isEqualByComparingTo("0.00");
        assertThat(result.candidate().estimatedMonthlyBankPnl()).isEqualByComparingTo("0.00");
        assertThat(result.candidate().oneTimeCustomerLiquiditySupport()).isEqualByComparingTo("0.00");
        assertThat(result.candidate().oneTimeBankCost()).isEqualByComparingTo("0.00");
        assertThat(result.candidate().reliefRecipients()).isZero();
        assertThat(result.candidate().subsidiesIssued()).isZero();
        assertThat(result.candidate().realizedActionCount()).isZero();
        assertThat(result.candidate().totalHouseholdLiquidity()).isEqualByComparingTo("125.00");
    }

    @Test
    void externalStimulusSupportsCustomerLiquidityWithoutCreatingBankCost() {
        BankingPolicyKpiEvaluator.InterventionEffect externalStimulus =
                new BankingPolicyKpiEvaluator.InterventionEffect(
                        BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                        "candidate:stimulus-1",
                        BankingPolicyKpiEvaluator.InterventionKind.STIMULUS,
                        true,
                        List.of("customer-1"),
                        1,
                        0,
                        decimal("250"),
                        1,
                        BankingPolicyKpiEvaluator.FundingSource.EXTERNAL);

        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "stimulus-run",
                BankingPolicyKpiEvaluator.PolicyKind.RATE,
                "0",
                "0",
                List.of(customer("customer-1", false, "100", "0", "100", "100")),
                List.of(customer("customer-1", false, "350", "0", "350", "350")),
                List.of(outcome(
                        "customer-1", "100", "350", "100", "350", "0", "0", "0", "0", "0", "0")),
                List.of(),
                List.of(externalStimulus)));

        assertThat(result.candidate().oneTimeCustomerLiquiditySupport()).isEqualByComparingTo("250.00");
        assertThat(result.candidate().oneTimeBankCost()).isEqualByComparingTo("0.00");
        assertThat(result.candidate().totalHouseholdLiquidity()).isEqualByComparingTo("350.00");
    }

    @Test
    void evaluatesFraudEffectsWithoutInventingCustomerDecisions() {
        BankingPolicyKpiEvaluator.EvaluationResult result = evaluator.evaluate(input(
                "fraud-run",
                BankingPolicyKpiEvaluator.PolicyKind.FRAUD,
                "0.20",
                "0.20",
                List.of(customer("customer-1", false, "500", "0.5", "-500", "-500")),
                List.of(customer("customer-1", false, "500", "0.5", "-500", "-500")),
                List.of(outcome(
                        "customer-1", "-500", "-500", "500", "500", "1000", "1000", "500", "500",
                        "0.5", "0.5")),
                List.of(new BankingPolicyKpiEvaluator.RealizedAction(
                        BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                        "candidate:fraud-wave-1",
                        BankingPolicyKpiEvaluator.RealizedActionKind.CUSTOMER_NOTIFICATION,
                        "FRAUD_ALERT",
                        true)),
                List.of(new BankingPolicyKpiEvaluator.InterventionEffect(
                        BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                        "candidate:fraud-wave-1",
                        BankingPolicyKpiEvaluator.InterventionKind.FRAUD_ALERT,
                        true,
                        List.of(),
                        2,
                        3,
                        BigDecimal.ZERO,
                        1,
                        BankingPolicyKpiEvaluator.FundingSource.NONE))));

        assertThat(result.candidate()).isEqualTo(new BankingPolicyKpiEvaluator.BranchKpis(
                new BankingPolicyKpiEvaluator.BranchIdentity("fraud-run", "fraud-run-candidate"),
                1, 1, decimal("1.0000"), decimal("-500.00"), decimal("500.00"),
                decimal("8.33"), decimal("8.33"), decimal("8.33"),
                decimal("0.00"), decimal("0.00"),
                decimal("1000.00"), decimal("500.00"), decimal("0.5000"), decimal("500.00"),
                0, 0, 0, decimal("0.00"), decimal("0.00"), decimal("0.0000"),
                0, 0, 2, 3, 1, 0, 1, 1));
        assertThat(result.baseline().fraudAlertedCustomers()).isZero();
    }

    @Test
    void incompleteEvaluationEvidenceNeverManufacturesPass() {
        AggregateMetrics metrics = new AggregateMetrics(
                "branch-1", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");

        MetricSummarySection summary = metrics.asSummarySection(null);

        assertThat(summary.overallVerdict()).isEqualTo("UNVERIFIED");
        assertThat(summary.overallScore()).isZero();
        assertThat(summary.scoreRationale()).doesNotContain("PASS");
    }

    private static BankingPolicyKpiEvaluator.EvaluationInput input(
            String runId,
            BankingPolicyKpiEvaluator.PolicyKind policyKind,
            String baselineRate,
            String candidateRate,
            List<BankingPolicyKpiEvaluator.CustomerSnapshot> baselineCustomers,
            List<BankingPolicyKpiEvaluator.CustomerSnapshot> candidateCustomers,
            List<BankingPolicyKpiEvaluator.CustomerOutcome> outcomes,
            List<BankingPolicyKpiEvaluator.RealizedAction> actions,
            List<BankingPolicyKpiEvaluator.InterventionEffect> effects) {
        return new BankingPolicyKpiEvaluator.EvaluationInput(
                new BankingPolicyKpiEvaluator.BranchIdentity(runId, runId + "-baseline"),
                new BankingPolicyKpiEvaluator.BranchIdentity(runId, runId + "-candidate"),
                new BankingPolicyKpiEvaluator.ProductFacts("PRODUCT-1", "ASSET"),
                policyKind,
                new BigDecimal(baselineRate),
                new BigDecimal(candidateRate),
                baselineCustomers,
                candidateCustomers,
                outcomes,
                actions,
                effects);
    }

    private static BankingPolicyKpiEvaluator.CustomerSnapshot customer(
            String id,
            boolean policyAgent,
            String currentBalance,
            String utilisation,
            String productBalance,
            String totalBalance) {
        return new BankingPolicyKpiEvaluator.CustomerSnapshot(
                id,
                policyAgent,
                new BigDecimal(currentBalance),
                new BigDecimal(utilisation),
                false,
                new BigDecimal(productBalance),
                new BigDecimal(totalBalance));
    }

    private static BankingPolicyKpiEvaluator.CustomerOutcome outcome(
            String id,
            String baselineProductBalance,
            String candidateProductBalance,
            String baselineCurrentBalance,
            String candidateCurrentBalance,
            String baselineCreditLimit,
            String candidateCreditLimit,
            String baselineAvailableCredit,
            String candidateAvailableCredit,
            String baselineUtilisation,
            String candidateUtilisation) {
        return new BankingPolicyKpiEvaluator.CustomerOutcome(
                id,
                false,
                new BigDecimal(baselineProductBalance),
                new BigDecimal(candidateProductBalance),
                new BigDecimal(baselineCurrentBalance),
                new BigDecimal(candidateCurrentBalance),
                new BigDecimal(baselineCreditLimit),
                new BigDecimal(candidateCreditLimit),
                new BigDecimal(baselineAvailableCredit),
                new BigDecimal(candidateAvailableCredit),
                new BigDecimal(baselineUtilisation),
                new BigDecimal(candidateUtilisation));
    }

    private static BankingPolicyKpiEvaluator.InterventionEffect effect(
            BankingPolicyKpiEvaluator.InterventionKind kind,
            List<String> affectedCustomers,
            String amount,
            int occurrences) {
        return new BankingPolicyKpiEvaluator.InterventionEffect(
                BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                "candidate:test-effect",
                kind,
                true,
                affectedCustomers,
                affectedCustomers.size(),
                kind == BankingPolicyKpiEvaluator.InterventionKind.FRAUD_ALERT
                        ? affectedCustomers.size()
                        : 0,
                new BigDecimal(amount),
                occurrences,
                kind == BankingPolicyKpiEvaluator.InterventionKind.HARDSHIP_RELIEF
                        ? BankingPolicyKpiEvaluator.FundingSource.BANK
                        : BankingPolicyKpiEvaluator.FundingSource.NONE);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
