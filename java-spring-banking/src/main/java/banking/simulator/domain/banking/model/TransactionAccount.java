package banking.simulator.domain.banking.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionAccount extends InterestEarningAccount {

    @JsonProperty("bsb")
    private Integer bsb;

    public TransactionAccount(Product type, Long number, Double balance, Integer bsb, String currency,
            List<Customer> customers) {
        super(type, number, balance, currency, customers);
        if (bsb == null || bsb == 0)
            bsb = 62401;
        this.bsb = bsb;
    }

    public TransactionAccount(Long id) {
        super(null, id, 0.0, "AUD", null);
        this.bsb = 62401;
    }

    public TransactionAccount() {
        super(null, null, 0.0, "AUD", null);
    }

    public final Integer getBsb() {
        return this.bsb;
    }

}
