package banking.simulator.remote.domain.contract.models;

import java.time.LocalDate;

/**
 * Compatibility model retained for runtime code that still expects the historic
 * scheduled-action contract name instead of generic domain payload maps.
 */
public class ScheduledActionEntity {

    public enum FrequencyEnum {
        HOURLY("HOURLY"),
        DAILY("DAILY"),
        WEEKDAYS("WEEKDAYS"),
        WEEKENDS("WEEKENDS"),
        WEEKLY("WEEKLY"),
        FORTNIGHTLY("FORTNIGHTLY"),
        MONTHLY("MONTHLY"),
        YEARLY("YEARLY");

        private final String value;

        FrequencyEnum(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public enum ActionTypeEnum {
        PROMPT("PROMPT"),
        DETERMINISTIC_TURN("DETERMINISTIC_TURN");

        private final String value;

        ActionTypeEnum(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private String description;
    private FrequencyEnum frequency;
    private ActionTypeEnum actionType;
    private String prompt;
    private String time;
    private Boolean applyTimeJitter;
    private String day;
    private LocalDate date;
    private LocalDate startDate;
    private LocalDate endDate;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ScheduledActionEntity description(String description) {
        setDescription(description);
        return this;
    }

    public FrequencyEnum getFrequency() {
        return frequency;
    }

    public void setFrequency(FrequencyEnum frequency) {
        this.frequency = frequency;
    }

    public ScheduledActionEntity frequency(FrequencyEnum frequency) {
        setFrequency(frequency);
        return this;
    }

    public ActionTypeEnum getActionType() {
        return actionType;
    }

    public void setActionType(ActionTypeEnum actionType) {
        this.actionType = actionType;
    }

    public ScheduledActionEntity actionType(ActionTypeEnum actionType) {
        setActionType(actionType);
        return this;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public ScheduledActionEntity prompt(String prompt) {
        setPrompt(prompt);
        return this;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public ScheduledActionEntity time(String time) {
        setTime(time);
        return this;
    }

    public Boolean getApplyTimeJitter() {
        return applyTimeJitter;
    }

    public void setApplyTimeJitter(Boolean applyTimeJitter) {
        this.applyTimeJitter = applyTimeJitter;
    }

    public ScheduledActionEntity applyTimeJitter(Boolean applyTimeJitter) {
        setApplyTimeJitter(applyTimeJitter);
        return this;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public ScheduledActionEntity day(String day) {
        setDay(day);
        return this;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public ScheduledActionEntity date(LocalDate date) {
        setDate(date);
        return this;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public ScheduledActionEntity startDate(LocalDate startDate) {
        setStartDate(startDate);
        return this;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public ScheduledActionEntity endDate(LocalDate endDate) {
        setEndDate(endDate);
        return this;
    }
}
