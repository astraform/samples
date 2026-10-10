package banking.simulator.domain.banking.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class InterestEarningAccount extends Account {

    private double interestRate;
    
    public double getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }

    public InterestEarningAccount(Product product, Long number, Double balance, String currency,
            List<Customer> customers) {
                super(product, number, balance, currency, customers);
    }
    
}
