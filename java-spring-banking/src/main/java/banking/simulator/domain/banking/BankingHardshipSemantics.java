package banking.simulator.domain.banking;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class BankingHardshipSemantics {

    private BankingHardshipSemantics() {
    }

    public static boolean isSubsidyEligibleAccount(BankingAccountView account) {
        if (account == null || account.balance() == null) {
            return false;
        }
        if (BankingAccountSemantics.isCreditAccount(account)) {
            return false;
        }

        String name = account.name() == null ? "" : account.name().toLowerCase(Locale.ROOT);
        String productCode = BankingProductSemantics.normalizeProductCode(account.productCode());

        if (name.contains("loan") || name.contains("mortgage") || name.contains("credit card")
                || name.contains("card")) {
            return false;
        }
        if (productCode != null
                && (productCode.startsWith("LN") || BankingProductSemantics.isDebtProduct(productCode))) {
            return false;
        }
        return true;
    }

    public static BankingAccountView lowestBalanceSubsidyEligibleAccount(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return null;
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .filter(BankingHardshipSemantics::isSubsidyEligibleAccount)
                .min(Comparator.comparingDouble(account -> BankingAccountSemantics.safeAmount(account.balance())))
                .orElse(null);
    }

    public static double totalSubsidyEligibleBalance(List<BankingAccountView> accounts) {
        if (accounts == null) {
            return 0.0;
        }
        return accounts.stream()
                .filter(Objects::nonNull)
                .filter(BankingHardshipSemantics::isSubsidyEligibleAccount)
                .mapToDouble(account -> BankingAccountSemantics.safeAmount(account.balance()))
                .sum();
    }
}
