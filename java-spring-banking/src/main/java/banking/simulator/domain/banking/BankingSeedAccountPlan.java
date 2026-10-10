package banking.simulator.domain.banking;

public record BankingSeedAccountPlan(
        BankingSeedAccountSpec seedAccount,
        boolean debitCardRequested) {

    public BankingSeedAccountPlan {
        if (seedAccount == null) {
            throw new IllegalArgumentException("seedAccount is required");
        }
    }

    public String accountName() {
        return seedAccount.name();
    }
}
