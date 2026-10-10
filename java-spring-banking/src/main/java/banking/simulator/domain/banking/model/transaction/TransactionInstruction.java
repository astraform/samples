package banking.simulator.domain.banking.model.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.Merchant;

public class TransactionInstruction {
    protected TransactionInstructionType type;
    protected Double amount;
    protected Account from;
    protected Account to;
    protected Boolean exactAmount;
    protected String description;
    protected Merchant merchant;

    public TransactionInstruction(TransactionInstructionType type, Double amount, Boolean exactAmount, Account from, Account to, String description) {
       // super();
        this.type = type;
        this.amount = amount;
        this.from = from;
        this.to = to;
        this.exactAmount = exactAmount;
        this.description = description;
        this.merchant = null;
    }

        public TransactionInstruction(TransactionInstructionType type, Double amount, Boolean exactAmount, Account from, Merchant merchant, String description) {
        this.type = type;
        this.amount = amount;
        this.from = from;
        this.to = merchant.getSchemeSettlemeAccount();
        this.exactAmount = exactAmount;
        this.description = description;
        this.merchant = merchant;
    }

    public TransactionInstructionType getType() {
        return type;
    }

    public Double getAmount() {
        if (exactAmount) {
            return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
        } else {
            Random r = new Random();
            return new BigDecimal(amount + r.nextFloat()*amount/3 - r.nextFloat()*amount/3).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
    }

    public Double getNominalAmount() {
        return amount;
    }

    public Boolean isExactAmount() {
        return exactAmount;
    }

    public Account getFrom() {
        return from;
    }

    public Account getTo() {
        return to;
    }

//    public String toString() {
//        return String.join(">", type.toString(), from.getNumber().toString(), to.getNumber().toString(), amount.toString());
//    }

    public String getDescription() {
        return description;
    }

    public Merchant getMerchant() {
            return merchant;
    }

    @Override
    public String toString() {
        return "TransactionInstruction{" +
                "type=" + type +
                ", amount=" + amount +
                ", from=" + from +
                ", to=" + to +
                ", exactAmount=" + exactAmount +
                ", description='" + description + '\'' +
                ", merchant=" + merchant +
                '}';
    }
    
}
