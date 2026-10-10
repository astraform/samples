package banking.simulator.domain.banking;

import java.util.List;

public record BankingDoubleEntryPosting(
        List<BankingPostingLeg> credits,
        List<BankingPostingLeg> debits) {

    public BankingDoubleEntryPosting {
        credits = credits == null ? List.of() : List.copyOf(credits);
        debits = debits == null ? List.of() : List.copyOf(debits);
    }
}
