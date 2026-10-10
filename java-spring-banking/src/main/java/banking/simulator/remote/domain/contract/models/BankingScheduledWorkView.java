package banking.simulator.remote.domain.contract.models;

import java.util.Objects;

/**
 * Provider-local schedule view used in banking inspection payloads.
 */
public class BankingScheduledWorkView {
    private String description;
    private String kind;
    private String type;
    private String nextExecution;
    private Double amount;

    public BankingScheduledWorkView description(String description) {
        this.description = description;
        return this;
    }

    public BankingScheduledWorkView kind(String kind) {
        this.kind = kind;
        return this;
    }

    public BankingScheduledWorkView type(String type) {
        this.type = type;
        return this;
    }

    public BankingScheduledWorkView nextExecution(String nextExecution) {
        this.nextExecution = nextExecution;
        return this;
    }

    public BankingScheduledWorkView amount(Double amount) {
        this.amount = amount;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getNextExecution() {
        return nextExecution;
    }

    public void setNextExecution(String nextExecution) {
        this.nextExecution = nextExecution;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BankingScheduledWorkView that)) {
            return false;
        }
        return Objects.equals(description, that.description)
                && Objects.equals(kind, that.kind)
                && Objects.equals(type, that.type)
                && Objects.equals(nextExecution, that.nextExecution)
                && Objects.equals(amount, that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, kind, type, nextExecution, amount);
    }
}
