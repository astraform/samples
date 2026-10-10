package banking.simulator.domain.banking;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.Card;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.CreditCard;
import banking.simulator.domain.banking.model.DebitCard;
import banking.simulator.domain.banking.model.TransactionAccount;
import java.util.List;
import java.util.Map;

public final class BankingRuntimeCardBindings {

    private BankingRuntimeCardBindings() {
    }

    public static DebitCard debitCard(Long number, TransactionAccount settlementAccount) {
        return new DebitCard(number, settlementAccount);
    }

    public static CreditCard creditCard(Long number, CreditAccount settlementAccount) {
        return new CreditCard(number, settlementAccount);
    }

    public static void refreshSettlementAccounts(List<Card> cards, Map<Long, Account> refreshedByNumber) {
        if (cards == null || cards.isEmpty() || refreshedByNumber == null || refreshedByNumber.isEmpty()) {
            return;
        }
        for (Card card : cards) {
            if (card instanceof CreditCard creditCard && creditCard.getSettlementAccount() != null) {
                Account refreshed = refreshedByNumber.get(creditCard.getSettlementAccount().getNumber());
                if (refreshed instanceof CreditAccount refreshedCreditAccount) {
                    creditCard.setSettlementAccount(refreshedCreditAccount);
                }
            } else if (card instanceof DebitCard debitCard && debitCard.getSettlementAccount() != null) {
                Account refreshed = refreshedByNumber.get(debitCard.getSettlementAccount().getNumber());
                if (refreshed instanceof TransactionAccount refreshedTransactionAccount) {
                    debitCard.setSettlementAccount(refreshedTransactionAccount);
                }
            }
        }
    }
}
