package banking.simulator.domain.banking;

import java.time.ZonedDateTime;
import java.util.List;

public final class BankingExecutionPayloads {

    private BankingExecutionPayloads() {
    }

    public static BankingDoubleEntryPosting internalTransfer(
            BankingPostingTarget fromAccount,
            BankingPostingTarget toAccount,
            double amount,
            ZonedDateTime currentTime,
            String description) {
        if (fromAccount == null || toAccount == null) {
            throw new IllegalArgumentException("fromAccount and toAccount are required");
        }
        if (!Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        if (currentTime == null) {
            throw new IllegalArgumentException("currentTime is required");
        }
        return new BankingDoubleEntryPosting(
                List.of(accountLeg(toAccount, amount, currentTime, description)),
                List.of(accountLeg(fromAccount, amount, currentTime, description)));
    }

    public static BankingPostingLeg accountLeg(
            BankingPostingTarget account,
            double amount,
            ZonedDateTime currentTime,
            String description) {
        if (account == null || account.accountId() == null) {
            throw new IllegalArgumentException("account with id is required");
        }
        return new BankingPostingLeg(
                account.accountId(),
                account.bsb(),
                amount,
                account.currency(),
                currentTime.toOffsetDateTime(),
                description,
                null);
    }
}
