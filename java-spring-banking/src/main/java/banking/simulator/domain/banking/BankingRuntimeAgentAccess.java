package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.util.List;

public interface BankingRuntimeAgentAccess {

    String displayName();

    boolean isActive();

    Long bankingCustomerId();

    List<BankingAccountView> statusAccountViewsSnapshot();

    List<BankingAccountView> originatedAccountViewsSnapshot();

    void injectTransaction(double amount, String description);

    default BankingHardshipScanResult runHardshipReliefScan(
            ZonedDateTime eventTime,
            BankingHardshipScanPolicy scanPolicy) {
        return null;
    }

    default boolean supportsAccountLinkedControls() {
        return bankingCustomerId() != null;
    }
}
