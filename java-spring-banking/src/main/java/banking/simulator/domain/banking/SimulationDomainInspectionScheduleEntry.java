package banking.simulator.domain.banking;

public record SimulationDomainInspectionScheduleEntry(
        String description,
        String type,
        String nextExecution,
        Double amount) {
}
