package banking.simulator.remote.domain;

import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelExecutionProvider;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelTestProviders;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** Subprocess fixture for proving provider launch admission across JVMs. */
public final class BankingRemotePolicyWindTunnelLaunchProcess {
    private BankingRemotePolicyWindTunnelLaunchProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path workspaceRoot = Path.of(args[0]);
        Path ready = Path.of(args[1]);
        Path gate = Path.of(args[2]);
        Path activationLog = Path.of(args[3]);
        String runId = args[4];
        String mode = args.length > 6 ? args[6] : "launch";
        if ("stale-worker".equals(mode)
                || "takeover-worker".equals(mode)
                || "queued-admitter".equals(mode)
                || "queued-takeover".equals(mode)) {
            runClaimWorker(workspaceRoot, ready, gate, activationLog, runId, mode);
            return;
        }
        double candidateRate = Double.parseDouble(args[5]);

        Files.writeString(ready, "ready");
        awaitGate(gate);

        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        byte[] activationSettings = objectMapper.writeValueAsBytes(Map.of(
                "schemaVersion", "scenario_lab_pinned_cohort_activation.v1",
                "agentDecisionMode", "STUB_LLM",
                "agentDecisionCadence", "EVENT_ONLY"));
        String activationSettingsDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(activationSettings));
        Map<String, Object> request = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("presetId", "saver-repricing"),
                Map.entry("simulationRunId", runId),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                Map.entry("simulationHorizonDays", 1),
                Map.entry("simulationStartEpochMs", 1_800_000_000_000L),
                Map.entry("prePolicyDays", 0),
                Map.entry("postPolicyDays", 1),
                Map.entry("candidateRate", candidateRate),
                Map.entry("activationSettingsDigest", activationSettingsDigest),
                Map.entry("activationSettingsSnapshot", Map.of(
                        "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                        "contentType", "application/json",
                        "encoding", "base64url",
                        "sha256", activationSettingsDigest,
                        "content", Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(activationSettings)))));

        BankingRemotePolicyWindTunnelExecutionContext context =
                new BankingRemotePolicyWindTunnelExecutionContext(
                        objectMapper,
                        workspaceRoot,
                        "http://agent-runtime.invalid",
                        "http://simulator.invalid",
                        "http://banking.invalid");
        if (!context.configurePlatformOwnedV2Storage(true)) {
            throw new IllegalStateException("shared storage probe failed");
        }
        BankingPolicyWindTunnelExecutionProvider provider =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        new RestTemplate(),
                        ignored -> appendActivation(activationLog));
        try {
            provider.startRun(context, request, "PLATFORM_ADMIN");
            System.out.println("ADMITTED");
        } catch (RemoteDomainProtocolException conflict) {
            if (conflict.status().value() != 409) {
                throw conflict;
            }
            System.out.println("CONFLICT");
        }
    }

    private static void runClaimWorker(
            Path workspaceRoot,
            Path ready,
            Path gate,
            Path resultLog,
            String runId,
            String mode) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext context =
                new BankingRemotePolicyWindTunnelExecutionContext(
                        objectMapper,
                        workspaceRoot,
                        "http://agent-runtime.invalid",
                        "http://simulator.invalid",
                        "http://banking.invalid");
        if (!context.configurePlatformOwnedV2Storage(true)) {
            throw new IllegalStateException("shared storage probe failed");
        }
        if ("queued-admitter".equals(mode) || "queued-takeover".equals(mode)) {
            runQueuedProvider(context, objectMapper, ready, gate, resultLog, runId, mode);
            return;
        }
        long initialNow = 1_800_000_000_000L;
        String digest = "sha256:" + "a".repeat(64);
        PolicyWindTunnelRunCreateRequest request = new PolicyWindTunnelRunCreateRequest(
                runId,
                "banking-scenario-lab",
                "banking-policy-compare",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "saver-repricing",
                "QUEUED",
                initialNow,
                null,
                null,
                Map.of("phase", "QUEUED"));

        if ("stale-worker".equals(mode)) {
            var admission = context.admitLaunch(request, digest, "owner-stale", initialNow);
            if (!admission.startsFreshExecution()) {
                throw new IllegalStateException("initial worker was not admitted");
            }
            context.executeWithClaim(admission.executionClaim(), () -> {
                try {
                    context.updateRun(
                            runId,
                            new PolicyWindTunnelRunUpdate(
                                    "RUNNING",
                                    null,
                                    null,
                                    null,
                                    Map.of("phase", "PREPARING_CLOCK")));
                    Files.writeString(ready, "ready");
                    awaitGate(gate);
                    expectStale(() -> context.updateRun(
                            runId,
                            new PolicyWindTunnelRunUpdate(
                                    "RUNNING", null, null, null, Map.of("phase", "STALE"))));
                    expectStale(() -> context.registerArtifact(
                            runId, "stale", "output/stale.json", "application/json"));
                    expectStale(() -> context.linkEvaluation(runId, "stale-evaluation"));
                    appendActivation(resultLog, "stale-fenced\n");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            System.out.println("STALE_FENCED");
            return;
        }

        long takeoverNow = initialNow
                + BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS
                + 1L;
        var admission = context.admitLaunch(request, digest, "owner-takeover", takeoverNow);
        if (!admission.recoversExistingExecution()) {
            throw new IllegalStateException("expired worker claim was not taken over");
        }
        Files.writeString(ready, "ready");
        awaitGate(gate);
        context.executeWithClaim(admission.executionClaim(), () -> {
            context.updateRun(
                    runId,
                    new PolicyWindTunnelRunUpdate(
                            "COMPLETED",
                            null,
                            null,
                            System.currentTimeMillis(),
                            Map.of("phase", "COMPLETED")));
            context.registerArtifact(
                    runId, "summary", "output/summary.json", "application/json");
            context.linkEvaluation(runId, "takeover-evaluation");
        });
        appendActivation(resultLog, "takeover-completed\n");
        System.out.println("TAKEOVER_COMPLETED");
    }

    private static void runQueuedProvider(
            BankingRemotePolicyWindTunnelExecutionContext context,
            ObjectMapper objectMapper,
            Path ready,
            Path gate,
            Path activationLog,
            String runId,
            String mode) throws Exception {
        long wallClockNow = System.currentTimeMillis();
        long providerNow = "queued-admitter".equals(mode)
                ? Math.subtractExact(
                        wallClockNow,
                        BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS + 1L)
                : wallClockNow;
        Map<String, Object> request = providerLaunchRequest(runId);
        AtomicBoolean executionStarted = new AtomicBoolean();
        RestTemplate restTemplate = recordingRestTemplate(activationLog);

        if ("queued-takeover".equals(mode)) {
            Files.writeString(ready, "ready");
            awaitGate(gate);
        }
        BankingPolicyWindTunnelExecutionProvider provider =
                BankingPolicyWindTunnelTestProviders.withAdmissionBoundary(
                        objectMapper,
                        restTemplate,
                        work -> {
                            executionStarted.set(true);
                            work.run();
                        },
                        () -> providerNow,
                        "queued-admitter".equals(mode)
                                ? () -> haltAfterAdmission(ready)
                                : () -> {
                                });

        provider.startRun(context, request, "PLATFORM_ADMIN");
        if (!executionStarted.get()) {
            System.out.println("REPLAY_NO_EXECUTION");
            return;
        }
        var current = context.findRun(runId).orElseThrow();
        context.updateRun(
                runId,
                new PolicyWindTunnelRunUpdate(
                        "COMPLETED",
                        null,
                        null,
                        System.currentTimeMillis(),
                        current.metadata()));
        System.out.println("QUEUED_RECOVERED");
    }

    private static Map<String, Object> providerLaunchRequest(String runId) throws Exception {
        byte[] activationSettings = """
                {"agentDecisionCadence":"EVENT_ONLY","agentDecisionMode":"STUB_LLM","schemaVersion":"scenario_lab_pinned_cohort_activation.v1"}
                """.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String activationSettingsDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(activationSettings));
        String cohortId = runId + "-cohort";
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("presetId", "saver-repricing"),
                Map.entry("simulationRunId", runId),
                Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                Map.entry("agentDecisionMode", "STUB_LLM"),
                Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                Map.entry("simulationHorizonDays", 1),
                Map.entry("prePolicyDays", 0),
                Map.entry("postPolicyDays", 1),
                Map.entry("candidateRate", 0.08),
                Map.entry("cohortId", cohortId),
                Map.entry("cohortSelection", Map.of("cohortId", cohortId)),
                Map.entry("activationSettingsDigest", activationSettingsDigest),
                Map.entry("activationSettingsSnapshot", Map.of(
                        "schemaVersion", "scenario_lab_pinned_activation_settings_snapshot.v1",
                        "contentType", "application/json",
                        "encoding", "base64url",
                        "sha256", activationSettingsDigest,
                        "content", Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(activationSettings)))));
    }

    private static RestTemplate recordingRestTemplate(Path activationLog) {
        RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);
        org.mockito.Mockito.when(restTemplate.getForObject(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("{\"max\":7200}");
        org.mockito.Mockito.when(restTemplate.exchange(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(HttpMethod.class),
                        org.mockito.ArgumentMatchers.any(HttpEntity.class),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    HttpMethod method = invocation.getArgument(1);
                    if (url.endsWith("/api/products") && HttpMethod.GET.equals(method)) {
                        return ResponseEntity.notFound().build();
                    }
                    if (url.endsWith("/api/agents/init") && HttpMethod.POST.equals(method)) {
                        appendActivation(activationLog);
                    }
                    return ResponseEntity.ok("{}");
                });
        return restTemplate;
    }

    private static void haltAfterAdmission(Path ready) {
        try {
            Files.writeString(ready, "admitted");
            Runtime.getRuntime().halt(0);
        } catch (Exception failure) {
            Runtime.getRuntime().halt(2);
        }
    }

    private static void expectStale(Runnable operation) {
        try {
            operation.run();
        } catch (BankingRemotePolicyWindTunnelExecutionContext.StaleExecutionClaimException expected) {
            return;
        }
        throw new IllegalStateException("stale execution claim unexpectedly mutated durable state");
    }

    private static void awaitGate(Path gate) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (Files.notExists(gate)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("launch barrier timed out");
            }
            Thread.sleep(10L);
        }
    }

    private static void appendActivation(Path activationLog) {
        appendActivation(activationLog, "activation\n");
    }

    private static void appendActivation(Path activationLog, String value) {
        try {
            Files.createDirectories(activationLog.getParent());
            try (FileChannel channel = FileChannel.open(
                            activationLog,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND);
                    FileLock ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                channel.force(true);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("could not record provider activation", failure);
        }
    }
}
