package banking.simulator.remote.domain;

import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext.PolicyWindTunnelRunUpdate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Test-only factory for the durable context required by PLATFORM_OWNED_V2 launches. */
public final class BankingRemotePolicyWindTunnelTestContexts {
    private static final Queue<BankingRemotePolicyWindTunnelExecutionContext> CONTEXTS =
            new ConcurrentLinkedQueue<>();

    private BankingRemotePolicyWindTunnelTestContexts() {
    }

    public static BankingRemotePolicyWindTunnelExecutionContext durable(
            ObjectMapper objectMapper,
            Path workspaceRoot) {
        BankingRemotePolicyWindTunnelExecutionContext context =
                new BankingRemotePolicyWindTunnelExecutionContext(
                        objectMapper,
                        workspaceRoot,
                        "http://agent-runtime.test",
                        "http://simulator.test",
                        "http://banking.test");
        if (!context.configurePlatformOwnedV2Storage(true)) {
            throw new IllegalStateException("test durable storage probe failed");
        }
        CONTEXTS.add(context);
        return context;
    }

    public static void stopBackgroundRuns() throws InterruptedException {
        BankingRemotePolicyWindTunnelExecutionContext context;
        boolean stoppedContext = false;
        while ((context = CONTEXTS.poll()) != null) {
            stoppedContext = true;
            BankingRemotePolicyWindTunnelExecutionContext currentContext = context;
            Path runsRoot = currentContext.workspaceRoot()
                    .resolve("output/experiments/banking-scenario-lab/runs");
            if (Files.notExists(runsRoot)) {
                continue;
            }
            try (var runDirectories = Files.list(runsRoot)) {
                for (Path runDirectory : runDirectories.filter(Files::isDirectory).toList()) {
                    String runId = runDirectory.getFileName().toString();
                    currentContext.findRun(runId).ifPresent(run -> {
                        if (!java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(run.status())) {
                            currentContext.updateRun(runId, new PolicyWindTunnelRunUpdate(
                                    "CANCELLED",
                                    null,
                                    null,
                                    System.currentTimeMillis(),
                                    Map.copyOf(run.metadata())));
                        }
                    });
                }
            } catch (Exception ignored) {
                // Test cleanup must not hide the test's primary assertion failure.
            }
        }
        if (stoppedContext) {
            Thread.sleep(2_100L);
        }
    }
}
