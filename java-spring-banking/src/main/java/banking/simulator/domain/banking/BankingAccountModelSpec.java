package banking.simulator.domain.banking;

public record BankingAccountModelSpec(
        BankingAccountModelKind kind,
        BankingProductView product,
        Long accountNumber,
        Double balance,
        Double creditLimit,
        Integer bsb,
        String currency,
        String productCode) {
}
