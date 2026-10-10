package banking.simulator.domain.banking;

public record BankingPostingTarget(Long accountId, Integer bsb, String currency) {

    public BankingPostingTarget {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (currency == null || currency.isBlank()) {
            currency = "AUD";
        }
    }

    public static BankingPostingTarget fromAccount(BankingAccountView account) {
        if (account == null || account.id() == null) {
            throw new IllegalArgumentException("account with id is required");
        }
        return new BankingPostingTarget(account.id(), account.bsb(), account.currency());
    }
}
