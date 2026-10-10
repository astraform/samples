package banking.simulator.domain.banking;

import java.time.OffsetDateTime;

public record BankingPostingLeg(
        Long accountId,
        Integer bsb,
        Double amount,
        String currency,
        OffsetDateTime dateTime,
        String description,
        Long merchantId) {

    public BankingPostingLeg {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (amount == null || !Double.isFinite(amount) || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        if (dateTime == null) {
            throw new IllegalArgumentException("dateTime is required");
        }
        if (currency == null || currency.isBlank()) {
            currency = "AUD";
        }
    }

    public BankingPostingLeg withAccount(Long nextAccountId, Integer nextBsb) {
        return new BankingPostingLeg(nextAccountId, nextBsb, amount, currency, dateTime, description, merchantId);
    }

    public BankingPostingLeg withDescription(String nextDescription) {
        return new BankingPostingLeg(accountId, bsb, amount, currency, dateTime, nextDescription, merchantId);
    }

    public BankingPostingLeg withMerchantId(Long nextMerchantId) {
        return new BankingPostingLeg(accountId, bsb, amount, currency, dateTime, description, nextMerchantId);
    }
}
