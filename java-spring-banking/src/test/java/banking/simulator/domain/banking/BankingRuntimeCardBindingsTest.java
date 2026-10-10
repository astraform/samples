package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertSame;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.Card;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.CreditCard;
import banking.simulator.domain.banking.model.DebitCard;
import banking.simulator.domain.banking.model.Product;
import banking.simulator.domain.banking.model.TransactionAccount;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingRuntimeCardBindingsTest {

    @Test
    void refreshesSettlementAccountsToLatestRuntimeModels() {
        Product txnProduct = new Product(1L, "BASIC_TRANSACTION", "Basic Transaction", "txn");
        Product creditProduct = new Product(2L, "CC001", "Rewards Card", "credit");

        TransactionAccount oldTxn = new TransactionAccount(txnProduct, 10L, 100.0, 62401, "AUD", List.of());
        CreditAccount oldCredit = new CreditAccount(creditProduct, 11L, -50.0, 1000.0, List.of());
        Card debitCard = BankingRuntimeCardBindings.debitCard(4111111111111111L, oldTxn);
        Card creditCard = BankingRuntimeCardBindings.creditCard(5555555555554444L, oldCredit);

        TransactionAccount refreshedTxn = new TransactionAccount(txnProduct, 10L, 250.0, 62401, "AUD", List.of());
        CreditAccount refreshedCredit = new CreditAccount(creditProduct, 11L, -20.0, 2000.0, List.of());
        Map<Long, Account> refreshed = new HashMap<>();
        refreshed.put(10L, refreshedTxn);
        refreshed.put(11L, refreshedCredit);

        BankingRuntimeCardBindings.refreshSettlementAccounts(List.of(debitCard, creditCard), refreshed);

        assertSame(refreshedTxn, ((DebitCard) debitCard).getSettlementAccount());
        assertSame(refreshedCredit, ((CreditCard) creditCard).getSettlementAccount());
    }
}
