package banking.simulator.domain.banking;

public record BankingMerchantView(
        Long id,
        String name,
        String description,
        BankingAccountView settlementAccount,
        BankingProductView settlementProduct) {

    public BankingMerchantView {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("merchant name is required");
        }
        if (settlementAccount == null) {
            throw new IllegalArgumentException("settlementAccount is required");
        }
    }
}
