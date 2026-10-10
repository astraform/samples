package banking.simulator.domain.banking;

import java.time.LocalDate;
import java.util.Objects;

public final class BankingCustomerIdentitySemantics {

    private BankingCustomerIdentitySemantics() {
    }

    public static boolean matchesProvidedIdentity(
            String canonicalFullName,
            LocalDate canonicalDateOfBirth,
            String providedFullName,
            LocalDate providedDateOfBirth) {
        if (providedFullName != null && !Objects.equals(canonicalFullName, providedFullName)) {
            return false;
        }
        if (providedDateOfBirth != null && !Objects.equals(canonicalDateOfBirth, providedDateOfBirth)) {
            return false;
        }
        return true;
    }
}
