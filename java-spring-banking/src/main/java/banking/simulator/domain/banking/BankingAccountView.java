package banking.simulator.domain.banking;

public record BankingAccountView(
        Long id,
        String name,
        String productCode,
        Double balance,
        Double limit,
        Integer bsb,
        String currency) {
}
