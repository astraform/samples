package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.transaction.TransactionInstruction;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BankingRuntimeScheduleSupportTest {

    @Test
    void lifecycleSchedule_shouldProjectBankingPlansToGenericLifecycleEntries() {
        BankingRuntimeScheduleAccess access = () -> List.of(new BankingRuntimeScheduledWorkPlan(
                instruction("Rent", 1500.0),
                "monthly"));

        List<AgentLifecycleScheduleEntry> schedule = BankingRuntimeScheduleSupport.lifecycleSchedule(access);

        assertEquals(List.of(new AgentLifecycleScheduleEntry("Rent", "PAYMENT", "monthly", 1500.0)), schedule);
    }

    @Test
    void inspectionSchedule_shouldProjectBankingPlansToInspectionEntries() {
        BankingRuntimeScheduleAccess access = () -> List.of(new BankingRuntimeScheduledWorkPlan(
                instruction("Coffee", 5.5),
                "daily"));

        List<SimulationDomainInspectionScheduleEntry> schedule =
                BankingRuntimeScheduleSupport.inspectionSchedule(access);

        assertEquals(
                List.of(new SimulationDomainInspectionScheduleEntry("Coffee", "PAYMENT", "TBC", 5.5)),
                schedule);
    }

    @Test
    void recurrenceSpec_shouldProjectBankingContractToGenericScheduleSpec() {
        BankingPlannedTransactionContract contract = new BankingPlannedTransactionContract(
                "Rent",
                null,
                TransactionInstructionType.PAYMENT,
                1500.0,
                true,
                "monthly",
                "15",
                "09:30",
                false,
                null,
                null,
                null,
                List.of("Digital"),
                List.of("Everyday"),
                List.of(),
                null);

        var recurrenceSpec = BankingRuntimeScheduleSupport.recurrenceSpec(contract);

        assertNotNull(recurrenceSpec);
        assertEquals("monthly", recurrenceSpec.frequencyName());
        assertEquals("15", recurrenceSpec.day());
        assertEquals("09:30", recurrenceSpec.time());
        assertFalse(recurrenceSpec.applyTimeJitter());
    }

    private static TransactionInstruction instruction(String description, double amount) {
        return new TransactionInstruction(
                TransactionInstructionType.PAYMENT,
                amount,
                true,
                (Account) null,
                (Account) null,
                description);
    }
}
