package banking.simulator.domain.banking;

public class BankingGatewayCreateException extends RuntimeException {
    public BankingGatewayCreateException(String message) {
        super(message);
    }

    public BankingGatewayCreateException(String message, Throwable cause) {
        super(message, cause);
    }
}
