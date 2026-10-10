package banking.simulator.domain.banking;

public record BankingMerchantProfile(
        String name,
        String description,
        String category,
        String segment,
        String currency,
        String productCode,
        Integer bsb) {

    public BankingMerchantProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("merchant name is required");
        }
    }
}
