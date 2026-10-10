package banking.simulator.domain.banking;

public record BankingPolicyState(
        String agentId,
        double currentBalance,
        double creditUtilisation,
        int missedPaymentsLast3m,
        boolean hardshipFlag,
        int daysSinceLastOutreach) {

    public String getAgentId() {
        return agentId;
    }

    public double getCurrentBalance() {
        return currentBalance;
    }

    public double getCreditUtilisation() {
        return creditUtilisation;
    }

    public int getMissedPaymentsLast3m() {
        return missedPaymentsLast3m;
    }

    public boolean isHardshipFlag() {
        return hardshipFlag;
    }

    public int getDaysSinceLastOutreach() {
        return daysSinceLastOutreach;
    }
}
