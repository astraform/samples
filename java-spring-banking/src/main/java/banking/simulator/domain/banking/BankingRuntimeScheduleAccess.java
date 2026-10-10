package banking.simulator.domain.banking;

import java.util.List;

public interface BankingRuntimeScheduleAccess {
    List<BankingRuntimeScheduledWorkPlan> plannedBankingScheduledWorkPlans();
}
