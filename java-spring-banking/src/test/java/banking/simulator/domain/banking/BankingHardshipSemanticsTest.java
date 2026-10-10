package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BankingHardshipSemanticsTest {

    @Test
    void subsidyEligibility_excludesDebtAndCardLikeAccounts() {
        BankingAccountView everyday = new BankingAccountView(101L, "Everyday Account", "SA001", 50.0, null, 624001, "AUD");
        BankingAccountView creditCard = new BankingAccountView(202L, "Rewards Card", "CC001", -400.0, 2000.0, null, "AUD");
        BankingAccountView mortgage = new BankingAccountView(303L, "Home Mortgage", "HL001", -200000.0, null, null, "AUD");
        BankingAccountView genericLoan = new BankingAccountView(404L, "Emergency Buffer", "LN001", 300.0, null, null, "AUD");

        assertTrue(BankingHardshipSemantics.isSubsidyEligibleAccount(everyday));
        assertFalse(BankingHardshipSemantics.isSubsidyEligibleAccount(creditCard));
        assertFalse(BankingHardshipSemantics.isSubsidyEligibleAccount(mortgage));
        assertFalse(BankingHardshipSemantics.isSubsidyEligibleAccount(genericLoan));
    }

    @Test
    void hardshipAggregates_andTermDepositDetection_followBankingRules() {
        BankingAccountView primary = new BankingAccountView(101L, "Everyday Account", "SA001", 50.0, null, 624001, "AUD");
        BankingAccountView saver = new BankingAccountView(202L, "Online Saver", "TS001", 125.0, null, 624001, "AUD");
        BankingAccountView card = new BankingAccountView(303L, "Rewards Card", "CC001", -400.0, 2000.0, null, "AUD");
        List<BankingAccountView> accounts = List.of(primary, saver, card);

        assertEquals(175.0, BankingHardshipSemantics.totalSubsidyEligibleBalance(accounts));
        assertSame(primary, BankingHardshipSemantics.lowestBalanceSubsidyEligibleAccount(accounts));
        assertTrue(BankingProductSemantics.isTermDepositProduct("td001"));
        assertFalse(BankingProductSemantics.isTermDepositProduct("sav001"));
    }
}
