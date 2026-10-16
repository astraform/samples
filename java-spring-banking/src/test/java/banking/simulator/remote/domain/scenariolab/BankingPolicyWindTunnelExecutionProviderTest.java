package banking.simulator.remote.domain.scenariolab;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelStoredRun;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionProvider;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelExecutionContext;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelService;
import banking.simulator.remote.domain.BankingRemotePolicyWindTunnelTestContexts;
import banking.simulator.remote.domain.BankingClockDrivenRateChangePlan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.DEFAULT_SIMULATION_START_EPOCH_MS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BankingPolicyWindTunnelExecutionProviderTest {
    @TempDir
    Path workspaceRoot;

    private com.sun.net.httpserver.HttpServer experimentServer;
    private java.util.concurrent.ExecutorService experimentExecutor;
    private final Map<String, RestTemplate> experimentFixtures = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Map<String, Object>> experimentRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger experimentFixtureIds = new AtomicInteger();
    private final AtomicInteger outboundHttpRequests = new AtomicInteger();
    private volatile Integer directExperimentStatus;
    private volatile Map<String, Object> directExperimentBody;

    @org.junit.jupiter.api.BeforeEach
    void startNativeExperimentFixture() throws Exception {
        experimentServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        experimentExecutor = java.util.concurrent.Executors.newCachedThreadPool();
        experimentServer.setExecutor(experimentExecutor);
        experimentServer.createContext("/", exchange -> {
            outboundHttpRequests.incrementAndGet();
            try {
                String path = exchange.getRequestURI().getPath();
                int api = path.indexOf("/api/simulation-runs/");
                if (api < 0) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                String fixtureId = path.substring(1, api);
                String runPath = path.substring(api);
                String method = exchange.getRequestMethod();
                byte[] raw = exchange.getRequestBody().readAllBytes();
                Map<String, Object> body = raw.length == 0 ? Map.of()
                        : new ObjectMapper().readValue(raw, Map.class);
                experimentRequests.add(Map.of("method", method, "path", runPath,
                        "body", body, "idempotencyKey", Optional.ofNullable(
                                exchange.getRequestHeaders().getFirst("Idempotency-Key")).orElse(""),
                        "rawBodyBase64", Base64.getEncoder().encodeToString(raw)));
                int status;
                Object responseBody;
                if (directExperimentStatus != null) {
                    status = directExperimentStatus;
                    responseBody = directExperimentBody;
                    if (status == -1) {
                        return; // Actual uncertain HTTP response; no replacement request is accepted.
                    }
                } else {
                    // Existing lifecycle tests retain their configured server responses. The SDK and its
                    // typed models remain real; only the owned HTTP fixture delegates to old response stubs.
                    RestTemplate fixture = experimentFixtures.get(fixtureId);
                    String fixtureUrl = "http://experiment-service:7457" + runPath;
                    if ("GET".equals(method)) {
                        responseBody = fixture.getForObject(fixtureUrl, Object.class);
                        status = responseBody == null ? 404 : 200;
                    } else if ("PATCH".equals(method)) {
                        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                        exchange.getRequestHeaders().forEach(headers::put);
                        ResponseEntity<Object> response = fixture.exchange(fixtureUrl, HttpMethod.PATCH,
                                new HttpEntity<>(body, headers), Object.class);
                        status = response == null ? 500 : response.getStatusCode().value();
                        responseBody = response == null ? Map.of("message", "Unconfigured response") : response.getBody();
                    } else {
                        status = 405;
                        responseBody = Map.of("message", "Unexpected fixture operation");
                    }
                }
                byte[] response = new ObjectMapper().writeValueAsBytes(responseBody);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (org.springframework.web.client.HttpStatusCodeException rejected) {
                byte[] response = rejected.getResponseBodyAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(rejected.getStatusCode().value(), response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception uncertain) {
                // A fixture exception closes this exact request. The SDK must retain uncertainty.
            } finally {
                exchange.close();
            }
        });
        experimentServer.start();
    }

    @AfterEach
    void stopNativeExperimentFixture() throws Exception {
        if (experimentServer != null) experimentServer.stop(0);
        if (experimentExecutor != null) {
            experimentExecutor.shutdownNow();
            assertTrue(experimentExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void malformedFinalizeRejectsBeforePreparedReceiptRestoration() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, task -> {});
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-malformed-finalize";
        seedStoredReceipt(provider, context, runId, "PREPARED", "COMPLETED");
        MutableContext absent = new MutableContext(workspaceRoot);
        assertInvalidWithoutEffects(absent, rest, () -> provider.controlRun(absent, runId, "FINALIZE", Map.of("operationId", "malformed")));
        assertInvalidWithoutEffects(absent, rest, () -> provider.controlRun(absent, runId, "FINALIZE", Map.of()));
        assertTrue(absent.findRun(runId).isEmpty());
    }

    @Test
    void nonFinalizeOperationAndWrongPublishedOperationRejectBeforeRestoration() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, task -> {});
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-wrong-operation";
        seedStoredReceipt(provider, context, runId, "PUBLISHED", "COMPLETED");
        MutableContext absent = new MutableContext(workspaceRoot);
        for (String action : List.of("CANCEL", "PAUSE", "RESUME", "SET_PLAYBACK_RATE")) {
            assertInvalidWithoutEffects(absent, rest, () -> provider.controlRun(absent, runId, action, finalizationRequest(runId)));
        }
        assertInvalidWithoutEffects(absent, rest, () -> provider.controlRun(absent, runId, "FINALIZE", Map.of("operationId", "scenario-lab-finalize:" + "0".repeat(64))));
        assertTrue(absent.findRun(runId).isEmpty());
    }

    private Object invokePure(Object owner, String name, Object... arguments) throws Exception {
        Method method = java.util.Arrays.stream(owner.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)
                        && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        method.setAccessible(true);
        try {
            return method.invoke(owner, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }

    private Map<String, String> savedFileDigests() throws Exception {
        Map<String, String> files = new java.util.TreeMap<>();
        try (var paths = Files.walk(workspaceRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(workspaceRoot.relativize(path).toString(), sha256(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private String storedContextJson(MutableContext context) throws Exception {
        Map<String, Object> records = new java.util.TreeMap<>();
        for (String runId : context.runs.keySet()) records.put(runId, context.findRun(runId).orElseThrow());
        return new ObjectMapper().rebuild().findAndAddModules().build().writeValueAsString(Map.of(
                "runs", records, "artifacts", context.registeredArtifacts));
    }

    private void assertRetiredWithoutEffects(PolicyWindTunnelExecutionContext context, RestTemplate rest,
            org.junit.jupiter.api.function.Executable operation) throws Exception {
        String before = context instanceof MutableContext memory ? storedContextJson(memory) : null;
        Map<String, String> files = savedFileDigests();
        int requests = outboundHttpRequests.get();
        RemoteDomainProtocolException rejected = assertThrows(RemoteDomainProtocolException.class, operation);
        assertEquals("scenario_lab_native_execution_unavailable", rejected.code());
        assertTrue(rejected.getMessage().contains("retired global clock"));
        if (context instanceof MutableContext memory) assertEquals(before, storedContextJson(memory));
        assertEquals(files, savedFileDigests());
        assertEquals(requests, outboundHttpRequests.get());
        verifyNoInteractions(rest);
    }

    /** A saved historical fixture, constructed by the existing pure evidence owners.
     * No clock execution, activation, admission or model output is used to create it. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> seedStoredReceipt(BankingPolicyWindTunnelExecutionProvider provider,
            MutableContext context, String runId, String state, String status) throws Exception {
        if ("PREPARED".equals(state)) createFinalizableRun(context, runId);
        else createTerminalRun(context, runId, status, "PLATFORM_OWNED_V2");
        var stored = context.findRun(runId).orElseThrow();
        Map<String, Object> parameters = BankingPolicyScenarioLabRunMetadata.fromMetadata(stored.metadata()).parameters();
        Path directory = (Path) invokePure(provider, "finalizationSummaryDirectory", context, runId);
        Files.createDirectories(directory);
        Map<String, Object> artifactPaths = (Map<String, Object>) invokePure(provider,
                "finalizedHybridArtifactPaths", workspaceRoot, directory, runId);
        Map<String, Object> summary = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("schemaVersion", "customer_outcome_simulation_summary.v1"),
                Map.entry("runId", runId), Map.entry("runConfig", parameters),
                Map.entry("fixtureProvenance", "explicit stored historical evidence; no current execution"),
                Map.entry("artifacts", artifactPaths),
                Map.entry("decisionReadiness", Map.of("status", "NOT_ASSESSED", "recommendation", "WATCH",
                        "reason", "Saved fixture evidence is not current clock execution acceptance")),
                Map.entry("decisionComparison", Map.of("comparisonId", runId + "-stored",
                        "decisionQuestion", "Inspect the recorded historical artifacts")),
                Map.entry("simulationTimeline", Map.of("schemaVersion", "customer_outcome_simulation_timeline.v1",
                        "events", List.of(Map.of("eventId", "stored-observation", "eventType", "OBSERVATION")))),
                Map.entry("sideEffectLedger", Map.of("schemaVersion", "customer_outcome_side_effect_ledger.v1",
                        "assessment", "NOT_ASSESSED", "entries", List.of())),
                Map.entry("branchStateProjection", Map.of("schemaVersion", "customer_outcome_branch_state_projection.v1",
                        "source", "stored-fixture", "branches", List.of(Map.of("branchRole", "recorded",
                                "state", Map.of("balance", -1200, "minimumPayment", 48))))),
                Map.entry("domainOutcomeScore", Map.of("status", "NOT_ASSESSED", "recommendation", "WATCH")),
                Map.entry("domainOutcomeScores", List.of(Map.of("status", "NOT_ASSESSED", "source", "stored-fixture"))),
                Map.entry("hybridAgentAudit", Map.of("source", "stored-fixture", "assessment", "NOT_ASSESSED",
                        "agents", List.of())),
                Map.entry("checkpointSummaries", List.of(Map.of("checkpointId", "stored-checkpoint",
                        "source", "stored-fixture", "state", Map.of("balance", -1200))))));
        invokePure(provider, "writeClockDrivenHybridFinalizedArtifacts", directory, summary);
        Map<String, Object> manifest = (Map<String, Object>) invokePure(provider,
                "finalizationArtifactManifest", context, artifactPaths);
        Set<String> expectedArtifactTypes = new java.util.HashSet<>(artifactPaths.keySet());
        expectedArtifactTypes.remove("simulationRunEvidence");
        assertEquals(expectedArtifactTypes, manifest.keySet());
        for (Object raw : manifest.values()) {
            Map<String, Object> entry = (Map<String, Object>) raw;
            byte[] content = Files.readAllBytes(workspaceRoot.resolve((String) entry.get("artifactRef")));
            assertEquals(((Number) entry.get("byteLength")).longValue(), content.length);
            assertEquals(entry.get("sha256"), sha256(content));
        }
        Map<String, Object> request = finalizationRequest(runId);
        Object operation = invokePure(provider, "finalizationOperation", request);
        Map<String, Object> metadata = (Map<String, Object>) invokePure(provider,
                "withFinalizationOperation", stored.metadata(), operation);
        Long completed = "PREPARED".equals(state) ? null : 1_791_993_600_000L;
        Map<String, Object> snapshot = (Map<String, Object>) invokePure(provider, "runSnapshot", stored,
                "PREPARED".equals(state) ? "RUNNING" : status, artifactPaths.get("summary"),
                "/api/experiment-runs/" + runId + "/scenario-lab/bundle", completed, metadata);
        Method digestAccessor = operation.getClass().getDeclaredMethod("inputDigest");
        digestAccessor.setAccessible(true);
        String inputDigest = (String) digestAccessor.invoke(operation);
        Map<String, Object> receipt = (Map<String, Object>) invokePure(provider, "finalizationReceipt", runId,
                "PLATFORM_OWNED_V2", operation, inputDigest, state, snapshot, manifest);
        invokePure(provider, "attachCompletionNotificationPayload", context, receipt);
        invokePure(provider, "writeFinalizationReceipt", context, runId, receipt);
        Map<String, Object> validated = (Map<String, Object>) invokePure(provider, "loadFinalizationReceipt", context, runId);
        assertEquals(new ObjectMapper().writeValueAsString(receipt), new ObjectMapper().writeValueAsString(validated));
        return validated;
    }

    private void writeChangedReceipt(String runId, Map<String, Object> receipt) throws Exception {
        Files.write(finalizationReceiptPath(runId), new ObjectMapper().writeValueAsBytes(receipt));
    }

    private void assertStoredReceiptRejectedWithoutMutation(BankingPolicyWindTunnelExecutionProvider provider,
            MutableContext context, String runId) throws Exception {
        String before = storedContextJson(context);
        Map<String, String> files = savedFileDigests();
        int requests = outboundHttpRequests.get();
        assertThrows(IllegalStateException.class, () -> provider.getRunStatus(context, runId));
        assertEquals(before, storedContextJson(context));
        assertEquals(files, savedFileDigests());
        assertEquals(requests, outboundHttpRequests.get());
    }

    private void assertInvalidWithoutEffects(MutableContext context, RestTemplate rest,
            org.junit.jupiter.api.function.Executable operation) throws Exception {
        String before = storedContextJson(context);
        Map<String, String> files = savedFileDigests();
        int requests = outboundHttpRequests.get();
        assertThrows(IllegalArgumentException.class, operation);
        assertEquals(before, storedContextJson(context));
        assertEquals(files, savedFileDigests());
        assertEquals(requests, outboundHttpRequests.get());
        verifyNoInteractions(rest);
    }

    private BankingPolicyWindTunnelExecutionProvider provider(
            ObjectMapper mapper, RestTemplate rest, java.util.concurrent.Executor executor) {
        return provider(mapper, rest, executor, System::currentTimeMillis, () -> {}, (run, operation) -> {});
    }

    private BankingPolicyWindTunnelExecutionProvider provider(
            ObjectMapper mapper, RestTemplate rest, java.util.concurrent.Executor executor,
            java.util.function.LongSupplier time) {
        return provider(mapper, rest, executor, time, () -> {}, (run, operation) -> {});
    }

    private BankingPolicyWindTunnelExecutionProvider provider(
            ObjectMapper mapper, RestTemplate rest, java.util.concurrent.Executor executor,
            java.util.function.LongSupplier time, Runnable afterAdmission) {
        return provider(mapper, rest, executor, time, afterAdmission, (run, operation) -> {});
    }

    private BankingPolicyWindTunnelExecutionProvider provider(
            ObjectMapper mapper, RestTemplate rest, java.util.concurrent.Executor executor,
            java.util.function.LongSupplier time, Runnable afterAdmission,
            java.util.function.BiConsumer<String, String> afterPrepared) {
        String id = "fixture-" + experimentFixtureIds.incrementAndGet();
        experimentFixtures.put(id, rest);
        var client = new ai.astraform.platform.client.ApiClient()
                .setConnectTimeout(java.time.Duration.ofSeconds(5))
                .setReadTimeout(java.time.Duration.ofSeconds(30));
        client.updateBaseUri("http://127.0.0.1:" + experimentServer.getAddress().getPort() + "/" + id);
        return new BankingPolicyWindTunnelExecutionProvider(
                mapper, rest, executor, time, afterAdmission, afterPrepared, client);
    }

    private Map<String, Object> completionPayload(String runId, boolean withIdentity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "COMPLETED");
        payload.put("summaryPath", "artifacts/summary.json");
        payload.put("bundlePath", "/api/experiment-runs/" + runId + "/scenario-lab/bundle");
        payload.put("completedAtEpochMs", 1_791_993_600_000L);
        payload.put("source", "java-spring-banking");
        payload.put("metadata", Map.of("scenarioLab", Map.of("ready", false,
                "scalars", List.of("48", 48, 48.25, false))));
        if (withIdentity) {
            payload.put("runId", runId);
            payload.put("operationId", "scenario-lab-finalize:identity-470");
            payload.put("inputDigest", "sha256:" + "a".repeat(64));
            payload.put("providerRevision", "java-banking-test-revision");
        }
        return payload;
    }

    private boolean completionModelPreservesIdentity() throws Exception {
        var mapper = ai.astraform.platform.client.ApiClient.createDefaultObjectMapper();
        var original = mapper.readTree(mapper.writeValueAsBytes(completionPayload("sdk-run", true)));
        var request = mapper.treeToValue(original,
                ai.astraform.platform.client.model.SimulationRunUpdateRequest.class);
        return original.equals(mapper.readTree(mapper.writeValueAsBytes(request)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeSdkRunReadPreservesActualCountsAndMetadata() throws Exception {
        directExperimentStatus = 200;
        directExperimentBody = Map.of("schemaVersion", "simulation_run_evidence.v1", "runId", "sdk-read",
                "status", "COMPLETED", "agentCount", 3, "eventCount", 48, "checkpointCount", 12,
                "metadata", Map.of("scenarioLab", Map.of("verified", false, "source", "actual-fixture")));
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        Method read = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("fetchSimulationRunRecord", String.class);
        read.setAccessible(true);
        Map<String, Object> actual = (Map<String, Object>) read.invoke(provider, "sdk-read");
        assertEquals(3L, ((Number) actual.get("agentCount")).longValue());
        assertEquals(48L, ((Number) actual.get("eventCount")).longValue());
        assertEquals(12L, ((Number) actual.get("checkpointCount")).longValue());
        assertEquals(directExperimentBody.get("metadata"), actual.get("metadata"));
        assertEquals(1, experimentRequests.size());
        assertEquals("GET", experimentRequests.get(0).get("method"));
        verifyNoInteractions(rest);
    }

    @Test
    void nativeSdkUnavailableRunReadRemainsBestEffortWithoutRetry() throws Exception {
        directExperimentStatus = 503;
        directExperimentBody = Map.of("message", "Actual unavailable fixture response");
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        Method read = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("fetchSimulationRunRecord", String.class);
        read.setAccessible(true);
        assertEquals(Map.of(), read.invoke(provider, "sdk-unavailable"));
        assertEquals(1, experimentRequests.size());
        verifyNoInteractions(rest);
    }

    @Test
    void nativeSdkSupportedCompletionUsesExactBodyWithoutTransportRetry() throws Exception {
        directExperimentStatus = 200;
        directExperimentBody = Map.of("runId", "sdk-supported", "status", "COMPLETED");
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, "sdk-supported");
        Map<String, Object> payload = completionPayload("sdk-supported", false);
        Method notify = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("notifySimulationRunCompleted",
                PolicyWindTunnelExecutionContext.class, String.class, Map.class);
        notify.setAccessible(true);
        assertEquals(true, notify.invoke(provider, context, "sdk-supported", payload));
        assertEquals(1, experimentRequests.size());
        assertEquals(payload, experimentRequests.get(0).get("body"));
        assertEquals("", experimentRequests.get(0).get("idempotencyKey"));
        verifyNoInteractions(rest);
    }

    @Test
    void nativeSdkFullCompletionPreservesIdentityOrRejectsBeforePatch() throws Exception {
        directExperimentStatus = 200;
        directExperimentBody = Map.of("runId", "sdk-identity", "status", "COMPLETED");
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, "sdk-identity");
        Map<String, Object> payload = completionPayload("sdk-identity", true);
        Method notify = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("notifySimulationRunCompleted",
                PolicyWindTunnelExecutionContext.class, String.class, Map.class);
        notify.setAccessible(true);
        boolean capable = completionModelPreservesIdentity();
        assertEquals(capable, notify.invoke(provider, context, "sdk-identity", payload));
        if (capable) {
            assertEquals(1, experimentRequests.size());
            assertEquals(payload, experimentRequests.get(0).get("body"));
            assertEquals(payload.get("operationId"), experimentRequests.get(0).get("idempotencyKey"));
        } else {
            assertEquals(List.of(), experimentRequests);
            assertTrue(BankingPolicyScenarioLabRunMetadata.fromMetadata(
                    context.findRun("sdk-identity").orElseThrow().metadata()).logTail().stream()
                    .anyMatch(line -> line.contains("notification remains pending")));
        }
        assertEquals("scenario-lab-finalize:identity-470", payload.get("operationId"));
        verifyNoInteractions(rest);
    }

    @Test
    void nativeSdkCompletionRejectionAndUncertaintyNeverRetryOrDowngrade() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, "sdk-rejected");
        Map<String, Object> payload = completionPayload("sdk-rejected", false);
        Method notify = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("notifySimulationRunCompleted",
                PolicyWindTunnelExecutionContext.class, String.class, Map.class);
        notify.setAccessible(true);
        for (int status : new int[] {409, -1}) {
            directExperimentStatus = status;
            directExperimentBody = Map.of("message", "Do not change the saved operation");
            experimentRequests.clear();
            assertEquals(false, notify.invoke(provider, context, "sdk-rejected", payload));
            assertEquals(1, experimentRequests.size());
            assertEquals(payload, experimentRequests.get(0).get("body"));
        }
        verifyNoInteractions(rest);
    }


    @Test
    void retiredClockLaunchRejectsBeforeContextFilesOrAnyOutboundRequest() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        var rejected = assertThrows(RemoteDomainProtocolException.class,
                () -> provider.startRun(context, Map.of("executionMode", "CLOCK_DRIVEN_HYBRID"), "REMOTE_PROVIDER"));
        assertTrue(rejected.getMessage().contains("retired global clock"));
        verifyNoInteractions(context, rest);
        assertEquals(0, outboundHttpRequests.get());
        try (var files = Files.list(workspaceRoot)) { assertEquals(0, files.count()); }
    }

    @Test
    void retiredClockLifecycleActionsLeaveHistoricalRecordUnchanged() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-clock-controls";
        createFinalizableRun(context, runId);
        var before = context.findRun(runId).orElseThrow();
        for (String action : List.of("CANCEL", "PAUSE", "RESUME", "SET_PLAYBACK_RATE", "FINALIZE")) {
            Map<String, Object> payload = "FINALIZE".equals(action) ? finalizationRequest(runId) : Map.of();
            var rejected = assertThrows(RemoteDomainProtocolException.class,
                    () -> provider.controlRun(context, runId, action, payload));
            assertTrue(rejected.getMessage().contains("Native Scenario Lab execution is not available"));
            assertEquals(before, context.findRun(runId).orElseThrow());
        }
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(rest);
    }

    @Test
    void retiredClockOutboundDefensesRejectReadsAndMutationsAtActualHttpBoundary() throws Exception {
        RestTemplate realTransport = new RestTemplate();
        var provider = provider(new ObjectMapper(), realTransport, Runnable::run);
        String origin = "http://127.0.0.1:" + experimentServer.getAddress().getPort();
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        when(context.simulatorUrl()).thenReturn(origin);
        for (String name : List.of("simulatorMaxPlaybackRate", "fetchSimulatorReplay", "fetchSimulationTimeEpochMs")) {
            Method read = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(name, PolicyWindTunnelExecutionContext.class);
            read.setAccessible(true);
            var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> read.invoke(provider, context));
            assertTrue(rejected.getCause() instanceof RemoteDomainProtocolException);
        }
        for (String name : List.of("clearActiveAgents", "waitForRuntimeTickDrain", "fetchDecisionOpportunityReconciliation")) {
            Method retired = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(name,
                    PolicyWindTunnelExecutionContext.class, String.class);
            retired.setAccessible(true);
            var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> retired.invoke(provider, context, "historical-run"));
            assertTrue(rejected.getCause() instanceof RemoteDomainProtocolException);
        }
        Method replay = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "postReplayContractUpdateForRun", PolicyWindTunnelExecutionContext.class,
                String.class, Map.class, String.class);
        replay.setAccessible(true);
        var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> replay.invoke(provider, context, "historical-run", Map.of("paused", true), "Pause requested"));
        assertTrue(rejected.getCause() instanceof RemoteDomainProtocolException);

        var runtime = new BankingPolicyScenarioExecutionRuntime(new ObjectMapper(), workspaceRoot,
                origin, origin, origin, realTransport);
        Path output = workspaceRoot.resolve("retired-execution");
        assertThrows(RemoteDomainProtocolException.class,
                () -> runtime.executeScenario("historical-run", "CANDIDATE", output,
                        List.of(), List.of(), List.of(), null));
        assertFalse(Files.exists(output));
        assertEquals(0, outboundHttpRequests.get());
    }

    @Test
    void historicalStoredStatusRemainsReadableAndMissingLiveEvidenceIsExplicit() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), rest, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-read-only";
        createFinalizableRun(context, runId);
        assertEquals(context.findRun(runId).orElseThrow().status(), provider.getRunStatus(context, runId).status());
        IllegalArgumentException unavailable = assertThrows(IllegalArgumentException.class, () -> provider.loadBundle(context, runId));
        assertTrue(unavailable.getMessage().contains("does not have a registered summary artifact or summary path"));
        assertThrows(IllegalArgumentException.class, () -> provider.readArtifact(context, runId, "summary"));
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(rest);
    
    }

    @AfterEach
    void stopDurableContextBackgroundRuns() throws InterruptedException {
        BankingRemotePolicyWindTunnelTestContexts.stopBackgroundRuns();
    }

    @Test
    void opportunityReconciliationBacklogAndEvidenceGapsArePartial() throws Exception {
        Method partial = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "opportunityReconciliationIsPartial",
                Map.class);
        partial.setAccessible(true);

        assertEquals(true, partial.invoke(null, emptyOpportunityReconciliation("run-test")));
        assertEquals(true, partial.invoke(null, Map.of(
                "schemaVersion", "customer_decision_opportunity_reconciliation.v1",
                "simulationRunId", "run-test",
                "ledgerReconciled", true)));
        assertEquals(false, BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                Map.of(
                        "schemaVersion", "customer_decision_opportunity_reconciliation.v1",
                        "simulationRunId", "run-test",
                        "ledgerReconciled", true),
                "run-test"));
        Map<String, Object> missingFenceProof = new LinkedHashMap<>(
                emptyOpportunityReconciliation("run-test"));
        missingFenceProof.remove("generationSealedReplayFenceDigest");
        assertFalse(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                missingFenceProof, "run-test"));
    }

    @Test
    void opportunityReconciliationRequiresManifestCoverageAndDurableTickDebtSemantics() {
        Map<String, Object> manifestOpen = unsealedOpportunityReconciliation("run-open");
        manifestOpen.put("status", "INITIALIZATION_MANIFEST_OPEN");
        manifestOpen.put("initializationManifestStatus", "OPEN");
        manifestOpen.put("expectedInitializationAgents", 2L);
        manifestOpen.put("registeredInitializationAgents", 1L);
        manifestOpen.put("resolvedInitializationAgents", 0L);
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                manifestOpen, "run-open"));

        Map<String, Object> falseGenerationOpen = new LinkedHashMap<>(manifestOpen);
        falseGenerationOpen.put("status", "GENERATION_OPEN");
        assertFalse(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                falseGenerationOpen, "run-open"));

        Map<String, Object> tickDebt = unsealedOpportunityReconciliation("run-tick-debt");
        tickDebt.put("status", "TICK_FAILURE_DEBT");
        tickDebt.put("tickFailureCount", 1L);
        tickDebt.put("tickFailureLedgerCount", 1L);
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                tickDebt, "run-tick-debt"));

        Map<String, Object> launderedTickDebt = new LinkedHashMap<>(tickDebt);
        launderedTickDebt.put("status", "GENERATION_OPEN");
        assertFalse(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                launderedTickDebt, "run-tick-debt"));

        Map<String, Object> legacyUnverified = unsealedOpportunityReconciliation("run-legacy");
        legacyUnverified.put("status", "INITIALIZATION_MANIFEST_UNVERIFIED");
        legacyUnverified.put("initializationManifestStatus", "LEGACY_UNVERIFIED");
        legacyUnverified.put("expectedInitializationAgents", 0L);
        legacyUnverified.put("registeredInitializationAgents", 0L);
        legacyUnverified.put("resolvedInitializationAgents", 0L);
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                legacyUnverified, "run-legacy"));

        Map<String, Object> sealedLegacyUnverified = new LinkedHashMap<>(
                emptyOpportunityReconciliation("run-sealed-legacy"));
        sealedLegacyUnverified.put("status", "INITIALIZATION_MANIFEST_UNVERIFIED");
        sealedLegacyUnverified.put("ledgerReconciled", false);
        sealedLegacyUnverified.put("initializationManifestStatus", "LEGACY_UNVERIFIED");
        sealedLegacyUnverified.put("expectedInitializationAgents", 0L);
        sealedLegacyUnverified.put("registeredInitializationAgents", 0L);
        sealedLegacyUnverified.put("resolvedInitializationAgents", 0L);
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                sealedLegacyUnverified, "run-sealed-legacy"));

        Map<String, Object> inconsistentTickLedger = new LinkedHashMap<>(tickDebt);
        inconsistentTickLedger.put("status", "TICK_FAILURE_LEDGER_INCONSISTENT");
        inconsistentTickLedger.put("tickFailureLedgerCount", 2L);
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                inconsistentTickLedger, "run-tick-debt"));
        inconsistentTickLedger.put("status", "TICK_FAILURE_DEBT");
        assertFalse(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                inconsistentTickLedger, "run-tick-debt"));
    }

    @Test
    void incompletePerAgentAuditMakesReconciledReportProofPartial() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse("run-test", 1).getBody();
        Method totalsMethod = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "buildOpportunityLedgerTotals",
                List.class);
        totalsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) totalsMethod.invoke(provider, agents);
        Map<String, Object> reconciliation = new LinkedHashMap<>(emptyOpportunityReconciliation("run-test"));
        reconciliation.put("status", "RECONCILED");
        reconciliation.put("expectedGeneratedOpportunities", 1);
        reconciliation.put("generatedOpportunities", 1);
        reconciliation.put("terminalOpportunities", 1);
        reconciliation.put("validDecisions", 1);
        reconciliation.put("statusCounts", Map.of(
                "PENDING", 0L,
                "RUNNING", 0L,
                "SUCCEEDED", 1L,
                "DEFERRED", 0L,
                "BLOCKED", 0L,
                "FAILED", 0L,
                "CANCELLED", 0L));

        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment proof =
                provider.assessOpportunityProof(
                        Map.of("summary", Map.of("opportunityLedger", totals), "agents", List.of()),
                        "run-test",
                        reconciliation);

        assertTrue(proof.partialEvidence());
        assertFalse(proof.agentAuditVerified());
        assertTrue(proof.reason().contains("complete, run-scoped per-agent audit"));
    }

    @Test
    void initializedZeroOpportunityCohortRequiresRunScopedAuditForEveryAgent() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> auditedAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse("run-zero", 0).getBody();
        Method totalsMethod = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "buildOpportunityLedgerTotals",
                List.class);
        totalsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> auditedTotals = (Map<String, Object>) totalsMethod.invoke(provider, auditedAgents);
        Map<String, Object> reconciliation = emptyOpportunityReconciliation("run-zero");

        assertEquals(List.of("run-zero"), auditedTotals.get("simulationRunIds"));
        assertEquals(1, auditedTotals.get("agentCount"));
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment auditedProof =
                provider.assessOpportunityProof(
                        Map.of(
                                "summary", Map.of("opportunityLedger", auditedTotals),
                                "agents", auditedAgents),
                        "run-zero",
                        reconciliation);
        assertTrue(auditedProof.agentAuditVerified());
        assertTrue(auditedProof.partialEvidence());

        @SuppressWarnings("unchecked")
        Map<String, Object> missingTotals = (Map<String, Object>) totalsMethod.invoke(provider, List.of());
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment missingProof =
                provider.assessOpportunityProof(
                        Map.of(
                                "summary", Map.of("opportunityLedger", missingTotals),
                                "agents", List.of()),
                        "run-zero",
                        reconciliation);
        assertFalse(missingProof.agentAuditVerified());
        assertTrue(missingProof.partialEvidence());

        Map<String, Object> unauditedAgent = hybridDecisionAgent(
                "Generated Customer 1",
                0,
                0,
                0,
                0);
        unauditedAgent.put("agentId", "runtime-2");
        unauditedAgent.put("runtimeIdentity", "runtime-2");
        List<Map<String, Object>> partiallyAuditedAgents = new ArrayList<>(auditedAgents);
        partiallyAuditedAgents.add(unauditedAgent);
        @SuppressWarnings("unchecked")
        Map<String, Object> partiallyAuditedTotals =
                (Map<String, Object>) totalsMethod.invoke(provider, partiallyAuditedAgents);
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment partiallyAuditedProof =
                provider.assessOpportunityProof(
                        Map.of(
                                "summary", Map.of("opportunityLedger", partiallyAuditedTotals),
                                "agents", partiallyAuditedAgents),
                        "run-zero",
                        reconciliation);
        assertFalse(partiallyAuditedProof.agentAuditVerified());
        assertTrue(partiallyAuditedProof.partialEvidence());
    }

    @Test
    void deterministicOutcomeReviewDoesNotRequireOrClaimAgentDecisionAuditVerification() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);

        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment proof =
                provider.assessOpportunityProof(
                        Map.of(),
                        "run-deterministic",
                        emptyOpportunityReconciliation("run-deterministic"),
                        "DETERMINISTIC");

        assertFalse(proof.partialEvidence());
        assertFalse(proof.agentAuditVerified());
        assertFalse(proof.agentAuditRequired());
        assertTrue(proof.reason().isBlank());

        Map<String, Object> agenticSummary = new LinkedHashMap<>();
        agenticSummary.put("agentDecisionMode", "DETERMINISTIC");
        agenticSummary.put("actualDecisionCount", 0L);
        agenticSummary.put("evidenceIntegrityWarnings", List.of());
        Method applyReconciliation = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "applyOpportunityReconciliationToAgenticSummary",
                Map.class,
                Map.class,
                Map.class,
                String.class,
                Map.class,
                boolean.class);
        applyReconciliation.setAccessible(true);
        applyReconciliation.invoke(
                provider,
                agenticSummary,
                Map.of(),
                Map.of(),
                "run-deterministic",
                emptyOpportunityReconciliation("run-deterministic"),
                true);

        assertEquals(false, agenticSummary.get("opportunityAgentAuditRequired"));
        assertEquals(false, agenticSummary.get("opportunityAdmissionCountVerified"));
        List<?> warnings = (List<?>) agenticSummary.get("evidenceIntegrityWarnings");
        assertFalse(warnings.stream().anyMatch(warning ->
                "OPPORTUNITY_AGENT_AUDIT_UNVERIFIED".equals(((Map<?, ?>) warning).get("code"))));
    }

    @Test
    void deterministicModeIsPartialWhenAnyAgentOrDurableDecisionEvidenceExists() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        Map<String, Object> rawDecisionAudit = Map.of(
                "summary", Map.of("decisionCount", 1),
                "totals", Map.of("decisionCount", 1),
                "agents", List.of(Map.of(
                        "agentId", "runtime-1",
                        "eventCount", 1,
                        "totalDecisionCount", 1,
                        "decisions", List.of(Map.of("action", "ASK_FOR_HELP")))));

        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment rawProof =
                provider.assessOpportunityProof(
                        rawDecisionAudit,
                        "run-deterministic-raw",
                        emptyOpportunityReconciliation("run-deterministic-raw"),
                        "DETERMINISTIC");
        assertTrue(rawProof.partialEvidence());
        assertFalse(rawProof.agentAuditRequired());
        assertTrue(rawProof.reason().contains("Deterministic decision mode recorded non-zero"));

        Map<String, Object> agenticSummary = new LinkedHashMap<>();
        agenticSummary.put("agentDecisionMode", "DETERMINISTIC");
        agenticSummary.put("actualDecisionCount", 1L);
        agenticSummary.put("evidenceIntegrityWarnings", List.of());
        Method applyReconciliation = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "applyOpportunityReconciliationToAgenticSummary",
                Map.class,
                Map.class,
                Map.class,
                String.class,
                Map.class,
                boolean.class);
        applyReconciliation.setAccessible(true);
        applyReconciliation.invoke(
                provider,
                agenticSummary,
                rawDecisionAudit,
                Map.of(),
                "run-deterministic-raw",
                emptyOpportunityReconciliation("run-deterministic-raw"),
                true);
        assertEquals("DECISION_MODE_EVIDENCE_CONFLICT", agenticSummary.get("decisionCoverageStatus"));
        List<?> warnings = (List<?>) agenticSummary.get("evidenceIntegrityWarnings");
        assertTrue(warnings.stream().anyMatch(warning ->
                "DECISION_MODE_EVIDENCE_CONFLICT".equals(((Map<?, ?>) warning).get("code"))));

        String durableRunId = "run-deterministic-durable";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> durableAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(durableRunId, 1).getBody();
        Map<String, Object> durableAudit = opportunityDecisionAudit(provider, durableAgents, 1);
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment durableProof =
                provider.assessOpportunityProof(
                        durableAudit,
                        durableRunId,
                        reconciledOpportunityReconciliation(durableRunId, 1),
                        "DETERMINISTIC");
        assertTrue(durableProof.agentAuditVerified());
        assertFalse(durableProof.agentAuditRequired());
        assertTrue(durableProof.partialEvidence());
        assertTrue(durableProof.reason().contains("Deterministic decision mode recorded non-zero"));
    }

    @Test
    void opportunityAuditRequiresOwnedUniqueContractValidRowsForEveryValidDecision() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);

        String missingRunId = "run-missing-decision-row";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missingAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(missingRunId, 1).getBody();
        missingAgents.getFirst().put("decisions", List.of());
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment missingProof =
                provider.assessOpportunityProof(
                        opportunityDecisionAudit(provider, missingAgents, 1),
                        missingRunId,
                        reconciledOpportunityReconciliation(missingRunId, 1));
        assertFalse(missingProof.agentAuditVerified());
        assertTrue(missingProof.partialEvidence());

        String duplicateRunId = "run-duplicate-opportunity-row";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicateAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(duplicateRunId, 2).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicateRows =
                (List<Map<String, Object>>) duplicateAgents.getFirst().get("decisions");
        Map<String, Object> duplicateRow = new LinkedHashMap<>(duplicateRows.get(1));
        duplicateRow.put("opportunityId", duplicateRows.getFirst().get("opportunityId"));
        duplicateRows.set(1, duplicateRow);
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment duplicateProof =
                provider.assessOpportunityProof(
                        opportunityDecisionAudit(provider, duplicateAgents, 2),
                        duplicateRunId,
                        reconciledOpportunityReconciliation(duplicateRunId, 2));
        assertFalse(duplicateProof.agentAuditVerified());
        assertTrue(duplicateProof.partialEvidence());

        String invalidContractRunId = "run-invalid-contract-row";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidContractAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(invalidContractRunId, 1).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidContractRows =
                (List<Map<String, Object>>) invalidContractAgents.getFirst().get("decisions");
        Map<String, Object> invalidContractRow = new LinkedHashMap<>(invalidContractRows.getFirst());
        invalidContractRow.put("opportunityDecisionContractValid", false);
        invalidContractRows.set(0, invalidContractRow);
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment invalidContractProof =
                provider.assessOpportunityProof(
                        opportunityDecisionAudit(provider, invalidContractAgents, 1),
                        invalidContractRunId,
                        reconciledOpportunityReconciliation(invalidContractRunId, 1));
        assertFalse(invalidContractProof.agentAuditVerified());
        assertTrue(invalidContractProof.partialEvidence());

        String foreignRunId = "run-foreign-agent-audit";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> foreignAgents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(foreignRunId, 1).getBody();
        foreignAgents.getFirst().put("agentId", "foreign-agent");
        foreignAgents.getFirst().put("runtimeIdentity", "foreign-agent");
        foreignAgents.getFirst().put("memoryKey", "foreign-agent");
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment foreignProof =
                provider.assessOpportunityProof(
                        opportunityDecisionAudit(provider, foreignAgents, 1),
                        foreignRunId,
                        reconciledOpportunityReconciliation(foreignRunId, 1));
        assertFalse(foreignProof.agentAuditVerified());
        assertTrue(foreignProof.partialEvidence());
    }

    @Test
    void invalidCorrelatedEventOnlyDecisionIsAnEvidenceGapNotLegacyCadence() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        String runId = "run-invalid-event-only";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(runId, 0).getBody();
        Map<String, Object> agent = agents.getFirst();
        agent.put("eventCount", 1);
        agent.put("totalDecisionCount", 1);
        agent.put("decisions", List.of(Map.ofEntries(
                Map.entry("action", "INVALID_CUSTOMER_DECISION"),
                Map.entry("opportunityId", "opportunity-failed-1"),
                Map.entry("opportunityAttempt", 1),
                Map.entry("opportunityDecisionContractValid", false))));
        @SuppressWarnings("unchecked")
        Map<String, Object> failedAudit = new LinkedHashMap<>(
                (Map<String, Object>) agent.get("opportunityAudit"));
        failedAudit.put("generatedOpportunities", 1);
        failedAudit.put("terminalOpportunities", 1);
        failedAudit.put("admittedOpportunities", 1);
        failedAudit.put("totalAttempts", 1);
        failedAudit.put("validDecisions", 0);
        failedAudit.put("decisionEvidenceGaps", 1);
        failedAudit.put("statusCounts", Map.of(
                "PENDING", 0L,
                "RUNNING", 0L,
                "SUCCEEDED", 0L,
                "DEFERRED", 0L,
                "BLOCKED", 0L,
                "FAILED", 1L,
                "CANCELLED", 0L));
        agent.put("opportunityAudit", failedAudit);
        Map<String, Object> decisionAudit = opportunityDecisionAudit(provider, agents, 1);

        Map<String, Object> reconciliation = new LinkedHashMap<>(emptyOpportunityReconciliation(runId));
        reconciliation.put("status", "RECONCILED");
        reconciliation.put("expectedGeneratedOpportunities", 1);
        reconciliation.put("generatedOpportunities", 1);
        reconciliation.put("terminalOpportunities", 1);
        reconciliation.put("validDecisions", 0);
        reconciliation.put("decisionEvidenceGaps", 1);
        reconciliation.put("statusCounts", failedAudit.get("statusCounts"));
        assertTrue(BankingPolicyWindTunnelExecutionProvider.opportunityReconciliationIsValid(
                reconciliation,
                runId));

        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment proof =
                provider.assessOpportunityProof(
                        decisionAudit,
                        runId,
                        reconciliation,
                        "REAL_LLM");
        assertTrue(proof.agentAuditVerified());
        assertTrue(proof.partialEvidence());
        assertFalse(proof.reason().contains("Legacy cadence telemetry"));

        Map<String, Object> agenticSummary = new LinkedHashMap<>();
        agenticSummary.put("agentDecisionMode", "REAL_LLM");
        agenticSummary.put("actualDecisionCount", 1L);
        agenticSummary.put("evidenceIntegrityWarnings", List.of());
        Method applyReconciliation = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "applyOpportunityReconciliationToAgenticSummary",
                Map.class,
                Map.class,
                Map.class,
                String.class,
                Map.class,
                boolean.class);
        applyReconciliation.setAccessible(true);
        applyReconciliation.invoke(
                provider,
                agenticSummary,
                decisionAudit,
                Map.of(),
                runId,
                reconciliation,
                true);
        assertEquals(0L, agenticSummary.get("legacyCadenceDecisionCount"));
        assertEquals("RECONCILED_WITH_EVIDENCE_GAPS", agenticSummary.get("decisionCoverageStatus"));
        List<?> warnings = (List<?>) agenticSummary.get("evidenceIntegrityWarnings");
        assertFalse(warnings.stream().anyMatch(warning ->
                "EVIDENCE_MODEL_SPLIT".equals(((Map<?, ?>) warning).get("code"))));
        assertTrue(warnings.stream().anyMatch(warning ->
                "OPPORTUNITY_DECISION_EVIDENCE_GAPS".equals(((Map<?, ?>) warning).get("code"))));
    }

    @Test
    void nonzeroLegacyDurableDecisionMismatchIsPartialForStubAndRealModes() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        String runId = "run-nonzero-split";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse(runId, 1).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisionRows =
                (List<Map<String, Object>>) agents.getFirst().get("decisions");
        Map<String, Object> correlatedDecision = new LinkedHashMap<>(decisionRows.getFirst());
        correlatedDecision.put("opportunityId", "opportunity-1");
        correlatedDecision.put("opportunityDecisionContractValid", true);
        decisionRows.set(0, correlatedDecision);
        decisionRows.add(Map.of(
                "action", "ASK_FOR_HELP",
                "reason", "Legacy cadence decision without an opportunity correlation.",
                "fallbackApplied", false,
                "responseFormat", "structured",
                "contractSchemaVersion", "hybrid_micro_decision_v1"));
        agents.getFirst().put("totalDecisionCount", 2);
        agents.getFirst().put("eventCount", 2);
        Method totalsMethod = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "buildOpportunityLedgerTotals",
                List.class);
        totalsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> opportunityTotals =
                (Map<String, Object>) totalsMethod.invoke(provider, agents);
        Map<String, Object> decisionAudit = Map.of(
                "summary", Map.of(
                        "decisionCount", 2,
                        "opportunityLedger", opportunityTotals),
                "totals", Map.of("decisionCount", 2),
                "agents", agents);
        Map<String, Object> reconciliation = reconciledOpportunityReconciliation(runId, 1);
        Method applyReconciliation = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "applyOpportunityReconciliationToAgenticSummary",
                Map.class,
                Map.class,
                Map.class,
                String.class,
                Map.class,
                boolean.class);
        applyReconciliation.setAccessible(true);

        for (String decisionMode : List.of("STUB_LLM", "REAL_LLM")) {
            BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment proof =
                    provider.assessOpportunityProof(
                            decisionAudit,
                            runId,
                            reconciliation,
                            decisionMode);
            assertTrue(proof.agentAuditRequired());
            assertTrue(proof.agentAuditVerified());
            assertTrue(proof.partialEvidence());
            assertTrue(proof.reason().contains("Legacy cadence telemetry recorded 1"));
            assertTrue(proof.reason().contains("durable opportunity ledger reconciled 1"));

            Map<String, Object> agenticSummary = new LinkedHashMap<>();
            agenticSummary.put("agentDecisionMode", decisionMode);
            agenticSummary.put("actualDecisionCount", 2L);
            agenticSummary.put("evidenceIntegrityWarnings", List.of());
            applyReconciliation.invoke(
                    provider,
                    agenticSummary,
                    decisionAudit,
                    Map.of(),
                    runId,
                    reconciliation,
                    true);

            assertEquals("EVIDENCE_MODEL_SPLIT", agenticSummary.get("decisionCoverageStatus"));
            assertEquals(1L, agenticSummary.get("legacyCadenceDecisionCount"));
            assertEquals(1L, agenticSummary.get("durableValidDecisionCount"));
            assertEquals(1L, agenticSummary.get("actualDecisionCount"));
            List<?> warnings = (List<?>) agenticSummary.get("evidenceIntegrityWarnings");
            assertTrue(warnings.stream().anyMatch(warning ->
                    "EVIDENCE_MODEL_SPLIT".equals(((Map<?, ?>) warning).get("code"))));
        }

        Map<String, Object> eventOnlyAgent = new LinkedHashMap<>(agents.getFirst());
        eventOnlyAgent.put("decisions", List.of(correlatedDecision));
        eventOnlyAgent.put("totalDecisionCount", 1);
        eventOnlyAgent.put("eventCount", 1);
        Map<String, Object> eventOnlyAudit = Map.of(
                "summary", Map.of(
                        "decisionCount", 1,
                        "opportunityLedger", opportunityTotals),
                "totals", Map.of("decisionCount", 1),
                "agents", List.of(eventOnlyAgent));
        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment eventOnlyProof =
                provider.assessOpportunityProof(
                        eventOnlyAudit,
                        runId,
                        reconciliation,
                        "REAL_LLM");
        assertTrue(eventOnlyProof.agentAuditVerified());
        assertFalse(eventOnlyProof.partialEvidence());
    }

    @Test
    void positiveLegacyUnspecifiedDebtMakesReconciledReportProofPartial() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) (List<?>)
                opportunityHybridDecisionResponse("run-test", 1).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> originalAudit = (Map<String, Object>) agents.getFirst().get("opportunityAudit");
        Map<String, Object> legacyAudit = new LinkedHashMap<>(originalAudit);
        legacyAudit.put("historicalDeferralCount", 1L);
        legacyAudit.put("historicalDeferralReasonCounts", Map.of("unspecified", 1L));
        agents.getFirst().put("opportunityAudit", legacyAudit);

        Method totalsMethod = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "buildOpportunityLedgerTotals",
                List.class);
        totalsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) totalsMethod.invoke(provider, agents);
        Map<String, Object> reconciliation = new LinkedHashMap<>(emptyOpportunityReconciliation("run-test"));
        reconciliation.put("status", "RECONCILED");
        reconciliation.put("expectedGeneratedOpportunities", 1);
        reconciliation.put("generatedOpportunities", 1);
        reconciliation.put("terminalOpportunities", 1);
        reconciliation.put("validDecisions", 1);
        reconciliation.put("statusCounts", Map.of(
                "PENDING", 0L,
                "RUNNING", 0L,
                "SUCCEEDED", 1L,
                "DEFERRED", 0L,
                "BLOCKED", 0L,
                "FAILED", 0L,
                "CANCELLED", 0L));

        BankingPolicyWindTunnelExecutionProvider.OpportunityProofAssessment proof =
                provider.assessOpportunityProof(
                        Map.of("summary", Map.of("opportunityLedger", totals), "agents", agents),
                        "run-test",
                        reconciliation);

        assertEquals(1, totals.get("invalidAgentAuditCount"));
        assertTrue(proof.partialEvidence());
        assertFalse(proof.agentAuditVerified());
    }

    @Test
    void opportunityAuditRejectsMissingAndMalformedReasonMaps() throws Exception {
        Method validator = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "opportunityAgentAuditIsValid",
                Map.class);
        validator.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) opportunityHybridDecisionResponse(
                "run-test",
                1).getBody().getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> validAudit = new LinkedHashMap<>(
                (Map<String, Object>) agent.get("opportunityAudit"));
        assertTrue((Boolean) validator.invoke(null, validAudit));

        Map<String, Object> legacyAudit = new LinkedHashMap<>(validAudit);
        legacyAudit.put("deferredReasonCounts", Map.of("unspecified", 0L));
        legacyAudit.put("historicalDeferralReasonCounts", Map.of("unspecified", 0L));
        assertTrue((Boolean) validator.invoke(null, legacyAudit));

        for (String field : List.of("deferredReasonCounts", "historicalDeferralReasonCounts")) {
            Map<String, Object> missing = new LinkedHashMap<>(validAudit);
            missing.remove(field);
            assertFalse((Boolean) validator.invoke(null, missing));

            for (Object invalid : new Object[] {
                    null,
                    "not-an-object",
                    Map.of("", 0L),
                    Map.of(" provider_unavailable ", 0L),
                    Map.of("x".repeat(129), 0L),
                    Map.of("valid_customer_decision_turn", 0L),
                    Map.of("future_provider_pause", 0L),
                    Map.of("provider_unavailable_\u00e9", 0L),
                    Map.of("unspecified", 1L)
            }) {
                Map<String, Object> malformed = new LinkedHashMap<>(validAudit);
                malformed.put(field, invalid);
                assertFalse((Boolean) validator.invoke(null, malformed));
            }
        }
    }

    @Test
    void opportunityAuditDeferredReasonsMatchTheV1LifecycleContract() throws Exception {
        Method parser = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "strictOpportunityReasonCounts",
                Object.class);
        parser.setAccessible(true);
        assertEquals(Map.of("unspecified", 0L), parser.invoke(null, Map.of("unspecified", 0L)));
        assertNull(parser.invoke(null, Map.of("unspecified", 1L)));
    }

    @Test
    void presetResolutionPreservesScenarioLabCohortSelection() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());
        BankingPolicyScenarioLabRequest request = presetService.resolveRequest(Map.ofEntries(
                Map.entry("presetId", "saver-repricing"),
                Map.entry("simulationRunId", "policy-wt-platform-owned-test"),
                Map.entry("cohortId", "banking-balanced-banking-100-test"),
                Map.entry("cohortTargetSize", 100),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "HOURLY"),
                Map.entry("agentDecisionIntervalMinutes", 60),
                Map.entry("cohortArtifactRefs", Map.of("customers", Map.of(
                        "type", "customers",
                        "downloadUrl", "/api/dashboard/cohort-factory/artifact?cohortId=banking-balanced-banking-100-test&type=customers"))),
                Map.entry("cohortSelection", Map.of(
                        "cohortId", "banking-balanced-banking-100-test",
                        "recipeId", "balanced-banking-100"))),
                "PLATFORM_ADMIN");

        assertEquals("banking-balanced-banking-100-test", request.normalizedPayload().get("cohortId"));
        assertEquals("policy-wt-platform-owned-test", request.normalizedPayload().get("simulationRunId"));
        assertEquals(100, request.normalizedPayload().get("cohortTargetSize"));
        assertEquals("CLOCK_DRIVEN_HYBRID", request.normalizedPayload().get("executionMode"));
        assertEquals("CLOCK_DRIVEN_SIMULATION", request.normalizedPayload().get("runPath"));
        assertEquals("STUB_LLM", request.normalizedPayload().get("agentDecisionMode"));
        assertEquals("HOURLY", request.normalizedPayload().get("agentDecisionCadence"));
        assertEquals(60, request.normalizedPayload().get("agentDecisionIntervalMinutes"));
        assertTrue(request.normalizedPayload().containsKey("cohortArtifactRefs"));
        assertTrue(request.normalizedPayload().containsKey("cohortSelection"));
    }

    @Test
    void presetResolutionCreatesOneImmutableLaunchAnchorAndRejectsInvalidValues() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());
        long before = System.currentTimeMillis();

        BankingPolicyScenarioLabRequest initial = presetService.resolveRequest(
                Map.of("presetId", "saver-repricing"),
                "PLATFORM_ADMIN");
        long anchor = ((Number) initial.normalizedPayload().get("simulationStartEpochMs")).longValue();
        BankingPolicyScenarioLabRequest resumed = presetService.resolveRequest(
                initial.normalizedPayload(),
                "PLATFORM_ADMIN");

        assertTrue(anchor >= before && anchor <= System.currentTimeMillis());
        assertEquals(anchor, resumed.normalizedPayload().get("simulationStartEpochMs"));
        assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", 0),
                "PLATFORM_ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", 1.5),
                "PLATFORM_ADMIN"));

        assertEquals(1L, presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", 1.0d),
                "PLATFORM_ADMIN").normalizedPayload().get("simulationStartEpochMs"));
        assertEquals(1_000L, presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", new BigDecimal("1E3")),
                "PLATFORM_ADMIN").normalizedPayload().get("simulationStartEpochMs"));
        assertEquals(Long.MAX_VALUE, presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", Long.MAX_VALUE),
                "PLATFORM_ADMIN").normalizedPayload().get("simulationStartEpochMs"));
        assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", true),
                "PLATFORM_ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(
                Map.of("presetId", "saver-repricing", "simulationStartEpochMs", "1000"),
                "PLATFORM_ADMIN"));
        assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(
                Map.of(
                        "presetId", "saver-repricing",
                        "simulationStartEpochMs", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                "PLATFORM_ADMIN"));
    }

    @Test
    void providerRejectsInvalidPlatformOwnedRunIdBeforeCreatingRun() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(
                context,
                Map.of(
                        "presetId", "saver-repricing",
                        "executionMode", "CLOCK_DRIVEN_HYBRID",
                        "simulationRunId", "../not-a-run"),
                "PLATFORM_ADMIN"));
    }

    @Test
    void clockPauseRequiresExplicitReplayOwnership() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "clock-release-missing-owner-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.75, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "CANCEL", Map.of()));
    }

    @Test
    void clockDurationRequiresExplicitReplayOwnership() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/replay"),
                eq(String.class)))
                .thenReturn("{}");
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        Method method = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "clockDrivenSimulationWindow",
                PolicyWindTunnelExecutionContext.class,
                String.class,
                Map.class);
        method.setAccessible(true);

        MutableContext context = new MutableContext(workspaceRoot);
        assertRetiredWithoutEffects(context, restTemplate, () -> invokePure(provider,
                "clockDrivenSimulationWindow", context, "clock-duration-retired", Map.of("simulationHorizonDays", 1)));
    }

    @Test
    void clockDurationReportsUnverifiedWhenClockTimeIsUnavailable() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/replay"),
                eq(String.class)))
                .thenReturn("{\"executionContext\":{\"runId\":\"clock-duration-missing-time-test\"}}");
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/time"),
                eq(Long.class)))
                .thenReturn(null);
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        Method method = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "clockDrivenSimulationWindow",
                PolicyWindTunnelExecutionContext.class,
                String.class,
                Map.class);
        method.setAccessible(true);

        MutableContext context = new MutableContext(workspaceRoot);
        assertRetiredWithoutEffects(context, restTemplate, () -> invokePure(provider,
                "clockDrivenSimulationWindow", context, "clock-duration-retired", Map.of("simulationHorizonDays", 1)));
    }

    @Test
    void manualPlaybackControlsRequireExplicitReplayOwnership() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "manual-controls-missing-owner-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "PAUSE", Map.of()));
    }

    @Test
    void runningClockCanChangePlaybackRateWithoutUsingResumeTransition() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "running-playback-rate-change-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "playbackRate", 1800.0,
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context,
                runId,
                "SET_PLAYBACK_RATE",
                Map.of("playbackRate", 600.0)));
    }

    @Test
    void playbackRateChangeWithoutExplicitRatePreservesStoredRate() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "playback-rate-change-missing-rate-preservation-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "playbackRate", 600.0,
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "SET_PLAYBACK_RATE", Map.of()));
    }

    @Test
    void playbackRateChangeWithInvalidExplicitRatePreservesStoredRate() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "playback-rate-change-invalid-rate-preservation-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "playbackRate", 600.0,
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));

        List<Object> invalidRates = List.of(0, -10.0, "NaN", "not-a-number");
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        for (Object invalidRate : invalidRates) {
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(
                    context,
                    runId,
                    "SET_PLAYBACK_RATE",
                    Map.of("playbackRate", invalidRate)));
        }
    }

    @Test
    void pauseResumePreservesOperatorSelectedPlaybackRate() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "pause-resume-playback-rate-preservation-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "playbackRate", 1800.0,
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "SET_PLAYBACK_RATE", Map.of("playbackRate", 600.0)));
    }

    @Test
    void resumeDoesNotStartPlaybackDuringActivation() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "resume-during-activation-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("ACTIVATING_COHORT", 0.25, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "RESUME", Map.of("playbackRate", 2.0)));
    }

    @Test
    void pauseBeforeReplayOwnershipStillMarksRunPaused() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "pause-before-replay-owner-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("PREPARING_CLOCK", 0.05, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "PAUSE", Map.of()));
    }

    @Test
    void resumeFromPreparingPauseRequeuesSetupWorker() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                queuedWorker::set);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "resume-from-preparing-pause-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "cohortId", "banking-balanced-banking-100-test",
                "simulationHorizonDays", 1,
                "prePolicyDays", 0,
                "postPolicyDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("PREPARING_CLOCK", 0.05, false, null, null, null)
                .withLifecycle("PAUSED", 0.05, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "PAUSED",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "RESUME", Map.of()));
    }

    @Test
    void resumeFromActivationPauseRequeuesSetupWorkerFromCleanPreparingPhase() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                queuedWorker::set);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "resume-from-activation-pause-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "cohortId", "banking-balanced-banking-100-test",
                "simulationHorizonDays", 1,
                "prePolicyDays", 0,
                "postPolicyDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("PREPARING_CLOCK", 0.05, false, null, null, null)
                .withLifecycle("ACTIVATING_COHORT", 0.30, false, null, null, null)
                .withLifecycle("PAUSED", 0.30, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "PAUSED",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "RESUME", Map.of()));
    }

    @Test
    void resumedPreparingWorkerDoesNotStealReplayFromAnotherRun() throws Exception {
        writeGeneratedCohortFixture("banking-balanced-banking-100-test", 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                queuedWorker::set);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "resume-preparing-conflict-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "cohortId", "banking-balanced-banking-100-test",
                "simulationHorizonDays", 1,
                "prePolicyDays", 0,
                "postPolicyDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("PREPARING_CLOCK", 0.05, false, null, null, null)
                .withLifecycle("PAUSED", 0.05, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "PAUSED",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "RESUME", Map.of()));
    }

    @Test
    void resumedActivationWorkerRequiresExistingReplayOwnership() throws Exception {
        writeGeneratedCohortFixture("banking-balanced-banking-100-test", 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                queuedWorker::set);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "resume-activation-conflict-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "cohortId", "banking-balanced-banking-100-test",
                "simulationHorizonDays", 1,
                "prePolicyDays", 0,
                "postPolicyDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("PREPARING_CLOCK", 0.05, false, null, null, null)
                .withLifecycle("ACTIVATING_COHORT", 0.30, false, null, null, null)
                .withLifecycle("PAUSED", 0.30, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "PAUSED",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "RESUME", Map.of()));
    }

    @Test
    void presetResolutionRejectsUnboundedAgentDecisionCadence() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> presetService.resolveRequest(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "CUSTOM_INTERVAL",
                "agentDecisionIntervalMinutes", 1),
                "PLATFORM_ADMIN"));

        assertTrue(error.getMessage().contains(
                "agentDecisionCadence must be one of EVENT_ONLY, HOURLY, DAILY, WEEKLY"));
    }

    @Test
    void presetResolutionRecomputesAgentDecisionIntervalFromBoundedCadence() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());

        BankingPolicyScenarioLabRequest request = presetService.resolveRequest(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "WEEKLY",
                "agentDecisionIntervalMinutes", 1),
                "PLATFORM_ADMIN");

        assertEquals("WEEKLY", request.normalizedPayload().get("agentDecisionCadence"));
        assertEquals(7 * 24 * 60, request.normalizedPayload().get("agentDecisionIntervalMinutes"));
    }

    @Test
    void presetResolutionSupportsEventOnlyActivationWithoutCalendarInterval() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());

        BankingPolicyScenarioLabRequest request = presetService.resolveRequest(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "agentDecisionMode", "DETERMINISTIC",
                "agentDecisionCadence", "EVENT_ONLY"),
                "PLATFORM_ADMIN");

        assertEquals("EVENT_ONLY", request.normalizedPayload().get("agentDecisionCadence"));
        assertEquals(0, request.normalizedPayload().get("agentDecisionIntervalMinutes"));
    }

    @Test
    void presetResolutionPreservesPinnedOpportunityFinalizationMode() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());

        BankingPolicyScenarioLabRequest request = presetService.resolveRequest(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V1"),
                "PLATFORM_ADMIN");

        assertEquals(
                "PLATFORM_OWNED_V1",
                request.normalizedPayload().get("opportunityGenerationFinalizationMode"));
    }

    @Test
    void eventOnlyLaunchRequiresClockDrivenHybridAndExplicitV2Finalization() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        RemoteDomainProtocolException mode = assertThrows(RemoteDomainProtocolException.class, () ->
                provider.startRun(context, Map.of("executionMode", "FAST_BATCH_COMPARE", "agentDecisionCadence", "EVENT_ONLY"), "PLATFORM_ADMIN"));
        assertEquals("scenario_lab_execution_mode_retired", mode.code());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, Map.of(
                "executionMode", "CLOCK_DRIVEN_HYBRID", "agentDecisionCadence", "EVENT_ONLY",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"), "PLATFORM_ADMIN"));
    }

    @Test
    void presetResolutionAllowsTeamOperatorToSetSimulationRunLength() {
        BankingPolicyScenarioLabPresetService presetService =
                new BankingPolicyScenarioLabPresetService(new ObjectMapper());

        BankingPolicyScenarioLabRequest request = presetService.resolveRequest(Map.of(
                "presetId", "saver-repricing",
                "baselineRate", "0.03",
                "candidateRate", "0.08",
                "simulationHorizonDays", 7,
                "prePolicyDays", 2,
                "postPolicyDays", 5),
                "TEAM_OPERATOR");

        assertEquals("7", request.parameters().getSimulationHorizonDays());
        assertEquals("2", request.parameters().getPrePolicyDays());
        assertEquals("5", request.parameters().getPostPolicyDays());
        assertEquals("7", request.normalizedPayload().get("simulationHorizonDays"));
        assertEquals("2", request.normalizedPayload().get("prePolicyDays"));
        assertEquals("5", request.normalizedPayload().get("postPolicyDays"));
    }

    @Test
    void newProviderLaunchRejectsFastMissingAndUnknownModesBeforePersistenceOrExecution() {
        AtomicInteger executions = new AtomicInteger();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                command -> executions.incrementAndGet());
        MutableContext context = new MutableContext(workspaceRoot);

        for (Map<String, Object> payload : List.<Map<String, Object>>of(
                Map.of("presetId", "saver-repricing"),
                Map.of("presetId", "saver-repricing", "executionMode", "FAST_BATCH_COMPARE"),
                Map.of("presetId", "saver-repricing", "executionMode", "UNKNOWN"))) {
            RemoteDomainProtocolException rejected = assertThrows(
                    RemoteDomainProtocolException.class,
                    () -> provider.startRun(context, payload, "PLATFORM_ADMIN"));
            assertTrue(rejected.getMessage().contains("CLOCK_DRIVEN_HYBRID"));
            assertEquals(HttpStatus.BAD_REQUEST, rejected.status());
            assertEquals("scenario_lab_execution_mode_retired", rejected.code());
        }

        assertTrue(context.runs.isEmpty());
        assertTrue(context.registeredArtifacts.isEmpty());
        assertEquals(0, executions.get());
        assertFalse(Files.exists(workspaceRoot.resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)));
    }

    @Test
    void retainedFastBatchExecutorStillPersistsCurrentEvaluationEnvelope() throws Exception {
        writeSeededAgentsFixture("TS001");
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://banking.test/api/products"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).body(""));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("""
                        [
                          {
                            "agentId": "nina-saver",
                            "runtimeIdentity": "banking:nina-saver",
                            "domainId": "banking",
                            "persona": {
                              "personaName": "Nina Saver",
                              "traits": ["segment:saver"]
                            },
                            "state": {
                              "currentBalance": -500,
                              "creditUtilisation": 0
                            },
                            "accounts": [
                              {
                                "name": "nina-savings",
                                "productCode": "TS001",
                                "balance": -500,
                                "limit": 5000
                              }
                            ]
                          }
                        ]
                        """));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents/"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/runtime-state"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"idle\":true}"));
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.startsWith("http://simulator.test/api/clock/advance?seconds=")),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                objectMapper,
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "retained-fast-batch-characterization";
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("presetId", "saver-repricing"),
                Map.entry("executionMode", "FAST_BATCH_COMPARE"),
                Map.entry("runPath", "POLICY_ONLY_CHECK"),
                Map.entry("runMode", "DEMO_FAST"),
                Map.entry("compareMode", "SEEDED_DETERMINISTIC"),
                Map.entry("simulationHorizonDays", 7),
                Map.entry("prePolicyDays", 2),
                Map.entry("postPolicyDays", 5),
                Map.entry("productCode", "TS001"),
                Map.entry("baselineRate", 0.03),
                Map.entry("candidateRate", 0.08));
        BankingPolicyScenarioLabRequest resolved =
                new BankingPolicyScenarioLabPresetService(objectMapper)
                        .resolveRequest(payload, "PLATFORM_ADMIN");
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-saver-repricing",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "saver-repricing",
                "QUEUED",
                System.currentTimeMillis(),
                null,
                null,
                BankingPolicyScenarioLabRunMetadata.queued(resolved.normalizedPayload()).writeTo(Map.of())));

        Method executeRun = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "executeRun",
                PolicyWindTunnelExecutionContext.class,
                String.class,
                BankingPolicyScenarioLabRequest.class);
        executeRun.setAccessible(true);
        executeRun.invoke(provider, context, runId, resolved);

        assertEquals("FAILED", context.findRun(runId).orElseThrow().status());
        Path runRoot = workspaceRoot.resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT).resolve(runId);
        assertFalse(Files.exists(runRoot.resolve("evaluation/baseline-evaluation.json")));
        assertFalse(Files.exists(runRoot.resolve("evaluation/candidate-evaluation.json")));
        assertEquals(0, outboundHttpRequests.get());
        verify(restTemplate, atLeast(1)).exchange(eq("http://banking.test/api/products"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(restTemplate, never()).exchange(contains("/api/clock"), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        for (var invocation : org.mockito.Mockito.mockingDetails(restTemplate).getInvocations()) {
            assertEquals("exchange", invocation.getMethod().getName());
            assertEquals("http://banking.test/api/products", invocation.getArgument(0));
            assertEquals(HttpMethod.GET, invocation.getArgument(1));
        }
    }

    @Test
    void executionPlanFallsBackToDomainProductMetadataWhenProductEndpointIsUnavailable() throws Exception {
        writeSeededAgentsFixture();
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan = runtime.materializeExecutionPlan(
                new ScenarioLabRunParameters()
                        .presetId("credit-stress")
                        .productCode("CC001")
                        .baselineRate("0.19")
                        .candidateRate("0.24")
                        .candidateStimulusAmount("0")
                        .cadenceProfile("DEMO_FAST_FORK_FOCUSED"));

        assertEquals("CC001", executionPlan.productMetadata().productCode());
        assertEquals("Standard Credit Card", executionPlan.productMetadata().productName());
        assertEquals("CRED_AND_CHRG_CARDS", executionPlan.productMetadata().productCategory());
        assertEquals("ASSET", executionPlan.productMetadata().balanceSheetSide());
        assertEquals("banking", executionPlan.seededAgents().getFirst().get("domainId"));
        assertEquals(false, executionPlan.seededAgents().getFirst().get("autonomousDecisionEnabled"));
        assertFalse(executionPlan.seededAgents().getFirst().containsKey("actions"));
    }

    @Test
    void demoFastPlanUsesSparseInteractiveClockSteps() throws Exception {
        writeSeededAgentsFixture();
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan = runtime.materializeExecutionPlan(
                new ScenarioLabRunParameters()
                        .presetId("saver-repricing")
                        .productCode("TS001")
                        .baselineRate("0.03")
                        .candidateRate("0.08")
                        .candidateStimulusAmount("0")
                        .simulationHorizonDays("30")
                        .prePolicyDays("10")
                        .postPolicyDays("20")
                        .cadenceProfile("DEMO_FAST_FORK_FOCUSED"));

        List<BankingPolicyScenarioExecutionRuntime.BatchStep> batchSteps = executionPlan.batchSteps();
        assertEquals("D30", batchSteps.getLast().label());
        assertTrue(batchSteps.size() <= 36);
        assertTrue(batchSteps.stream().anyMatch(step -> step.label().equals("D10") && step.capture()));
        assertTrue(batchSteps.stream().anyMatch(step -> step.label().equals("D11") && step.capture()));
        assertEquals(4, batchSteps.stream().filter(BankingPolicyScenarioExecutionRuntime.BatchStep::capture).count());
        assertTrue(executionPlan.baselineEvents().stream()
                .allMatch(event -> "banking-policy-pack".equals(event.service())));
        assertTrue(executionPlan.candidateEvents().stream()
                .allMatch(event -> "banking-policy-pack".equals(event.service())));
        assertTrue(executionPlan.baselineEvents().stream()
                .map(BankingPolicyScenarioExecutionRuntime.ScenarioEvent::path)
                .noneMatch(path -> path.startsWith("/api/god/")));
        assertTrue(executionPlan.candidateEvents().stream()
                .map(BankingPolicyScenarioExecutionRuntime.ScenarioEvent::path)
                .noneMatch(path -> path.startsWith("/api/god/")));
    }

    @Test
    void executionPlanUsesGeneratedCohortWhenScenarioLabPayloadSelectsOne() throws Exception {
        String cohortId = "banking-balanced-banking-100-test";
        Path cohortDir = workspaceRoot.resolve("output/cohorts").resolve(cohortId);
        Files.createDirectories(cohortDir);
        Files.writeString(cohortDir.resolve("customers.json"), """
                [
                  {
                    "personaName": "Generated Customer One",
                    "domainId": "banking",
                    "model": "stub",
                    "interactionMode": "SIMULATION",
                    "autonomousDecisionEnabled": false,
                    "traits": ["synthetic-customer", "cohort-segment:everyday"],
                    "goals": [],
                    "initialAccounts": [
                      {
                        "name": "generated-one-card",
                        "productCode": "CC001",
                        "balance": -250,
                        "limit": 2000
                      }
                    ]
                  },
                  {
                    "personaName": "Generated Customer Two",
                    "domainId": "banking",
                    "model": "stub",
                    "interactionMode": "SIMULATION",
                    "autonomousDecisionEnabled": false,
                    "traits": ["synthetic-customer", "cohort-segment:hardship"],
                    "goals": [],
                    "initialAccounts": [
                      {
                        "name": "generated-two-saver",
                        "productCode": "TS001",
                        "balance": 120
                      }
                    ]
                  }
                ]
                """);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan = runtime.materializeExecutionPlan(
                new ScenarioLabRunParameters()
                        .presetId("credit-stress")
                        .productCode("CC001")
                        .baselineRate("0.19")
                        .candidateRate("0.24")
                        .candidateStimulusAmount("0")
                        .cadenceProfile("DEMO_FAST_FORK_FOCUSED"),
                Map.of(
                        "cohortId", cohortId,
                        "cohortSelection", Map.of("cohortId", cohortId)));

        assertEquals(2, executionPlan.seededAgents().size());
        assertEquals("Generated Customer One", executionPlan.seededAgents().getFirst().get("personaName"));
        assertEquals("stub", executionPlan.seededAgents().getFirst().get("model"));
        assertEquals("SIMULATION", executionPlan.seededAgents().getFirst().get("interactionMode"));
        assertEquals(false, executionPlan.seededAgents().getFirst().get("autonomousDecisionEnabled"));
        assertEquals("banking", executionPlan.seededAgents().getFirst().get("domainId"));
        assertTrue(((List<?>) executionPlan.seededAgents().getFirst().get("initialAccounts"))
                .stream()
                .allMatch(account -> ((Map<?, ?>) account).containsKey("currency")));
    }

    @Test
    void fastCompareRejectsGeneratedCohortThatRequiresStreamingRunner() throws Exception {
        String cohortId = "banking-launch-demo-1000-test";
        Path cohortDir = workspaceRoot.resolve("output/cohorts").resolve(cohortId);
        Files.createDirectories(cohortDir);
        StringBuilder customers = new StringBuilder("[");
        for (int index = 0; index < 251; index++) {
            if (index > 0) {
                customers.append(',');
            }
            customers.append("""
                    {
                      "personaName": "Generated Customer %d",
                      "domainId": "banking",
                      "model": "stub",
                      "interactionMode": "SIMULATION",
                      "autonomousDecisionEnabled": false,
                      "goals": [],
                      "initialAccounts": [
                        {
                          "name": "generated-account-%d",
                          "productCode": "TS001",
                          "balance": 120
                        }
                      ]
                    }
                    """.formatted(index, index));
        }
        customers.append(']');
        Files.writeString(cohortDir.resolve("customers.json"), customers.toString());
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runtime.materializeExecutionPlan(
                        new ScenarioLabRunParameters()
                                .presetId("saver-repricing")
                                .productCode("TS001")
                                .baselineRate("0.03")
                                .candidateRate("0.08")
                                .candidateStimulusAmount("0")
                                .cadenceProfile("DEMO_FAST_FORK_FOCUSED"),
                        Map.of("cohortId", cohortId)));

        assertTrue(error.getMessage().contains("supports at most 250"));
        assertTrue(error.getMessage().contains("Clock-Driven Simulation limit"));
    }

    @Test
    void stubLlmDecisionModeNormalizesOneHundredGeneratedCustomersForNoTokenRuntime() throws Exception {
        String cohortId = "banking-balanced-banking-100-stub-test";
        writeGeneratedCohortFixture(cohortId, 100);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        List<Map<String, Object>> agents = runtime.loadGeneratedCohortAgentsForStreaming(cohortId, "STUB_LLM");

        assertEquals(100, agents.size());
        assertTrue(agents.stream().allMatch(agent -> "stub".equals(agent.get("provider"))));
        assertTrue(agents.stream().allMatch(agent -> "stub".equals(agent.get("model"))));
        assertTrue(agents.stream().allMatch(agent -> "HYBRID".equals(agent.get("interactionMode"))));
        assertTrue(agents.stream().allMatch(agent -> Boolean.TRUE.equals(agent.get("autonomousDecisionEnabled"))));
        assertTrue(agents.stream().allMatch(agent -> "DAILY".equals(agent.get("autonomousDecisionCadence"))));
        assertTrue(agents.stream().allMatch(agent -> Integer.valueOf(24 * 60).equals(agent.get("autonomousDecisionIntervalMinutes"))));
        assertTrue(agents.stream().noneMatch(agent -> agent.containsKey("agentDecisionMode")));
        assertTrue(agents.stream().allMatch(agent ->
                ((List<?>) agent.get("traits")).contains("scenario-lab:hybrid-eligible")));
        assertTrue(agents.stream().allMatch(agent ->
                ((List<?>) agent.get("traits")).contains("agent-decision-mode:stub-llm")));
    }

    @Test
    void stubLlmDecisionModeHonorsRequestedDecisionCadence() throws Exception {
        String cohortId = "banking-balanced-banking-100-hourly-cadence-test";
        writeGeneratedCohortFixture(cohortId, 2);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        List<Map<String, Object>> agents = runtime.loadGeneratedCohortAgentsForStreaming(
                cohortId,
                "STUB_LLM",
                Map.of("agentDecisionCadence", "HOURLY"));

        assertEquals(2, agents.size());
        assertTrue(agents.stream().allMatch(agent -> "HOURLY".equals(agent.get("autonomousDecisionCadence"))));
        assertTrue(agents.stream().allMatch(agent -> Integer.valueOf(60).equals(agent.get("autonomousDecisionIntervalMinutes"))));
    }

    @Test
    void eventOnlyDecisionModeActivatesWithoutCalendarDecisionSlots() throws Exception {
        String cohortId = "banking-balanced-banking-100-event-only-cadence-test";
        writeGeneratedCohortFixture(cohortId, 2);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        List<Map<String, Object>> agents = runtime.loadGeneratedCohortAgentsForStreaming(
                cohortId,
                "STUB_LLM",
                Map.of("agentDecisionCadence", "EVENT_ONLY"));

        assertEquals(2, agents.size());
        assertTrue(agents.stream().allMatch(agent -> "EVENT_ONLY".equals(agent.get("autonomousDecisionCadence"))));
        assertTrue(agents.stream().allMatch(agent -> Integer.valueOf(0).equals(agent.get("autonomousDecisionIntervalMinutes"))));
    }

    @Test
    void realLlmDecisionModeNormalizesBudgetedCanaryAgents() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-canary-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        List<Map<String, Object>> agents = runtime.loadGeneratedCohortAgentsForStreaming(
                cohortId,
                "REAL_LLM",
                Map.of(
                        "agentDecisionCadence", "DAILY",
                        "realLlmBudget", Map.of(
                                "provider", "openai",
                                "model", "gemini-2.5-flash-lite",
                                "maxCalls", 70,
                                "maxUsd", 0.50,
                                "maxInputTokens", 150_000,
                                "maxOutputTokens", 25_000)));

        assertEquals(10, agents.size());
        assertTrue(agents.stream().allMatch(agent -> "openai".equals(agent.get("provider"))));
        assertTrue(agents.stream().allMatch(agent -> "gemini-2.5-flash-lite".equals(agent.get("model"))));
        assertTrue(agents.stream().allMatch(agent -> "HYBRID".equals(agent.get("interactionMode"))));
        assertTrue(agents.stream().allMatch(agent -> Boolean.TRUE.equals(agent.get("autonomousDecisionEnabled"))));
        assertTrue(agents.stream().allMatch(agent -> Integer.valueOf(70).equals(agent.get("realLlmMaxDecisionCalls"))));
        assertTrue(agents.stream().allMatch(agent ->
                "gemini-2.5-flash-lite".equals(((Map<?, ?>) agent.get("realLlmBudget")).get("model"))));
        assertTrue(agents.stream().allMatch(agent ->
                "STOP_AT_BUDGET".equals(((Map<?, ?>) agent.get("realLlmBudget")).get("stopPolicy"))));
        assertTrue(agents.stream().allMatch(agent ->
                ((List<?>) agent.get("traits")).contains("agent-decision-mode:real-llm-canary")));
    }

    @Test
    void realLlmDecisionModeRejectsNonCanaryModel() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-wrong-model-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId,
                        "REAL_LLM",
                        Map.of(
                                "agentDecisionCadence", "DAILY",
                                "realLlmBudget", Map.of(
                                        "provider", "openai",
                                        "model", "gpt-4.1-mini",
                                        "maxCalls", 30,
                                        "maxUsd", 0.50,
                                        "maxInputTokens", 150_000,
                                        "maxOutputTokens", 25_000))));

        assertTrue(
                error.getMessage().contains("REAL_LLM canary runs are restricted to openai/gemini-2.5-flash-lite"),
                error.getMessage());
    }

    @Test
    void realLlmDecisionModeRejectsOversizedBudgetDuringAgentNormalization() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-budget-overage-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId,
                        "REAL_LLM",
                        Map.of(
                                "agentDecisionCadence", "DAILY",
                                "realLlmBudget", Map.of(
                                        "provider", "openai",
                                        "model", "gemini-2.5-flash-lite",
                                        "maxCalls", 500,
                                        "maxUsd", 99.0,
                                        "maxInputTokens", 150_000,
                                        "maxOutputTokens", 25_000))));

        assertTrue(
                error.getMessage().contains("REAL_LLM canary maxCalls must be between 1 and 70"),
                error.getMessage());
    }

    @Test
    void realLlmDecisionModeRejectsMissingBudgetDuringAgentNormalization() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-missing-budget-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId,
                        "REAL_LLM",
                        Map.of("agentDecisionCadence", "DAILY")));

        assertTrue(
                error.getMessage().contains("REAL_LLM canary budget provider is required"),
                error.getMessage());
    }

    @Test
    void realLlmDecisionModeRejectsMalformedBudgetDuringAgentNormalization() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-malformed-budget-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId,
                        "REAL_LLM",
                        Map.of(
                                "agentDecisionCadence", "DAILY",
                                "realLlmBudget", Map.of(
                                        "provider", "openai",
                                        "model", "gemini-2.5-flash-lite",
                                        "maxCalls", "garbage",
                                        "maxUsd", 0.50,
                                        "maxInputTokens", 150_000,
                                        "maxOutputTokens", 25_000))));

        assertTrue(
                error.getMessage().contains("REAL_LLM canary budget maxCalls must be an integer"),
                error.getMessage());
    }

    @Test
    void realLlmDecisionModeRejectsWrappedIntegerBudgetDuringAgentNormalization() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-wrapped-budget-test";
        writeGeneratedCohortFixture(cohortId, 10);
        BankingPolicyScenarioExecutionRuntime runtime = runtimeWithUnavailableProductEndpoint();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtime.loadGeneratedCohortAgentsForStreaming(
                        cohortId,
                        "REAL_LLM",
                        Map.of(
                                "agentDecisionCadence", "DAILY",
                                "realLlmBudget", Map.of(
                                        "provider", "openai",
                                        "model", "gemini-2.5-flash-lite",
                                        "maxCalls", 4_294_967_346L,
                                        "maxUsd", 0.50,
                                        "maxInputTokens", 150_000,
                                        "maxOutputTokens", 25_000))));

        assertTrue(
                error.getMessage().contains("REAL_LLM canary budget maxCalls must be an integer within 32-bit range"),
                error.getMessage());
    }

    @Test
    void realLlmClockDrivenRunRejectsFullScaleCohort() throws Exception {
        String cohortId = "banking-balanced-banking-100-real-llm-rejected-test";
        writeGeneratedCohortFixture(cohortId, 100);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        Map<String, Object> originalPayload = withPinnedActivationSettings(Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "REAL_LLM"),
                        Map.entry("agentDecisionCadence", "DAILY"),
                        Map.entry("simulationHorizonDays", 3),
                        Map.entry("prePolicyDays", 1),
                        Map.entry("postPolicyDays", 2),
                        Map.entry("realLlmOptIn", true),
                        Map.entry("realLlmBudget", Map.of(
                                "provider", "openai",
                                "model", "gemini-2.5-flash-lite",
                                "maxCalls", 50,
                                "maxUsd", 1.0,
                                "maxInputTokens", 150_000,
                                "maxOutputTokens", 25_000)),
                        Map.entry("cohortId", cohortId),
                        Map.entry("cohortSelection", Map.of("cohortId", cohortId))));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () ->
                invokePure(provider, "enforceRealLlmCanary", originalPayload, "REAL_LLM", 100));
        assertTrue(invalid.getMessage().contains("REAL_LLM canary runs are capped at 10 customers"), invalid.getMessage());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, originalPayload, "PLATFORM_ADMIN"));
    }

    @Test
    void realLlmClockDrivenRunAllowsMonthlyDailyTenCustomerCanaryWithinBudget() throws Exception {
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                mock(RestTemplate.class),
                Runnable::run);
        Method method = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "enforceRealLlmCanary",
                Map.class,
                String.class,
                int.class);
        method.setAccessible(true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("simulationHorizonDays", 30);
        payload.put("agentDecisionCadence", "DAILY");
        payload.put("realLlmOptIn", true);
        payload.put("realLlmBudget", Map.of(
                "provider", "openai",
                "model", "gemini-2.5-flash-lite",
                "maxCalls", 70,
                "maxUsd", 1.0,
                "maxInputTokens", 150_000,
                "maxOutputTokens", 25_000));

        method.invoke(provider, payload, "REAL_LLM", 10);

        Map<String, Object> oversizedPayload = new LinkedHashMap<>(payload);
        oversizedPayload.put("simulationHorizonDays", 31);
        ReflectiveOperationException error = assertThrows(
                ReflectiveOperationException.class,
                () -> method.invoke(provider, oversizedPayload, "REAL_LLM", 10));

        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(
                error.getCause().getMessage().contains("REAL_LLM canary runs are capped at 30 simulated days"),
                error.getCause().getMessage());
    }

    @Test
    void realLlmFinalizationIncludesFullCustomerDecisionRows() throws Exception {
        assertFinalizationIncludesFullCustomerDecisionRows(
                "real-llm-full-audit-test",
                "REAL_LLM",
                "DAILY");
    }

    @Test
    void eventOnlyStubFinalizationIncludesFullCustomerDecisionRows() throws Exception {
        assertFinalizationIncludesFullCustomerDecisionRows(
                "event-only-stub-full-audit-test",
                "STUB_LLM",
                "EVENT_ONLY");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertFinalizationIncludesFullCustomerDecisionRows(
            String runId,
            String decisionMode,
            String decisionCadence) throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) invokePure(provider, "buildDecisionAuditPayload",
                realLlmHybridDecisionResponse().getBody(), "stored-fixture");
        List<Map<String, Object>> agents = (List<Map<String, Object>>) audit.get("agents");
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) agents.getFirst().get("decisions");
        assertEquals(2, decisions.size());
        assertEquals("I will wait today.", decisions.getFirst().get("customerVoice"));
        assertEquals("stored-fixture", audit.get("source"));
        assertFalse(audit.containsKey("runtimeTickDrain"));
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void realLlmClockDrivenRunRejectsOversizedBudgetBeforeActivation() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-run-budget-overage-test";
        writeGeneratedCohortFixture(cohortId, 10);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        Map<String, Object> originalPayload = withPinnedActivationSettings(Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "REAL_LLM"),
                        Map.entry("agentDecisionCadence", "DAILY"),
                        Map.entry("simulationHorizonDays", 3),
                        Map.entry("prePolicyDays", 1),
                        Map.entry("postPolicyDays", 2),
                        Map.entry("realLlmOptIn", true),
                        Map.entry("realLlmBudget", Map.of(
                                "provider", "openai",
                                "model", "gemini-2.5-flash-lite",
                                "maxCalls", 500,
                                "maxUsd", 1.0,
                                "maxInputTokens", 150_000,
                                "maxOutputTokens", 25_000)),
                        Map.entry("cohortId", cohortId),
                        Map.entry("cohortSelection", Map.of("cohortId", cohortId))));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () ->
                invokePure(provider, "enforceRealLlmCanary", originalPayload, "REAL_LLM", 2));
        assertTrue(invalid.getMessage().contains("REAL_LLM canary maxCalls must be between 1 and 70"), invalid.getMessage());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, originalPayload, "PLATFORM_ADMIN"));
    }

    @Test
    void realLlmClockDrivenRunRejectsWrappedIntegerBudgetBeforeActivation() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-run-wrapped-budget-test";
        writeGeneratedCohortFixture(cohortId, 10);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        Map<String, Object> originalPayload = withPinnedActivationSettings(Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "REAL_LLM"),
                        Map.entry("agentDecisionCadence", "DAILY"),
                        Map.entry("simulationHorizonDays", 3),
                        Map.entry("prePolicyDays", 1),
                        Map.entry("postPolicyDays", 2),
                        Map.entry("realLlmOptIn", true),
                        Map.entry("realLlmBudget", Map.of(
                                "provider", "openai",
                                "model", "gemini-2.5-flash-lite",
                                "maxCalls", 4_294_967_346L,
                                "maxUsd", 1.0,
                                "maxInputTokens", 150_000,
                                "maxOutputTokens", 25_000)),
                        Map.entry("cohortId", cohortId),
                        Map.entry("cohortSelection", Map.of("cohortId", cohortId))));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () ->
                invokePure(provider, "enforceRealLlmCanary", originalPayload, "REAL_LLM", 2));
        assertTrue(invalid.getMessage().contains("REAL_LLM canary budget maxCalls must be an integer within 32-bit range"), invalid.getMessage());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, originalPayload, "PLATFORM_ADMIN"));
    }

    @Test
    void realLlmClockDrivenRunRejectsMalformedBudgetBeforeActivation() throws Exception {
        String cohortId = "banking-balanced-banking-10-real-llm-run-malformed-budget-test";
        writeGeneratedCohortFixture(cohortId, 10);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        Map<String, Object> originalPayload = withPinnedActivationSettings(Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "REAL_LLM"),
                        Map.entry("agentDecisionCadence", "DAILY"),
                        Map.entry("simulationHorizonDays", 3),
                        Map.entry("prePolicyDays", 1),
                        Map.entry("postPolicyDays", 2),
                        Map.entry("realLlmOptIn", true),
                        Map.entry("realLlmBudget", Map.of(
                                "provider", "openai",
                                "model", "gemini-2.5-flash-lite",
                                "maxCalls", 50,
                                "maxUsd", Double.NaN,
                                "maxInputTokens", 150_000,
                                "maxOutputTokens", 25_000)),
                        Map.entry("cohortId", cohortId),
                        Map.entry("cohortSelection", Map.of("cohortId", cohortId))));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () ->
                invokePure(provider, "enforceRealLlmCanary", originalPayload, "REAL_LLM", 2));
        assertTrue(invalid.getMessage().contains("REAL_LLM canary budget maxUsd must be a finite number"), invalid.getMessage());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, originalPayload, "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void platformOwnedDailyActivationUsesPinnedBytesAfterMutableCohortIsReplaced() throws Exception {
        String runId = "policy-wt-pinned-cohort-runtime-test";
        String cohortId = "banking-pinned-cohort-runtime-test";
        writeGeneratedCohortFixture(cohortId, 2);
        Path mutableCustomers = workspaceRoot.resolve("output/cohorts")
                .resolve(cohortId)
                .resolve("customers.json");
        byte[] pinnedBytes = Files.readAllBytes(mutableCustomers);
        String artifactDigest = sha256(pinnedBytes);
        String identityDigest = sha256(new ObjectMapper().writeValueAsBytes(
                List.of("Generated Customer 0", "Generated Customer 1")));
        Map<String, Object> snapshotEnvelope = Map.of(
                "schemaVersion", "scenario_lab_cohort_artifact_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64",
                "sha256", artifactDigest,
                "content", Base64.getEncoder().encodeToString(pinnedBytes));
        Map<String, Object> activationSettings = new LinkedHashMap<>();
        activationSettings.put("schemaVersion", "scenario_lab_pinned_cohort_activation.v1");
        activationSettings.put("agentDecisionMode", "DETERMINISTIC");
        activationSettings.put("agentDecisionCadence", "DAILY");
        byte[] activationSettingsBytes = new ObjectMapper().writeValueAsBytes(activationSettings);
        String activationSettingsDigest = sha256(activationSettingsBytes);
        Map<String, Object> activationSettingsEnvelope = Map.of(
                "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64url",
                "sha256", activationSettingsDigest,
                "content", Base64.getUrlEncoder().withoutPadding().encodeToString(activationSettingsBytes));

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init/pinned-cohort"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        AtomicReference<Runnable> queuedWorker = new AtomicReference<>();
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                queuedWorker::set);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("simulationRunId", runId),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "DETERMINISTIC"),
                        Map.entry("agentDecisionCadence", "DAILY"),
                        Map.entry("simulationStartEpochMs", DEFAULT_SIMULATION_START_EPOCH_MS),
                        Map.entry("simulationHorizonDays", 30),
                        Map.entry("cohortId", cohortId),
                        Map.entry("activationId", "platform-activation-opaque-123"),
                        Map.entry("cohortTargetSize", 2),
                        Map.entry("cohortArtifactDigest", artifactDigest),
                        Map.entry("cohortAgentIdentitySetDigest", identityDigest),
                        Map.entry("cohortArtifactSnapshot", snapshotEnvelope),
                        Map.entry("activationSettingsDigest", activationSettingsDigest),
                        Map.entry("activationSettingsSnapshot", activationSettingsEnvelope)),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridActivatesProvenGeneratedCohortThroughRuntimeChunks() throws Exception {
        String cohortId = "banking-balanced-banking-100-runtime-test";
        writeGeneratedCohortFixture(cohortId, 100);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(100, 72, 720, 480, 240));
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://banking.test/policy-wind-tunnel/pack"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("outcomeScorecards", List.of(contractScorecard()))));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.ofEntries(
                Map.entry("presetId", "saver-repricing"),
                Map.entry("simulationRunId", "policy-wt-platform-owned-runtime-test"),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "HOURLY"),
                Map.entry("agentDecisionIntervalMinutes", 60),
                Map.entry("simulationStartEpochMs", DEFAULT_SIMULATION_START_EPOCH_MS),
                Map.entry("cohortId", cohortId),
                Map.entry("cohortSelection", Map.of("cohortId", cohortId)))),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void creditAprLiftPinsAndInstallsDayTenPlanBeforeStartingClock() throws Exception {
        String runId = "policy-wt-rate-change-installation-test";
        String cohortId = "banking-rate-change-installation-test";
        writeGeneratedRateChangeCohortFixture(cohortId);
        byte[] cohortBytes = Files.readAllBytes(
                workspaceRoot.resolve("output/cohorts").resolve(cohortId).resolve("customers.json"));
        String cohortArtifactDigest = sha256(cohortBytes);
        String cohortIdentitySetDigest = BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                List.of("Generated Customer 0", "Generated Customer 1"));
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate restTemplate = mock(RestTemplate.class);
        Path runRoot = workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId);
        Path installationPath = runRoot.resolve("clock-driven-rate-change-installation.json");
        AtomicBoolean installationAcknowledged = new AtomicBoolean();
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenAnswer(invocation -> {
                    Map<?, ?> body = (Map<?, ?>) ((HttpEntity<?>) invocation.getArgument(2)).getBody();
                    if (runId.equals(body.get("expectedRunId"))) {
                        assertTrue(installationAcknowledged.get());
                        assertTrue(Files.isRegularFile(installationPath));
                    }
                    return ResponseEntity.ok("{}");
                });
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init/pinned-cohort"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/domain-control-actions/execute"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> body = (Map<String, Object>)
                            ((HttpEntity<?>) invocation.getArgument(2)).getBody();
                    Map<String, Object> inputs = (Map<String, Object>) body.get("inputs");
                    String operationId = String.valueOf(body.get("operationId"));
                    String planDigest = String.valueOf(inputs.get("planDigest"));
                    Map<String, Object> response = Map.of(
                            "simulationRunId", runId,
                            "actionId", BankingClockDrivenRateChangePlan.ACTION_ID,
                            "operationId", operationId,
                            "matchedAgents", 2,
                            "succeeded", 2,
                            "failed", 0,
                            "results", List.of(
                                    Map.of(
                                            "agentId", "Generated Customer 0",
                                            "cohortAgentId", "Generated Customer 0",
                                            "providerRuntimeAgentId", "provider-customer-0",
                                            "opportunityAgentId", "opportunity-customer-0",
                                            "controlAction", Map.of(
                                                    "actionId", BankingClockDrivenRateChangePlan.ACTION_ID,
                                                    "operationId", operationId,
                                                    "outcome", "PLAN_INSTALLED",
                                                    "planDigest", planDigest,
                                                    "eligibleAccountCount", 1)),
                                    Map.of(
                                            "agentId", "Generated Customer 1",
                                            "cohortAgentId", "Generated Customer 1",
                                            "providerRuntimeAgentId", "provider-customer-1",
                                            "opportunityAgentId", "opportunity-customer-1",
                                            "controlAction", Map.of(
                                                    "actionId", BankingClockDrivenRateChangePlan.ACTION_ID,
                                                    "operationId", operationId,
                                                    "outcome", "PLAN_INSTALLED",
                                                    "planDigest", planDigest,
                                                    "eligibleAccountCount", 0))));
                    ResponseEntity<String> result = ResponseEntity.ok(mapper.writeValueAsString(response));
                    installationAcknowledged.set(true);
                    return result;
                });

        PolicyWindTunnelExecutionProvider provider = provider(
                mapper,
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(mapper, workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(
                context,
                withPinnedActivationSettings(Map.ofEntries(
                        Map.entry("presetId", "credit-apr-lift"),
                        Map.entry("simulationRunId", runId),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "STUB_LLM"),
                        Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                        Map.entry("simulationStartEpochMs", DEFAULT_SIMULATION_START_EPOCH_MS),
                        Map.entry("simulationHorizonDays", 30),
                        Map.entry("prePolicyDays", 10),
                        Map.entry("postPolicyDays", 20),
                        Map.entry("cohortId", cohortId),
                        Map.entry("cohortSelection", Map.of("cohortId", cohortId)),
                        Map.entry("activationId", runId + "-activation"),
                        Map.entry("cohortArtifactDigest", cohortArtifactDigest),
                        Map.entry("cohortAgentIdentitySetDigest", cohortIdentitySetDigest),
                        Map.entry("cohortArtifactSnapshot", Map.of(
                                "schemaVersion", "scenario_lab_cohort_artifact_snapshot.v1",
                                "contentType", "application/json",
                                "encoding", "base64",
                                "sha256", cohortArtifactDigest,
                                "content", Base64.getEncoder().encodeToString(cohortBytes))))),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retiredRateChangeInstallationRejectsBeforeAnyCustomerMutation() throws Exception {
        String runId = "policy-wt-rate-change-substituted-customer";
        ObjectMapper mapper = new ObjectMapper();
        BankingClockDrivenRateChangePlan.Snapshot plan = BankingClockDrivenRateChangePlan.compileForLaunch(
                        runId,
                        "credit-apr-lift",
                        Map.of(
                                "productCode", "CC001",
                                "baselineRate", "0.19",
                                "candidateRate", "0.24",
                                "prePolicyDays", 10,
                                "simulationStartEpochMs", DEFAULT_SIMULATION_START_EPOCH_MS))
                .orElseThrow();
        String operationId = BankingClockDrivenRateChangePlan.installOperationId(plan);
        RestTemplate restTemplate = mock(RestTemplate.class);
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                mapper,
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(mapper, workspaceRoot);
        Set<String> expectedAgentIds = Set.of("customer-a", "customer-b");
        Method install = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "installClockDrivenRateChangePlan",
                PolicyWindTunnelExecutionContext.class,
                String.class,
                BankingClockDrivenRateChangePlan.Snapshot.class,
                Set.class,
                String.class,
                String.class);
        install.setAccessible(true);

        Exception failure = assertThrows(Exception.class, () -> install.invoke(
                provider,
                context,
                runId,
                plan,
                expectedAgentIds,
                BankingClockDrivenRateChangePlan.agentIdentitySetDigest(List.copyOf(expectedAgentIds)),
                "f".repeat(64)));

        assertTrue(failure.getCause() instanceof RemoteDomainProtocolException);
        assertTrue(failure.getCause().getMessage().contains("Native Scenario Lab execution is not available"));
        verifyNoInteractions(restTemplate);
        assertFalse(Files.exists(workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId)
                .resolve("clock-driven-rate-change-installation.json")));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridDoesNotOverwritePauseBeforeFirstActivationTransition() throws Exception {
        String cohortId = "banking-balanced-banking-100-pre-activation-paused-test";
        String runId = "pre-activation-paused-run";
        writeGeneratedCohortFixture(cohortId, 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        AtomicInteger replayPosts = new AtomicInteger();
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenAnswer(invocation -> {
                    if (replayPosts.incrementAndGet() == 1) {
                        PolicyWindTunnelStoredRun storedRun = context.findRun(runId).orElseThrow();
                        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata.fromMetadata(storedRun.metadata())
                                .withLifecycle("PAUSED", 0.05, false, null, null, null)
                                .writeTo(storedRun.metadata());
                        context.updateRun(runId, new PolicyWindTunnelRunUpdate("PAUSED", null, null, null, metadata));
                    }
                    return ResponseEntity.ok("{}");
                });
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "simulationRunId", runId,
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "HOURLY",
                "agentDecisionIntervalMinutes", 60,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    void expiredReplayRecoversActivatingPhaseWithoutRepeatingCohortActivation() throws Exception {
        String cohortId = "banking-balanced-banking-100-expired-activation-recovery";
        String runId = "expired-activation-recovery";
        writeGeneratedCohortFixture(cohortId, 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        AtomicLong admissionTime = new AtomicLong(System.currentTimeMillis());
        List<Runnable> scheduled = new ArrayList<>();
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(),
                        restTemplate,
                        scheduled::add,
                        admissionTime::get);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        Map<String, Object> request = withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "simulationRunId", runId,
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "EVENT_ONLY",
                "simulationHorizonDays", 30,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId)));
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, request, "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridInitialStartDoesNotStealReplayFromAnotherRun() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        String cohortId = "banking-balanced-banking-100-owned-clock-test";
        writeGeneratedCohortFixture(cohortId, 2);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "HOURLY",
                "agentDecisionIntervalMinutes", 60,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridWorkerFailureDoesNotOverwriteCancelledRun() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-worker-cancelled";
        createTerminalRun(context, runId, "CANCELLED", "PLATFORM_OWNED_V2");
        invokeClockDrivenWorkerFailure(provider, context, runId, new IllegalStateException("late historical failure"));
        assertEquals("CANCELLED", context.findRun(runId).orElseThrow().status());
        assertTrue(provider.getRunStatus(context, runId).logTail().stream().anyMatch(line -> line.contains("preserving current run state")));
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridWorkerFailureDoesNotOverwritePausedRun() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-worker-paused";
        createTerminalRun(context, runId, "PAUSED", "PLATFORM_OWNED_V2");
        invokeClockDrivenWorkerFailure(provider, context, runId, new IllegalStateException("late historical failure"));
        assertEquals("PAUSED", context.findRun(runId).orElseThrow().status());
        assertTrue(provider.getRunStatus(context, runId).logTail().stream().anyMatch(line -> line.contains("preserving current run state")));
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridStartUsesReplayOwnershipCasAfterActivation() throws Exception {
        String cohortId = "banking-balanced-banking-100-start-cas-test";
        writeGeneratedCohortFixture(cohortId, 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "HOURLY",
                "agentDecisionIntervalMinutes", 60,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridStartDoesNotOverridePauseDuringActivation() throws Exception {
        String cohortId = "banking-balanced-banking-100-start-paused-test";
        String runId = "pause-during-activation-run";
        writeGeneratedCohortFixture(cohortId, 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "simulationRunId", runId,
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "HOURLY",
                "agentDecisionIntervalMinutes", 60,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridRejectsOversizedGeneratedCohortBeforeActivation() throws Exception {
        String cohortId = "banking-hardship-pressure-250-runtime-test";
        writeGeneratedCohortFixture(cohortId, 250);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    void lifecycleActionRechecksTerminalStateAfterWaitingForLock() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "terminal-recheck-after-lock-wait-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "simulationHorizonDays", 1));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.55, true, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));

        Object lock = lifecycleLock(provider, runId);
        AtomicReference<PolicyWindTunnelRunRecord> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "CANCEL", Map.of()));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridFinalizationWaitsForDecisionAuditToSettle() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                startsWith("http://experiment-service:7457/api/simulation-runs/clock-driven-settle-test"),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("runId", "clock-driven-settle-test")));
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(
                        hybridDecisionResponse(2, 0, 0, 0),
                        hybridDecisionResponse(2, 0, 0, 0),
                        hybridDecisionResponse(2, 0, 0, 0),
                        hybridDecisionResponse(2, 0, 0, 0),
                        hybridDecisionResponse(2, 3, 0, 3),
                        hybridDecisionResponse(2, 3, 0, 3),
                        hybridDecisionResponse(2, 3, 0, 3),
                        hybridDecisionResponse(2, 3, 0, 3),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2),
                        opportunityHybridDecisionResponse("clock-driven-settle-test", 2));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/runtime-state?simulationRunId=clock-driven-settle-test"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(
                        runtimeStateResponse(1, 1, 0),
                        runtimeStateResponse(0, 0, 2),
                        runtimeStateResponse(0, 0, 0));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "clock-driven-settle-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-settle-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        stubReconciledOpportunityReconciliation(restTemplate, runId, 2);

        Map<String, Object> finalizationRequest = finalizationRequest(runId);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context, runId, "FINALIZE", finalizationRequest));
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalizationReceiptResumesPartialPublicationAndSurvivesProcessRestart() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var first = provider(new ObjectMapper(), restTemplate, task -> {});
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-receipt-restart";
        seedStoredReceipt(first, context, runId, "PREPARED", "COMPLETED");
        byte[] frozenPrepared = Files.readAllBytes(finalizationReceiptPath(runId));
        MutableContext unregisteredRestart = new MutableContext(workspaceRoot);
        var restarted = provider(new ObjectMapper(), restTemplate, task -> {});
        assertRetiredWithoutEffects(unregisteredRestart, restTemplate, () -> restarted.controlRun(unregisteredRestart, runId, "FINALIZE", finalizationRequest(runId)));
        assertTrue(unregisteredRestart.findRun(runId).isEmpty());
        assertTrue(java.util.Arrays.equals(frozenPrepared, Files.readAllBytes(finalizationReceiptPath(runId))));
        Map<String, Object> published = seedStoredReceipt(first, context, runId, "PUBLISHED", "COMPLETED");
        directExperimentStatus = 200;
        directExperimentBody = Map.of("runId", runId);
        PolicyWindTunnelRunRecord restored = restarted.controlRun(unregisteredRestart, runId, "FINALIZE", finalizationRequest(runId));
        assertEquals("COMPLETED", restored.status());
        assertEquals(((Map<?, ?>) published.get("artifactManifest")).keySet(),
                unregisteredRestart.registeredArtifacts(runId).keySet());
        Map<String, Object> receipt = (Map<String, Object>) invokePure(restarted, "loadFinalizationReceipt", unregisteredRestart, runId);
        assertEquals(published.get("artifactManifest"), receipt.get("artifactManifest"));
        if (completionModelPreservesIdentity()) {
            assertEquals("CONFIRMED", receipt.get("completionNotificationState"));
            assertEquals(1, outboundHttpRequests.get());
            restarted.controlRun(unregisteredRestart, runId, "FINALIZE", finalizationRequest(runId));
            assertEquals(1, outboundHttpRequests.get());
        } else {
            assertEquals("PENDING", receipt.get("completionNotificationState"));
            assertEquals(0, outboundHttpRequests.get());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparedFinalizationReceiptFailsClosedWhenArtifactIsMissing() throws Exception {
        String runId = "clock-driven-finalization-missing-artifact-test";
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(restTemplate, runId);
        MutableContext firstContext = new MutableContext(workspaceRoot);
        createFinalizableRun(firstContext, runId);
        
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        seedStoredReceipt(provider, firstContext, runId, "PREPARED", "COMPLETED");
        Map<String, Object> receipt = new ObjectMapper().readValue(Files.readAllBytes(finalizationReceiptPath(runId)), Map.class);
        Map<String, Object> summaryEntry = (Map<String, Object>) ((Map<?, ?>) receipt.get("artifactManifest")).get("summary");
        Path summaryPath = workspaceRoot.resolve(String.valueOf(summaryEntry.get("artifactRef")));
        Files.delete(summaryPath);

        MutableContext restartedContext = new MutableContext(workspaceRoot);
        BankingPolicyWindTunnelExecutionProvider restarted =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        assertThrows(IllegalStateException.class, () -> restarted.controlRun(
                restartedContext, runId, "FINALIZE", finalizationRequest(runId)));

        assertFalse(Files.exists(summaryPath));
        assertTrue(restartedContext.findRun(runId).isEmpty());
        assertEquals("PREPARED", new ObjectMapper().readValue(
                Files.readAllBytes(finalizationReceiptPath(runId)), Map.class).get("state"));
        verify(restTemplate, never()).exchange(
                startsWith("http://experiment-service:7457/api/simulation-runs/" + runId),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Object.class));
    
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparedFinalizationReceiptRejectsAValidButIncompleteArtifactManifest() throws Exception {
        String runId = "clock-driven-finalization-subset-manifest-test";
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(restTemplate, runId);
        MutableContext firstContext = new MutableContext(workspaceRoot);
        createFinalizableRun(firstContext, runId);
        
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        seedStoredReceipt(provider, firstContext, runId, "PREPARED", "COMPLETED");
        Path receiptPath = finalizationReceiptPath(runId);
        Map<String, Object> receipt = new ObjectMapper().readValue(
                Files.readAllBytes(receiptPath), Map.class);
        Map<String, Object> artifactManifest = new LinkedHashMap<>(
                (Map<String, Object>) receipt.get("artifactManifest"));
        assertTrue(artifactManifest.remove("hybridAgentAuditJson") != null);
        receipt.put("artifactManifest", artifactManifest);
        Files.write(receiptPath, new ObjectMapper().writeValueAsBytes(receipt));

        MutableContext restartedContext = new MutableContext(workspaceRoot);
        BankingPolicyWindTunnelExecutionProvider restarted =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);

        assertThrows(IllegalStateException.class, () -> restarted.controlRun(
                restartedContext, runId, "FINALIZE", finalizationRequest(runId)));
        assertTrue(restartedContext.findRun(runId).isEmpty());
        verify(restTemplate, never()).exchange(
                startsWith("http://experiment-service:7457/api/simulation-runs/" + runId),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Object.class));
    
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedFinalizationReceiptRejectsDifferentProviderRevision() throws Exception {
        String runId = "clock-driven-finalization-provider-revision-test";
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(restTemplate, runId);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, runId);
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        seedStoredReceipt(provider, context, runId, "PUBLISHED", "COMPLETED");
        Path receiptPath = finalizationReceiptPath(runId);
        Map<String, Object> receipt = new ObjectMapper().readValue(
                Files.readAllBytes(receiptPath), Map.class);
        receipt.put("providerRevision", "oci-sha256:" + "0".repeat(64));
        Files.write(receiptPath, new ObjectMapper().writeValueAsBytes(receipt));

        MutableContext restartedContext = new MutableContext(workspaceRoot);
        BankingPolicyWindTunnelExecutionProvider restarted =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);

        assertThrows(IllegalStateException.class, () -> restarted.controlRun(
                restartedContext, runId, "FINALIZE", finalizationRequest(runId)));
        assertTrue(restartedContext.findRun(runId).isEmpty());
    
    }

    @Test
    void artifactWriteFailureDoesNotCreatePreparedReceipt() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-artifact-write-failure";
        createFinalizableRun(context, runId);
        Path directory = (Path) invokePure(provider, "finalizationSummaryDirectory", context, runId);
        Files.createDirectories(directory.getParent());
        Files.writeString(directory, "file blocks the artifact directory");
        Map<String, String> before = savedFileDigests();
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> seedStoredReceipt(provider, context, runId, "PREPARED", "COMPLETED"));
        assertFalse(Files.exists(finalizationReceiptPath(runId)));
        assertEquals(before, savedFileDigests());
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedFinalizationReceiptFailsClosedWhenArtifactIsAltered() throws Exception {
        String runId = "clock-driven-finalization-altered-artifact-test";
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(restTemplate, runId);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, runId);
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        Map<String, Object> receipt = seedStoredReceipt(provider, context, runId, "PUBLISHED", "COMPLETED");
        Map<String, Object> summaryEntry = (Map<String, Object>) ((Map<?, ?>) receipt.get("artifactManifest")).get("summary");
        Path summaryPath = workspaceRoot.resolve(String.valueOf(summaryEntry.get("artifactRef")));
        Files.writeString(summaryPath, "{\"tampered\":true}");

        MutableContext restartedContext = new MutableContext(workspaceRoot);
        BankingPolicyWindTunnelExecutionProvider restarted =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        assertThrows(IllegalStateException.class, () -> restarted.controlRun(
                restartedContext, runId, "FINALIZE", finalizationRequest(runId)));
        assertThrows(IllegalStateException.class, () -> restarted.loadBundle(
                restartedContext, runId));

        assertTrue(restartedContext.findRun(runId).isEmpty());
        verify(restTemplate, never()).exchange(
                startsWith("http://experiment-service:7457/api/simulation-runs/" + runId),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Object.class));
    
    }

    @Test
    void preparedReceiptPreservesFrozenProofWhenTerminalStateWins() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, task -> {});
        for (String status : List.of("FAILED", "CANCELLED")) {
            MutableContext context = new MutableContext(workspaceRoot);
            String runId = "historical-prepared-terminal-" + status.toLowerCase();
            Map<String, Object> prepared = seedStoredReceipt(provider, context, runId, "PREPARED", "COMPLETED");
            byte[] bytes = Files.readAllBytes(finalizationReceiptPath(runId));
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(status, null, null, System.currentTimeMillis(), context.findRun(runId).orElseThrow().metadata()));
            assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
            assertEquals(status, context.findRun(runId).orElseThrow().status());
            assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(finalizationReceiptPath(runId))));
            assertEquals("RUNNING", ((Map<?, ?>) prepared.get("runSnapshot")).get("status"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void completionNotificationRetriesStableOperationAfterPublishedReceipt() throws Exception {
        String runId = "historical-finalization-notification-retry";
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, task -> {});
        MutableContext context = new MutableContext(workspaceRoot);
        Map<String, Object> original = seedStoredReceipt(provider, context, runId, "PUBLISHED", "COMPLETED");
        directExperimentStatus = 408;
        directExperimentBody = Map.of("error", "temporary rejection");
        provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId));
        assertEquals("PENDING", ((Map<?, ?>) invokePure(provider, "loadFinalizationReceipt", context, runId)).get("completionNotificationState"));
        directExperimentStatus = 200;
        directExperimentBody = Map.of("runId", runId);
        provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId));
        Map<?, ?> receipt = (Map<?, ?>) invokePure(provider, "loadFinalizationReceipt", context, runId);
        assertEquals(original.get("artifactManifest"), receipt.get("artifactManifest"));
        if (completionModelPreservesIdentity()) {
            assertEquals(2, experimentRequests.size());
            assertEquals(experimentRequests.get(0).get("body"), experimentRequests.get(1).get("body"));
            ObjectMapper wireMapper = new ObjectMapper();
            assertEquals(wireMapper.readTree(wireMapper.writeValueAsBytes(original.get("completionNotificationPayload"))),
                    wireMapper.readTree(wireMapper.writeValueAsBytes(experimentRequests.get(0).get("body"))));
            assertEquals(experimentRequests.get(0).get("rawBodyBase64"), experimentRequests.get(1).get("rawBodyBase64"));
            assertEquals(finalizationRequest(runId).get("operationId"), experimentRequests.get(0).get("idempotencyKey"));
            assertEquals(experimentRequests.get(0).get("idempotencyKey"), experimentRequests.get(1).get("idempotencyKey"));
            assertEquals("CONFIRMED", receipt.get("completionNotificationState"));
            provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId));
            assertEquals(2, experimentRequests.size());
        } else {
            assertEquals(0, experimentRequests.size());
            assertEquals("PENDING", receipt.get("completionNotificationState"));
        }
        verifyNoInteractions(restTemplate);
    }

    @Test
    void twoProviderInstancesSharingStoragePublishOneFinalization() throws Exception {
        String runId = "historical-shared-finalization";
        RestTemplate restTemplate = mock(RestTemplate.class);
        var first = provider(new ObjectMapper(), restTemplate, task -> {});
        var second = provider(new ObjectMapper(), restTemplate, task -> {});
        MutableContext firstContext = new MutableContext(workspaceRoot);
        Map<String, Object> published = seedStoredReceipt(first, firstContext, runId, "PUBLISHED", "COMPLETED");
        directExperimentStatus = 200;
        directExperimentBody = Map.of("runId", runId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable reconcile = () -> { try { ready.countDown(); release.await();
            MutableContext isolated = new MutableContext(workspaceRoot);
            (Thread.currentThread().getName().equals("historical-first") ? first : second)
                    .controlRun(isolated, runId, "FINALIZE", finalizationRequest(runId));
            assertEquals("COMPLETED", isolated.findRun(runId).orElseThrow().status());
            assertEquals(((Map<?, ?>) published.get("artifactManifest")).keySet(),
                    isolated.registeredArtifacts(runId).keySet());
        } catch (Throwable error) { failure.compareAndSet(null, error); } };
        Thread left = new Thread(reconcile, "historical-first"), right = new Thread(reconcile, "historical-second");
        left.start(); right.start(); assertTrue(ready.await(2, TimeUnit.SECONDS)); release.countDown();
        left.join(5_000); right.join(5_000); assertFalse(left.isAlive()); assertFalse(right.isAlive());
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertEquals(completionModelPreservesIdentity() ? 1 : 0, experimentRequests.size());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void platformOwnedV2FinalizationRejectsMissingOperationBeforePublicationMutation() throws Exception {
        String runId = "clock-driven-finalization-missing-operation-test";
        RestTemplate restTemplate = mock(RestTemplate.class);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, runId);
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        assertInvalidWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context, runId, "FINALIZE", Map.of()));
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    void failedAndCancelledV2TerminalRunsPublishDurableTerminalReceipts() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, task -> {});
        for (String status : List.of("FAILED", "CANCELLED")) {
            MutableContext context = new MutableContext(workspaceRoot);
            String runId = "historical-terminal-" + status.toLowerCase();
            Map<String, Object> receipt = seedStoredReceipt(provider, context, runId, "PUBLISHED", status);
            assertEquals("NOT_REQUIRED", receipt.get("completionNotificationState"));
            MutableContext restarted = new MutableContext(workspaceRoot);
            assertEquals(status, provider.controlRun(restarted, runId, "FINALIZE", finalizationRequest(runId)).status());
            assertEquals(((Map<?, ?>) receipt.get("artifactManifest")).keySet(),
                    restarted.registeredArtifacts(runId).keySet());
            assertEquals(receipt.get("artifactManifest"), ((Map<?, ?>) invokePure(provider, "loadFinalizationReceipt", restarted, runId)).get("artifactManifest"));
        }
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void v2FinalizationReceiptRejectsAmbiguousJsonContradictoryStatesAndEscapingArtifacts()
            throws Exception {
        String publishedRunId = "clock-driven-v2-strict-published-receipt";
        RestTemplate publishedRestTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(publishedRestTemplate, publishedRunId);
        MutableContext publishedContext = new MutableContext(workspaceRoot);
        createFinalizableRun(publishedContext, publishedRunId);
        BankingPolicyWindTunnelExecutionProvider publishedProvider =
                provider(
                        new ObjectMapper(), publishedRestTemplate, Runnable::run);
        seedStoredReceipt(publishedProvider, publishedContext, publishedRunId, "PUBLISHED", "COMPLETED");
        Path publishedReceiptPath = finalizationReceiptPath(publishedRunId);
        byte[] validPublishedReceipt = Files.readAllBytes(publishedReceiptPath);
        String publishedJson = new String(validPublishedReceipt, StandardCharsets.UTF_8);
        assertTrue(publishedJson.matches("(?s).*\\\"state\\\"\\s*:\\s*\\\"PUBLISHED\\\".*"));

        Files.writeString(
                publishedReceiptPath,
                publishedJson.replaceFirst(
                        "\\\"state\\\"\\s*:\\s*\\\"PUBLISHED\\\"",
                        "\"state\":\"PREPARED\",\"state\":\"PUBLISHED\""),
                StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                publishedProvider.getRunStatus(publishedContext, publishedRunId));

        Files.write(publishedReceiptPath, validPublishedReceipt);
        Files.writeString(
                publishedReceiptPath,
                publishedJson + "{}",
                StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                publishedProvider.getRunStatus(publishedContext, publishedRunId));

        byte[] malformedUtf8 = validPublishedReceipt.clone();
        int providerRevisionOffset = publishedJson.indexOf(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
        assertTrue(providerRevisionOffset >= 0);
        malformedUtf8[providerRevisionOffset] = (byte) 0xff;
        Files.write(publishedReceiptPath, malformedUtf8);
        assertThrows(IllegalStateException.class, () ->
                publishedProvider.getRunStatus(publishedContext, publishedRunId));

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> publishedReceipt = mapper.readValue(validPublishedReceipt, Map.class);
        Map<String, Object> runningSnapshot = new LinkedHashMap<>(
                (Map<String, Object>) publishedReceipt.get("runSnapshot"));
        runningSnapshot.put("status", "RUNNING");
        runningSnapshot.remove("completedAtEpochMs");
        publishedReceipt.put("runSnapshot", runningSnapshot);
        Files.write(publishedReceiptPath, mapper.writeValueAsBytes(publishedReceipt));
        assertThrows(IllegalStateException.class, () ->
                publishedProvider.getRunStatus(publishedContext, publishedRunId));

        publishedReceipt = mapper.readValue(validPublishedReceipt, Map.class);
        Map<String, Object> artifactManifest = new LinkedHashMap<>(
                (Map<String, Object>) publishedReceipt.get("artifactManifest"));
        Map<String, Object> summaryArtifact = new LinkedHashMap<>(
                (Map<String, Object>) artifactManifest.get("summary"));
        summaryArtifact.put("artifactRef", "../outside-summary.json");
        artifactManifest.put("summary", summaryArtifact);
        publishedReceipt.put("artifactManifest", artifactManifest);
        Files.write(publishedReceiptPath, mapper.writeValueAsBytes(publishedReceipt));
        assertThrows(IllegalStateException.class, () ->
                publishedProvider.getRunStatus(publishedContext, publishedRunId));

        String preparedRunId = "clock-driven-v2-strict-prepared-receipt";
        RestTemplate preparedRestTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(preparedRestTemplate, preparedRunId);
        MutableContext preparedContext = new MutableContext(workspaceRoot);
        createFinalizableRun(preparedContext, preparedRunId);
        BankingPolicyWindTunnelExecutionProvider preparedProvider =
                provider(
                        new ObjectMapper(), preparedRestTemplate, Runnable::run);
        seedStoredReceipt(preparedProvider, preparedContext, preparedRunId, "PREPARED", "COMPLETED");
        Path preparedReceiptPath = finalizationReceiptPath(preparedRunId);
        Map<String, Object> preparedReceipt = mapper.readValue(
                Files.readAllBytes(preparedReceiptPath), Map.class);
        Map<String, Object> completedSnapshot = new LinkedHashMap<>(
                (Map<String, Object>) preparedReceipt.get("runSnapshot"));
        completedSnapshot.put("status", "COMPLETED");
        completedSnapshot.put("completedAtEpochMs", System.currentTimeMillis());
        preparedReceipt.put("runSnapshot", completedSnapshot);
        Files.write(preparedReceiptPath, mapper.writeValueAsBytes(preparedReceipt));
        assertThrows(IllegalStateException.class, () ->
                preparedProvider.getRunStatus(preparedContext, preparedRunId));
    
    }

    @Test
    void completedV2TerminalRunFailsClosedWhenReceiptIsMissing() throws Exception {
        String runId = "clock-driven-v2-completed-no-receipt";
        RestTemplate restTemplate = mock(RestTemplate.class);
        MutableContext context = new MutableContext(workspaceRoot);
        createTerminalRun(context, runId, "COMPLETED", "PLATFORM_OWNED_V2");
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    void explicitlyPinnedLegacyV1RecoveryRejectsV2OperationId() throws Exception {
        String runId = "clock-driven-v1-operation-rejection";
        RestTemplate restTemplate = mock(RestTemplate.class);
        MutableContext context = new MutableContext(workspaceRoot);
        createTerminalRun(context, runId, "FAILED", "PLATFORM_OWNED_V1");
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        assertInvalidWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context, runId, "FINALIZE", finalizationRequest(runId)));
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitV1RecoveryFinalizesNonterminalRunsWithoutV2Receipt() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        var provider = provider(new ObjectMapper(), restTemplate, Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "historical-v1-nonterminal";
        createFinalizableRun(context, runId, "PLATFORM_OWNED_V1");
        assertEquals("RUNNING", provider.getRunStatus(context, runId).status());
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", Map.of()));
        assertFalse(Files.exists(finalizationReceiptPath(runId)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void v2ReceiptRejectsAnyPersistedV1FinalizationMode() throws Exception {
        String runId = "clock-driven-v2-receipt-v1-label-rejection";
        RestTemplate restTemplate = mock(RestTemplate.class);
        stubProviderFinalizationDependencies(restTemplate, runId);
        MutableContext context = new MutableContext(workspaceRoot);
        createFinalizableRun(context, runId);
        BankingPolicyWindTunnelExecutionProvider provider =
                provider(
                        new ObjectMapper(), restTemplate, Runnable::run);
        seedStoredReceipt(provider, context, runId, "PUBLISHED", "COMPLETED");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> validReceipt = mapper.readValue(
                Files.readAllBytes(finalizationReceiptPath(runId)), Map.class);

        for (String invalidMode : List.of("LEGACY_V1", "PLATFORM_OWNED_V1")) {
            Map<String, Object> invalidReceipt = new LinkedHashMap<>(validReceipt);
            invalidReceipt.put("finalizationMode", invalidMode);
            Files.write(
                    finalizationReceiptPath(runId),
                    mapper.writeValueAsBytes(invalidReceipt));

            assertThrows(IllegalStateException.class, () -> provider.getRunStatus(context, runId));
        }
    
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenHybridFinalizationRejectsUnscopedRuntimeDrainResponse() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/runtime-state?simulationRunId=clock-driven-unscoped-drain-test"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok((Map) Map.of(
                        "scope", "global",
                        "activeTickTasks", 0,
                        "runningAgents", 0,
                        "pendingAgents", 0)));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(
                        hybridDecisionResponse(2, 3, 2, 1),
                        hybridDecisionResponse(2, 3, 2, 1),
                        hybridDecisionResponse(2, 3, 2, 1),
                        hybridDecisionResponse(2, 3, 2, 1),
                        hybridDecisionResponse(2, 3, 2, 1));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "clock-driven-unscoped-drain-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-unscoped-drain-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        stubSealedOpportunityReconciliation(restTemplate, runId);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(
                context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void agenticExplanationShowsAdmittedTurnsWhenNoAuditRowsExistYet() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(0, 3, 2, 1));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "clock-driven-zero-audit-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-zero-audit-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));


        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) invokePure(provider,
                "buildDecisionAuditPayload", hybridDecisionResponse(0, 3, 2, 1).getBody(), "stored-fixture");
        @SuppressWarnings("unchecked")
        Map<String, Object> agenticSimulation = (Map<String, Object>) invokePure(provider,
                "buildAgenticSimulationSummary", parameters, 100, audit, Map.of(), Map.of(), runId, Map.of(), false);
        assertEquals(0, agenticSimulation.get("actualDecisionCount"));
        assertEquals(3, agenticSimulation.get("observedDecisionOpportunities"));
        assertEquals(2, agenticSimulation.get("admittedDecisionOpportunities"));
        String explanation = String.valueOf(agenticSimulation.get("decisionCoverageExplanation"));
        assertTrue(explanation.contains("runtime checked 3"));
        assertTrue(explanation.contains("admitted 2"));
        assertTrue(explanation.contains("captured 0 audited"));
    
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void agenticSummaryPreservesInconsistentAdmissionEvidence() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(3, 4, 2, 2));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "clock-driven-admission-integrity-test";
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/replay"),
                eq(String.class)))
                .thenReturn("{\"executionContext\":{\"runId\":\"" + runId + "\"}}");
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/time"),
                eq(Long.class)))
                .thenReturn(DEFAULT_SIMULATION_START_EPOCH_MS + 86_400_000L);
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-admission-clamp-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));


        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) invokePure(provider,
                "buildDecisionAuditPayload", hybridDecisionResponse(3, 4, 2, 2).getBody(), "stored-fixture");
        @SuppressWarnings("unchecked")
        Map<String, Object> agenticSimulation = (Map<String, Object>) invokePure(provider,
                "buildAgenticSimulationSummary", parameters, 100, audit, Map.of(), Map.of(), runId, Map.of(), false);
        assertEquals(3, agenticSimulation.get("actualDecisionCount"));
        assertEquals(4, agenticSimulation.get("observedDecisionOpportunities"));
        assertEquals(2, agenticSimulation.get("admittedDecisionOpportunities"));
        assertEquals(2, agenticSimulation.get("deferredDecisionOpportunities"));
        List<?> warnings = (List<?>) agenticSimulation.get("evidenceIntegrityWarnings");
        assertEquals("AUDITED_DECISIONS_EXCEED_ADMITTED_TURNS", ((Map<?, ?>) warnings.getFirst()).get("code"));
        assertTrue(String.valueOf(agenticSimulation.get("decisionCoverageExplanation")).contains("Evidence integrity warning"));
    
        assertEquals(0, outboundHttpRequests.get());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenScorecardCountsRejectedWorkBeyondUiCheckpointSample() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(1, 3, 2, 1));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        context.putPackMetadata(BankingPolicyWindTunnelManifest.PACK_ID, Map.of(
                "outcomeScorecards", List.of(rejectedWorkScorecard())));
        String runId = "clock-driven-full-checkpoint-score-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-full-checkpoint-score-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        writeCheckpointSampleWithLateRejections(runId);
        stubSealedOpportunityReconciliation(restTemplate, runId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) invokePure(provider,
                "buildClockDrivenHybridStateMutations", context, runId);
        assertEquals(0, mutations.stream().filter(row -> "REMOTE_DOMAIN_WORK_REJECTED".equals(row.get("mutationType"))).count());
        int rejected = (Integer) invokePure(provider, "countRejectedCheckpointWork", context, runId, mutations);
        assertEquals(30, rejected);
        PolicyWindTunnelExecutionContext scoreContext = mock(PolicyWindTunnelExecutionContext.class);
        when(scoreContext.packMetadata(BankingPolicyWindTunnelManifest.PACK_ID)).thenReturn(Map.of("outcomeScorecards", List.of(rejectedWorkScorecard())));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scores = (List<Map<String, Object>>) invokePure(provider,
                "evaluateDomainOutcomeScorecards", scoreContext, runId, "hardship-relief", parameters,
                Map.of(), Map.of("rejectedWorkCount", rejected), Map.of());
        assertEquals("BLOCK", scores.getFirst().get("recommendation"));
        org.mockito.Mockito.clearInvocations(restTemplate);
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenScorecardDeduplicatesCumulativeRejectedCheckpointSnapshots() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(1, 3, 2, 1));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        context.putPackMetadata(BankingPolicyWindTunnelManifest.PACK_ID, Map.of(
                "outcomeScorecards", List.of(rejectedWorkScorecard())));
        String runId = "clock-driven-cumulative-rejection-score-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-cumulative-rejection-score-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        writeRepeatedRejectedCheckpointSnapshots(runId);
        stubSealedOpportunityReconciliation(restTemplate, runId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) invokePure(provider,
                "buildClockDrivenHybridStateMutations", context, runId);
        assertEquals(30, mutations.stream().filter(row -> "REMOTE_DOMAIN_WORK_REJECTED".equals(row.get("mutationType"))).count());
        int rejected = (Integer) invokePure(provider, "countRejectedCheckpointWork", context, runId, mutations);
        assertEquals(1, rejected);
        PolicyWindTunnelExecutionContext scoreContext = mock(PolicyWindTunnelExecutionContext.class);
        when(scoreContext.packMetadata(BankingPolicyWindTunnelManifest.PACK_ID)).thenReturn(Map.of("outcomeScorecards", List.of(rejectedWorkScorecard())));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scores = (List<Map<String, Object>>) invokePure(provider,
                "evaluateDomainOutcomeScorecards", scoreContext, runId, "hardship-relief", parameters,
                Map.of(), Map.of("rejectedWorkCount", rejected), Map.of());
        assertEquals("WATCH", scores.getFirst().get("recommendation"));
        org.mockito.Mockito.clearInvocations(restTemplate);
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void clockDrivenScorecardCountsAnonymousRejectedCheckpointSnapshotsFailOpen() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.toString().startsWith("http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId=")),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(hybridDecisionResponse(1, 3, 2, 1));
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        context.putPackMetadata(BankingPolicyWindTunnelManifest.PACK_ID, Map.of(
                "outcomeScorecards", List.of(rejectedWorkScorecard())));
        String runId = "clock-driven-anonymous-rejection-score-test";
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of(
                "presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "DAILY",
                "simulationHorizonDays", 1,
                "cohortId", "banking-balanced-banking-100-anonymous-rejection-score-test",
                "cohortTargetSize", 100));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
        writeAnonymousRejectedCheckpointSnapshots(runId);
        stubSealedOpportunityReconciliation(restTemplate, runId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) invokePure(provider,
                "buildClockDrivenHybridStateMutations", context, runId);
        assertEquals(30, mutations.stream().filter(row -> "REMOTE_DOMAIN_WORK_REJECTED".equals(row.get("mutationType"))).count());
        int rejected = (Integer) invokePure(provider, "countRejectedCheckpointWork", context, runId, mutations);
        assertEquals(30, rejected);
        PolicyWindTunnelExecutionContext scoreContext = mock(PolicyWindTunnelExecutionContext.class);
        when(scoreContext.packMetadata(BankingPolicyWindTunnelManifest.PACK_ID)).thenReturn(Map.of("outcomeScorecards", List.of(rejectedWorkScorecard())));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scores = (List<Map<String, Object>>) invokePure(provider,
                "evaluateDomainOutcomeScorecards", scoreContext, runId, "hardship-relief", parameters,
                Map.of(), Map.of("rejectedWorkCount", rejected), Map.of());
        assertEquals("BLOCK", scores.getFirst().get("recommendation"));
        org.mockito.Mockito.clearInvocations(restTemplate);
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
    }

    @Test
    void clockDrivenHybridClampsPlaybackToSimulatorRuntimeLimit() throws Exception {
        String cohortId = "banking-balanced-banking-100-playback-clamp-test";
        writeGeneratedCohortFixture(cohortId, 2);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/rate/info"),
                eq(String.class)))
                .thenReturn("{\"max\":3600.0}");
        when(restTemplate.exchange(
                eq("http://simulator.test/api/clock/replay"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                startsWith("http://agent-runtime.test/api/agents"),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/init"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        BankingRemotePolicyWindTunnelExecutionContext context =
                BankingRemotePolicyWindTunnelTestContexts.durable(new ObjectMapper(), workspaceRoot);
        // Historical clock settings are retained; execution must stop before any mutation or outbound call.
        assertRetiredWithoutEffects(context, restTemplate, () -> provider.startRun(context, withPinnedActivationSettings(Map.of(
                "presetId", "saver-repricing",
                "executionMode", "CLOCK_DRIVEN_HYBRID",
                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "runPath", "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "EVENT_ONLY",
                "playbackRate", 7200.0,
                "simulationStartEpochMs", 1_800_000_000_000L,
                "cohortId", cohortId,
                "cohortSelection", Map.of("cohortId", cohortId))),
                "PLATFORM_ADMIN"));
    }

    @Test
    void eventOnlyNoTokenPlaybackUsesHigherBoundOnlyWhenSimulatorProvesSupport() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(
                eq("http://simulator.test/api/clock/rate/info"),
                eq(String.class)))
                .thenReturn("{\"max\":14400.0}");
        BankingPolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        Method playbackRate = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "playbackRate",
                Map.class,
                String.class,
                PolicyWindTunnelExecutionContext.class);
        playbackRate.setAccessible(true);

        for (String cadence : List.of("EVENT_ONLY", "DAILY")) {
            for (String mode : List.of("STUB_LLM", "DETERMINISTIC", "REAL_LLM")) {
                assertRetiredWithoutEffects(context, restTemplate, () -> invokePure(provider,
                        "playbackRate", Map.of("agentDecisionCadence", cadence), mode, context));
            }
        }
    }

    @Test
    void policyOnlyRunRejectsPauseResumeFinalizeAndAllowsCancel() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        PolicyWindTunnelExecutionProvider provider = provider(
                new ObjectMapper(),
                restTemplate,
                Runnable::run);
        MutableContext context = new MutableContext(workspaceRoot);
        String runId = "policy-only-run";
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(Map.of(
                        "presetId", "saver-repricing",
                        "executionMode", "FAST_BATCH_COMPARE",
                        "runPath", "POLICY_ONLY_CHECK",
                        "agentDecisionMode", "DETERMINISTIC"))
                .withLifecycle("RUNNING", 0.2, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-saver-repricing",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "saver-repricing",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));

        IllegalArgumentException pauseError = assertThrows(IllegalArgumentException.class,
                () -> provider.controlRun(context, runId, "PAUSE", Map.of()));
        assertTrue(pauseError.getMessage().contains("CLOCK_DRIVEN_HYBRID"));
        IllegalArgumentException resumeError = assertThrows(IllegalArgumentException.class,
                () -> provider.controlRun(context, runId, "RESUME", Map.of()));
        assertTrue(resumeError.getMessage().contains("CLOCK_DRIVEN_HYBRID"));
        IllegalArgumentException finalizeError = assertThrows(IllegalArgumentException.class,
                () -> provider.controlRun(context, runId, "FINALIZE", Map.of()));
        assertEquals("Scenario Lab finalization requires an explicitly pinned finalization mode", finalizeError.getMessage());
        assertInvalidWithoutEffects(context, restTemplate, () -> provider.controlRun(context, runId, "FINALIZE", Map.of()));

        PolicyWindTunnelRunRecord cancelled = provider.controlRun(context, runId, "CANCEL", Map.of());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", cancelled.phase());
        assertTrue(cancelled.logTail().stream().anyMatch(line -> line.contains("Captured evidence remains available")));
    }

    @Test
    void providerOwnsBundleArtifactAndEvidenceExport() throws Exception {
        PolicyWindTunnelExecutionProvider provider = new BankingPolicyWindTunnelExecutionProvider();
        String runId = "run-1";
        Path summaryDir = workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId)
                .resolve("summary");
        Files.createDirectories(summaryDir);
        Path summary = summaryDir.resolve("scenario-lab-summary.json");
        Path impactedCohorts = summaryDir.resolve("impacted-cohorts.csv");
        Path experimentCard = summaryDir.resolve("experiment-card.md");
        Path simulationTimeline = summaryDir.resolve("simulation-timeline.json");
        Path sideEffectLedger = summaryDir.resolve("side-effect-ledger.json");
        Path branchStateProjection = summaryDir.resolve("branch-state-projection.json");
        Files.writeString(impactedCohorts, "agentId,segment\nagent-1,thin-file\n");
        Files.writeString(experimentCard, "# Experiment Card\n");
        Files.writeString(simulationTimeline, """
                {
                  "schemaVersion": "customer_outcome_simulation_timeline.v1",
                  "events": [
                    {
                      "eventId": "effect-proposed-api-call-policy-candidate",
                      "eventType": "API_CALL",
                      "branchRole": "proposed",
                      "elapsedSeconds": 86400,
                      "evidenceRef": "sideEffectLedgerJson"
                    }
                  ]
                }
                """);
        Files.writeString(sideEffectLedger, """
                {
                  "schemaVersion": "customer_outcome_side_effect_ledger.v1",
                  "entries": [
                    {
                      "effectId": "proposed-api-call-policy-candidate",
                      "effectType": "API_CALL",
                      "operation": "POST /policy/rate-change",
                      "status": "APPLIED",
                      "evidenceRef": "sideEffectLedgerJson"
                    }
                  ]
                }
                """);
        Files.writeString(branchStateProjection, """
                {
                  "schemaVersion": "customer_outcome_branch_state_projection.v1",
                  "branches": [
                    {
                      "branchId": "baseline-current-policy",
                      "branchRole": "baseline",
                      "agentCount": 2
                    },
                    {
                      "branchId": "proposed-candidate-policy",
                      "branchRole": "proposed",
                      "agentCount": 2
                    }
                  ],
                  "mutations": [
                    {
                      "mutationId": "mutation-1-alex-borrower",
                      "mutationType": "RATE_POLICY_EXPOSURE",
                      "personaName": "Alex Borrower",
                      "deltaValue": 12.50,
                      "evidenceRef": "branchStateProjectionJson"
                    }
                  ],
                  "metricTraces": [
                    {
                      "metricKey": "exposedBalance",
                      "sourceMutationIds": ["mutation-1-alex-borrower"]
                    }
                  ]
                }
                """);
        Files.writeString(summary, """
                {
                  "scenarioId": "banking-policy-compare",
                  "demoPreset": { "presetId": "credit-line-review" },
                  "decisionComparison": {
                    "schemaVersion": "customer_outcome_decision_comparison.v1",
                    "comparisonId": "credit-line-review-baseline-vs-proposed",
                    "scenarioId": "banking-policy-compare",
                    "baseline": {
                      "branchId": "baseline-current-policy",
                      "evidenceLinks": { "evaluationReport": "baseline-evaluation.json" }
                    },
                    "proposed": {
                      "branchId": "proposed-candidate-policy",
                      "evidenceLinks": { "evaluationReport": "candidate-evaluation.json" }
                    },
                    "metricDeltas": [
                      {
                        "metricKey": "overallScore",
                        "baselineValue": 0.9,
                        "proposedValue": 0.8,
                        "deltaValue": -0.1,
                        "evidenceRef": "comparisonReport"
                      }
                    ]
                  },
                  "simulationTimeline": {
                    "schemaVersion": "customer_outcome_simulation_timeline.v1",
                    "events": [
                      { "eventId": "effect-proposed-api-call-policy-candidate", "eventType": "API_CALL" }
                    ]
                  },
                  "sideEffectLedger": {
                    "schemaVersion": "customer_outcome_side_effect_ledger.v1",
                    "entries": [
                      { "effectId": "proposed-api-call-policy-candidate", "effectType": "API_CALL" }
                    ]
                  },
                  "branchStateProjection": {
                    "schemaVersion": "customer_outcome_branch_state_projection.v1",
                    "branches": [
                      { "branchId": "baseline-current-policy", "branchRole": "baseline" },
                      { "branchId": "proposed-candidate-policy", "branchRole": "proposed" }
                    ],
                    "mutations": [
                      { "mutationId": "mutation-1-alex-borrower", "mutationType": "RATE_POLICY_EXPOSURE" }
                    ],
                    "metricTraces": [
                      { "metricKey": "exposedBalance", "sourceMutationIds": ["mutation-1-alex-borrower"] }
                    ]
                  },
                  "runConfig": { "compareMode": "baseline-vs-candidate" },
                  "policyToggle": {
                    "productCode": "CARD-01",
                    "productName": "Credit Card",
                    "policyType": "credit-limit"
                  },
                  "exposureSummary": {
                    "scopeLabel": "Borrowers",
                    "cohortAgents": 2,
                    "eligibleAgents": 1,
                    "nonEligibleAgents": 1,
                    "impactedAgents": 1,
                    "eligibleAgentPct": 0.5,
                    "impactedOfEligiblePct": 1.0
                  },
                  "headline": {
                    "impactedAgents": 1,
                    "topImpactedSegment": "thin-file"
                  },
                  "evaluator": { "failBreaches": 0 },
                  "artifacts": {}
                }
                """);

        Map<String, String> registeredArtifacts = new LinkedHashMap<>();
        registeredArtifacts.put("summary", relative(summary));
        registeredArtifacts.put("impactedCohortsCsv", relative(impactedCohorts));
        registeredArtifacts.put("experimentCard", relative(experimentCard));
        registeredArtifacts.put("simulationTimelineJson", relative(simulationTimeline));
        registeredArtifacts.put("sideEffectLedgerJson", relative(sideEffectLedger));
        registeredArtifacts.put("branchStateProjectionJson", relative(branchStateProjection));
        FakeContext context = new FakeContext(workspaceRoot, runId, relative(summary), registeredArtifacts);

        Map<String, Object> bundle = provider.loadBundle(context, runId);
        assertEquals(runId, bundle.get("runId"));
        assertEquals(BankingPolicyWindTunnelManifest.PACK_ID, bundle.get("packId"));
        assertEquals(BankingPolicyWindTunnelManifest.DOMAIN_ID, bundle.get("domainId"));
        assertEquals(BankingPolicyWindTunnelManifest.SUMMARY_SCHEMA_VERSION, bundle.get("summarySchemaVersion"));
        assertTrue(((Map<?, ?>) bundle.get("branches")).containsKey("policyToggle"));
        assertEquals("credit-line-review-baseline-vs-proposed",
                ((Map<?, ?>) bundle.get("decisionComparison")).get("comparisonId"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) bundle.get("decisionComparison")).get("metricDeltas")).size());
        assertEquals("customer_outcome_simulation_timeline.v1",
                ((Map<?, ?>) bundle.get("simulationTimeline")).get("schemaVersion"));
        assertEquals("customer_outcome_side_effect_ledger.v1",
                ((Map<?, ?>) bundle.get("sideEffectLedger")).get("schemaVersion"));
        assertEquals("customer_outcome_branch_state_projection.v1",
                ((Map<?, ?>) bundle.get("branchStateProjection")).get("schemaVersion"));
        Map<?, ?> timeline = (Map<?, ?>) bundle.get("timeline");
        assertEquals(1, ((List<?>) timeline.get("events")).size());
        assertEquals(1, ((List<?>) timeline.get("sideEffects")).size());
        assertTrue(((Map<?, ?>) timeline.get("stateProjection")).containsKey("mutations"));
        assertTrue(((Map<?, ?>) bundle.get("metrics")).containsKey("headline"));
        assertTrue(((Map<?, ?>) bundle.get("segments")).containsKey("exposure"));
        assertEquals(bundle.get("summary"), bundle.get("domainPayload"));
        assertTrue(String.valueOf(bundle.get("impactedCohortsCsv")).contains("thin-file"));
        assertTrue(((Map<?, ?>) bundle.get("artifactDownloads")).containsKey("evidencePack"));
        assertTrue(((Map<?, ?>) ((Map<?, ?>) bundle.get("artifacts")).get("downloads")).containsKey("evidencePack"));

        PolicyWindTunnelArtifactPayload artifact = provider.readArtifact(context, runId, "summary");
        assertEquals("summary", artifact.type());
        assertEquals("application/json", artifact.contentType());
        assertEquals("scenario-lab-summary.json", artifact.filename());
        assertTrue(artifact.content().length > 0);

        PolicyWindTunnelArtifactPayload ledgerArtifact = provider.readArtifact(context, runId, "side-effect-ledger-json");
        assertEquals("side-effect-ledger-json", ledgerArtifact.type());
        assertEquals("side-effect-ledger.json", ledgerArtifact.filename());

        PolicyWindTunnelArtifactPayload projectionArtifact = provider.readArtifact(context, runId, "branch-state-projection-json");
        assertEquals("branch-state-projection-json", projectionArtifact.type());
        assertEquals("branch-state-projection.json", projectionArtifact.filename());

        PolicyWindTunnelArchivePayload archive = provider.buildEvidencePack(context, runId);
        assertEquals("scenario-lab-evidence-" + runId, archive.baseName());
        assertFalse(zipEntryNames(archive.content()).isEmpty());
        assertTrue(zipEntryNames(archive.content()).stream().anyMatch(entry -> entry.endsWith("/manifest.json")));
        assertTrue(zipEntryNames(archive.content()).stream().anyMatch(entry -> entry.endsWith("/README.md")));
        assertTrue(zipEntryNames(archive.content()).stream().anyMatch(entry -> entry.endsWith("/simulation-timeline.json")));
        assertTrue(zipEntryNames(archive.content()).stream().anyMatch(entry -> entry.endsWith("/side-effect-ledger.json")));
        assertTrue(zipEntryNames(archive.content()).stream().anyMatch(entry -> entry.endsWith("/branch-state-projection.json")));
        String manifest = zipEntryText(archive.content(), "/manifest.json");
        assertTrue(manifest.contains("\"summaryPath\" : \"artifacts/scenario-lab-summary.json\""));
        assertTrue(manifest.contains("\"path\" : \"artifacts/impacted-cohorts.csv\""));
        assertFalse(manifest.contains("output/experiments"));
        String readme = zipEntryText(archive.content(), "/README.md");
        assertTrue(readme.contains("- Summary path: `artifacts/scenario-lab-summary.json`"));
        assertFalse(readme.contains("output/experiments"));
    }

    @Test
    void experimentCardFramesCustomerOutcomeReportWithEvidenceChain() throws Exception {
        Path summaryDir = workspaceRoot.resolve("summary");
        Path baselineDir = workspaceRoot.resolve("baseline");
        Path candidateDir = workspaceRoot.resolve("candidate");
        Path evaluationDir = workspaceRoot.resolve("evaluation");
        Files.createDirectories(evaluationDir);
        Path baselineReport = evaluationDir.resolve("baseline-evaluation.json");
        Path candidateReport = evaluationDir.resolve("candidate-evaluation.json");
        Path comparisonReport = evaluationDir.resolve("regression-comparison.json");
        Files.writeString(baselineReport, "{}");
        Files.writeString(candidateReport, "{}");
        Files.writeString(comparisonReport, "{}");

        BankingPolicyScenarioSummaryPayload summary = new ObjectMapper().rebuild()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build()
                .readValue("""
                {
                  "demoPreset": { "presetId": "hardship-threshold-tradeoff" },
                  "decisionComparison": {
                    "comparisonId": "hardship-threshold-tradeoff-baseline-vs-proposed",
                    "scenarioId": "hardship-handling",
                    "title": "Hardship Threshold Tradeoff Decision Review",
                    "decisionQuestion": "Should wider hardship eligibility launch?",
                    "baseline": { "branchId": "baseline-current-policy" },
                    "proposed": { "branchId": "proposed-candidate-policy" },
                    "metricDeltas": [
                      {
                        "metricKey": "householdLiquidity",
                        "label": "Household Liquidity",
                        "baselineValue": -760410.70,
                        "proposedValue": -751960.70,
                        "deltaValue": 8450.0,
                        "unit": "currency",
                        "evidenceRef": "branchStateProjectionJson"
                      }
                    ],
                    "replayEvidence": {
                      "requiredEvidence": ["customerProfile", "timeline", "sideEffectLedger"]
                    }
                  },
                  "branchStateProjection": {
                    "mutations": [
                      {
                        "mutationId": "mutation-1-hana-relief-eligible",
                        "mutationType": "HARDSHIP_SUBSIDY_PROJECTION",
                        "personaName": "Hana Relief Eligible",
                        "baselineValue": -855.0,
                        "projectedValue": -205.0,
                        "deltaValue": 650.0,
                        "sourceEffectId": "proposed-api-call-policy-candidate-hardship-scan",
                        "evidenceRef": "branchStateProjectionJson"
                      }
                    ],
                    "metricTraces": [
                      {
                        "metricKey": "householdLiquidity",
                        "label": "Household Liquidity",
                        "baselineValue": -760410.70,
                        "proposedValue": -751960.70,
                        "deltaValue": 8450.0,
                        "unit": "currency",
                        "mutationEvidenceRef": "branchStateProjectionJson",
                        "sourceMutationIds": ["mutation-1-hana-relief-eligible"],
                        "evidenceChain": {
                          "supportLevel": "MUTATION_LINKED",
                          "artifactRefs": {
                            "branchStateProjectionJson": "branchStateProjectionJson",
                            "sideEffectLedgerJson": "sideEffectLedgerJson"
                          }
                        }
                      }
                    ]
                  },
                  "policyToggle": {
                    "productCode": "TS001",
                    "productName": "Standard Saving Product",
                    "policyType": "hardship"
                  },
                  "runConfig": {
                    "cohortSize": 52,
                    "compareMode": "SEEDED_DETERMINISTIC"
                  },
                  "sideEffectLedger": {
                    "entries": [
                      {
                        "effectId": "proposed-api-call-policy-candidate-hardship-scan",
                        "response": {
                          "hardshipThreshold": 250,
                          "subsidyAmount": 650
                        }
                      }
                    ]
                  },
                  "candidate": {
                    "impactedAgents": 13,
                    "exposedBalance": -4086369.90
                  },
                  "agentDiffs": [
                    {
                      "personaName": "Hana Relief Eligible",
                      "segment": "hardship",
                      "productCode": "TS001",
                      "productName": "Household Liquidity",
                      "productCategory": "HARDSHIP_RELIEF",
                      "balanceSheetSide": "OFF_BALANCE",
                      "productBalance": -855.0,
                      "interestBearingBalance": 0.0,
                      "currentBalance": -855.0,
                      "baselineCurrentBalance": -855.0,
                      "candidateCurrentBalance": -855.0,
                      "deltaCurrentBalance": 0.0,
                      "baselineCreditLimit": 2000.0,
                      "candidateCreditLimit": 2000.0,
                      "deltaCreditLimit": 0.0,
                      "baselineAvailableCredit": 1100.0,
                      "candidateAvailableCredit": 1100.0,
                      "deltaAvailableCredit": 0.0,
                      "baselineCreditUtilisation": 0.45,
                      "candidateCreditUtilisation": 0.45,
                      "deltaCreditUtilisation": 0.0,
                      "hardshipFlag": false,
                      "lowLiquidityBefore": true,
                      "lowLiquidityAfter": true,
                      "subsidyReceived": true,
                      "deltaMonthlyInterest": 0.0,
                      "deltaMonthlyBankMetric": 0.0,
                      "deltaMonthlyBankPnl": 0.0,
                      "fraudAlerted": false
                    }
                  ],
                  "checkpoints": [],
                  "notableActions": [],
                  "scheduledWorkFailures": [],
                  "impactedSegments": [],
                  "delta": {
                    "estimatedMonthlyCustomerInterest": 8450.0,
                    "estimatedMonthlyBankPnl": -8450.0
                  },
                  "evaluator": {
                    "breachCount": 0,
                    "failBreaches": 0,
                    "decisionGates": {
                      "breachedGateCount": 1,
                      "failGateCount": 0,
                      "gates": [
                        {
                          "gateId": "payment-stress-stressed-personas-cap",
                          "metricKey": "residualHardshipAgents",
                          "observedValue": 13.0,
                          "reason": "customers still below the hardship threshold after proposed relief",
                          "breached": true,
                          "fail": false
                        }
                      ]
                    }
                  },
                  "exposureSummary": {
                    "scopeLabel": "Vulnerable hardship cohort",
                    "cohortAgents": 52,
                    "eligibleAgents": 13,
                    "nonEligibleAgents": 39
                  },
                  "paymentStressSummary": {
                    "totalFailureCount": 0,
                    "baseline": {},
                    "candidate": {
                      "insufficientFundsFailureCount": 0,
                      "affectedPersonaCount": 0
                    }
                  }
                }
                """, BankingPolicyScenarioSummaryPayload.class);

        new BankingPolicyScenarioLabSummaryArtifactWriter(new ObjectMapper(), workspaceRoot)
                .writeSummaryArtifacts(
                        summaryDir,
                        "policy-wt-report-001",
                        summary,
                        baselineDir,
                        candidateDir,
                        baselineReport,
                        candidateReport,
                        comparisonReport);

        String experimentCard = Files.readString(summaryDir.resolve("experiment-card.md"));
        assertTrue(experimentCard.contains("`WATCH`"));
        assertTrue(experimentCard.contains("- Regression threshold breaches: `0`"));
        assertTrue(experimentCard.contains("- Decision gate fail breaches: `0`"));
        assertTrue(experimentCard.contains("- Decision gate warnings: `1`"));
        assertTrue(experimentCard.contains("`payment-stress-stressed-personas-cap` on `residualHardshipAgents=13` warned"));
        assertFalse(experimentCard.contains("on `affectedPersonaCount` warned"));
        assertTrue(experimentCard.contains("Household Liquidity"));
        assertTrue(experimentCard.contains("`MUTATION_LINKED`"));
        assertTrue(experimentCard.contains("- Timeline artifact: `simulation-timeline.json`"));
        assertFalse(experimentCard.contains("Timeline artifact: `output/"));
        assertTrue(experimentCard.contains("stress-test projection"));
        assertTrue(experimentCard.contains("Hana Relief Eligible"));

        String impactedCohorts = Files.readString(summaryDir.resolve("impacted-cohorts.csv"));
        assertTrue(impactedCohorts.contains("projectedLiquidity,subsidyAmount,deltaLiquidity,hardshipScopeReason,residualHardshipStatus"));
        assertTrue(impactedCohorts.contains("\"-205.0\",\"650.0\",\"650.0\",\"subsidy_received\",\"residual_hardship\""));
    }

    private void writeGeneratedCohortFixture(String cohortId, int customerCount) throws Exception {
        Path cohortDir = workspaceRoot.resolve("output/cohorts").resolve(cohortId);
        Files.createDirectories(cohortDir);
        StringBuilder customers = new StringBuilder("[");
        for (int index = 0; index < customerCount; index++) {
            if (index > 0) {
                customers.append(',');
            }
            customers.append("""
                    {
                      "personaName": "Generated Customer %d",
                      "domainId": "banking",
                      "model": "stub",
                      "interactionMode": "SIMULATION",
                      "autonomousDecisionEnabled": false,
                      "goals": [],
                      "initialAccounts": [
                        {
                          "name": "generated-account-%d",
                          "productCode": "TS001",
                          "balance": 120
                        }
                      ],
                      "scheduledWork": [
                        {
                          "workId": "salary-%d",
                          "description": "Salary Deposit",
                          "kind": "transaction",
                          "frequency": "FORTNIGHTLY",
                          "payload": {
                            "type": "DEPOSIT",
                            "amount": 1000
                          }
                        }
                      ]
                    }
                    """.formatted(index, index, index));
        }
        customers.append(']');
        Files.writeString(cohortDir.resolve("customers.json"), customers.toString());
    }

    private void writeGeneratedRateChangeCohortFixture(String cohortId) throws Exception {
        Path cohortDir = workspaceRoot.resolve("output/cohorts").resolve(cohortId);
        Files.createDirectories(cohortDir);
        Files.writeString(cohortDir.resolve("customers.json"), """
                [
                  {
                    "personaName": "Generated Customer 0",
                    "domainId": "banking",
                    "model": "stub",
                    "interactionMode": "SIMULATION",
                    "autonomousDecisionEnabled": false,
                    "goals": [],
                    "initialAccounts": [
                      {
                        "accountId": "cc-001",
                        "name": "Rewards Card",
                        "productCode": "CC001",
                        "balance": -1200,
                        "limit": 3000,
                        "currency": "AUD"
                      }
                    ],
                    "scheduledWork": []
                  },
                  {
                    "personaName": "Generated Customer 1",
                    "domainId": "banking",
                    "model": "stub",
                    "interactionMode": "SIMULATION",
                    "autonomousDecisionEnabled": false,
                    "goals": [],
                    "initialAccounts": [
                      {
                        "accountId": "tx-001",
                        "name": "Everyday Account",
                        "productCode": "TX001",
                        "balance": 500,
                        "currency": "AUD"
                      }
                    ],
                    "scheduledWork": []
                  }
                ]
                """);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private Path finalizationReceiptPath(String runId) {
        return workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId)
                .resolve("finalization-receipt.json");
    }

    private static void createFinalizableRun(MutableContext context, String runId) {
        createFinalizableRun(context, runId, "PLATFORM_OWNED_V2");
    }

    private static void createFinalizableRun(
            MutableContext context,
            String runId,
            String finalizationMode) {
        Map<String, Object> parameters = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("presetId", "hardship-relief"),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                Map.entry("simulationHorizonDays", 1),
                Map.entry("cohortId", "banking-balanced-banking-100-finalization-test"),
                Map.entry("cohortTargetSize", 100),
                Map.entry("opportunityGenerationFinalizationMode", finalizationMode)));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle("CLOCK_RUNNING", 0.91, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                "RUNNING",
                System.currentTimeMillis(),
                null,
                null,
                metadata));
    }

    private static void createTerminalRun(
            MutableContext context,
            String runId,
            String terminalStatus,
            String finalizationMode) {
        Map<String, Object> parameters = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("presetId", "hardship-relief"),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                Map.entry("simulationHorizonDays", 1),
                Map.entry("opportunityGenerationFinalizationMode", finalizationMode)));
        Map<String, Object> metadata = BankingPolicyScenarioLabRunMetadata
                .queued(parameters)
                .withLifecycle(terminalStatus, 1.0, false, null, null, null)
                .writeTo(Map.of());
        context.createRun(new PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest(
                runId,
                BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "hardship-relief",
                terminalStatus,
                System.currentTimeMillis(),
                null,
                null,
                metadata));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubProviderFinalizationDependencies(
            RestTemplate restTemplate,
            String runId) {
        when(restTemplate.exchange(
                eq("http://agent-runtime.test/api/agents/runtime-state?simulationRunId=" + runId),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(runtimeStateResponse(runId, 0, 0, 0));
        when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.<String>argThat(url -> url != null
                        && url.startsWith(
                                "http://agent-runtime.test/api/agents/hybrid-decisions?simulationRunId="
                                        + runId)),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(opportunityHybridDecisionResponse(runId, 2));
        stubReconciledOpportunityReconciliation(restTemplate, runId, 2);
        when(restTemplate.exchange(
                startsWith("http://experiment-service:7457/api/simulation-runs/" + runId),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("runId", runId)));
    }

    private static Map<String, Object> finalizationRequest(String runId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (runId + BankingRemotePolicyWindTunnelService.PROVIDER_REVISION)
                            .getBytes(StandardCharsets.UTF_8));
            return Map.of("operationId", "scenario-lab-finalize:" + HexFormat.of().formatHex(digest));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Thread finalizationThread(
            String name,
            BankingPolicyWindTunnelExecutionProvider provider,
            MutableContext context,
            String runId,
            CountDownLatch start,
            AtomicReference<PolicyWindTunnelRunRecord> result,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                result.set(provider.controlRun(context, runId, "FINALIZE", finalizationRequest(runId)));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, name);
    }

    private static void invokeClockDrivenWorkerFailure(
            BankingPolicyWindTunnelExecutionProvider provider,
            PolicyWindTunnelExecutionContext context,
            String runId,
            Exception failure) throws Exception {
        Method handler = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "handleClockDrivenWorkerFailure",
                PolicyWindTunnelExecutionContext.class,
                String.class,
                Exception.class);
        handler.setAccessible(true);
        handler.invoke(provider, context, runId, failure);
    }

    private void writeSimulationRunCheckpointFixture(String runId) throws Exception {
        Path checkpointDir = workspaceRoot.resolve("output/simulation-runs").resolve(runId).resolve("checkpoints");
        Files.createDirectories(checkpointDir);
        Files.writeString(checkpointDir.resolve("checkpoint-001.json"), """
                {
                  "protocolVersion": "remote-domain.v1",
                  "operation": "execute-work",
                  "nextState": {
                    "schemaVersion": "banking.remote-state.v1",
                    "data": {
                      "domainId": "banking",
                      "agentId": "Generated Customer 0",
                      "personaName": "Generated Customer 0",
                      "lastAction": "execute-work",
                      "executedWorkCount": 3,
                      "appliedTransactionCount": 1,
                      "rejectedTransactionCount": 2,
                      "executionLog": [
                        {
                          "description": "Salary Deposit",
                          "status": "APPLIED"
                        },
                        {
                          "description": "Weekly Groceries",
                          "status": "REJECTED",
                          "failureMessage": "Insufficient funds"
                        }
                      ],
                      "transactionPostings": [
                        {
                          "description": "Salary Deposit"
                        }
                      ]
                    }
                  },
                  "runtimeMetadata": {
                    "domainId": "banking"
                  },
                  "statusView": {
                    "customerState": {
                      "agentId": "Generated Customer 0",
                      "currentBalance": 120.0
                    },
                    "recentTransactions": [
                      {
                        "description": "Salary Deposit"
                      }
                    ]
                  }
                }
                """);
    }

    private void writeCheckpointSampleWithLateRejections(String runId) throws Exception {
        Path checkpointDir = workspaceRoot.resolve("output/simulation-runs").resolve(runId).resolve("checkpoints");
        Files.createDirectories(checkpointDir);
        for (int index = 0; index < 130; index++) {
            boolean rejected = index >= 100;
            Files.writeString(checkpointDir.resolve("checkpoint-%03d.json".formatted(index)), """
                    {
                      "protocolVersion": "remote-domain.v1",
                      "operation": "execute-work",
                      "nextState": {
                        "schemaVersion": "banking.remote-state.v1",
                        "data": {
                          "domainId": "banking",
                          "agentId": "Generated Customer %1$d",
                          "personaName": "Generated Customer %1$d",
                          "lastAction": "execute-work",
                          "executedWorkCount": 1,
                          "appliedTransactionCount": %2$d,
                          "rejectedTransactionCount": %3$d,
                          "executionLog": [
                            {
                              "description": "Scheduled payment",
                              "status": "%4$s"
                            }
                          ]
                        }
                      },
                      "runtimeMetadata": {
                        "domainId": "banking"
                      },
                      "statusView": {
                        "customerState": {
                          "agentId": "Generated Customer %1$d",
                          "currentBalance": 120.0
                        }
                      }
                    }
                    """.formatted(
                    index,
                    rejected ? 0 : 1,
                    rejected ? 1 : 0,
                    rejected ? "REJECTED" : "APPLIED"));
        }
    }

    private void writeRepeatedRejectedCheckpointSnapshots(String runId) throws Exception {
        Path checkpointDir = workspaceRoot.resolve("output/simulation-runs").resolve(runId).resolve("checkpoints");
        Files.createDirectories(checkpointDir);
        for (int index = 0; index < 30; index++) {
            Files.writeString(checkpointDir.resolve("checkpoint-%03d.json".formatted(index)), """
                    {
                      "protocolVersion": "remote-domain.v1",
                      "operation": "execute-work",
                      "nextState": {
                        "schemaVersion": "banking.remote-state.v1",
                        "data": {
                          "domainId": "banking",
                          "agentId": "Repeated Customer",
                          "personaName": "Repeated Customer",
                          "lastAction": "execute-work",
                          "executedWorkCount": 1,
                          "appliedTransactionCount": 0,
                          "rejectedTransactionCount": 1,
                          "executionLog": [
                            {
                              "workId": "same-rejected-work",
                              "occurrenceKey": "same-rejected-work-2026-01",
                              "description": "Scheduled payment",
                              "status": "REJECTED"
                            }
                          ]
                        }
                      },
                      "runtimeMetadata": {
                        "domainId": "banking"
                      },
                      "statusView": {
                        "customerState": {
                          "agentId": "Repeated Customer",
                          "currentBalance": 120.0
                        }
                      }
                    }
                    """);
        }
    }

    private void writeAnonymousRejectedCheckpointSnapshots(String runId) throws Exception {
        Path checkpointDir = workspaceRoot.resolve("output/simulation-runs").resolve(runId).resolve("checkpoints");
        Files.createDirectories(checkpointDir);
        for (int index = 0; index < 30; index++) {
            Files.writeString(checkpointDir.resolve("checkpoint-%03d.json".formatted(index)), """
                    {
                      "protocolVersion": "remote-domain.v1",
                      "operation": "execute-work",
                      "nextState": {
                        "schemaVersion": "banking.remote-state.v1",
                        "data": {
                          "domainId": "banking",
                          "agentId": "Anonymous Customer",
                          "personaName": "Anonymous Customer",
                          "lastAction": "execute-work",
                          "executedWorkCount": 1,
                          "appliedTransactionCount": 0,
                          "rejectedTransactionCount": 0,
                          "executionLog": [
                            {
                              "description": "Scheduled payment",
                              "status": "REJECTED"
                            }
                          ]
                        }
                      },
                      "runtimeMetadata": {
                        "domainId": "banking"
                      },
                      "statusView": {
                        "customerState": {
                          "agentId": "Anonymous Customer",
                          "currentBalance": 120.0
                        }
                      }
                    }
                    """);
        }
    }

    private void writeSeededAgentsFixture() throws Exception {
        writeSeededAgentsFixture("CC001");
    }

    private void writeSeededAgentsFixture(String productCode) throws Exception {
        Path seededAgents = workspaceRoot.resolve("scenarios/demo/seeded-cohorts/banking-demo-agents.json");
        Files.createDirectories(seededAgents.getParent());
        Files.writeString(seededAgents, """
                [
                  {
                    "personaName": "Nina Saver",
                    "goals": [],
                    "initialAccounts": [
                      {
                        "name": "nina-card",
                        "productCode": "%s",
                        "balance": -500,
                        "limit": 5000
                      }
                    ]
                  }
                ]
                """.formatted(productCode));
    }

    private BankingPolicyScenarioExecutionRuntime runtimeWithUnavailableProductEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://simulator.test/api/products"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).body(""));

        return new BankingPolicyScenarioExecutionRuntime(
                new ObjectMapper(),
                workspaceRoot,
                "http://agent-runtime.test",
                "http://simulator.test",
                "http://simulator.test",
                restTemplate);
    }

    private String relative(Path path) {
        return workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static List<String> zipEntryNames(byte[] content) throws Exception {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content))) {
            List<String> entries = new java.util.ArrayList<>();
            for (var entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                entries.add(entry.getName());
            }
            return entries;
        }
    }

    private static String zipEntryText(byte[] content, String suffix) throws Exception {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (var entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                if (!entry.getName().endsWith(suffix)) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                zipInputStream.transferTo(output);
                return output.toString(StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing zip entry ending with " + suffix);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResponseEntity<List> hybridDecisionResponse(int decisionCount) {
        return hybridDecisionResponse(decisionCount, decisionCount + 1, decisionCount, 1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResponseEntity<List> realLlmHybridDecisionResponse() {
        List<Map<String, Object>> decisions = List.of(
                Map.of(
                        "simTime", "2026-01-01T00:00:00Z",
                        "actionType", "DECISION",
                        "action", "NO_ACTION",
                        "customerVoice", "I will wait today.",
                        "intent", "WAIT_AND_MONITOR",
                        "reasonCode", "NO_IMMEDIATE_NEED",
                        "fallbackApplied", false,
                        "responseFormat", "customer_decision_turn",
                        "contractSchemaVersion", "customer_decision_turn.v1"),
                Map.of(
                        "simTime", "2026-01-02T00:00:00Z",
                        "actionType", "DECISION",
                        "action", "CONTACT_BANK",
                        "customerVoice", "I will call the bank tomorrow if my account changes.",
                        "intent", "SEEK_SUPPORT",
                        "reasonCode", "LIMIT_PRESSURE",
                        "fallbackApplied", false,
                        "responseFormat", "customer_decision_turn",
                        "contractSchemaVersion", "customer_decision_turn.v1"));
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("personaName", "Real LLM Canary Customer");
        agent.put("segment", "credit");
        agent.put("eventCount", decisions.size());
        agent.put("totalDecisionCount", decisions.size());
        agent.put("decisionWindowCount", 7);
        agent.put("decisionOpportunityCount", 7);
        agent.put("decisionAdmittedCount", 2);
        agent.put("decisionDeferredCount", 5);
        agent.put("decisionDeferredByReason", Map.of("busyness_probability_skip", 5));
        agent.put("decisionWindowSummary", Map.of(
                "windowCount", 7,
                "opportunityCount", 7,
                "admittedCount", 2,
                "deferredCount", 5,
                "deferredByReason", Map.of("busyness_probability_skip", 5)));
        agent.put("fallbackCount", 0);
        agent.put("effectiveProvider", "openai");
        agent.put("effectiveModel", "gemini-2.5-flash-lite");
        agent.put("decisions", decisions);
        return ResponseEntity.ok((List) List.of(agent));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResponseEntity<List> hybridDecisionResponse(
            int decisionCount,
            int opportunityCount,
            int admittedCount,
            int deferredCount) {
        return hybridDecisionResponse(1, decisionCount, opportunityCount, admittedCount, deferredCount);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResponseEntity<List> hybridDecisionResponse(
            int agentCount,
            int decisionCountPerAgent,
            int opportunityCountPerAgent,
            int admittedCountPerAgent,
            int deferredCountPerAgent) {
        List<Map<String, Object>> agents = new java.util.ArrayList<>();
        for (int agentIndex = 0; agentIndex < agentCount; agentIndex++) {
            agents.add(hybridDecisionAgent(
                    "Generated Customer " + agentIndex,
                    decisionCountPerAgent,
                    opportunityCountPerAgent,
                    admittedCountPerAgent,
                    deferredCountPerAgent));
        }
        return ResponseEntity.ok((List) agents);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResponseEntity<List> opportunityHybridDecisionResponse(String runId, int decisionCount) {
        Map<String, Object> agent = hybridDecisionAgent(
                "Generated Customer 0",
                decisionCount,
                decisionCount + 1,
                decisionCount,
                1);
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (int decisionIndex = 0; decisionIndex < decisionCount; decisionIndex++) {
            decisions.add(Map.ofEntries(
                    Map.entry("action", "ASK_FOR_HELP"),
                    Map.entry("reason", "Stub decision recorded for no-token Scenario Lab proof."),
                    Map.entry("fallbackApplied", false),
                    Map.entry("responseFormat", "structured"),
                    Map.entry("contractSchemaVersion", "hybrid_micro_decision_v1"),
                    Map.entry("opportunityId", "opportunity-" + (decisionIndex + 1)),
                    Map.entry("opportunityAttempt", 1),
                    Map.entry("opportunityDecisionContractValid", true)));
        }
        agent.put("decisions", decisions);
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of(
                "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED")) {
            statusCounts.put(status, "SUCCEEDED".equals(status) ? (long) decisionCount : 0L);
        }
        agent.put("agentId", "runtime-1");
        agent.put("runtimeIdentity", "runtime-1");
        agent.put("opportunityAudit", Map.ofEntries(
                Map.entry("schemaVersion", "customer_decision_opportunity_agent_audit.v1"),
                Map.entry("simulationRunId", runId),
                Map.entry("agentId", "runtime-1"),
                Map.entry("generatedOpportunities", decisionCount),
                Map.entry("terminalOpportunities", decisionCount),
                Map.entry("remainingBacklog", 0),
                Map.entry("admittedOpportunities", decisionCount),
                Map.entry("totalAttempts", decisionCount),
                Map.entry("validDecisions", decisionCount),
                Map.entry("recoverableRecordedDecisions", 0),
                Map.entry("succeededWithoutDecisionEvidence", 0),
                Map.entry("decisionEvidenceGaps", 0),
                Map.entry("statusCounts", statusCounts),
                Map.entry("deferredReasonCounts", Map.of()),
                Map.entry("historicalDeferralCount", 0),
                Map.entry("historicalDeferralReasonCounts", Map.of()),
                Map.entry("firstScheduledSimTime", "2026-07-11T00:00:00Z"),
                Map.entry("lastScheduledSimTime", "2026-07-11T00:00:00Z"),
                Map.entry("asOf", "2026-07-12T00:00:00Z")));
        return ResponseEntity.ok((List) List.of(agent));
    }

    private static Map<String, Object> opportunityDecisionAudit(
            BankingPolicyWindTunnelExecutionProvider provider,
            List<Map<String, Object>> agents,
            int decisionCount) throws Exception {
        Method totalsMethod = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod(
                "buildOpportunityLedgerTotals",
                List.class);
        totalsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> opportunityTotals =
                (Map<String, Object>) totalsMethod.invoke(provider, agents);
        return Map.of(
                "summary", Map.of(
                        "decisionCount", decisionCount,
                        "opportunityLedger", opportunityTotals),
                "totals", Map.of("decisionCount", decisionCount),
                "agents", agents);
    }

    private static Map<String, Object> hybridDecisionAgent(
            String personaName,
            int decisionCount,
            int opportunityCount,
            int admittedCount,
            int deferredCount) {
        List<Map<String, Object>> decisions = new java.util.ArrayList<>();
        if (decisionCount > 0) {
            decisions.add(Map.of(
                    "action", "ASK_FOR_HELP",
                    "reason", "Stub decision recorded for no-token Scenario Lab proof.",
                    "fallbackApplied", false,
                    "responseFormat", "structured",
                    "contractSchemaVersion", "hybrid_micro_decision_v1"));
        }
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("personaName", personaName);
        agent.put("segment", "everyday");
        agent.put("eventCount", decisionCount);
        agent.put("totalDecisionCount", decisionCount);
        agent.put("decisionWindowCount", opportunityCount);
        agent.put("decisionOpportunityCount", opportunityCount);
        agent.put("decisionAdmittedCount", admittedCount);
        agent.put("decisionDeferredCount", deferredCount);
        agent.put("decisionDeferredByReason", Map.of("busyness_probability_skip", deferredCount));
        agent.put("decisionWindowSummary", Map.of(
                "windowCount", opportunityCount,
                "opportunityCount", opportunityCount,
                "admittedCount", admittedCount,
                "deferredCount", deferredCount,
                "deferredByReason", Map.of("busyness_probability_skip", deferredCount)));
        agent.put("fallbackCount", 0);
        agent.put("effectiveProvider", "stub");
        agent.put("effectiveModel", "stub");
        agent.put("decisions", decisions);
        return agent;
    }

    private static ResponseEntity<Map> runtimeStateResponse(
            int activeTickTasks,
            int runningAgents,
            int pendingAgents) {
        return runtimeStateResponse(
                "clock-driven-settle-test",
                activeTickTasks,
                runningAgents,
                pendingAgents);
    }

    private static ResponseEntity<Map> runtimeStateResponse(
            String simulationRunId,
            int activeTickTasks,
            int runningAgents,
            int pendingAgents) {
        return ResponseEntity.ok((Map) Map.of(
                "schemaVersion", "agent_runtime_state_v1",
                "scope", "simulationRun",
                "simulationRunId", simulationRunId,
                "activeAgents", 100,
                "activeTickTasks", activeTickTasks,
                "runningAgents", runningAgents,
                "pendingAgents", pendingAgents,
                "idle", activeTickTasks <= 0 && runningAgents <= 0 && pendingAgents <= 0));
    }

    private static Map<String, Object> contractScorecard() {
        return Map.of(
                "schemaVersion", "domain_outcome_scorecard.v1",
                "scorecardId", "contract.remote-scorecard.v1",
                "label", "Contract supplied scorecard",
                "version", "0.1.0",
                "metrics", List.of(Map.of(
                        "metricId", "runtimeEvidenceSignals",
                        "label", "Runtime evidence signals",
                        "valueExpression", "metrics.evidenceSignalCount",
                        "evidenceRef", "simulationRunEvidencePack")),
                "recommendationRules", List.of(
                        Map.of(
                                "recommendation", "WATCH",
                                "expression", "metrics.evidenceSignalCount > 0",
                                "reason", "Runtime evidence was captured.",
                                "evidenceRef", "simulationRunEvidencePack"),
                        Map.of(
                                "recommendation", "REVIEW",
                                "expression", "true",
                                "reason", "Human review required.",
                        "evidenceRef", "simulationRunEvidencePack")));
    }

    private static Map<String, Object> rejectedWorkScorecard() {
        return Map.of(
                "schemaVersion", "domain_outcome_scorecard.v1",
                "scorecardId", "contract.rejected-work-scorecard.v1",
                "label", "Rejected work scorecard",
                "version", "0.1.0",
                "metrics", List.of(Map.of(
                        "metricId", "rejectedWorkItems",
                        "label", "Rejected work items",
                        "valueExpression", "metrics.rejectedWorkCount",
                        "evidenceRef", "branchStateProjectionJson")),
                "recommendationRules", List.of(
                        Map.of(
                                "recommendation", "BLOCK",
                                "expression", "metrics.rejectedWorkCount >= 25",
                                "reason", "Rejected work crosses the blocking threshold.",
                                "evidenceRef", "branchStateProjectionJson"),
                        Map.of(
                                "recommendation", "WATCH",
                                "expression", "true",
                                "reason", "Rejected work is below the blocking threshold.",
                                "evidenceRef", "branchStateProjectionJson")));
    }

    private static Map<String, Object> localFallbackScorecard() {
        return Map.of(
                "schemaVersion", "domain_outcome_scorecard.v1",
                "scorecardId", "contract.local-fallback-scorecard.v1",
                "label", "Local fallback scorecard",
                "version", "0.1.0",
                "recommendationRules", List.of(Map.of(
                        "recommendation", "REVIEW",
                        "expression", "true",
                        "reason", "Local fallback should only apply when remote metadata is unavailable.",
                        "evidenceRef", "simulationRunEvidencePack")));
    }

    private static Map<String, Object> emptyOpportunityReconciliation(String runId) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of(
                "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED")) {
            statusCounts.put(status, 0L);
        }
        return Map.ofEntries(
                Map.entry("schemaVersion", "customer_decision_opportunity_reconciliation.v1"),
                Map.entry("simulationRunId", runId),
                Map.entry("status", "EMPTY"),
                Map.entry("ledgerReconciled", true),
                Map.entry("generationMode", "EVENT_DRIVEN_DECISION_TRIGGERS"),
                Map.entry("generationSealed", true),
                Map.entry("observedWorkWindows", 1),
                Map.entry("generationObservedAt", "2026-07-11T00:00:00Z"),
                Map.entry("generationSealedAt", "2026-07-12T00:00:00Z"),
                Map.entry("generationSealedReplayContractVersion", 7),
                Map.entry("generationSealedReplayFenceDigest", "a".repeat(64)),
                Map.entry("generationSealedReplayFenceLeaseOwner", "scenario-lab-finalizer:test"),
                Map.entry("generationSealedReplayFenceExpiresAtEpochMs", 1_800_000_000_000L),
                Map.entry("initializationManifestStatus", "CLOSED"),
                Map.entry("expectedInitializationAgents", 1),
                Map.entry("registeredInitializationAgents", 1),
                Map.entry("resolvedInitializationAgents", 1),
                Map.entry("tickFailureCount", 0),
                Map.entry("tickFailureLedgerCount", 0),
                Map.entry("expectedGeneratedOpportunities", 0),
                Map.entry("rejectedTriggers", 0),
                Map.entry("migrationAnomalies", 0),
                Map.entry("generatedOpportunities", 0),
                Map.entry("terminalOpportunities", 0),
                Map.entry("remainingBacklog", 0),
                Map.entry("validDecisions", 0),
                Map.entry("recoverableRecordedDecisions", 0),
                Map.entry("succeededWithoutDecisionEvidence", 0),
                Map.entry("decisionEvidenceGaps", 0),
                Map.entry("statusCounts", statusCounts),
                Map.entry("asOf", "2026-07-12T00:00:00Z"));
    }

    private static Map<String, Object> unsealedOpportunityReconciliation(String runId) {
        Map<String, Object> reconciliation = new LinkedHashMap<>(emptyOpportunityReconciliation(runId));
        reconciliation.put("ledgerReconciled", false);
        reconciliation.put("generationSealed", false);
        reconciliation.put("observedWorkWindows", 0L);
        reconciliation.put("generationSealedAt", null);
        reconciliation.put("generationSealedReplayContractVersion", null);
        reconciliation.put("generationSealedReplayFenceDigest", null);
        reconciliation.put("generationSealedReplayFenceLeaseOwner", null);
        reconciliation.put("generationSealedReplayFenceExpiresAtEpochMs", null);
        return reconciliation;
    }

    private static void stubSealedOpportunityReconciliation(RestTemplate restTemplate, String runId) {
        when(restTemplate.getForObject(
                eq("http://agent-runtime.test/api/agents/simulation-runs/"
                        + runId
                        + "/decision-opportunities/reconciliation"),
                eq(Map.class)))
                .thenReturn(emptyOpportunityReconciliation(runId));
    }

    private static void stubReconciledOpportunityReconciliation(
            RestTemplate restTemplate,
            String runId,
            int succeeded) {
        Map<String, Object> reconciliation = reconciledOpportunityReconciliation(runId, succeeded);
        when(restTemplate.getForObject(
                eq("http://agent-runtime.test/api/agents/simulation-runs/"
                        + runId
                        + "/decision-opportunities/reconciliation"),
                eq(Map.class)))
                .thenReturn(reconciliation);
    }

    private static Map<String, Object> reconciledOpportunityReconciliation(
            String runId,
            int succeeded) {
        Map<String, Object> reconciliation = new LinkedHashMap<>(emptyOpportunityReconciliation(runId));
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of(
                "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED")) {
            statusCounts.put(status, "SUCCEEDED".equals(status) ? (long) succeeded : 0L);
        }
        reconciliation.put("status", "RECONCILED");
        reconciliation.put("expectedGeneratedOpportunities", succeeded);
        reconciliation.put("generatedOpportunities", succeeded);
        reconciliation.put("terminalOpportunities", succeeded);
        reconciliation.put("validDecisions", succeeded);
        reconciliation.put("statusCounts", statusCounts);
        return reconciliation;
    }

    private static Object lifecycleLock(
            BankingPolicyWindTunnelExecutionProvider provider,
            String runId) throws Exception {
        Method method = BankingPolicyWindTunnelExecutionProvider.class.getDeclaredMethod("lifecycleLock", String.class);
        method.setAccessible(true);
        return method.invoke(provider, runId);
    }

    private static Map<String, Object> withPinnedActivationSettings(Map<String, Object> source) {
        try {
            Map<String, Object> settings = Map.of(
                    "schemaVersion", "scenario_lab_pinned_cohort_activation.v1",
                    "agentDecisionMode", String.valueOf(source.get("agentDecisionMode")),
                    "agentDecisionCadence", String.valueOf(source.getOrDefault(
                            "agentDecisionCadence", "DAILY")));
            byte[] content = new ObjectMapper().writeValueAsBytes(settings);
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            Map<String, Object> request = new LinkedHashMap<>(source);
            request.put("activationSettingsDigest", digest);
            request.put("activationSettingsSnapshot", Map.of(
                    "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                    "contentType", "application/json",
                    "encoding", "base64url",
                    "sha256", digest,
                    "content", Base64.getUrlEncoder().withoutPadding().encodeToString(content)));
            return request;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create pinned activation test settings", failure);
        }
    }

    private static final class MutableContext implements PolicyWindTunnelExecutionContext {
        private final Path workspaceRoot;
        private final Map<String, MutableRun> runs = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> registeredArtifacts = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> packMetadata = new LinkedHashMap<>();
        private PolicyWindTunnelRunUpdate readyToFinalizePublication;
        private int failArtifactRegistrationOnAttempt;
        private int artifactRegistrationAttempts;
        private int failRunUpdateOnAttempt;
        private int runUpdateAttempts;

        private MutableContext(Path workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        private void putPackMetadata(String packId, Map<String, Object> metadata) {
            packMetadata.put(packId, metadata);
        }

        private synchronized void failArtifactRegistrationOnAttempt(int attempt) {
            failArtifactRegistrationOnAttempt = attempt;
            artifactRegistrationAttempts = 0;
        }

        private synchronized void failRunUpdateOnAttempt(int attempt) {
            failRunUpdateOnAttempt = attempt;
            runUpdateAttempts = 0;
        }

        @Override
        public Path workspaceRoot() {
            return workspaceRoot;
        }

        @Override
        public String agentRuntimeUrl() {
            return "http://agent-runtime.test";
        }

        @Override
        public String simulatorUrl() {
            return "http://simulator.test";
        }

        @Override
        public String dependencyUrl(String dependencyId) {
            return "http://banking.test";
        }

        @Override
        public Map<String, Object> packMetadata(String packId) {
            return packMetadata.getOrDefault(packId, Map.of());
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
        public synchronized void createRun(PolicyWindTunnelRunCreateRequest request) {
            OffsetDateTime now = OffsetDateTime.now();
            runs.put(request.runId(), new MutableRun(
                    request.runId(),
                    request.status(),
                    request.summaryPath(),
                    request.bundlePath(),
                    null,
                    request.startedAtEpochMs(),
                    now,
                    now,
                    new LinkedHashMap<>(request.metadata())));
        }

        @Override
        public synchronized Optional<PolicyWindTunnelStoredRun> findRun(String runId) {
            MutableRun run = runs.get(runId);
            if (run == null) {
                return Optional.empty();
            }
            return Optional.of(new PolicyWindTunnelStoredRun(
                    run.runId,
                    run.status,
                    run.summaryPath,
                    run.bundlePath,
                    run.evaluationRunId,
                    run.startedAtEpochMs,
                    run.createdAt,
                    run.updatedAt,
                    run.metadata));
        }

        @Override
        public synchronized void updateRun(String runId, PolicyWindTunnelRunUpdate update) {
            runUpdateAttempts += 1;
            if (failRunUpdateOnAttempt == runUpdateAttempts) {
                failRunUpdateOnAttempt = 0;
                throw new IllegalStateException("simulated run update interruption");
            }
            MutableRun run = runs.get(runId);
            if (run == null) {
                throw new IllegalArgumentException("Unknown run " + runId);
            }
            if ("READY_TO_FINALIZE".equals(update.status())) {
                readyToFinalizePublication = new PolicyWindTunnelRunUpdate(
                        update.status(),
                        update.summaryPath(),
                        update.bundlePath(),
                        update.completedAtEpochMs(),
                        new LinkedHashMap<>(update.metadata()));
            }
            if (update.status() != null) {
                run.status = update.status();
            }
            if (update.summaryPath() != null) {
                run.summaryPath = update.summaryPath();
            }
            if (update.bundlePath() != null) {
                run.bundlePath = update.bundlePath();
            }
            if (update.metadata() != null) {
                run.metadata = new LinkedHashMap<>(update.metadata());
            }
            run.updatedAt = OffsetDateTime.now();
        }

        private synchronized Optional<PolicyWindTunnelRunUpdate> readyToFinalizePublication() {
            return Optional.ofNullable(readyToFinalizePublication);
        }

        @Override
        public synchronized void registerArtifact(
                String runId,
                String artifactType,
                String artifactRef,
                String contentType) {
            artifactRegistrationAttempts += 1;
            if (failArtifactRegistrationOnAttempt == artifactRegistrationAttempts) {
                failArtifactRegistrationOnAttempt = 0;
                throw new IllegalStateException("simulated artifact registration interruption");
            }
            registeredArtifacts.computeIfAbsent(runId, ignored -> new LinkedHashMap<>())
                    .put(artifactType, artifactRef);
        }

        @Override
        public synchronized Map<String, String> registeredArtifacts(String runId) {
            return Map.copyOf(registeredArtifacts.getOrDefault(runId, Map.of()));
        }

        @Override
        public void linkEvaluation(String runId, String evaluationRunId) {
            MutableRun run = runs.get(runId);
            if (run != null) {
                run.evaluationRunId = evaluationRunId;
            }
        }

        @Override
        public Map<String, Object> evaluateSuite(Map<String, Object> suite, String baselineEvaluationRunId) {
            String runId = String.valueOf(suite.getOrDefault("runId", "unknown-run"));
            return Map.of(
                    "schemaVersion", "evaluation.v1",
                    "run", Map.of(
                            "runId", runId,
                            "manifestPath", String.valueOf(suite.getOrDefault("manifestPath", "banking-scenario-lab")),
                            "generatedAt", "2026-05-06T00:00:00Z",
                            "endpoints", Map.of("scenarioLab", "remote-policy-wind-tunnel-provider"),
                            "canonicalArtifactRef", "output/evaluation/" + runId + ".json"),
                    "summary", Map.of(
                            "overallVerdict", "PASS",
                            "overallScore", 0.92,
                            "scenarioCount", 1,
                            "passedScenarios", 1,
                            "failedScenarios", 0,
                            "dimensions", List.of(Map.of(
                                    "dimension", "POLICY",
                                    "score", 0.92,
                                    "verdict", "PASS",
                                    "confidence", Map.of("score", 0.8, "label", "HIGH"),
                                    "rationale", "Synthetic evaluator contract proof."))),
                    "scenarioResults", List.of(Map.of(
                            "scenarioId", "contract-shape-proof",
                            "status", "PASS",
                            "category", "contract",
                            "overallVerdict", "PASS",
                            "overallScore", 0.92,
                            "dimensionScores", List.of(Map.of(
                                    "dimension", "POLICY",
                                    "score", 0.92,
                                    "verdict", "PASS",
                                    "confidence", Map.of("score", 0.8, "label", "HIGH"),
                                    "rationale", "Full evaluation envelope remains consumable.")))),
                    "generatedAt", "2026-05-06T00:00:01Z");
        }

        @Override
        public Map<String, Object> compareEvaluations(
                Map<String, Object> baselineReport,
                Map<String, Object> candidateReport) {
            String baselineRunId = String.valueOf(((Map<?, ?>) baselineReport.get("run")).get("runId"));
            String candidateRunId = String.valueOf(((Map<?, ?>) candidateReport.get("run")).get("runId"));
            return Map.of(
                    "baselineRunId", baselineRunId,
                    "candidateRunId", candidateRunId,
                    "overallDelta", 0.0,
                    "dimensionDeltas", List.of(Map.of(
                            "dimension", "POLICY",
                            "delta", 0.0,
                            "baselineScore", 0.92,
                            "candidateScore", 0.92)),
                    "scenarioDeltas", List.of(Map.of(
                            "scenarioId", "contract-shape-proof",
                            "verdictChanged", false,
                            "overallDelta", 0.0)),
                    "thresholdBreaches", List.of(Map.of(
                            "key", "overallDelta",
                            "delta", 0.0,
                            "threshold", -0.1,
                            "severity", "INFO",
                            "details", "No regression threshold breach.")));
        }

        private static final class MutableRun {
            private final String runId;
            private String status;
            private String summaryPath;
            private String bundlePath;
            private String evaluationRunId;
            private final Long startedAtEpochMs;
            private final OffsetDateTime createdAt;
            private OffsetDateTime updatedAt;
            private Map<String, Object> metadata;

            private MutableRun(
                    String runId,
                    String status,
                    String summaryPath,
                    String bundlePath,
                    String evaluationRunId,
                    Long startedAtEpochMs,
                    OffsetDateTime createdAt,
                    OffsetDateTime updatedAt,
                    Map<String, Object> metadata) {
                this.runId = runId;
                this.status = status;
                this.summaryPath = summaryPath;
                this.bundlePath = bundlePath;
                this.evaluationRunId = evaluationRunId;
                this.startedAtEpochMs = startedAtEpochMs;
                this.createdAt = createdAt;
                this.updatedAt = updatedAt;
                this.metadata = metadata;
            }
        }
    }

    private record FakeContext(
            Path workspaceRoot,
            String runId,
            String summaryPath,
            Map<String, String> registeredArtifacts) implements PolicyWindTunnelExecutionContext {

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
        }

        @Override
        public Optional<PolicyWindTunnelStoredRun> findRun(String candidateRunId) {
            if (!runId.equals(candidateRunId)) {
                return Optional.empty();
            }
            return Optional.of(new PolicyWindTunnelStoredRun(
                    runId,
                    "COMPLETED",
                    summaryPath,
                    "/api/experiment-runs/" + runId + "/scenario-lab/bundle",
                    "eval-1",
                    1L,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    Map.of()));
        }

        @Override
        public void updateRun(String runId, PolicyWindTunnelRunUpdate update) {
        }

        @Override
        public void registerArtifact(String runId, String artifactType, String artifactRef, String contentType) {
        }

        @Override
        public Map<String, String> registeredArtifacts(String candidateRunId) {
            return runId.equals(candidateRunId) ? registeredArtifacts : Map.of();
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
    }
}
