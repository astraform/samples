package banking.simulator.domain.banking;

public record BankingDepositRateTierSelectionRule(
        String name,
        Double minimumValue,
        Double maximumValue) {
}
