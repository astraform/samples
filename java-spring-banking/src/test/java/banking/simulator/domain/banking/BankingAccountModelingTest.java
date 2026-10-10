package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BankingAccountModelingTest {

    @Test
    void mapsCreditAndTransactionProductsToModelSpecs() {
        BankingAccountView creditAccount = new BankingAccountView(101L, "Rewards Card", "CC001", -400.0, 2000.0, null, "AUD");
        BankingProductView creditProduct = new BankingProductView(
                11L,
                "CC001",
                "Rewards Card",
                "Credit card",
                "CRED_AND_CHRG_CARDS",
                java.util.List.of());

        BankingAccountModelSpec creditSpec = BankingAccountModeling.toModelSpec(creditAccount, creditProduct, 123456, "AUD");
        assertEquals(BankingAccountModelKind.CREDIT, creditSpec.kind());
        assertEquals(2000.0, creditSpec.creditLimit());
        assertEquals(null, creditSpec.bsb());

        BankingAccountView txAccount = new BankingAccountView(202L, "Everyday", "TX001", 1500.0, null, 654321, "NZD");
        BankingProductView txProduct = new BankingProductView(
                22L,
                "TX001",
                "Everyday",
                "Cash account",
                "TRANS_AND_SAVINGS_ACCOUNTS",
                java.util.List.of());

        BankingAccountModelSpec txSpec = BankingAccountModeling.toModelSpec(txAccount, txProduct, 654321, "NZD");
        assertEquals(BankingAccountModelKind.TRANSACTION, txSpec.kind());
        assertEquals(654321, txSpec.bsb());
        assertEquals("NZD", txSpec.currency());
        assertTrue(BankingAccountModeling.isFundingCandidate(txProduct));
        assertFalse(BankingAccountModeling.isFundingCandidate(creditProduct));
    }
}
