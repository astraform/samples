package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BankingProductRateSemanticsTest {

    @Test
    void extractsMaximumDepositRateFromDomainProductView() {
        BankingProductView product = new BankingProductView(
                1L,
                "SAV001",
                "Saver",
                "Savings product",
                "TRANS_AND_SAVINGS_ACCOUNTS",
                List.of(
                        new BankingDepositRateView("INTRODUCTORY", 0.032),
                        new BankingDepositRateView("STANDARD", 0.045)));

        assertEquals(0.045, BankingProductRateSemantics.maxDepositRate(product));
    }

    @Test
    void rendersPromptFriendlyMarketRateLine() {
        BankingProductView product = new BankingProductView(
                1L,
                "SAV001",
                "Saver",
                "Savings product",
                "TRANS_AND_SAVINGS_ACCOUNTS",
                List.of(new BankingDepositRateView("STANDARD", 0.045)));

        String line = BankingProductRateSemantics.renderMarketDataLine("Rainy Day", "SAV001", product);

        assertTrue(line.contains("Rainy Day"));
        assertTrue(line.contains("SAV001"));
        assertTrue(line.contains("STANDARD Rate: 0.045"));
    }
}
