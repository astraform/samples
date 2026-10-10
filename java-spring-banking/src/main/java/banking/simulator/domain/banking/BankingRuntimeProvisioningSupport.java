package banking.simulator.domain.banking;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BankingRuntimeProvisioningSupport {
    private static final Logger LOG = Logger.getLogger(BankingRuntimeProvisioningSupport.class.getName());

    private BankingRuntimeProvisioningSupport() {
    }

    public static Long provisionBankingCustomer(
            BankingRuntimeProvisioningAccess runtimeAccess,
            BankingCustomerProfile customerProfile) {
        if (runtimeAccess == null || customerProfile == null) {
            return null;
        }
        return runtimeAccess.bank().createBankingCustomer(customerProfile);
    }

    public static void initializeCustomerRuntimeState(
            BankingRuntimeProvisioningAccess runtimeAccess,
            BankingCustomerProfile customerProfile,
            List<BankingSeedAccountPlan> initialAccountPlans) {
        if (runtimeAccess == null || customerProfile == null) {
            return;
        }
        runtimeAccess.configureRuntimePortfolio(
                customerProfile,
                initialAccountPlans == null ? List.of() : initialAccountPlans);
    }

    public static void initializePolicyRuntimeState(
            BankingRuntimeProvisioningAccess runtimeAccess,
            BankingCustomerProfile customerProfile,
            List<BankingSeedAccountPlan> initialAccountPlans,
            String policyAgentName) {
        if (runtimeAccess == null || customerProfile == null) {
            return;
        }

        List<BankingSeedAccountPlan> safeInitialPlans = initialAccountPlans == null ? List.of() : initialAccountPlans;
        if (safeInitialPlans.isEmpty()) {
            LOG.warning("Policy Agent '" + policyAgentName + "' has no initialAccounts - subsidy payments will be disabled");
            runtimeAccess.setAccountIds(List.of());
            return;
        }

        BankingRuntimePortfolioBootstrapResult bootstrap = BankingRuntimePortfolioSupport.originateAccounts(
                runtimeAccess.bank(),
                runtimeAccess.bankingCustomerId(),
                customerProfile,
                safeInitialPlans.stream()
                        .map(BankingSeedAccountPlan::seedAccount)
                        .toList());
        for (BankingSeedAccountPlan accountPlan : safeInitialPlans) {
            String accountName = accountPlan.accountName();
            if (accountName == null || accountName.isBlank()) {
                continue;
            }
            try {
                BankingAccountView created = bootstrap.accountViewsByName().get(accountName.trim());
                if (created == null) {
                    continue;
                }
                LOG.info(() -> "Policy Fund account created: id=" + created.id()
                        + ", name='" + accountName + "', balance=$" + accountPlan.seedAccount().balance());
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to create Policy Fund account '" + accountName + "'", e);
            }
        }
        runtimeAccess.setAccountIds(bootstrap.createdAccountIds());
    }
}
