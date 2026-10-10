package banking.simulator.domain.banking;

import java.util.List;

public final class BankingRuntimeAccountLoadSupport {

    private BankingRuntimeAccountLoadSupport() {
    }

    public static List<BankingAccountView> loadLiveAccounts(BankingRuntimeAccountLoadAccess access) {
        if (access == null || access.bankingCustomerId() == null || access.runtimeGateway() == null) {
            return List.of();
        }
        try {
            return sanitize(access.runtimeGateway().getAccountViewsForCustomer(access.bankingCustomerId()));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<BankingAccountView> loadLiveOrStatusAccounts(BankingRuntimeAccountLoadAccess access) {
        List<BankingAccountView> liveAccounts = loadLiveAccounts(access);
        if (!liveAccounts.isEmpty()) {
            return liveAccounts;
        }
        return sanitize(access != null ? access.statusAccountViewsSnapshot() : null);
    }

    public static List<BankingAccountView> loadLiveOrOriginatedAccounts(BankingRuntimeAccountLoadAccess access) {
        List<BankingAccountView> liveAccounts = loadLiveAccounts(access);
        if (!liveAccounts.isEmpty()) {
            return liveAccounts;
        }
        return sanitize(access != null ? access.originatedAccountViewsSnapshot() : null);
    }

    public static List<String> preferredAccountNames(BankingRuntimeAccountLoadAccess access) {
        return loadLiveAccounts(access).stream()
                .filter(account -> account != null
                        && account.name() != null
                        && !account.name().isBlank())
                .sorted((left, right) -> Boolean.compare(
                        BankingAccountSemantics.isCreditAccount(left),
                        BankingAccountSemantics.isCreditAccount(right)))
                .map(BankingAccountView::name)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<BankingAccountView> sanitize(List<BankingAccountView> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(account -> account != null)
                .toList();
    }
}
