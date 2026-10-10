package banking.simulator.domain.banking.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(Long id, String code, String name, String description) {
}
