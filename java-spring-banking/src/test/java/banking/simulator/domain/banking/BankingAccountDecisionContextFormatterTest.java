package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BankingAccountDecisionContextFormatterTest {

    @Test
    void renderPromptSection_includesSpendableFieldsForCashAndCreditAccounts() {
        BankingAccountView cash = new BankingAccountView(101L, "Everyday", "TX001", 250.0, null, 624001, "AUD");
        BankingAccountView credit = new BankingAccountView(202L, "Rewards Card", "CC001", -1200.0, 5000.0, null, "AUD");

        String rendered = BankingAccountDecisionContextFormatter.renderPromptSection(List.of(cash, credit));

        assertTrue(rendered.contains("Everyday (#101, TX001): balance=$250.00, spendCapacity=CASH, availableToSpend=$250.00"));
        assertTrue(rendered.contains("Rewards Card (#202, CC001): balance=$-1200.00, spendCapacity=CREDIT, availableToSpend=$3800.00"));
        assertTrue(rendered.contains("creditLimit=$5000.00, availableCredit=$3800.00, currentDebt=$1200.00"));
        assertTrue(rendered.contains("Use `availableToSpend` as the affordability guardrail"));
    }

    @Test
    void calculateAvailableToSpend_matchesChannelCreditSemantics() {
        BankingAccountView cash = new BankingAccountView(101L, "Everyday", "TX001", 125.0, null, 624001, "AUD");
        BankingAccountView credit = new BankingAccountView(202L, "Rewards Card", "CC001", -400.0, 2000.0, null, "AUD");

        assertEquals(125.0, BankingAccountSemantics.calculateAvailableToSpend(cash));
        assertEquals(1600.0, BankingAccountSemantics.calculateAvailableToSpend(credit));
    }

    @Test
    void portfolioMetrics_andSelectors_followBankingSemantics() {
        BankingAccountView cash = new BankingAccountView(101L, "Everyday", "TX001", 750.0, null, 624001, "AUD");
        BankingAccountView savings = new BankingAccountView(202L, "Online Saver", "SAV001", 3200.0, null, 624001, "AUD");
        BankingAccountView credit = new BankingAccountView(303L, "Rewards Card", "CC001", -1200.0, 5000.0, null, "AUD");
        BankingAccountView homeLoan = new BankingAccountView(404L, "Mortgage", "HL001", -350000.0, null, null, "AUD");
        List<BankingAccountView> accounts = List.of(cash, savings, credit, homeLoan);

        assertEquals(-346050.0, BankingPortfolioMetrics.totalLiquidBalance(accounts));
        assertEquals(0.24, BankingPortfolioMetrics.creditUtilisation(accounts), 0.0001);
        assertEquals(cash, BankingAccountSelectors.selectPrimaryCashAccount(accounts));
        assertEquals(savings, BankingAccountSelectors.selectLargestSavingsAccount(accounts));
        assertEquals(homeLoan, BankingAccountSelectors.selectHighestPriorityDebtAccount(accounts));
        assertTrue(BankingProductSemantics.isSavingsProduct("sav001"));
        assertTrue(BankingProductSemantics.isDebtProduct("cc001"));
    }
}
