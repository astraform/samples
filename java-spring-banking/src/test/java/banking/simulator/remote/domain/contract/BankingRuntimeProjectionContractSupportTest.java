package banking.simulator.remote.domain.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import banking.simulator.domain.banking.BankingAccountView;
import banking.simulator.domain.banking.BankingRuntimeProjectionAccess;
import banking.simulator.domain.banking.BankingRuntimeStateSnapshot;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingRuntimeProjectionContractSupportTest {

    @Test
    void statusAccountsAndStateMapToTransportContracts() {
        BankingRuntimeProjectionAccess access = new FakeProjectionAccess();

        assertEquals(1, BankingRuntimeProjectionContractSupport.statusAccounts(access).size());
        assertEquals("TX001", BankingRuntimeProjectionContractSupport.statusAccounts(access).getFirst().getProductCode());
        assertEquals("agent-1",
                BankingRuntimeProjectionContractSupport.statusState(access, ZonedDateTime.now(), "fallback").getAgentId());
    }

    private static final class FakeProjectionAccess implements BankingRuntimeProjectionAccess {
        @Override
        public String personaName() {
            return "Customer One";
        }

        @Override
        public Long bankingCustomerId() {
            return 42L;
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
        public List<BankingAccountView> statusAccountViewsSnapshot() {
            return List.of(new BankingAccountView(7L, "Everyday", "TX001", 2500.0, null, null, "AUD"));
        }

        @Override
        public Map<String, Object> balancesSnapshot() {
            return Map.of("7", 2500.0);
        }

        @Override
        public BankingRuntimeStateSnapshot stateSnapshot(ZonedDateTime now, String agentId) {
            return new BankingRuntimeStateSnapshot("agent-1", 2500.0, 0.0, 0, false, 4);
        }
    }
}
