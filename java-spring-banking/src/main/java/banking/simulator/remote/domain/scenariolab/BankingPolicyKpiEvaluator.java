package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

final class BankingPolicyKpiEvaluator {
    private static final BigDecimal LOW_LIQUIDITY_THRESHOLD = BigDecimal.valueOf(100);
    private static final BigDecimal HIGH_UTILISATION_THRESHOLD = BigDecimal.valueOf(0.9);

    EvaluationResult evaluate(EvaluationInput input) {
        Objects.requireNonNull(input, "input");
        return new EvaluationResult(
                evaluateBranch(input, Branch.BASELINE),
                evaluateBranch(input, Branch.CANDIDATE));
    }

    private BranchKpis evaluateBranch(EvaluationInput input, Branch branch) {
        BranchIdentity identity = branch == Branch.BASELINE
                ? input.baselineIdentity()
                : input.candidateIdentity();
        List<CustomerSnapshot> snapshots = branch == Branch.BASELINE
                ? input.baselineCustomers()
                : input.candidateCustomers();
        List<CustomerSnapshot> customers = snapshots.stream()
                .filter(snapshot -> !snapshot.policyAgent())
                .toList();
        List<CustomerOutcome> outcomes = input.customerOutcomes().stream()
                .filter(outcome -> !outcome.policyAgent())
                .toList();

        BigDecimal exposedBalance = outcomes.stream()
                .map(outcome -> outcome.productBalance(branch))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interestBearingBalance = input.policyKind() == PolicyKind.HARDSHIP
                ? BigDecimal.ZERO
                : outcomes.stream()
                        .map(outcome -> outcome.productBalance(branch).abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCreditLimit = outcomes.stream()
                .map(outcome -> outcome.creditLimit(branch))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal availableCredit = outcomes.stream()
                .map(outcome -> outcome.availableCredit(branch))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weightedUtilisation = outcomes.stream()
                .map(outcome -> outcome.creditLimit(branch).multiply(outcome.creditUtilisation(branch)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageUtilisation = totalCreditLimit.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : weightedUtilisation.divide(totalCreditLimit, 6, RoundingMode.HALF_UP);

        BigDecimal householdLiquidity = customers.stream()
                .map(CustomerSnapshot::currentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int lowLiquidityCustomers = (int) customers.stream()
                .filter(customer -> customer.currentBalance().compareTo(LOW_LIQUIDITY_THRESHOLD) < 0)
                .count();
        int hardshipCustomers = (int) customers.stream().filter(CustomerSnapshot::hardshipFlag).count();
        int highUtilisationCustomers = (int) customers.stream()
                .filter(customer -> customer.creditUtilisation().compareTo(HIGH_UTILISATION_THRESHOLD) >= 0)
                .count();
        BigDecimal policyFundBalance = snapshots.stream()
                .filter(CustomerSnapshot::policyAgent)
                .map(CustomerSnapshot::totalBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal retainedFundedBalanceProxy = input.policyKind() == PolicyKind.RATE
                        && "LIABILITY".equals(input.product().balanceSheetSide())
                ? customers.stream()
                        .filter(customer -> customer.currentBalance().compareTo(LOW_LIQUIDITY_THRESHOLD) < 0)
                        .map(customer -> customer.productBalance().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;
        BigDecimal liquiditySupportRateProxy = exposedBalance.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : retainedFundedBalanceProxy.divide(exposedBalance.abs(), 6, RoundingMode.HALF_UP);

        BigDecimal monthlyCustomerMetric;
        BigDecimal monthlyBankMetric;
        BigDecimal monthlyBankPnl;
        if (input.policyKind() == PolicyKind.HARDSHIP) {
            monthlyCustomerMetric = BigDecimal.ZERO;
            monthlyBankMetric = BigDecimal.ZERO;
            monthlyBankPnl = BigDecimal.ZERO;
        } else {
            BigDecimal annualRate = branch == Branch.BASELINE
                    ? input.baselineAnnualRate()
                    : input.candidateAnnualRate();
            monthlyCustomerMetric = monthlyInterest(exposedBalance, annualRate);
            monthlyBankMetric = monthlyCustomerMetric;
            monthlyBankPnl = "LIABILITY".equals(input.product().balanceSheetSide())
                    ? monthlyBankMetric.negate()
                    : monthlyBankMetric;
        }

        BigDecimal oneTimeCustomerLiquiditySupport = appliedEffectAmount(
                input.interventionEffects(), branch, InterventionKind.HARDSHIP_RELIEF).add(
                        appliedEffectAmount(input.interventionEffects(), branch, InterventionKind.STIMULUS));
        BigDecimal oneTimeBankCost = appliedBankFundedAmount(input.interventionEffects(), branch);

        int reliefRecipients = distinctAffectedCustomers(
                input.interventionEffects(), branch, InterventionKind.HARDSHIP_RELIEF);
        int subsidiesIssued = appliedEffectOccurrences(
                input.interventionEffects(), branch, InterventionKind.HARDSHIP_RELIEF);
        int realizedActions = (int) input.realizedActions().stream()
                .filter(action -> action.branch() == branch && action.applied())
                .count();
        int realizedDecisions = (int) input.realizedActions().stream()
                .filter(action -> action.branch() == branch
                        && action.kind() == RealizedActionKind.CUSTOMER_DECISION
                        && action.applied())
                .count();
        int appliedEffects = (int) input.interventionEffects().stream()
                .filter(effect -> effect.branch() == branch && effect.applied())
                .count();
        int fraudAlertedCustomers = affectedCustomerCount(
                input.interventionEffects(), branch, InterventionKind.FRAUD_ALERT);
        int fraudAffectedAccounts = affectedAccountCount(
                input.interventionEffects(), branch, InterventionKind.FRAUD_ALERT);
        int fraudAlertWaves = appliedEffectOccurrences(
                input.interventionEffects(), branch, InterventionKind.FRAUD_ALERT);

        return new BranchKpis(
                identity,
                customers.size(),
                outcomes.size(),
                customers.isEmpty()
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf((double) outcomes.size() / customers.size())
                                .setScale(4, RoundingMode.HALF_UP),
                money(exposedBalance),
                money(interestBearingBalance),
                money(monthlyCustomerMetric),
                money(monthlyBankMetric),
                money(monthlyBankPnl),
                money(oneTimeCustomerLiquiditySupport),
                money(oneTimeBankCost),
                money(totalCreditLimit),
                money(availableCredit),
                averageUtilisation.setScale(4, RoundingMode.HALF_UP),
                money(householdLiquidity),
                lowLiquidityCustomers,
                reliefRecipients,
                subsidiesIssued,
                money(policyFundBalance),
                money(retainedFundedBalanceProxy),
                liquiditySupportRateProxy.setScale(4, RoundingMode.HALF_UP),
                hardshipCustomers,
                highUtilisationCustomers,
                fraudAlertedCustomers,
                fraudAffectedAccounts,
                fraudAlertWaves,
                realizedDecisions,
                realizedActions,
                appliedEffects);
    }

    private static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return balance.abs().multiply(rate).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal appliedEffectAmount(
            List<InterventionEffect> effects,
            Branch branch,
            InterventionKind kind) {
        return effects.stream()
                .filter(effect -> effect.branch() == branch && effect.kind() == kind && effect.applied())
                .map(InterventionEffect::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal appliedBankFundedAmount(
            List<InterventionEffect> effects,
            Branch branch) {
        return effects.stream()
                .filter(effect -> effect.branch() == branch
                        && effect.applied()
                        && effect.fundingSource() == FundingSource.BANK)
                .map(InterventionEffect::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int distinctAffectedCustomers(
            List<InterventionEffect> effects,
            Branch branch,
            InterventionKind kind) {
        return (int) effects.stream()
                .filter(effect -> effect.branch() == branch && effect.kind() == kind && effect.applied())
                .flatMap(effect -> effect.affectedCustomerIds().stream())
                .distinct()
                .count();
    }

    private static int appliedEffectOccurrences(
            List<InterventionEffect> effects,
            Branch branch,
            InterventionKind kind) {
        return effects.stream()
                .filter(effect -> effect.branch() == branch && effect.kind() == kind && effect.applied())
                .mapToInt(InterventionEffect::occurrenceCount)
                .sum();
    }

    private static int affectedCustomerCount(
            List<InterventionEffect> effects,
            Branch branch,
            InterventionKind kind) {
        return effects.stream()
                .filter(effect -> effect.branch() == branch && effect.kind() == kind && effect.applied())
                .mapToInt(InterventionEffect::affectedCustomerCount)
                .sum();
    }

    private static int affectedAccountCount(
            List<InterventionEffect> effects,
            Branch branch,
            InterventionKind kind) {
        return effects.stream()
                .filter(effect -> effect.branch() == branch && effect.kind() == kind && effect.applied())
                .mapToInt(InterventionEffect::affectedAccountCount)
                .sum();
    }

    enum Branch {
        BASELINE,
        CANDIDATE
    }

    enum PolicyKind {
        RATE,
        LIMIT,
        HARDSHIP,
        FRAUD
    }

    enum RealizedActionKind {
        CUSTOMER_DECISION,
        ACCOUNT_MUTATION,
        CUSTOMER_NOTIFICATION,
        OTHER
    }

    enum InterventionKind {
        RATE_CHANGE,
        LIMIT_CHANGE,
        STIMULUS,
        MARKET_CRASH,
        HARDSHIP_RELIEF,
        FRAUD_ALERT
    }

    enum FundingSource {
        NONE,
        BANK,
        EXTERNAL
    }

    record EvaluationInput(
            BranchIdentity baselineIdentity,
            BranchIdentity candidateIdentity,
            ProductFacts product,
            PolicyKind policyKind,
            BigDecimal baselineAnnualRate,
            BigDecimal candidateAnnualRate,
            List<CustomerSnapshot> baselineCustomers,
            List<CustomerSnapshot> candidateCustomers,
            List<CustomerOutcome> customerOutcomes,
            List<RealizedAction> realizedActions,
            List<InterventionEffect> interventionEffects) {
        EvaluationInput {
            Objects.requireNonNull(baselineIdentity, "baselineIdentity");
            Objects.requireNonNull(candidateIdentity, "candidateIdentity");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(policyKind, "policyKind");
            baselineAnnualRate = Objects.requireNonNull(baselineAnnualRate, "baselineAnnualRate");
            candidateAnnualRate = Objects.requireNonNull(candidateAnnualRate, "candidateAnnualRate");
            baselineCustomers = List.copyOf(baselineCustomers);
            candidateCustomers = List.copyOf(candidateCustomers);
            customerOutcomes = List.copyOf(customerOutcomes);
            realizedActions = List.copyOf(realizedActions);
            interventionEffects = List.copyOf(interventionEffects);
        }
    }

    record BranchIdentity(String runId, String branchId) {
        BranchIdentity {
            if (runId == null || runId.isBlank() || branchId == null || branchId.isBlank()) {
                throw new IllegalArgumentException("runId and branchId are required");
            }
        }
    }

    record ProductFacts(String productCode, String balanceSheetSide) {
        ProductFacts {
            if (productCode == null || productCode.isBlank()
                    || balanceSheetSide == null || balanceSheetSide.isBlank()) {
                throw new IllegalArgumentException("productCode and balanceSheetSide are required");
            }
        }
    }

    record CustomerSnapshot(
            String customerId,
            boolean policyAgent,
            BigDecimal currentBalance,
            BigDecimal creditUtilisation,
            boolean hardshipFlag,
            BigDecimal productBalance,
            BigDecimal totalBalance) {
        CustomerSnapshot {
            Objects.requireNonNull(customerId, "customerId");
            currentBalance = Objects.requireNonNull(currentBalance, "currentBalance");
            creditUtilisation = Objects.requireNonNull(creditUtilisation, "creditUtilisation");
            productBalance = Objects.requireNonNull(productBalance, "productBalance");
            totalBalance = Objects.requireNonNull(totalBalance, "totalBalance");
        }
    }

    record CustomerOutcome(
            String customerId,
            boolean policyAgent,
            BigDecimal baselineProductBalance,
            BigDecimal candidateProductBalance,
            BigDecimal baselineCurrentBalance,
            BigDecimal candidateCurrentBalance,
            BigDecimal baselineCreditLimit,
            BigDecimal candidateCreditLimit,
            BigDecimal baselineAvailableCredit,
            BigDecimal candidateAvailableCredit,
            BigDecimal baselineCreditUtilisation,
            BigDecimal candidateCreditUtilisation) {
        CustomerOutcome {
            Objects.requireNonNull(customerId, "customerId");
            baselineProductBalance = Objects.requireNonNull(baselineProductBalance, "baselineProductBalance");
            candidateProductBalance = Objects.requireNonNull(candidateProductBalance, "candidateProductBalance");
            baselineCurrentBalance = Objects.requireNonNull(baselineCurrentBalance, "baselineCurrentBalance");
            candidateCurrentBalance = Objects.requireNonNull(candidateCurrentBalance, "candidateCurrentBalance");
            baselineCreditLimit = Objects.requireNonNull(baselineCreditLimit, "baselineCreditLimit");
            candidateCreditLimit = Objects.requireNonNull(candidateCreditLimit, "candidateCreditLimit");
            baselineAvailableCredit = Objects.requireNonNull(baselineAvailableCredit, "baselineAvailableCredit");
            candidateAvailableCredit = Objects.requireNonNull(candidateAvailableCredit, "candidateAvailableCredit");
            baselineCreditUtilisation = Objects.requireNonNull(
                    baselineCreditUtilisation, "baselineCreditUtilisation");
            candidateCreditUtilisation = Objects.requireNonNull(
                    candidateCreditUtilisation, "candidateCreditUtilisation");
        }

        BigDecimal productBalance(Branch branch) {
            return branch == Branch.BASELINE ? baselineProductBalance : candidateProductBalance;
        }

        BigDecimal creditLimit(Branch branch) {
            return branch == Branch.BASELINE ? baselineCreditLimit : candidateCreditLimit;
        }

        BigDecimal availableCredit(Branch branch) {
            return branch == Branch.BASELINE ? baselineAvailableCredit : candidateAvailableCredit;
        }

        BigDecimal creditUtilisation(Branch branch) {
            return branch == Branch.BASELINE ? baselineCreditUtilisation : candidateCreditUtilisation;
        }

    }

    record RealizedAction(
            Branch branch,
            String evidenceId,
            RealizedActionKind kind,
            String actionCode,
            boolean applied) {
        RealizedAction {
            Objects.requireNonNull(branch, "branch");
            if (evidenceId == null || evidenceId.isBlank()) {
                throw new IllegalArgumentException("evidenceId is required");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(actionCode, "actionCode");
        }
    }

    record InterventionEffect(
            Branch branch,
            String evidenceId,
            InterventionKind kind,
            boolean applied,
            List<String> affectedCustomerIds,
            int affectedCustomerCount,
            int affectedAccountCount,
            BigDecimal amount,
            int occurrenceCount,
            FundingSource fundingSource) {
        InterventionEffect {
            Objects.requireNonNull(branch, "branch");
            if (evidenceId == null || evidenceId.isBlank()) {
                throw new IllegalArgumentException("evidenceId is required");
            }
            Objects.requireNonNull(kind, "kind");
            affectedCustomerIds = List.copyOf(affectedCustomerIds);
            amount = Objects.requireNonNull(amount, "amount");
            fundingSource = Objects.requireNonNull(fundingSource, "fundingSource");
            if (affectedCustomerCount < 0 || affectedAccountCount < 0 || occurrenceCount < 0) {
                throw new IllegalArgumentException("effect counters cannot be negative");
            }
            if (!affectedCustomerIds.isEmpty() && affectedCustomerIds.size() != affectedCustomerCount) {
                throw new IllegalArgumentException("identified customers must match affectedCustomerCount");
            }
        }
    }

    record EvaluationResult(BranchKpis baseline, BranchKpis candidate) {
        BigDecimal estimatedMonthlyInterestDelta() {
            return candidate.estimatedMonthlyInterest().subtract(baseline.estimatedMonthlyInterest());
        }
    }

    record BranchKpis(
            BranchIdentity identity,
            int activeCustomers,
            int impactedCustomers,
            BigDecimal impactedCustomerRatio,
            BigDecimal exposedBalance,
            BigDecimal interestBearingBalance,
            BigDecimal estimatedMonthlyInterest,
            BigDecimal estimatedMonthlyBankMetric,
            BigDecimal estimatedMonthlyBankPnl,
            BigDecimal oneTimeCustomerLiquiditySupport,
            BigDecimal oneTimeBankCost,
            BigDecimal totalCreditLimit,
            BigDecimal availableCredit,
            BigDecimal averageCreditUtilisation,
            BigDecimal totalHouseholdLiquidity,
            int lowLiquidityCustomers,
            int reliefRecipients,
            int subsidiesIssued,
            BigDecimal policyFundBalance,
            BigDecimal retainedFundedBalanceProxy,
            BigDecimal liquiditySupportRateProxy,
            int hardshipCustomers,
            int highUtilisationCustomers,
            int fraudAlertedCustomers,
            int fraudAffectedAccounts,
            int fraudAlertWaves,
            int realizedDecisionCount,
            int realizedActionCount,
            int appliedInterventionCount) {
    }
}
