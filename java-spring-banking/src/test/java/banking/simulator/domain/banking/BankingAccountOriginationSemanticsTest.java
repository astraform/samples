package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BankingAccountOriginationSemanticsTest {

    @Test
    void resolveOpeningCurrencyDefaultsToAudWhenMissing() {
        assertEquals("AUD",
                BankingAccountOriginationSemantics.resolveOpeningCurrency(BankingProductAccountKind.TRANSACTION, null));
        assertEquals("AUD",
                BankingAccountOriginationSemantics.resolveOpeningCurrency(BankingProductAccountKind.CREDIT, " "));
    }

    @Test
    void resolveOpeningCurrencyNormalizesNonTermDepositCurrencies() {
        assertEquals("USD",
                BankingAccountOriginationSemantics.resolveOpeningCurrency(BankingProductAccountKind.LOAN, " usd "));
    }

    @Test
    void resolveOpeningCurrencyPinsTermDepositsToAud() {
        assertEquals("AUD",
                BankingAccountOriginationSemantics.resolveOpeningCurrency(BankingProductAccountKind.TERM_DEPOSIT, "USD"));
    }

    @Test
    void resolveOpeningBsbDefaultsToZero() {
        assertEquals(0, BankingAccountOriginationSemantics.resolveOpeningBsb(null));
        assertEquals(123456, BankingAccountOriginationSemantics.resolveOpeningBsb(123456));
    }

    @Test
    void resolveOpeningDatePrefersRequestedDate() {
        assertEquals(LocalDate.parse("2025-01-31"),
                BankingAccountOriginationSemantics.resolveOpeningDate(
                        LocalDate.parse("2025-01-31"),
                        LocalDate.parse("2026-04-14")));
    }

    @Test
    void resolveOpeningDateFallsBackWhenRequestDateMissing() {
        assertEquals(LocalDate.parse("2026-04-14"),
                BankingAccountOriginationSemantics.resolveOpeningDate(
                        null,
                        LocalDate.parse("2026-04-14")));
    }

    @Test
    void resolveOpeningDateRejectsMissingFallbackWhenRequestDateMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> BankingAccountOriginationSemantics.resolveOpeningDate(null, null));
    }

    @Test
    void resolveInitialCreditStatementWindowStartsAtOpenDateAndRunsForOneMonth() {
        BankingCreditStatementWindow statementWindow = BankingAccountOriginationSemantics
                .resolveInitialCreditStatementWindow(LocalDate.parse("2026-04-14"));

        assertEquals(LocalDate.parse("2026-04-14"), statementWindow.startDate());
        assertEquals(LocalDate.parse("2026-05-14"), statementWindow.endDate());
    }

    @Test
    void resolveInitialTermDepositMaturityInstructionDefaultsToPaidOutAtMaturity() {
        assertEquals(BankingTermDepositMaturityKind.PAID_OUT_AT_MATURITY,
                BankingAccountOriginationSemantics.resolveInitialTermDepositMaturityInstruction());
    }
}
