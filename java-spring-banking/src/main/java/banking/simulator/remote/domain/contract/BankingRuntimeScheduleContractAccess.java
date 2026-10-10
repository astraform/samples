package banking.simulator.remote.domain.contract;

import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import java.util.List;

public interface BankingRuntimeScheduleContractAccess {
    List<PlannedTransactionEntity> plannedScheduleEntities();

    List<PlannedTransactionEntity> pendingScheduleEntities();
}
