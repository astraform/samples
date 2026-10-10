package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.util.List;

public record BankingHardshipScanSummary(
        ZonedDateTime eventTime,
        int policyAgentsScanned,
        List<String> policyAgentNames,
        int activeAgentsSeen,
        int analysedAgents,
        int hardshipDetections,
        int subsidiesIssued,
        double hardshipThreshold,
        double subsidyAmount,
        List<String> reliefRecipients,
        int skippedCooldownCount,
        int insufficientFundCount) {
}
