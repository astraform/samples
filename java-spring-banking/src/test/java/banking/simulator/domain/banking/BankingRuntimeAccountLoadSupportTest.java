package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BankingRuntimeAccountLoadSupportTest {

    @Test
    void preferredAccountNames_ordersDepositAccountsAheadOfCreditAccounts() {
        BankingRuntimeAccountLoadAccess access = new FixedAccountLoadAccess(
                42L,
                List.of(
                        new BankingAccountView(2L, "Rewards", "CC001", -320.0, 5_000.0, null, "AUD"),
                        new BankingAccountView(1L, "Everyday", "TS001", 1_250.0, null, 111111, "AUD")));

        assertEquals(List.of("Everyday", "Rewards"), BankingRuntimeAccountLoadSupport.preferredAccountNames(access));
    }

    @Test
    void loadLiveOrStatusAccounts_fallsBackToStatusSnapshotWhenLiveLookupFails() {
        BankingRuntimeAccountLoadAccess access = new BankingRuntimeAccountLoadAccess() {
            @Override
            public Long bankingCustomerId() {
                return 42L;
            }

            @Override
            public BankingRuntimeGateway runtimeGateway() {
                return new ThrowingGateway();
            }

            @Override
            public List<BankingAccountView> statusAccountViewsSnapshot() {
                return List.of(new BankingAccountView(1L, "Fallback", "TS001", 100.0, null, null, "AUD"));
            }
        };

        List<BankingAccountView> accounts = BankingRuntimeAccountLoadSupport.loadLiveOrStatusAccounts(access);

        assertEquals(1, accounts.size());
        assertEquals("Fallback", accounts.getFirst().name());
    }

    @Test
    void loadLiveOrOriginatedAccounts_fallsBackToOriginatedSnapshotWhenLiveLookupIsEmpty() {
        BankingRuntimeAccountLoadAccess access = new BankingRuntimeAccountLoadAccess() {
            @Override
            public Long bankingCustomerId() {
                return 42L;
            }

            @Override
            public BankingRuntimeGateway runtimeGateway() {
                return new EmptyGateway();
            }

            @Override
            public List<BankingAccountView> originatedAccountViewsSnapshot() {
                return List.of(new BankingAccountView(7L, "Seeded", "TS001", 250.0, null, null, "AUD"));
            }
        };

        List<BankingAccountView> accounts = BankingRuntimeAccountLoadSupport.loadLiveOrOriginatedAccounts(access);

        assertEquals(1, accounts.size());
        assertEquals("Seeded", accounts.getFirst().name());
    }

    @Test
    void loadLiveAccounts_returnsEmptyWhenNoBankingIdentityExists() {
        BankingRuntimeAccountLoadAccess access = new FixedAccountLoadAccess(null, List.of());

        assertTrue(BankingRuntimeAccountLoadSupport.loadLiveAccounts(access).isEmpty());
    }

    private record FixedAccountLoadAccess(Long bankingCustomerId, List<BankingAccountView> liveAccounts)
            implements BankingRuntimeAccountLoadAccess {

        @Override
        public BankingRuntimeGateway runtimeGateway() {
            return new FixedGateway(liveAccounts);
        }
    }

    private record FixedGateway(List<BankingAccountView> liveAccounts) implements BankingRuntimeGateway {
        @Override
        public Long createBankingCustomer(BankingCustomerProfile customerProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingMerchantView createMerchant(BankingMerchantProfile merchantProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BankingAccountView> getAccountViewsForCustomer(Long customerId) {
            return liveAccounts;
        }

        @Override
        public Optional<BankingProductView> getProduct(String productCode) {
            return Optional.empty();
        }

        @Override
        public BankingAccountView openAccount(BankingAccountOpeningRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void issueCard(BankingCardIssuanceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingDoubleEntryPosting createDoubleEntryTransaction(BankingDoubleEntryPosting transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductRate(String productCode, Double newRate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductLimit(String productCode, Double newLimit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyGateway implements BankingRuntimeGateway {
        EmptyGateway() {
        }

        @Override
        public Long createBankingCustomer(BankingCustomerProfile customerProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingMerchantView createMerchant(BankingMerchantProfile merchantProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BankingAccountView> getAccountViewsForCustomer(Long customerId) {
            return List.of();
        }

        @Override
        public Optional<BankingProductView> getProduct(String productCode) {
            return Optional.empty();
        }

        @Override
        public BankingAccountView openAccount(BankingAccountOpeningRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void issueCard(BankingCardIssuanceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingDoubleEntryPosting createDoubleEntryTransaction(BankingDoubleEntryPosting transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductRate(String productCode, Double newRate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductLimit(String productCode, Double newLimit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ThrowingGateway implements BankingRuntimeGateway {
        @Override
        public Long createBankingCustomer(BankingCustomerProfile customerProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingMerchantView createMerchant(BankingMerchantProfile merchantProfile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BankingAccountView> getAccountViewsForCustomer(Long customerId) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Optional<BankingProductView> getProduct(String productCode) {
            return Optional.empty();
        }

        @Override
        public BankingAccountView openAccount(BankingAccountOpeningRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void issueCard(BankingCardIssuanceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingDoubleEntryPosting createDoubleEntryTransaction(BankingDoubleEntryPosting transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductRate(String productCode, Double newRate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankingProductView updateProductLimit(String productCode, Double newLimit) {
            throw new UnsupportedOperationException();
        }
    }
}
