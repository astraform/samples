package banking.simulator.domain.banking;

import java.time.LocalDate;

public record BankingAccountOpeningRequest(
        Long customerId,
        String customerFullName,
        String customerPersona,
        LocalDate customerDateOfBirth,
        String name,
        String productCode,
        Double balance,
        Double limit,
        Integer bsb,
        String currency,
        String fromAccountId,
        Double investAmount,
        String term,
        String interestPaymentFrequency) {

    public BankingAccountOpeningRequest {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (customerFullName == null || customerFullName.isBlank()) {
            throw new IllegalArgumentException("customerFullName is required");
        }
        if (customerPersona == null || customerPersona.isBlank()) {
            throw new IllegalArgumentException("customerPersona is required");
        }
        if (customerDateOfBirth == null) {
            throw new IllegalArgumentException("customerDateOfBirth is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode is required");
        }
        if (currency == null || currency.isBlank()) {
            currency = "AUD";
        }
    }
}
