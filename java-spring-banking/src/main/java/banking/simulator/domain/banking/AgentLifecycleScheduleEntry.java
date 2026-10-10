package banking.simulator.domain.banking;

public record AgentLifecycleScheduleEntry(
        String description,
        String type,
        String frequency,
        Double amount) {
}
