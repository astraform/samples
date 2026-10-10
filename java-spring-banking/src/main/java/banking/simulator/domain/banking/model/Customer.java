package banking.simulator.domain.banking.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;


@JsonIdentityInfo(generator = ObjectIdGenerators.UUIDGenerator.class, property="@UUID")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Customer {
    private Long id;
    private String firstName;
    private String surname;
    private ArrayList<String> names;
    private String fullName;
    private String dateOfBirth;

    @JsonProperty("accounts")
    private List<Account> accounts = new ArrayList<Account>();

    public Customer (Long id, String firstName, String surname) {
        this.id = id;
        this.firstName = firstName;
        this.surname = surname;
    }

    public Customer() {}

    public Customer(Long id, ArrayList<String> names) {
        this.id = id;
        this.names = names;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public ArrayList<String> getNames() {
        return names;
    }
    public String getFullName() {
        if (names != null) {
            fullName = String.join(" ", names);
        }
        return fullName;
    }

    public void setNames(ArrayList<String> names) {
        this.names = names;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

}
