package banking.simulator.domain.banking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankingControlEventSemanticsTest {

    private final BankingControlEventSemantics semantics = new BankingControlEventSemantics();

    @Test
    void findAffectedAccounts_filtersByProductCodeCaseInsensitively() {
        List<BankingAccountView> affected = semantics.findAffectedAccounts(List.of(
                new BankingAccountView(1L, "Saver", "TS001", 1000.0, null, null, "AUD"),
                new BankingAccountView(2L, "Card", "CC001", -200.0, 5000.0, null, "AUD")),
                "ts001");

        assertEquals(1, affected.size());
        assertEquals("Saver", affected.getFirst().name());
    }

    @Test
    void formatFraudAlertImpactMessage_includesCustomMessageAndAccountLimit() {
        String message = semantics.formatFraudAlertImpactMessage(
                List.of(new BankingAccountView(2L, "Card", "CC001", -200.0, 5000.0, null, "AUD")),
                "CC001",
                "HIGH",
                "CARD",
                "Suspicious ATM cash-out pattern");

        assertTrue(message.contains("Suspicious ATM cash-out pattern"));
        assertTrue(message.contains("limit $5000.00"));
        assertTrue(message.contains("balance $-200.00"));
    }

    @Test
    void normalizeFraudSeverity_rejectsInvalidSeverity() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> semantics.normalizeFraudSeverity("urgent"));

        assertEquals("severity must be one of LOW, MEDIUM, HIGH, CRITICAL", exception.getMessage());
    }
}
