package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class BankingExecutionPayloadsTest {

    @Test
    void buildsInternalTransferPayloadWithDebitAndCreditLegs() {
        BankingPostingTarget from = new BankingPostingTarget(10L, 62401, "AUD");
        BankingPostingTarget to = new BankingPostingTarget(20L, 62402, "AUD");
        ZonedDateTime now = ZonedDateTime.parse("2026-04-04T10:30:00+11:00[Australia/Sydney]");

        BankingDoubleEntryPosting transaction = BankingExecutionPayloads.internalTransfer(
                from,
                to,
                125.5,
                now,
                "Savings sweep");

        BankingPostingLeg debit = transaction.debits().getFirst();
        BankingPostingLeg credit = transaction.credits().getFirst();
        assertEquals(10L, debit.accountId());
        assertEquals(62401, debit.bsb());
        assertEquals(20L, credit.accountId());
        assertEquals(62402, credit.bsb());
        assertEquals(125.5, debit.amount());
        assertEquals("Savings sweep", credit.description());
    }

    @Test
    void rejectsInvalidTransferAmount() {
        BankingPostingTarget from = new BankingPostingTarget(10L, null, "AUD");
        BankingPostingTarget to = new BankingPostingTarget(20L, null, "AUD");
        ZonedDateTime now = ZonedDateTime.parse("2026-04-04T10:30:00+11:00[Australia/Sydney]");

        assertThrows(
                IllegalArgumentException.class,
                () -> BankingExecutionPayloads.internalTransfer(from, to, 0.0, now, "bad"));
    }
}
