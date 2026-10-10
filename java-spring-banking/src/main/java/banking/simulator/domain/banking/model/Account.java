package banking.simulator.domain.banking.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;


@JsonTypeInfo(use=JsonTypeInfo.Id.MINIMAL_CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonIdentityInfo(generator = ObjectIdGenerators.UUIDGenerator.class, property="@UUID")
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Account {
    @JsonProperty("id")
    protected final Long number;
    protected final Product product;
    protected Double balance;
    protected String currency;
    protected String productCode;

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    @JsonProperty("customers")
    @JsonTypeInfo(use=JsonTypeInfo.Id.MINIMAL_CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    private List<Customer> customers;

    public Account(Product product, Long number, Double balance, String currency, List<Customer> customers) {
        this.number = number;
        this.product = product;
        this.customers = customers;
        if (balance == null) {
            this.balance = 0d;
        } else {
            this.balance = balance;
        }
        if (currency == null) {
            this.currency = "AUD";
        } else {
            this.currency = currency;
        }
    }
    public Account(Product product, Long number, String currency, List<Customer> customers) {
        this(product, number, 0d, currency, customers);
    }

    public final Long getNumber() {
        return number;
    }

    public final Product getProduct() {
        return product;
    }
    public final Double getBalance() {
        return balance;
    }
    public final List<Customer> getCustomers() {
        return this.customers;
    }
    public final void addCustomer(Customer customer) {
        this.customers.add(customer);
    }

    public String getCurrency() {
        return this.currency;
    }
    public final void setCustomers(List<Customer> customers) {
        this.customers = customers;
    }
    
}
