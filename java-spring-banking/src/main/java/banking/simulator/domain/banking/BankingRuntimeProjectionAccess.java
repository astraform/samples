package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public interface BankingRuntimeProjectionAccess {

    String personaName();

    Long bankingCustomerId();

    String runtimeIdentity();

    String conversationId();

    List<BankingAccountView> statusAccountViewsSnapshot();

    Map<String, Object> balancesSnapshot();

    BankingRuntimeStateSnapshot stateSnapshot(ZonedDateTime now, String agentId);
}
