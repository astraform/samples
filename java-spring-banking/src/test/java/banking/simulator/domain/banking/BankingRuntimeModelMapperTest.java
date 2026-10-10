package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.Merchant;
import banking.simulator.domain.banking.model.TransactionAccount;
import org.junit.jupiter.api.Test;

class BankingRuntimeModelMapperTest {

    @Test
    void mapsCreditAccountSpecIntoRuntimeModel() {
        BankingAccountView account = new BankingAccountView(42L, "Rewards Card", "CC001", -120.0, 5000.0, null, "AUD");
        BankingProductView product = new BankingProductView(7L, "CC001", "Rewards Card", "Card product", "CRED_AND_CHRG_CARDS", java.util.List.of());

        CreditAccount runtime = assertInstanceOf(
                CreditAccount.class,
                BankingRuntimeModelMapper.toRuntimeAccount(account, product, null, "AUD"));

        assertEquals(42L, runtime.getNumber());
        assertEquals(-120.0, runtime.getBalance());
        assertEquals(5000.0, runtime.getCreditLimit());
        assertEquals("CC001", runtime.getProductCode());
        assertEquals("Rewards Card", runtime.getProduct().name());
    }

    @Test
    void mapsMerchantSettlementAccountIntoRuntimeMerchant() {
        BankingAccountView settlement = new BankingAccountView(123L, "Merchant Settlement", "BASIC_TRANSACTION", 99.5, null, 624001, "AUD");
        BankingProductView product = new BankingProductView(
                5L,
                "BASIC_TRANSACTION",
                "Basic Transaction",
                "Settlement account",
                "TRANS_AND_SAVINGS_ACCOUNTS",
                java.util.List.of());

        Merchant merchant = BankingRuntimeModelMapper.toRuntimeMerchant(
                "Cafe",
                88L,
                "Coffee merchant",
                settlement,
                product,
                62401,
                "AUD");

        TransactionAccount settlementAccount = merchant.getSchemeSettlemeAccount();
        assertEquals("Cafe", merchant.getName());
        assertEquals("Coffee merchant", merchant.merchantDescription());
        assertEquals(123L, settlementAccount.getNumber());
        assertEquals(62401, settlementAccount.getBsb());
        assertEquals("Basic Transaction", settlementAccount.getProduct().name());
    }

    @Test
    void mapsMerchantViewIntoRuntimeMerchant() {
        BankingMerchantView merchantView = new BankingMerchantView(
                88L,
                "Cafe",
                "Coffee merchant",
                new BankingAccountView(123L, "Merchant Settlement", "BASIC_TRANSACTION", 99.5, null, 62401, "AUD"),
                new BankingProductView(
                        5L,
                        "BASIC_TRANSACTION",
                        "Basic Transaction",
                        "Settlement account",
                        "TRANS_AND_SAVINGS_ACCOUNTS",
                        java.util.List.of()));

        Merchant merchant = BankingRuntimeModelMapper.toRuntimeMerchant(merchantView);

        TransactionAccount settlementAccount = merchant.getSchemeSettlemeAccount();
        assertEquals("Cafe", merchant.getName());
        assertEquals("Coffee merchant", merchant.merchantDescription());
        assertEquals(123L, settlementAccount.getNumber());
        assertEquals(62401, settlementAccount.getBsb());
        assertEquals("Basic Transaction", settlementAccount.getProduct().name());
    }
}
