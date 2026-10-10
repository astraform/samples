package platform.marketingops.remote.scenariolab;

import ai.astraform.remote.domain.sdk.PolicyExpressionDefinition;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;
import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelPackDefinition;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelPackDescriptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Marketing-ops-owned Policy Wind Tunnel definition.
 *
 * <p>The remote provider publishes this metadata over the Wind Tunnel protocol;
 * controls, KPI math, and summary payload belong to this remote domain service.</p>
 */
public final class MarketingOpsPolicyWindTunnelDefinition implements PolicyWindTunnelPackDefinition {

    public static final String PACK_ID = "marketing-ops-policy-pack";
    public static final String DOMAIN_ID = "marketing-ops";
    public static final String LABEL = "Marketing Ops Policy Pack";
    public static final String DESCRIPTION =
            "First-party proof pack for campaign launch readiness, review capacity, approvals, and SLA risk.";
    public static final String EXPERIMENT_ID = "marketing-ops-wind-tunnel";
    public static final String ROUTE_BASE_PATH = "/api/experiments/marketing-ops-wind-tunnel";
    public static final String ARTIFACT_ROOT = "output/experiments/marketing-ops-wind-tunnel/runs";
    public static final String SUMMARY_SCHEMA_VERSION = "policy_wind_tunnel_summary.v1";
    public static final String DEFAULT_PRESET_ID = "launch-readiness-pressure";
    public static final String SCENARIO_ID = "marketing-ops-launch-readiness";
    private static final Map<String, Object> POPULATION_CATALOG = PopulationCatalogs.fromClasspath(
            MarketingOpsPolicyWindTunnelDefinition.class,
            "/population-catalogs/marketing-ops.population-catalog.json");

    private static final PolicyWindTunnelPackDescriptor DESCRIPTOR = new PolicyWindTunnelPackDescriptor(
            PACK_ID,
            DOMAIN_ID,
            LABEL,
            DESCRIPTION,
            EXPERIMENT_ID,
            ROUTE_BASE_PATH,
            ARTIFACT_ROOT,
            SCENARIO_ID,
            true);

    @Override
    public PolicyWindTunnelPackDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> presetCatalog() {
        return Map.of(
                "schemaVersion", "scenario_lab_presets.v1",
                "summarySchemaVersion", SUMMARY_SCHEMA_VERSION,
                "defaults", Map.of(
                        "presetId", DEFAULT_PRESET_ID,
                        "runMode", "quick",
                        "compareMode", "baseline-vs-candidate",
                        "blockerCount", 12,
                        "reviewCapacity", 8,
                        "slaTargetHours", 48,
                        "approvalStrictness", "balanced"),
                "controls", controlSchema(),
                "uiSchema", uiSchema(),
                "presets", List.of(Map.of(
                        "presetId", DEFAULT_PRESET_ID,
                        "label", "Launch readiness pressure",
                        "description", "Compare baseline and candidate approval policy under a campaign launch queue spike.",
                        "domainId", DOMAIN_ID,
                        "controlDefaults", Map.of(
                                "blockerCount", 12,
                                "reviewCapacity", 8,
                                "slaTargetHours", 48,
                                "approvalStrictness", "balanced"))));
    }

    @Override
    public Map<String, Object> capabilities() {
        return Map.of(
                "runModes", List.of("quick"),
                "compareModes", List.of("baseline-vs-candidate"),
                "evidenceExport", true,
                "artifactTypes", List.of("summary", "experiment-card"),
                "remoteProviderReady", true);
    }

    @Override
    public Map<String, Object> controlSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("presetId", "blockerCount", "reviewCapacity", "slaTargetHours", "approvalStrictness"),
                "properties", Map.of(
                        "presetId", Map.of(
                                "type", "string",
                                "title", "Preset",
                                "default", DEFAULT_PRESET_ID),
                        "blockerCount", Map.of(
                                "type", "integer",
                                "title", "Blockers",
                                "minimum", 0,
                                "maximum", 50,
                                "default", 12),
                        "reviewCapacity", Map.of(
                                "type", "integer",
                                "title", "Review capacity",
                                "minimum", 1,
                                "maximum", 40,
                                "default", 8),
                        "slaTargetHours", Map.of(
                                "type", "integer",
                                "title", "SLA target hours",
                                "minimum", 4,
                                "maximum", 168,
                                "default", 48),
                        "approvalStrictness", Map.of(
                                "type", "string",
                                "title", "Approval strictness",
                                "enum", List.of("relaxed", "balanced", "strict"),
                                "default", "balanced")));
    }

    @Override
    public Map<String, Object> uiSchema() {
        return Map.of(
                "layout", "compact-grid",
                "fields", List.of(
                        Map.of("name", "blockerCount", "widget", "number", "label", "Blockers", "unit", "items"),
                        Map.of("name", "reviewCapacity", "widget", "number", "label", "Review capacity", "unit", "reviews/day"),
                        Map.of("name", "slaTargetHours", "widget", "number", "label", "SLA target", "unit", "hours"),
                        Map.of("name", "approvalStrictness", "widget", "select", "label", "Approval strictness")));
    }

    @Override
    public Map<String, Object> outcomeSchema() {
        return Map.of(
                "schemaVersion", "domain_outcome_schema.v1",
                "outcomeEnvelope", "domain_outcome_score.v1",
                "actorPlural", "launch campaigns",
                "policyCategories", List.of("launch-readiness", "review-capacity", "approval-strictness", "sla-risk"),
                "recommendations", List.of("LAUNCH", "WATCH", "BLOCK", "REVIEW"),
                "metricDefinitions", List.of(
                        Map.of(
                                "metricId", "launchReadinessPct",
                                "label", "Launch readiness",
                                "unit", "percent",
                                "evidence", "domain-kpi"),
                        Map.of(
                                "metricId", "slaRiskPct",
                                "label", "SLA risk",
                                "unit", "percent",
                                "evidence", "decision-gates"),
                        Map.of(
                                "metricId", "approvalsCompleted",
                                "label", "Approvals completed",
                                "unit", "count",
                                "evidence", "timeline"),
                        Map.of(
                                "metricId", "escalations",
                                "label", "Escalations",
                                "unit", "count",
                                "evidence", "side-effect-ledger"),
                        Map.of(
                                "metricId", "queueAgeHours",
                                "label", "Queue age",
                                "unit", "hours",
                                "evidence", "domain-state")));
    }

    @Override
    public Map<String, Object> outcomeScorecard() {
        return launchReadinessScorecard();
    }

    @Override
    public List<Map<String, Object>> outcomeScorecards() {
        return List.of(
                launchReadinessScorecard(),
                evidenceReadinessScorecard());
    }

    private Map<String, Object> launchReadinessScorecard() {
        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("schemaVersion", "domain_outcome_scorecard.v1");
        scorecard.put("scorecardId", "marketing-ops.launch-readiness.v1");
        scorecard.put("label", "Marketing Ops launch-readiness scorecard");
        scorecard.put("version", "0.1.0");
        scorecard.put("supportLevel", "EVIDENCE_LINKED");
        scorecard.put("appliesTo", Map.of(
                "domainId", DOMAIN_ID,
                "packId", PACK_ID,
                "executionModes", List.of("FAST_BATCH_COMPARE", "CLOCK_DRIVEN_HYBRID"),
                "presets", List.of(DEFAULT_PRESET_ID)));
        scorecard.put("metrics", List.of(
                scoreMetric(
                        "launchReadinessPct",
                        "Launch readiness",
                        "metrics.launchReadinessPct",
                        "summaryJson",
                        "Candidate launch readiness percentage computed by the Marketing Ops domain."),
                scoreMetric(
                        "slaRiskPct",
                        "SLA risk",
                        "metrics.slaRiskPct",
                        "summaryJson",
                        "Share of launch work projected to breach the configured review SLA."),
                scoreMetric(
                        "escalations",
                        "Escalations",
                        "metrics.escalations",
                        "sideEffectLedgerJson",
                        "Approvals or blockers expected to require escalation.")));
        scorecard.put("gates", List.of(
                scoreGate(
                        "marketing-ops.launch-readiness.minimum",
                        "Minimum launch readiness",
                        "metrics.launchReadinessPct >= 80",
                        "summaryJson",
                        "Candidate policy keeps launch readiness at or above 80%."),
                scoreGate(
                        "marketing-ops.sla-risk.maximum",
                        "SLA risk ceiling",
                        "metrics.slaRiskPct <= 0.25",
                        "summaryJson",
                        "Candidate policy keeps SLA risk at or below 25%.")));
        scorecard.put("recommendationRules", List.of(
                recommendationRule(
                        "BLOCK",
                        "Do not launch",
                        "metrics.slaRiskPct > 0.5 || metrics.launchReadinessPct < 60",
                        "summaryJson",
                        "Launch readiness is too low or SLA risk is too high for a safe campaign release."),
                recommendationRule(
                        "WATCH",
                        "Watch before launch",
                        "metrics.slaRiskPct > 0.25 || metrics.launchReadinessPct < 80 || metrics.escalations > 3",
                        "summaryJson",
                        "The candidate needs review because launch pressure or escalation load is visible."),
                recommendationRule(
                        "LAUNCH",
                        "Launch-ready",
                        "metrics.launchReadinessPct >= 80 && metrics.slaRiskPct <= 0.25 && metrics.escalations <= 3",
                        "summaryJson",
                        "The candidate passes the launch-readiness gates under the simulated assumptions."),
                recommendationRule(
                        "REVIEW",
                        "Review manually",
                        "true",
                        "summaryJson",
                        "Evidence exists, but the scorecard did not produce a stronger recommendation.")));
        scorecard.put("headlineClaims", List.of(
                "Marketing Ops scoring is based on domain-owned queue pressure, SLA risk, and launch-readiness math.",
                "A WATCH recommendation means the launch owner should inspect approval capacity before rollout."));
        scorecard.put("limitations", List.of(
                "This is stress-test evidence under configured assumptions, not a forecast of campaign performance.",
                "Provider-internal review systems require domain-emitted evidence events to be scored."));
        return Map.copyOf(scorecard);
    }

    private Map<String, Object> evidenceReadinessScorecard() {
        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("schemaVersion", "domain_outcome_scorecard.v1");
        scorecard.put("scorecardId", "marketing-ops.evidence-readiness.v1");
        scorecard.put("label", "Marketing Ops evidence-readiness scorecard");
        scorecard.put("version", "0.1.0");
        scorecard.put("supportLevel", "EVIDENCE_LINKED");
        scorecard.put("appliesTo", Map.of(
                "domainId", DOMAIN_ID,
                "packId", PACK_ID,
                "executionModes", List.of("FAST_BATCH_COMPARE", "CLOCK_DRIVEN_HYBRID"),
                "presets", List.of("*")));
        scorecard.put("metrics", List.of(
                scoreMetric(
                        "timelineEvents",
                        "Timeline events",
                        "metrics.timelineEventCount",
                        "simulationTimelineJson",
                        "Replayable simulated-time events captured for the run."),
                scoreMetric(
                        "runtimeEvidenceSignals",
                        "Runtime evidence signals",
                        "metrics.evidenceSignalCount",
                        "simulationRunEvidencePack",
                        "Core-mediated evidence signals available for audit.")));
        scorecard.put("gates", List.of(
                scoreGate(
                        "marketing-ops.evidence.minimum-signal-volume",
                        "Minimum evidence volume",
                        "metrics.evidenceSignalCount >= 1",
                        "simulationRunEvidencePack",
                        "The run captured at least one durable evidence signal.")));
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
                "It does not score campaign risk or launch safety."));
        scorecard.put("limitations", List.of(
                "This scorecard validates evidence availability, not marketing business risk.",
                "Use it alongside a launch-readiness scorecard before making product decisions."));
        return Map.copyOf(scorecard);
    }

    @Override
    public List<PolicyExpressionDefinition> policyExpressions() {
        return List.of(
                PolicyExpressionDefinition.celBoolean(
                        "marketing.launch.sla-risk",
                        PolicyExpressionStage.DECISION_GATE,
                        "metrics.slaRiskPct <= 0.25",
                        "launch-readiness-gate",
                        "Candidate launch policy must keep SLA risk at or below 25%."),
                PolicyExpressionDefinition.celBoolean(
                        "marketing.launch.readiness",
                        PolicyExpressionStage.DECISION_GATE,
                        "metrics.launchReadinessPct >= 80.0",
                        "launch-readiness-gate",
                        "Candidate launch policy must keep readiness at or above 80%."));
    }

    @Override
    public String summarySchemaVersion() {
        return SUMMARY_SCHEMA_VERSION;
    }

    @Override
    public Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> defaults = Map.of(
                "presetId", DEFAULT_PRESET_ID,
                "runMode", "quick",
                "compareMode", "baseline-vs-candidate",
                "blockerCount", 12,
                "reviewCapacity", 8,
                "slaTargetHours", 48,
                "approvalStrictness", "balanced");
        Map<String, Object> normalized = new LinkedHashMap<>(defaults);
        if (payload != null) {
            normalized.putAll(payload);
        }
        normalized.put("presetId", firstNonBlank(normalized.get("presetId"), DEFAULT_PRESET_ID));
        normalized.put("runMode", firstNonBlank(normalized.get("runMode"), "quick"));
        normalized.put("compareMode", firstNonBlank(normalized.get("compareMode"), "baseline-vs-candidate"));
        normalized.put("blockerCount", clamp(intValue(normalized.get("blockerCount"), 12), 0, 50));
        normalized.put("reviewCapacity", clamp(intValue(normalized.get("reviewCapacity"), 8), 1, 40));
        normalized.put("slaTargetHours", clamp(intValue(normalized.get("slaTargetHours"), 48), 4, 168));
        String strictness = stringValue(normalized.get("approvalStrictness")).toLowerCase(Locale.ROOT);
        if (!List.of("relaxed", "balanced", "strict").contains(strictness)) {
            strictness = "balanced";
        }
        normalized.put("approvalStrictness", strictness);
        return Map.copyOf(normalized);
    }

    @Override
    public Map<String, Object> buildSummary(
            String runId,
            Map<String, Object> payload,
            OffsetDateTime now) {
        int blockers = intValue(payload.get("blockerCount"), 12);
        int reviewCapacity = intValue(payload.get("reviewCapacity"), 8);
        int slaTargetHours = intValue(payload.get("slaTargetHours"), 48);
        String strictness = stringValue(payload.get("approvalStrictness"));
        Map<String, Object> population = populationContext(payload);
        blockers = Math.max(0, blockers + intValue(population.get("blockerPressure"), 0));
        reviewCapacity = Math.max(1, reviewCapacity + intValue(population.get("reviewCapacityAdjustment"), 0));

        int baselineCapacity = Math.max(1, reviewCapacity - 1);
        int candidateCapacity = Math.max(1, reviewCapacity + strictnessAdjustment(strictness));
        Map<String, Object> baselineMetrics = computeMetrics(blockers, baselineCapacity, slaTargetHours);
        Map<String, Object> candidateMetrics = computeMetrics(blockers, candidateCapacity, slaTargetHours);
        double launchReadiness = doubleValue(candidateMetrics.get("launchReadinessPct"), 0.0);
        double slaRisk = doubleValue(candidateMetrics.get("slaRiskPct"), 0.0);
        int escalations = intValue(candidateMetrics.get("escalations"), 0);

        List<Map<String, Object>> metrics = List.of(
                metric("queueAgeHours", "Queue age", candidateMetrics.get("queueAgeHours"), "hours"),
                metric("slaRiskPct", "SLA risk", slaRisk, "ratio"),
                metric("approvalsCompleted", "Approvals completed", candidateMetrics.get("approvalsCompleted"), "count"),
                metric("escalations", "Escalations", escalations, "count"),
                metric("launchReadinessPct", "Launch readiness", launchReadiness, "percent"));
        List<Map<String, Object>> gates = List.of(
                gate("sla-risk", slaRisk <= 0.25, "Candidate SLA risk must stay at or below 25%."),
                gate("launch-readiness", launchReadiness >= 80.0, "Launch readiness must stay at or above 80%."),
                gate("escalation-load", escalations <= 3, "Escalations must stay at or below 3."));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", SUMMARY_SCHEMA_VERSION);
        summary.put("domainId", DOMAIN_ID);
        summary.put("packId", PACK_ID);
        summary.put("runId", runId);
        summary.put("presetId", payload.get("presetId"));
        summary.put("createdAt", now.toString());
        summary.put("headline", Map.of(
                "title", "Marketing Ops launch readiness",
                "summary", launchReadiness >= 80.0
                        ? "Candidate approval policy can absorb the launch queue pressure."
                        : "Candidate approval policy needs capacity or strictness adjustment before launch.",
                "verdict", launchReadiness >= 80.0 && slaRisk <= 0.25 ? "PASS" : "WATCH"));
        summary.put("branches", List.of(
                Map.of("branchId", "baseline", "label", "Current approval policy", "metrics", baselineMetrics),
                Map.of("branchId", "candidate", "label", "Candidate approval policy", "metrics", candidateMetrics)));
        summary.put("metrics", metrics);
        summary.put("segments", List.of(
                Map.of("segmentId", "creative-review", "label", "Creative review", "slaRiskPct", slaRisk),
                Map.of("segmentId", "legal-approval", "label", "Legal approval", "escalations", escalations),
                Map.of("segmentId", "launch-ops", "label", "Launch operations", "readinessPct", launchReadiness)));
        summary.put("population", population);
        summary.put("timeline", List.of(
                Map.of("step", "queue-ingest", "label", "Campaign blockers entered queue", "hour", 0),
                Map.of("step", "approval-review", "label", "Review capacity applied", "hour", 24),
                Map.of("step", "launch-gate", "label", "Launch readiness gate evaluated", "hour", slaTargetHours)));
        summary.put("decisionGates", gates);
        summary.put("artifacts", Map.of(
                "summary", "scenario-lab-summary.json",
                "experimentCard", "experiment-card.md"));
        Map<String, Object> domainPayload = new LinkedHashMap<>();
        domainPayload.put("blockerCount", blockers);
        domainPayload.put("reviewCapacity", reviewCapacity);
        domainPayload.put("slaTargetHours", slaTargetHours);
        domainPayload.put("approvalStrictness", strictness);
        domainPayload.put("campaignMoment", "launch-readiness");
        domainPayload.put("population", population);
        summary.put("domainPayload", domainPayload);
        return summary;
    }

    @Override
    public String buildExperimentCard(String runId, Map<String, Object> summary) {
        Map<String, Object> headline = mapValue(summary.get("headline"));
        Map<String, Object> domainPayload = mapValue(summary.get("domainPayload"));
        return String.join("\n",
                "# Marketing Ops Policy Wind Tunnel",
                "",
                "- Run ID: `" + runId + "`",
                "- Preset: `" + summary.get("presetId") + "`",
                "- Verdict: `" + headline.get("verdict") + "`",
                "- Blockers: `" + domainPayload.get("blockerCount") + "`",
                "- Review capacity: `" + domainPayload.get("reviewCapacity") + "`",
                "- SLA target hours: `" + domainPayload.get("slaTargetHours") + "`",
                "- Approval strictness: `" + domainPayload.get("approvalStrictness") + "`",
                "- Population: `" + experimentCardPopulationLine(domainPayload) + "`",
                "",
                String.valueOf(headline.get("summary")),
                "");
    }

    private Map<String, Object> populationContext(Map<String, Object> payload) {
        Map<String, Object> selection = mapValue(payload.get("cohortSelection"));
        Map<String, Object> summary = mapValue(payload.get("cohortSummary"));
        Map<String, Object> trustedBundle = mapValue(payload.get("trustedPopulationBundle"));
        Map<String, Object> trustedSummary = mapValue(trustedBundle.get("summary"));
        Map<String, Object> trustedRecipe = mapValue(trustedBundle.get("recipe"));
        Map<String, Object> trustedValidation = mapValue(trustedBundle.get("validation"));
        Map<String, Object> trustedSegmentCounts = mapValue(trustedSummary.get("segmentCounts"));
        String cohortId = firstNonBlank(
                stringValue(selection.get("cohortId")),
                stringValue(payload.get("cohortId")));
        String recipeId = firstNonBlank(
                stringValue(selection.get("recipeId")),
                stringValue(payload.get("cohortRecipeId")));
        String domainId = firstNonBlank(
                stringValue(selection.get("domainId")),
                stringValue(payload.get("cohortDomainId")));
        int selectedTargetSize = firstPositiveInt(
                selection.get("targetSize"),
                payload.get("cohortTargetSize"),
                summary.get("totalCustomers"));
        int trustedTargetSize = firstPositiveInt(
                trustedSummary.get("totalCustomers"),
                trustedRecipe.get("targetSize"),
                trustedBundle.get("targetSize"));
        int selectedScheduledWorkItems = firstPositiveInt(
                selection.get("scheduledWorkItems"),
                summary.get("scheduledWorkItems"));
        int trustedScheduledWorkItems = firstPositiveInt(
                trustedSummary.get("scheduledWorkItems"));
        String validationStatus = firstNonBlank(
                stringValue(selection.get("validationStatus")),
                stringValue(summary.get("validationStatus")));
        String trustedValidationStatus = firstNonBlank(
                stringValue(trustedValidation.get("status")),
                stringValue(trustedSummary.get("validationStatus")));
        Map<String, Object> eligibility = mapValue(selection.get("runPathEligibility"));
        PopulationTrust trust = trustedPopulationSelection(
                cohortId,
                domainId,
                recipeId,
                validationStatus,
                selectedTargetSize,
                eligibility,
                trustedBundle,
                trustedSummary,
                trustedRecipe,
                trustedValidationStatus,
                trustedTargetSize);
        boolean consumed = trust.consumed();
        int targetSize = consumed ? trustedTargetSize : selectedTargetSize;
        int scheduledWorkItems = consumed && trustedScheduledWorkItems > 0
                ? trustedScheduledWorkItems
                : selectedScheduledWorkItems;
        Map<String, Object> segmentCounts = consumed ? trustedSegmentCounts : Map.of();
        if (consumed) {
            cohortId = firstNonBlank(stringValue(trustedBundle.get("cohortId")), cohortId);
            domainId = firstNonBlank(
                    stringValue(trustedBundle.get("domainId")),
                    firstNonBlank(stringValue(trustedSummary.get("domainId")), domainId));
            recipeId = firstNonBlank(
                    stringValue(trustedRecipe.get("recipeId")),
                    firstNonBlank(stringValue(trustedSummary.get("recipeId")), recipeId));
            validationStatus = trustedValidationStatus;
        }
        int launchAudience = intValue(segmentCounts.get("launch-audience"), 0);
        int retentionRisk = intValue(segmentCounts.get("retention-risk"), 0);
        int supportSensitive = intValue(segmentCounts.get("support-sensitive"), 0);
        int highIntent = intValue(segmentCounts.get("high-intent"), 0);
        int blockerPressure = consumed
                ? Math.max(1, (int) Math.ceil((retentionRisk + supportSensitive + Math.max(0, targetSize - highIntent)) / 25.0))
                : 0;
        int reviewCapacityAdjustment = consumed && launchAudience + highIntent >= Math.max(1, targetSize / 2) ? 1 : 0;

        Map<String, Object> population = new LinkedHashMap<>();
        population.put("schemaVersion", "scenario_lab_population_consumption.v1");
        population.put("consumed", consumed);
        population.put("cohortId", cohortId);
        population.put("domainId", domainId);
        population.put("recipeId", recipeId);
        population.put("validationStatus", validationStatus);
        population.put("targetSize", targetSize);
        population.put("scheduledWorkItems", scheduledWorkItems);
        population.put("segmentCounts", segmentCounts);
        population.put("blockerPressure", blockerPressure);
        population.put("reviewCapacityAdjustment", reviewCapacityAdjustment);
        population.put("source", consumed ? "trustedGeneratedPopulationBundle" : "none");
        if (!consumed && !trust.reason().isBlank()) {
            population.put("untrustedReason", trust.reason());
        }
        return population;
    }

    private PopulationTrust trustedPopulationSelection(
            String cohortId,
            String domainId,
            String recipeId,
            String validationStatus,
            int selectedTargetSize,
            Map<String, Object> selectionEligibility,
            Map<String, Object> trustedBundle,
            Map<String, Object> trustedSummary,
            Map<String, Object> trustedRecipe,
            String trustedValidationStatus,
            int trustedTargetSize) {
        if (cohortId.isBlank() || selectedTargetSize <= 0) {
            return new PopulationTrust(false, "");
        }
        if (!DOMAIN_ID.equals(domainId)) {
            return new PopulationTrust(false, "domain_mismatch");
        }
        if (!providerCatalogRecipeIds().contains(recipeId)) {
            return new PopulationTrust(false, "unknown_recipe");
        }
        if (!"valid".equalsIgnoreCase(validationStatus)) {
            return new PopulationTrust(false, "population_not_validated");
        }
        if (trustedBundle.isEmpty()) {
            return new PopulationTrust(false, "trusted_population_bundle_missing");
        }
        String trustedCohortId = stringValue(trustedBundle.get("cohortId"));
        String trustedDomainId = firstNonBlank(
                stringValue(trustedBundle.get("domainId")),
                stringValue(trustedSummary.get("domainId")));
        String trustedRecipeId = firstNonBlank(
                stringValue(trustedRecipe.get("recipeId")),
                stringValue(trustedSummary.get("recipeId")));
        if (!cohortId.equals(trustedCohortId)) {
            return new PopulationTrust(false, "trusted_population_cohort_mismatch");
        }
        if (!DOMAIN_ID.equals(trustedDomainId) || !domainId.equals(trustedDomainId)) {
            return new PopulationTrust(false, "trusted_population_domain_mismatch");
        }
        if (!recipeId.equals(trustedRecipeId)) {
            return new PopulationTrust(false, "trusted_population_recipe_mismatch");
        }
        if (trustedTargetSize <= 0 || selectedTargetSize != trustedTargetSize) {
            return new PopulationTrust(false, "trusted_population_target_mismatch");
        }
        if (!"valid".equalsIgnoreCase(trustedValidationStatus)) {
            return new PopulationTrust(false, "trusted_population_not_validated");
        }
        Map<String, Object> catalogEligibility = mapValue(POPULATION_CATALOG.get("runPathEligibility"));
        if (!scenarioReadyEligibility(catalogEligibility)) {
            return new PopulationTrust(false, "provider_catalog_not_scenario_ready");
        }
        int maxTargetSize = intValue(catalogEligibility.get("windTunnelReadyMaxTargetSize"), 0);
        if (maxTargetSize <= 0 || trustedTargetSize > maxTargetSize) {
            return new PopulationTrust(false, "target_size_exceeds_wind_tunnel_limit");
        }
        if (!scenarioReadyEligibility(selectionEligibility)) {
            return new PopulationTrust(false, "selection_eligibility_untrusted");
        }
        return new PopulationTrust(true, "");
    }

    private List<String> providerCatalogRecipeIds() {
        Map<String, Object> populationRecipes = mapValue(POPULATION_CATALOG.get("populationRecipes"));
        return objectMapList(populationRecipes.get("recipes")).stream()
                .map(recipe -> stringValue(recipe.get("recipeId")))
                .filter(recipeId -> !recipeId.isBlank())
                .toList();
    }

    private boolean scenarioReadyEligibility(Map<String, Object> eligibility) {
        return "SCENARIO_READY".equals(stringValue(eligibility.get("status")))
                && Boolean.TRUE.equals(eligibility.get("windTunnelReady"))
                && Boolean.TRUE.equals(eligibility.get("windTunnelConsumesPopulation"));
    }

    private String experimentCardPopulationLine(Map<String, Object> domainPayload) {
        Map<String, Object> population = mapValue(domainPayload.get("population"));
        if (!Boolean.TRUE.equals(population.get("consumed"))) {
            return "none";
        }
        return firstNonBlank(stringValue(population.get("cohortId")), "generated population")
                + " / " + intValue(population.get("targetSize"), 0) + " members";
    }

    private Map<String, Object> computeMetrics(int blockers, int capacity, int slaTargetHours) {
        int approvalsCompleted = Math.min(blockers, capacity);
        int escalations = Math.max(0, blockers - capacity);
        double queueAgeHours = Math.ceil((blockers * 8.0) / Math.max(1, capacity)) * 8.0;
        double slaRiskPct = slaTargetHours <= 0
                ? 1.0
                : Math.max(0.0, Math.min(1.0, (queueAgeHours - slaTargetHours) / slaTargetHours));
        double launchReadiness = Math.max(0.0, Math.min(100.0, 100.0 - slaRiskPct * 60.0 - escalations * 5.0));
        return Map.of(
                "capacity", capacity,
                "queueAgeHours", queueAgeHours,
                "slaRiskPct", round(slaRiskPct),
                "approvalsCompleted", approvalsCompleted,
                "escalations", escalations,
                "launchReadinessPct", round(launchReadiness));
    }

    private Map<String, Object> metric(String metricId, String label, Object value, String unit) {
        return Map.of("metricId", metricId, "label", label, "value", value, "unit", unit);
    }

    private Map<String, Object> gate(String gateId, boolean passed, String narrative) {
        return Map.of(
                "gateId", gateId,
                "status", passed ? "PASS" : "WATCH",
                "passed", passed,
                "narrative", narrative);
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

    private int strictnessAdjustment(String strictness) {
        return switch (strictness) {
            case "relaxed" -> 1;
            case "strict" -> -1;
            default -> 0;
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            BigDecimal decimal;
            if (value instanceof Number number) {
                if (number instanceof Double || number instanceof Float) {
                    double doubleValue = number.doubleValue();
                    if (!Double.isFinite(doubleValue)) {
                        return fallback;
                    }
                }
                decimal = new BigDecimal(number.toString());
            } else {
                String text = stringValue(value);
                if (text.isBlank()) {
                    return fallback;
                }
                decimal = new BigDecimal(text);
            }
            return decimal.toBigIntegerExact().intValueExact();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(stringValue(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(stringValue(key), mapValue));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    ((Map<?, ?>) item).forEach((key, mapValue) -> result.put(stringValue(key), mapValue));
                    return result;
                })
                .toList();
    }

    private int firstPositiveInt(Object... values) {
        for (Object value : values) {
            int parsed = intValue(value, 0);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String firstNonBlank(Object value, String fallback) {
        String candidate = stringValue(value);
        return candidate.isBlank() ? fallback : candidate;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record PopulationTrust(boolean consumed, String reason) {
    }
}
