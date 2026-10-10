package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankingAccountOriginationSupportTest {

    @Test
    void normalizeSeedAccount_populatesFundingForTermDeposit() {
        BankingSeedAccountSpec normalized = BankingAccountOriginationSupport.normalizeSeedAccount(
                new BankingSeedAccountSpec(
                        "Rainy Day TD",
                        "TD001",
                        2_000.0,
                        null,
                        null,
                        "AUD",
                        null,
                        null,
                        null,
                        null),
                List.of(new BankingAccountView(55L, "Everyday", "TS001", 8_500.0, null, 111111, "AUD")));

        assertEquals("55", normalized.fromAccountId());
        assertEquals(2_000.0, normalized.investAmount());
        assertEquals(0.0, normalized.balance());
        assertEquals("12", normalized.term());
        assertEquals("P1Y", normalized.interestPaymentFrequency());
    }

    @Test
    void normalizeSeedAccount_rejectsUnfundedTermDeposit() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> BankingAccountOriginationSupport.normalizeSeedAccount(
                        new BankingSeedAccountSpec(
                                "Rainy Day TD",
                                "TD001",
                                null,
                                null,
                                null,
                                "AUD",
                                null,
                                null,
                                null,
                                null),
                        List.of()));

        assertTrue(error.getMessage().contains("requires a transaction/savings account before it"));
    }

    @Test
    void toOpeningRequest_carriesCustomerIdentityAndSeedFields() {
        BankingAccountOpeningRequest request = BankingAccountOriginationSupport.toOpeningRequest(
                42L,
                new BankingCustomerProfile(
                        "Test Persona",
                        "Budget-conscious professional",
                        LocalDate.parse("1992-04-05")),
                new BankingSeedAccountSpec(
                        "Everyday",
                        "TS001",
                        500.0,
                        null,
                        111111,
                        "AUD",
                        null,
                        null,
                        null,
                        null));

        assertEquals(42L, request.customerId());
        assertEquals("Test Persona", request.customerFullName());
        assertEquals("Budget-conscious professional", request.customerPersona());
        assertEquals(LocalDate.parse("1992-04-05"), request.customerDateOfBirth());
        assertEquals("Everyday", request.name());
        assertEquals("TS001", request.productCode());
        assertEquals(500.0, request.balance());
    }
}
