package banking.simulator.domain.banking.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CreditAccount extends InterestEarningAccount {

    @JsonProperty("creditLimit")
    protected Double creditLimit;

    public CreditAccount(Product product, Long number, Double balance, Double creditLimit, List<Customer> customers) {
        super(product, number, balance, "AUD", customers);

        this.creditLimit = creditLimit;
    }
    public Double getCreditLimit() {
        return this.creditLimit;
    }
    public void setCreditLimit(Double creditLimit) {
        this.creditLimit = creditLimit;
    }
    
}
