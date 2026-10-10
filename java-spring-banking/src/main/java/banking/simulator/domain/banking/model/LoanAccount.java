package banking.simulator.domain.banking.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonIgnoreProperties(ignoreUnknown = true)
public class LoanAccount extends CreditAccount {

    @JsonProperty("bsb")
    private Integer bsb;

    public LoanAccount(Product product, Long number, Double balance, Integer bsb, Double creditLimit, List<Customer> customers) {
        super(product, number, balance, creditLimit, customers);
        this.bsb = bsb;
    }
    public Integer getBsb() {
        return this.bsb;
    }
    
}
