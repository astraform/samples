package banking.simulator.domain.banking;

import java.util.List;

public final class BankingPortfolioMetrics {

    private BankingPortfolioMetrics() {
    }

    public static double totalLiquidBalance(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return 0.0;
        }
        return accounts.stream()
                .filter(account -> account != null && !BankingAccountSemantics.isCreditAccount(account))
                .mapToDouble(account -> BankingAccountSemantics.safeAmount(account.balance()))
                .sum();
    }

    public static double creditUtilisation(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return 0.0;
        }
        double totalLimit = 0.0;
        double totalUsed = 0.0;
        for (BankingAccountView account : accounts) {
            if (!BankingAccountSemantics.isCreditAccount(account)) {
                continue;
            }
            double limit = BankingAccountSemantics.safeAmount(account.limit());
            double balance = BankingAccountSemantics.safeAmount(account.balance());
            totalLimit += limit;
            if (balance < 0.0) {
                totalUsed += Math.abs(balance);
            }
        }
        if (totalLimit == 0.0) {
            return 0.0;
        }
        return totalUsed / totalLimit;
    }
}
