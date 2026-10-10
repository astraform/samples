package banking.simulator.domain.banking;

import java.util.List;

public interface BankingRuntimeAccountLoadAccess {

    Long bankingCustomerId();

    BankingRuntimeGateway runtimeGateway();

    default List<BankingAccountView> statusAccountViewsSnapshot() {
        return List.of();
    }

    default List<BankingAccountView> originatedAccountViewsSnapshot() {
        return List.of();
    }
}
