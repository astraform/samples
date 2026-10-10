package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankingPolicyKpiEvidenceAdapterTest {
    private final BankingPolicyKpiEvidenceAdapter adapter = new BankingPolicyKpiEvidenceAdapter();

    @Test
    void emptyResultsAreValidOnlyWhenCompiledPlansAreEmpty() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(customer("customer-1", "Alice"));

        BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence = adapter.adapt(
                manifest("Alice"),
                List.of(),
                List.of(),
                customers,
                customers,
                List.of(),
                List.of());

        assertThat(evidence.actions()).isEmpty();
        assertThat(evidence.effects()).isEmpty();
        assertThat(evidence.canonicalCustomerId("Alice")).isEqualTo("customer-1");

        BankingPolicyScenarioExecutionRuntime.ScenarioEvent expected = plan(event(
                "fraud-wave-1",
                "/policy/fraud-alert",
                Map.of("productCode", "CC001", "severity", "HIGH", "channel", "CARD"),
                true,
                Map.of()));
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"),
                List.of(),
                List.of(expected),
                customers,
                customers,
                List.of(),
                List.of()))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("missing intervention evidence");
    }

    @Test
    void requiresExactCompiledBranchPlanAndLaunchManifest() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(customer("customer-1", "Alice"));
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result = event(
                "fraud-wave-1",
                "/policy/fraud-alert",
                Map.of("productCode", "CC001", "severity", "HIGH", "channel", "CARD"),
                true,
                Map.of(
                        "productCode", "CC001",
                        "severity", "HIGH",
                        "channel", "CARD",
                        "alertedAgents", 1,
                        "affectedAccounts", 1));
        BankingPolicyScenarioExecutionRuntime.ScenarioEvent expected = plan(result);

        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(), customers, customers, List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("unexpected");
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(expected), List.of(), customers, customers, List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("missing intervention evidence baseline");

        BankingPolicyScenarioExecutionRuntime.ScenarioEvent wrongPath = new BankingPolicyScenarioExecutionRuntime.ScenarioEvent(
                expected.name(), expected.service(), expected.method(), "/policy/crash", expected.body(), Map.of(), 0);
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(wrongPath), customers, customers, List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("does not match the compiled plan");

        BankingPolicyScenarioExecutionRuntime.ScenarioEvent wrongFacts = new BankingPolicyScenarioExecutionRuntime.ScenarioEvent(
                expected.name(), expected.service(), expected.method(), expected.path(),
                Map.of("productCode", "CC999", "severity", "HIGH", "channel", "CARD"), Map.of(), 0);
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(wrongFacts), customers, customers, List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("facts do not match");

        BankingPolicyScenarioExecutionRuntime.ScenarioEvent wrongTiming =
                new BankingPolicyScenarioExecutionRuntime.ScenarioEvent(
                        expected.name(), expected.service(), expected.method(), expected.path(),
                        expected.body(), Map.of(), 86_400);
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(wrongTiming), customers, customers,
                List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("does not match the compiled plan");

        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice", "Bob"), List.of(), List.of(expected), customers, customers, List.of(), List.of(result)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("customer manifest");
    }

    @Test
    void mapsActualFraudResponseCountersWithoutInventingCustomerIdentities() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(
                customer("customer-1", "Alice"), customer("customer-2", "Bob"));
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result = event(
                "fraud-wave-1",
                "/policy/fraud-alert",
                Map.of("productCode", "CC001", "severity", "HIGH", "channel", "CARD"),
                true,
                Map.of(
                        "productCode", "CC001",
                        "severity", "HIGH",
                        "channel", "CARD",
                        "alertedAgents", 2,
                        "affectedAccounts", 3));

        BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence = adapter.adapt(
                manifest("Alice", "Bob"),
                List.of(),
                List.of(plan(result)),
                customers,
                customers,
                List.of(),
                List.of(result));

        BankingPolicyKpiEvaluator.InterventionEffect effect = evidence.effects().getFirst();
        assertThat(effect.affectedCustomerCount()).isEqualTo(2);
        assertThat(effect.affectedAccountCount()).isEqualTo(3);
        assertThat(effect.affectedCustomerIds()).isEmpty();
    }

    @Test
    void resolvesHardshipRecipientsToOneCanonicalCustomerIdentity() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(customer("customer-1", "Alice"));
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result = event(
                "hardship-scan-1",
                "/policy/hardship-scan",
                Map.of("subsidyAmount", new BigDecimal("75"), "fundingSource", "BANK"),
                true,
                Map.of(
                        "hardshipDetections", 1,
                        "subsidiesIssued", 1,
                        "skippedCooldownCount", 0,
                        "insufficientFundCount", 0,
                        "subsidyAmount", new BigDecimal("75"),
                        "fundingSource", "BANK",
                        "reliefRecipients", List.of("Alice")));

        BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence = adapter.adapt(
                manifest("Alice"),
                List.of(),
                List.of(plan(result)),
                customers,
                customers,
                List.of(),
                List.of(result));

        assertThat(evidence.effects().getFirst().affectedCustomerIds()).containsExactly("customer-1");
        assertThat(evidence.effects().getFirst().amount()).isEqualByComparingTo("75");
        assertThat(evidence.effects().getFirst().fundingSource())
                .isEqualTo(BankingPolicyKpiEvaluator.FundingSource.BANK);
    }

    @Test
    void recordsExternalStimulusFundingWithoutInventingBankCostOwnership() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(customer("customer-1", "Alice"));
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result = event(
                "stimulus-1",
                "/policy/stimulus",
                Map.of("amount", new BigDecimal("250"), "fundingSource", "EXTERNAL"),
                true,
                Map.of(
                        "amount", new BigDecimal("250"),
                        "fundingSource", "EXTERNAL",
                        "injectedAgents", 1,
                        "notifiedAgents", 1));

        BankingPolicyKpiEvidenceAdapter.AdaptedEvidence evidence = adapter.adapt(
                manifest("Alice"), List.of(), List.of(plan(result)), customers, customers,
                List.of(), List.of(result));

        assertThat(evidence.effects().getFirst().amount()).isEqualByComparingTo("250");
        assertThat(evidence.effects().getFirst().fundingSource())
                .isEqualTo(BankingPolicyKpiEvaluator.FundingSource.EXTERNAL);
    }

    @Test
    void rejectsDisplayIdentitySharedByDifferentCustomers() {
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Shared Name"),
                List.of(),
                List.of(),
                List.of(customer("customer-1", "Shared Name")),
                List.of(customer("customer-2", "Shared Name")),
                List.of(),
                List.of()))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("identity alias is contradictory");
    }

    @Test
    void failedOrContradictoryInterventionEvidenceIsIncomplete() {
        List<BankingPolicyScenarioAgentSnapshot> customers = List.of(customer("customer-1", "Alice"));
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult failed = event(
                "hardship-scan-1",
                "/policy/hardship-scan",
                Map.of("fundingSource", "BANK"),
                false,
                Map.of());
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult contradictory = event(
                "hardship-scan-1",
                "/policy/hardship-scan",
                Map.of("subsidyAmount", new BigDecimal("75"), "fundingSource", "BANK"),
                true,
                Map.of(
                        "hardshipDetections", 1,
                        "subsidiesIssued", 0,
                        "skippedCooldownCount", 0,
                        "insufficientFundCount", 0,
                        "subsidyAmount", new BigDecimal("75"),
                        "fundingSource", "BANK",
                        "reliefRecipients", List.of("Alice")));

        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(plan(failed)), customers, customers,
                List.of(), List.of(failed)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("failed intervention evidence");
        assertThatThrownBy(() -> adapter.adapt(
                manifest("Alice"), List.of(), List.of(plan(contradictory)), customers, customers,
                List.of(), List.of(contradictory)))
                .isInstanceOf(BankingPolicyKpiEvidenceAdapter.KpiEvidenceIncompleteException.class)
                .hasMessageContaining("contradictory");
    }

    private static BankingPolicyScenarioExecutionRuntime.ScenarioEvent plan(
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        return new BankingPolicyScenarioExecutionRuntime.ScenarioEvent(
                result.name(),
                result.service(),
                result.method(),
                result.path(),
                result.requestPayload(),
                Map.of(),
                result.afterElapsedSeconds());
    }

    private static BankingPolicyScenarioExecutionRuntime.ScenarioEventResult event(
            String name,
            String path,
            Map<String, Object> request,
            boolean ok,
            Map<String, Object> response) {
        return new BankingPolicyScenarioExecutionRuntime.ScenarioEventResult(
                name,
                "banking-policy-pack",
                "POST",
                path,
                0,
                request,
                ok,
                response,
                ok ? "" : "event_failed");
    }

    private static List<Map<String, Object>> manifest(String... personaNames) {
        return java.util.Arrays.stream(personaNames)
                .map(personaName -> Map.<String, Object>of("personaName", personaName))
                .toList();
    }

    private static BankingPolicyScenarioAgentSnapshot customer(String agentId, String personaName) {
        return new BankingPolicyScenarioAgentSnapshot(
                agentId,
                "banking:" + agentId,
                personaName,
                "retail",
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                List.of(),
                List.of(),
                Map.of());
    }
}
