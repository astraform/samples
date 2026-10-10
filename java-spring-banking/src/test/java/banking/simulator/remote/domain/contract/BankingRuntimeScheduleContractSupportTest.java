package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.AgentLifecycleScheduleEntry;
import banking.simulator.domain.banking.SimulationDomainInspectionScheduleEntry;
import banking.simulator.remote.domain.contract.models.BankingScheduledWorkView;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BankingRuntimeScheduleContractSupportTest {

    @Test
    void statusSchedule_shouldMergePlannedAndPendingScheduleEntries() {
        PlannedTransactionEntity planned = new PlannedTransactionEntity().description("Rent");
        PlannedTransactionEntity pending = new PlannedTransactionEntity().description("Coffee");

        BankingRuntimeScheduleContractAccess access = new BankingRuntimeScheduleContractAccess() {
            @Override
            public List<PlannedTransactionEntity> plannedScheduleEntities() {
                return List.of(planned);
            }

            @Override
            public List<PlannedTransactionEntity> pendingScheduleEntities() {
                return List.of(pending);
            }
        };

        assertEquals(List.of(planned, pending), BankingRuntimeScheduleContractSupport.statusSchedule(access));
    }

    @Test
    void statusSchedule_canProjectGenericLifecycleScheduleIntoBankingCompatibilityShape() {
        List<PlannedTransactionEntity> projected = BankingRuntimeScheduleContractSupport.statusSchedule(List.of(
                new AgentLifecycleScheduleEntry("Rent", "PAYMENT", "MONTHLY", 1800.0),
                new AgentLifecycleScheduleEntry("Readiness sweep", "TASK", "QUARTERLY", null)));

        assertEquals(2, projected.size());
        assertEquals("Rent", projected.getFirst().getDescription());
        assertEquals(PlannedTransactionEntity.TypeEnum.PAYMENT, projected.getFirst().getType());
        assertEquals(PlannedTransactionEntity.FrequencyEnum.MONTHLY, projected.getFirst().getFrequency());
        assertEquals(1800.0, projected.getFirst().getAmount());

        assertEquals("Readiness sweep", projected.get(1).getDescription());
        assertNull(projected.get(1).getType(), "unsupported generic work kinds should not be forced into banking enums");
        assertNull(projected.get(1).getFrequency(), "unsupported generic cadences should not explode the compatibility projection");
        assertNull(projected.get(1).getAmount());
    }

    @Test
    void inspectionSchedule_canProjectGenericInspectionScheduleIntoBankingView() {
        List<BankingScheduledWorkView> projected = BankingRuntimeScheduleContractSupport.inspectionSchedule(List.of(
                new SimulationDomainInspectionScheduleEntry("Mortgage", "TRANSFER", "2026-05-01T09:00:00Z", 3200.0)));

        assertEquals(1, projected.size());
        assertEquals("Mortgage", projected.getFirst().getDescription());
        assertEquals("transaction", projected.getFirst().getKind());
        assertEquals("TRANSFER", projected.getFirst().getType());
        assertEquals("2026-05-01T09:00:00Z", projected.getFirst().getNextExecution());
        assertEquals(3200.0, projected.getFirst().getAmount());
    }
}
