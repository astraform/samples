package banking.simulator.domain.banking;

public interface BankingRuntimeIdentityAccess {
    String runtimeIdentity();

    Long bankingCustomerId();

    String conversationId();

    String personaName();
}
