package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;

import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

record BankingPolicyScenarioLabRunMetadata(
        String phase,
        double progress,
        Map<String, Object> parameters,
        List<Map<String, Object>> phaseTimings,
        List<String> logTail,
        Map<String, Object> error,
        boolean bundleReady,
        String bundleUrl,
        String evidencePackUrl,
        String pausedFromPhase) {

    private static final int MAX_LOG_LINES = 40;

    static BankingPolicyScenarioLabRunMetadata queued(Map<String, Object> parameters) {
        return new BankingPolicyScenarioLabRunMetadata(
                "QUEUED",
                0.0,
                objectMap(parameters),
                startPhaseTiming("QUEUED"),
                List.of("Run accepted by experiment-service."),
                Map.of(),
                false,
                null,
                null,
                null);
    }

    static BankingPolicyScenarioLabRunMetadata fromMetadata(Map<String, Object> metadata) {
        Map<String, Object> policy = objectMap(objectMap(metadata).get("scenarioLab"));
        return new BankingPolicyScenarioLabRunMetadata(
                firstNonBlank(stringValue(policy.get("phase")), "QUEUED"),
                toDouble(policy.get("progress"), 0.0),
                objectMap(policy.get("parameters")),
                listOfMaps(policy.get("phaseTimings")),
                stringList(policy.get("logTail")),
                objectMap(policy.get("error")),
                booleanValue(policy.get("bundleReady"), false),
                stringValue(policy.get("bundleUrl")),
                stringValue(policy.get("evidencePackUrl")),
                stringValue(policy.get("pausedFromPhase")));
    }

    BankingPolicyScenarioLabRunMetadata withLifecycle(
            String phase,
            double progress,
            boolean bundleReady,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error) {
        return withLifecycle(phase, progress, bundleReady, bundleUrl, evidencePackUrl, error, null);
    }

    BankingPolicyScenarioLabRunMetadata withLifecycle(
            String phase,
            double progress,
            boolean bundleReady,
            String bundleUrl,
            String evidencePackUrl,
            Map<String, Object> error,
            String pausedFromPhaseOverride) {
        String nextPhase = firstNonBlank(phase, this.phase);
        String nextPausedFromPhase = "PAUSED".equals(nextPhase)
                ? firstNonBlank(pausedFromPhaseOverride, pausedFromPhase, this.phase)
                : null;
        return new BankingPolicyScenarioLabRunMetadata(
                nextPhase,
                progress,
                parameters,
                transitionPhaseTimings(phaseTimings, this.phase, nextPhase),
                logTail,
                error == null ? this.error : objectMap(error),
                bundleReady,
                blank(bundleUrl) ? this.bundleUrl : bundleUrl,
                blank(evidencePackUrl) ? this.evidencePackUrl : evidencePackUrl,
                nextPausedFromPhase);
    }

    BankingPolicyScenarioLabRunMetadata appendLog(String line) {
        List<String> nextLogTail = new ArrayList<>(logTail);
        nextLogTail.add(line);
        if (nextLogTail.size() > MAX_LOG_LINES) {
            nextLogTail = new ArrayList<>(nextLogTail.subList(nextLogTail.size() - MAX_LOG_LINES, nextLogTail.size()));
        }
        return new BankingPolicyScenarioLabRunMetadata(
                phase,
                progress,
                parameters,
                phaseTimings,
                nextLogTail,
                error,
                bundleReady,
                bundleUrl,
                evidencePackUrl,
                pausedFromPhase);
    }

    BankingPolicyScenarioLabRunMetadata withParameters(Map<String, Object> nextParameters) {
        return new BankingPolicyScenarioLabRunMetadata(
                phase,
                progress,
                objectMap(nextParameters),
                phaseTimings,
                logTail,
                error,
                bundleReady,
                bundleUrl,
                evidencePackUrl,
                pausedFromPhase);
    }

    Map<String, Object> writeTo(Map<String, Object> metadata) {
        Map<String, Object> copy = new LinkedHashMap<>(objectMap(metadata));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("phase", phase);
        policy.put("progress", progress);
        policy.put("parameters", objectMap(parameters));
        policy.put("phaseTimings", new ArrayList<>(phaseTimings));
        policy.put("logTail", new ArrayList<>(logTail));
        policy.put("bundleReady", bundleReady);
        if (!blank(bundleUrl)) {
            policy.put("bundleUrl", bundleUrl);
        }
        if (!blank(evidencePackUrl)) {
            policy.put("evidencePackUrl", evidencePackUrl);
        }
        if (error != null && !error.isEmpty()) {
            policy.put("error", objectMap(error));
        }
        if (!blank(pausedFromPhase)) {
            policy.put("pausedFromPhase", pausedFromPhase);
        }
        copy.put("scenarioLab", policy);
        return copy;
    }

    PolicyWindTunnelRunRecord toStatus(PolicyWindTunnelStoredRun record, String statusPathPrefix) {
        return new PolicyWindTunnelRunRecord(
                record.runId(),
                stringValue(record.status()),
                statusPathPrefix + urlEncode(record.runId()),
                firstNonBlank(phase, stringValue(record.status())),
                objectMap(parameters),
                toOffsetDateTime(record.startedAtEpochMs(), record.createdAt()),
                record.updatedAt(),
                progress,
                listOfMaps(phaseTimings),
                stringList(logTail),
                error == null || error.isEmpty() ? null : objectMap(error),
                bundleReady,
                stringValue(bundleUrl),
                stringValue(evidencePackUrl));
    }

    private static OffsetDateTime toOffsetDateTime(Long epochMs, OffsetDateTime fallback) {
        return epochMs == null ? fallback : Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC);
    }

    private static List<Map<String, Object>> startPhaseTiming(String phase) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return List.of(linkedMap(
                "phase", firstNonBlank(phase, "UNKNOWN"),
                "status", "RUNNING",
                "startedAt", now.toString()));
    }

    private static List<Map<String, Object>> transitionPhaseTimings(
            List<Map<String, Object>> existing,
            String previousPhase,
            String nextPhase) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String normalizedNext = firstNonBlank(nextPhase, previousPhase, "UNKNOWN");
        List<Map<String, Object>> timings = new ArrayList<>();
        boolean hasOpenNextPhase = false;
        for (Map<String, Object> timing : existing == null ? List.<Map<String, Object>>of() : existing) {
            Map<String, Object> copy = new LinkedHashMap<>(objectMap(timing));
            String timingPhase = firstNonBlank(stringValue(copy.get("phase")), previousPhase, "UNKNOWN");
            boolean completed = !blank(stringValue(copy.get("completedAt")));
            if (!completed && !timingPhase.equals(normalizedNext)) {
                closeTiming(copy, now);
            }
            if (!completed && timingPhase.equals(normalizedNext)) {
                hasOpenNextPhase = true;
            }
            timings.add(copy);
        }
        if (!hasOpenNextPhase) {
            Map<String, Object> next = linkedMap(
                    "phase", normalizedNext,
                    "status", isTerminalPhase(normalizedNext) ? "COMPLETED" : "RUNNING",
                    "startedAt", now.toString());
            if (isTerminalPhase(normalizedNext)) {
                closeTiming(next, now);
            }
            timings.add(next);
        } else if (isTerminalPhase(normalizedNext)) {
            timings.stream()
                    .filter(timing -> normalizedNext.equals(stringValue(timing.get("phase"))))
                    .filter(timing -> blank(stringValue(timing.get("completedAt"))))
                    .forEach(timing -> closeTiming(timing, now));
        }
        return timings;
    }

    private static void closeTiming(Map<String, Object> timing, OffsetDateTime completedAt) {
        timing.put("completedAt", completedAt.toString());
        timing.put("status", "COMPLETED");
        timing.put("durationMs", durationMs(timing.get("startedAt"), completedAt));
    }

    private static long durationMs(Object startedAtValue, OffsetDateTime completedAt) {
        try {
            OffsetDateTime startedAt = OffsetDateTime.parse(stringValue(startedAtValue));
            return Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean isTerminalPhase(String phase) {
        String normalized = stringValue(phase);
        return "COMPLETED".equals(normalized) || "FAILED".equals(normalized) || "CANCELLED".equals(normalized);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
