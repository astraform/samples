package banking.simulator.domain.banking;

public record BankingSeedAccountSpec(
        String name,
        String productCode,
        Double balance,
        Double limit,
        Integer bsb,
        String currency,
        String fromAccountId,
        Double investAmount,
        String term,
        String interestPaymentFrequency) {

    public BankingSeedAccountSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode is required");
        }
    }

    public BankingSeedAccountSpec withFundingDetails(
            Double updatedBalance,
            String updatedFromAccountId,
            Double updatedInvestAmount,
            String updatedTerm,
            String updatedInterestPaymentFrequency) {
        return new BankingSeedAccountSpec(
                name,
                productCode,
                updatedBalance,
                limit,
                bsb,
                currency,
                updatedFromAccountId,
                updatedInvestAmount,
                updatedTerm,
                updatedInterestPaymentFrequency);
    }
}
