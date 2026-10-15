package banking.simulator.remote.domain;

import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelExecutionProvider;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunCreateRequest;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BankingRemotePolicyWindTunnelExecutionContextTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void sharedStorageReadinessRequiresChildProcessLockAndPublication() {
        BankingRemotePolicyWindTunnelExecutionContext context =
                new BankingRemotePolicyWindTunnelExecutionContext(
                        new ObjectMapper().rebuild().findAndAddModules().build(),
                        workspaceRoot,
                        "http://agent-runtime.test",
                        "http://simulator.test",
                        "http://banking.test");

        assertTrue(context.configurePlatformOwnedV2Storage(true));
        assertTrue(context.platformOwnedV2StorageReady());
        assertEquals(
                workspaceRoot.resolve("output/experiments/banking-scenario-lab/runs"),
                context.durableRunStateRoot());
    }

    @Test
    void independentProviderInstancesRecoverRunAndArtifactStateFromSharedStorage() {
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext first = context(objectMapper);
        BankingRemotePolicyWindTunnelExecutionContext second = context(objectMapper);

        first.createRun(new PolicyWindTunnelRunCreateRequest(
                "shared-run",
                "banking-scenario-lab",
                "banking-policy-compare",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "saver-repricing",
                "RUNNING",
                1_720_000_000_000L,
                null,
                null,
                Map.of("phase", "QUEUED")));

        assertEquals("RUNNING", second.findRun("shared-run").orElseThrow().status());
        assertEquals("QUEUED", second.findRun("shared-run").orElseThrow().metadata().get("phase"));

        second.updateRun("shared-run", new PolicyWindTunnelRunUpdate(
                "COMPLETED",
                "output/summary.json",
                "/api/runs/shared-run/bundle",
                1_720_000_100_000L,
                Map.of("phase", "COMPLETED")));
        second.registerArtifact("shared-run", "summary", "output/summary.json", "application/json");

        BankingRemotePolicyWindTunnelExecutionContext replacement = context(objectMapper);
        assertEquals("COMPLETED", replacement.findRun("shared-run").orElseThrow().status());
        assertEquals("COMPLETED", replacement.findRun("shared-run").orElseThrow().metadata().get("phase"));
        assertEquals("output/summary.json", replacement.registeredArtifacts("shared-run").get("summary"));
    }

    @Test
    void sharedStorageAdmitsOneExecutionAndRejectsContradictoryLaunches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext firstContext = context(objectMapper);
        BankingRemotePolicyWindTunnelExecutionContext secondContext = context(objectMapper);
        long now = System.currentTimeMillis();
        PolicyWindTunnelRunCreateRequest request = new PolicyWindTunnelRunCreateRequest(
                "replica-safe-launch", "banking-scenario-lab", "banking-policy-compare",
                "CANDIDATE", "SEEDED_DETERMINISTIC", "saver-repricing", "QUEUED", now,
                null, null, Map.of("phase", "QUEUED",
                        "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"));
        String digest = "sha256:" + "a".repeat(64);
        var accepted = firstContext.admitLaunch(request, digest, "first-owner", now);
        var replay = secondContext.admitLaunch(request, digest, "second-owner", now + 1L);

        assertEquals("PLATFORM_OWNED_V2",
                accepted.run().metadata().get("opportunityGenerationFinalizationMode"));
        assertTrue(accepted.startsFreshExecution());
        assertTrue(!replay.startsFreshExecution());
        assertEquals(BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.REPLAY,
                replay.outcome());
        var conflict = secondContext.admitLaunch(
                request, "sha256:" + "b".repeat(64), "conflicting-owner", now + 2L);
        assertEquals(BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.CONFLICT,
                conflict.outcome());
        assertEquals(null, conflict.executionClaim());
        assertEquals("first-owner", firstContext.findRun("replica-safe-launch").orElseThrow()
                .metadata().get(BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_OWNER_METADATA_KEY));

        firstContext.executeWithClaim(accepted.executionClaim(), () -> firstContext.updateRun(
                "replica-safe-launch", new PolicyWindTunnelRunUpdate(
                        "COMPLETED", null, null, System.currentTimeMillis(),
                        firstContext.findRun("replica-safe-launch").orElseThrow().metadata())));
        var terminalReplay = secondContext.admitLaunch(request, digest, "terminal-owner", now + 3L);
        assertEquals(BankingRemotePolicyWindTunnelExecutionContext.LaunchExecutionDirective.NONE,
                terminalReplay.executionDirective());
        assertEquals(null, terminalReplay.executionClaim());
        assertEquals("COMPLETED", secondContext.findRun("replica-safe-launch").orElseThrow().status());
    }

    @Test
    void platformOwnedV2RejectsNonDurableExecutionContext() {
        BankingPolicyWindTunnelExecutionProvider provider = new BankingPolicyWindTunnelExecutionProvider(
                new ObjectMapper().rebuild().findAndAddModules().build(),
                stubbedRuntimeRestTemplate(),
                Runnable::run);
        PolicyWindTunnelExecutionContext nonDurable = mock(PolicyWindTunnelExecutionContext.class);

        RemoteDomainProtocolException rejected = assertThrows(
                RemoteDomainProtocolException.class,
                () -> provider.startRun(nonDurable, Map.ofEntries(
                        Map.entry("presetId", "saver-repricing"),
                        Map.entry("simulationRunId", "non-durable-v2"),
                        Map.entry("executionMode", "CLOCK_DRIVEN_HYBRID"),
                        Map.entry("runPath", "CLOCK_DRIVEN_SIMULATION"),
                        Map.entry("agentDecisionMode", "STUB_LLM"),
                        Map.entry("agentDecisionCadence", "EVENT_ONLY"),
                        Map.entry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        Map.entry("simulationHorizonDays", 1),
                        Map.entry("prePolicyDays", 0),
                        Map.entry("postPolicyDays", 1)),
                        "PLATFORM_ADMIN"));

        assertEquals(400, rejected.status().value());
        assertEquals("scenario_lab_native_execution_unavailable", rejected.code());
        org.mockito.Mockito.verifyNoInteractions(nonDurable);
        BankingRemotePolicyWindTunnelExecutionContext unavailableStorage =
                new BankingRemotePolicyWindTunnelExecutionContext(
                        new ObjectMapper().rebuild().findAndAddModules().build(), workspaceRoot,
                        "http://agent-runtime.test", "http://simulator.test", "http://banking.test");
        assertTrue(!unavailableStorage.configurePlatformOwnedV2Storage(false));
        assertTrue(!unavailableStorage.platformOwnedV2StorageReady());
    }

    @Test
    void durableAdmissionClassifiesReplayConflictAndFencesExpiredTakeover() {
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext context = context(objectMapper);
        long now = System.currentTimeMillis();
        String runId = "durable-admission-fence";
        String digest = "sha256:" + "a".repeat(64);
        PolicyWindTunnelRunCreateRequest request = new PolicyWindTunnelRunCreateRequest(
                runId,
                "banking-scenario-lab",
                "banking-policy-compare",
                "CANDIDATE",
                "SEEDED_DETERMINISTIC",
                "saver-repricing",
                "QUEUED",
                now,
                null,
                null,
                Map.of("phase", "QUEUED"));

        var created = context.admitLaunch(request, digest, "owner-created", now);
        var replay = context.admitLaunch(request, digest, "owner-replay", now + 1L);
        var conflict = context.admitLaunch(
                request,
                "sha256:" + "b".repeat(64),
                "owner-conflict",
                now + 2L);

        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.CREATED,
                created.outcome());
        assertTrue(created.startsFreshExecution());
        assertTrue(!created.recoversExistingExecution());
        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.REPLAY,
                replay.outcome());
        assertTrue(!replay.startsFreshExecution());
        assertTrue(!replay.recoversExistingExecution());
        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.CONFLICT,
                conflict.outcome());

        AtomicReference<BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmission> takeoverRef =
                new AtomicReference<>();
        context.executeWithClaim(created.executionClaim(), () -> {
            context.updateRun(
                    runId,
                    new PolicyWindTunnelRunUpdate(
                            "RUNNING",
                            null,
                            null,
                            null,
                            Map.of("phase", "PREPARING_CLOCK")));
            long takeoverTime = now
                    + (2L * BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS)
                    + 1L;
            takeoverRef.set(context.admitLaunch(request, digest, "owner-takeover", takeoverTime));
            assertThrows(
                    BankingRemotePolicyWindTunnelExecutionContext.StaleExecutionClaimException.class,
                    () -> context.updateRun(
                            runId,
                            new PolicyWindTunnelRunUpdate(
                                    "RUNNING", null, null, null, Map.of("phase", "STALE"))));
            assertThrows(
                    BankingRemotePolicyWindTunnelExecutionContext.StaleExecutionClaimException.class,
                    () -> context.registerArtifact(
                            runId, "stale", "output/stale.json", "application/json"));
            assertThrows(
                    BankingRemotePolicyWindTunnelExecutionContext.StaleExecutionClaimException.class,
                    () -> context.linkEvaluation(runId, "stale-evaluation"));
        });
        var takeover = takeoverRef.get();
        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.REPLAY,
                takeover.outcome());
        assertTrue(!takeover.startsFreshExecution());
        assertTrue(takeover.recoversExistingExecution());
        assertTrue(context.registeredArtifacts(runId).isEmpty());

        context.executeWithClaim(takeover.executionClaim(), () -> context.updateRun(
                runId,
                new PolicyWindTunnelRunUpdate(
                        "COMPLETED", null, null, System.currentTimeMillis(), Map.of("phase", "COMPLETED"))));
        assertEquals("COMPLETED", context.findRun(runId).orElseThrow().status());
        assertThrows(
                IllegalStateException.class,
                () -> context.updateRun(
                        runId,
                        new PolicyWindTunnelRunUpdate(
                            "RUNNING", null, null, null, Map.of("phase", "RESURRECTED"))));
        assertEquals("COMPLETED", context.findRun(runId).orElseThrow().status());

        var terminalReplay = context.admitLaunch(
                request,
                digest,
                "owner-terminal-replay",
                now + (2L * BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS));
        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.REPLAY,
                terminalReplay.outcome());
        assertEquals(
                BankingRemotePolicyWindTunnelExecutionContext.LaunchExecutionDirective.NONE,
                terminalReplay.executionDirective());
        assertEquals(null, terminalReplay.executionClaim());

    }

    @Test
    void expiredNonRecoverablePhasesDoNotAcquireExecutionOwnership() {
        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext context = context(objectMapper);
        long now = System.currentTimeMillis();
        String digest = "sha256:" + "c".repeat(64);
        int caseIndex = 0;

        for (Map.Entry<String, String> state : Map.of(
                        "PAUSED", "PAUSED",
                        "READY_TO_FINALIZE", "READY_TO_FINALIZE",
                        "FINALIZING_REPORT", "RUNNING")
                .entrySet()) {
            String runId = "expired-non-recoverable-" + caseIndex++;
            PolicyWindTunnelRunCreateRequest request = new PolicyWindTunnelRunCreateRequest(
                    runId,
                    "banking-scenario-lab",
                    "banking-policy-compare",
                    "CANDIDATE",
                    "SEEDED_DETERMINISTIC",
                    "saver-repricing",
                    "QUEUED",
                    now,
                    null,
                    null,
                    Map.of("phase", "QUEUED"));
            var created = context.admitLaunch(request, digest, "original-owner", now);
            context.executeWithClaim(created.executionClaim(), () -> context.updateRun(
                    runId,
                    new PolicyWindTunnelRunUpdate(
                            state.getValue(), null, null, null, Map.of("phase", state.getKey()))));

            var replay = context.admitLaunch(
                    request,
                    digest,
                    "takeover-owner",
                    now + (2L * BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_LEASE_MS));

            assertEquals(
                    BankingRemotePolicyWindTunnelExecutionContext.LaunchAdmissionOutcome.REPLAY,
                    replay.outcome());
            assertEquals(
                    BankingRemotePolicyWindTunnelExecutionContext.LaunchExecutionDirective.NONE,
                    replay.executionDirective());
            assertEquals(null, replay.executionClaim());
            assertEquals(
                    "original-owner",
                    context.findRun(runId).orElseThrow().metadata().get(
                            BankingRemotePolicyWindTunnelExecutionContext.EXECUTION_CLAIM_OWNER_METADATA_KEY));
        }
    }

    @Test
    void independentJvmAdmissionsProduceOneExecutionClaimAndPreserveTerminalState() throws Exception {
        Path gate = workspaceRoot.resolve("launch.gate");
        Path activationLog = workspaceRoot.resolve("activation.log");
        String runId = "cross-process-launch";
        Process first = launchProcess("first.ready", gate, activationLog, runId, "0.08");
        Process second = launchProcess("second.ready", gate, activationLog, runId, "0.08");
        awaitFile(workspaceRoot.resolve("first.ready"));
        awaitFile(workspaceRoot.resolve("second.ready"));
        Files.writeString(gate, "go");

        assertProcessSucceeded(first);
        assertProcessSucceeded(second);
        assertEquals(1, Files.readAllLines(activationLog).size());

        Process conflicting = launchProcess("conflict.ready", gate, activationLog, runId, "0.09");
        assertTrue(assertProcessSucceeded(conflicting).contains("CONFLICT"));
        assertEquals(1, Files.readAllLines(activationLog).size());

        ObjectMapper objectMapper = new ObjectMapper().rebuild().findAndAddModules().build();
        BankingRemotePolicyWindTunnelExecutionContext context = context(objectMapper);
        context.updateRun(runId, new PolicyWindTunnelRunUpdate(
                "COMPLETED", null, null, System.currentTimeMillis(),
                context.findRun(runId).orElseThrow().metadata()));
        Process terminalReplay = launchProcess("terminal.ready", gate, activationLog, runId, "0.08");
        assertProcessSucceeded(terminalReplay);
        assertEquals(1, Files.readAllLines(activationLog).size());
        assertEquals("COMPLETED", context(objectMapper).findRun(runId).orElseThrow().status());
    }

    @Test
    void independentJvmsFenceExpiredTakeoverAndLateStaleWriter() throws Exception {
        String runId = "cross-process-expired-takeover";
        Path resultLog = workspaceRoot.resolve("claim-results.log");
        Path staleReady = workspaceRoot.resolve("stale.ready");
        Path staleGate = workspaceRoot.resolve("stale.gate");
        Path takeoverReady = workspaceRoot.resolve("takeover.ready");
        Path takeoverGate = workspaceRoot.resolve("takeover.gate");

        Process stale = claimProcess(
                staleReady, staleGate, resultLog, runId, "stale-worker");
        awaitFile(staleReady);
        Process takeover = claimProcess(
                takeoverReady, takeoverGate, resultLog, runId, "takeover-worker");
        awaitFile(takeoverReady);

        Files.writeString(staleGate, "release");
        assertTrue(assertProcessSucceeded(stale).contains("STALE_FENCED"));
        Files.writeString(takeoverGate, "release");
        assertTrue(assertProcessSucceeded(takeover).contains("TAKEOVER_COMPLETED"));

        BankingRemotePolicyWindTunnelExecutionContext recovered =
                context(new ObjectMapper().rebuild().findAndAddModules().build());
        var durableRun = recovered.findRun(runId).orElseThrow();
        assertEquals("COMPLETED", durableRun.status());
        assertEquals("takeover-evaluation", durableRun.evaluationRunId());
        assertEquals("output/summary.json", recovered.registeredArtifacts(runId).get("summary"));
        assertTrue(!recovered.registeredArtifacts(runId).containsKey("stale"));
        assertEquals(
                List.of("stale-fenced", "takeover-completed"),
                Files.readAllLines(resultLog));
    }

    @Test
    void independentJvmRecoversQueuedAdmissionExactlyOnceAfterAdmitterExits() throws Exception {
        String runId = "cross-process-queued-recovery";
        Path executionLog = workspaceRoot.resolve("queued-recovery.log");
        Path unusedGate = workspaceRoot.resolve("unused-queued-admitter.gate");
        Process admitter = claimProcess(
                workspaceRoot.resolve("queued-admitter.ready"),
                unusedGate,
                executionLog,
                runId,
                "queued-admitter");

        assertProcessSucceeded(admitter);
        assertTrue(Files.notExists(executionLog));

        Path takeoverGate = workspaceRoot.resolve("queued-takeover.gate");
        Process firstTakeover = claimProcess(
                workspaceRoot.resolve("queued-takeover-first.ready"),
                takeoverGate,
                executionLog,
                runId,
                "queued-takeover");
        Process secondTakeover = claimProcess(
                workspaceRoot.resolve("queued-takeover-second.ready"),
                takeoverGate,
                executionLog,
                runId,
                "queued-takeover");
        awaitFile(workspaceRoot.resolve("queued-takeover-first.ready"));
        awaitFile(workspaceRoot.resolve("queued-takeover-second.ready"));
        Files.writeString(takeoverGate, "recover");

        String firstResult = assertProcessSucceeded(firstTakeover);
        String secondResult = assertProcessSucceeded(secondTakeover);
        assertTrue(firstResult.contains("QUEUED_RECOVERED")
                || secondResult.contains("QUEUED_RECOVERED"));
        assertTrue(firstResult.contains("REPLAY_NO_EXECUTION")
                || secondResult.contains("REPLAY_NO_EXECUTION"));
        assertEquals(List.of("activation"), Files.readAllLines(executionLog));

        Process terminalReplay = claimProcess(
                workspaceRoot.resolve("queued-terminal-replay.ready"),
                takeoverGate,
                executionLog,
                runId,
                "queued-takeover");
        assertTrue(assertProcessSucceeded(terminalReplay).contains("REPLAY_NO_EXECUTION"));
        assertEquals(List.of("activation"), Files.readAllLines(executionLog));
        assertEquals(
                "COMPLETED",
                context(new ObjectMapper().rebuild().findAndAddModules().build())
                        .findRun(runId)
                        .orElseThrow()
                        .status());
    }

    private Process launchProcess(
            String readyName,
            Path gate,
            Path activationLog,
            String runId,
            String candidateRate) throws Exception {
        return new ProcessBuilder(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        BankingRemotePolicyWindTunnelLaunchProcess.class.getName(),
                        workspaceRoot.toString(),
                        workspaceRoot.resolve(readyName).toString(),
                        gate.toString(),
                        activationLog.toString(),
                        runId,
                        candidateRate))
                .redirectErrorStream(true)
                .start();
    }

    private Process claimProcess(
            Path ready,
            Path gate,
            Path resultLog,
            String runId,
            String mode) throws Exception {
        return new ProcessBuilder(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        BankingRemotePolicyWindTunnelLaunchProcess.class.getName(),
                        workspaceRoot.toString(),
                        ready.toString(),
                        gate.toString(),
                        resultLog.toString(),
                        runId,
                        "0.08",
                        mode))
                .redirectErrorStream(true)
                .start();
    }

    private void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (Files.notExists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("subprocess did not reach launch barrier: " + path);
            }
            Thread.sleep(10L);
        }
    }

    private String assertProcessSucceeded(Process process) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "provider subprocess did not terminate");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private BankingRemotePolicyWindTunnelExecutionContext context(ObjectMapper objectMapper) {
        BankingRemotePolicyWindTunnelExecutionContext context = new BankingRemotePolicyWindTunnelExecutionContext(
                objectMapper,
                workspaceRoot,
                "http://agent-runtime.test",
                "http://simulator.test",
                "http://banking.test");
        assertTrue(context.configurePlatformOwnedV2Storage(true));
        return context;
    }

    private RestTemplate stubbedRuntimeRestTemplate() {
        return mock(RestTemplate.class);
    }

}
