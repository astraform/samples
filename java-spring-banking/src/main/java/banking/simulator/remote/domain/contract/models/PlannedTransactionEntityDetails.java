package banking.simulator.remote.domain.contract.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compatibility contract used by the banking runtime schedule adapters.
 */
public class PlannedTransactionEntityDetails {
    private String description;
    private Merchant merchant;
    private List<String> from = new ArrayList<>();
    private List<String> to = new ArrayList<>();

    public PlannedTransactionEntityDetails description(String description) {
        this.description = description;
        return this;
    }

    public PlannedTransactionEntityDetails merchant(Merchant merchant) {
        this.merchant = merchant;
        return this;
    }

    public PlannedTransactionEntityDetails from(List<String> from) {
        this.from = from == null ? new ArrayList<>() : new ArrayList<>(from);
        return this;
    }

    public PlannedTransactionEntityDetails to(List<String> to) {
        this.to = to == null ? new ArrayList<>() : new ArrayList<>(to);
        return this;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }

    public List<String> getFrom() {
        return from;
    }

    public void setFrom(List<String> from) {
        this.from = from == null ? new ArrayList<>() : new ArrayList<>(from);
    }

    public List<String> getTo() {
        return to;
    }

    public void setTo(List<String> to) {
        this.to = to == null ? new ArrayList<>() : new ArrayList<>(to);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlannedTransactionEntityDetails that)) {
            return false;
        }
        return Objects.equals(description, that.description)
                && Objects.equals(merchant, that.merchant)
                && Objects.equals(from, that.from)
                && Objects.equals(to, that.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, merchant, from, to);
    }
}
