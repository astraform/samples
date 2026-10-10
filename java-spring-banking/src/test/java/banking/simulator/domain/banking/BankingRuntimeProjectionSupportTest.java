package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingRuntimeProjectionSupportTest {

    @Test
    void a2aKnownContextFactsSummarizesCustomerAccounts() {
        BankingRuntimeProjectionAccess access = new FakeProjectionAccess(
                42L,
                List.of(new BankingAccountView(7L, "Everyday", "TX001", 2500.0, null, null, "AUD")),
                Map.of("7", 2500.0),
                new BankingRuntimeStateSnapshot("agent-1", 2500.0, 0.0, 0, false, 4));

        List<String> facts = BankingRuntimeProjectionSupport.a2aKnownContextFacts(access);

        assertEquals("bankingCustomerId=42", facts.getFirst());
        assertTrue(facts.get(1).contains("productCode=TX001"));
        assertTrue(facts.get(1).contains("balance=2500.00"));
    }

    @Test
    void runtimeMetadataAndBalanceSnapshotUseCustomerIdentity() {
        BankingRuntimeProjectionAccess access = new FakeProjectionAccess(
                42L,
                List.of(),
                Map.of("7", 2500.0),
                new BankingRuntimeStateSnapshot("agent-1", 2500.0, 0.0, 0, false, 4));

        assertEquals(Map.of("bankingCustomerId", 42L), BankingRuntimeProjectionSupport.runtimeMetadata(access));
        assertEquals(Map.of("7", 2500.0), BankingRuntimeProjectionSupport.eventBalancesSnapshot(access));
    }

    private record FakeProjectionAccess(
            Long bankingCustomerId,
            List<BankingAccountView> statusAccountViewsSnapshot,
            Map<String, Object> balancesSnapshot,
            BankingRuntimeStateSnapshot stateSnapshot) implements BankingRuntimeProjectionAccess {

        @Override
        public String personaName() {
            return "Customer One";
        }

        @Override
        public String runtimeIdentity() {
            return "42";
        }

        @Override
        public String conversationId() {
            return "conversation-1";
        }

        @Override
        public BankingRuntimeStateSnapshot stateSnapshot(ZonedDateTime now, String agentId) {
            return stateSnapshot;
        }
    }
}
