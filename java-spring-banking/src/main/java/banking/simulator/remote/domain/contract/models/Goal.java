package banking.simulator.remote.domain.contract.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Provider-local compatibility goal shape for banking persona payloads.
 */
public class Goal {
    private String goalId;
    private String description;
    private Double targetAmount;
    private PriorityEnum priority;

    public enum PriorityEnum {
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high");

        private final String value;

        PriorityEnum(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static PriorityEnum fromValue(String value) {
            if (value == null) {
                return null;
            }
            String candidate = value.trim();
            for (PriorityEnum priority : values()) {
                if (priority.name().equalsIgnoreCase(candidate)
                        || priority.value.equalsIgnoreCase(candidate)) {
                    return priority;
                }
            }
            throw new IllegalArgumentException("Unexpected priority value '" + value + "'");
        }
    }

    public Goal goalId(String goalId) {
        this.goalId = goalId;
        return this;
    }

    public Goal description(String description) {
        this.description = description;
        return this;
    }

    public Goal targetAmount(Double targetAmount) {
        this.targetAmount = targetAmount;
        return this;
    }

    public Goal priority(PriorityEnum priority) {
        this.priority = priority;
        return this;
    }

    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(String goalId) {
        this.goalId = goalId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Double getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(Double targetAmount) {
        this.targetAmount = targetAmount;
    }

    public PriorityEnum getPriority() {
        return priority;
    }

    public void setPriority(PriorityEnum priority) {
        this.priority = priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Goal goal)) {
            return false;
        }
        return Objects.equals(goalId, goal.goalId)
                && Objects.equals(description, goal.description)
                && Objects.equals(targetAmount, goal.targetAmount)
                && priority == goal.priority;
    }

    @Override
    public int hashCode() {
        return Objects.hash(goalId, description, targetAmount, priority);
    }
}
