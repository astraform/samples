package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.Merchant;
import banking.simulator.domain.banking.model.Product;
import banking.simulator.domain.banking.model.TransactionAccount;
import java.util.List;

public final class BankingRuntimeModelMapper {

    private BankingRuntimeModelMapper() {
    }

    public static Account toRuntimeAccount(
            BankingAccountView account,
            BankingProductView product,
            Integer bsb,
            String currency) {
        return toRuntimeAccount(BankingAccountModeling.toModelSpec(account, product, bsb, currency));
    }

    public static Account toRuntimeAccount(BankingAccountModelSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        Product runtimeProduct = new Product(
                spec.product().id(),
                spec.product().productCode(),
                spec.product().name(),
                spec.product().description());

        Account runtimeAccount;
        if (spec.kind() == BankingAccountModelKind.CREDIT) {
            runtimeAccount = new CreditAccount(
                    runtimeProduct,
                    spec.accountNumber(),
                    spec.balance(),
                    spec.creditLimit(),
                    List.of());
        } else {
            runtimeAccount = new TransactionAccount(
                    runtimeProduct,
                    spec.accountNumber(),
                    spec.balance(),
                    spec.bsb(),
                    spec.currency(),
                    List.of());
        }
        runtimeAccount.setProductCode(spec.productCode());
        return runtimeAccount;
    }

    public static BankingAccountView toAccountView(String accountName, Account account) {
        if (account == null) {
            return null;
        }
        String productCode = BankingProductSemantics.normalizeProductCode(
                account.getProductCode() != null
                        ? account.getProductCode()
                        : account.getProduct() != null ? account.getProduct().code() : null);
        Double limit = account instanceof CreditAccount creditAccount
                ? creditAccount.getCreditLimit()
                : null;
        return new BankingAccountView(
                account.getNumber(),
                accountName,
                productCode,
                account.getBalance(),
                limit,
                account instanceof TransactionAccount transactionAccount ? transactionAccount.getBsb() : null,
                account.getCurrency());
    }

    public static Merchant toRuntimeMerchant(
            String name,
            Long id,
            String description,
            BankingAccountView settlementAccount,
            BankingProductView settlementProduct,
            Integer bsb,
            String currency) {
        Account runtimeAccount = toRuntimeAccount(settlementAccount, settlementProduct, bsb, currency);
        if (!(runtimeAccount instanceof TransactionAccount transactionAccount)) {
            throw new IllegalArgumentException("merchant settlement account must be transaction-like");
        }
        Merchant merchant = new Merchant(name, id, transactionAccount);
        merchant.setMerchantDescription(description);
        return merchant;
    }

    public static Merchant toRuntimeMerchant(BankingMerchantView merchantView) {
        if (merchantView == null) {
            throw new IllegalArgumentException("merchantView is required");
        }
        return toRuntimeMerchant(
                merchantView.name(),
                merchantView.id(),
                merchantView.description(),
                merchantView.settlementAccount(),
                merchantView.settlementProduct(),
                merchantView.settlementAccount().bsb(),
                merchantView.settlementAccount().currency());
    }
}
