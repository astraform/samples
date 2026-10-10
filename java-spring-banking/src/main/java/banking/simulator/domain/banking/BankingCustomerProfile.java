package banking.simulator.domain.banking;

import java.time.LocalDate;

public record BankingCustomerProfile(
        String fullName,
        String persona,
        LocalDate dateOfBirth) {

    public BankingCustomerProfile {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName is required");
        }
        if (persona == null || persona.isBlank()) {
            throw new IllegalArgumentException("persona is required");
        }
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("dateOfBirth is required");
        }
    }
}
