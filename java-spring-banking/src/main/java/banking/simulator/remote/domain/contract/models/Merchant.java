package banking.simulator.remote.domain.contract.models;

import java.util.Objects;

/**
 * Compatibility contract used by the banking runtime seed/schedule adapters.
 */
public class Merchant {
    private String name;
    private String description;
    private String category;
    private String segment;
    private String currency;
    private String productCode;
    private String bsb;

    public Merchant name(String name) {
        this.name = name;
        return this;
    }

    public Merchant description(String description) {
        this.description = description;
        return this;
    }

    public Merchant category(String category) {
        this.category = category;
        return this;
    }

    public Merchant segment(String segment) {
        this.segment = segment;
        return this;
    }

    public Merchant currency(String currency) {
        this.currency = currency;
        return this;
    }

    public Merchant productCode(String productCode) {
        this.productCode = productCode;
        return this;
    }

    public Merchant bsb(String bsb) {
        this.bsb = bsb;
        return this;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSegment() {
        return segment;
    }

    public void setSegment(String segment) {
        this.segment = segment;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getBsb() {
        return bsb;
    }

    public void setBsb(String bsb) {
        this.bsb = bsb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Merchant merchant)) {
            return false;
        }
        return Objects.equals(name, merchant.name)
                && Objects.equals(description, merchant.description)
                && Objects.equals(category, merchant.category)
                && Objects.equals(segment, merchant.segment)
                && Objects.equals(currency, merchant.currency)
                && Objects.equals(productCode, merchant.productCode)
                && Objects.equals(bsb, merchant.bsb);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, category, segment, currency, productCode, bsb);
    }
}
