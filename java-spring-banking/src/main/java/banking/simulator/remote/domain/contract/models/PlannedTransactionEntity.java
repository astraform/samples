package banking.simulator.remote.domain.contract.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compatibility contract used by the banking runtime schedule adapters.
 */
public class PlannedTransactionEntity {

    public enum FrequencyEnum {
        HOURLY("Hourly"),
        DAILY("Daily"),
        WEEKDAYS("Weekdays"),
        WEEKENDS("Weekends"),
        WEEKLY("Weekly"),
        FORTNIGHTLY("Fortnightly"),
        MONTHLY("Monthly"),
        YEARLY("Yearly");

        private final String value;

        FrequencyEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static FrequencyEnum fromValue(String value) {
            if (value == null) {
                return null;
            }
            String candidate = value.trim();
            for (FrequencyEnum frequency : values()) {
                if (frequency.name().equalsIgnoreCase(candidate)
                        || frequency.value.equalsIgnoreCase(candidate)) {
                    return frequency;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }

    public enum TypeEnum {
        WITHDRAWAL("Withdrawal"),
        PAYMENT("Payment"),
        DEPOSIT("Deposit"),
        TRANSFER("Transfer"),
        PAYROLL("Payroll"),
        DIRECTDEBIT("DirectDebit");

        private final String value;

        TypeEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static TypeEnum fromValue(String value) {
            if (value == null) {
                return null;
            }
            String candidate = value.trim();
            String normalizedCandidate = normalizeToken(candidate);
            for (TypeEnum type : values()) {
                if (type.name().equalsIgnoreCase(candidate)
                        || type.value.equalsIgnoreCase(candidate)
                        || normalizeToken(type.name()).equals(normalizedCandidate)
                        || normalizeToken(type.value).equals(normalizedCandidate)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }

        private static String normalizeToken(String value) {
            return value == null
                    ? ""
                    : value.replaceAll("[\\s_-]", "").toUpperCase(Locale.ROOT);
        }
    }

    public enum PermittedChannelsEnum {
        ATM("ATM"),
        BRANCH("Branch"),
        DIGITAL("Digital"),
        DIRECT("Direct"),
        CC("CC");

        private final String value;

        PermittedChannelsEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }

        @JsonCreator
        public static PermittedChannelsEnum fromValue(String value) {
            if (value == null) {
                return null;
            }
            String candidate = value.trim();
            for (PermittedChannelsEnum channel : values()) {
                if (channel.name().equalsIgnoreCase(candidate)
                        || channel.value.equalsIgnoreCase(candidate)) {
                    return channel;
                }
            }
            String normalized = candidate.toUpperCase(Locale.ROOT);
            for (PermittedChannelsEnum channel : values()) {
                if (channel.name().equals(normalized)) {
                    return channel;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }

    private String description;
    private FrequencyEnum frequency;
    private TypeEnum type;
    private Double amount;
    private Boolean isAmountExact;
    private String day;
    private String time;
    private Boolean applyTimeJitter;
    private LocalDate date;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<PermittedChannelsEnum> permittedChannels = new ArrayList<>();
    private PlannedTransactionEntityDetails details;

    public PlannedTransactionEntity description(String description) {
        this.description = description;
        return this;
    }

    public PlannedTransactionEntity frequency(FrequencyEnum frequency) {
        this.frequency = frequency;
        return this;
    }

    public PlannedTransactionEntity type(TypeEnum type) {
        this.type = type;
        return this;
    }

    public PlannedTransactionEntity amount(Double amount) {
        this.amount = amount;
        return this;
    }

    public PlannedTransactionEntity isAmountExact(Boolean isAmountExact) {
        this.isAmountExact = isAmountExact;
        return this;
    }

    public PlannedTransactionEntity day(String day) {
        this.day = day;
        return this;
    }

    public PlannedTransactionEntity time(String time) {
        this.time = time;
        return this;
    }

    public PlannedTransactionEntity applyTimeJitter(Boolean applyTimeJitter) {
        this.applyTimeJitter = applyTimeJitter;
        return this;
    }

    public PlannedTransactionEntity date(LocalDate date) {
        this.date = date;
        return this;
    }

    public PlannedTransactionEntity startDate(LocalDate startDate) {
        this.startDate = startDate;
        return this;
    }

    public PlannedTransactionEntity endDate(LocalDate endDate) {
        this.endDate = endDate;
        return this;
    }

    public PlannedTransactionEntity permittedChannels(List<PermittedChannelsEnum> permittedChannels) {
        this.permittedChannels = permittedChannels == null ? new ArrayList<>() : new ArrayList<>(permittedChannels);
        return this;
    }

    public PlannedTransactionEntity details(PlannedTransactionEntityDetails details) {
        this.details = details;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public FrequencyEnum getFrequency() {
        return frequency;
    }

    public void setFrequency(FrequencyEnum frequency) {
        this.frequency = frequency;
    }

    public TypeEnum getType() {
        return type;
    }

    public void setType(TypeEnum type) {
        this.type = type;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public Boolean getIsAmountExact() {
        return isAmountExact;
    }

    public void setIsAmountExact(Boolean amountExact) {
        isAmountExact = amountExact;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public Boolean getApplyTimeJitter() {
        return applyTimeJitter;
    }

    public void setApplyTimeJitter(Boolean applyTimeJitter) {
        this.applyTimeJitter = applyTimeJitter;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<PermittedChannelsEnum> getPermittedChannels() {
        return permittedChannels;
    }

    public void setPermittedChannels(List<PermittedChannelsEnum> permittedChannels) {
        this.permittedChannels = permittedChannels == null ? new ArrayList<>() : new ArrayList<>(permittedChannels);
    }

    public PlannedTransactionEntityDetails getDetails() {
        return details;
    }

    public void setDetails(PlannedTransactionEntityDetails details) {
        this.details = details;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlannedTransactionEntity that)) {
            return false;
        }
        return Objects.equals(description, that.description)
                && frequency == that.frequency
                && type == that.type
                && Objects.equals(amount, that.amount)
                && Objects.equals(isAmountExact, that.isAmountExact)
                && Objects.equals(day, that.day)
                && Objects.equals(time, that.time)
                && Objects.equals(applyTimeJitter, that.applyTimeJitter)
                && Objects.equals(date, that.date)
                && Objects.equals(startDate, that.startDate)
                && Objects.equals(endDate, that.endDate)
                && Objects.equals(permittedChannels, that.permittedChannels)
                && Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                description,
                frequency,
                type,
                amount,
                isAmountExact,
                day,
                time,
                applyTimeJitter,
                date,
                startDate,
                endDate,
                permittedChannels,
                details);
    }
}
