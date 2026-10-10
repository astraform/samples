package banking.simulator.domain.banking;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class BankingProductRateSemantics {

    private BankingProductRateSemantics() {
    }

    public static double maxDepositRate(BankingProductView product) {
        if (product == null || product.depositRates() == null || product.depositRates().isEmpty()) {
            return 0.0;
        }
        return product.depositRates().stream()
                .filter(Objects::nonNull)
                .map(BankingDepositRateView::rate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);
    }

    public static String renderMarketDataLine(String accountName, String productCode, BankingProductView product) {
        String effectiveAccountName = accountName != null ? accountName : "Unknown Account";
        String effectiveProductCode = productCode != null ? productCode : "UNKNOWN";
        List<BankingDepositRateView> rates = product != null && product.depositRates() != null
                ? product.depositRates().stream().filter(Objects::nonNull).toList()
                : List.of();

        StringBuilder line = new StringBuilder(
                String.format(Locale.ROOT, "- Account '%s' (%s): ", effectiveAccountName, effectiveProductCode));
        if (rates.isEmpty()) {
            line.append("No specific rate data.");
            return line.toString();
        }
        for (BankingDepositRateView rate : rates) {
            line.append(String.format(Locale.ROOT, "%s Rate: %s ", rate.rateType(), rate.rate()));
        }
        return line.toString().trim();
    }
}
