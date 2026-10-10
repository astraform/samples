package banking.simulator.domain.banking;

import java.util.List;

public record BankingDepositRateSelectionRule(
        String applicationFrequency,
        List<BankingDepositRateTierSelectionRule> tiers) {
}
