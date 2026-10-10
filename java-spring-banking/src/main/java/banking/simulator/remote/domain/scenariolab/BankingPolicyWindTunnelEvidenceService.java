package banking.simulator.remote.domain.scenariolab;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactMetadata;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BankingPolicyWindTunnelEvidenceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    BankingPolicyWindTunnelEvidenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
    }

    Map<String, Object> loadBundle(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        ScenarioLabLookupReference lookupReference = resolveLookupReference(context, runId);
        ScenarioLabBundleFiles bundleFiles = resolveBundleFiles(lookupReference);
        Path summaryFile = bundleFiles.summaryFile();
        JsonNode summaryDocument = bundleFiles.summaryDocument();

        String relativeSummaryPath = relativizeToWorkspace(lookupReference, summaryFile);
        String relativeImpactedCsvPath = relativizeToWorkspace(lookupReference, bundleFiles.impactedCohortsCsv());
        String relativeExperimentCardPath = relativizeToWorkspace(lookupReference, bundleFiles.experimentCard());
        String relativeComparisonReportPath = relativizeToWorkspace(lookupReference, bundleFiles.comparisonReport());
        String relativeCheckpointsJsonPath = relativizeToWorkspace(lookupReference, bundleFiles.checkpointsJson());
        String relativeAgentDiffsJsonPath = relativizeToWorkspace(lookupReference, bundleFiles.agentDiffsJson());
        String relativeNotableActionsJsonPath = relativizeToWorkspace(lookupReference, bundleFiles.notableActionsJson());
        String relativeScheduledWorkFailuresJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.scheduledWorkFailuresJson());
        String relativeSimulationTimelineJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.simulationTimelineJson());
        String relativeSideEffectLedgerJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.sideEffectLedgerJson());
        String relativeDomainOutcomeScoreJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.domainOutcomeScoreJson());
        String relativeDomainOutcomeScoresJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.domainOutcomeScoresJson());
        String relativeBranchStateProjectionJsonPath =
                relativizeToWorkspace(lookupReference, bundleFiles.branchStateProjectionJson());
        String relativeHybridPerRunKpisPath = relativizeToWorkspace(lookupReference, bundleFiles.hybridPerRunKpisJson());
        String relativeHybridBandSummaryPath = relativizeToWorkspace(lookupReference, bundleFiles.hybridBandSummaryJson());
        String relativeHybridReplicateManifestPath =
                relativizeToWorkspace(lookupReference, bundleFiles.hybridReplicateManifestJson());
        String relativeHybridAgentAuditPath = relativizeToWorkspace(lookupReference, bundleFiles.hybridAgentAuditJson());

        Map<String, String> artifactPaths = new LinkedHashMap<>();
        artifactPaths.put("summary", relativeSummaryPath);
        artifactPaths.put("impactedCohortsCsv", relativeImpactedCsvPath);
        artifactPaths.put("experimentCard", relativeExperimentCardPath);
        artifactPaths.put("comparisonReport", relativeComparisonReportPath);
        artifactPaths.put("checkpointsJson", relativeCheckpointsJsonPath);
        artifactPaths.put("agentDiffsJson", relativeAgentDiffsJsonPath);
        artifactPaths.put("notableActionsJson", relativeNotableActionsJsonPath);
        artifactPaths.put("scheduledWorkFailuresJson", relativeScheduledWorkFailuresJsonPath);
        artifactPaths.put("simulationTimelineJson", relativeSimulationTimelineJsonPath);
        artifactPaths.put("sideEffectLedgerJson", relativeSideEffectLedgerJsonPath);
        artifactPaths.put("domainOutcomeScoreJson", relativeDomainOutcomeScoreJsonPath);
        artifactPaths.put("domainOutcomeScoresJson", relativeDomainOutcomeScoresJsonPath);
        artifactPaths.put("branchStateProjectionJson", relativeBranchStateProjectionJsonPath);
        putIfNotBlank(artifactPaths, "hybridPerRunKpis", relativeHybridPerRunKpisPath);
        putIfNotBlank(artifactPaths, "hybridBandSummary", relativeHybridBandSummaryPath);
        putIfNotBlank(artifactPaths, "hybridReplicateManifest", relativeHybridReplicateManifestPath);
        putIfNotBlank(artifactPaths, "hybridAgentAuditJson", relativeHybridAgentAuditPath);

        Map<String, String> artifactDownloads = new LinkedHashMap<>();
        artifactDownloads.put("evidencePack", buildEvidencePackDownloadUrl(lookupReference.runId()));
        artifactDownloads.put("simulationRunEvidence", "/api/simulation-runs/"
                + urlEncode(lookupReference.runId()) + "/export");
        artifactDownloads.put("summary", buildArtifactDownloadUrl(lookupReference.runId(), "summary"));
        artifactDownloads.put("impactedCohortsCsv",
                buildArtifactDownloadUrl(lookupReference.runId(), "impacted-cohorts"));
        artifactDownloads.put("experimentCard",
                buildArtifactDownloadUrl(lookupReference.runId(), "experiment-card"));
        artifactDownloads.put("comparisonReport", relativeComparisonReportPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "comparison-report"));
        artifactDownloads.put("checkpointsJson", relativeCheckpointsJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "checkpoints-json"));
        artifactDownloads.put("agentDiffsJson", relativeAgentDiffsJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "agent-diffs-json"));
        artifactDownloads.put("notableActionsJson", relativeNotableActionsJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "notable-actions-json"));
        artifactDownloads.put("scheduledWorkFailuresJson", relativeScheduledWorkFailuresJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "scheduled-work-failures-json"));
        artifactDownloads.put("simulationTimelineJson", relativeSimulationTimelineJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "simulation-timeline-json"));
        artifactDownloads.put("sideEffectLedgerJson", relativeSideEffectLedgerJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "side-effect-ledger-json"));
        artifactDownloads.put("domainOutcomeScoreJson", relativeDomainOutcomeScoreJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "domain-outcome-score-json"));
        artifactDownloads.put("domainOutcomeScoresJson", relativeDomainOutcomeScoresJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "domain-outcome-scores-json"));
        artifactDownloads.put("branchStateProjectionJson", relativeBranchStateProjectionJsonPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "branch-state-projection-json"));
        putIfNotBlank(artifactDownloads, "hybridPerRunKpis", relativeHybridPerRunKpisPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "hybrid-per-run-kpis"));
        putIfNotBlank(artifactDownloads, "hybridBandSummary", relativeHybridBandSummaryPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "hybrid-band-summary"));
        putIfNotBlank(artifactDownloads, "hybridReplicateManifest", relativeHybridReplicateManifestPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "hybrid-replicate-manifest"));
        putIfNotBlank(artifactDownloads, "hybridAgentAuditJson", relativeHybridAgentAuditPath.isBlank()
                ? ""
                : buildArtifactDownloadUrl(lookupReference.runId(), "hybrid-agent-audit"));

        Object summaryPayload = objectMapper.convertValue(summaryDocument, Object.class);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", "policy_wind_tunnel_bundle.v1");
        bundle.put("summarySchemaVersion", BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION);
        bundle.put("domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID);
        bundle.put("packId", BankingPolicyWindTunnelManifest.PACK_ID);
        bundle.put("experimentId", BankingPolicyWindTunnelManifest.EXPERIMENT_ID);
        bundle.put("runId", lookupReference.runId());
        bundle.put("presetId", summaryDocument.path("demoPreset").path("presetId").asText(""));
        if (!lookupReference.evaluationRunId().isBlank()) {
            bundle.put("evaluationRunId", lookupReference.evaluationRunId());
        }
        bundle.put("lookupMode", lookupReference.lookupMode());
        bundle.put("summaryPath", relativeSummaryPath);
        bundle.put("loadedAt", OffsetDateTime.now());
        bundle.put("decisionComparison", jsonPayload(summaryDocument.path("decisionComparison")));
        bundle.put("simulationTimeline", jsonPayload(summaryDocument.path("simulationTimeline")));
        bundle.put("sideEffectLedger", jsonPayload(summaryDocument.path("sideEffectLedger")));
        bundle.put("domainOutcomeScore", jsonPayload(summaryDocument.path("domainOutcomeScore")));
        bundle.put("domainOutcomeScores", jsonPayload(summaryDocument.path("domainOutcomeScores")));
        bundle.put("branchStateProjection", jsonPayload(summaryDocument.path("branchStateProjection")));
        bundle.put("branches", buildGenericBranches(summaryDocument));
        bundle.put("metrics", buildGenericMetrics(summaryDocument));
        bundle.put("segments", buildGenericSegments(summaryDocument));
        bundle.put("timeline", buildGenericTimeline(summaryDocument));
        bundle.put("decisionGates", buildGenericDecisionGates(summaryDocument));
        bundle.put("artifacts", Map.of(
                "paths", artifactPaths,
                "downloads", artifactDownloads));
        bundle.put("summary", summaryPayload);
        bundle.put("domainPayload", summaryPayload);
        bundle.put("impactedCohortsCsv", readOptionalText(bundleFiles.impactedCohortsCsv()));
        bundle.put("experimentCardMarkdown", readOptionalText(bundleFiles.experimentCard()));
        bundle.put("artifactPaths", artifactPaths);
        bundle.put("artifactDownloads", artifactDownloads);

        boolean hybridAvailable = bundleFiles.hybridPerRunKpisJson() != null
                || bundleFiles.hybridBandSummaryJson() != null
                || bundleFiles.hybridReplicateManifestJson() != null
                || bundleFiles.hybridAgentAuditJson() != null;
        if (hybridAvailable) {
            Map<String, Object> hybrid = new LinkedHashMap<>();
            hybrid.put("available", true);
            hybrid.put("perRunKpis", readOptionalJson(bundleFiles.hybridPerRunKpisJson()));
            hybrid.put("bandSummary", readOptionalJson(bundleFiles.hybridBandSummaryJson()));
            hybrid.put("decisionAuditSummary", summaryDocument.path("hybridDecisionAuditSummary").isObject()
                    ? objectMapper.convertValue(summaryDocument.path("hybridDecisionAuditSummary"), MAP_TYPE)
                    : Map.of());
            hybrid.put("replicateManifest", readOptionalJson(bundleFiles.hybridReplicateManifestJson()));
            hybrid.put("agentAudit", readOptionalJson(bundleFiles.hybridAgentAuditJson()));
            bundle.put("hybrid", hybrid);
        }
        return bundle;
    }

    private Map<String, Object> buildGenericBranches(JsonNode summaryDocument) {
        Map<String, Object> branches = new LinkedHashMap<>();
        branches.put("baseline", jsonPayload(summaryDocument.path("baseline")));
        branches.put("candidate", jsonPayload(summaryDocument.path("candidate")));
        branches.put("delta", jsonPayload(summaryDocument.path("delta")));
        branches.put("policyToggle", jsonPayload(summaryDocument.path("policyToggle")));
        return branches;
    }

    private Map<String, Object> buildGenericMetrics(JsonNode summaryDocument) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("headline", jsonPayload(summaryDocument.path("headline")));
        metrics.put("economics", jsonPayload(summaryDocument.path("economics")));
        metrics.put("pressure", jsonPayload(summaryDocument.path("pressureMetrics")));
        metrics.put("tradeoffs", jsonPayload(summaryDocument.path("tradeoffSummary")));
        metrics.put("evaluator", jsonPayload(summaryDocument.path("evaluator")));
        return metrics;
    }

    private Map<String, Object> buildGenericSegments(JsonNode summaryDocument) {
        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("exposure", jsonPayload(summaryDocument.path("exposureSummary")));
        segments.put("impacted", jsonPayload(summaryDocument.path("impactedSegments")));
        segments.put("impactSummary", jsonPayload(summaryDocument.path("segmentImpactSummary")));
        return segments;
    }

    private Map<String, Object> buildGenericTimeline(JsonNode summaryDocument) {
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("t0Snapshot", jsonPayload(summaryDocument.path("t0Snapshot")));
        timeline.put("checkpoints", jsonPayload(summaryDocument.path("checkpoints")));
        timeline.put("events", jsonPayload(summaryDocument.path("simulationTimeline").path("events")));
        timeline.put("sideEffects", jsonPayload(summaryDocument.path("sideEffectLedger").path("entries")));
        timeline.put("stateProjection", jsonPayload(summaryDocument.path("branchStateProjection")));
        return timeline;
    }

    private Map<String, Object> buildGenericDecisionGates(JsonNode summaryDocument) {
        Map<String, Object> decisionGates = new LinkedHashMap<>();
        decisionGates.put("evaluator", jsonPayload(summaryDocument.path("evaluator")));
        decisionGates.put("compareModeStrategy", jsonPayload(summaryDocument.path("compareModeStrategy")));
        decisionGates.put("scheduledWorkFailures", jsonPayload(summaryDocument.path("scheduledWorkFailures")));
        return decisionGates;
    }

    PolicyWindTunnelArtifactPayload readArtifact(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String type) throws IOException {
        ScenarioLabLookupReference lookupReference = resolveLookupReference(context, runId);
        ScenarioLabBundleFiles bundleFiles = resolveBundleFiles(lookupReference);
        ScenarioLabArtifactDescriptor descriptor = resolveArtifactDescriptor(bundleFiles, type);
        Path file = descriptor.path();
        if (file == null || !Files.exists(file)) {
            throw new NoSuchFileException(descriptor.type());
        }
        return new PolicyWindTunnelArtifactPayload(
                descriptor.type(),
                descriptor.contentType(),
                file.getFileName().toString(),
                Files.readAllBytes(file));
    }

    PolicyWindTunnelArtifactMetadata readArtifactMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String type) throws IOException {
        ScenarioLabLookupReference lookupReference = resolveLookupReference(context, runId);
        ScenarioLabBundleFiles bundleFiles = resolveBundleFiles(lookupReference);
        ScenarioLabArtifactDescriptor descriptor = resolveArtifactDescriptor(bundleFiles, type);
        Path file = descriptor.path();
        if (file == null || !Files.exists(file)) {
            throw new NoSuchFileException(descriptor.type());
        }
        return new PolicyWindTunnelArtifactMetadata(
                descriptor.contentType(),
                file.getFileName().toString());
    }

    PolicyWindTunnelArchivePayload buildEvidencePack(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        ScenarioLabLookupReference lookupReference = resolveLookupReference(context, runId);
        ScenarioLabBundleFiles bundleFiles = resolveBundleFiles(lookupReference);
        String archiveBaseName = buildEvidencePackBaseName(lookupReference, bundleFiles.summaryDocument());
        return new PolicyWindTunnelArchivePayload(
                archiveBaseName,
                buildEvidencePackArchive(archiveBaseName, lookupReference, bundleFiles));
    }

    PolicyWindTunnelArtifactMetadata buildEvidencePackMetadata(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        ScenarioLabLookupReference lookupReference = resolveLookupReference(context, runId);
        ScenarioLabBundleFiles bundleFiles = resolveBundleFiles(lookupReference);
        String archiveBaseName = buildEvidencePackBaseName(lookupReference, bundleFiles.summaryDocument());
        return new PolicyWindTunnelArtifactMetadata(
                "application/zip",
                archiveBaseName + ".zip");
    }

    private ScenarioLabLookupReference resolveLookupReference(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        String normalizedRunId = stringValue(runId);
        if (normalizedRunId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }

        PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun run = context.findRun(normalizedRunId)
                .orElseThrow(() -> new NoSuchFileException(normalizedRunId));
        Map<String, String> registeredArtifacts = context.registeredArtifacts(normalizedRunId);
        String resolvedSummaryPath = firstNonBlank(registeredArtifacts.get("summary"), run.summaryPath());
        if (resolvedSummaryPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Experiment run does not have a registered summary artifact or summary path: " + normalizedRunId);
        }
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();
        return new ScenarioLabLookupReference(
                normalizedRunId,
                resolvedSummaryPath,
                stringValue(run.evaluationRunId()),
                registeredArtifacts,
                registeredArtifacts.isEmpty() ? "run-record" : "run-registry",
                workspaceRoot);
    }

    private ScenarioLabBundleFiles resolveBundleFiles(ScenarioLabLookupReference lookupReference) throws IOException {
        Path summaryFile = resolveArtifactPath(lookupReference, lookupReference.summaryPath());
        JsonNode summaryDocument = readJsonValue(summaryFile, JsonNode.class);

        Path impactedCohortsCsv = resolveRegistryBackedArtifactPath(
                lookupReference,
                "impactedCohortsCsv",
                summaryFile,
                summaryDocument.path("artifacts").path("impactedCohortsCsv").asText(""),
                "impacted-cohorts.csv");
        Path experimentCard = resolveRegistryBackedArtifactPath(
                lookupReference,
                "experimentCard",
                summaryFile,
                summaryDocument.path("artifacts").path("experimentCard").asText(""),
                "experiment-card.md");
        Path comparisonReport = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "comparisonReport",
                summaryDocument.path("artifacts").path("comparisonReport").asText(""));
        Path checkpointsJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "checkpointsJson",
                summaryFile,
                summaryDocument.path("artifacts").path("checkpointsJson").asText(""),
                "checkpoints.json");
        Path agentDiffsJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "agentDiffsJson",
                summaryFile,
                summaryDocument.path("artifacts").path("agentDiffsJson").asText(""),
                "agent-diffs.json");
        Path notableActionsJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "notableActionsJson",
                summaryFile,
                summaryDocument.path("artifacts").path("notableActionsJson").asText(""),
                "notable-actions.json");
        Path scheduledWorkFailuresJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "scheduledWorkFailuresJson",
                summaryFile,
                summaryDocument.path("artifacts").path("scheduledWorkFailuresJson").asText(""),
                "scheduled-work-failures.json");
        Path simulationTimelineJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "simulationTimelineJson",
                summaryFile,
                summaryDocument.path("artifacts").path("simulationTimelineJson").asText(""),
                "simulation-timeline.json");
        Path sideEffectLedgerJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "sideEffectLedgerJson",
                summaryFile,
                summaryDocument.path("artifacts").path("sideEffectLedgerJson").asText(""),
                "side-effect-ledger.json");
        Path domainOutcomeScoreJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "domainOutcomeScoreJson",
                summaryFile,
                summaryDocument.path("artifacts").path("domainOutcomeScoreJson").asText(""),
                "domain-outcome-score.json");
        Path domainOutcomeScoresJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "domainOutcomeScoresJson",
                summaryFile,
                summaryDocument.path("artifacts").path("domainOutcomeScoresJson").asText(""),
                "domain-outcome-scores.json");
        Path branchStateProjectionJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "branchStateProjectionJson",
                summaryFile,
                summaryDocument.path("artifacts").path("branchStateProjectionJson").asText(""),
                "branch-state-projection.json");
        Path hybridPerRunKpisJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "hybridPerRunKpis",
                summaryFile,
                summaryDocument.path("artifacts").path("hybridPerRunKpis").asText(""),
                "hybrid-per-run-kpis.json");
        Path hybridBandSummaryJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "hybridBandSummary",
                summaryFile,
                summaryDocument.path("artifacts").path("hybridBandSummary").asText(""),
                "hybrid-band-summary.json");
        Path hybridReplicateManifestJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "hybridReplicateManifest",
                summaryFile,
                summaryDocument.path("artifacts").path("hybridReplicateManifest").asText(""),
                "hybrid-replicate-manifest.json");
        Path hybridAgentAuditJson = resolveRegistryBackedOptionalArtifactPath(
                lookupReference,
                "hybridAgentAuditJson",
                summaryFile,
                summaryDocument.path("artifacts").path("hybridAgentAuditJson").asText(""),
                "hybrid-agent-audit.json");

        return new ScenarioLabBundleFiles(
                summaryFile,
                summaryDocument,
                impactedCohortsCsv,
                experimentCard,
                comparisonReport,
                checkpointsJson,
                agentDiffsJson,
                notableActionsJson,
                scheduledWorkFailuresJson,
                simulationTimelineJson,
                sideEffectLedgerJson,
                domainOutcomeScoreJson,
                domainOutcomeScoresJson,
                branchStateProjectionJson,
                hybridPerRunKpisJson,
                hybridBandSummaryJson,
                hybridReplicateManifestJson,
                hybridAgentAuditJson);
    }

    private ScenarioLabArtifactDescriptor resolveArtifactDescriptor(ScenarioLabBundleFiles bundleFiles, String type) {
        String normalizedType = stringValue(type).trim().toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "summary" -> new ScenarioLabArtifactDescriptor("summary", bundleFiles.summaryFile(), "application/json");
            case "csv", "impacted-cohorts", "impacted-cohorts-csv" -> new ScenarioLabArtifactDescriptor(
                    "impacted-cohorts",
                    bundleFiles.impactedCohortsCsv(),
                    "text/csv");
            case "experiment-card", "markdown", "md" -> new ScenarioLabArtifactDescriptor(
                    "experiment-card",
                    bundleFiles.experimentCard(),
                    "text/markdown");
            case "comparison", "comparison-report" -> new ScenarioLabArtifactDescriptor(
                    "comparison-report",
                    bundleFiles.comparisonReport(),
                    "application/json");
            case "checkpoints", "checkpoints-json" -> new ScenarioLabArtifactDescriptor(
                    "checkpoints-json",
                    bundleFiles.checkpointsJson(),
                    "application/json");
            case "agent-diffs", "agent-diffs-json" -> new ScenarioLabArtifactDescriptor(
                    "agent-diffs-json",
                    bundleFiles.agentDiffsJson(),
                    "application/json");
            case "notable-actions", "notable-actions-json" -> new ScenarioLabArtifactDescriptor(
                    "notable-actions-json",
                    bundleFiles.notableActionsJson(),
                    "application/json");
            case "scheduled-work-failures", "scheduled-work-failures-json" -> new ScenarioLabArtifactDescriptor(
                    "scheduled-work-failures-json",
                    bundleFiles.scheduledWorkFailuresJson(),
                    "application/json");
            case "simulation-timeline", "simulation-timeline-json" -> new ScenarioLabArtifactDescriptor(
                    "simulation-timeline-json",
                    bundleFiles.simulationTimelineJson(),
                    "application/json");
            case "side-effect-ledger", "side-effect-ledger-json" -> new ScenarioLabArtifactDescriptor(
                    "side-effect-ledger-json",
                    bundleFiles.sideEffectLedgerJson(),
                    "application/json");
            case "domain-outcome-score", "domain-outcome-score-json" -> new ScenarioLabArtifactDescriptor(
                    "domain-outcome-score-json",
                    bundleFiles.domainOutcomeScoreJson(),
                    "application/json");
            case "domain-outcome-scores", "domain-outcome-scores-json" -> new ScenarioLabArtifactDescriptor(
                    "domain-outcome-scores-json",
                    bundleFiles.domainOutcomeScoresJson(),
                    "application/json");
            case "branch-state-projection", "branch-state-projection-json" -> new ScenarioLabArtifactDescriptor(
                    "branch-state-projection-json",
                    bundleFiles.branchStateProjectionJson(),
                    "application/json");
            case "hybrid-per-run-kpis" -> new ScenarioLabArtifactDescriptor(
                    "hybrid-per-run-kpis",
                    bundleFiles.hybridPerRunKpisJson(),
                    "application/json");
            case "hybrid-band-summary" -> new ScenarioLabArtifactDescriptor(
                    "hybrid-band-summary",
                    bundleFiles.hybridBandSummaryJson(),
                    "application/json");
            case "hybrid-replicate-manifest" -> new ScenarioLabArtifactDescriptor(
                    "hybrid-replicate-manifest",
                    bundleFiles.hybridReplicateManifestJson(),
                    "application/json");
            case "hybrid-agent-audit", "hybrid-agent-audit-json" -> new ScenarioLabArtifactDescriptor(
                    "hybrid-agent-audit-json",
                    bundleFiles.hybridAgentAuditJson(),
                    "application/json");
            default -> throw new IllegalArgumentException(
                    "Unsupported scenario-lab artifact type. Supported values: summary, impacted-cohorts, experiment-card, comparison-report, checkpoints-json, agent-diffs-json, notable-actions-json, scheduled-work-failures-json, simulation-timeline-json, side-effect-ledger-json, branch-state-projection-json, hybrid-per-run-kpis, hybrid-band-summary, hybrid-replicate-manifest, hybrid-agent-audit.");
        };
    }

    private Path resolveRegistryBackedArtifactPath(
            ScenarioLabLookupReference lookupReference,
            String artifactKey,
            Path summaryFile,
            String artifactPath,
            String fallbackFileName) {
        String registeredArtifactRef = lookupReference.registeredArtifacts().get(artifactKey);
        if (!stringValue(registeredArtifactRef).isBlank()) {
            return resolveArtifactPath(lookupReference, registeredArtifactRef);
        }
        return resolveArtifactPath(lookupReference, summaryFile, artifactPath, fallbackFileName);
    }

    private Path resolveRegistryBackedOptionalArtifactPath(
            ScenarioLabLookupReference lookupReference,
            String artifactKey,
            String artifactPath) {
        String registeredArtifactRef = lookupReference.registeredArtifacts().get(artifactKey);
        if (!stringValue(registeredArtifactRef).isBlank()) {
            return resolveArtifactPath(lookupReference, registeredArtifactRef);
        }
        return resolveOptionalArtifactPath(lookupReference, artifactPath);
    }

    private Path resolveRegistryBackedOptionalArtifactPath(
            ScenarioLabLookupReference lookupReference,
            String artifactKey,
            Path summaryFile,
            String artifactPath,
            String fallbackFileName) {
        String registeredArtifactRef = lookupReference.registeredArtifacts().get(artifactKey);
        if (!stringValue(registeredArtifactRef).isBlank()) {
            return resolveArtifactPath(lookupReference, registeredArtifactRef);
        }
        return resolveOptionalArtifactPath(lookupReference, summaryFile, artifactPath, fallbackFileName);
    }

    private byte[] buildEvidencePackArchive(
            String archiveBaseName,
            ScenarioLabLookupReference lookupReference,
            ScenarioLabBundleFiles bundleFiles) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Set<String> entryNames = new HashSet<>();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            String archiveRoot = archiveBaseName + "/";
            Map<String, Object> manifest = buildEvidencePackManifest(lookupReference, bundleFiles);
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "manifest.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            addZipEntry(
                    zipOutputStream,
                    entryNames,
                    archiveRoot + "README.md",
                    buildEvidencePackReadme(lookupReference, bundleFiles)
                            .getBytes(StandardCharsets.UTF_8));
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.summaryFile(), true);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.impactedCohortsCsv(), true);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.experimentCard(), true);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.comparisonReport(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.checkpointsJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.agentDiffsJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.notableActionsJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.scheduledWorkFailuresJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.simulationTimelineJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.sideEffectLedgerJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.domainOutcomeScoreJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.domainOutcomeScoresJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.branchStateProjectionJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.hybridPerRunKpisJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.hybridBandSummaryJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.hybridReplicateManifestJson(), false);
            addEvidencePackFile(zipOutputStream, entryNames, archiveRoot, bundleFiles.hybridAgentAuditJson(), false);
        }
        return outputStream.toByteArray();
    }

    private Map<String, Object> buildEvidencePackManifest(
            ScenarioLabLookupReference lookupReference,
            ScenarioLabBundleFiles bundleFiles) {
        JsonNode summaryDocument = bundleFiles.summaryDocument();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", lookupReference.runId());
        manifest.put("summaryPath", archiveArtifactPath(bundleFiles.summaryFile()));
        manifest.put("evaluationRunId", lookupReference.evaluationRunId());
        manifest.put("scenarioId", summaryDocument.path("scenarioId").asText(""));
        manifest.put("presetId", summaryDocument.path("demoPreset").path("presetId").asText(""));
        manifest.put("compareMode", summaryDocument.path("runConfig").path("compareMode").asText(""));
        manifest.put("exportedAt", OffsetDateTime.now());
        if (summaryDocument.path("exposureSummary").isObject()) {
            manifest.put(
                    "exposureSummary",
                    objectMapper.convertValue(summaryDocument.path("exposureSummary"), MAP_TYPE));
        }

        List<Map<String, Object>> includedFiles = new ArrayList<>();
        includedFiles.add(Map.of(
                "path", "README.md",
                "generated", true,
                "purpose", "Human-readable export overview"));
        addManifestEntry(lookupReference, includedFiles, bundleFiles.summaryFile());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.impactedCohortsCsv());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.experimentCard());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.comparisonReport());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.checkpointsJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.agentDiffsJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.notableActionsJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.scheduledWorkFailuresJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.simulationTimelineJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.sideEffectLedgerJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.domainOutcomeScoreJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.domainOutcomeScoresJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.branchStateProjectionJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.hybridPerRunKpisJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.hybridBandSummaryJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.hybridReplicateManifestJson());
        addManifestEntry(lookupReference, includedFiles, bundleFiles.hybridAgentAuditJson());
        manifest.put("includedFiles", includedFiles);
        return manifest;
    }

    private String buildEvidencePackReadme(
            ScenarioLabLookupReference lookupReference,
            ScenarioLabBundleFiles bundleFiles) {
        JsonNode summaryDocument = bundleFiles.summaryDocument();
        String scenarioId = summaryDocument.path("scenarioId").asText("");
        String presetId = summaryDocument.path("demoPreset").path("presetId").asText("");
        String productCode = summaryDocument.path("policyToggle").path("productCode").asText("");
        String productName = summaryDocument.path("policyToggle").path("productName").asText("");
        String policyType = summaryDocument.path("policyToggle").path("policyType").asText("");
        String compareMode = summaryDocument.path("runConfig").path("compareMode").asText("");
        JsonNode exposureSummary = summaryDocument.path("exposureSummary");
        JsonNode headline = summaryDocument.path("headline");
        JsonNode evaluator = summaryDocument.path("evaluator");

        List<String> lines = new ArrayList<>();
        lines.add("# Policy Wind Tunnel Evidence Pack");
        lines.add("");
        lines.add("## Scenario");
        lines.add("- Run ID: `" + lookupReference.runId() + "`");
        lines.add("- Summary path: `" + archiveArtifactPath(bundleFiles.summaryFile()) + "`");
        lines.add("- Scenario ID: `" + scenarioId + "`");
        lines.add("- Preset ID: `" + presetId + "`");
        lines.add("- Product: `" + productCode + "` - `" + productName + "`");
        lines.add("- Policy type: `" + policyType + "`");
        lines.add("- Compare mode: `" + compareMode + "`");
        lines.add("");
        if (exposureSummary.isObject()) {
            lines.add("## Exposure Scope");
            lines.add("- Scope label: `" + exposureSummary.path("scopeLabel").asText("Exposure Scope") + "`");
            if (!exposureSummary.path("scopeNarrative").asText("").isBlank()) {
                lines.add("- Scope narrative: " + exposureSummary.path("scopeNarrative").asText(""));
            }
            lines.add("- Active cohort: `" + exposureSummary.path("cohortAgents").asInt(0) + "`");
            lines.add("- Eligible scope: `" + exposureSummary.path("eligibleAgents").asInt(0) + "`");
            lines.add("- Out of scope: `" + exposureSummary.path("nonEligibleAgents").asInt(0) + "`");
            lines.add("- Impacted agents: `" + exposureSummary.path("impactedAgents").asInt(0) + "`");
            lines.add("- Eligible share of cohort: `"
                    + String.format(Locale.ROOT, "%.1f%%", exposureSummary.path("eligibleAgentPct").asDouble(0) * 100.0)
                    + "`");
            lines.add("- Impacted share of eligible: `"
                    + String.format(
                            Locale.ROOT,
                            "%.1f%%",
                            exposureSummary.path("impactedOfEligiblePct").asDouble(0) * 100.0)
                    + "`");
            lines.add("");
        }
        lines.add("## Headline");
        lines.add("- Impacted agents: `" + headline.path("impactedAgents").asInt(0) + "`");
        lines.add("- Top impacted segment: `" + headline.path("topImpactedSegment").asText("unclassified") + "`");
        lines.add("- Regression fail breaches: `" + evaluator.path("failBreaches").asInt(0) + "`");
        lines.add("");
        lines.add("## Included Files");
        lines.add("- `manifest.json`");
        lines.add("- `README.md`");
        lines.add("- `scenario-lab-summary.json`");
        lines.add("- `impacted-cohorts.csv`");
        lines.add("- `simulation-timeline.json`");
        lines.add("- `side-effect-ledger.json`");
        lines.add("- `branch-state-projection.json`");
        lines.add("- `experiment-card.md`");
        return String.join("\n", lines) + "\n";
    }

    private void addManifestEntry(
            ScenarioLabLookupReference lookupReference,
            List<Map<String, Object>> includedFiles,
            Path file) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", archiveArtifactPath(file));
            entry.put("sizeBytes", Files.size(file));
            includedFiles.add(entry);
        } catch (IOException ignored) {
        }
    }

    private void addEvidencePackFile(
            ZipOutputStream zipOutputStream,
            Set<String> entryNames,
            String archiveRoot,
            Path file,
            boolean required) throws IOException {
        if (file == null || !Files.exists(file)) {
            if (required) {
                throw new NoSuchFileException(String.valueOf(file));
            }
            return;
        }
        addZipEntry(zipOutputStream, entryNames, archiveRoot + zipEntryName(file), Files.readAllBytes(file));
    }

    private void addZipEntry(
            ZipOutputStream zipOutputStream,
            Set<String> entryNames,
            String entryName,
            byte[] content) throws IOException {
        String normalizedEntryName = entryName.replace('\\', '/');
        if (!entryNames.add(normalizedEntryName)) {
            return;
        }
        ZipEntry entry = new ZipEntry(normalizedEntryName);
        zipOutputStream.putNextEntry(entry);
        zipOutputStream.write(content);
        zipOutputStream.closeEntry();
    }

    private String zipEntryName(Path file) {
        return "artifacts/" + file.getFileName();
    }

    private String archiveArtifactPath(Path file) {
        if (file == null) {
            return "";
        }
        return zipEntryName(file);
    }

    private String buildArtifactDownloadUrl(String runId, String type) {
        return "/api/experiment-runs/" + urlEncode(runId) + "/artifacts/" + urlEncode(type) + "/content";
    }

    private String buildEvidencePackDownloadUrl(String runId) {
        return "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/evidence-pack";
    }

    private String buildEvidencePackBaseName(
            ScenarioLabLookupReference lookupReference,
            JsonNode summaryDocument) {
        String suffix = lookupReference.runId();
        if (suffix.isBlank()) {
            suffix = summaryDocument.path("scenarioId").asText("");
        }
        if (suffix.isBlank() && !lookupReference.summaryPath().isBlank()) {
            suffix = Path.of(lookupReference.summaryPath()).getFileName().toString();
        }
        suffix = suffix.replaceAll("\\.[A-Za-z0-9]+$", "");
        suffix = suffix.replaceAll("[^A-Za-z0-9._-]+", "-");
        suffix = suffix.replaceAll("-{2,}", "-");
        suffix = suffix.replaceAll("^-|-$", "");
        if (suffix.isBlank()) {
            suffix = "bundle";
        }
        return "scenario-lab-evidence-" + suffix;
    }

    private <T> T readJsonValue(Path file, Class<T> valueType) throws IOException {
        if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
        }
        return objectMapper.readValue(Files.readString(file), valueType);
    }

    private String readOptionalText(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        return Files.readString(file);
    }

    private Object readOptionalJson(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        return objectMapper.readValue(Files.readString(file), Object.class);
    }

    private Object jsonPayload(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private Path resolveArtifactPath(ScenarioLabLookupReference lookupReference, String pathValue) {
        String normalized = stringValue(pathValue);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Artifact path is required");
        }
        Path candidate = Path.of(normalized);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : lookupReference.workspaceRoot().resolve(candidate).toAbsolutePath().normalize();
        if (!resolved.startsWith(lookupReference.workspaceRoot())) {
            Path remapped = remapForeignWorkspaceArtifact(lookupReference, resolved);
            if (remapped != null) {
                resolved = remapped;
            }
        }
        if (!resolved.startsWith(lookupReference.workspaceRoot())) {
            throw new IllegalArgumentException(
                    "Artifact path must stay within the host workspace root: " + lookupReference.workspaceRoot());
        }
        return resolved;
    }

    private Path resolveArtifactPath(
            ScenarioLabLookupReference lookupReference,
            Path summaryFile,
            String artifactPath,
            String fallbackFileName) {
        if (!stringValue(artifactPath).isBlank()) {
            return resolveArtifactPath(lookupReference, artifactPath);
        }
        return requireWithinWorkspace(
                lookupReference,
                summaryFile.getParent().resolve(fallbackFileName).normalize());
    }

    private Path resolveOptionalArtifactPath(ScenarioLabLookupReference lookupReference, String artifactPath) {
        if (stringValue(artifactPath).isBlank()) {
            return null;
        }
        return resolveArtifactPath(lookupReference, artifactPath);
    }

    private Path resolveOptionalArtifactPath(
            ScenarioLabLookupReference lookupReference,
            Path summaryFile,
            String artifactPath,
            String fallbackFileName) {
        if (!stringValue(artifactPath).isBlank()) {
            return resolveArtifactPath(lookupReference, artifactPath);
        }
        Path fallback = requireWithinWorkspace(
                lookupReference,
                summaryFile.getParent().resolve(fallbackFileName).normalize());
        return Files.exists(fallback) ? fallback : null;
    }

    private Path requireWithinWorkspace(ScenarioLabLookupReference lookupReference, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(lookupReference.workspaceRoot())) {
            throw new IllegalArgumentException(
                    "Artifact path must stay within the host workspace root: " + lookupReference.workspaceRoot());
        }
        return normalized;
    }

    private Path remapForeignWorkspaceArtifact(ScenarioLabLookupReference lookupReference, Path absolutePath) {
        String normalized = absolutePath.normalize().toString().replace('\\', '/');
        int outputMarkerIndex = normalized.indexOf("/output/");
        if (outputMarkerIndex < 0) {
            return null;
        }
        String outputRelativePath = normalized.substring(outputMarkerIndex + 1);
        Path remapped = lookupReference.workspaceRoot().resolve(outputRelativePath).normalize();
        return remapped.startsWith(lookupReference.workspaceRoot()) ? remapped : null;
    }

    private String relativizeToWorkspace(ScenarioLabLookupReference lookupReference, Path file) {
        if (file == null) {
            return "";
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(lookupReference.workspaceRoot())) {
            return lookupReference.workspaceRoot().relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    private void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (!stringValue(value).isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String candidate = stringValue(value);
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record ScenarioLabLookupReference(
            String runId,
            String summaryPath,
            String evaluationRunId,
            Map<String, String> registeredArtifacts,
            String lookupMode,
            Path workspaceRoot) {
    }

    private record ScenarioLabBundleFiles(
            Path summaryFile,
            JsonNode summaryDocument,
            Path impactedCohortsCsv,
            Path experimentCard,
            Path comparisonReport,
            Path checkpointsJson,
            Path agentDiffsJson,
            Path notableActionsJson,
            Path scheduledWorkFailuresJson,
            Path simulationTimelineJson,
            Path sideEffectLedgerJson,
            Path domainOutcomeScoreJson,
            Path domainOutcomeScoresJson,
            Path branchStateProjectionJson,
            Path hybridPerRunKpisJson,
            Path hybridBandSummaryJson,
            Path hybridReplicateManifestJson,
            Path hybridAgentAuditJson) {
    }

    private record ScenarioLabArtifactDescriptor(
            String type,
            Path path,
            String contentType) {
    }
}
