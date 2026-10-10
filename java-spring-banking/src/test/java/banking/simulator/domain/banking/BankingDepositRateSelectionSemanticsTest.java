package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BankingDepositRateSelectionSemanticsTest {

    @Test
    void matchesWhenFrequencyAmountAndTermAllAlign() {
        BankingDepositRateSelectionRule rule = new BankingDepositRateSelectionRule(
                "MONTHLY",
                List.of(
                        new BankingDepositRateTierSelectionRule("amount", 1_000.0, 5_000.0),
                        new BankingDepositRateTierSelectionRule("term", 12.0, 24.0)));
        BankingTermDepositSelectionInput input = new BankingTermDepositSelectionInput(
                "MONTHLY",
                1_500.0,
                "P1Y");

        assertTrue(BankingDepositRateSelectionSemantics.matches(rule, input));
    }

    @Test
    void rejectsWhenFrequencyOrTiersDoNotMatch() {
        BankingDepositRateSelectionRule rule = new BankingDepositRateSelectionRule(
                "MONTHLY",
                List.of(
                        new BankingDepositRateTierSelectionRule("amount", 1_000.0, 5_000.0),
                        new BankingDepositRateTierSelectionRule("term", 12.0, 24.0)));

        assertFalse(BankingDepositRateSelectionSemantics.matches(
                rule,
                new BankingTermDepositSelectionInput("ANNUALLY", 1_500.0, "P1Y")));
        assertFalse(BankingDepositRateSelectionSemantics.matches(
                rule,
                new BankingTermDepositSelectionInput("MONTHLY", 500.0, "P1Y")));
        assertFalse(BankingDepositRateSelectionSemantics.matches(
                rule,
                new BankingTermDepositSelectionInput("MONTHLY", 1_500.0, "P3Y")));
    }

    @Test
    void treatsBlankOrInvalidTermAsNonMatchingForTermTier() {
        BankingDepositRateSelectionRule rule = new BankingDepositRateSelectionRule(
                "MONTHLY",
                List.of(new BankingDepositRateTierSelectionRule("term", 12.0, 24.0)));

        assertFalse(BankingDepositRateSelectionSemantics.matches(
                rule,
                new BankingTermDepositSelectionInput("MONTHLY", 1_500.0, null)));
        assertFalse(BankingDepositRateSelectionSemantics.matches(
                rule,
                new BankingTermDepositSelectionInput("MONTHLY", 1_500.0, "not-a-term")));
    }

    @Test
    void allowsRulesWithoutExplicitTiers() {
        BankingDepositRateSelectionRule rule = new BankingDepositRateSelectionRule("MONTHLY", List.of());
        BankingTermDepositSelectionInput input = new BankingTermDepositSelectionInput("MONTHLY", 100.0, "P6M");

        assertTrue(BankingDepositRateSelectionSemantics.matches(rule, input));
    }

    @Test
    void firstMatchingIndexReturnsFirstMatchingRule() {
        List<BankingDepositRateSelectionRule> rules = List.of(
                new BankingDepositRateSelectionRule(
                        "MONTHLY",
                        List.of(new BankingDepositRateTierSelectionRule("amount", 10_000.0, 20_000.0))),
                new BankingDepositRateSelectionRule(
                        "MONTHLY",
                        List.of(new BankingDepositRateTierSelectionRule("amount", 1_000.0, 5_000.0))));

        assertEquals(1, BankingDepositRateSelectionSemantics.firstMatchingIndex(
                rules,
                new BankingTermDepositSelectionInput("MONTHLY", 1_500.0, "P1Y")).orElseThrow());
    }
}
