package banking.simulator.domain.banking;

import java.util.List;
import java.util.Locale;

public final class BankingAccountDecisionContextFormatter {

    private BankingAccountDecisionContextFormatter() {
    }

    public static String renderPromptSection(List<BankingAccountView> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return "Account decision context:\n- none";
        }

        StringBuilder builder = new StringBuilder("Account decision context:\n");
        for (BankingAccountView account : accounts) {
            if (account == null) {
                continue;
            }
            builder.append("- ")
                    .append(renderAccountLine(account))
                    .append("\n");
        }
        builder.append("Interpretation rules:\n")
                .append("- Use `availableToSpend` as the affordability guardrail for new debit or spend decisions.\n")
                .append("- For credit accounts, raw `balance` may be negative because it represents debt already used; rely on `availableCredit` and `availableToSpend` instead of raw balance alone.");
        return builder.toString().trim();
    }

    public static String renderAccountLine(BankingAccountView account) {
        if (account == null) {
            return "unknown account";
        }

        String accountName = BankingAccountSemantics.hasText(account.name()) ? account.name().trim() : "Account";
        String productCode = BankingAccountSemantics.hasText(account.productCode()) ? account.productCode().trim() : "?";
        String accountId = account.id() != null ? "#" + account.id() : "#?";
        double balance = BankingAccountSemantics.safeAmount(account.balance());
        double limit = BankingAccountSemantics.safeAmount(account.limit());
        boolean creditAccount = BankingAccountSemantics.isCreditAccount(account);
        double availableCredit = creditAccount ? BankingAccountSemantics.calculateAvailableToSpend(account) : 0.0;
        double availableToSpend = creditAccount ? availableCredit : Math.max(0.0, balance);
        String spendCapacity = creditAccount ? "CREDIT" : "CASH";

        StringBuilder builder = new StringBuilder(String.format(
                Locale.ROOT,
                "%s (%s, %s): balance=$%.2f, spendCapacity=%s, availableToSpend=$%.2f",
                accountName,
                accountId,
                productCode,
                balance,
                spendCapacity,
                availableToSpend));

        if (creditAccount) {
            builder.append(String.format(
                    Locale.ROOT,
                    ", creditLimit=$%.2f, availableCredit=$%.2f, currentDebt=$%.2f",
                    limit,
                    availableCredit,
                    BankingAccountSemantics.currentDebt(account)));
        }

        return builder.toString();
    }
}
