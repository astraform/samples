package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceHarness;
import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceReport;
import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceScenario;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelBinaryPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelService;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "astraform.workspace.root=target/banking-remote-policy-wind-tunnel-conformance-test"
})
class BankingRemotePolicyWindTunnelConformanceTest {
    private static final byte[] PROVIDER_STATE = "{\"status\":\"CANCELLED\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PolicyWindTunnelService policyWindTunnelService;

    @BeforeEach
    void configureLegacyRemotePolicyWindTunnelProvider() {
        configureRemotePolicyWindTunnelProvider(false);
    }

    private void configureRemotePolicyWindTunnelProvider(boolean platformOwnedV2) {
        reset(policyWindTunnelService);
        BankingRemotePolicyWindTunnelService metadataService = new BankingRemotePolicyWindTunnelService(
                objectMapper,
                "target/banking-remote-policy-wind-tunnel-conformance-test",
                "http://agent-runtime.test",
                "http://sim.test",
                "http://banking-domain.test",
                platformOwnedV2);
        String runId = "banking-remote-conformance-run";
        Map<String, Object> pack = metadataService.pack();
        String providerRevision = String.valueOf(pack.get("providerRevision"));
        Map<String, String> statuses = new ConcurrentHashMap<>();
        Map<String, Map<String, Object>> receipts = new ConcurrentHashMap<>();

        when(policyWindTunnelService.pack()).thenReturn(pack);
        when(policyWindTunnelService.presets()).thenReturn(metadataService.presets());
        when(policyWindTunnelService.startRun(any(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> payload = invocation.getArgument(0);
            if (!"CLOCK_DRIVEN_HYBRID".equals(payload.get("executionMode"))) {
                return Map.of("runId", runId, "status", "COMPLETED", "bundleReady", true);
            }
            String requestedRunId = String.valueOf(payload.get("simulationRunId"));
            statuses.put(requestedRunId, "RUNNING");
            return Map.of("runId", requestedRunId, "status", "RUNNING", "bundleReady", false);
        });
        when(policyWindTunnelService.getRun(anyString())).thenAnswer(invocation -> {
            String requestedRunId = invocation.getArgument(0);
            if (statuses.containsKey(requestedRunId)) {
                return Map.of(
                        "runId", requestedRunId,
                        "status", statuses.get(requestedRunId),
                        "bundleReady", false);
            }
            return Map.of("runId", runId, "status", "COMPLETED", "bundleReady", true);
        });
        when(policyWindTunnelService.controlRun(anyString(), any())).thenAnswer(invocation -> {
            String requestedRunId = invocation.getArgument(0);
            Map<String, Object> payload = invocation.getArgument(1);
            String action = String.valueOf(payload.get("action"));
            if ("CANCEL".equals(action)) {
                statuses.put(requestedRunId, "CANCELLED");
                return Map.of(
                        "runId", requestedRunId,
                        "status", "CANCELLED",
                        "lifecycleAction", "CANCEL");
            }
            if (!"FINALIZE".equals(action)) {
                return Map.of("runId", requestedRunId, "status", statuses.get(requestedRunId));
            }
            Map<String, Object> existing = receipts.get(requestedRunId);
            if (existing != null) {
                if (!payload.keySet().equals(Set.of("action", "operationId"))) {
                    throw new IllegalArgumentException("finalization operation conflict");
                }
                return Map.of(
                        "runId", requestedRunId,
                        "status", "CANCELLED",
                        "lifecycleAction", "FINALIZE");
            }
            String operationId = String.valueOf(payload.get("operationId"));
            receipts.put(requestedRunId, finalizationReceipt(
                    requestedRunId,
                    providerRevision,
                    operationId));
            return Map.of(
                    "runId", requestedRunId,
                    "status", "CANCELLED",
                    "lifecycleAction", "FINALIZE");
        });
        when(policyWindTunnelService.bundle(eq(runId))).thenReturn(bundle(runId));
        when(policyWindTunnelService.artifact(anyString(), anyString())).thenAnswer(invocation -> {
            String requestedRunId = invocation.getArgument(0);
            String artifactType = invocation.getArgument(1);
            if ("finalization-receipt".equals(artifactType)) {
                return new PolicyWindTunnelBinaryPayload(
                        objectMapper.writeValueAsBytes(receipts.get(requestedRunId)),
                        "application/json",
                        "finalization-receipt.json");
            }
            if ("provider-state".equals(artifactType)) {
                return new PolicyWindTunnelBinaryPayload(
                        PROVIDER_STATE,
                        "application/json",
                        "provider-state.json");
            }
            return new PolicyWindTunnelBinaryPayload(
                    "{\"schemaVersion\":\"policy_wind_tunnel_summary.v1\"}"
                            .getBytes(StandardCharsets.UTF_8),
                    "application/json",
                    "scenario-lab-summary.json");
        });
        when(policyWindTunnelService.evidencePack(eq(runId))).thenReturn(new PolicyWindTunnelBinaryPayload(
                "zip".getBytes(StandardCharsets.UTF_8),
                "application/zip",
                "scenario-lab-evidence.zip"));
    }

    @Test
    void legacyRemoteServiceConformsToPolicyWindTunnelProviderContract() throws Exception {
        PolicyWindTunnelConformanceReport report = new PolicyWindTunnelConformanceHarness().assertConforms(
                mockMvc,
                objectMapper,
                new PolicyWindTunnelConformanceScenario(
                        "banking-policy-pack",
                        "saver-repricing"));

        Map<String, Object> reportMap = report.toMap();
        assertThat(reportMap)
                .containsEntry("packId", "banking-policy-pack")
                .containsEntry("bundleSchemaVersion", "policy_wind_tunnel_bundle.v1")
                .containsEntry("policyExpressionCount", 2);
        assertThat(reportMap)
                .containsEntry("scenarioLabFinalizationV2Checked", false)
                .containsEntry("scenarioLabFinalizationV2", null);
    }

    @Test
    void platformOwnedV2ConformanceSurvivesIndependentProcessRestart() throws Exception {
        configureRemotePolicyWindTunnelProvider(true);
        try (BankingPolicyWindTunnelV2ProbeDriver driver = new BankingPolicyWindTunnelV2ProbeDriver()) {
            PolicyWindTunnelConformanceReport report = new PolicyWindTunnelConformanceHarness().assertConforms(
                    mockMvc,
                    objectMapper,
                    new PolicyWindTunnelConformanceScenario(
                            "banking-policy-pack",
                            "saver-repricing"),
                    driver);

            Map<String, Object> reportMap = report.toMap();
            assertThat(reportMap).containsEntry("scenarioLabFinalizationV2Checked", true);
            assertThat(((Map<?, ?>) reportMap.get("scenarioLabFinalizationV2")).get("status"))
                    .isEqualTo("PASSED");
        }
    }

    @Test
    void platformOwnedV2PendingNotificationRecoversWithoutAnotherFinalize() throws Exception {
        try (BankingPolicyWindTunnelV2ProbeDriver driver = new BankingPolicyWindTunnelV2ProbeDriver()) {
            driver.verifyPendingNotificationRecoveryAfterRestart();
        }
    }

    @Test
    void platformOwnedV2NotificationRecorderPreservesConcurrentAttempts() throws Exception {
        try (BankingPolicyWindTunnelV2ProbeDriver driver = new BankingPolicyWindTunnelV2ProbeDriver()) {
            driver.verifyConcurrentNotificationRecordingIsAppendOnly();
        }
    }

    @Test
    void platformOwnedV2NotificationRecoveryIsReplicaExclusive() throws Exception {
        try (BankingPolicyWindTunnelV2ProbeDriver driver = new BankingPolicyWindTunnelV2ProbeDriver()) {
            driver.verifyNotificationRecoveryCallbackIsReplicaExclusive();
        }
    }

    @Test
    void clockDrivenRateChangeComponentHarnessSurvivesProviderRestart() throws Exception {
        try (BankingPolicyWindTunnelV2ProbeDriver driver = new BankingPolicyWindTunnelV2ProbeDriver()) {
            driver.verifyClockDrivenRateChangeComponentRestart();
        }
    }

    private static Map<String, Object> finalizationReceipt(
            String runId,
            String providerRevision,
            String operationId) {
        String canonicalFinalizeInput = "{\"action\":\"FINALIZE\",\"operationId\":\""
                + operationId
                + "\"}";
        Map<String, Object> runSnapshot = new LinkedHashMap<>();
        runSnapshot.put("status", "CANCELLED");
        runSnapshot.put("summaryPath", null);
        runSnapshot.put("bundlePath", null);
        runSnapshot.put("startedAtEpochMs", 1_784_500_000_000L);
        runSnapshot.put("completedAtEpochMs", 1_784_500_001_000L);
        runSnapshot.put("metadata", Map.of("phase", "CANCELLED"));

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", "scenario_lab_provider_finalization_receipt.v2");
        receipt.put("runId", runId);
        receipt.put("finalizationMode", "PLATFORM_OWNED_V2");
        receipt.put("providerRevision", providerRevision);
        receipt.put("operationId", operationId);
        receipt.put("inputDigest", sha256(canonicalFinalizeInput.getBytes(StandardCharsets.UTF_8)));
        receipt.put("state", "PUBLISHED");
        receipt.put("completionNotificationState", "NOT_REQUIRED");
        receipt.put("completionNotificationPayload", Map.of());
        receipt.put("runSnapshot", runSnapshot);
        receipt.put("artifactManifest", Map.of(
                "providerState", Map.of(
                        "artifactRef", "runs/" + runId + "/provider-state.json",
                        "contentType", "application/json",
                        "sha256", sha256(PROVIDER_STATE),
                        "byteLength", PROVIDER_STATE.length)));
        receipt.put("updatedAt", "2026-07-20T00:00:01Z");
        return receipt;
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static Map<String, Object> bundle(String runId) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", "policy_wind_tunnel_bundle.v1");
        bundle.put("summarySchemaVersion", "policy_wind_tunnel_summary.v1");
        bundle.put("domainId", "banking");
        bundle.put("packId", "banking-policy-pack");
        bundle.put("runId", runId);
        bundle.put("presetId", "saver-repricing");
        bundle.put("branches", Map.of(
                "baseline", Map.of("label", "Baseline"),
                "candidate", Map.of("label", "Candidate")));
        bundle.put("metrics", Map.of("simulatedSubjects", 1, "outcomeDelta", 0));
        bundle.put("segments", List.of(Map.of("segmentId", "demo", "count", 1)));
        bundle.put("timeline", List.of(Map.of("eventType", "run-completed")));
        bundle.put("decisionGates", List.of(Map.of("gateId", "review", "status", "PASS")));
        bundle.put("artifacts", Map.of("summary", "scenario-lab-summary.json"));
        bundle.put("domainPayload", Map.of("domain", "banking"));
        bundle.put("summary", Map.of(
                "schemaVersion", "policy_wind_tunnel_summary.v1",
                "domainId", "banking",
                "packId", "banking-policy-pack",
                "runId", runId,
                "presetId", "saver-repricing",
                "metrics", Map.of("simulatedSubjects", 1)));
        return bundle;
    }
}
