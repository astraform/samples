package banking.simulator.domain.banking.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Card {
    @JsonProperty("number")
    private final Long number;

    private CCScheme type;

    public Card(Long number) {
        this.number = number;
        String numberStr = String.valueOf(number);
        if (numberStr.startsWith("4")) {
            this.type = CCScheme.VISA;
        } else if (numberStr.startsWith("5")) {
            this.type = CCScheme.MC;
        } else {
            this.type = CCScheme.NOTACCEPTED;
        }
    }

    public abstract <T> T getSettlementAccount();

    public enum CCScheme {
        MC,
        VISA,
        NOTACCEPTED
    }

    public CCScheme type() {
        return this.type;
    }

    public Long getNumber() {
        return number;
    }

}
