package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class BankingRuntimeControlSupport {

    private final BankingRuntimeGateway bank;
    private final BankingControlEventSemantics bankingControlEventSemantics;

    public BankingRuntimeControlSupport(BankingRuntimeGateway bank) {
        this(bank, new BankingControlEventSemantics());
    }

    BankingRuntimeControlSupport(
            BankingRuntimeGateway bank,
            BankingControlEventSemantics bankingControlEventSemantics) {
        this.bank = bank;
        this.bankingControlEventSemantics = bankingControlEventSemantics;
    }

    public String normalizeFraudSeverity(String severity) {
        return bankingControlEventSemantics.normalizeFraudSeverity(severity);
    }

    public String normalizeFraudChannel(String channel) {
        return bankingControlEventSemantics.normalizeFraudChannel(channel);
    }

    public String formatFraudAlertImpactMessage(
            List<BankingAccountView> affected,
            String productCode,
            String severity,
            String channel,
            String customMessage) {
        return bankingControlEventSemantics.formatFraudAlertImpactMessage(
                affected,
                productCode,
                severity,
                channel,
                customMessage);
    }

    public String formatRateImpactMessage(List<BankingAccountView> affected, String productCode, Double newRate) {
        return bankingControlEventSemantics.formatRateImpactMessage(affected, productCode, newRate);
    }

    public String formatCreditLimitImpactMessage(
            List<BankingAccountView> affected,
            String productCode,
            Double newLimit) {
        return bankingControlEventSemantics.formatCreditLimitImpactMessage(affected, productCode, newLimit);
    }

    public List<BankingAccountView> findAffectedAccounts(BankingRuntimeAgentAccess agent, String productCode) {
        return bankingControlEventSemantics.findAffectedAccounts(resolveAgentAccounts(agent), productCode);
    }

    public boolean injectStimulusIfSupported(BankingRuntimeAgentAccess agent, Double amount, String description) {
        if (agent == null || !agent.supportsAccountLinkedControls()) {
            return false;
        }
        agent.injectTransaction(amount, description);
        return true;
    }

    public BankingProductView updateProductRate(String productCode, Double newRate) {
        return bank.updateProductRate(productCode, newRate);
    }

    public BankingProductView updateProductLimit(String productCode, Double newLimit) {
        return bank.updateProductLimit(productCode, newLimit);
    }

    public BankingHardshipScanSummary runHardshipScan(
            Collection<? extends BankingRuntimeAgentAccess> agents,
            ZonedDateTime eventTime,
            double hardshipThreshold,
            double subsidyAmount) {
        BankingHardshipScanPolicy scanPolicy =
                new BankingHardshipScanPolicy(hardshipThreshold, subsidyAmount);

        List<BankingHardshipScanResult> results = agents.stream()
                .filter(agent -> agent != null && agent.isActive())
                .map(agent -> agent.runHardshipReliefScan(eventTime, scanPolicy))
                .filter(Objects::nonNull)
                .toList();

        return new BankingHardshipScanSummary(
                eventTime,
                results.size(),
                results.stream()
                        .map(BankingHardshipScanResult::policyAgentName)
                        .distinct()
                        .toList(),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::activeAgentsSeen)
                        .max()
                        .orElse(agents.size()),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::analysedAgents)
                        .sum(),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::hardshipDetections)
                        .sum(),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::subsidiesIssued)
                        .sum(),
                hardshipThreshold,
                subsidyAmount,
                results.stream()
                        .flatMap(result -> result.reliefRecipients().stream())
                        .distinct()
                        .toList(),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::skippedCooldownCount)
                        .sum(),
                results.stream()
                        .mapToInt(BankingHardshipScanResult::insufficientFundCount)
                        .sum());
    }

    private List<BankingAccountView> resolveAgentAccounts(BankingRuntimeAgentAccess agent) {
        if (agent == null || agent.bankingCustomerId() == null) {
            return List.of();
        }

        List<BankingAccountView> statusSnapshot = sanitizeAccounts(agent.statusAccountViewsSnapshot());
        if (!statusSnapshot.isEmpty()) {
            return statusSnapshot;
        }

        List<BankingAccountView> accounts = sanitizeAccounts(bank.getAccountViewsForCustomer(agent.bankingCustomerId()));
        if (!accounts.isEmpty()) {
            return accounts;
        }

        return sanitizeAccounts(agent.originatedAccountViewsSnapshot());
    }

    private List<BankingAccountView> sanitizeAccounts(List<BankingAccountView> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(account -> account != null)
                .toList();
    }
}
