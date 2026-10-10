package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BankingAccountSemanticsTest {

    @Test
    void hasSufficientFundsUsesSafeAmountDefaults() {
        assertTrue(BankingAccountSemantics.hasSufficientFunds(100.0, 25.0));
        assertTrue(BankingAccountSemantics.hasSufficientFunds(null, null));
        assertFalse(BankingAccountSemantics.hasSufficientFunds(50.0, 75.0));
    }
}
