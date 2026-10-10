package banking.simulator.remote.domain.contract.models;

import java.util.Objects;

/**
 * Provider-local compatibility shape for banking account seed/status payloads.
 */
public class AccountDto {
    private Long id;
    private String name;
    private String productCode;
    private Double balance;
    private Double limit;
    private Integer bsb;
    private String currency;
    private String fromAccountId;
    private Double investAmount;
    private String term;
    private String interestPaymentFrequency;
    private Boolean debitCard;

    public AccountDto id(Long id) {
        this.id = id;
        return this;
    }

    public AccountDto name(String name) {
        this.name = name;
        return this;
    }

    public AccountDto productCode(String productCode) {
        this.productCode = productCode;
        return this;
    }

    public AccountDto balance(Double balance) {
        this.balance = balance;
        return this;
    }

    public AccountDto limit(Double limit) {
        this.limit = limit;
        return this;
    }

    public AccountDto bsb(Integer bsb) {
        this.bsb = bsb;
        return this;
    }

    public AccountDto currency(String currency) {
        this.currency = currency;
        return this;
    }

    public AccountDto fromAccountId(String fromAccountId) {
        this.fromAccountId = fromAccountId;
        return this;
    }

    public AccountDto investAmount(Double investAmount) {
        this.investAmount = investAmount;
        return this;
    }

    public AccountDto term(String term) {
        this.term = term;
        return this;
    }

    public AccountDto interestPaymentFrequency(String interestPaymentFrequency) {
        this.interestPaymentFrequency = interestPaymentFrequency;
        return this;
    }

    public AccountDto debitCard(Boolean debitCard) {
        this.debitCard = debitCard;
        return this;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public Double getBalance() {
        return balance;
    }

    public void setBalance(Double balance) {
        this.balance = balance;
    }

    public Double getLimit() {
        return limit;
    }

    public void setLimit(Double limit) {
        this.limit = limit;
    }

    public Integer getBsb() {
        return bsb;
    }

    public void setBsb(Integer bsb) {
        this.bsb = bsb;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(String fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public Double getInvestAmount() {
        return investAmount;
    }

    public void setInvestAmount(Double investAmount) {
        this.investAmount = investAmount;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getInterestPaymentFrequency() {
        return interestPaymentFrequency;
    }

    public void setInterestPaymentFrequency(String interestPaymentFrequency) {
        this.interestPaymentFrequency = interestPaymentFrequency;
    }

    public Boolean getDebitCard() {
        return debitCard;
    }

    public void setDebitCard(Boolean debitCard) {
        this.debitCard = debitCard;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccountDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(productCode, that.productCode)
                && Objects.equals(balance, that.balance)
                && Objects.equals(limit, that.limit)
                && Objects.equals(bsb, that.bsb)
                && Objects.equals(currency, that.currency)
                && Objects.equals(fromAccountId, that.fromAccountId)
                && Objects.equals(investAmount, that.investAmount)
                && Objects.equals(term, that.term)
                && Objects.equals(interestPaymentFrequency, that.interestPaymentFrequency)
                && Objects.equals(debitCard, that.debitCard);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id,
                name,
                productCode,
                balance,
                limit,
                bsb,
                currency,
                fromAccountId,
                investAmount,
                term,
                interestPaymentFrequency,
                debitCard);
    }
}
