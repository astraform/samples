package banking.simulator.remote.domain;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;
import ai.astraform.remote.domain.openapi.models.RemoteDomainAgentContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionTarget;
import ai.astraform.remote.domain.openapi.models.RemoteDomainHostContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTimeWindow;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelExecutionProvider;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelManifest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

/** Independent process fixture that runs the banking V2 provider against shared durable state. */
public final class BankingPolicyWindTunnelV2ProviderProcess {
    private static final String BASE_PATH = "/policy-wind-tunnel";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final Path workspaceRoot;
    private final Path runRoot;
    private final ObjectMapper objectMapper;
    private final BankingRemotePolicyWindTunnelExecutionContext context;
    private final BankingPolicyWindTunnelExecutionProvider provider;
    private final BankingRemotePolicyWindTunnelService metadataService;
    private final ConformanceRestTemplate conformanceRestTemplate;
    private final ConformanceExecutor conformanceExecutor;
    private final HttpServer nativeExperimentServer;
    private final java.util.concurrent.ExecutorService nativeExperimentExecutor;
    private final java.util.concurrent.atomic.AtomicInteger nativeExperimentRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, HeldLock> storageLocks = new ConcurrentHashMap<>();

    private BankingPolicyWindTunnelV2ProviderProcess(Path workspaceRoot, String processRole) throws Exception {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.runRoot = this.workspaceRoot.resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT).normalize();
        this.objectMapper = new ObjectMapper().rebuild()
                .findAndAddModules()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        this.context = BankingRemotePolicyWindTunnelExecutionContext.fromConfiguration(
                objectMapper,
                this.workspaceRoot.toString(),
                "http://agent-runtime.test",
                "http://simulator.test",
                "http://banking-domain.test");
        if (!context.configurePlatformOwnedV2Storage(true)) {
            throw new IllegalStateException("shared durable storage verification failed");
        }
        this.metadataService = new BankingRemotePolicyWindTunnelService(
                objectMapper,
                this.workspaceRoot.toString(),
                "http://agent-runtime.test",
                "http://simulator.test",
                "http://banking-domain.test",
                false);
        this.conformanceExecutor = new ConformanceExecutor();
        this.conformanceRestTemplate = new ConformanceRestTemplate(runRoot, objectMapper);
        this.nativeExperimentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.nativeExperimentExecutor = Executors.newCachedThreadPool();
        nativeExperimentServer.setExecutor(nativeExperimentExecutor);
        nativeExperimentServer.createContext("/api/simulation-runs/", exchange -> {
            nativeExperimentRequests.incrementAndGet();
            try {
                String runId = exchange.getRequestURI().getPath().substring("/api/simulation-runs/".length());
                requireSafeId(runId);
                if (!"PATCH".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "native fixture accepts completion PATCH only"));
                    return;
                }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.startsWith("application/json")) {
                    sendJson(exchange, 415, Map.of("error", "application/json required"));
                    return;
                }
                byte[] content = exchange.getRequestBody().readAllBytes();
                Map<String, Object> payload = objectMapper.readValue(content, MAP_TYPE);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (!runId.equals(payload.get("runId")) || !String.valueOf(payload.get("operationId")).equals(key)) {
                    sendJson(exchange, 400, Map.of("error", "native completion identity mismatch"));
                    return;
                }
                conformanceRestTemplate.nativeNotification(runId, content, key);
                sendJson(exchange, 200, Map.of("runId", runId));
            } catch (ResourceAccessException rejected) {
                sendJson(exchange, 408, Map.of("error", "recorded owned fixture delivery failure"));
            } catch (Exception error) {
                sendJson(exchange, 500, Map.of("error", error.getClass().getSimpleName()));
            } finally { exchange.close(); }
        });
        nativeExperimentServer.start();
        var nativeClient = new ai.astraform.platform.client.ApiClient()
                .setConnectTimeout(java.time.Duration.ofSeconds(5))
                .setReadTimeout(java.time.Duration.ofSeconds(30));
        nativeClient.updateBaseUri("http://127.0.0.1:" + nativeExperimentServer.getAddress().getPort());
        BiConsumer<String, String> preparedHook = this::afterPrepared;
        Constructor<BankingPolicyWindTunnelExecutionProvider> constructor =
                BankingPolicyWindTunnelExecutionProvider.class.getDeclaredConstructor(
                        ObjectMapper.class,
                        RestTemplate.class,
                        Executor.class,
                        LongSupplier.class,
                        Runnable.class,
                        BiConsumer.class,
                        ai.astraform.platform.client.ApiClient.class);
        constructor.setAccessible(true);
        this.provider = constructor.newInstance(
                objectMapper,
                conformanceRestTemplate,
                conformanceExecutor,
                (LongSupplier) System::currentTimeMillis,
                (Runnable) () -> { },
                preparedHook,
                nativeClient);
        this.provider.recoverPendingFinalizationNotifications(context);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("expected workspace root, ready file, and process role");
        }
        Path workspaceRoot = Path.of(args[0]);
        Path readyFile = Path.of(args[1]);
        Files.createDirectories(workspaceRoot);
        BankingPolicyWindTunnelV2ProviderProcess fixture =
                new BankingPolicyWindTunnelV2ProviderProcess(workspaceRoot, args[2]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(BASE_PATH, fixture::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            fixture.close();
            server.stop(0);
            stopped.countDown();
        }, "banking-v2-conformance-shutdown"));
        server.start();
        atomicWrite(
                readyFile,
                Integer.toString(server.getAddress().getPort()).getBytes(StandardCharsets.US_ASCII));
        stopped.await();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ((BASE_PATH + "/pack").equals(path) && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, platformOwnedV2Pack());
                return;
            }
            requirePinnedRevision(exchange);
            if ((BASE_PATH + "/presets").equals(path) && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, metadataService.presets());
                return;
            }
            if ((BASE_PATH + "/conformance-v2/native-sdk-status").equals(path)) {
                sendJson(exchange, 200, Map.of("requests", nativeExperimentRequests.get()));
                return;
            }
            String storagePrefix = BASE_PATH + "/conformance-v2/storage/";
            if (path.startsWith(storagePrefix)) {
                handleStorage(exchange, path.substring(storagePrefix.length()));
                return;
            }
            String controlPrefix = BASE_PATH + "/conformance-v2/runs/";
            if (path.startsWith(controlPrefix)) {
                handleConformanceControl(exchange, path.substring(controlPrefix.length()));
                return;
            }
            if ((BASE_PATH + "/runs").equals(path) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> request = new LinkedHashMap<>(readObject(exchange));
                boolean rateChangeAcceptance = Boolean.TRUE.equals(
                        request.remove("blackBoxRateChangeAcceptance"));
                if ("CLOCK_DRIVEN_HYBRID".equals(request.get("executionMode"))) {
                    request.putIfAbsent("prePolicyDays", 0);
                    request.putIfAbsent("postPolicyDays", request.get("simulationHorizonDays"));
                }
                String runId = String.valueOf(request.get("simulationRunId"));
                Supplier<PolicyWindTunnelRunRecord> launch = () -> provider.startRun(
                        context,
                        request,
                        exchange.getRequestHeaders().getFirst("X-Astraform-Execution-Role"));
                PolicyWindTunnelRunRecord result = rateChangeAcceptance
                        ? launch.get()
                        : conformanceExecutor.withLaunchExecutionSuppressed(launch);
                sendJson(exchange, 200, runRecord(result));
                return;
            }
            String runPrefix = BASE_PATH + "/runs/";
            if (!path.startsWith(runPrefix)) {
                sendJson(exchange, 404, Map.of("error", "not_found"));
                return;
            }
            String suffix = path.substring(runPrefix.length());
            int separator = suffix.indexOf('/');
            String runId = separator < 0 ? suffix : suffix.substring(0, separator);
            String operation = separator < 0 ? "" : suffix.substring(separator + 1);
            requireSafeId(runId);
            if (operation.isEmpty() && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, runRecord(provider.getRunStatus(context, runId)));
                return;
            }
            if ("lifecycle".equals(operation) && "POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> request = readObject(exchange);
                String action = String.valueOf(request.getOrDefault("action", ""));
                PolicyWindTunnelRunRecord controlled = provider.controlRun(
                        context,
                        runId,
                        action,
                        request,
                        exchange.getRequestHeaders().getFirst(
                                "X-Astraform-Opportunity-Correlation-Capability"));
                if ("FINALIZE".equals(action)) incrementFinalizeCount(runId);
                sendJson(exchange, 200, runRecord(controlled));
                return;
            }
            if (operation.startsWith("artifacts/") && "GET".equals(exchange.getRequestMethod())) {
                PolicyWindTunnelArtifactPayload artifact = provider.readArtifact(
                        context,
                        runId,
                        operation.substring("artifacts/".length()));
                sendBytes(exchange, 200, artifact.contentType(), artifact.content());
                return;
            }
            sendJson(exchange, 404, Map.of("error", "not_found"));
        } catch (ai.astraform.remote.domain.sdk.RemoteDomainProtocolException error) {
            sendJson(exchange, 400, Map.of("error", error.code(), "message", error.getMessage()));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, Map.of("error", error.getMessage()));
        } catch (Exception error) {
            sendJson(exchange, 500, Map.of("error", error.getClass().getSimpleName()));
        } finally {
            exchange.close();
        }
    }

    private void handleConformanceControl(HttpExchange exchange, String suffix) throws Exception {
        int separator = suffix.indexOf('/');
        if (separator <= 0) {
            sendJson(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        String runId = suffix.substring(0, separator);
        String operation = suffix.substring(separator + 1);
        requireSafeId(runId);
        if ("historical-published".equals(operation) && "POST".equals(exchange.getRequestMethod())) {
            seedHistoricalPublishedReceipt(runId);
            sendJson(exchange, 200, Map.of("seededHistoricalReceipt", true));
            return;
        }
        if ("ready".equals(operation) && "POST".equals(exchange.getRequestMethod())) {
            var stored = context.findRun(runId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown run"));
            Map<String, Object> metadata = new LinkedHashMap<>(stored.metadata());
            Map<String, Object> scenarioLab = new LinkedHashMap<>(map(metadata.get("scenarioLab")));
            scenarioLab.put("phase", "READY_TO_FINALIZE");
            scenarioLab.put("progress", 0.91d);
            scenarioLab.put("bundleReady", false);
            metadata.put("scenarioLab", scenarioLab);
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    "READY_TO_FINALIZE",
                    stored.summaryPath(),
                    stored.bundlePath(),
                    null,
                    metadata));
            sendJson(exchange, 200, Map.of("ready", true));
            return;
        }
        if ("legacy".equals(operation) && "POST".equals(exchange.getRequestMethod())) {
            Map<String, Object> parameters = Map.of(
                    "presetId", "hardship-relief",
                    "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V1");
            Map<String, Object> metadata = Map.of("scenarioLab", Map.of(
                    "phase", "COMPLETED",
                    "progress", 1.0d,
                    "bundleReady", true,
                    "parameters", parameters,
                    "phaseTimings", List.of(),
                    "logTail", List.of()));
            context.createRun(new PolicyWindTunnelRunCreateRequest(
                    runId,
                    BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                    "banking-hardship-relief",
                    "CANDIDATE",
                    "SEEDED_DETERMINISTIC",
                    "hardship-relief",
                    "COMPLETED",
                    System.currentTimeMillis(),
                    null,
                    null,
                    metadata));
            sendJson(exchange, 200, Map.of("created", true));
            return;
        }
        if ("notification-attempt".equals(operation)
                && "POST".equals(exchange.getRequestMethod())) {
            conformanceRestTemplate.recordNotificationProbe(
                    runId,
                    exchange.getRequestBody().readAllBytes());
            sendJson(exchange, 200, Map.of("recorded", true));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "not_found"));
    }


    /** Explicit saved historical evidence, created by existing pure file/hash/receipt owners.
     * This fixture is never labelled a newly executed native Scenario Lab run. */
    @SuppressWarnings("unchecked")
    private void seedHistoricalPublishedReceipt(String runId) throws Exception {
        Map<String, Object> parameters = Map.of("presetId", "hardship-relief",
                "executionMode", "CLOCK_DRIVEN_HYBRID", "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2",
                "agentDecisionMode", "STUB_LLM", "agentDecisionCadence", "EVENT_ONLY",
                "simulationHorizonDays", 1, "cohortTargetSize", 1);
        Map<String, Object> metadata = Map.of("scenarioLab", Map.of(
                "phase", "COMPLETED", "progress", 1.0d, "bundleReady", true, "parameters", parameters,
                "phaseTimings", List.of(), "logTail", List.of("Explicit saved historical receipt fixture")));
        context.createRun(new PolicyWindTunnelRunCreateRequest(runId, BankingPolicyWindTunnelManifest.EXPERIMENT_ID,
                "banking-hardship-relief", "CANDIDATE", "SEEDED_DETERMINISTIC", "hardship-relief", "COMPLETED",
                1_791_993_500_000L, null, null, metadata));
        var stored = context.findRun(runId).orElseThrow();
        Path directory = (Path) invokePure("finalizationSummaryDirectory", context, runId);
        Files.createDirectories(directory);
        Map<String, Object> artifactPaths = (Map<String, Object>) invokePure("finalizedHybridArtifactPaths", workspaceRoot, directory, runId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", "customer_outcome_simulation_summary.v1");
        summary.put("runId", runId); summary.put("runConfig", parameters); summary.put("artifacts", artifactPaths);
        summary.put("fixtureProvenance", "saved historical fixture; no current clock/native execution");
        summary.put("decisionReadiness", Map.of("status", "NOT_ASSESSED", "recommendation", "WATCH", "reason", "Stored evidence is not new native execution acceptance"));
        summary.put("decisionComparison", Map.of("comparisonId", runId + "-saved", "decisionQuestion", "Inspect saved evidence"));
        summary.put("simulationTimeline", Map.of("events", List.of(Map.of("eventId", "saved-observation", "eventType", "OBSERVATION"))));
        summary.put("sideEffectLedger", Map.of("assessment", "NOT_ASSESSED", "entries", List.of()));
        summary.put("branchStateProjection", Map.of("source", "stored-fixture", "branches", List.of(Map.of("balance", -1200, "minimumPayment", 48))));
        summary.put("domainOutcomeScore", Map.of("status", "NOT_ASSESSED"));
        summary.put("domainOutcomeScores", List.of(Map.of("status", "NOT_ASSESSED")));
        summary.put("hybridAgentAudit", Map.of("source", "stored-fixture", "assessment", "NOT_ASSESSED", "agents", List.of()));
        summary.put("checkpointSummaries", List.of(Map.of("checkpointId", "saved-checkpoint", "balance", -1200)));
        invokePure("writeClockDrivenHybridFinalizedArtifacts", directory, summary);
        Map<String, Object> manifest = (Map<String, Object>) invokePure("finalizationArtifactManifest", context, artifactPaths);
        if (manifest.size() != 14) throw new AssertionError("historical receipt must contain all 14 real artifacts");
        Map<String, Object> request = Map.of("action", "FINALIZE", "operationId", "scenario-lab-finalize:" +
                ConformanceRestTemplate.sha256((runId + BankingRemotePolicyWindTunnelService.PROVIDER_REVISION).getBytes(StandardCharsets.UTF_8)));
        Object operation = invokePure("finalizationOperation", request);
        Map<String, Object> updatedMetadata = (Map<String, Object>) invokePure("withFinalizationOperation", metadata, operation);
        java.lang.reflect.Method digest = operation.getClass().getDeclaredMethod("inputDigest"); digest.setAccessible(true);
        Map<String, Object> snapshot = (Map<String, Object>) invokePure("runSnapshot", stored, "COMPLETED",
                artifactPaths.get("summary"), "/api/experiment-runs/" + runId + "/scenario-lab/bundle", 1_791_993_600_000L, updatedMetadata);
        Map<String, Object> receipt = (Map<String, Object>) invokePure("finalizationReceipt", runId,
                "PLATFORM_OWNED_V2", operation, digest.invoke(operation), "PUBLISHED", snapshot, manifest);
        invokePure("attachCompletionNotificationPayload", context, receipt);
        invokePure("writeFinalizationReceipt", context, runId, receipt);
        invokePure("loadFinalizationReceipt", context, runId); // Validates every actual artifact byte length and hash.
    }

    private Object invokePure(String name, Object... arguments) throws Exception {
        java.lang.reflect.Method method = java.util.Arrays.stream(provider.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(provider, arguments); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw (Error) error.getCause();
        }
    }

    private void handleStorage(HttpExchange exchange, String suffix) throws Exception {
        int separator = suffix.indexOf('/');
        if (separator <= 0 || separator == suffix.length() - 1) {
            sendJson(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        String probeId = suffix.substring(0, separator);
        String resource = suffix.substring(separator + 1);
        requireSafeId(probeId);
        if ("lock".equals(resource) && "POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, Map.of("acquired", acquireStorageLock(probeId)));
            return;
        }
        if ("lock".equals(resource) && "DELETE".equals(exchange.getRequestMethod())) {
            releaseStorageLock(probeId);
            sendJson(exchange, 200, Map.of("released", true));
            return;
        }
        Path value = workspaceRoot.resolve("conformance-storage/values/" + probeId + ".bin");
        if ("value".equals(resource) && "PUT".equals(exchange.getRequestMethod())) {
            atomicWrite(value, exchange.getRequestBody().readAllBytes());
            sendJson(exchange, 200, Map.of("published", true));
            return;
        }
        if ("value".equals(resource) && "GET".equals(exchange.getRequestMethod())) {
            sendBytes(exchange, 200, "application/octet-stream", Files.readAllBytes(value));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "not_found"));
    }

    private boolean acquireStorageLock(String probeId) throws IOException {
        if (storageLocks.containsKey(probeId)) {
            return true;
        }
        Path path = workspaceRoot.resolve("conformance-storage/locks/" + probeId + ".lock");
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return false;
            }
            storageLocks.put(probeId, new HeldLock(channel, lock));
            return true;
        } catch (OverlappingFileLockException unavailable) {
            channel.close();
            return false;
        }
    }

    private void releaseStorageLock(String probeId) throws IOException {
        HeldLock held = storageLocks.remove(probeId);
        if (held != null) {
            held.close();
        }
    }

    private void afterPrepared(String runId, String operationId) {
        Path action = runDirectory(runId).resolve("prepared-action.txt");
        try {
            if (!Files.isRegularFile(action)) {
                return;
            }
            String requested = Files.readString(action).trim();
            if ("CRASH".equals(requested)) {
                Runtime.getRuntime().halt(86);
            }
            if ("PAUSE".equals(requested)) {
                throw new IllegalStateException("conformance pause after PREPARED");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not read PREPARED action", failure);
        }
    }

    private void incrementFinalizeCount(String runId) throws IOException {
        Path counter = runDirectory(runId).resolve("finalize-request-count.txt");
        synchronized (counter.toString().intern()) {
            int value = Files.isRegularFile(counter)
                    ? Integer.parseInt(Files.readString(counter).trim())
                    : 0;
            atomicWrite(counter, Integer.toString(value + 1).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private Map<String, Object> runRecord(PolicyWindTunnelRunRecord record) {
        Map<String, Object> response = new LinkedHashMap<>(objectMapper.convertValue(record, MAP_TYPE));
        Object finalizationMode = record.parameters().get("opportunityGenerationFinalizationMode");
        if (finalizationMode != null) {
            response.put("finalizationMode", finalizationMode);
        }
        return response;
    }

    private Map<String, Object> platformOwnedV2Pack() {
        Map<String, Object> pack = new LinkedHashMap<>(metadataService.pack());
        Map<String, Object> capabilities = new LinkedHashMap<>(map(pack.get("capabilities")));
        capabilities.put("platformOwnedV2StorageReady", true);
        capabilities.put("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2");
        pack.put("capabilities", Map.copyOf(capabilities));
        return Map.copyOf(pack);
    }

    private Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        return objectMapper.readValue(exchange.getRequestBody().readAllBytes(), MAP_TYPE);
    }

    private void requirePinnedRevision(HttpExchange exchange) {
        String expected = exchange.getRequestHeaders().getFirst("X-Astraform-Expected-Provider-Revision");
        if (!BankingRemotePolicyWindTunnelService.PROVIDER_REVISION.equals(expected)) {
            throw new IllegalArgumentException("provider revision mismatch");
        }
    }

    private static void requireSafeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new IllegalArgumentException("invalid identifier");
        }
    }

    private Path runDirectory(String runId) {
        return runRoot.resolve(runId).normalize();
    }

    private void close() {
        nativeExperimentServer.stop(0);
        nativeExperimentExecutor.shutdownNow();
        for (HeldLock held : storageLocks.values()) {
            try {
                held.close();
            } catch (IOException ignored) {
                // Process shutdown is already best-effort.
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendBytes(exchange, status, "application/json", objectMapper.writeValueAsBytes(value));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] content)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set(
                "X-Astraform-Provider-Revision",
                BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".pending");
        try {
            Files.write(pending, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        pending,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private record HeldLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
        }
    }

    private static final class ConformanceExecutor implements Executor {
        private final ThreadLocal<Boolean> suppressLaunchExecution = ThreadLocal.withInitial(() -> false);

        private <T> T withLaunchExecutionSuppressed(Supplier<T> launch) {
            if (suppressLaunchExecution.get()) {
                throw new IllegalStateException("nested conformance launch suppression is not supported");
            }
            suppressLaunchExecution.set(true);
            try {
                return launch.get();
            } finally {
                suppressLaunchExecution.remove();
            }
        }

        @Override
        public void execute(Runnable command) {
            if (suppressLaunchExecution.get()) {
                return;
            }
            Thread thread = new Thread(command, "banking-v2-provider-recovery");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static final class ConformanceRestTemplate extends RestTemplate {
        private final Path runRoot;
        private final ObjectMapper objectMapper;
        private final BankingRemoteDomainService bankingDomainService;

        private ConformanceRestTemplate(Path runRoot, ObjectMapper objectMapper) {
            this.runRoot = runRoot;
            this.objectMapper = objectMapper;
            this.bankingDomainService = new BankingRemoteDomainService(objectMapper);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> ResponseEntity<T> exchange(
                String url,
                HttpMethod method,
                HttpEntity<?> requestEntity,
                Class<T> responseType,
                Object... uriVariables) {
            String runId = url.contains("/api/domain-control-actions/execute")
                    ? String.valueOf(map(requestEntity.getBody()).get("simulationRunId"))
                    : runId(url);
            if (url.contains("/api/domain-control-actions/execute")
                    && method == HttpMethod.POST
                    && Files.isRegularFile(runRoot.resolve(runId).resolve(
                            "rate-change-component-characterization"))) {
                try {
                    return (ResponseEntity<T>) ResponseEntity.ok(
                            objectMapper.writeValueAsString(executeRateChangeFanout(
                                    runId,
                                    map(requestEntity.getBody()))));
                } catch (IOException failure) {
                    throw new ResourceAccessException(
                            "could not persist rate-change fanout evidence",
                            failure);
                }
            }
            if (url.contains("/decision-opportunities/correlation-audit")) {
                return (ResponseEntity<T>) correlationAudit(runId, requestEntity);
            }
            if (url.contains("/api/agents/runtime-state")) {
                int activeAgents = Files.isRegularFile(
                                runRoot.resolve(runId).resolve("rate-change-correlation-audit.json"))
                        ? 2
                        : 1;
                return (ResponseEntity<T>) ResponseEntity.ok(Map.of(
                        "schemaVersion", "agent_runtime_state_v1",
                        "scope", "simulationRun",
                        "simulationRunId", runId,
                        "activeAgents", activeAgents,
                        "activeTickTasks", 0,
                        "runningAgents", 0,
                        "pendingAgents", 0,
                        "idle", true));
            }
            if (url.contains("/api/agents/hybrid-decisions")) {
                return (ResponseEntity<T>) ResponseEntity.ok(List.of());
            }
            if (method == HttpMethod.PATCH && url.contains("/api/simulation-runs/")) {
                return (ResponseEntity<T>) notification(runId, requestEntity);
            }
            if (url.endsWith("/policy-wind-tunnel/pack")) {
                return (ResponseEntity<T>) ResponseEntity.ok(Map.of());
            }
            Object body = responseType == String.class ? "{}" : Map.of();
            return (ResponseEntity<T>) ResponseEntity.ok(body);
        }

        @Override
        public <T> ResponseEntity<T> exchange(
                URI url,
                HttpMethod method,
                HttpEntity<?> requestEntity,
                Class<T> responseType) {
            return exchange(url.toString(), method, requestEntity, responseType);
        }

        @Override
        public <T> T execute(
                URI url,
                HttpMethod method,
                RequestCallback requestCallback,
                ResponseExtractor<T> responseExtractor) {
            if (!url.toString().contains("/decision-opportunities/correlation-audit")) {
                return super.execute(url, method, requestCallback, responseExtractor);
            }
            MockClientHttpRequest request = new MockClientHttpRequest(method, url);
            try {
                if (requestCallback != null) {
                    requestCallback.doWithRequest(request);
                }
                ResponseEntity<byte[]> entity = correlationAudit(
                        runId(url.toString()),
                        new HttpEntity<>(request.getHeaders()));
                byte[] content = entity.getBody() == null ? new byte[0] : entity.getBody();
                MockClientHttpResponse response =
                        new MockClientHttpResponse(content, entity.getStatusCode());
                response.getHeaders().putAll(entity.getHeaders());
                return responseExtractor == null ? null : responseExtractor.extractData(response);
            } catch (IOException failure) {
                throw new ResourceAccessException(
                        "could not execute conformance correlation-audit request",
                        failure);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) {
            String runId = runId(url);
            if (url.endsWith("/decision-opportunities/reconciliation")) {
                return (T) reconciliation(runId);
            }
            if (url.endsWith("/api/clock/replay")) {
                return (T) "{}";
            }
            if (url.endsWith("/api/clock/time")) {
                return (T) Long.valueOf(1_784_500_000_000L);
            }
            if (url.endsWith("/api/clock/rate/info")) {
                return (T) "{\"maxPlaybackRate\":7200}";
            }
            return (T) Map.of();
        }

        private Map<String, Object> executeRateChangeFanout(
                String runId,
                Map<String, Object> broadcast) throws IOException {
            BankingClockDrivenRateChangePlan.Snapshot snapshot =
                    BankingClockDrivenRateChangePlan.requireSnapshot(broadcast.get("inputs"));
            if (!runId.equals(snapshot.plan().get("simulationRunId"))) {
                throw new IllegalArgumentException("rate-change fanout run ownership is invalid");
            }
            List<RateChangeAgentState> agents = List.of(
                    prepareRateChangeAgent(
                            "customer-001",
                            Map.ofEntries(
                                    Map.entry("accountId", "cc-001"),
                                    Map.entry("name", "Rewards Card"),
                                    Map.entry("productCode", "CC001"),
                                    Map.entry("balance", -1200),
                                    Map.entry("limit", 3000),
                                    Map.entry("currency", "AUD"))),
                    prepareRateChangeAgent(
                            "customer-002",
                            Map.ofEntries(
                                    Map.entry("accountId", "tx-001"),
                                    Map.entry("name", "Everyday Account"),
                                    Map.entry("productCode", "TX001"),
                                    Map.entry("balance", 500),
                                    Map.entry("currency", "AUD"))));
            List<Map<String, Object>> results = new java.util.ArrayList<>();
            List<Map<String, Object>> triggers = new java.util.ArrayList<>();
            List<Map<String, Object>> finalStates = new java.util.ArrayList<>();
            Map<String, String> opportunityAgentIdsByCohortAgentId = new LinkedHashMap<>();
            long startEpochMs = ((Number) snapshot.plan().get("simulationStartEpochMs")).longValue();
            long scheduledForEpochMs = ((Number) snapshot.plan().get("scheduledForEpochMs")).longValue();
            for (RateChangeAgentState agent : agents) {
                var installed = bankingDomainService.executeControlAction(
                        rateChangeControlActionRequest(agent, snapshot));
                String providerRuntimeAgentId = installed.getResult().getRuntimeIdentity();
                String opportunityAgentId = "domain:banking:agent:" + providerRuntimeAgentId;
                opportunityAgentIdsByCohortAgentId.put(agent.agentId(), opportunityAgentId);
                results.add(Map.of(
                        "agentId", providerRuntimeAgentId,
                        "cohortAgentId", agent.agentId(),
                        "providerRuntimeAgentId", providerRuntimeAgentId,
                        "opportunityAgentId", opportunityAgentId,
                        "controlAction", new LinkedHashMap<>(installed.getResult().getControlAction())));
                var executed = bankingDomainService.executeDomainSystemWorkWindow(
                        rateChangeWindowRequest(
                                agent.agentId(),
                                providerRuntimeAgentId,
                                installed.getNextState(),
                                startEpochMs,
                                scheduledForEpochMs));
                List<?> emitted = executed.getResult().getDomainSystemWork().getDecisionTriggers();
                if (emitted != null) {
                    for (Object trigger : emitted) {
                        triggers.add(objectMapper.convertValue(trigger, MAP_TYPE));
                    }
                }
                finalStates.add(Map.of(
                        "agentId", agent.agentId(),
                        "state", new LinkedHashMap<>(executed.getNextState().getData())));
            }
            if (triggers.size() != 1) {
                throw new IllegalStateException(
                        "real banking fanout must emit exactly one eligible-customer trigger");
            }
            Map<String, Object> audit = rateChangeCorrelationAudit(
                    runId,
                    triggers.getFirst(),
                    opportunityAgentIdsByCohortAgentId);
            atomicWrite(
                    runRoot.resolve(runId).resolve("rate-change-correlation-audit.json"),
                    objectMapper.writeValueAsBytes(audit));
            atomicWrite(
                    runRoot.resolve(runId).resolve("rate-change-fanout.json"),
                    objectMapper.writeValueAsBytes(Map.of(
                            "simulationRunId", runId,
                            "matchedAgents", agents.size(),
                            "results", results,
                            "finalStates", finalStates,
                            "decisionTriggers", triggers)));
            incrementRateChangeFanoutCount(runId);
            return Map.of(
                    "simulationRunId", runId,
                    "actionId", BankingClockDrivenRateChangePlan.ACTION_ID,
                    "operationId", broadcast.get("operationId"),
                    "matchedAgents", agents.size(),
                    "succeeded", agents.size(),
                    "failed", 0,
                    "results", results);
        }

        private RateChangeAgentState prepareRateChangeAgent(
                String agentId,
                Map<String, Object> initialAccount) {
            long sentAtEpochMs = 1_770_000_000_000L;
            RemoteDomainPrepareRequest request = new RemoteDomainPrepareRequest();
            request.setProtocolVersion(RemoteDomainPrepareRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
            request.setRequestId(UUID.randomUUID());
            request.setOperation(RemoteDomainPrepareRequest.OperationEnum.PREPARE);
            request.setIdempotencyKey(agentId + ":prepare:" + sentAtEpochMs);
            request.setSentAtEpochMs(sentAtEpochMs);
            request.setDeadlineEpochMs(sentAtEpochMs + 5_000L);
            request.setHost(new RemoteDomainHostContext(
                    "agent-runtime", "rate-change-component", "test"));
            request.setDomain(new RemoteDomainContext("banking"));
            request.setAgent(agentContext(agentId));
            request.setPayload(new RemoteDomainPrepareRequestAllOfPayload(Map.ofEntries(
                    Map.entry("personaName", agentId),
                    Map.entry("personaDescription", "Pinned rate-change acceptance customer"),
                    Map.entry("agentType", "Customer"),
                    Map.entry("interactionMode", "SIMULATION"),
                    Map.entry("dateOfBirth", "1988-06-14"),
                    Map.entry("traits", List.of("rate-change-acceptance")),
                    Map.entry("goals", List.of()),
                    Map.entry("initialAccounts", List.of(initialAccount)),
                    Map.entry("scheduledWork", List.of()))));
            var prepared = bankingDomainService.prepare(request);
            return new RateChangeAgentState(agentId, prepared.getNextState());
        }

        private RemoteDomainControlActionRequest rateChangeControlActionRequest(
                RateChangeAgentState agent,
                BankingClockDrivenRateChangePlan.Snapshot snapshot) {
            String operationId = BankingClockDrivenRateChangePlan.installOperationId(snapshot);
            long sentAtEpochMs = ((Number) snapshot.plan().get("simulationStartEpochMs")).longValue();
            RemoteDomainControlActionRequest request = new RemoteDomainControlActionRequest();
            request.setProtocolVersion(
                    RemoteDomainControlActionRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
            request.setRequestId(UUID.randomUUID());
            request.setOperation(RemoteDomainControlActionRequest.OperationEnum.CONTROL_ACTION);
            request.setIdempotencyKey(agent.agentId() + ":control-action:" + operationId);
            request.setSentAtEpochMs(sentAtEpochMs);
            request.setDeadlineEpochMs(sentAtEpochMs + 5_000L);
            request.setHost(new RemoteDomainHostContext(
                    "agent-runtime", "rate-change-component", "test"));
            request.setDomain(new RemoteDomainContext("banking"));
            request.setAgent(agentContext(agent.agentId()));
            request.setState(agent.state());
            RemoteDomainControlActionTarget target = new RemoteDomainControlActionTarget(
                    RemoteDomainControlActionTarget.ScopeEnum.BROADCAST);
            target.setSelector(Map.of("simulationRunId", snapshot.plan().get("simulationRunId")));
            RemoteDomainControlActionPayload payload = new RemoteDomainControlActionPayload(
                    BankingClockDrivenRateChangePlan.ACTION_ID,
                    operationId,
                    target,
                    new LinkedHashMap<>(snapshot.asMap()));
            payload.setRequestedBy("banking-policy-wind-tunnel-provider");
            payload.setReason("Install rate-change plan");
            payload.setSimulationTimeEpochMs(sentAtEpochMs);
            request.setPayload(payload);
            return request;
        }

        private RemoteDomainSystemWorkWindowRequest rateChangeWindowRequest(
                String agentId,
                String runtimeIdentity,
                RemoteDomainOpaqueState state,
                long startEpochMs,
                long scheduledForEpochMs) {
            RemoteDomainSystemWorkWindowRequest request = new RemoteDomainSystemWorkWindowRequest();
            request.setProtocolVersion(
                    RemoteDomainSystemWorkWindowRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
            request.setRequestId(UUID.randomUUID());
            request.setOperation(
                    RemoteDomainSystemWorkWindowRequest.OperationEnum.DOMAIN_SYSTEM_WORK_WINDOW);
            request.setIdempotencyKey(agentId + ":rate-change-window:" + scheduledForEpochMs);
            request.setSentAtEpochMs(scheduledForEpochMs);
            request.setDeadlineEpochMs(scheduledForEpochMs + 5_000L);
            request.setHost(new RemoteDomainHostContext(
                    "agent-runtime", "rate-change-component", "test"));
            request.setDomain(new RemoteDomainContext("banking"));
            RemoteDomainAgentContext agent = agentContext(agentId);
            agent.setRuntimeIdentity(runtimeIdentity);
            request.setAgent(agent);
            request.setState(state);
            RemoteDomainSystemWorkWindowPayload payload = new RemoteDomainSystemWorkWindowPayload(
                    new RemoteDomainTimeWindow(startEpochMs, scheduledForEpochMs));
            payload.setWorkKinds(List.of(BankingClockDrivenRateChangePlan.WORK_KIND));
            payload.setMaxWorkItems(10);
            payload.setExecutionMode(
                    RemoteDomainSystemWorkWindowPayload.ExecutionModeEnum.EVALUATE_AND_EXECUTE);
            request.setPayload(payload);
            return request;
        }

        private static RemoteDomainAgentContext agentContext(String agentId) {
            RemoteDomainAgentContext agent = new RemoteDomainAgentContext(agentId, agentId);
            agent.setRuntimeIdentity("provider-runtime-" + agentId);
            agent.setAgentType("Customer");
            agent.setInteractionMode("SIMULATION");
            agent.setTraits(List.of("rate-change-acceptance"));
            return agent;
        }

        private Map<String, Object> rateChangeCorrelationAudit(
                String runId,
                Map<String, Object> trigger,
                Map<String, String> opportunityAgentIdsByCohortAgentId) throws IOException {
            String triggerId = String.valueOf(trigger.get("triggerId"));
            String cohortAgentId = String.valueOf(map(trigger.get("payload")).get("customerId"));
            String agentId = opportunityAgentIdsByCohortAgentId.get(cohortAgentId);
            if (agentId == null) {
                throw new IllegalStateException(
                        "rate-change trigger is not bound to an installed opportunity identity");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("opportunityId", UUID.nameUUIDFromBytes(
                    ("opportunity:" + triggerId).getBytes(StandardCharsets.UTF_8)).toString());
            row.put("agentId", agentId);
            row.put("domainId", "banking");
            row.put("triggerId", triggerId);
            row.put("triggerType", trigger.get("triggerType"));
            row.put("triggerRef", trigger.get("triggerRef"));
            row.put("scheduledForEpochMs", trigger.get("scheduledForEpochMs"));
            row.put("priority", trigger.get("priority"));
            row.put("reason", trigger.get("reason"));
            row.put("status", "SUCCEEDED");
            row.put("attemptCount", 1);
            row.put("decisionMode", "STUB_LLM");
            row.put("triggerDigest", sha256(objectMapper.writeValueAsBytes(trigger)));
            row.put("payload", trigger.get("payload"));
            row.put("evidenceRefs", trigger.get("evidenceRefs"));
            row.put("decisionEventId", UUID.nameUUIDFromBytes(
                    ("decision:" + triggerId).getBytes(StandardCharsets.UTF_8)).toString());
            row.put("decisionAttempt", 1);
            row.put("decisionContractValid", true);
            return Map.of(
                    "schemaVersion", "customer_decision_opportunity_correlation_audit.v1",
                    "simulationRunId", runId,
                    "triggerType", "rate_or_policy_change",
                    "opportunityCount", 1,
                    "opportunities", List.of(row),
                    "asOf", Instant.ofEpochMilli(
                            ((Number) trigger.get("scheduledForEpochMs")).longValue()).toString());
        }

        private ResponseEntity<byte[]> correlationAudit(
                String runId,
                HttpEntity<?> requestEntity) {
            try {
                Path runDirectory = runRoot.resolve(runId);
                String expectedCapability = Files.readString(
                        runDirectory.resolve("expected-correlation-capability.txt"),
                        StandardCharsets.US_ASCII).trim();
                String actualCapability = requestEntity.getHeaders().getFirst(
                        "X-Astraform-Opportunity-Correlation-Capability");
                if (!expectedCapability.equals(actualCapability)) {
                    return ResponseEntity.status(403).build();
                }
                atomicWrite(
                        runDirectory.resolve("correlation-audit-authenticated"),
                        actualCapability.getBytes(StandardCharsets.US_ASCII));
                byte[] content = Files.readAllBytes(
                        runDirectory.resolve("rate-change-correlation-audit.json"));
                return ResponseEntity.ok()
                        .header("Content-Type", "application/json;charset=UTF-8")
                        .body(content);
            } catch (IOException failure) {
                throw new ResourceAccessException(
                        "could not serve authenticated rate-change audit",
                        failure);
            }
        }

        private void incrementRateChangeFanoutCount(String runId) throws IOException {
            Path counter = runRoot.resolve(runId).resolve("rate-change-fanout-count.txt");
            int current = Files.isRegularFile(counter)
                    ? Integer.parseInt(Files.readString(counter).trim())
                    : 0;
            atomicWrite(
                    counter,
                    Integer.toString(current + 1).getBytes(StandardCharsets.US_ASCII));
        }

        private ResponseEntity<Object> notification(String runId, HttpEntity<?> requestEntity) {
            return nativeNotification(runId, objectMapper.writeValueAsBytes(requestEntity.getBody()),
                    requestEntity.getHeaders().getFirst("Idempotency-Key"));
        }

        private ResponseEntity<Object> nativeNotification(String runId, byte[] actualContent, String actualKey) {
            boolean callbackBarrier = Files.isRegularFile(
                    runRoot.resolve(runId).resolve("notification-callback-barrier"));
            byte[] content = null;
            String key = null;
            try {
                content = actualContent;
                key = actualKey;
                if (callbackBarrier) {
                    appendCallbackEvent(runId, "ENTRY", content, key);
                    awaitCallbackRelease(runId);
                }
                RecordedNotification recorded = appendNotificationAttempt(runId, content, key, null);
                if ("FAILED".equals(recorded.outcome())) {
                    if (Files.isRegularFile(
                                    runRoot.resolve(runId).resolve("crash-after-notification-failure"))
                            && recorded.sequence() == 1) {
                        Runtime.getRuntime().halt(87);
                    }
                    throw new ResourceAccessException("simulated transient notification failure");
                }
                return ResponseEntity.ok(Map.of("runId", runId));
            } catch (IOException failure) {
                throw new ResourceAccessException("could not record notification", failure);
            } finally {
                if (callbackBarrier && content != null) {
                    try {
                        appendCallbackEvent(runId, "EXIT", content, key);
                    } catch (IOException failure) {
                        throw new ResourceAccessException("could not record notification callback exit", failure);
                    }
                }
            }
        }

        private void awaitCallbackRelease(String runId) throws IOException {
            Path release = runRoot.resolve(runId).resolve("notification-callback-release");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            try {
                while (Files.notExists(release) && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("notification callback barrier was interrupted", interrupted);
            }
            if (Files.notExists(release)) {
                throw new IOException("notification callback barrier timed out");
            }
        }

        private void appendCallbackEvent(
                String runId,
                String event,
                byte[] content,
                String idempotencyKey) throws IOException {
            Path events = runRoot.resolve(runId).resolve("notification-callbacks");
            Files.createDirectories(events);
            try (FileChannel channel = FileChannel.open(
                            events.resolve(".append.lock"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                List<Path> existing;
                try (var files = Files.list(events)) {
                    existing = files
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
                }
                int activeCallbacks = 0;
                for (Path path : existing) {
                    Map<String, Object> recorded = objectMapper.readValue(path.toFile(), MAP_TYPE);
                    activeCallbacks += "ENTRY".equals(recorded.get("event")) ? 1 : -1;
                }
                activeCallbacks += "ENTRY".equals(event) ? 1 : -1;
                if (activeCallbacks < 0) {
                    throw new IOException("notification callback event history is invalid");
                }
                int sequence = existing.size() + 1;
                Map<String, Object> recorded = new LinkedHashMap<>();
                recorded.put("sequence", sequence);
                recorded.put("event", event);
                recorded.put("processId", ProcessHandle.current().pid());
                recorded.put("timestampEpochMs", System.currentTimeMillis());
                recorded.put("activeCallbacks", activeCallbacks);
                recorded.put("payloadSha256", sha256(content));
                recorded.put("idempotencyKey", String.valueOf(idempotencyKey));
                writeNew(
                        events.resolve(String.format("%08d-%s.json", sequence, UUID.randomUUID())),
                        objectMapper.writeValueAsBytes(recorded));
            }
        }

        private void recordNotificationProbe(String runId, byte[] content) throws IOException {
            appendNotificationAttempt(
                    runId,
                    content,
                    "notification-recorder:" + runId,
                    "FAILED");
        }

        private RecordedNotification appendNotificationAttempt(
                String runId,
                byte[] content,
                String idempotencyKey,
                String forcedOutcome) throws IOException {
            Path notifications = runRoot.resolve(runId).resolve("notifications");
            Files.createDirectories(notifications);
            Path appendLock = notifications.resolve(".append.lock");
            try (FileChannel channel = FileChannel.open(
                            appendLock,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                long count;
                try (var files = Files.list(notifications)) {
                    count = files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
                }
                int sequence = Math.toIntExact(count + 1L);
                boolean controlledFailure = Files.isRegularFile(
                                runRoot.resolve(runId).resolve("crash-after-notification-failure"))
                        && Files.notExists(runRoot.resolve(runId).resolve("notification-available"));
                String outcome = forcedOutcome != null
                        ? forcedOutcome
                        : (controlledFailure || sequence == 1 ? "FAILED" : "SUCCEEDED");
                String stem = String.format("%08d-%s", sequence, UUID.randomUUID());
                writeNew(notifications.resolve(stem + ".json"), content);
                writeNew(
                        notifications.resolve(stem + ".key"),
                        String.valueOf(idempotencyKey).getBytes(StandardCharsets.UTF_8));
                writeNew(
                        notifications.resolve(stem + ".outcome"),
                        outcome.getBytes(StandardCharsets.US_ASCII));
                writeNew(
                        notifications.resolve(stem + ".timestamp"),
                        Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
                return new RecordedNotification(sequence, outcome);
            }
        }

        private static void writeNew(Path target, byte[] content) throws IOException {
            Files.write(
                    target,
                    content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }

        private static String sha256(byte[] content) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }

        private static String runId(String url) {
            String marker = "simulationRunId=";
            int query = url.indexOf(marker);
            if (query >= 0) {
                return url.substring(query + marker.length()).split("&", 2)[0];
            }
            for (String pathMarker : List.of("/simulation-runs/", "/decision-opportunities/")) {
                int path = url.indexOf(pathMarker);
                if (path >= 0) {
                    return url.substring(path + pathMarker.length()).split("[/?]", 2)[0];
                }
            }
            String agents = "/simulation-runs/";
            int path = url.indexOf(agents);
            return path >= 0 ? url.substring(path + agents.length()).split("[/?]", 2)[0] : "unknown";
        }

        private record RecordedNotification(int sequence, String outcome) { }

        private Map<String, Object> reconciliation(String runId) {
            boolean rateChange = Files.isRegularFile(
                    runRoot.resolve(runId).resolve("rate-change-correlation-audit.json"));
            Map<String, Long> statusCounts = new LinkedHashMap<>();
            for (String status : List.of(
                    "PENDING", "RUNNING", "SUCCEEDED", "DEFERRED", "BLOCKED", "FAILED", "CANCELLED")) {
                statusCounts.put(status, rateChange && "SUCCEEDED".equals(status) ? 1L : 0L);
            }
            return Map.ofEntries(
                    Map.entry("schemaVersion", "customer_decision_opportunity_reconciliation.v1"),
                    Map.entry("simulationRunId", runId),
                    Map.entry("status", rateChange ? "RECONCILED" : "EMPTY"),
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
                    Map.entry("expectedInitializationAgents", rateChange ? 2 : 1),
                    Map.entry("registeredInitializationAgents", rateChange ? 2 : 1),
                    Map.entry("resolvedInitializationAgents", rateChange ? 2 : 1),
                    Map.entry("tickFailureCount", 0),
                    Map.entry("tickFailureLedgerCount", 0),
                    Map.entry("expectedGeneratedOpportunities", rateChange ? 1 : 0),
                    Map.entry("rejectedTriggers", 0),
                    Map.entry("migrationAnomalies", 0),
                    Map.entry("generatedOpportunities", rateChange ? 1 : 0),
                    Map.entry("terminalOpportunities", rateChange ? 1 : 0),
                    Map.entry("remainingBacklog", 0),
                    Map.entry("validDecisions", rateChange ? 1 : 0),
                    Map.entry("recoverableRecordedDecisions", 0),
                    Map.entry("succeededWithoutDecisionEvidence", 0),
                    Map.entry("decisionEvidenceGaps", 0),
                    Map.entry("statusCounts", statusCounts),
                    Map.entry("asOf", "2026-07-12T00:00:00Z"));
        }

        private record RateChangeAgentState(String agentId, RemoteDomainOpaqueState state) { }
    }
}
