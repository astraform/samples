package banking.simulator.remote.domain.scenariolab;

import banking.simulator.domain.banking.BankingDomainIds;
import org.junit.jupiter.api.Test;
import ai.astraform.remote.domain.sdk.CelPolicyExpressionEvaluator;
import ai.astraform.remote.domain.sdk.PolicyExpressionContext;
import ai.astraform.remote.domain.sdk.PolicyExpressionDefinition;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankingPolicyWindTunnelManifestTest {

    private final BankingPolicyWindTunnelManifest manifest = new BankingPolicyWindTunnelManifest();

    @Test
    void exposesBankingOwnedCatalogAndSchemaMetadata() {
        Map<String, Object> catalog = manifest.presetCatalog();

        assertEquals(BankingPolicyWindTunnelManifest.PACK_ID, manifest.descriptor().packId());
        assertEquals(BankingDomainIds.BANKING, manifest.descriptor().domainId());
        assertEquals("banking-product-endpoint", BankingPolicyWindTunnelManifest.BANKING_PRODUCT_ENDPOINT_DEPENDENCY);
        assertEquals("banking_scenario_lab_presets.v1", catalog.get("schemaVersion"));
        assertEquals(BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION, catalog.get("summarySchemaVersion"));
        assertTrue(catalog.containsKey("controls"));
        assertTrue(catalog.containsKey("uiSchema"));
        assertEquals("domain_outcome_schema.v1", manifest.outcomeSchema().get("schemaVersion"));
        assertEquals("domain_outcome_scorecard.v1", manifest.outcomeScorecard().get("schemaVersion"));
        assertEquals("banking.customer-impact.v1", manifest.outcomeScorecard().get("scorecardId"));
        Map<String, Object> capabilities = manifest.capabilities();
        assertEquals(
                "LEGACY_V1",
                capabilities.get("opportunityGenerationFinalizationMode"));
        assertEquals(false, capabilities.get("platformOwnedV2StorageReady"));
        assertEquals(
                "PLATFORM_PINNED_BYTES_V1",
                capabilities.get("cohortActivationMode"));
        assertEquals(List.of("CLOCK_DRIVEN_HYBRID"), capabilities.get("executionModes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> realLlmPolicy = (Map<String, Object>) capabilities.get("realLlmCanaryPolicy");
        assertEquals("real_llm_canary_policy.v1", realLlmPolicy.get("schemaVersion"));
        assertEquals(30, realLlmPolicy.get("maxDays"));
        assertEquals(70, realLlmPolicy.get("maxCalls"));
        assertEquals(List.of("EVENT_ONLY", "DAILY", "WEEKLY"), realLlmPolicy.get("allowedCadences"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outcomeRoles =
                (Map<String, Object>) capabilities.get("domainOutcomeScorecardRoles");
        assertEquals("domain_outcome_scorecard_roles.v1", outcomeRoles.get("schemaVersion"));
        assertEquals(
                List.of(
                        Map.of(
                                "scorecardId", "banking.customer-impact.v1",
                                "role", "BUSINESS_OUTCOME"),
                        Map.of(
                                "scorecardId", "banking.evidence-readiness.v1",
                                "role", "PROOF_GATE")),
                outcomeRoles.get("roles"));
        assertEquals(2, manifest.outcomeScorecards().size());
        for (Map<String, Object> scorecard : manifest.outcomeScorecards()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> appliesTo = (Map<String, Object>) scorecard.get("appliesTo");
            assertEquals(List.of("CLOCK_DRIVEN_HYBRID"), appliesTo.get("executionModes"));
        }
        assertTrue(manifest.outcomeSchema().toString().contains("hardship"));
        assertTrue(manifest.outcomeScorecard().toString().contains("branchStateProjectionJson"));
        assertTrue(manifest.outcomeScorecards().toString().contains("banking.evidence-readiness.v1"));
        assertFalse(((List<?>) catalog.get("presets")).isEmpty());
        Map<?, ?> creditAprLift = ((List<?>) catalog.get("presets")).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(preset -> "credit-apr-lift".equals(preset.get("presetId")))
                .findFirst()
                .orElseThrow();
        assertEquals(true, creditAprLift.get("certified"));
        assertEquals(List.of("baselineRate", "candidateRate"), creditAprLift.get("guidedEditableFields"));
        assertEquals(0.19, creditAprLift.get("baselineRate"));
        assertEquals(0.24, creditAprLift.get("candidateRate"));
    }

    @Test
    void publishesCelPolicyExpressionsForBankingDecisionGates() {
        List<PolicyExpressionDefinition> expressions = manifest.policyExpressions();
        assertEquals(2, expressions.size());
        assertTrue(expressions.stream()
                .anyMatch(expression -> "banking.rate.candidate-rate-floor".equals(expression.expressionId())));

        CelPolicyExpressionEvaluator evaluator = new CelPolicyExpressionEvaluator();
        PolicyExpressionContext context = PolicyExpressionContext.create(
                BankingDomainIds.BANKING,
                BankingPolicyWindTunnelManifest.PACK_ID,
                PolicyExpressionStage.BRANCH_GUARD,
                "candidate",
                Map.of(),
                Map.of(
                        "baselineRate", 0.03,
                        "candidateRate", 0.08,
                        "candidateHardshipScan", true,
                        "candidateHardshipThreshold", 250.0),
                Map.of(),
                Map.of(),
                Map.of());

        assertTrue(expressions.stream()
                .allMatch(expression -> evaluator.evaluate(expression, context).passed()));
    }
}
