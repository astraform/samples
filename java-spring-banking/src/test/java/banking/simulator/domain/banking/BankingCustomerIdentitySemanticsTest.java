package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BankingCustomerIdentitySemanticsTest {

    @Test
    void matchesProvidedIdentityAllowsMissingProvidedFields() {
        assertTrue(BankingCustomerIdentitySemantics.matchesProvidedIdentity(
                "Taylor Example",
                LocalDate.parse("1990-01-20"),
                null,
                null));
    }

    @Test
    void matchesProvidedIdentityRejectsMismatchedNameOrDateOfBirth() {
        assertFalse(BankingCustomerIdentitySemantics.matchesProvidedIdentity(
                "Taylor Example",
                LocalDate.parse("1990-01-20"),
                "Morgan Example",
                null));
        assertFalse(BankingCustomerIdentitySemantics.matchesProvidedIdentity(
                "Taylor Example",
                LocalDate.parse("1990-01-20"),
                "Taylor Example",
                LocalDate.parse("1990-01-21")));
    }
}
