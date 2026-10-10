package banking.simulator.remote.domain;

import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelManifest;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;

public final class BankingRemotePolicyWindTunnelExecutionContext implements PolicyWindTunnelExecutionContext {
    private static final String RUN_STATE_SCHEMA = "banking_policy_wind_tunnel_provider_state.v1";
    private static final String RUN_STATE_FILE = "provider-run-state.json";
    private static final String RUN_STATE_LOCK_FILE = "provider-run-state.lock";
    private static final int MAX_RUN_STATE_BYTES = 8 * 1024 * 1024;
    public static final String LAUNCH_DIGEST_METADATA_KEY = "astraformProviderLaunchDigest";
    public static final String EXECUTION_CLAIM_OWNER_METADATA_KEY = "astraformProviderExecutionClaimOwner";
    public static final String EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY =
            "astraformProviderExecutionClaimExpiresAtEpochMs";
    public static final long EXECUTION_CLAIM_LEASE_MS = 24 * 60 * 60 * 1000L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Map<Path, Object> PROCESS_RUN_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final String agentRuntimeUrl;
    private final String simulatorUrl;
    private final String bankingProductEndpointUrl;
    private final Map<String, StoredRunData> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> artifacts = new ConcurrentHashMap<>();
    private final ThreadLocal<ExecutionClaim> activeExecutionClaim = new ThreadLocal<>();
    private volatile boolean platformOwnedV2StorageReady;

    BankingRemotePolicyWindTunnelExecutionContext(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl,
            String bankingProductEndpointUrl) {
        this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .build();
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.agentRuntimeUrl = stripTrailingSlash(agentRuntimeUrl);
        this.simulatorUrl = stripTrailingSlash(simulatorUrl);
        this.bankingProductEndpointUrl = stripTrailingSlash(bankingProductEndpointUrl);
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
        return switch (stringValue(dependencyId)) {
            case "banking-product-endpoint" -> bankingProductEndpointUrl;
            default -> "";
        };
    }

    boolean verifySharedDurableStorage() {
        Path probeRoot = durableRunStateRoot()
                .resolve(".astraform-storage-probe")
                .resolve(UUID.randomUUID().toString())
                .normalize();
        Path lockPath = probeRoot.resolve("probe.lock");
        Path published = probeRoot.resolve("probe.current");
        Path attempted = probeRoot.resolve("child.attempted");
        byte[] parentMarker = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        byte[] childMarker = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        Path pending = null;
        Process child = null;
        try {
            Files.createDirectories(probeRoot);
            try (FileChannel lockChannel = FileChannel.open(
                            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                pending = Files.createTempFile(probeRoot, "probe.", ".pending");
                writeAndForce(pending, parentMarker);
                Files.move(
                        pending,
                        published,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                pending = null;
                forceDirectory(probeRoot);

                child = startStorageProbeChild(
                        lockPath, published, attempted, parentMarker, childMarker);
                long attemptedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                while (Files.notExists(attempted) && child.isAlive()) {
                    if (System.nanoTime() >= attemptedDeadline) {
                        return false;
                    }
                    Thread.sleep(10L);
                }
                if (Files.notExists(attempted)
                        || !child.isAlive()
                        || !java.util.Arrays.equals(parentMarker, Files.readAllBytes(published))) {
                    return false;
                }
            }
            if (!child.waitFor(3L, TimeUnit.SECONDS)) {
                return false;
            }
            return child.exitValue() == 0
                    && java.util.Arrays.equals(childMarker, Files.readAllBytes(published));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try {
                    child.waitFor(1L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            deleteQuietly(pending);
            deleteQuietly(attempted);
            deleteQuietly(published);
            deleteQuietly(lockPath);
            deleteQuietly(probeRoot);
        }
    }

    private Process startStorageProbeChild(
            Path lockPath,
            Path published,
            Path attempted,
            byte[] expectedMarker,
            byte[] replacementMarker) throws IOException {
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path", ""));
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (classPath.indexOf(java.io.File.pathSeparatorChar) < 0 && classPath.endsWith(".jar")) {
            command.add("-Dloader.main=" + BankingRemotePolicyWindTunnelStorageProbeProcess.class.getName());
            command.add("-cp");
            command.add(classPath);
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(BankingRemotePolicyWindTunnelStorageProbeProcess.class.getName());
        }
        command.add(lockPath.toString());
        command.add(published.toString());
        command.add(attempted.toString());
        command.add(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(expectedMarker));
        command.add(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(replacementMarker));
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void writeAndForce(Path path, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(content));
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed readiness-probe cleanup does not turn a failed proof green.
        }
    }

    boolean configurePlatformOwnedV2Storage(boolean sharedStorageEnabled) {
        platformOwnedV2StorageReady = sharedStorageEnabled && verifySharedDurableStorage();
        return platformOwnedV2StorageReady;
    }

    public boolean platformOwnedV2StorageReady() {
        return platformOwnedV2StorageReady;
    }

    Path durableRunStateRoot() {
        return workspaceRoot.resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT).normalize();
    }

    @Override
    public void createExperiment(
            String experimentId,
            String name,
            String description,
            String domainId,
            String owner,
            String status) {
        // The remote provider owns execution state locally; experiment-service
        // mirrors remote runs through its proxy adapter.
    }

    @Override
    public void createRun(PolicyWindTunnelRunCreateRequest request) {
        withRunLock(request.runId(), () -> {
            DurableRunState existing = readDurableRunState(request.runId());
            if (existing != null) {
                cache(existing);
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            DurableRunState created = new DurableRunState(new StoredRunData(
                    request.runId(),
                    request.status(),
                    request.summaryPath(),
                    request.bundlePath(),
                    null,
                    request.startedAtEpochMs(),
                    now,
                    now,
                    copyMap(request.metadata())), Map.of());
            writeDurableRunState(created);
            cache(created);
            return null;
        });
    }

    public LaunchAdmission admitLaunch(
            PolicyWindTunnelRunCreateRequest request,
            String launchDigest,
            String requestedOwner,
            long nowEpochMs) {
        requireLaunchIdentity(request.runId(), launchDigest, requestedOwner, nowEpochMs);
        return withRunLock(request.runId(), () -> {
            DurableRunState existing = readDurableRunState(request.runId());
            if (existing == null) {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                Map<String, Object> metadata = launchMetadata(
                        request.metadata(), launchDigest, requestedOwner, nowEpochMs);
                DurableRunState created = new DurableRunState(new StoredRunData(
                        request.runId(),
                        request.status(),
                        request.summaryPath(),
                        request.bundlePath(),
                        null,
                        request.startedAtEpochMs(),
                        now,
                        now,
                        metadata), Map.of());
                writeDurableRunState(created);
                cache(created);
                return new LaunchAdmission(
                        LaunchAdmissionOutcome.CREATED,
                        created.run().toStoredRun(),
                        new ExecutionClaim(request.runId(), launchDigest, requestedOwner),
                        LaunchExecutionDirective.START_NEW);
            }

            cache(existing);
            Map<String, Object> metadata = new LinkedHashMap<>(existing.run().metadata());
            if (!launchDigest.equals(metadata.get(LAUNCH_DIGEST_METADATA_KEY))) {
                return new LaunchAdmission(
                        LaunchAdmissionOutcome.CONFLICT,
                        existing.run().toStoredRun(),
                        null,
                        LaunchExecutionDirective.NONE);
            }
            if (terminalStatus(existing.run().status())) {
                return new LaunchAdmission(
                        LaunchAdmissionOutcome.REPLAY,
                        existing.run().toStoredRun(),
                        null,
                        LaunchExecutionDirective.NONE);
            }
            long expiresAt = exactEpochMs(metadata.get(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY), 0L);
            if (expiresAt > nowEpochMs || !phaseRequiresRecovery(existing.run())) {
                return new LaunchAdmission(
                        LaunchAdmissionOutcome.REPLAY,
                        existing.run().toStoredRun(),
                        null,
                        LaunchExecutionDirective.NONE);
            }

            metadata.put(EXECUTION_CLAIM_OWNER_METADATA_KEY, requestedOwner);
            metadata.put(
                    EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY,
                    claimExpiry(nowEpochMs));
            StoredRunData current = existing.run();
            DurableRunState claimed = new DurableRunState(new StoredRunData(
                    current.runId(),
                    current.status(),
                    current.summaryPath(),
                    current.bundlePath(),
                    current.evaluationRunId(),
                    current.startedAtEpochMs(),
                    current.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    Map.copyOf(metadata)), existing.artifacts());
            writeDurableRunState(claimed);
            cache(claimed);
            return new LaunchAdmission(
                    LaunchAdmissionOutcome.REPLAY,
                    claimed.run().toStoredRun(),
                    new ExecutionClaim(request.runId(), launchDigest, requestedOwner),
                    LaunchExecutionDirective.RECOVER_EXISTING);
        });
    }

    public void executeWithClaim(ExecutionClaim claim, Runnable execution) {
        if (claim == null || execution == null) {
            throw new IllegalArgumentException("Scenario Lab execution claim and work are required");
        }
        ExecutionClaim previous = activeExecutionClaim.get();
        if (previous != null) {
            throw new IllegalStateException("Scenario Lab execution claim scopes cannot be nested");
        }
        assertAndRenewExecutionClaim(claim, System.currentTimeMillis());
        activeExecutionClaim.set(claim);
        try {
            execution.run();
        } finally {
            activeExecutionClaim.remove();
            releaseTerminalExecutionClaim(claim);
        }
    }

    public Optional<ExecutionClaim> currentExecutionClaim() {
        return Optional.ofNullable(activeExecutionClaim.get());
    }

    public void renewCurrentExecutionClaim() {
        ExecutionClaim claim = activeExecutionClaim.get();
        if (claim == null) {
            throw new IllegalStateException("Scenario Lab worker has no active execution claim");
        }
        assertAndRenewExecutionClaim(claim, System.currentTimeMillis());
    }

    @Override
    public Optional<PolicyWindTunnelStoredRun> findRun(String runId) {
        return withRunLock(runId, () -> {
            DurableRunState durable = readDurableRunState(runId);
            if (durable != null) {
                cache(durable);
                return Optional.of(durable.run().toStoredRun());
            }
            StoredRunData cached = runs.get(runId);
            return cached == null ? Optional.empty() : Optional.of(cached.toStoredRun());
        });
    }

    @Override
    public void updateRun(String runId, PolicyWindTunnelRunUpdate update) {
        withRunLock(runId, () -> {
            DurableRunState durable = currentState(runId);
            if (durable == null) {
                return null;
            }
            StoredRunData current = durable.run();
            String nextStatus = firstNonBlank(update.status(), current.status());
            if (terminalStatus(current.status())
                    && !current.status().equalsIgnoreCase(nextStatus)) {
                throw new IllegalStateException(
                        "Scenario Lab terminal run state cannot be changed");
            }
            ExecutionClaim claim = activeExecutionClaim.get();
            Map<String, Object> currentMetadata = renewActiveClaimMetadata(runId, current.metadata());
            Map<String, Object> nextMetadata = preserveExecutionClaim(
                    currentMetadata, copyMap(update.metadata()), nextStatus, claim);
            DurableRunState updated = new DurableRunState(new StoredRunData(
                    current.runId(),
                    nextStatus,
                    firstNonBlank(update.summaryPath(), current.summaryPath()),
                    firstNonBlank(update.bundlePath(), current.bundlePath()),
                    current.evaluationRunId(),
                    current.startedAtEpochMs(),
                    current.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    nextMetadata), durable.artifacts());
            writeDurableRunState(updated);
            cache(updated);
            return null;
        });
    }

    @Override
    public void registerArtifact(String runId, String artifactType, String artifactRef, String contentType) {
        withRunLock(runId, () -> {
            DurableRunState durable = currentState(runId);
            if (durable == null) {
                throw new IllegalArgumentException("Unknown runId: " + runId);
            }
            StoredRunData current = durable.run();
            Map<String, Object> metadata = renewActiveClaimMetadata(runId, current.metadata());
            Map<String, String> updatedArtifacts = new LinkedHashMap<>(durable.artifacts());
            updatedArtifacts.put(artifactType, artifactRef);
            DurableRunState updated = new DurableRunState(new StoredRunData(
                    current.runId(),
                    current.status(),
                    current.summaryPath(),
                    current.bundlePath(),
                    current.evaluationRunId(),
                    current.startedAtEpochMs(),
                    current.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    Map.copyOf(metadata)), Map.copyOf(updatedArtifacts));
            writeDurableRunState(updated);
            cache(updated);
            return null;
        });
    }

    @Override
    public Map<String, String> registeredArtifacts(String runId) {
        return withRunLock(runId, () -> {
            DurableRunState durable = currentState(runId);
            if (durable == null) {
                return Map.of();
            }
            cache(durable);
            return Map.copyOf(durable.artifacts());
        });
    }

    @Override
    public void linkEvaluation(String runId, String evaluationRunId) {
        withRunLock(runId, () -> {
            DurableRunState durable = currentState(runId);
            if (durable == null) {
                return null;
            }
            StoredRunData current = durable.run();
            Map<String, Object> metadata = renewActiveClaimMetadata(runId, current.metadata());
            DurableRunState updated = new DurableRunState(new StoredRunData(
                    current.runId(),
                    current.status(),
                    current.summaryPath(),
                    current.bundlePath(),
                    evaluationRunId,
                    current.startedAtEpochMs(),
                    current.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    Map.copyOf(metadata)), durable.artifacts());
            writeDurableRunState(updated);
            cache(updated);
            return null;
        });
    }

    private DurableRunState currentState(String runId) {
        DurableRunState durable = readDurableRunState(runId);
        if (durable != null) {
            return durable;
        }
        StoredRunData cachedRun = runs.get(runId);
        if (cachedRun == null) {
            return null;
        }
        return new DurableRunState(cachedRun, Map.copyOf(artifacts.getOrDefault(runId, Map.of())));
    }

    private void cache(DurableRunState state) {
        runs.put(state.run().runId(), state.run());
        artifacts.put(state.run().runId(), new ConcurrentHashMap<>(state.artifacts()));
    }

    private Map<String, Object> renewActiveClaimMetadata(
            String runId,
            Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>(source);
        ExecutionClaim claim = activeExecutionClaim.get();
        if (claim != null) {
            requireClaimOwner(runId, metadata, claim);
            metadata.put(
                    EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY,
                    claimExpiry(System.currentTimeMillis()));
        }
        return metadata;
    }

    private Map<String, Object> preserveExecutionClaim(
            Map<String, Object> current,
            Map<String, Object> updated,
            String nextStatus,
            ExecutionClaim claim) {
        Map<String, Object> result = new LinkedHashMap<>(updated);
        Object launchDigest = current.get(LAUNCH_DIGEST_METADATA_KEY);
        if (launchDigest != null) {
            result.put(LAUNCH_DIGEST_METADATA_KEY, launchDigest);
        }
        if (terminalStatus(nextStatus)) {
            if (claim == null) {
                result.remove(EXECUTION_CLAIM_OWNER_METADATA_KEY);
                result.remove(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY);
            } else {
                result.put(EXECUTION_CLAIM_OWNER_METADATA_KEY, claim.owner());
                result.put(
                        EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY,
                        current.get(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY));
            }
            return Map.copyOf(result);
        }
        Object currentOwner = current.get(EXECUTION_CLAIM_OWNER_METADATA_KEY);
        if (currentOwner != null) {
            result.put(EXECUTION_CLAIM_OWNER_METADATA_KEY, currentOwner);
            Object currentExpiry = current.get(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY);
            if (currentExpiry != null) {
                result.put(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY, currentExpiry);
            }
        }
        return Map.copyOf(result);
    }

    private void assertAndRenewExecutionClaim(ExecutionClaim claim, long nowEpochMs) {
        withRunLock(claim.runId(), () -> {
            DurableRunState durable = readDurableRunState(claim.runId());
            if (durable == null || terminalStatus(durable.run().status())) {
                throw new StaleExecutionClaimException("Scenario Lab execution claim is no longer active");
            }
            Map<String, Object> metadata = new LinkedHashMap<>(durable.run().metadata());
            requireClaimOwner(claim.runId(), metadata, claim);
            long expiresAt = exactEpochMs(metadata.get(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY), 0L);
            long renewalThreshold = Math.addExact(nowEpochMs, EXECUTION_CLAIM_LEASE_MS / 2L);
            if (expiresAt <= renewalThreshold) {
                metadata.put(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY, claimExpiry(nowEpochMs));
                StoredRunData current = durable.run();
                DurableRunState renewed = new DurableRunState(new StoredRunData(
                        current.runId(),
                        current.status(),
                        current.summaryPath(),
                        current.bundlePath(),
                        current.evaluationRunId(),
                        current.startedAtEpochMs(),
                        current.createdAt(),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        Map.copyOf(metadata)), durable.artifacts());
                writeDurableRunState(renewed);
                cache(renewed);
            }
            return null;
        });
    }

    private void releaseTerminalExecutionClaim(ExecutionClaim claim) {
        withRunLock(claim.runId(), () -> {
            DurableRunState durable = readDurableRunState(claim.runId());
            if (durable == null || !terminalStatus(durable.run().status())) {
                return null;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(durable.run().metadata());
            if (!claim.owner().equals(metadata.get(EXECUTION_CLAIM_OWNER_METADATA_KEY))) {
                return null;
            }
            metadata.remove(EXECUTION_CLAIM_OWNER_METADATA_KEY);
            metadata.remove(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY);
            StoredRunData current = durable.run();
            DurableRunState released = new DurableRunState(new StoredRunData(
                    current.runId(),
                    current.status(),
                    current.summaryPath(),
                    current.bundlePath(),
                    current.evaluationRunId(),
                    current.startedAtEpochMs(),
                    current.createdAt(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    Map.copyOf(metadata)), durable.artifacts());
            writeDurableRunState(released);
            cache(released);
            return null;
        });
    }

    private void requireClaimOwner(
            String runId,
            Map<String, Object> metadata,
            ExecutionClaim claim) {
        if (!runId.equals(claim.runId())
                || !claim.launchDigest().equals(metadata.get(LAUNCH_DIGEST_METADATA_KEY))
                || !claim.owner().equals(metadata.get(EXECUTION_CLAIM_OWNER_METADATA_KEY))) {
            throw new StaleExecutionClaimException("Scenario Lab execution claim ownership changed");
        }
    }

    private Map<String, Object> launchMetadata(
            Map<String, Object> source,
            String launchDigest,
            String owner,
            long nowEpochMs) {
        Map<String, Object> metadata = new LinkedHashMap<>(copyMap(source));
        metadata.put(LAUNCH_DIGEST_METADATA_KEY, launchDigest);
        metadata.put(EXECUTION_CLAIM_OWNER_METADATA_KEY, owner);
        metadata.put(EXECUTION_CLAIM_EXPIRES_AT_METADATA_KEY, claimExpiry(nowEpochMs));
        return Map.copyOf(metadata);
    }

    private static void requireLaunchIdentity(
            String runId,
            String launchDigest,
            String owner,
            long nowEpochMs) {
        if (runId == null || runId.isBlank()
                || launchDigest == null || !launchDigest.matches("sha256:[0-9a-f]{64}")
                || owner == null || owner.isBlank() || owner.length() > 128
                || nowEpochMs < 1L || nowEpochMs > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Scenario Lab launch admission identity is invalid");
        }
    }

    private static long claimExpiry(long nowEpochMs) {
        long expiry = Math.addExact(nowEpochMs, EXECUTION_CLAIM_LEASE_MS);
        if (expiry > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Scenario Lab execution claim expiry is outside I-JSON range");
        }
        return expiry;
    }

    private static boolean phaseRequiresRecovery(StoredRunData run) {
        Object scenarioLab = run.metadata().get("scenarioLab");
        Object phase = scenarioLab instanceof Map<?, ?> policy
                ? policy.get("phase")
                : run.metadata().get("phase");
        return switch (run.status()) {
            case "QUEUED" -> "QUEUED".equals(phase);
            case "RUNNING" -> java.util.Set.of(
                            "QUEUED",
                            "PREPARING_CLOCK",
                            "ACTIVATING_COHORT",
                            "CLOCK_RUNNING")
                    .contains(phase);
            default -> false;
        };
    }

    private Path runRoot(String runId) {
        Path artifactRoot = durableRunStateRoot();
        Path runRoot = artifactRoot.resolve(runId).normalize();
        if (runId == null || runId.isBlank() || !runRoot.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("Scenario Lab runId escapes the provider state root");
        }
        return runRoot;
    }

    private Path runStatePath(String runId) {
        return runRoot(runId).resolve(RUN_STATE_FILE);
    }

    private DurableRunState readDurableRunState(String runId) {
        Path statePath = runStatePath(runId);
        if (Files.notExists(statePath)) {
            return null;
        }
        try {
            byte[] content = Files.readAllBytes(statePath);
            if (content.length == 0 || content.length > MAX_RUN_STATE_BYTES) {
                throw new IllegalStateException("Scenario Lab provider run state size is invalid");
            }
            Map<String, Object> document = objectMapper.readValue(content, MAP_TYPE);
            if (!document.keySet().equals(java.util.Set.of("schemaVersion", "run", "artifacts"))
                    || !RUN_STATE_SCHEMA.equals(document.get("schemaVersion"))) {
                throw new IllegalStateException("Scenario Lab provider run state is invalid");
            }
            Map<String, Object> run = requiredMap(document.get("run"));
            if (!run.keySet().equals(java.util.Set.of(
                    "runId", "status", "summaryPath", "bundlePath", "evaluationRunId",
                    "startedAtEpochMs", "createdAt", "updatedAt", "metadata"))
                    || !runId.equals(requiredString(run.get("runId")))) {
                throw new IllegalStateException("Scenario Lab provider run state is invalid");
            }
            StoredRunData stored = new StoredRunData(
                    runId,
                    requiredString(run.get("status")),
                    nullableString(run.get("summaryPath")),
                    nullableString(run.get("bundlePath")),
                    nullableString(run.get("evaluationRunId")),
                    nullableLong(run.get("startedAtEpochMs")),
                    OffsetDateTime.parse(requiredString(run.get("createdAt"))),
                    OffsetDateTime.parse(requiredString(run.get("updatedAt"))),
                    copyMap(requiredMap(run.get("metadata"))));
            Map<String, Object> rawArtifacts = requiredMap(document.get("artifacts"));
            Map<String, String> storedArtifacts = new LinkedHashMap<>();
            rawArtifacts.forEach((key, value) -> storedArtifacts.put(key, requiredString(value)));
            return new DurableRunState(stored, Map.copyOf(storedArtifacts));
        } catch (IOException | RuntimeException invalidState) {
            throw new IllegalStateException("Scenario Lab provider run state could not be read", invalidState);
        }
    }

    private void writeDurableRunState(DurableRunState state) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", state.run().runId());
        run.put("status", state.run().status());
        run.put("summaryPath", emptyToNull(state.run().summaryPath()));
        run.put("bundlePath", emptyToNull(state.run().bundlePath()));
        run.put("evaluationRunId", emptyToNull(state.run().evaluationRunId()));
        run.put("startedAtEpochMs", state.run().startedAtEpochMs());
        run.put("createdAt", state.run().createdAt().toString());
        run.put("updatedAt", state.run().updatedAt().toString());
        run.put("metadata", state.run().metadata());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", RUN_STATE_SCHEMA);
        document.put("run", run);
        document.put("artifacts", state.artifacts());
        try {
            atomicWrite(runStatePath(state.run().runId()), objectMapper.writeValueAsBytes(document));
        } catch (IOException failure) {
            throw new IllegalStateException("Scenario Lab provider run state could not be persisted", failure);
        }
    }

    private void atomicWrite(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".pending");
        try {
            try (FileChannel channel = FileChannel.open(
                    pending, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(content));
                channel.force(true);
            }
            try {
                Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException ignored) {
                // Some file systems do not expose directory fsync. The file itself is already durable.
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private <T> T withRunLock(String runId, RunStateWork<T> work) {
        Path lockPath = runRoot(runId).resolve(RUN_STATE_LOCK_FILE).toAbsolutePath().normalize();
        Object processLock = PROCESS_RUN_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
        synchronized (processLock) {
            try {
                Files.createDirectories(lockPath.getParent());
                try (FileChannel channel = FileChannel.open(
                                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    return work.execute();
                }
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("Scenario Lab provider run state lock failed", failure);
            }
        }
    }

    private static Map<String, Object> requiredMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Scenario Lab provider run state is invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalStateException("Scenario Lab provider run state is invalid");
            }
            result.put(stringKey, entryValue);
        });
        return result;
    }

    private static String requiredString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Scenario Lab provider run state is invalid");
        }
        return text;
    }

    private static String nullableString(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalStateException("Scenario Lab provider run state is invalid");
        }
        return text;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger)) {
            throw new IllegalStateException("Scenario Lab provider run state is invalid");
        }
        long resolved = ((Number) value).longValue();
        if (resolved < 1L || resolved > 9_007_199_254_740_991L) {
            throw new IllegalStateException("Scenario Lab provider run state is invalid");
        }
        return resolved;
    }

    private static long exactEpochMs(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        Long resolved = nullableLong(value);
        return resolved == null ? fallback : resolved;
    }

    private static boolean terminalStatus(String status) {
        return java.util.Set.of("COMPLETED", "FAILED", "CANCELLED")
                .contains(stringValue(status).toUpperCase(java.util.Locale.ROOT));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    private interface RunStateWork<T> {
        T execute() throws Exception;
    }

    private record DurableRunState(StoredRunData run, Map<String, String> artifacts) {
        private DurableRunState {
            artifacts = Map.copyOf(artifacts);
        }
    }

    @Override
    public Map<String, Object> evaluateSuite(Map<String, Object> suite, String baselineEvaluationRunId) {
        Map<String, Object> summary = objectMap(suite.get("summary"));
        int scenarioCount = intValue(summary.get("scenarioCount"), 1);
        int failed = intValue(summary.get("failed"), 0);
        int passed = intValue(summary.get("passed"), Math.max(0, scenarioCount - failed));
        double score = scenarioCount <= 0 ? 1.0d : Math.max(0.0d, Math.min(1.0d, passed / (double) scenarioCount));
        String runId = firstNonBlank(
                stringValue(suite.get("runId")),
                "remote-eval-" + System.currentTimeMillis());
        return linkedMap(
                "schemaVersion", "evaluation.v1",
                "run", linkedMap(
                        "runId", runId,
                        "manifestPath", firstNonBlank(stringValue(suite.get("manifestPath")), "banking-remote-policy-wind-tunnel"),
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
            if (Files.isDirectory(current.resolve("java-spring-banking"))
                    && Files.isDirectory(current.resolve("scenarios"))) {
                return current;
            }
            current = current.getParent();
        }
        return cwd;
    }

    static BankingRemotePolicyWindTunnelExecutionContext fromConfiguration(
            ObjectMapper objectMapper,
            String workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl,
            String bankingProductEndpointUrl) {
        return new BankingRemotePolicyWindTunnelExecutionContext(
                objectMapper,
                resolveWorkspaceRoot(workspaceRoot),
                firstNonBlank(agentRuntimeUrl, "http://agent-runtime:8082"),
                firstNonBlank(simulatorUrl, "http://sim:8083"),
                firstNonBlank(bankingProductEndpointUrl, "http://localhost:8094"));
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
        String normalized = stringValue(value);
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public enum LaunchAdmissionOutcome {
        CREATED,
        REPLAY,
        CONFLICT
    }

    public enum LaunchExecutionDirective {
        START_NEW,
        RECOVER_EXISTING,
        NONE
    }

    public record ExecutionClaim(String runId, String launchDigest, String owner) {
    }

    public record LaunchAdmission(
            LaunchAdmissionOutcome outcome,
            PolicyWindTunnelStoredRun run,
            ExecutionClaim executionClaim,
            LaunchExecutionDirective executionDirective) {

        public boolean startsFreshExecution() {
            return executionDirective == LaunchExecutionDirective.START_NEW;
        }

        public boolean recoversExistingExecution() {
            return executionDirective == LaunchExecutionDirective.RECOVER_EXISTING;
        }
    }

    public static final class StaleExecutionClaimException extends IllegalStateException {
        public StaleExecutionClaimException(String message) {
            super(message);
        }
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
                    metadata == null ? Map.of() : metadata);
        }
    }
}
