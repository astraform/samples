package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BankingRuntimeControlSupportTest {

    @Test
    void runHardshipScanAggregatesOnlyPolicyResults() {
        BankingRuntimeControlSupport support = new BankingRuntimeControlSupport(new NoopGateway());
        ZonedDateTime eventTime = ZonedDateTime.parse("2026-03-07T00:00:00Z");

        BankingRuntimeAgentAccess policyAgent = new FakeRuntimeAgent(
                "Policy Agent",
                true,
                99L,
                List.of(),
                List.of(),
                new BankingHardshipScanResult(
                        "Policy Agent",
                        5,
                        3,
                        2,
                        1,
                        1,
                        0,
                        100.0,
                        500.0,
                        List.of("Customer One"),
                        List.of("issued"),
                        "ok"));

        BankingRuntimeAgentAccess customerAgent = new FakeRuntimeAgent(
                "Customer One",
                true,
                1L,
                List.of(),
                List.of(),
                null);

        BankingHardshipScanSummary summary = support.runHardshipScan(
                List.of(policyAgent, customerAgent),
                eventTime,
                100.0,
                500.0);

        assertEquals(1, summary.policyAgentsScanned());
        assertEquals(List.of("Policy Agent"), summary.policyAgentNames());
        assertEquals(5, summary.activeAgentsSeen());
        assertEquals(3, summary.analysedAgents());
        assertEquals(2, summary.hardshipDetections());
        assertEquals(1, summary.subsidiesIssued());
        assertEquals(List.of("Customer One"), summary.reliefRecipients());
        assertEquals(1, summary.skippedCooldownCount());
        assertEquals(0, summary.insufficientFundCount());
    }

    @Test
    void findAffectedAccountsPrefersStatusSnapshotBeforeGatewayLookup() {
        BankingRuntimeControlSupport support = new BankingRuntimeControlSupport(new NoopGateway());
        BankingRuntimeAgentAccess agent = new FakeRuntimeAgent(
                "Customer One",
                true,
                1L,
                List.of(
                        new BankingAccountView(1L, "Card", "CC001", -1200.0, 5000.0, null, "AUD"),
                        new BankingAccountView(2L, "Savings", "SV001", 500.0, null, null, "AUD")),
                List.of(),
                null);

        List<BankingAccountView> affected = support.findAffectedAccounts(agent, "CC001");

        assertEquals(1, affected.size());
        assertEquals("CC001", affected.getFirst().productCode());
    }

    private record FakeRuntimeAgent(
            String displayName,
            boolean isActive,
            Long bankingCustomerId,
            List<BankingAccountView> statusAccountViewsSnapshot,
            List<BankingAccountView> originatedAccountViewsSnapshot,
            BankingHardshipScanResult hardshipScanResult) implements BankingRuntimeAgentAccess {

        @Override
        public void injectTransaction(double amount, String description) {
        }

        @Override
        public BankingHardshipScanResult runHardshipReliefScan(
                ZonedDateTime eventTime,
                BankingHardshipScanPolicy scanPolicy) {
            return hardshipScanResult;
        }
    }

    private static final class NoopGateway implements BankingRuntimeGateway {

        @Override
        public Long createBankingCustomer(BankingCustomerProfile customerProfile) {
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
        public BankingDoubleEntryPosting createDoubleEntryTransaction(BankingDoubleEntryPosting posting) {
            return posting;
        }

        @Override
        public BankingProductView updateProductRate(String productCode, Double rate) {
            return new BankingProductView(null, productCode, null, null, null, List.of());
        }

        @Override
        public BankingProductView updateProductLimit(String productCode, Double limit) {
            return new BankingProductView(null, productCode, null, null, null, List.of());
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
        public BankingMerchantView createMerchant(BankingMerchantProfile merchantProfile) {
            throw new UnsupportedOperationException();
        }
    }
}
