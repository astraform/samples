package banking.simulator.domain.banking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BankingRuntimeProjectionSupport {
    private BankingRuntimeProjectionSupport() {
    }

    public static Optional<Long> bankingCustomerId(BankingRuntimeProjectionAccess access) {
        return access == null ? Optional.empty() : Optional.ofNullable(access.bankingCustomerId());
    }

    public static Map<String, Object> runtimeMetadata(BankingRuntimeProjectionAccess access) {
        return bankingCustomerId(access)
                .<Map<String, Object>>map(customerId -> Map.of("bankingCustomerId", customerId))
                .orElseGet(Map::of);
    }

    public static List<String> a2aKnownContextFacts(BankingRuntimeProjectionAccess access) {
        if (access == null || access.bankingCustomerId() == null) {
            return List.of();
        }

        List<String> facts = new ArrayList<>();
        facts.add("bankingCustomerId=" + access.bankingCustomerId());
        List<BankingAccountView> accounts = access.statusAccountViewsSnapshot();
        if (accounts == null || accounts.isEmpty()) {
            facts.add("accounts=none");
            return facts;
        }

        String accountSummary = accounts.stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(
                        account -> Optional.ofNullable(account.id()).orElse(Long.MAX_VALUE)))
                .limit(6)
                .map(BankingRuntimeProjectionSupport::formatAccountFact)
                .filter(BankingRuntimeProjectionSupport::hasText)
                .collect(java.util.stream.Collectors.joining(" | "));
        facts.add(hasText(accountSummary)
                ? "accounts=" + accountSummary
                : "accounts=unavailable");
        return facts;
    }

    public static Map<String, Object> eventBalancesSnapshot(BankingRuntimeProjectionAccess access) {
        if (access == null || access.bankingCustomerId() == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = access.balancesSnapshot();
        return snapshot == null || snapshot.isEmpty() ? Map.of() : new LinkedHashMap<>(snapshot);
    }

    private static String formatAccountFact(BankingAccountView account) {
        List<String> parts = new ArrayList<>();
        if (account.id() != null) {
            parts.add("id=" + account.id());
        }
        if (hasText(account.productCode())) {
            parts.add("productCode=" + account.productCode());
        }
        if (hasText(account.name())) {
            parts.add("name=" + account.name());
        }
        if (account.balance() != null) {
            parts.add("balance=" + String.format(Locale.ROOT, "%.2f", account.balance()));
        }
        if (hasText(account.currency())) {
            parts.add("currency=" + account.currency());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
