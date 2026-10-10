package banking.simulator.domain.banking;

import java.util.List;
import java.util.Locale;

public final class BankingControlEventSemantics {

    public List<BankingAccountView> findAffectedAccounts(List<BankingAccountView> accounts, String productCode) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(account -> account != null && account.productCode() != null
                        && productCode.equalsIgnoreCase(account.productCode()))
                .toList();
    }

    public String formatRateImpactMessage(List<BankingAccountView> accounts, String productCode, Double newRate) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 RATE CHANGE ALERT: The interest rate on your ");
        sb.append(productCode.replace("_", " ").toLowerCase(Locale.ROOT));
        sb.append(" product(s) has changed to ").append(String.format(Locale.ROOT, "%.2f%%", newRate)).append(".\n\n");
        sb.append("Your affected accounts:\n");

        double totalBalance = 0.0;
        for (BankingAccountView account : accounts) {
            Double balance = account.balance() != null ? account.balance() : 0.0;
            totalBalance += balance;
            sb.append("  - ").append(account.name())
                    .append(" (#").append(account.id()).append(")")
                    .append(": $").append(String.format(Locale.ROOT, "%.2f", balance)).append("\n");
        }

        double monthlyImpact = totalBalance * (newRate / 100) / 12;
        sb.append("\nEstimated monthly impact: $").append(String.format(Locale.ROOT, "%.2f", Math.abs(monthlyImpact)));
        sb.append(monthlyImpact >= 0 ? " earnings" : " in interest");
        return sb.toString();
    }

    public String formatCreditLimitImpactMessage(
            List<BankingAccountView> accounts,
            String productCode,
            Double newLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("💳 CREDIT LIMIT CHANGE ALERT: The credit line on your ");
        sb.append(productCode.replace("_", " ").toLowerCase(Locale.ROOT));
        sb.append(" product(s) is now $").append(String.format(Locale.ROOT, "%.2f", newLimit)).append(".\n\n");
        sb.append("Your affected accounts:\n");

        double totalAvailableCredit = 0.0;
        for (BankingAccountView account : accounts) {
            Double balance = account.balance() != null ? account.balance() : 0.0;
            Double limit = account.limit() != null ? account.limit() : newLimit;
            double used = balance < 0 ? Math.abs(balance) : 0.0;
            double available = limit - used;
            totalAvailableCredit += available;

            sb.append("  - ").append(account.name())
                    .append(" (#").append(account.id()).append(")")
                    .append(": limit $").append(String.format(Locale.ROOT, "%.2f", limit))
                    .append(", available $").append(String.format(Locale.ROOT, "%.2f", available)).append("\n");
        }

        sb.append("\nEstimated available credit across impacted accounts: $")
                .append(String.format(Locale.ROOT, "%.2f", totalAvailableCredit));
        return sb.toString();
    }

    public String formatFraudAlertImpactMessage(
            List<BankingAccountView> accounts,
            String productCode,
            String severity,
            String channel,
            String customMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ FRAUD ALERT (").append(severity).append("): ");
        if (hasText(customMessage)) {
            sb.append(customMessage.trim());
        } else {
            sb.append("Suspicious ").append(channel.toLowerCase(Locale.ROOT))
                    .append(" activity has been detected on your ")
                    .append(productCode.replace("_", " ").toLowerCase(Locale.ROOT))
                    .append(" account(s). Review recent activity and lock the channel if required.");
        }
        sb.append("\n\nAffected accounts:\n");

        for (BankingAccountView account : accounts) {
            Double balance = account.balance() != null ? account.balance() : 0.0;
            sb.append("  - ").append(account.name())
                    .append(" (#").append(account.id()).append(")")
                    .append(": balance $").append(String.format(Locale.ROOT, "%.2f", balance));
            if (account.limit() != null) {
                sb.append(", limit $").append(String.format(Locale.ROOT, "%.2f", account.limit()));
            }
            sb.append("\n");
        }

        sb.append(
                "\nRecommended action: verify recent transactions, re-authenticate the customer, and block the channel if fraud is confirmed.");
        return sb.toString();
    }

    public String normalizeFraudSeverity(String severity) {
        String normalized = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> throw new IllegalArgumentException("severity must be one of LOW, MEDIUM, HIGH, CRITICAL");
        };
    }

    public String normalizeFraudChannel(String channel) {
        String normalized = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
