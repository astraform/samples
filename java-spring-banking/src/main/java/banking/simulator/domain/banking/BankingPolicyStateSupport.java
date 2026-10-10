package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class BankingPolicyStateSupport {
    private BankingPolicyStateSupport() {
    }

    public static BankingPolicyState from(BankingPolicyStateAccess access, ZonedDateTime now) {
        if (access == null) {
            return new BankingPolicyState(null, 0.0, 0.0, 0, false, -1);
        }

        List<BankingAccountView> accounts = access.statusAccountViewsSnapshot();
        double balance = BankingPortfolioMetrics.totalLiquidBalance(accounts);
        double utilisation = BankingPortfolioMetrics.creditUtilisation(accounts);
        int missedPayments = access.missedPaymentsLast3m() != null ? access.missedPaymentsLast3m() : 0;
        boolean hardship = Boolean.TRUE.equals(access.hardshipFlag());

        int daysSinceLastOutreach = -1;
        if (access.lastOutreachDate() != null && now != null) {
            daysSinceLastOutreach =
                    (int) ChronoUnit.DAYS.between(access.lastOutreachDate(), now.toInstant());
        }

        return new BankingPolicyState(
                access.displayAgentId(),
                balance,
                utilisation,
                missedPayments,
                hardship,
                daysSinceLastOutreach);
    }
}
