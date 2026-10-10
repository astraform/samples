package banking.simulator.domain.banking;

public class BankingGatewayReadException extends RuntimeException {
    public BankingGatewayReadException(String message) {
        super(message);
    }

    public BankingGatewayReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
