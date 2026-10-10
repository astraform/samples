package banking.simulator.domain.banking.model.transaction;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TransactionInstructionType {
    WITHDRAWAL,
    DEPOSIT,
    TRANSFER,
    PAYMENT,
    PAYROLL,
    DIRECTDEBIT;

    @JsonCreator
    public static TransactionInstructionType fromString(String transactionInstructionType) {
        return transactionInstructionType == null
                ? null
                : TransactionInstructionType.valueOf(transactionInstructionType.toUpperCase());
    }
}
