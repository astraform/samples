package platform.marketingops.remote.scenariolab;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionProvider;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Marketing-ops-owned execution implementation used by the Marketing Ops
 * remote Policy Wind Tunnel provider.
 */
public final class MarketingOpsPolicyWindTunnelExecutionProvider implements PolicyWindTunnelExecutionProvider {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MarketingOpsPolicyWindTunnelDefinition definition;
    private final ObjectMapper objectMapper;

    public MarketingOpsPolicyWindTunnelExecutionProvider() {
        this(new MarketingOpsPolicyWindTunnelDefinition(), new ObjectMapper());
    }

    MarketingOpsPolicyWindTunnelExecutionProvider(
            MarketingOpsPolicyWindTunnelDefinition definition,
            ObjectMapper objectMapper) {
        this.definition = definition;
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
    }

    @Override
    public String packId() {
        return MarketingOpsPolicyWindTunnelDefinition.PACK_ID;
    }

    @Override
    public PolicyWindTunnelRunRecord startRun(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> payload,
            String executionRole) {
        Map<String, Object> normalizedPayload = definition.normalizePayload(payload);
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime completedAt = startedAt.plusSeconds(2);
        String runId = "marketing-ops-wt-" + startedAt.toEpochSecond() + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        ensureExperimentRegistered(context);
        context.createRun(new PolicyWindTunnelRunCreateRequest(
                runId,
                MarketingOpsPolicyWindTunnelDefinition.EXPERIMENT_ID,
                MarketingOpsPolicyWindTunnelDefinition.SCENARIO_ID,
                "CANDIDATE",
                stringValue(normalizedPayload.get("compareMode")),
                stringValue(normalizedPayload.get("presetId")),
                "RUNNING",
                startedAt.toInstant().toEpochMilli(),
                null,
                null,
                lifecycleMetadata(normalizedPayload, "RUNNING", 0.5, false)));

        try {
            MarketingOpsArtifacts artifacts = writeArtifacts(context, runId, normalizedPayload, startedAt);
            context.registerArtifact(runId, "summary", artifacts.summaryPath(), "application/json");
            context.registerArtifact(runId, "experimentCard", artifacts.experimentCardPath(), "text/markdown");
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    "COMPLETED",
                    artifacts.summaryPath(),
                    bundleUrl(runId),
                    completedAt.toInstant().toEpochMilli(),
                    lifecycleMetadataWithUrls(normalizedPayload, runId)));
        } catch (IOException e) {
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    "FAILED",
                    null,
                    null,
                    completedAt.toInstant().toEpochMilli(),
                    failureMetadata(normalizedPayload, e)));
        }

        return getRunStatus(context, runId);
    }

    @Override
    public PolicyWindTunnelRunRecord getRunStatus(
            PolicyWindTunnelExecutionContext context,
            String runId) {
        PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun record = context.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
        Map<String, Object> metadata = mapValue(record.metadata());
        return new PolicyWindTunnelRunRecord(
                runId,
                firstNonBlank(record.status(), "UNKNOWN"),
                "/api/scenario-lab/packs/" + packId() + "/runs/" + urlEncode(runId),
                firstNonBlank(metadata.get("phase"), record.status()),
                mapValue(metadata.get("parameters")),
                record.createdAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.createdAt(),
                record.updatedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.updatedAt(),
                doubleValue(metadata.get("progress"), 0.0),
                listOfMaps(metadata.get("phaseTimings")),
                listOfStrings(metadata.get("logTail")),
                mapValue(metadata.get("error")),
                booleanValue(metadata.get("bundleReady")),
                firstNonBlank(metadata.get("bundleUrl"), record.bundlePath()),
                stringValue(metadata.get("evidencePackUrl")));
    }

    @Override
    public PolicyWindTunnelRunRecord controlRun(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String action,
            Map<String, Object> payload) {
        PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun record = context.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown runId: " + runId));
        String status = firstNonBlank(record.status(), "UNKNOWN");
        if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status.toUpperCase(Locale.ROOT))) {
            return getRunStatus(context, runId);
        }
        String normalizedAction = stringValue(action).toUpperCase(Locale.ROOT);
        if (!"CANCEL".equals(normalizedAction)) {
            throw new IllegalArgumentException(
                    "Marketing Ops quick Wind Tunnel runs support cancel only. Unsupported action: " + action);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(mapValue(record.metadata()));
        Map<String, Object> parameters = mapValue(metadata.get("parameters"));
        metadata.put("phase", "CANCELLED");
        metadata.put("progress", 1.0d);
        metadata.put("bundleReady", false);
        metadata.put("parameters", parameters);
        metadata.put("logTail", List.of(
                "Cancel requested.",
                "Marketing Ops run stopped before an evidence bundle was finalized."));
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                "CANCELLED",
                record.summaryPath(),
                record.bundlePath(),
                OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli(),
                Map.copyOf(metadata)));
        return getRunStatus(context, runId);
    }

    @Override
    public Map<String, Object> loadBundle(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun run = context.findRun(runId)
                .orElseThrow(() -> new NoSuchFileException(runId));
        MarketingOpsArtifacts artifacts = resolveArtifacts(context, run);
        Map<String, Object> summary = readJsonMap(resolveArtifactPath(context, artifacts.summaryPath()));

        Map<String, String> artifactPaths = new LinkedHashMap<>();
        artifactPaths.put("summary", artifacts.summaryPath());
        artifactPaths.put("experimentCard", artifacts.experimentCardPath());

        Map<String, String> artifactDownloads = new LinkedHashMap<>();
        artifactDownloads.put("evidencePack", evidencePackUrl(runId));
        artifactDownloads.put("summary", artifactUrl(runId, "summary"));
        artifactDownloads.put("experimentCard", artifactUrl(runId, "experiment-card"));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", "policy_wind_tunnel_bundle.v1");
        bundle.put("summarySchemaVersion", definition.summarySchemaVersion());
        bundle.put("runId", runId);
        bundle.put("domainId", MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID);
        bundle.put("packId", packId());
        bundle.put("presetId", summary.get("presetId"));
        bundle.put("summaryPath", artifacts.summaryPath());
        bundle.put("loadedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        bundle.put("branches", summary.get("branches"));
        bundle.put("metrics", summary.get("metrics"));
        bundle.put("segments", summary.get("segments"));
        bundle.put("timeline", summary.get("timeline"));
        bundle.put("decisionGates", summary.get("decisionGates"));
        bundle.put("artifacts", Map.of(
                "paths", artifactPaths,
                "downloads", artifactDownloads));
        bundle.put("summary", summary);
        bundle.put("domainPayload", summary.get("domainPayload"));
        bundle.put("experimentCardMarkdown", Files.readString(resolveArtifactPath(context, artifacts.experimentCardPath())));
        bundle.put("artifactPaths", artifactPaths);
        bundle.put("artifactDownloads", artifactDownloads);
        return bundle;
    }

    @Override
    public PolicyWindTunnelArtifactPayload readArtifact(
            PolicyWindTunnelExecutionContext context,
            String runId,
            String artifactType) throws IOException {
        PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun run = context.findRun(runId)
                .orElseThrow(() -> new NoSuchFileException(runId));
        MarketingOpsArtifacts artifacts = resolveArtifacts(context, run);
        String normalizedType = stringValue(artifactType).toLowerCase(Locale.ROOT);
        if ("summary".equals(normalizedType)) {
            Path summary = resolveArtifactPath(context, artifacts.summaryPath());
            return new PolicyWindTunnelArtifactPayload(
                    "summary",
                    "application/json",
                    summary.getFileName().toString(),
                    Files.readAllBytes(summary));
        }
        if ("experiment-card".equals(normalizedType)
                || "experimentcard".equals(normalizedType)
                || "markdown".equals(normalizedType)
                || "md".equals(normalizedType)) {
            Path experimentCard = resolveArtifactPath(context, artifacts.experimentCardPath());
            return new PolicyWindTunnelArtifactPayload(
                    "experiment-card",
                    "text/markdown",
                    experimentCard.getFileName().toString(),
                    Files.readAllBytes(experimentCard));
        }
        throw new IllegalArgumentException("Unsupported artifact type for pack " + packId() + ": " + artifactType);
    }

    @Override
    public PolicyWindTunnelArchivePayload buildEvidencePack(
            PolicyWindTunnelExecutionContext context,
            String runId) throws IOException {
        String archiveBaseName = "scenario-lab-evidence-" + runId;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            PolicyWindTunnelArtifactPayload summary = readArtifact(context, runId, "summary");
            PolicyWindTunnelArtifactPayload card = readArtifact(context, runId, "experiment-card");
            addZipEntry(zip, archiveBaseName + "/README.md", card.content());
            addZipEntry(zip, archiveBaseName + "/scenario-lab-summary.json", summary.content());
            addZipEntry(zip, archiveBaseName + "/bundle.json", jsonBytes(loadBundle(context, runId)));
        }
        return new PolicyWindTunnelArchivePayload(archiveBaseName, outputStream.toByteArray());
    }

    private void ensureExperimentRegistered(PolicyWindTunnelExecutionContext context) {
        context.createExperiment(
                MarketingOpsPolicyWindTunnelDefinition.EXPERIMENT_ID,
                MarketingOpsPolicyWindTunnelDefinition.LABEL,
                MarketingOpsPolicyWindTunnelDefinition.DESCRIPTION,
                MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID,
                "marketing-ops-policy-wind-tunnel-provider",
                "ACTIVE");
    }

    private MarketingOpsArtifacts writeArtifacts(
            PolicyWindTunnelExecutionContext context,
            String runId,
            Map<String, Object> normalizedPayload,
            OffsetDateTime startedAt) throws IOException {
        Path runRoot = context.workspaceRoot()
                .resolve(MarketingOpsPolicyWindTunnelDefinition.ARTIFACT_ROOT)
                .resolve(runId)
                .normalize();
        Path summaryDir = runRoot.resolve("summary");
        Files.createDirectories(summaryDir);

        Map<String, Object> summaryPayload = payloadWithTrustedPopulationBundle(context, normalizedPayload);
        Map<String, Object> summary = definition.buildSummary(runId, summaryPayload, startedAt);
        String experimentCard = definition.buildExperimentCard(runId, summary);
        Path summaryPath = summaryDir.resolve("scenario-lab-summary.json");
        Path experimentCardPath = summaryDir.resolve("experiment-card.md");
        Files.write(summaryPath, jsonBytes(summary));
        Files.writeString(experimentCardPath, experimentCard);
        return new MarketingOpsArtifacts(
                relativizeToWorkspace(context, summaryPath),
                relativizeToWorkspace(context, experimentCardPath));
    }

    private Map<String, Object> payloadWithTrustedPopulationBundle(
            PolicyWindTunnelExecutionContext context,
            Map<String, Object> normalizedPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(normalizedPayload);
        payload.remove("trustedPopulationBundle");
        payload.remove("trustedPopulationLoadError");
        Map<String, Object> selection = mapValue(payload.get("cohortSelection"));
        String cohortId = firstNonBlank(selection.get("cohortId"), payload.get("cohortId"));
        if (cohortId.isBlank()) {
            return payload;
        }
        String safeCohortId = safePathToken(cohortId);
        if (safeCohortId.isBlank()) {
            payload.put("trustedPopulationLoadError", "invalid_cohort_id");
            return payload;
        }
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();
        Path cohortsRoot = workspaceRoot
                .resolve("output")
                .resolve("cohorts")
                .toAbsolutePath()
                .normalize();
        Path bundlePath = cohortsRoot
                .resolve(safeCohortId)
                .resolve("bundle.json")
                .toAbsolutePath()
                .normalize();
        if (!bundlePath.startsWith(cohortsRoot)) {
            payload.put("trustedPopulationLoadError", "cohort_bundle_path_outside_workspace");
            return payload;
        }
        if (!Files.isRegularFile(bundlePath)) {
            payload.put("trustedPopulationLoadError", "cohort_bundle_missing");
            return payload;
        }
        try {
            Map<String, Object> bundle = readJsonMap(bundlePath);
            if (!cohortId.equals(stringValue(bundle.get("cohortId")))) {
                payload.put("trustedPopulationLoadError", "cohort_bundle_id_mismatch");
                return payload;
            }
            payload.put("trustedPopulationBundle", bundle);
        } catch (IOException e) {
            payload.put("trustedPopulationLoadError", "cohort_bundle_unreadable");
        }
        return payload;
    }

    private String safePathToken(String value) {
        String token = stringValue(value);
        if (".".equals(token) || "..".equals(token)) {
            return "";
        }
        return token.matches("[A-Za-z0-9._-]+") ? token : "";
    }

    private MarketingOpsArtifacts resolveArtifacts(
            PolicyWindTunnelExecutionContext context,
            PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun run) {
        Map<String, String> registeredArtifacts = context.registeredArtifacts(run.runId());
        String summaryPath = firstNonBlank(registeredArtifacts.get("summary"), run.summaryPath());
        String experimentCardPath = firstNonBlank(registeredArtifacts.get("experimentCard"), fallbackExperimentCardPath(summaryPath));
        if (summaryPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Experiment run does not have a registered summary artifact or summary path: " + run.runId());
        }
        return new MarketingOpsArtifacts(summaryPath, experimentCardPath);
    }

    private String fallbackExperimentCardPath(String summaryPath) {
        if (summaryPath == null || summaryPath.isBlank()) {
            return "";
        }
        Path summary = Path.of(summaryPath);
        Path parent = summary.getParent();
        if (parent == null) {
            return "experiment-card.md";
        }
        return parent.resolve("experiment-card.md").toString().replace('\\', '/');
    }

    private Path resolveArtifactPath(
            PolicyWindTunnelExecutionContext context,
            String pathValue) {
        String normalized = stringValue(pathValue);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Artifact path is required");
        }
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize();
        Path candidate = Path.of(normalized);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspaceRoot.resolve(candidate).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Artifact path must stay within the host workspace root: " + workspaceRoot);
        }
        return resolved;
    }

    private Map<String, Object> lifecycleMetadata(
            Map<String, Object> normalizedPayload,
            String phase,
            double progress,
            boolean bundleReady) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packId", packId());
        metadata.put("domainId", MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID);
        metadata.put("parameters", normalizedPayload);
        metadata.put("phase", phase);
        metadata.put("progress", progress);
        metadata.put("phaseTimings", phaseTimings(phase, progress));
        metadata.put("bundleReady", bundleReady);
        metadata.put("logTail", phase.equals("COMPLETED")
                ? List.of(
                        "Marketing Ops provider accepted run.",
                        "Computed domain-owned launch readiness summary.",
                        "Run completed and evidence bundle is ready.")
                : List.of("Marketing Ops provider accepted run."));
        return metadata;
    }

    private Map<String, Object> lifecycleMetadataWithUrls(
            Map<String, Object> normalizedPayload,
            String runId) {
        Map<String, Object> metadata = lifecycleMetadata(normalizedPayload, "COMPLETED", 1.0, true);
        metadata.put("bundleUrl", bundleUrl(runId));
        metadata.put("evidencePackUrl", evidencePackUrl(runId));
        return metadata;
    }

    private Map<String, Object> failureMetadata(Map<String, Object> normalizedPayload, IOException error) {
        Map<String, Object> metadata = lifecycleMetadata(normalizedPayload, "FAILED", 1.0, false);
        metadata.put("error", Map.of(
                "type", error.getClass().getSimpleName(),
                "message", firstNonBlank(error.getMessage(), "Marketing Ops Wind Tunnel run failed")));
        metadata.put("logTail", List.of("Run failed: " + firstNonBlank(error.getMessage(), error.getClass().getSimpleName())));
        return metadata;
    }

    private String relativizeToWorkspace(
            PolicyWindTunnelExecutionContext context,
            Path path) {
        return context.workspaceRoot()
                .toAbsolutePath()
                .normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private Map<String, Object> readJsonMap(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new NoSuchFileException(path.toString());
        }
        return objectMapper.readValue(Files.readString(path), MAP_TYPE);
    }

    private void addZipEntry(ZipOutputStream zip, String entryName, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(content);
        zip.closeEntry();
    }

    private byte[] jsonBytes(Object value) throws JacksonException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    private String bundleUrl(String runId) {
        return "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/bundle";
    }

    private String evidencePackUrl(String runId) {
        return "/api/experiment-runs/" + urlEncode(runId) + "/scenario-lab/evidence-pack";
    }

    private String artifactUrl(String runId, String type) {
        return "/api/experiment-runs/" + urlEncode(runId) + "/artifacts/" + urlEncode(type) + "/content";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::mapValue)
                .toList();
    }

    private List<Map<String, Object>> phaseTimings(String phase, double progress) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean complete = progress >= 1.0 || "COMPLETED".equals(phase) || "FAILED".equals(phase);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("phase", phase);
        timing.put("status", complete ? "COMPLETED" : "RUNNING");
        timing.put("startedAt", now.toString());
        if (complete) {
            timing.put("completedAt", now.toString());
            timing.put("durationMs", 0L);
        }
        return List.of(timing);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(stringValue(value));
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

    private String firstNonBlank(Object value, Object fallback) {
        String candidate = stringValue(value);
        return candidate.isBlank() ? stringValue(fallback) : candidate;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record MarketingOpsArtifacts(String summaryPath, String experimentCardPath) {
    }
}
