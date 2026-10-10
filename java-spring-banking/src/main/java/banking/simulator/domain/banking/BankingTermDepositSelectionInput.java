package banking.simulator.domain.banking;

public record BankingTermDepositSelectionInput(
        String interestPaymentFrequency,
        Double investAmount,
        String term) {
}
