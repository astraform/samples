package banking.simulator.remote.domain.contract.models;

import java.util.Objects;

/**
 * Provider-local banking runtime status projection.
 */
public class CustomerState {
    private String agentId;
    private Double currentBalance;
    private Double creditUtilisation;
    private Integer missedPaymentsLast3m;
    private Boolean hardshipFlag;
    private Integer daysSinceLastOutreach;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Double getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(Double currentBalance) {
        this.currentBalance = currentBalance;
    }

    public Double getCreditUtilisation() {
        return creditUtilisation;
    }

    public void setCreditUtilisation(Double creditUtilisation) {
        this.creditUtilisation = creditUtilisation;
    }

    public Integer getMissedPaymentsLast3m() {
        return missedPaymentsLast3m;
    }

    public void setMissedPaymentsLast3m(Integer missedPaymentsLast3m) {
        this.missedPaymentsLast3m = missedPaymentsLast3m;
    }

    public Boolean getHardshipFlag() {
        return hardshipFlag;
    }

    public void setHardshipFlag(Boolean hardshipFlag) {
        this.hardshipFlag = hardshipFlag;
    }

    public Integer getDaysSinceLastOutreach() {
        return daysSinceLastOutreach;
    }

    public void setDaysSinceLastOutreach(Integer daysSinceLastOutreach) {
        this.daysSinceLastOutreach = daysSinceLastOutreach;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomerState that)) {
            return false;
        }
        return Objects.equals(agentId, that.agentId)
                && Objects.equals(currentBalance, that.currentBalance)
                && Objects.equals(creditUtilisation, that.creditUtilisation)
                && Objects.equals(missedPaymentsLast3m, that.missedPaymentsLast3m)
                && Objects.equals(hardshipFlag, that.hardshipFlag)
                && Objects.equals(daysSinceLastOutreach, that.daysSinceLastOutreach);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                agentId,
                currentBalance,
                creditUtilisation,
                missedPaymentsLast3m,
                hardshipFlag,
                daysSinceLastOutreach);
    }
}
