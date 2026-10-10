package banking.simulator.domain.banking;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BankingAccountSelectors {

    private BankingAccountSelectors() {
    }

    public static BankingAccountView selectPrimaryCashAccount(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return null;
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .filter(account -> !BankingProductSemantics.isDebtProduct(account.productCode()))
                .filter(account -> !BankingProductSemantics.isSavingsProduct(account.productCode()))
                .filter(account -> BankingAccountSemantics.safeAmount(account.balance()) > 0.0)
                .max(Comparator.comparingDouble(account -> BankingAccountSemantics.safeAmount(account.balance())))
                .orElse(null);
    }

    public static BankingAccountView selectLargestSavingsAccount(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return null;
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .filter(account -> BankingProductSemantics.isSavingsProduct(account.productCode()))
                .filter(account -> BankingAccountSemantics.safeAmount(account.balance()) > 0.0)
                .max(Comparator.comparingDouble(account -> BankingAccountSemantics.safeAmount(account.balance())))
                .orElse(null);
    }

    public static BankingAccountView selectHighestPriorityDebtAccount(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return null;
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .filter(account -> BankingProductSemantics.isDebtProduct(account.productCode())
                        || BankingAccountSemantics.safeAmount(account.balance()) < 0.0)
                .filter(account -> Math.abs(BankingAccountSemantics.safeAmount(account.balance())) > 0.0)
                .max(Comparator.comparingDouble(account -> Math.abs(Math.min(
                        0.0,
                        BankingAccountSemantics.safeAmount(account.balance())))))
                .orElse(null);
    }
}
