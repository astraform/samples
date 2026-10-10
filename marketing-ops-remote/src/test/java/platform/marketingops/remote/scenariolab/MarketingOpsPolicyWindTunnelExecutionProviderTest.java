package platform.marketingops.remote.scenariolab;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketingOpsPolicyWindTunnelExecutionProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspaceRoot;

    @Test
    void providerStartsRunAndOwnsBundleArtifactAndEvidenceExport() throws Exception {
        MarketingOpsPolicyWindTunnelExecutionProvider provider =
                new MarketingOpsPolicyWindTunnelExecutionProvider();
        FakeContext context = new FakeContext(workspaceRoot);
        String cohortId = "marketing-ops-campaign-readiness-100-provider-test";
        Map<String, Object> segmentCounts = Map.of(
                "launch-audience", 35,
                "retention-risk", 25,
                "high-intent", 25,
                "support-sensitive", 15);
        writeTrustedPopulationBundle(cohortId, segmentCounts);

        PolicyWindTunnelRunRecord status = provider.startRun(context, Map.of(
                "presetId", MarketingOpsPolicyWindTunnelDefinition.DEFAULT_PRESET_ID,
                "blockerCount", 14,
                "reviewCapacity", 9,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", cohortId,
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "targetSize", 100,
                        "scheduledWorkItems", 100,
                        "validationStatus", "valid",
                        "runPathEligibility", Map.of(
                                "status", "SCENARIO_READY",
                                "windTunnelReady", true,
                                "windTunnelConsumesPopulation", true),
                        "segmentCounts", segmentCounts)), "TEAM_OPERATOR");

        assertEquals("COMPLETED", status.status());
        assertTrue(status.runId().startsWith("marketing-ops-wt-"));
        assertTrue(status.bundleReady());
        assertTrue(status.bundleUrl().contains("/scenario-lab/bundle"));

        Map<String, Object> bundle = provider.loadBundle(context, status.runId());
        assertEquals("policy_wind_tunnel_bundle.v1", bundle.get("schemaVersion"));
        assertEquals(MarketingOpsPolicyWindTunnelDefinition.PACK_ID, bundle.get("packId"));
        assertEquals(MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID, bundle.get("domainId"));
        assertTrue(bundle.containsKey("branches"));
        assertTrue(bundle.containsKey("metrics"));
        assertTrue(bundle.containsKey("segments"));
        assertTrue(bundle.containsKey("timeline"));
        assertTrue(bundle.containsKey("decisionGates"));
        assertTrue(bundle.containsKey("domainPayload"));
        @SuppressWarnings("unchecked")
        Map<String, Object> domainPayload = (Map<String, Object>) bundle.get("domainPayload");
        @SuppressWarnings("unchecked")
        Map<String, Object> population = (Map<String, Object>) domainPayload.get("population");
        assertEquals(Boolean.TRUE, population.get("consumed"));
        assertEquals("marketing-ops-campaign-readiness-100-provider-test", population.get("cohortId"));
        assertEquals("campaign-readiness-100", population.get("recipeId"));
        assertTrue(String.valueOf(bundle.get("experimentCardMarkdown"))
                .contains("Marketing Ops Policy Wind Tunnel"));
        assertTrue(String.valueOf(bundle.get("experimentCardMarkdown"))
                .contains("marketing-ops-campaign-readiness-100-provider-test"));

        PolicyWindTunnelArtifactPayload summary = provider.readArtifact(context, status.runId(), "summary");
        assertEquals("application/json", summary.contentType());
        assertTrue(new String(summary.content(), StandardCharsets.UTF_8).contains("\"domainId\""));

        PolicyWindTunnelArchivePayload archive = provider.buildEvidencePack(context, status.runId());
        assertEquals("scenario-lab-evidence-" + status.runId(), archive.baseName());
        assertTrue(archive.content().length > 0);
    }

    @Test
    void providerIgnoresCallerSuppliedTrustedPopulationBundle() throws Exception {
        MarketingOpsPolicyWindTunnelExecutionProvider provider =
                new MarketingOpsPolicyWindTunnelExecutionProvider();
        FakeContext context = new FakeContext(workspaceRoot);
        String cohortId = "forged-provider-population";
        Map<String, Object> segmentCounts = Map.of("support-sensitive", 100);

        PolicyWindTunnelRunRecord status = provider.startRun(context, Map.of(
                "presetId", MarketingOpsPolicyWindTunnelDefinition.DEFAULT_PRESET_ID,
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", cohortId,
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "targetSize", 100,
                        "scheduledWorkItems", 100,
                        "validationStatus", "valid",
                        "runPathEligibility", Map.of(
                                "status", "SCENARIO_READY",
                                "windTunnelReady", true,
                                "windTunnelConsumesPopulation", true),
                        "segmentCounts", segmentCounts),
                "trustedPopulationBundle", trustedPopulationBundle(cohortId, segmentCounts)), "TEAM_OPERATOR");

        Map<String, Object> bundle = provider.loadBundle(context, status.runId());
        @SuppressWarnings("unchecked")
        Map<String, Object> domainPayload = (Map<String, Object>) bundle.get("domainPayload");
        @SuppressWarnings("unchecked")
        Map<String, Object> population = (Map<String, Object>) domainPayload.get("population");
        assertEquals(Boolean.FALSE, population.get("consumed"));
        assertEquals("trusted_population_bundle_missing", population.get("untrustedReason"));
    }

    @Test
    void trustedBundleLookupRejectsParentDirectoryCohortId() throws Exception {
        MarketingOpsPolicyWindTunnelExecutionProvider provider =
                new MarketingOpsPolicyWindTunnelExecutionProvider();
        FakeContext context = new FakeContext(workspaceRoot);
        String cohortId = "..";
        Map<String, Object> segmentCounts = Map.of("support-sensitive", 100);
        Path outputRoot = workspaceRoot.resolve("output");
        Files.createDirectories(outputRoot);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("bundle.json").toFile(),
                trustedPopulationBundle(cohortId, segmentCounts));

        PolicyWindTunnelRunRecord status = provider.startRun(context, Map.of(
                "presetId", MarketingOpsPolicyWindTunnelDefinition.DEFAULT_PRESET_ID,
                "blockerCount", 10,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced",
                "cohortSelection", Map.of(
                        "cohortId", cohortId,
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "targetSize", 100,
                        "scheduledWorkItems", 100,
                        "validationStatus", "valid",
                        "runPathEligibility", Map.of(
                                "status", "SCENARIO_READY",
                                "windTunnelReady", true,
                                "windTunnelConsumesPopulation", true),
                        "segmentCounts", segmentCounts)), "TEAM_OPERATOR");

        Map<String, Object> bundle = provider.loadBundle(context, status.runId());
        @SuppressWarnings("unchecked")
        Map<String, Object> domainPayload = (Map<String, Object>) bundle.get("domainPayload");
        @SuppressWarnings("unchecked")
        Map<String, Object> population = (Map<String, Object>) domainPayload.get("population");
        assertEquals(Boolean.FALSE, population.get("consumed"));
        assertEquals("trusted_population_bundle_missing", population.get("untrustedReason"));
    }

    private void writeTrustedPopulationBundle(
            String cohortId,
            Map<String, Object> segmentCounts) throws Exception {
        Path cohortRoot = workspaceRoot.resolve("output").resolve("cohorts").resolve(cohortId);
        Files.createDirectories(cohortRoot);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                cohortRoot.resolve("bundle.json").toFile(),
                trustedPopulationBundle(cohortId, segmentCounts));
    }

    private Map<String, Object> trustedPopulationBundle(
            String cohortId,
            Map<String, Object> segmentCounts) {
        return Map.of(
                "schemaVersion", "synthetic_cohort_bundle.v1",
                "cohortId", cohortId,
                "domainId", "marketing-ops",
                "recipe", Map.of(
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "targetSize", 100),
                "summary", Map.of(
                        "domainId", "marketing-ops",
                        "recipeId", "campaign-readiness-100",
                        "totalCustomers", 100,
                        "scheduledWorkItems", 100,
                        "segmentCounts", segmentCounts),
                "validation", Map.of("status", "valid"));
    }

    private static final class FakeContext implements PolicyWindTunnelExecutionContext {

        private final Path workspaceRoot;
        private final Map<String, PolicyWindTunnelStoredRun> runs = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> artifactsByRun = new LinkedHashMap<>();

        private FakeContext(Path workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        @Override
        public Path workspaceRoot() {
            return workspaceRoot;
        }

        @Override
        public String agentRuntimeUrl() {
            return "http://localhost:8082";
        }

        @Override
        public String simulatorUrl() {
            return "http://localhost:8083";
        }

        @Override
        public String dependencyUrl(String dependencyId) {
            return "";
        }

        @Override
        public void createExperiment(
                String experimentId,
                String name,
                String description,
                String domainId,
                String owner,
                String status) {
        }

        @Override
        public void createRun(PolicyWindTunnelRunCreateRequest request) {
            OffsetDateTime now = OffsetDateTime.now();
            runs.put(request.runId(), new PolicyWindTunnelStoredRun(
                    request.runId(),
                    request.status(),
                    request.summaryPath(),
                    request.bundlePath(),
                    "",
                    request.startedAtEpochMs(),
                    now,
                    now,
                    request.metadata()));
        }

        @Override
        public Optional<PolicyWindTunnelStoredRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public void updateRun(String runId, PolicyWindTunnelRunUpdate update) {
            PolicyWindTunnelStoredRun existing = runs.get(runId);
            runs.put(runId, new PolicyWindTunnelStoredRun(
                    runId,
                    firstNonNull(update.status(), existing.status()),
                    firstNonNull(update.summaryPath(), existing.summaryPath()),
                    firstNonNull(update.bundlePath(), existing.bundlePath()),
                    existing.evaluationRunId(),
                    existing.startedAtEpochMs(),
                    existing.createdAt(),
                    OffsetDateTime.now(),
                    firstNonNull(update.metadata(), existing.metadata())));
        }

        @Override
        public void registerArtifact(String runId, String artifactType, String artifactRef, String contentType) {
            artifactsByRun.computeIfAbsent(runId, ignored -> new LinkedHashMap<>())
                    .put(artifactType, artifactRef);
        }

        @Override
        public Map<String, String> registeredArtifacts(String runId) {
            return artifactsByRun.getOrDefault(runId, Map.of());
        }

        @Override
        public void linkEvaluation(String runId, String evaluationRunId) {
        }

        @Override
        public Map<String, Object> evaluateSuite(Map<String, Object> suite, String baselineEvaluationRunId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> compareEvaluations(
                Map<String, Object> baselineReport,
                Map<String, Object> candidateReport) {
            return Map.of();
        }

        private static <T> T firstNonNull(T value, T fallback) {
            return value == null ? fallback : value;
        }
    }
}
