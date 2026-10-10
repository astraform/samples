package banking.simulator.domain.banking.model;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CreditCard extends Card {

    @JsonProperty("settlementAccount")
    @JsonIdentityInfo(generator = ObjectIdGenerators.UUIDGenerator.class, scope=CreditAccount.class, property="@UUID")
    private CreditAccount settlementAccount;

    public CreditCard(Long number, CreditAccount settlementAccount) {
        super(number);
        this.settlementAccount = settlementAccount;
    }

    public CreditAccount getSettlementAccount() {
        return this.settlementAccount;
    }
    public void setSettlementAccount(CreditAccount settlementAccount) {
        this.settlementAccount = settlementAccount;
    }

}
