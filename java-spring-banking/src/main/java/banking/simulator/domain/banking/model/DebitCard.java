package banking.simulator.domain.banking.model;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DebitCard extends Card {

    @JsonIdentityInfo(generator = ObjectIdGenerators.UUIDGenerator.class, scope=TransactionAccount.class, property="@UUID")
    @JsonProperty("settlementAccount")
    private TransactionAccount settlementAccount;

    public DebitCard(Long number, TransactionAccount settlementAccount) {
        super(number);
        this.settlementAccount = settlementAccount;
    }
    public TransactionAccount getSettlementAccount() {
        return this.settlementAccount;
    }
    public void setSettlementAccount(TransactionAccount settlementAccount) {
        this.settlementAccount = settlementAccount;
    }
    
}
