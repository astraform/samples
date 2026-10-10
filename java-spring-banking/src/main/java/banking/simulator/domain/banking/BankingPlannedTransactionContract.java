package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record BankingPlannedTransactionContract(
        String description,
        String detailsDescription,
        TransactionInstructionType type,
        Double amount,
        boolean exactAmount,
        String frequencyName,
        String day,
        String time,
        boolean applyTimeJitter,
        LocalDate date,
        LocalDate startDate,
        LocalDate endDate,
        List<String> permittedChannelNames,
        List<String> fromAccounts,
        List<String> toAccounts,
        BankingMerchantProfile merchantProfile) {

    public BankingPlannedTransactionContract {
        description = normalizeText(description);
        detailsDescription = normalizeText(detailsDescription);
        frequencyName = normalizeFrequency(frequencyName);
        day = normalizeText(day);
        time = normalizeText(time);
        permittedChannelNames = immutableTrimmedList(permittedChannelNames);
        fromAccounts = immutableTrimmedList(fromAccounts);
        toAccounts = immutableTrimmedList(toAccounts);
    }

    public String resolvedDescription() {
        return description != null ? description : detailsDescription;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeFrequency(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static List<String> immutableTrimmedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
