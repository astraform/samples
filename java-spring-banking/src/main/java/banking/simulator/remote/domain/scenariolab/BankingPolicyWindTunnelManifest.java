package banking.simulator.remote.domain.scenariolab;

import banking.simulator.domain.banking.BankingDomainIds;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.sdk.PolicyExpressionDefinition;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelPackDescriptor;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelPackManifest;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Banking remote-provider Policy Wind Tunnel manifest.
 *
 * <p>The public pack identity, preset catalog, and schema-driven controls live
 * with the banking remote-domain service. Banking model and business semantics
 * are provider-local implementation details behind remote-domain.v1.</p>
 */
public final class BankingPolicyWindTunnelManifest implements PolicyWindTunnelPackManifest {

    public static final String PACK_ID = "banking-policy-pack";
    public static final String DOMAIN_ID = BankingDomainIds.BANKING;
    public static final String LABEL = "Banking Policy Pack";
    public static final String DESCRIPTION =
            "First-party banking pack for retail deposit, credit-limit, hardship, and fraud policy comparison.";
    public static final String EXPERIMENT_ID = "banking-scenario-lab";
    public static final String ROUTE_BASE_PATH = "/api/experiments/banking-scenario-lab";
    public static final String ARTIFACT_ROOT = "output/experiments/banking-scenario-lab/runs";
    public static final String SCENARIO_ID = "banking-policy-compare";
    public static final String SUMMARY_SCHEMA_VERSION = "policy_wind_tunnel_summary.v1";
    public static final String BANKING_PRODUCT_ENDPOINT_DEPENDENCY = "banking-product-endpoint";
    public static final String CATALOG_RESOURCE = "banking-scenario-lab/preset-catalog.json";

    private static final PolicyWindTunnelPackDescriptor DESCRIPTOR = new PolicyWindTunnelPackDescriptor(
            PACK_ID,
            DOMAIN_ID,
            LABEL,
            DESCRIPTION,
            EXPERIMENT_ID,
            ROUTE_BASE_PATH,
            ARTIFACT_ROOT,
            SCENARIO_ID,
            false);

    private final ObjectMapper objectMapper;
    private final Map<String, Object> catalogDocument;

    public BankingPolicyWindTunnelManifest() {
        this(new ObjectMapper().rebuild().findAndAddModules().build());
    }

    BankingPolicyWindTunnelManifest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild().build();
        this.catalogDocument = loadCatalogDocument();
    }

    @Override
    public PolicyWindTunnelPackDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> presetCatalog() {
        Map<String, Object> catalog = deepCopyMap(catalogDocument);
        catalog.putIfAbsent("summarySchemaVersion", SUMMARY_SCHEMA_VERSION);
        catalog.putIfAbsent("controls", controlSchema());
        catalog.putIfAbsent("uiSchema", uiSchema());
        return catalog;
    }

    @Override
    public Map<String, Object> capabilities() {
        return Map.ofEntries(
                Map.entry("runModes", List.of("quick", "standard")),
                Map.entry("executionModes", List.of("CLOCK_DRIVEN_HYBRID")),
                Map.entry("compareModes", List.of("baseline-vs-candidate")),
                Map.entry("evidenceExport", true),
                Map.entry("artifactTypes", List.of(
                        "summary",
                        "impacted-cohorts",
                        "experiment-card",
                        "comparison-report",
                        "checkpoints-json",
                        "agent-diffs-json",
                        "notable-actions-json",
                        "scheduled-work-failures-json",
                        "simulation-timeline-json",
                        "side-effect-ledger-json")),
                Map.entry("opportunityGenerationFinalizationMode", "LEGACY_V1"),
                Map.entry("platformOwnedV2StorageReady", false),
                Map.entry("cohortActivationMode", "PLATFORM_PINNED_BYTES_V1"),
                Map.entry("realLlmCanaryPolicy", realLlmCanaryPolicy()),
                Map.entry(
                        "domainOutcomeScorecardRoles",
                        Map.of(
                                "schemaVersion", "domain_outcome_scorecard_roles.v1",
                                "roles", List.of(
                                        Map.of(
                                                "scorecardId", "banking.customer-impact.v1",
                                                "role", "BUSINESS_OUTCOME"),
                                        Map.of(
                                                "scorecardId", "banking.evidence-readiness.v1",
                                                "role", "PROOF_GATE")))),
                Map.entry("remoteProviderReady", true));
    }

    private Map<String, Object> realLlmCanaryPolicy() {
        return Map.ofEntries(
                Map.entry("schemaVersion", "real_llm_canary_policy.v1"),
                Map.entry("enabled", true),
                Map.entry("provider", stringEnv("ASTRAFORM_REAL_LLM_CANARY_PROVIDER", "openai")),
                Map.entry("model", stringEnv("ASTRAFORM_REAL_LLM_CANARY_MODEL", "gemini-2.5-flash-lite")),
                Map.entry("maxCustomers", intEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_CUSTOMERS", 10)),
                Map.entry("maxDays", intEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_HORIZON_DAYS", 30)),
                Map.entry("maxCalls", intEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_CALLS", 70)),
                Map.entry("maxUsd", doubleEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_USD", 1.0d)),
                Map.entry("maxInputTokens", intEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_INPUT_TOKENS", 150_000)),
                Map.entry("maxOutputTokens", intEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_OUTPUT_TOKENS", 25_000)),
                Map.entry("allowedCadences", List.of("EVENT_ONLY", "DAILY", "WEEKLY")),
                Map.entry("maxPlaybackRate", doubleEnv("ASTRAFORM_REAL_LLM_CANARY_MAX_PLAYBACK_RATE", 600.0d)));
    }

    @Override
    public Map<String, Object> controlSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("presetId", Map.of(
                "type", "string",
                "title", "Preset"));
        properties.put("simulationHorizonDays", Map.of(
                "type", "integer",
                "title", "Simulation horizon",
                "minimum", 1,
                "default", 30));
        properties.put("prePolicyDays", Map.of(
                "type", "integer",
                "title", "Pre-policy days",
                "minimum", 0,
                "default", 10));
        properties.put("postPolicyDays", Map.of(
                "type", "integer",
                "title", "Post-policy days",
                "minimum", 0,
                "default", 20));
        properties.put("runMode", Map.of(
                "type", "string",
                "title", "Run mode",
                "enum", List.of("DEMO_FAST", "DETAILED_ANALYSIS"),
                "default", "DEMO_FAST"));
        properties.put("cadenceProfile", Map.of(
                "type", "string",
                "title", "Cadence profile",
                "enum", List.of("DEMO_FAST_FORK_FOCUSED", "ADAPTIVE_15M_FIRST_24H_THEN_1H"),
                "default", "DEMO_FAST_FORK_FOCUSED"));
        properties.put("compareMode", Map.of(
                "type", "string",
                "title", "Compare mode",
                "enum", List.of("SEEDED_DETERMINISTIC"),
                "default", "SEEDED_DETERMINISTIC"));
        properties.put("productCode", Map.of(
                "type", "string",
                "title", "Product code",
                "default", "TS001"));
        properties.put("baselineRate", Map.of(
                "type", "number",
                "title", "Baseline rate",
                "minimum", 0,
                "maximum", 1,
                "default", 0.03));
        properties.put("candidateRate", Map.of(
                "type", "number",
                "title", "Candidate rate",
                "minimum", 0,
                "maximum", 1,
                "default", 0.08));
        properties.put("baselineLimit", Map.of(
                "type", "number",
                "title", "Baseline limit",
                "minimum", 0));
        properties.put("candidateLimit", Map.of(
                "type", "number",
                "title", "Candidate limit",
                "minimum", 0));
        properties.put("candidateStimulusAmount", Map.of(
                "type", "number",
                "title", "Candidate stimulus",
                "minimum", 0,
                "default", 0));
        properties.put("candidateCrash", Map.of(
                "type", "boolean",
                "title", "Crash overlay",
                "default", false));
        properties.put("candidateHardshipScan", Map.of(
                "type", "boolean",
                "title", "Hardship scan",
                "default", false));
        properties.put("candidateHardshipThreshold", Map.of(
                "type", "number",
                "title", "Hardship threshold",
                "minimum", 0));
        properties.put("candidateHardshipSubsidyAmount", Map.of(
                "type", "number",
                "title", "Hardship subsidy",
                "minimum", 1));
        properties.put("candidateFraudAlert", Map.of(
                "type", "boolean",
                "title", "Fraud alert",
                "default", false));
        properties.put("candidateFraudSeverity", Map.of(
                "type", "string",
                "title", "Fraud severity",
                "enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"),
                "default", "HIGH"));
        properties.put("candidateFraudChannel", Map.of(
                "type", "string",
                "title", "Fraud channel",
                "default", "CARD"));
        properties.put("candidateFraudMessage", Map.of(
                "type", "string",
                "title", "Fraud message"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of(
                        "presetId",
                        "simulationHorizonDays",
                        "prePolicyDays",
                        "postPolicyDays",
                        "runMode",
                        "cadenceProfile",
                        "compareMode",
                        "productCode",
                        "baselineRate",
                        "candidateRate"));
    }

    @Override
    public Map<String, Object> uiSchema() {
        return Map.of(
                "layout", "preset-first",
                "fields", List.of(
                        Map.of("name", "presetId", "widget", "preset-select", "label", "Preset"),
                        Map.of("name", "simulationHorizonDays", "widget", "number", "label", "Simulation horizon", "unit", "days"),
                        Map.of("name", "prePolicyDays", "widget", "number", "label", "Pre-policy", "unit", "days"),
                        Map.of("name", "postPolicyDays", "widget", "number", "label", "Post-policy", "unit", "days"),
                        Map.of("name", "runMode", "widget", "select", "label", "Run mode"),
                        Map.of("name", "cadenceProfile", "widget", "select", "label", "Cadence"),
                        Map.of("name", "compareMode", "widget", "select", "label", "Compare"),
                        Map.of("name", "productCode", "widget", "text", "label", "Product code"),
                        Map.of("name", "baselineRate", "widget", "number", "label", "Baseline rate", "unit", "decimal"),
                        Map.of("name", "candidateRate", "widget", "number", "label", "Candidate rate", "unit", "decimal"),
                        Map.of("name", "baselineLimit", "widget", "number", "label", "Baseline limit", "unit", "USD"),
                        Map.of("name", "candidateLimit", "widget", "number", "label", "Candidate limit", "unit", "USD"),
                        Map.of("name", "candidateStimulusAmount", "widget", "number", "label", "Stimulus", "unit", "USD"),
                        Map.of("name", "candidateCrash", "widget", "checkbox", "label", "Crash overlay"),
                        Map.of("name", "candidateHardshipScan", "widget", "checkbox", "label", "Hardship scan"),
                        Map.of("name", "candidateHardshipThreshold", "widget", "number", "label", "Hardship threshold", "unit", "USD"),
                        Map.of("name", "candidateHardshipSubsidyAmount", "widget", "number", "label", "Hardship subsidy", "unit", "USD"),
                        Map.of("name", "candidateFraudAlert", "widget", "checkbox", "label", "Fraud alert"),
                        Map.of("name", "candidateFraudSeverity", "widget", "select", "label", "Fraud severity"),
                        Map.of("name", "candidateFraudChannel", "widget", "text", "label", "Fraud channel"),
                        Map.of("name", "candidateFraudMessage", "widget", "text", "label", "Fraud message")));
    }

    @Override
    public Map<String, Object> outcomeSchema() {
        return Map.of(
                "schemaVersion", "domain_outcome_schema.v1",
                "outcomeEnvelope", "domain_outcome_score.v1",
                "actorPlural", "customers",
                "policyCategories", List.of("deposit-rate", "credit-limit", "hardship", "fraud"),
                "recommendations", List.of("LAUNCH", "WATCH", "BLOCK", "REVIEW"),
                "metricDefinitions", List.of(
                        Map.of(
                                "metricId", "simulatedSubjects",
                                "label", "Simulated customers",
                                "unit", "count",
                                "evidence", "cohort"),
                        Map.of(
                                "metricId", "impactedSubjects",
                                "label", "Impacted customers",
                                "unit", "count",
                                "evidence", "branch-state"),
                        Map.of(
                                "metricId", "exposure",
                                "label", "Balance or limit exposure",
                                "unit", "money",
                                "evidence", "domain-state"),
                        Map.of(
                                "metricId", "thresholdBreaches",
                                "label", "Policy threshold breaches",
                                "unit", "count",
                                "evidence", "decision-gates"),
                        Map.of(
                                "metricId", "outcomeDelta",
                                "label", "Customer outcome delta",
                                "unit", "domain-defined",
                                "evidence", "metric-trace"),
                        Map.of(
                                "metricId", "counterMetricDelta",
                                "label", "Business counter-metric delta",
                                "unit", "domain-defined",
                                "evidence", "metric-trace")));
    }

    @Override
    public Map<String, Object> outcomeScorecard() {
        return customerImpactOutcomeScorecard();
    }

    @Override
    public List<Map<String, Object>> outcomeScorecards() {
        return List.of(
                customerImpactOutcomeScorecard(),
                evidenceReadinessOutcomeScorecard());
    }

    private Map<String, Object> customerImpactOutcomeScorecard() {
        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("schemaVersion", "domain_outcome_scorecard.v1");
        scorecard.put("scorecardId", "banking.customer-impact.v1");
        scorecard.put("label", "Banking customer-impact scorecard");
        scorecard.put("version", "0.1.0");
        scorecard.put("supportLevel", "EVIDENCE_LINKED");
        scorecard.put("appliesTo", Map.of(
                "domainId", DOMAIN_ID,
                "packId", PACK_ID,
                "executionModes", List.of("CLOCK_DRIVEN_HYBRID"),
                "presets", List.of("*")));
        scorecard.put("metrics", List.of(
                scoreMetric(
                        "activatedSyntheticCustomers",
                        "Activated synthetic customers",
                        "metrics.cohortSize",
                        "simulationRunEvidencePack",
                        "Number of synthetic customers included in this decision run."),
                scoreMetric(
                        "runtimeEvidenceSignals",
                        "Runtime evidence signals",
                        "metrics.evidenceSignalCount",
                        "simulationRunEvidencePack",
                        "Core-mediated evidence signals captured for audit."),
                scoreMetric(
                        "affectedStateChanges",
                        "Affected state changes",
                        "metrics.stateChangeCount",
                        "branchStateProjectionJson",
                        "Domain state changes projected or observed during the run."),
                scoreMetric(
                        "rejectedWorkItems",
                        "Rejected work items",
                        "metrics.rejectedWorkCount",
                        "branchStateProjectionJson",
                        "Domain work items that were rejected or failed validation.")));
        scorecard.put("gates", List.of(
                scoreGate(
                        "banking.evidence.runtime-linked",
                        "Runtime evidence linked",
                        "signals.hasRuntimeEvidence && metrics.evidenceSignalCount > 0",
                        "simulationRunEvidencePack",
                        "The run has durable runtime evidence."),
                scoreGate(
                        "banking.evidence.state-linked",
                        "State evidence linked",
                        "signals.hasStateEvidence && metrics.stateChangeCount >= 0",
                        "branchStateProjectionJson",
                        "The run has branch state projection evidence.")));
        scorecard.put("recommendationRules", List.of(
                recommendationRule(
                        "BLOCK",
                        "Block launch",
                        "metrics.rejectedWorkCount >= 25",
                        "branchStateProjectionJson",
                        "Too many rejected domain actions to treat the candidate as launch-ready."),
                recommendationRule(
                        "WATCH",
                        "Watch before launch",
                        "metrics.rejectedWorkCount > 0 || metrics.stateChangeCount > 0",
                        "branchStateProjectionJson",
                        "The candidate produced observable domain impact and needs human review."),
                recommendationRule(
                        "REVIEW",
                        "Review manually",
                        "true",
                        "simulationRunEvidencePack",
                        "Evidence is captured, but the domain scorecard does not claim an automatic launch decision.")));
        scorecard.put("headlineClaims", List.of(
                "Scores are based on replay-linked runtime evidence and banking state projections.",
                "A WATCH recommendation means banking reviewers should inspect affected customers before launch."));
        scorecard.put("limitations", List.of(
                "This is stress-test evidence under configured assumptions, not a production forecast.",
                "Provider-internal banking actions require emitted evidence events to be scored."));
        return Map.copyOf(scorecard);
    }

    private Map<String, Object> evidenceReadinessOutcomeScorecard() {
        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("schemaVersion", "domain_outcome_scorecard.v1");
        scorecard.put("scorecardId", "banking.evidence-readiness.v1");
        scorecard.put("label", "Banking evidence-readiness scorecard");
        scorecard.put("version", "0.1.0");
        scorecard.put("supportLevel", "EVIDENCE_LINKED");
        scorecard.put("appliesTo", Map.of(
                "domainId", DOMAIN_ID,
                "packId", PACK_ID,
                "executionModes", List.of("CLOCK_DRIVEN_HYBRID"),
                "presets", List.of("*")));
        scorecard.put("metrics", List.of(
                scoreMetric(
                        "timelineEvents",
                        "Timeline events",
                        "metrics.timelineEventCount",
                        "simulationTimelineJson",
                        "Replayable simulated-time events captured for the run."),
                scoreMetric(
                        "stateChanges",
                        "Domain state changes",
                        "metrics.stateChangeCount",
                        "branchStateProjectionJson",
                        "Projected or observed domain state changes linked to the run."),
                scoreMetric(
                        "evidenceSignals",
                        "Evidence signals",
                        "metrics.evidenceSignalCount",
                        "simulationRunEvidencePack",
                        "Total platform-mediated evidence signals available for review.")));
        scorecard.put("gates", List.of(
                scoreGate(
                        "banking.evidence.minimum-signal-volume",
                        "Minimum evidence volume",
                        "metrics.evidenceSignalCount >= 1",
                        "simulationRunEvidencePack",
                        "The run captured at least one durable evidence signal."),
                scoreGate(
                        "banking.evidence.timeline-linked",
                        "Timeline linked",
                        "metrics.timelineEventCount >= 1",
                        "simulationTimelineJson",
                        "The run has a replayable timeline.")));
        scorecard.put("recommendationRules", List.of(
                recommendationRule(
                        "BLOCK",
                        "Insufficient evidence",
                        "metrics.evidenceSignalCount == 0",
                        "simulationRunEvidencePack",
                        "No evidence signals were captured, so the report cannot support a decision."),
                recommendationRule(
                        "WATCH",
                        "Evidence ready for review",
                        "metrics.evidenceSignalCount > 0",
                        "simulationRunEvidencePack",
                        "Evidence exists and should be reviewed before any launch decision."),
                recommendationRule(
                        "REVIEW",
                        "Manual review required",
                        "true",
                        "simulationRunEvidencePack",
                        "Evidence readiness could not produce a stronger recommendation.")));
        scorecard.put("headlineClaims", List.of(
                "Evidence readiness only scores whether the run is reviewable.",
                "It does not score customer harm or launch safety."));
        scorecard.put("limitations", List.of(
                "This scorecard validates evidence availability, not banking business risk.",
                "Use it alongside a customer-impact scorecard before making product decisions."));
        return Map.copyOf(scorecard);
    }

    @Override
    public List<PolicyExpressionDefinition> policyExpressions() {
        return List.of(
                PolicyExpressionDefinition.celBoolean(
                        "banking.rate.candidate-rate-floor",
                        PolicyExpressionStage.BRANCH_GUARD,
                        "parameters.candidateRate >= parameters.baselineRate",
                        "rate-policy-execution",
                        "Candidate deposit rate must not fall below the baseline rate for rate-increase comparisons."),
                PolicyExpressionDefinition.celBoolean(
                        "banking.hardship.threshold-required",
                        PolicyExpressionStage.DECISION_GATE,
                        "!parameters.candidateHardshipScan || parameters.candidateHardshipThreshold > 0",
                        "hardship-policy-execution",
                        "Hardship scan runs must declare a positive hardship threshold."));
    }

    @Override
    public String summarySchemaVersion() {
        return SUMMARY_SCHEMA_VERSION;
    }

    private Map<String, Object> loadCatalogDocument() {
        ClassLoader classLoader = BankingPolicyWindTunnelManifest.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(CATALOG_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing banking Policy Wind Tunnel catalog: " + CATALOG_RESOURCE);
            }
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load banking Policy Wind Tunnel catalog.", e);
        }
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        return objectMapper.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static Map<String, Object> scoreMetric(
            String metricId,
            String label,
            String valueExpression,
            String evidenceRef,
            String description) {
        return Map.of(
                "metricId", metricId,
                "label", label,
                "resultType", "number",
                "valueExpression", valueExpression,
                "evidenceRef", evidenceRef,
                "supportLevel", "EVIDENCE_LINKED",
                "description", description);
    }

    private static Map<String, Object> scoreGate(
            String gateId,
            String label,
            String expression,
            String evidenceRef,
            String description) {
        return Map.of(
                "gateId", gateId,
                "label", label,
                "expression", expression,
                "evidenceRef", evidenceRef,
                "supportLevel", "EVIDENCE_LINKED",
                "description", description);
    }

    private static Map<String, Object> recommendationRule(
            String recommendation,
            String label,
            String expression,
            String evidenceRef,
            String reason) {
        return Map.of(
                "recommendation", recommendation,
                "label", label,
                "expression", expression,
                "evidenceRef", evidenceRef,
                "supportLevel", "EVIDENCE_LINKED",
                "reason", reason);
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double doubleEnv(String name, double defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(0.0001d, Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String stringEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
