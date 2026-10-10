package banking.simulator.domain.banking;

public record BankingProductView(
        Long id,
        String productCode,
        String name,
        String description,
        String category,
        java.util.List<BankingDepositRateView> depositRates) {
}
