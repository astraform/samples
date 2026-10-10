package banking.simulator.domain.banking;

import java.util.List;

public interface BankingRuntimeProvisioningAccess {

    BankingRuntimeGateway bank();

    Long bankingCustomerId();

    void setAccountIds(List<Long> accountIds);

    default void configureRuntimePortfolio(
            BankingCustomerProfile customerProfile,
            List<BankingSeedAccountPlan> initialAccountPlans) {
        throw new UnsupportedOperationException("Runtime portfolio configuration is not supported");
    }
}
