package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.transaction.TransactionInstruction;
import java.util.List;

public final class BankingRuntimeScheduleSupport {
    private BankingRuntimeScheduleSupport() {
    }

    public static List<AgentLifecycleScheduleEntry> lifecycleSchedule(BankingRuntimeScheduleAccess access) {
        if (access == null) {
            return List.of();
        }
        return access.plannedBankingScheduledWorkPlans().stream()
                .map(BankingRuntimeScheduleSupport::toLifecycleScheduleEntry)
                .toList();
    }

    public static List<SimulationDomainInspectionScheduleEntry> inspectionSchedule(BankingRuntimeScheduleAccess access) {
        if (access == null) {
            return List.of();
        }
        return access.plannedBankingScheduledWorkPlans().stream()
                .map(BankingRuntimeScheduleSupport::toInspectionScheduleEntry)
                .toList();
    }

    public static ScheduleRecurrenceSpec recurrenceSpec(BankingPlannedTransactionContract contract) {
        if (contract == null) {
            return null;
        }
        return new ScheduleRecurrenceSpec(
                contract.frequencyName(),
                contract.day(),
                contract.time(),
                contract.applyTimeJitter());
    }

    private static AgentLifecycleScheduleEntry toLifecycleScheduleEntry(BankingRuntimeScheduledWorkPlan plan) {
        TransactionInstruction instruction = plan == null ? null : plan.instruction();
        return new AgentLifecycleScheduleEntry(
                instruction != null ? instruction.getDescription() : null,
                instruction != null && instruction.getType() != null ? instruction.getType().name() : null,
                plan != null ? plan.frequencyName() : null,
                instruction != null ? instruction.getAmount() : null);
    }

    private static SimulationDomainInspectionScheduleEntry toInspectionScheduleEntry(
            BankingRuntimeScheduledWorkPlan plan) {
        TransactionInstruction instruction = plan == null ? null : plan.instruction();
        return new SimulationDomainInspectionScheduleEntry(
                instruction != null ? instruction.getDescription() : null,
                instruction != null && instruction.getType() != null ? instruction.getType().name() : null,
                "TBC",
                instruction != null ? instruction.getAmount() : null);
    }
}
