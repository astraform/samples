package banking.simulator.domain.banking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BankingAccountSemantics {

    private BankingAccountSemantics() {
    }

    public static Map<String, Object> buildBalancesSnapshot(List<BankingAccountView> accounts) {
        Map<String, Object> balances = new LinkedHashMap<>();
        if (accounts == null) {
            return balances;
        }
        for (BankingAccountView account : accounts) {
            if (account == null) {
                continue;
            }
            double balance = safeAmount(account.balance());
            if (account.id() != null) {
                balances.put(String.valueOf(account.id()), balance);
            }
            if (hasText(account.name())) {
                balances.putIfAbsent(account.name().trim(), balance);
            }
        }
        return balances;
    }

    public static double calculateAvailableToSpend(BankingAccountView account) {
        if (account == null) {
            return 0.0;
        }
        double balance = safeAmount(account.balance());
        if (!isCreditAccount(account)) {
            return Math.max(0.0, balance);
        }
        return Math.max(0.0, safeAmount(account.limit()) + balance);
    }

    public static boolean isCreditAccount(BankingAccountView account) {
        return account != null && account.limit() != null && account.limit() > 0.0;
    }

    public static double currentDebt(BankingAccountView account) {
        return Math.max(0.0, -safeAmount(account != null ? account.balance() : null));
    }

    public static boolean hasSufficientFunds(Double availableBalance, Double requiredAmount) {
        return safeAmount(availableBalance) >= safeAmount(requiredAmount);
    }

    static double safeAmount(Double value) {
        return value != null ? value : 0.0;
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
