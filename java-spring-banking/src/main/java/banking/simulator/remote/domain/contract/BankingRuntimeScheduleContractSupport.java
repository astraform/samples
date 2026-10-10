package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.AgentLifecycleScheduleEntry;
import banking.simulator.domain.banking.SimulationDomainInspectionScheduleEntry;
import banking.simulator.remote.domain.contract.models.BankingScheduledWorkView;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import java.util.ArrayList;
import java.util.List;

public final class BankingRuntimeScheduleContractSupport {
    private BankingRuntimeScheduleContractSupport() {
    }

    public static List<PlannedTransactionEntity> statusSchedule(BankingRuntimeScheduleContractAccess access) {
        if (access == null) {
            return List.of();
        }
        List<PlannedTransactionEntity> schedule = new ArrayList<>();
        List<PlannedTransactionEntity> planned = access.plannedScheduleEntities();
        if (planned != null && !planned.isEmpty()) {
            schedule.addAll(planned);
        }
        List<PlannedTransactionEntity> pending = access.pendingScheduleEntities();
        if (pending != null && !pending.isEmpty()) {
            schedule.addAll(pending);
        }
        return schedule;
    }

    public static List<PlannedTransactionEntity> statusSchedule(
            List<AgentLifecycleScheduleEntry> lifecycleSchedule) {
        if (lifecycleSchedule == null || lifecycleSchedule.isEmpty()) {
            return List.of();
        }
        return lifecycleSchedule.stream()
                .filter(entry -> entry != null)
                .map(BankingRuntimeScheduleContractSupport::toPlannedTransactionEntity)
                .toList();
    }

    public static List<BankingScheduledWorkView> inspectionSchedule(
            List<SimulationDomainInspectionScheduleEntry> inspectionSchedule) {
        if (inspectionSchedule == null || inspectionSchedule.isEmpty()) {
            return List.of();
        }
        return inspectionSchedule.stream()
                .filter(entry -> entry != null)
                .map(BankingRuntimeScheduleContractSupport::toScheduledWorkView)
                .toList();
    }

    private static PlannedTransactionEntity toPlannedTransactionEntity(AgentLifecycleScheduleEntry entry) {
        PlannedTransactionEntity entity = new PlannedTransactionEntity()
                .description(entry.description())
                .amount(entry.amount());

        PlannedTransactionEntity.TypeEnum type = parseType(entry.type());
        if (type != null) {
            entity.setType(type);
        }

        PlannedTransactionEntity.FrequencyEnum frequency = parseFrequency(entry.frequency());
        if (frequency != null) {
            entity.setFrequency(frequency);
        }

        return entity;
    }

    private static BankingScheduledWorkView toScheduledWorkView(
            SimulationDomainInspectionScheduleEntry entry) {
        return new BankingScheduledWorkView()
                .description(entry.description())
                .kind("transaction")
                .type(entry.type())
                .nextExecution(entry.nextExecution())
                .amount(entry.amount());
    }

    private static PlannedTransactionEntity.TypeEnum parseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        for (PlannedTransactionEntity.TypeEnum value : PlannedTransactionEntity.TypeEnum.values()) {
            if (value.name().equalsIgnoreCase(rawType.trim())) {
                return value;
            }
        }
        return null;
    }

    private static PlannedTransactionEntity.FrequencyEnum parseFrequency(String rawFrequency) {
        if (rawFrequency == null || rawFrequency.isBlank()) {
            return null;
        }
        for (PlannedTransactionEntity.FrequencyEnum value : PlannedTransactionEntity.FrequencyEnum.values()) {
            if (value.name().equalsIgnoreCase(rawFrequency.trim())) {
                return value;
            }
        }
        return null;
    }
}
