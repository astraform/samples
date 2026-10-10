package banking.simulator.domain.banking;

public record BankingRuntimeStateSnapshot(
        String agentId,
        double currentBalance,
        double creditUtilisation,
        int missedPaymentsLast3m,
        boolean hardshipFlag,
        int daysSinceLastOutreach) {
}
