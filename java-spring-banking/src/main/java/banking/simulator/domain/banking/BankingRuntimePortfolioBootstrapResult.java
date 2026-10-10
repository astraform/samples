package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import java.util.List;
import java.util.Map;

public record BankingRuntimePortfolioBootstrapResult(
        List<Long> createdAccountIds,
        Map<String, Account> accountsByName,
        Map<String, BankingAccountView> accountViewsByName) {

    public BankingRuntimePortfolioBootstrapResult {
        createdAccountIds = createdAccountIds == null ? List.of() : List.copyOf(createdAccountIds);
        accountsByName = accountsByName == null ? Map.of() : Map.copyOf(accountsByName);
        accountViewsByName = accountViewsByName == null ? Map.of() : Map.copyOf(accountViewsByName);
    }
}
