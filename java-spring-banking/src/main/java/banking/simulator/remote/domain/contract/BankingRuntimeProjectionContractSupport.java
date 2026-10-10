package banking.simulator.remote.domain.contract;

import banking.simulator.domain.banking.BankingRuntimeProjectionAccess;
import banking.simulator.domain.banking.BankingRuntimeStateSnapshot;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.remote.domain.contract.models.CustomerState;
import java.time.ZonedDateTime;
import java.util.List;

public final class BankingRuntimeProjectionContractSupport {
    private BankingRuntimeProjectionContractSupport() {
    }

    public static CustomerState statusState(
            BankingRuntimeProjectionAccess access,
            ZonedDateTime now,
            String agentId) {
        BankingRuntimeStateSnapshot snapshot = access == null
                ? null
                : access.stateSnapshot(now, agentId);
        CustomerState state = new CustomerState();
        if (snapshot == null) {
            state.setAgentId(hasText(agentId) ? agentId.trim() : null);
            state.setCurrentBalance(0.0);
            state.setCreditUtilisation(0.0);
            state.setMissedPaymentsLast3m(0);
            state.setHardshipFlag(false);
            state.setDaysSinceLastOutreach(-1);
            return state;
        }
        state.setAgentId(hasText(snapshot.agentId()) ? snapshot.agentId() : agentId);
        state.setCurrentBalance(snapshot.currentBalance());
        state.setCreditUtilisation(snapshot.creditUtilisation());
        state.setMissedPaymentsLast3m(snapshot.missedPaymentsLast3m());
        state.setHardshipFlag(snapshot.hardshipFlag());
        state.setDaysSinceLastOutreach(snapshot.daysSinceLastOutreach());
        return state;
    }

    public static List<AccountDto> statusAccounts(BankingRuntimeProjectionAccess access) {
        if (access == null) {
            return List.of();
        }
        return BankingRuntimeContractMappings.toAccountDtos(access.statusAccountViewsSnapshot());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
