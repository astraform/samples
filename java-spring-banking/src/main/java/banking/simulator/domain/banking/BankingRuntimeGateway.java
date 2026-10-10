package banking.simulator.domain.banking;

import java.util.List;
import java.util.Optional;

public interface BankingRuntimeGateway {
    Long createBankingCustomer(BankingCustomerProfile customerProfile);

    BankingMerchantView createMerchant(BankingMerchantProfile merchantProfile);

    List<BankingAccountView> getAccountViewsForCustomer(Long customerId);

    Optional<BankingProductView> getProduct(String productCode);

    BankingAccountView openAccount(BankingAccountOpeningRequest request);

    void issueCard(BankingCardIssuanceRequest request);

    BankingDoubleEntryPosting createDoubleEntryTransaction(BankingDoubleEntryPosting transaction);

    BankingProductView updateProductRate(String productCode, Double newRate);

    BankingProductView updateProductLimit(String productCode, Double newLimit);
}
