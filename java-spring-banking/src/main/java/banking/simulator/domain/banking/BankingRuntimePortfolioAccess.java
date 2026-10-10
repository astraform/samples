package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.Card;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface BankingRuntimePortfolioAccess {
    Map<String, Account> originatedAccounts();

    List<Card> originatedCards();

    Object refreshMonitor();

    Instant lastRefreshAttemptAt();

    void lastRefreshAttemptAt(Instant instant);
}
