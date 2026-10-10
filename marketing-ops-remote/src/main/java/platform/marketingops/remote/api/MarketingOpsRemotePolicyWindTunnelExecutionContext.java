package platform.marketingops.remote.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;

final class MarketingOpsRemotePolicyWindTunnelExecutionContext implements PolicyWindTunnelExecutionContext {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final String agentRuntimeUrl;
    private final String simulatorUrl;
    private final Map<String, StoredRunData> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> artifacts = new ConcurrentHashMap<>();

    MarketingOpsRemotePolicyWindTunnelExecutionContext(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl) {
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.agentRuntimeUrl = stripTrailingSlash(agentRuntimeUrl);
        this.simulatorUrl = stripTrailingSlash(simulatorUrl);
    }

    @Override
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public String agentRuntimeUrl() {
        return agentRuntimeUrl;
    }

    @Override
    public String simulatorUrl() {
        return simulatorUrl;
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
        // The remote provider owns local execution state. experiment-service
        // mirrors remote runs through its Scenario Lab proxy adapter.
    }

    @Override
    public void createRun(PolicyWindTunnelRunCreateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        runs.put(request.runId(), new StoredRunData(
                request.runId(),
                request.status(),
                request.summaryPath(),
                request.bundlePath(),
                null,
                request.startedAtEpochMs(),
                now,
                now,
                copyMap(request.metadata())));
    }

    @Override
    public Optional<PolicyWindTunnelStoredRun> findRun(String runId) {
        StoredRunData data = runs.get(runId);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(data.toStoredRun());
    }

    @Override
    public void updateRun(String runId, PolicyWindTunnelRunUpdate update) {
        runs.computeIfPresent(runId, (ignored, current) -> new StoredRunData(
                current.runId(),
                firstNonBlank(update.status(), current.status()),
                firstNonBlank(update.summaryPath(), current.summaryPath()),
                firstNonBlank(update.bundlePath(), current.bundlePath()),
                current.evaluationRunId(),
                current.startedAtEpochMs(),
                current.createdAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                copyMap(update.metadata())));
    }

    @Override
    public void registerArtifact(String runId, String artifactType, String artifactRef, String contentType) {
        artifacts.computeIfAbsent(runId, ignored -> new ConcurrentHashMap<>())
                .put(artifactType, artifactRef);
    }

    @Override
    public Map<String, String> registeredArtifacts(String runId) {
        return Map.copyOf(artifacts.getOrDefault(runId, Map.of()));
    }

    @Override
    public void linkEvaluation(String runId, String evaluationRunId) {
        runs.computeIfPresent(runId, (ignored, current) -> new StoredRunData(
                current.runId(),
                current.status(),
                current.summaryPath(),
                current.bundlePath(),
                evaluationRunId,
                current.startedAtEpochMs(),
                current.createdAt(),
                OffsetDateTime.now(ZoneOffset.UTC),
                current.metadata()));
    }

    @Override
    public Map<String, Object> evaluateSuite(Map<String, Object> suite, String baselineEvaluationRunId) {
        Map<String, Object> summary = objectMap(suite.get("summary"));
        int scenarioCount = intValue(summary.get("scenarioCount"), 1);
        int failed = intValue(summary.get("failed"), 0);
        int passed = intValue(summary.get("passed"), Math.max(0, scenarioCount - failed));
        double score = scenarioCount <= 0 ? 1.0d : Math.max(0.0d, Math.min(1.0d, passed / (double) scenarioCount));
        String runId = firstNonBlank(stringValue(suite.get("runId")), "remote-eval-" + System.currentTimeMillis());
        return linkedMap(
                "schemaVersion", "evaluation_run_report.v1",
                "run", linkedMap(
                        "runId", runId,
                        "manifestPath", firstNonBlank(stringValue(suite.get("manifestPath")), "marketing-ops-remote-policy-wind-tunnel"),
                        "generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        "endpoints", suite.getOrDefault("endpoints", Map.of())),
                "summary", linkedMap(
                        "overallVerdict", failed == 0 ? "PASS" : "FAIL",
                        "overallScore", score,
                        "scenarioCount", scenarioCount,
                        "passedScenarios", passed,
                        "failedScenarios", failed,
                        "dimensions", java.util.List.of()),
                "scenarioResults", java.util.List.of(),
                "generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    @Override
    public Map<String, Object> compareEvaluations(
            Map<String, Object> baselineReport,
            Map<String, Object> candidateReport) {
        Map<String, Object> baselineSummary = objectMap(baselineReport.get("summary"));
        Map<String, Object> candidateSummary = objectMap(candidateReport.get("summary"));
        double baselineScore = doubleValue(baselineSummary.get("overallScore"), 0.0d);
        double candidateScore = doubleValue(candidateSummary.get("overallScore"), 0.0d);
        return linkedMap(
                "baselineRunId", stringValue(objectMap(baselineReport.get("run")).get("runId")),
                "candidateRunId", stringValue(objectMap(candidateReport.get("run")).get("runId")),
                "overallDelta", candidateScore - baselineScore,
                "dimensionDeltas", java.util.List.of(),
                "scenarioDeltas", java.util.List.of(),
                "thresholdBreaches", java.util.List.of());
    }

    static MarketingOpsRemotePolicyWindTunnelExecutionContext fromConfiguration(
            ObjectMapper objectMapper,
            String workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl) {
        return new MarketingOpsRemotePolicyWindTunnelExecutionContext(
                objectMapper,
                resolveWorkspaceRoot(workspaceRoot),
                firstNonBlank(agentRuntimeUrl, "http://agent-runtime:8082"),
                firstNonBlank(simulatorUrl, "http://sim:8083"));
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : objectMapper.convertValue(source, MAP_TYPE);
    }

    private static Path resolveWorkspaceRoot(String configuredRoot) {
        if (!isBlank(configuredRoot)) {
            Path configured = Path.of(configuredRoot.trim());
            return configured.isAbsolute()
                    ? configured.toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize().resolve(configured).normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path current = cwd;
        while (current != null) {
            if (Files.isDirectory(current.resolve("marketing-ops-remote"))
                    && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> result.put(String.valueOf(key), entryValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> linkedMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = stringValue(value);
            return text.isBlank() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = stringValue(value);
            return text.isBlank() ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String stripTrailingSlash(String value) {
        String text = firstNonBlank(value);
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private record StoredRunData(
            String runId,
            String status,
            String summaryPath,
            String bundlePath,
            String evaluationRunId,
            Long startedAtEpochMs,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Map<String, Object> metadata) {

        PolicyWindTunnelStoredRun toStoredRun() {
            return new PolicyWindTunnelStoredRun(
                    runId,
                    status,
                    summaryPath,
                    bundlePath,
                    evaluationRunId,
                    startedAtEpochMs,
                    createdAt,
                    updatedAt,
                    metadata);
        }
    }
}
