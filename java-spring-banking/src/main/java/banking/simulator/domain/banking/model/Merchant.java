package banking.simulator.domain.banking.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Merchant {
    private String name;
    private Long id;
    private String merchantDescription;

    @JsonProperty("schemeSettlementAccount")
    private TransactionAccount schemeSettlementAccount;

    public Merchant(String name, Long id, TransactionAccount schemeSettlemeAccount) {
        this.name = name;
        this.id = id;
        this.schemeSettlementAccount = schemeSettlemeAccount;
    }

    public void setName(String name) {
        this.name = name;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public void setSchemeSettlementAccount(TransactionAccount schemeSettlementAccount) {
        this.schemeSettlementAccount = schemeSettlementAccount;
    }
    public String getName() {
        return name;
    }
    public Long getId() {
        return id;
    }
    public String merchantDescription() {
        return merchantDescription;
    }
    public void setMerchantDescription(String merchantDescription) {
        this.merchantDescription = merchantDescription;
    }
    public TransactionAccount getSchemeSettlemeAccount() {
        return schemeSettlementAccount;
    }
    
}
