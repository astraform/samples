package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.transaction.TransactionInstruction;

public record BankingRuntimeScheduledWorkPlan(
        TransactionInstruction instruction,
        String frequencyName
) {
}
