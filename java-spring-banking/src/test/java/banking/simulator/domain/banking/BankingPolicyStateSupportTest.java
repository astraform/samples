package banking.simulator.domain.banking;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BankingPolicyStateSupportTest {

    @Test
    void from_shouldProjectBalancesAndProjectionFactsIntoPolicyState() {
        BankingPolicyStateAccess access = new BankingPolicyStateAccess() {
            @Override
            public String stableMemoryKey() {
                return "customer:42";
            }

            @Override
            public String displayAgentId() {
                return "42";
            }

            @Override
            public List<BankingAccountView> statusAccountViewsSnapshot() {
                return List.of(
                        new BankingAccountView(1L, "Everyday", "TX001", 1200.0, null, null, "AUD"),
                        new BankingAccountView(2L, "Card", "CC001", -300.0, 2000.0, null, "AUD"));
            }

            @Override
            public Integer missedPaymentsLast3m() {
                return 2;
            }

            @Override
            public Boolean hardshipFlag() {
                return true;
            }

            @Override
            public Instant lastOutreachDate() {
                return Instant.parse("2026-04-01T00:00:00Z");
            }
        };

        BankingPolicyState state =
                BankingPolicyStateSupport.from(access, ZonedDateTime.parse("2026-04-06T00:00:00Z"));

        assertEquals("42", state.getAgentId());
        assertEquals(1200.0, state.getCurrentBalance());
        assertEquals(0.15, state.getCreditUtilisation(), 0.0001);
        assertEquals(2, state.getMissedPaymentsLast3m());
        assertEquals(5, state.getDaysSinceLastOutreach());
        assertEquals(true, state.isHardshipFlag());
    }

    @Test
    void from_shouldDefaultSafelyWhenAccessMissing() {
        BankingPolicyState state = BankingPolicyStateSupport.from(null, ZonedDateTime.now());

        assertEquals(0.0, state.getCurrentBalance());
        assertEquals(0.0, state.getCreditUtilisation());
        assertEquals(0, state.getMissedPaymentsLast3m());
        assertFalse(state.isHardshipFlag());
        assertEquals(-1, state.getDaysSinceLastOutreach());
    }
}
