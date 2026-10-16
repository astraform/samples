package platform.marketingops.remote.scenariolab;

import org.junit.jupiter.api.Test;
import ai.astraform.remote.domain.sdk.CelPolicyExpressionEvaluator;
import ai.astraform.remote.domain.sdk.PolicyExpressionContext;
import ai.astraform.remote.domain.sdk.PolicyExpressionDefinition;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketingOpsPolicyWindTunnelDefinitionTest {

    private final MarketingOpsPolicyWindTunnelDefinition definition =
            new MarketingOpsPolicyWindTunnelDefinition();

    @Test
    void normalizesPayloadInsideDomainDefinition() {
        Map<String, Object> normalized = definition.normalizePayload(Map.of(
                "presetId", "",
                "blockerCount", 999,
                "reviewCapacity", 0,
                "slaTargetHours", 1,
                "approvalStrictness", "invalid"));

        assertEquals(MarketingOpsPolicyWindTunnelDefinition.DEFAULT_PRESET_ID, normalized.get("presetId"));
        assertEquals(50, normalized.get("blockerCount"));
        assertEquals(1, normalized.get("reviewCapacity"));
        assertEquals(4, normalized.get("slaTargetHours"));
        assertEquals("balanced", normalized.get("approvalStrictness"));
    }

    @Test
    void buildsGenericSummaryEnvelopeWithoutExperimentServiceTypes() {
        Map<String, Object> payload = definition.normalizePayload(Map.of(
                "blockerCount", 14,
                "reviewCapacity", 9,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced"));
        Map<String, Object> summary = definition.buildSummary(
                "marketing-wt-test",
                payload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));

        assertEquals(MarketingOpsPolicyWindTunnelDefinition.SUMMARY_SCHEMA_VERSION,
                summary.get("schemaVersion"));
        assertEquals(MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID, summary.get("domainId"));
        assertEquals(MarketingOpsPolicyWindTunnelDefinition.PACK_ID, summary.get("packId"));
        assertEquals("marketing-wt-test", summary.get("runId"));
        assertInstanceOf(Map.class, summary.get("domainPayload"));
        List<?> metrics = assertInstanceOf(List.class, summary.get("metrics"));
        assertFalse(metrics.isEmpty());
        assertTrue(summary.containsKey("branches"));
        assertTrue(summary.containsKey("decisionGates"));
    }

    @Test
    void generatedPopulationSelectionChangesLaunchReadinessSummary() {
        Map<String, Object> supportHeavyPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "marketing-ops-campaign-readiness-100-support-heavy",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "scheduledWorkItems", 100,
                        "segmentCounts", Map.of(
                                "support-sensitive", 80,
                                "launch-audience", 20)),
                "trustedPopulationBundle", trustedPopulationBundle(
                        "marketing-ops-campaign-readiness-100-support-heavy",
                        100,
                        "valid",
                        Map.of(
                                "support-sensitive", 80,
                                "launch-audience", 20))));
        Map<String, Object> highIntentPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "marketing-ops-campaign-readiness-100-high-intent",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "scheduledWorkItems", 100,
                        "segmentCounts", Map.of(
                                "high-intent", 80,
                                "launch-audience", 20)),
                "trustedPopulationBundle", trustedPopulationBundle(
                        "marketing-ops-campaign-readiness-100-high-intent",
                        100,
                        "valid",
                        Map.of(
                                "high-intent", 80,
                                "launch-audience", 20))));

        Map<String, Object> supportHeavySummary = definition.buildSummary(
                "marketing-wt-support-heavy",
                supportHeavyPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));
        Map<String, Object> highIntentSummary = definition.buildSummary(
                "marketing-wt-high-intent",
                highIntentPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));

        @SuppressWarnings("unchecked")
        Map<String, Object> population = (Map<String, Object>) supportHeavySummary.get("population");
        assertEquals(Boolean.TRUE, population.get("consumed"));
        assertEquals("marketing-ops-campaign-readiness-100-support-heavy", population.get("cohortId"));
        assertEquals("campaign-readiness-100", population.get("recipeId"));
        assertEquals(100, population.get("targetSize"));
        assertTrue(((Number) population.get("blockerPressure")).intValue() > 0);

        int supportEscalations = candidateEscalations(supportHeavySummary);
        int highIntentEscalations = candidateEscalations(highIntentSummary);
        assertTrue(
                supportEscalations > highIntentEscalations,
                "Different generated population segment mixes should change Marketing Ops Wind Tunnel output.");
    }

    @Test
    void untrustedPopulationSelectionDoesNotChangeLaunchReadinessSummary() {
        Map<String, Object> untrustedPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "forged-population",
                        "domainId", "other-domain",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "segmentCounts", Map.of("support-sensitive", 100))));
        Map<String, Object> selfCertifiedPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "self-certified-population",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "segmentCounts", Map.of("support-sensitive", 100))));
        Map<String, Object> oversizedPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "oversized-population",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 101,
                        "segmentCounts", Map.of("support-sensitive", 101)),
                "trustedPopulationBundle", trustedPopulationBundle(
                        "oversized-population",
                        101,
                        "valid",
                        Map.of("support-sensitive", 101))));
        Map<String, Object> missingValidationPayload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "invalid-population",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "invalid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "segmentCounts", Map.of("support-sensitive", 100)),
                "trustedPopulationBundle", trustedPopulationBundle(
                        "invalid-population",
                        100,
                        "invalid",
                        Map.of("support-sensitive", 100))));

        Map<String, Object> untrustedSummary = definition.buildSummary(
                "marketing-wt-untrusted",
                untrustedPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));
        Map<String, Object> selfCertifiedSummary = definition.buildSummary(
                "marketing-wt-self-certified",
                selfCertifiedPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));
        Map<String, Object> oversizedSummary = definition.buildSummary(
                "marketing-wt-oversized",
                oversizedPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));
        Map<String, Object> invalidSummary = definition.buildSummary(
                "marketing-wt-invalid",
                missingValidationPayload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));

        assertEquals(Boolean.FALSE, population(untrustedSummary).get("consumed"));
        assertEquals("domain_mismatch", population(untrustedSummary).get("untrustedReason"));
        assertEquals(Boolean.FALSE, population(selfCertifiedSummary).get("consumed"));
        assertEquals("trusted_population_bundle_missing", population(selfCertifiedSummary).get("untrustedReason"));
        assertEquals(Boolean.FALSE, population(oversizedSummary).get("consumed"));
        assertEquals("target_size_exceeds_wind_tunnel_limit", population(oversizedSummary).get("untrustedReason"));
        assertEquals(Boolean.FALSE, population(invalidSummary).get("consumed"));
        assertEquals("population_not_validated", population(invalidSummary).get("untrustedReason"));
        assertEquals(0, population(untrustedSummary).get("blockerPressure"));
        assertEquals(0, population(selfCertifiedSummary).get("blockerPressure"));
        assertEquals(0, population(oversizedSummary).get("blockerPressure"));
        assertEquals(0, population(invalidSummary).get("blockerPressure"));
    }

    @Test
    void oversizedTrustedTargetDoesNotWrapIntoValidPopulation() {
        Map<String, Object> payload = definition.normalizePayload(Map.of(
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", "wrapped-target-population",
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "validationStatus", "valid",
                        "runPathEligibility", scenarioReadyEligibility(),
                        "targetSize", 100,
                        "segmentCounts", Map.of("support-sensitive", 100)),
                "trustedPopulationBundle", trustedPopulationBundle(
                        "wrapped-target-population",
                        4_294_967_396L,
                        "valid",
                        Map.of("support-sensitive", 100))));

        Map<String, Object> summary = definition.buildSummary(
                "marketing-wt-wrapped-target",
                payload,
                OffsetDateTime.of(2026, 4, 26, 0, 0, 0, 0, ZoneOffset.UTC));

        assertEquals(Boolean.FALSE, population(summary).get("consumed"));
        assertEquals("trusted_population_target_mismatch", population(summary).get("untrustedReason"));
        assertEquals(0, population(summary).get("blockerPressure"));
    }

    @Test
    void publishesCelPolicyExpressionsForLaunchDecisionGates() {
        List<PolicyExpressionDefinition> expressions = definition.policyExpressions();
        assertEquals(2, expressions.size());
        assertTrue(expressions.stream()
                .anyMatch(expression -> "marketing.launch.sla-risk".equals(expression.expressionId())));

        CelPolicyExpressionEvaluator evaluator = new CelPolicyExpressionEvaluator();
        PolicyExpressionContext context = PolicyExpressionContext.create(
                MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID,
                MarketingOpsPolicyWindTunnelDefinition.PACK_ID,
                PolicyExpressionStage.DECISION_GATE,
                "candidate",
                Map.of(),
                Map.of("approvalStrictness", "balanced"),
                Map.of(),
                Map.of(
                        "slaRiskPct", 0.18,
                        "launchReadinessPct", 87.5),
                Map.of());

        assertTrue(expressions.stream()
                .allMatch(expression -> evaluator.evaluate(expression, context).passed()));
    }

    @Test
    void rendersExperimentCardFromDomainOwnedSummary() {
        Map<String, Object> payload = definition.normalizePayload(Map.of());
        Map<String, Object> summary = definition.buildSummary(
                "marketing-wt-card",
                payload,
                OffsetDateTime.now(ZoneOffset.UTC));

        String card = definition.buildExperimentCard("marketing-wt-card", summary);

        assertTrue(card.contains("Marketing Ops Policy Wind Tunnel"));
        assertTrue(card.contains("marketing-wt-card"));
        assertTrue(card.contains("Approval strictness"));
    }

    @SuppressWarnings("unchecked")
    private int candidateEscalations(Map<String, Object> summary) {
        List<Map<String, Object>> branches = (List<Map<String, Object>>) summary.get("branches");
        Map<String, Object> candidate = branches.get(1);
        Map<String, Object> metrics = (Map<String, Object>) candidate.get("metrics");
        return ((Number) metrics.get("escalations")).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> population(Map<String, Object> summary) {
        return (Map<String, Object>) summary.get("population");
    }

    private Map<String, Object> scenarioReadyEligibility() {
        return Map.of(
                "status", "SCENARIO_READY",
                "windTunnelReady", true,
                "windTunnelConsumesPopulation", true);
    }

    private Map<String, Object> trustedPopulationBundle(
            String cohortId,
            Object targetSize,
            String validationStatus,
            Map<String, Object> segmentCounts) {
        return Map.of(
                "schemaVersion", "synthetic_cohort_bundle.v1",
                "cohortId", cohortId,
                "domainId", "marketing-ops",
                "recipe", Map.of(
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "targetSize", targetSize),
                "summary", Map.of(
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "totalCustomers", targetSize,
                        "scheduledWorkItems", targetSize,
                        "segmentCounts", segmentCounts),
                "validation", Map.of("status", validationStatus));
    }
}
