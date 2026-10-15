package banking.simulator.remote.domain;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Subprocess fixture for proving the existing durable admission owner across JVMs, without runtime execution. */
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
        var request = admissionRequest(runId, System.currentTimeMillis(),
                Map.of("candidateRate", candidateRate));
        String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(Map.of("candidateRate", candidateRate))));
        var admission = context.admitLaunch(request, digest, ready.getFileName().toString(),
                System.currentTimeMillis());
        if (admission.outcome() == BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.CONFLICT) {
            System.out.println("CONFLICT");
            return;
        }
        if (admission.startsFreshExecution()) {
            context.executeWithClaim(admission.executionClaim(), () -> appendActivation(activationLog));
        }
        System.out.println("ADMITTED");
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
            runQueuedAdmission(context, ready, gate, resultLog, runId, mode);
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

    private static void runQueuedAdmission(
            BankingRemotePolicyWindTunnelExecutionContext context,
            Path ready,
            Path gate,
            Path activationLog,
            String runId,
            String mode) throws Exception {
        long now = "queued-admitter".equals(mode)
                ? System.currentTimeMillis() - BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS - 1L
                : System.currentTimeMillis();
        if ("queued-takeover".equals(mode)) {
            Files.writeString(ready, "ready");
            awaitGate(gate);
            now = System.currentTimeMillis();
        }
        var admission = context.admitLaunch(admissionRequest(runId, now, Map.of()),
                "sha256:" + "d".repeat(64), ready.getFileName().toString(), now);
        if ("queued-admitter".equals(mode)) {
            if (!admission.startsFreshExecution()) {
                throw new IllegalStateException("queued worker admission was not created");
            }
            haltAfterAdmission(ready);
        }
        if (!admission.recoversExistingExecution()) {
            System.out.println("REPLAY_NO_EXECUTION");
            return;
        }
        context.executeWithClaim(admission.executionClaim(), () -> {
            appendActivation(activationLog);
            context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                    "COMPLETED", null, null, System.currentTimeMillis(),
                    context.findRun(runId).orElseThrow().metadata()));
        });
        System.out.println("QUEUED_RECOVERED");
    }

    private static PolicyWindTunnelRunCreateRequest admissionRequest(
            String runId, long now, Map<String, Object> parameters) {
        return new PolicyWindTunnelRunCreateRequest(
                runId, "banking-scenario-lab", "banking-policy-compare", "CANDIDATE",
                "SEEDED_DETERMINISTIC", "saver-repricing", "QUEUED", now,
                null, null, Map.of("phase", "QUEUED", "parameters", parameters));
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
