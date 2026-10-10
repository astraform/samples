package banking.simulator.domain.banking;

import java.time.Instant;
import java.util.List;

public interface BankingPolicyStateAccess {
    String stableMemoryKey();

    String displayAgentId();

    List<BankingAccountView> statusAccountViewsSnapshot();

    Integer missedPaymentsLast3m();

    Boolean hardshipFlag();

    Instant lastOutreachDate();
}
