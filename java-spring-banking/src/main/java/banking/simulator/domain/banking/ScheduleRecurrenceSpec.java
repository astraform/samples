package banking.simulator.domain.banking;

public record ScheduleRecurrenceSpec(
        String frequencyName,
        String day,
        String time,
        boolean applyTimeJitter) {
}
