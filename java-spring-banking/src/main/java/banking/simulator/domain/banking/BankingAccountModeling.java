package banking.simulator.domain.banking;

public final class BankingAccountModeling {
    private BankingAccountModeling() {
    }

    public static BankingAccountModelSpec toModelSpec(BankingAccountView account, BankingProductView product) {
        return toModelSpec(account, product, account != null ? account.bsb() : null, account != null ? account.currency() : null);
    }

    public static BankingAccountModelSpec toModelSpec(
            BankingAccountView account,
            BankingProductView product,
            Integer bsb,
            String currency) {
        if (account == null || product == null) {
            throw new IllegalArgumentException("account and product are required");
        }
        BankingAccountModelKind kind = BankingProductSemantics.isCreditCategory(product.category())
                ? BankingAccountModelKind.CREDIT
                : BankingAccountModelKind.TRANSACTION;
        return new BankingAccountModelSpec(
                kind,
                product,
                account.id(),
                account.balance(),
                kind == BankingAccountModelKind.CREDIT ? account.limit() : null,
                kind == BankingAccountModelKind.TRANSACTION ? bsb : null,
                currency == null || currency.isBlank() ? "AUD" : currency,
                BankingProductSemantics.normalizeProductCode(product.productCode()));
    }

    public static boolean isFundingCandidate(BankingProductView product) {
        return product != null && BankingProductSemantics.isFundingCategory(product.category());
    }
}
