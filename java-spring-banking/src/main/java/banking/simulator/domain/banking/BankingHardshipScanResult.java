package banking.simulator.domain.banking;

import java.util.List;

public record BankingHardshipScanResult(
        String policyAgentName,
        int activeAgentsSeen,
        int analysedAgents,
        int hardshipDetections,
        int subsidiesIssued,
        int skippedCooldownCount,
        int insufficientFundCount,
        double hardshipThreshold,
        double subsidyAmount,
        List<String> reliefRecipients,
        List<String> actionItems,
        String message) {
}
