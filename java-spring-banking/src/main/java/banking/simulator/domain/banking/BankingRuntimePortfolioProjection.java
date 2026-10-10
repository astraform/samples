package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import java.util.Map;

public record BankingRuntimePortfolioProjection(
        Map<String, Account> accountsByName,
        Map<Long, Account> accountsByNumber) {
}
