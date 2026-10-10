package banking.simulator.domain.banking;

import java.time.LocalDate;

public record BankingCreditStatementWindow(
        LocalDate startDate,
        LocalDate endDate) {

    public BankingCreditStatementWindow {
        if (startDate == null) {
            throw new IllegalArgumentException("startDate is required");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("endDate is required");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }
}
