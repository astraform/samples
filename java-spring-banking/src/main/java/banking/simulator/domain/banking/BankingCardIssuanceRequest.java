package banking.simulator.domain.banking;

public record BankingCardIssuanceRequest(
        Long customerId,
        BankingCardType cardType,
        Long number,
        String scheme) {

    public BankingCardIssuanceRequest {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (cardType == null) {
            throw new IllegalArgumentException("cardType is required");
        }
        if (number == null) {
            throw new IllegalArgumentException("number is required");
        }
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("scheme is required");
        }
    }
}
