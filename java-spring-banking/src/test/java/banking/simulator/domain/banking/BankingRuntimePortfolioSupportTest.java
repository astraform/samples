package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.Card;
import banking.simulator.domain.banking.model.TransactionAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BankingRuntimePortfolioSupportTest {

    @Test
    void projectAccounts_buildsRuntimeAccountsAndNumberIndex() {
        BankingRuntimePortfolioProjection projection = BankingRuntimePortfolioSupport.projectAccounts(
                List.of(
                        new BankingAccountView(101L, "Everyday", "TS001", 1_250.0, null, 111111, "AUD"),
                        new BankingAccountView(202L, "Rewards", "CC001", -320.0, 5_000.0, null, "AUD")),
                productCode -> switch (productCode) {
                    case "TS001" -> Optional.of(new BankingProductView(
                            1L,
                            "TS001",
                            "Everyday",
                            "Everyday",
                            "TRANS_AND_SAVINGS_ACCOUNTS",
                            List.of()));
                    case "CC001" -> Optional.of(new BankingProductView(
                            2L,
                            "CC001",
                            "Rewards",
                            "Rewards",
                            "CRED_AND_CHRG_CARDS",
                            List.of()));
                    default -> Optional.empty();
                });

        assertEquals(2, projection.accountsByName().size());
        assertInstanceOf(TransactionAccount.class, projection.accountsByName().get("Everyday"));
        assertInstanceOf(CreditAccount.class, projection.accountsByName().get("Rewards"));
        assertEquals(2, projection.accountsByNumber().size());
        assertEquals(101L, projection.accountsByName().get("Everyday").getNumber());
        assertEquals(202L, projection.accountsByName().get("Rewards").getNumber());
    }

    @Test
    void toAccountViews_projectsStableSortedSnapshot() {
        BankingRuntimePortfolioProjection projection = BankingRuntimePortfolioSupport.projectAccounts(
                List.of(
                        new BankingAccountView(202L, "Rewards", "CC001", -320.0, 5_000.0, null, "AUD"),
                        new BankingAccountView(101L, "Everyday", "TS001", 1_250.0, null, 111111, "AUD")),
                productCode -> switch (productCode) {
                    case "TS001" -> Optional.of(new BankingProductView(
                            1L,
                            "TS001",
                            "Everyday",
                            "Everyday",
                            "TRANS_AND_SAVINGS_ACCOUNTS",
                            List.of()));
                    case "CC001" -> Optional.of(new BankingProductView(
                            2L,
                            "CC001",
                            "Rewards",
                            "Rewards",
                            "CRED_AND_CHRG_CARDS",
                            List.of()));
                    default -> Optional.empty();
                });

        List<BankingAccountView> snapshot = BankingRuntimePortfolioSupport.toAccountViews(projection.accountsByName());

        assertEquals(2, snapshot.size());
        assertEquals("Everyday", snapshot.get(0).name());
        assertEquals("Rewards", snapshot.get(1).name());
    }

    @Test
    void originateAccounts_buildsRuntimePortfolioFromSeedSpecs() {
        BankingRuntimeGateway gateway = new BankingRuntimeGateway() {
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
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<BankingProductView> getProduct(String productCode) {
                return switch (productCode) {
                    case "TS001" -> Optional.of(new BankingProductView(
                            1L,
                            "TS001",
                            "Everyday",
                            "Everyday",
                            "TRANS_AND_SAVINGS_ACCOUNTS",
                            List.of()));
                    case "CC001" -> Optional.of(new BankingProductView(
                            2L,
                            "CC001",
                            "Rewards",
                            "Rewards",
                            "CRED_AND_CHRG_CARDS",
                            List.of()));
                    default -> Optional.empty();
                };
            }

            @Override
            public BankingAccountView openAccount(BankingAccountOpeningRequest request) {
                if ("Everyday".equals(request.name())) {
                    return new BankingAccountView(101L, "Everyday", "TS001", 1_250.0, null, 111111, "AUD");
                }
                return new BankingAccountView(202L, "Rewards", "CC001", -320.0, 5_000.0, null, "AUD");
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
        };

        BankingRuntimePortfolioBootstrapResult result = BankingRuntimePortfolioSupport.originateAccounts(
                gateway,
                42L,
                new BankingCustomerProfile("Test Persona", "Budget conscious", java.time.LocalDate.parse("1990-01-01")),
                List.of(
                        new BankingSeedAccountSpec("Everyday", "TS001", 1_250.0, null, 111111, "AUD", null, null, null, null),
                        new BankingSeedAccountSpec("Rewards", "CC001", -320.0, 5_000.0, null, "AUD", null, null, null, null)));

        assertEquals(List.of(101L, 202L), result.createdAccountIds());
        assertEquals(2, result.accountsByName().size());
        assertInstanceOf(TransactionAccount.class, result.accountsByName().get("Everyday"));
        assertInstanceOf(CreditAccount.class, result.accountsByName().get("Rewards"));
        Account rewards = result.accountsByName().get("Rewards");
        assertEquals(202L, rewards.getNumber());
        assertTrue(result.accountViewsByName().containsKey("Everyday"));
    }

    @Test
    void bootstrapPortfolio_populatesPortfolioAndIssuesRequestedCards() {
        List<BankingCardIssuanceRequest> issuedCards = new ArrayList<>();
        BankingRuntimeGateway gateway = new BankingRuntimeGateway() {
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
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<BankingProductView> getProduct(String productCode) {
                return switch (productCode) {
                    case "TS001" -> Optional.of(new BankingProductView(
                            1L,
                            "TS001",
                            "Everyday",
                            "Everyday",
                            "TRANS_AND_SAVINGS_ACCOUNTS",
                            List.of()));
                    case "CC001" -> Optional.of(new BankingProductView(
                            2L,
                            "CC001",
                            "Rewards",
                            "Rewards",
                            "CRED_AND_CHRG_CARDS",
                            List.of()));
                    default -> Optional.empty();
                };
            }

            @Override
            public BankingAccountView openAccount(BankingAccountOpeningRequest request) {
                if ("Everyday".equals(request.name())) {
                    return new BankingAccountView(101L, "Everyday", "TS001", 1_250.0, null, 111111, "AUD");
                }
                return new BankingAccountView(202L, "Rewards", "CC001", -320.0, 5_000.0, null, "AUD");
            }

            @Override
            public void issueCard(BankingCardIssuanceRequest request) {
                issuedCards.add(request);
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
        };

        TestPortfolio portfolio = new TestPortfolio();
        BankingRuntimePortfolioBootstrapResult result = BankingRuntimePortfolioSupport.bootstrapPortfolio(
                gateway,
                42L,
                new BankingCustomerProfile("Test Persona", "Budget conscious", java.time.LocalDate.parse("1990-01-01")),
                List.of(
                        new BankingSeedAccountPlan(
                                new BankingSeedAccountSpec("Everyday", "TS001", 1_250.0, null, 111111, "AUD", null, null, null, null),
                                true),
                        new BankingSeedAccountPlan(
                                new BankingSeedAccountSpec("Rewards", "CC001", -320.0, 5_000.0, null, "AUD", null, null, null, null),
                                false)),
                portfolio);

        assertEquals(List.of(101L, 202L), result.createdAccountIds());
        assertEquals(2, portfolio.originatedAccounts().size());
        assertEquals(2, portfolio.originatedCards().size());
        assertEquals(2, issuedCards.size());
        assertEquals(BankingCardType.DEBIT, issuedCards.get(0).cardType());
        assertEquals(BankingCardType.CREDIT, issuedCards.get(1).cardType());
    }

    private static final class TestPortfolio implements BankingRuntimePortfolioAccess {
        private final Map<String, Account> originatedAccounts = new LinkedHashMap<>();
        private final List<Card> originatedCards = new ArrayList<>();
        private final Object refreshMonitor = new Object();
        private Instant lastRefreshAttemptAt;

        @Override
        public Map<String, Account> originatedAccounts() {
            return originatedAccounts;
        }

        @Override
        public List<Card> originatedCards() {
            return originatedCards;
        }

        @Override
        public Object refreshMonitor() {
            return refreshMonitor;
        }

        @Override
        public Instant lastRefreshAttemptAt() {
            return lastRefreshAttemptAt;
        }

        @Override
        public void lastRefreshAttemptAt(Instant instant) {
            this.lastRefreshAttemptAt = instant;
        }
    }
}
