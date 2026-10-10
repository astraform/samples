package banking.simulator.remote.domain;

import ai.astraform.remote.domain.conformance.ScenarioLabFinalizationV2ProbeDriver;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelManifest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class BankingPolicyWindTunnelV2ProbeDriver implements ScenarioLabFinalizationV2ProbeDriver {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String BASE_PATH = "/policy-wind-tunnel";

    private final ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
    private final Path root;
    private final Path store;
    private final List<ChildProvider> processes = new CopyOnWriteArrayList<>();
    private volatile ChildProvider currentProvider;

    BankingPolicyWindTunnelV2ProbeDriver() {
        try {
            root = Files.createTempDirectory("banking-v2-conformance-");
            store = root.resolve("shared-provider-state");
            Files.createDirectories(store);
        } catch (IOException failure) {
            throw new IllegalStateException("could not create V2 conformance storage", failure);
        }
    }

    @Override
    public synchronized ProviderProcess startInitialProvider() throws Exception {
        if (currentProvider == null) {
            currentProvider = startProvider("INITIAL");
        }
        return currentProvider;
    }

    @Override
    public ProviderProcess startDurableStoragePeer() throws Exception {
        return startProvider("STORAGE_PEER");
    }

    @Override
    public boolean tryAcquireDurableStorageLock(ProviderProcess provider, String probeId) throws Exception {
        HttpResponse<byte[]> response = storageRequest(provider, probeId + "/lock", "POST", new byte[0]);
        requireStatus(response, 200, "storage lock acquisition");
        return Boolean.TRUE.equals(objectMapper.readValue(response.body(), MAP_TYPE).get("acquired"));
    }

    @Override
    public void releaseDurableStorageLock(ProviderProcess provider, String probeId) throws Exception {
        requireStatus(
                storageRequest(provider, probeId + "/lock", "DELETE", new byte[0]),
                200,
                "storage lock release");
    }

    @Override
    public void publishDurableStorageValue(ProviderProcess provider, String probeId, byte[] content)
            throws Exception {
        requireStatus(
                storageRequest(provider, probeId + "/value", "PUT", content),
                200,
                "storage value publication");
    }

    @Override
    public byte[] readDurableStorageValue(ProviderProcess provider, String probeId) throws Exception {
        HttpResponse<byte[]> response = storageRequest(provider, probeId + "/value", "GET", new byte[0]);
        requireStatus(response, 200, "storage value read");
        return response.body();
    }

    @Override
    public void makeRunSuccessfullyFinalizable(String runId, String operationId) throws Exception {
        requireStatus(
                request(current(), BASE_PATH + "/conformance-v2/runs/" + runId + "/ready", "POST", new byte[0]),
                200,
                "make run finalizable");
    }

    @Override
    public void armTerminalPauseAfterPrepared(String runId, String operationId) throws Exception {
        writePreparedAction(runId, "PAUSE");
    }

    @Override
    public synchronized ProviderProcess restartAfterTerminalPublication(String runId, String operationId)
            throws Exception {
        currentProvider = startProvider("TERMINAL_RESTART");
        return currentProvider;
    }

    @Override
    public void armCrashAfterPrepared(String runId, String operationId) throws Exception {
        writePreparedAction(runId, "CRASH");
    }

    @Override
    public byte[] readPreparedReceipt(String runId) throws Exception {
        Path receipt = runDirectory(runId).resolve("finalization-receipt.json");
        return Files.isRegularFile(receipt) ? Files.readAllBytes(receipt) : new byte[0];
    }

    @Override
    public byte[] readPreparedArtifact(String runId, String artifactRef) throws Exception {
        Path artifact = store.resolve(artifactRef).normalize();
        if (!artifact.startsWith(store) || !Files.isRegularFile(artifact)) {
            throw new AssertionError("prepared artifact is outside shared durable storage");
        }
        return Files.readAllBytes(artifact);
    }

    @Override
    public synchronized ProviderProcess restartAfterPreparedCrash(String runId, String operationId)
            throws Exception {
        currentProvider = startProvider("SUCCESS_RESTART");
        return currentProvider;
    }

    @Override
    public ArtifactFault removeArtifact(String runId, String artifactRef) throws Exception {
        return replaceArtifact(artifactRef, null);
    }

    @Override
    public ArtifactFault alterArtifact(String runId, String artifactRef) throws Exception {
        Path artifact = checkedArtifact(artifactRef);
        byte[] original = Files.readAllBytes(artifact);
        byte[] altered = java.util.Arrays.copyOf(original, original.length + 1);
        altered[altered.length - 1] = '!';
        return replaceArtifact(artifactRef, altered);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ArtifactFault exposeInvalidReceipt(String runId, String vectorCase) throws Exception {
        Path receiptPath = runDirectory(runId).resolve("finalization-receipt.json");
        byte[] original = Files.readAllBytes(receiptPath);
        if ("missing_receipt".equals(vectorCase)) {
            Files.delete(receiptPath);
            return () -> Files.write(receiptPath, original);
        }
        byte[] invalid;
        if ("ambiguous_receipt_json".equals(vectorCase)) {
            invalid = "{\"state\":\"PREPARED\",\"state\":\"PUBLISHED\"} trailing"
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            Map<String, Object> receipt = objectMapper.readValue(original, MAP_TYPE);
            if ("receipt_missing_state".equals(vectorCase)) {
                receipt.remove("state");
            } else if ("prepared_with_terminal_status".equals(vectorCase)) {
                receipt.put("state", "PREPARED");
            } else if ("published_with_running_status".equals(vectorCase)) {
                ((Map<String, Object>) receipt.get("runSnapshot")).put("status", "RUNNING");
            } else if ("artifact_path_escape".equals(vectorCase)) {
                Map<String, Object> manifest = (Map<String, Object>) receipt.get("artifactManifest");
                Map<String, Object> artifact = (Map<String, Object>) manifest.values().iterator().next();
                artifact.put("artifactRef", "../escape.json");
            } else {
                throw new IllegalArgumentException("unsupported receipt vector " + vectorCase);
            }
            invalid = objectMapper.writeValueAsBytes(receipt);
        }
        Files.write(receiptPath, invalid);
        return () -> Files.write(receiptPath, original);
    }

    @Override
    public void prepareLegacyV1RecoveryRun(String runId) throws Exception {
        requireStatus(
                request(current(), BASE_PATH + "/conformance-v2/runs/" + runId + "/legacy", "POST", new byte[0]),
                200,
                "legacy recovery run preparation");
    }

    @Override
    public List<NotificationAttempt> notificationAttempts(String runId) throws Exception {
        Path directory = runDirectory(runId).resolve("notifications");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<NotificationAttempt> attempts = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path content : paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                String stem = content.getFileName().toString().replace(".json", "");
                Path key = directory.resolve(stem + ".key");
                Path outcome = directory.resolve(stem + ".outcome");
                if (Files.isRegularFile(key) && Files.isRegularFile(outcome)) {
                    attempts.add(new NotificationAttempt(
                            Files.readAllBytes(content),
                            Files.readString(key),
                            NotificationDeliveryOutcome.valueOf(Files.readString(outcome).trim())));
                }
            }
        }
        return List.copyOf(attempts);
    }

    @Override
    public List<NotificationAttempt> awaitNotificationAttempts(
            String runId,
            int expectedCount,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<NotificationAttempt> attempts;
        do {
            attempts = notificationAttempts(runId);
            if (attempts.size() >= expectedCount) {
                return attempts;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for completion notification attempts");
    }

    @Override
    public int finalizeRequestCount(String runId) throws Exception {
        Path counter = runDirectory(runId).resolve("finalize-request-count.txt");
        return Files.isRegularFile(counter) ? Integer.parseInt(Files.readString(counter).trim()) : 0;
    }

    void verifyPendingNotificationRecoveryAfterRestart() throws Exception {
        PendingNotificationRun pending = prepareCrashedPendingNotification();
        String runId = pending.runId();
        int finalizeCount = pending.finalizeRequestCount();

        ChildProvider replacementA = startProvider("NOTIFICATION_RESTART");
        ChildProvider replacementB = startProvider("NOTIFICATION_PEER");
        currentProvider = replacementA;
        List<NotificationAttempt> sustainedFailures = awaitNotificationAttempts(
                runId,
                5,
                Duration.ofSeconds(10));
        if (sustainedFailures.size() != 5
                || sustainedFailures.stream().anyMatch(
                        attempt -> attempt.outcome() != NotificationDeliveryOutcome.FAILED)) {
            throw new AssertionError("restart recovery did not retain notification failure");
        }
        List<Long> timestamps = notificationAttemptTimestamps(runId);
        long firstBackoff = timestamps.get(2) - timestamps.get(1);
        long firstCappedBackoff = timestamps.get(3) - timestamps.get(2);
        long secondCappedBackoff = timestamps.get(4) - timestamps.get(3);
        if (firstBackoff < 150L
                || firstCappedBackoff < 300L
                || secondCappedBackoff < 300L
                || firstCappedBackoff <= firstBackoff
                || firstCappedBackoff > 1_200L
                || secondCappedBackoff > 1_200L) {
            throw new AssertionError(
                    "notification retry intervals did not increase and settle at the configured cap: "
                            + List.of(firstBackoff, firstCappedBackoff, secondCappedBackoff));
        }
        Files.writeString(
                runDirectory(runId).resolve("notification-available"),
                "AVAILABLE",
                StandardCharsets.US_ASCII);
        List<NotificationAttempt> recovered = awaitNotificationAttempts(
                runId,
                6,
                Duration.ofSeconds(5));
        Thread.sleep(1_000L);
        recovered = notificationAttempts(runId);
        if (recovered.size() != 6
                || recovered.subList(0, 5).stream().anyMatch(
                        attempt -> attempt.outcome() != NotificationDeliveryOutcome.FAILED)
                || recovered.get(5).outcome() != NotificationDeliveryOutcome.SUCCEEDED) {
            throw new AssertionError("restart recovery did not end sustained failure with one success");
        }
        byte[] expectedContent = recovered.getFirst().content();
        String expectedIdempotencyKey = recovered.getFirst().idempotencyKey();
        if (recovered.stream().anyMatch(attempt ->
                !Arrays.equals(expectedContent, attempt.content())
                        || !expectedIdempotencyKey.equals(attempt.idempotencyKey()))) {
            throw new AssertionError("restart recovery changed notification ownership or payload bytes");
        }
        if (finalizeRequestCount(runId) != finalizeCount) {
            throw new AssertionError("restart recovery crossed the FINALIZE boundary again");
        }
        Map<String, Object> confirmedReceipt = objectMapper.readValue(
                runDirectory(runId).resolve("finalization-receipt.json").toFile(),
                MAP_TYPE);
        if (!"CONFIRMED".equals(confirmedReceipt.get("completionNotificationState"))) {
            throw new AssertionError("restart recovery did not durably confirm notification delivery");
        }
        if (!replacementA.process().isAlive() || !replacementB.process().isAlive()) {
            throw new AssertionError("replacement providers did not remain available after recovery");
        }
    }

    void verifyNotificationRecoveryCallbackIsReplicaExclusive() throws Exception {
        PendingNotificationRun pending = prepareCrashedPendingNotification();
        String runId = pending.runId();
        Path runDirectory = runDirectory(runId);
        Files.writeString(
                runDirectory.resolve("notification-available"),
                "AVAILABLE",
                StandardCharsets.US_ASCII);
        Files.writeString(
                runDirectory.resolve("notification-callback-barrier"),
                "BLOCK",
                StandardCharsets.US_ASCII);

        ChildProvider replacementA = startProvider("NOTIFICATION_EXCLUSIVITY");
        ChildProvider replacementB = startProvider("NOTIFICATION_EXCLUSIVITY");
        currentProvider = replacementA;
        List<CallbackEvent> blocked = awaitCallbackEvents(runId, 1, Duration.ofSeconds(5));
        Thread.sleep(500L);
        blocked = callbackEvents(runId);
        if (blocked.size() != 1
                || !"ENTRY".equals(blocked.getFirst().event())
                || blocked.getFirst().activeCallbacks() != 1
                || (!blocked.getFirst().processId().equals(replacementA.process().pid())
                        && !blocked.getFirst().processId().equals(replacementB.process().pid()))) {
            throw new AssertionError("two provider replicas entered one notification callback concurrently");
        }
        if (!replacementA.process().isAlive() || !replacementB.process().isAlive()) {
            throw new AssertionError("notification recovery replica exited while the callback was blocked");
        }

        Files.writeString(
                runDirectory.resolve("notification-callback-release"),
                "RELEASE",
                StandardCharsets.US_ASCII);
        List<CallbackEvent> completed = awaitCallbackEvents(runId, 2, Duration.ofSeconds(5));
        List<NotificationAttempt> attempts = awaitNotificationAttempts(runId, 2, Duration.ofSeconds(5));
        awaitConfirmedReceipt(runId, Duration.ofSeconds(5));
        Thread.sleep(500L);
        completed = callbackEvents(runId);
        attempts = notificationAttempts(runId);

        long entries = completed.stream().filter(event -> "ENTRY".equals(event.event())).count();
        long exits = completed.stream().filter(event -> "EXIT".equals(event.event())).count();
        int maxConcurrentCallbacks = completed.stream()
                .mapToInt(CallbackEvent::activeCallbacks)
                .max()
                .orElse(0);
        if (completed.size() != 2
                || entries != 1L
                || exits != 1L
                || maxConcurrentCallbacks != 1
                || completed.getLast().activeCallbacks() != 0) {
            throw new AssertionError("notification callback receipt lock did not provide replica exclusivity");
        }
        if (attempts.size() != 2
                || attempts.getFirst().outcome() != NotificationDeliveryOutcome.FAILED
                || attempts.getLast().outcome() != NotificationDeliveryOutcome.SUCCEEDED
                || !Arrays.equals(attempts.getFirst().content(), attempts.getLast().content())
                || !attempts.getFirst().idempotencyKey().equals(attempts.getLast().idempotencyKey())) {
            throw new AssertionError("exclusive notification recovery changed payload, key, or outcome history");
        }
        CallbackEvent entry = completed.getFirst();
        CallbackEvent exit = completed.getLast();
        if (!entry.processId().equals(exit.processId())
                || entry.timestampEpochMs() > exit.timestampEpochMs()
                || !entry.payloadSha256().equals(sha256(attempts.getLast().content()))
                || !entry.idempotencyKey().equals(attempts.getLast().idempotencyKey())
                || !entry.payloadSha256().equals(exit.payloadSha256())
                || !entry.idempotencyKey().equals(exit.idempotencyKey())) {
            throw new AssertionError("notification callback ownership record is contradictory");
        }
        if (finalizeRequestCount(runId) != pending.finalizeRequestCount()) {
            throw new AssertionError("replica notification recovery crossed FINALIZE again");
        }
    }

    void verifyConcurrentNotificationRecordingIsAppendOnly() throws Exception {
        String runId = "notification-recorder-" + UUID.randomUUID().toString().replace("-", "");
        ChildProvider replicaA = startProvider("RECORDER_RACE");
        ChildProvider replicaB = startProvider("RECORDER_RACE");
        byte[] firstPayload = "replica-a".getBytes(StandardCharsets.US_ASCII);
        byte[] secondPayload = "replica-b".getBytes(StandardCharsets.US_ASCII);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<byte[]>> first = executor.submit(() -> {
                start.await();
                return request(
                        replicaA,
                        BASE_PATH + "/conformance-v2/runs/" + runId + "/notification-attempt",
                        "POST",
                        firstPayload);
            });
            Future<HttpResponse<byte[]>> second = executor.submit(() -> {
                start.await();
                return request(
                        replicaB,
                        BASE_PATH + "/conformance-v2/runs/" + runId + "/notification-attempt",
                        "POST",
                        secondPayload);
            });
            start.countDown();
            requireStatus(first.get(5L, TimeUnit.SECONDS), 200, "first concurrent notification recording");
            requireStatus(second.get(5L, TimeUnit.SECONDS), 200, "second concurrent notification recording");
        }

        List<NotificationAttempt> attempts = notificationAttempts(runId);
        Set<String> payloads = new HashSet<>();
        attempts.forEach(attempt -> payloads.add(new String(attempt.content(), StandardCharsets.US_ASCII)));
        if (attempts.size() != 2 || !payloads.equals(Set.of("replica-a", "replica-b"))) {
            throw new AssertionError("concurrent notification attempts were overwritten: " + payloads);
        }
    }

    void verifyClockDrivenRateChangeComponentRestart() throws Exception {
        ChildProvider initial = (ChildProvider) startInitialProvider();
        String runId = "rate-change-component-" + UUID.randomUUID().toString().replace("-", "");
        requireStatus(
                request(
                        initial,
                        BASE_PATH + "/runs",
                        "POST",
                        objectMapper.writeValueAsBytes(rateChangeStartRequest(runId))),
                200,
                "rate-change component launch");

        Path runDirectory = runDirectory(runId);
        try {
            awaitFile(
                    runDirectory.resolve("clock-driven-rate-change-installation.json"),
                    Duration.ofSeconds(10),
                    "rate-change installation manifest");
        } catch (AssertionError timeout) {
            throw new AssertionError(
                    timeout.getMessage() + "; providerLog=" + Files.readString(initial.log()),
                    timeout);
        }
        awaitFile(
                runDirectory.resolve("rate-change-fanout.json"),
                Duration.ofSeconds(10),
                "rate-change fanout evidence");
        Map<String, Object> fanout = objectMapper.readValue(
                runDirectory.resolve("rate-change-fanout.json").toFile(),
                MAP_TYPE);
        if (!runId.equals(fanout.get("simulationRunId"))
                || ((Number) fanout.get("matchedAgents")).intValue() != 2
                || list(fanout.get("results")).size() != 2
                || list(fanout.get("decisionTriggers")).size() != 1
                || Integer.parseInt(Files.readString(
                        runDirectory.resolve("rate-change-fanout-count.txt")).trim()) != 1) {
            throw new AssertionError("rate-change launch did not execute one two-agent fanout");
        }
        assertRateChangeFinalStates(fanout);

        initial.terminate();
        ChildProvider restarted = startProvider("RATE_CHANGE_RESTART");
        currentProvider = restarted;
        requireStatus(
                request(
                        restarted,
                        BASE_PATH + "/conformance-v2/runs/" + runId + "/ready",
                        "POST",
                        new byte[0]),
                200,
                "rate-change restart readiness");

        String capability = "private-rate-change-audit:" + UUID.randomUUID();
        Files.writeString(
                runDirectory.resolve("expected-correlation-capability.txt"),
                capability,
                StandardCharsets.US_ASCII);
        String operationId = "scenario-lab-finalize:"
                + sha256((runId + BankingRemotePolicyWindTunnelService.PROVIDER_REVISION)
                        .getBytes(StandardCharsets.UTF_8));
        requireStatus(
                request(
                        restarted,
                        BASE_PATH + "/runs/" + runId + "/lifecycle",
                        "POST",
                        objectMapper.writeValueAsBytes(Map.of(
                                "action", "FINALIZE",
                                "operationId", operationId)),
                        Map.of("X-Astraform-Opportunity-Correlation-Capability", capability)),
                200,
                "rate-change restart finalization");
        awaitFile(
                runDirectory.resolve("correlation-audit-authenticated"),
                Duration.ofSeconds(5),
                "authenticated correlation audit");
        if (!capability.equals(Files.readString(
                        runDirectory.resolve("correlation-audit-authenticated"),
                        StandardCharsets.US_ASCII))
                || Integer.parseInt(Files.readString(
                        runDirectory.resolve("rate-change-fanout-count.txt")).trim()) != 1) {
            throw new AssertionError(
                    "restart changed fanout ownership or bypassed private audit authentication");
        }

        HttpResponse<byte[]> summaryResponse = request(
                restarted,
                BASE_PATH + "/runs/" + runId + "/artifacts/summary",
                "GET",
                new byte[0]);
        requireStatus(summaryResponse, 200, "rate-change provider summary");
        Map<String, Object> summary = objectMapper.readValue(summaryResponse.body(), MAP_TYPE);
        Map<String, Object> proof = map(summary.get("clockDrivenInterventionProof"));
        Map<String, Object> runConfig = map(summary.get("runConfig"));
        Map<String, Object> decisionReadiness = map(summary.get("decisionReadiness"));
        if (!"TECHNICAL_VALIDATION_ONLY".equals(proof.get("status"))
                || !Boolean.TRUE.equals(proof.get("chainIntegrityVerified"))
                || !Boolean.FALSE.equals(proof.get("realizedReactionVerified"))
                || ((Number) proof.get("installedCustomers")).intValue() != 2
                || ((Number) proof.get("eligibleCustomers")).intValue() != 1
                || ((Number) proof.get("generatedOpportunities")).intValue() != 1
                || ((Number) proof.get("correlatedDecisions")).intValue() != 1
                || ((Number) proof.get("realizedCustomerReactions")).intValue() != 0
                || ((Number) proof.get("technicalValidationDecisions")).intValue() != 1
                || !Boolean.TRUE.equals(runConfig.get(
                        "clockDrivenInterventionChainIntegrityVerified"))
                || !Boolean.FALSE.equals(runConfig.get(
                        "clockDrivenInterventionRealizedReactionVerified"))
                || !Boolean.FALSE.equals(runConfig.get(
                        "clockDrivenInterventionProofVerified"))
                || !Boolean.FALSE.equals(runConfig.get("opportunityAgentAuditVerified"))
                || !"PARTIAL_EVIDENCE".equals(decisionReadiness.get("status"))
                || !"WATCH".equals(decisionReadiness.get("recommendation"))
                || runConfig.keySet().stream().anyMatch(key -> key.toLowerCase().contains("correlation"))) {
            throw new AssertionError(
                    "restarted provider report did not preserve technical-only no-token truth: proof="
                            + proof
                            + ", runConfig="
                            + runConfig
                            + ", decisionReadiness="
                            + decisionReadiness
                            + ", providerLog="
                            + Files.readString(restarted.log()));
        }
    }

    private PendingNotificationRun prepareCrashedPendingNotification() throws Exception {
        ChildProvider initial = (ChildProvider) startInitialProvider();
        String runId = "notification-restart-" + UUID.randomUUID().toString().replace("-", "");
        String providerRevision = BankingRemotePolicyWindTunnelService.PROVIDER_REVISION;
        requireStatus(
                request(
                        initial,
                        BASE_PATH + "/runs",
                        "POST",
                        objectMapper.writeValueAsBytes(v2StartRequest(runId))),
                200,
                "notification recovery run start");
        requireStatus(
                request(
                        initial,
                        BASE_PATH + "/conformance-v2/runs/" + runId + "/ready",
                        "POST",
                        new byte[0]),
                200,
                "notification recovery run readiness");

        String operationId = "scenario-lab-finalize:"
                + sha256((runId + providerRevision).getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                runDirectory(runId).resolve("crash-after-notification-failure"),
                "CRASH",
                StandardCharsets.US_ASCII);
        try {
            request(
                    initial,
                    BASE_PATH + "/runs/" + runId + "/lifecycle",
                    "POST",
                    objectMapper.writeValueAsBytes(Map.of(
                            "action", "FINALIZE",
                            "operationId", operationId)));
        } catch (IOException expectedCrash) {
            // The child is deliberately halted after recording the first failed delivery.
        }
        if (!initial.process().waitFor(5L, TimeUnit.SECONDS)) {
            throw new AssertionError("initial provider did not crash after failed notification");
        }

        List<NotificationAttempt> failedAttempts = notificationAttempts(runId);
        if (failedAttempts.size() != 1
                || failedAttempts.getFirst().outcome() != NotificationDeliveryOutcome.FAILED) {
            throw new AssertionError("initial provider did not persist exactly one failed notification");
        }
        Map<String, Object> pendingReceipt = objectMapper.readValue(
                runDirectory(runId).resolve("finalization-receipt.json").toFile(),
                MAP_TYPE);
        if (!"PUBLISHED".equals(pendingReceipt.get("state"))
                || !"PENDING".equals(pendingReceipt.get("completionNotificationState"))) {
            throw new AssertionError("crashed provider did not leave durable pending notification debt");
        }
        return new PendingNotificationRun(runId, finalizeRequestCount(runId));
    }

    private void assertRateChangeFinalStates(Map<String, Object> fanout) {
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        for (Object raw : list(fanout.get("finalStates"))) {
            Map<String, Object> row = map(raw);
            states.put(String.valueOf(row.get("agentId")), map(row.get("state")));
        }
        if (states.size() != 2
                || !"0.24".equals(accountRate(states.get("customer-001"), "cc-001"))
                || !"".equals(accountRate(states.get("customer-002"), "tx-001"))) {
            throw new AssertionError(
                    "real banking fanout did not preserve eligible and unaffected customer states");
        }
    }

    private static String accountRate(Map<String, Object> state, String accountId) {
        if (state == null) {
            return "";
        }
        for (Object raw : list(state.get("accounts"))) {
            Map<String, Object> account = map(raw);
            if (accountId.equals(account.get("accountId"))) {
                return String.valueOf(account.getOrDefault("interestRate", ""));
            }
        }
        return "";
    }

    private Map<String, Object> rateChangeStartRequest(String runId) throws Exception {
        List<String> agentIds = List.of("customer-001", "customer-002");
        byte[] cohort = objectMapper.writeValueAsBytes(List.of(
                Map.ofEntries(
                        Map.entry("personaName", agentIds.get(0)),
                        Map.entry("domainId", "banking"),
                        Map.entry("agentType", "Customer"),
                        Map.entry("provider", "stub"),
                        Map.entry("model", "stub"),
                        Map.entry("interactionMode", "SIMULATION"),
                        Map.entry("autonomousDecisionEnabled", false),
                        Map.entry("traits", List.of("rate-change-eligible")),
                        Map.entry("goals", List.of()),
                        Map.entry("initialAccounts", List.of(Map.ofEntries(
                                Map.entry("accountId", "cc-001"),
                                Map.entry("name", "Rewards Card"),
                                Map.entry("productCode", "CC001"),
                                Map.entry("balance", -1200),
                                Map.entry("limit", 3000),
                                Map.entry("currency", "AUD")))),
                        Map.entry("scheduledWork", List.of())),
                Map.ofEntries(
                        Map.entry("personaName", agentIds.get(1)),
                        Map.entry("domainId", "banking"),
                        Map.entry("agentType", "Customer"),
                        Map.entry("provider", "stub"),
                        Map.entry("model", "stub"),
                        Map.entry("interactionMode", "SIMULATION"),
                        Map.entry("autonomousDecisionEnabled", false),
                        Map.entry("traits", List.of("rate-change-unaffected")),
                        Map.entry("goals", List.of()),
                        Map.entry("initialAccounts", List.of(Map.ofEntries(
                                Map.entry("accountId", "tx-001"),
                                Map.entry("name", "Everyday Account"),
                                Map.entry("productCode", "TX001"),
                                Map.entry("balance", 500),
                                Map.entry("currency", "AUD")))),
                        Map.entry("scheduledWork", List.of()))));
        String cohortDigest = sha256(cohort);
        String identityDigest = BankingClockDrivenRateChangePlan.agentIdentitySetDigest(agentIds);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("schemaVersion", "scenario_lab_pinned_cohort_activation.v1");
        settings.put("agentDecisionMode", "STUB_LLM");
        settings.put("agentDecisionCadence", "EVENT_ONLY");
        settings.put("agentDecisionIntervalMinutes", 0);
        settings.put("provider", "stub");
        settings.put("model", "stub");
        settings.put("interactionMode", "SIMULATION");
        settings.put("autonomousDecisionEnabled", false);
        byte[] settingsBytes = objectMapper.writeValueAsBytes(settings);
        String settingsDigest = sha256(settingsBytes);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("blackBoxRateChangeAcceptance", true);
        request.put("presetId", "credit-apr-lift");
        request.put("simulationRunId", runId);
        request.put("executionMode", "CLOCK_DRIVEN_HYBRID");
        request.put("runPath", "CLOCK_DRIVEN_SIMULATION");
        request.put("agentDecisionMode", "STUB_LLM");
        request.put("agentDecisionCadence", "EVENT_ONLY");
        request.put("simulationStartEpochMs", 1_770_000_000_000L);
        request.put("simulationHorizonDays", 30);
        request.put("prePolicyDays", 10);
        request.put("postPolicyDays", 20);
        request.put("productCode", "CC001");
        request.put("baselineRate", "0.19");
        request.put("candidateRate", "0.24");
        request.put("cohortId", "rate-change-component-cohort");
        request.put("cohortSelection", Map.of("cohortId", "rate-change-component-cohort"));
        request.put("cohortTargetSize", 2);
        request.put("customerCount", 2);
        request.put("activationId", runId + ":activation");
        request.put("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2");
        request.put("cohortArtifactDigest", cohortDigest);
        request.put("cohortAgentIdentitySetDigest", identityDigest);
        request.put("cohortArtifactSnapshot", Map.of(
                "schemaVersion", "scenario_lab_cohort_artifact_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64",
                "sha256", cohortDigest,
                "content", Base64.getEncoder().encodeToString(cohort)));
        request.put("activationSettingsDigest", settingsDigest);
        request.put("activationSettingsSnapshot", Map.of(
                "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64url",
                "sha256", settingsDigest,
                "content", Base64.getUrlEncoder().withoutPadding().encodeToString(settingsBytes)));
        return request;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static void awaitFile(Path path, Duration timeout, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (Files.notExists(path) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        if (Files.notExists(path)) {
            throw new AssertionError("timed out waiting for " + description);
        }
    }

    private List<CallbackEvent> awaitCallbackEvents(
            String runId,
            int expectedCount,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<CallbackEvent> events;
        do {
            events = callbackEvents(runId);
            if (events.size() >= expectedCount) {
                return events;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for notification callback events");
    }

    private List<CallbackEvent> callbackEvents(String runId) throws Exception {
        Path directory = runDirectory(runId).resolve("notification-callbacks");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<CallbackEvent> events = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path eventPath : paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                Map<String, Object> event = objectMapper.readValue(eventPath.toFile(), MAP_TYPE);
                events.add(new CallbackEvent(
                        ((Number) event.get("sequence")).intValue(),
                        String.valueOf(event.get("event")),
                        ((Number) event.get("processId")).longValue(),
                        ((Number) event.get("timestampEpochMs")).longValue(),
                        ((Number) event.get("activeCallbacks")).intValue(),
                        String.valueOf(event.get("payloadSha256")),
                        String.valueOf(event.get("idempotencyKey"))));
            }
        }
        return List.copyOf(events);
    }

    private void awaitConfirmedReceipt(String runId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Map<String, Object> receipt = objectMapper.readValue(
                    runDirectory(runId).resolve("finalization-receipt.json").toFile(),
                    MAP_TYPE);
            if ("CONFIRMED".equals(receipt.get("completionNotificationState"))) {
                return;
            }
            Thread.sleep(20L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("timed out waiting for durable notification confirmation");
    }

    @Override
    public void close() throws Exception {
        for (ChildProvider provider : processes) {
            provider.terminate();
        }
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private synchronized ChildProvider startProvider(String role) throws Exception {
        Path ready = root.resolve("provider-" + UUID.randomUUID() + ".port");
        Path log = root.resolve("provider-" + UUID.randomUUID() + ".log");
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classPath,
                BankingPolicyWindTunnelV2ProviderProcess.class.getName(),
                store.toString(),
                ready.toString(),
                role);
        builder.environment().put("ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MILLIS", "1000");
        builder.environment().put("ASTRAFORM_HYBRID_DECISION_AUDIT_SETTLE_POLL_MILLIS", "100");
        builder.environment().put("ASTRAFORM_HYBRID_DECISION_AUDIT_STABLE_SAMPLES", "1");
        builder.environment().put("ASTRAFORM_RUNTIME_TICK_DRAIN_STABLE_SAMPLES", "1");
        builder.environment().put("ASTRAFORM_RUNTIME_TICK_DRAIN_TIMEOUT_MILLIS", "1000");
        if ("NOTIFICATION_RESTART".equals(role)) {
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS", "100");
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS", "400");
        }
        if ("NOTIFICATION_PEER".equals(role)) {
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS", "5000");
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS", "5000");
        }
        if ("NOTIFICATION_EXCLUSIVITY".equals(role)) {
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_BASE_MILLIS", "100");
            builder.environment().put("ASTRAFORM_FINALIZATION_NOTIFICATION_RETRY_MAX_MILLIS", "100");
        }
        Process process = builder
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!Files.isRegularFile(ready) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        if (!Files.isRegularFile(ready) || !process.isAlive()) {
            String diagnostics = Files.isRegularFile(log) ? Files.readString(log) : "";
            process.destroyForcibly();
            throw new AssertionError("banking V2 provider process failed to start: " + diagnostics);
        }
        int port = Integer.parseInt(Files.readString(ready).trim());
        ChildProvider provider = new ChildProvider(
                URI.create("http://127.0.0.1:" + port),
                process,
                log);
        processes.add(provider);
        return provider;
    }

    private HttpResponse<byte[]> storageRequest(
            ProviderProcess provider,
            String suffix,
            String method,
            byte[] content) throws Exception {
        return request(provider, BASE_PATH + "/conformance-v2/storage/" + suffix, method, content);
    }

    private List<Long> notificationAttemptTimestamps(String runId) throws Exception {
        Path directory = runDirectory(runId).resolve("notifications");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Long> timestamps = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path content : paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                String stem = content.getFileName().toString().replace(".json", "");
                timestamps.add(Long.parseLong(Files.readString(directory.resolve(stem + ".timestamp")).trim()));
            }
        }
        return List.copyOf(timestamps);
    }

    private HttpResponse<byte[]> request(
            ProviderProcess provider,
            String path,
            String method,
            byte[] content) throws Exception {
        return request(provider, path, method, content, Map.of());
    }

    private HttpResponse<byte[]> request(
            ProviderProcess provider,
            String path,
            String method,
            byte[] content,
            Map<String, String> headers) throws Exception {
        HttpRequest.BodyPublisher body = content.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(content);
        HttpRequest.Builder request = HttpRequest.newBuilder(provider.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header(
                        "X-Astraform-Expected-Provider-Revision",
                        BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
        headers.forEach(request::header);
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private ArtifactFault replaceArtifact(String artifactRef, byte[] replacement) throws Exception {
        Path artifact = checkedArtifact(artifactRef);
        byte[] original = Files.readAllBytes(artifact);
        if (replacement == null) {
            Files.delete(artifact);
        } else {
            Files.write(artifact, replacement);
        }
        return () -> Files.write(artifact, original);
    }

    private Path checkedArtifact(String artifactRef) {
        Path artifact = store.resolve(artifactRef).normalize();
        if (!artifact.startsWith(store) || !Files.isRegularFile(artifact)) {
            throw new AssertionError("artifact fault target is invalid");
        }
        return artifact;
    }

    private void writePreparedAction(String runId, String action) throws IOException {
        Path marker = runDirectory(runId).resolve("prepared-action.txt");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, action);
    }

    private Map<String, Object> v2StartRequest(String runId) throws Exception {
        String personaName = "Notification Recovery Customer";
        byte[] cohort = objectMapper.writeValueAsBytes(List.of(Map.ofEntries(
                Map.entry("personaName", personaName),
                Map.entry("domainId", "banking"),
                Map.entry("agentType", "Customer"),
                Map.entry("provider", "stub"),
                Map.entry("model", "stub"),
                Map.entry("interactionMode", "HYBRID"),
                Map.entry("traits", List.of("conformance")),
                Map.entry("goals", List.of()),
                Map.entry("initialAccounts", List.of()),
                Map.entry("scheduledWork", List.of()))));
        String cohortDigest = sha256(cohort);
        byte[] identities = objectMapper.writeValueAsBytes(List.of(personaName));
        String identityDigest = sha256(identities);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("schemaVersion", "scenario_lab_pinned_cohort_activation.v1");
        settings.put("agentDecisionMode", "STUB_LLM");
        settings.put("agentDecisionCadence", "EVENT_ONLY");
        settings.put("agentDecisionIntervalMinutes", 0);
        settings.put("provider", "stub");
        settings.put("model", "stub");
        settings.put("interactionMode", "HYBRID");
        settings.put("autonomousDecisionEnabled", false);
        byte[] settingsBytes = objectMapper.writeValueAsBytes(settings);
        String settingsDigest = sha256(settingsBytes);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("presetId", "saver-repricing");
        request.put("simulationRunId", runId);
        request.put("executionMode", "CLOCK_DRIVEN_HYBRID");
        request.put("runPath", "CLOCK_DRIVEN_SIMULATION");
        request.put("agentDecisionMode", "STUB_LLM");
        request.put("agentDecisionCadence", "EVENT_ONLY");
        request.put("simulationHorizonDays", 1);
        request.put("prePolicyDays", 0);
        request.put("postPolicyDays", 1);
        request.put("cohortId", "notification-recovery-cohort");
        request.put("cohortTargetSize", 1);
        request.put("customerCount", 1);
        request.put("activationId", runId + ":activation");
        request.put("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2");
        request.put("cohortArtifactDigest", cohortDigest);
        request.put("cohortAgentIdentitySetDigest", identityDigest);
        request.put("cohortArtifactSnapshot", Map.of(
                "schemaVersion", "scenario_lab_cohort_artifact_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64",
                "sha256", cohortDigest,
                "content", Base64.getEncoder().encodeToString(cohort)));
        request.put("activationSettingsDigest", settingsDigest);
        request.put("activationSettingsSnapshot", Map.of(
                "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                "contentType", "application/json",
                "encoding", "base64url",
                "sha256", settingsDigest,
                "content", Base64.getUrlEncoder().withoutPadding().encodeToString(settingsBytes)));
        return request;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private Path runDirectory(String runId) {
        return store.resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT).resolve(runId).normalize();
    }

    private ChildProvider current() {
        ChildProvider provider = currentProvider;
        if (provider == null || !provider.processHandle().isAlive()) {
            throw new IllegalStateException("no active V2 provider process");
        }
        return provider;
    }

    private static void requireStatus(HttpResponse<byte[]> response, int expected, String operation) {
        if (response.statusCode() != expected) {
            throw new AssertionError(operation
                    + " returned HTTP "
                    + response.statusCode()
                    + ": "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    private record ChildProvider(URI baseUri, Process process, Path log) implements ProviderProcess {
        @Override
        public ProcessHandle processHandle() {
            return process.toHandle();
        }

        @Override
        public void terminate() throws Exception {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
            }
        }
    }

    private record PendingNotificationRun(String runId, int finalizeRequestCount) { }

    private record CallbackEvent(
            int sequence,
            String event,
            Long processId,
            long timestampEpochMs,
            int activeCallbacks,
            String payloadSha256,
            String idempotencyKey) { }
}
