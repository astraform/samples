package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BankingProductSemanticsTest {

    @Test
    void resolveInitialCreditLimitPrefersExplicitPositiveLimit() {
        assertEquals(7_500.0, BankingProductSemantics.resolveInitialCreditLimit("CC001", 7_500.0));
    }

    @Test
    void resolveInitialCreditLimitFallsBackByKnownProductCode() {
        assertEquals(10_000.0, BankingProductSemantics.resolveInitialCreditLimit("CC001", null));
        assertEquals(5_000.0, BankingProductSemantics.resolveInitialCreditLimit("CC002", null));
        assertEquals(5_000.0, BankingProductSemantics.resolveInitialCreditLimit("CC999", null));
    }

    @Test
    void resolveInitialCreditLimitTreatsNonPositiveExplicitLimitAsMissing() {
        assertEquals(5_000.0, BankingProductSemantics.resolveInitialCreditLimit("CC002", 0.0));
        assertEquals(5_000.0, BankingProductSemantics.resolveInitialCreditLimit("CC002", -100.0));
    }

    @Test
    void resolveAccountKindMapsKnownProductCategories() {
        assertEquals(BankingProductAccountKind.CREDIT,
                BankingProductSemantics.resolveAccountKind("CRED_AND_CHRG_CARDS"));
        assertEquals(BankingProductAccountKind.TRANSACTION,
                BankingProductSemantics.resolveAccountKind("TRANS_AND_SAVINGS_ACCOUNTS"));
        assertEquals(BankingProductAccountKind.TERM_DEPOSIT,
                BankingProductSemantics.resolveAccountKind("TERM_DEPOSITS"));
        assertEquals(BankingProductAccountKind.LOAN,
                BankingProductSemantics.resolveAccountKind("PERS_LOANS"));
        assertEquals(BankingProductAccountKind.LOAN,
                BankingProductSemantics.resolveAccountKind("RESIDENTIAL_MORTGAGES"));
    }

    @Test
    void resolveAccountKindRejectsUnknownCategory() {
        assertThrows(IllegalArgumentException.class,
                () -> BankingProductSemantics.resolveAccountKind("UNKNOWN"));
    }
}
