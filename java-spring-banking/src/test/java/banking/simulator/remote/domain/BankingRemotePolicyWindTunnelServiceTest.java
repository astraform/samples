package banking.simulator.remote.domain;

import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import ai.astraform.remote.domain.sdk.spring.PolicyWindTunnelControllerAdapter;
import ai.astraform.remote.domain.sdk.spring.RemoteDomainExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class BankingRemotePolicyWindTunnelServiceTest {
    @TempDir
    java.nio.file.Path workspaceRoot;

    @Test
    void exposesBankingPolicyWindTunnelMetadataFromRemoteDomainService() {
        BankingRemotePolicyWindTunnelService service = service(true);
        Map<String, Object> body = service.pack();

        assertThat(body)
                .containsEntry("packId", "banking-policy-pack")
                .containsEntry("domainId", "banking")
                .containsEntry("label", "Banking Policy Pack")
                .containsKeys(
                        "capabilities",
                        "controlSchema",
                        "uiSchema",
                        "domainLabels",
                        "reportLabels",
                        "outcomeSchema",
                        "outcomeScorecard",
                        "outcomeScorecards",
                        "policyExpressions",
                        "summarySchemaVersion");
        assertThat(scorecards(body))
                .extracting(scorecard -> scorecard.get("scorecardId"))
                .contains("banking.customer-impact.v1", "banking.evidence-readiness.v1");
        assertThat(String.valueOf(((Map<?, ?>) body.get("domainLabels")).get("actorPlural")))
                .isEqualTo("customers");
        assertThat(String.valueOf(((Map<?, ?>) body.get("capabilities")).get("remoteScorecardMetadata")))
                .isEqualTo("true");
        assertThat(String.valueOf(((Map<?, ?>) body.get("capabilities")).get("remoteProviderReady")))
                .isEqualTo("true");
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) body.get("capabilities");
        assertThat(capabilities)
                .containsEntry("executionModes", List.of("CLOCK_DRIVEN_HYBRID"))
                .containsEntry("opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2")
                .containsEntry("platformOwnedV2StorageReady", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> realLlmPolicy =
                (Map<String, Object>) ((Map<?, ?>) body.get("capabilities")).get("realLlmCanaryPolicy");
        assertThat(realLlmPolicy)
                .containsEntry("schemaVersion", "real_llm_canary_policy.v1")
                .containsEntry("enabled", true)
                .containsEntry("provider", "openai")
                .containsEntry("model", "gemini-2.5-flash-lite")
                .containsEntry("maxCustomers", 10)
                .containsEntry("maxDays", 30)
                .containsEntry("maxCalls", 70)
                .containsEntry("maxInputTokens", 150_000)
                .containsEntry("maxOutputTokens", 25_000)
                .containsEntry("maxPlaybackRate", 600.0d);
        assertThat(realLlmPolicy.get("allowedCadences")).isEqualTo(List.of("EVENT_ONLY", "DAILY", "WEEKLY"));
    }

    @Test
    void exposesBankingPolicyWindTunnelPresetCatalogFromRemoteDomainService() {
        BankingRemotePolicyWindTunnelService service = service(true);
        Map<String, Object> body = service.presets();

        assertThat(body)
                .containsEntry("schemaVersion", "banking_scenario_lab_presets.v1")
                .containsKey("presets")
                .containsEntry("summarySchemaVersion", "policy_wind_tunnel_summary.v1");
    }

    @Test
    void doesNotAdvertiseV2WithoutVerifiedSharedStorage() {
        BankingRemotePolicyWindTunnelService service = service(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities =
                (Map<String, Object>) service.pack().get("capabilities");

        assertThat(capabilities)
                .containsEntry("opportunityGenerationFinalizationMode", "LEGACY_V1")
                .containsEntry("platformOwnedV2StorageReady", false);
        assertThatThrownBy(() -> service.startRun(
                        Map.of(
                                "executionMode", "CLOCK_DRIVEN_HYBRID",
                                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        "PLATFORM_ADMIN"))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("Native Scenario Lab execution is not available yet");
    }

    @Test
    void validatesRetiredAndUnknownModesBeforeStorageReadiness() {
        for (boolean sharedStorageReady : List.of(false, true)) {
            BankingRemotePolicyWindTunnelService service = service(sharedStorageReady);
            for (Map<String, Object> payload : List.<Map<String, Object>>of(
                    Map.of(),
                    Map.of(
                            "executionMode", "FAST_BATCH_COMPARE",
                            "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                    Map.of(
                            "executionMode", "UNKNOWN",
                            "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"))) {
                assertThatThrownBy(() -> service.startRun(payload, "PLATFORM_ADMIN"))
                        .isInstanceOf(RemoteDomainProtocolException.class)
                        .hasMessageContaining("executionMode=CLOCK_DRIVEN_HYBRID");
            }
        }

        assertThatThrownBy(() -> service(false).startRun(
                        Map.of(
                                "executionMode", "CLOCK_DRIVEN_HYBRID",
                                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"),
                        "PLATFORM_ADMIN"))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("Native Scenario Lab execution is not available yet");
        assertThatThrownBy(() -> service(true).startRun(
                        Map.of(
                                "executionMode", "CLOCK_DRIVEN_HYBRID",
                                "opportunityGenerationFinalizationMode", "UNKNOWN"),
                        "PLATFORM_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opportunityGenerationFinalizationMode");
    }

    @Test
    void storageTrustBoundaryIgnoresSpringAndJvmConfigurationChannels() {
        Map<String, String> hostileEnvironment = new LinkedHashMap<>();
        hostileEnvironment.put(
                "SPRING_APPLICATION_JSON",
                "{\"astraform\":{\"remote-domain\":{\"shared-storage\":{\"enabled\":true}},"
                        + "\"workspace\":{\"root\":\"/tmp/spring-json-override\"}}}");
        hostileEnvironment.put(
                "JAVA_TOOL_OPTIONS",
                "-Dastraform.remote-domain.shared-storage.enabled=true "
                        + "-Dastraform.workspace.root=/tmp/java-tool-options-override");
        hostileEnvironment.put(
                "JDK_JAVA_OPTIONS",
                "-Dastraform.remote-domain.shared-storage.enabled=true "
                        + "-Dastraform.workspace.root=/tmp/jdk-java-options-override");
        hostileEnvironment.put(
                "_JAVA_OPTIONS",
                "-Dastraform.remote-domain.shared-storage.enabled=true "
                        + "-Dastraform.workspace.root=/tmp/java-options-override");
        hostileEnvironment.put("SPRING_CONFIG_LOCATION", "file:/tmp/hostile-application.yaml");
        hostileEnvironment.put("SPRING_CONFIG_IMPORT", "file:/tmp/hostile-import.yaml");

        assertThat(BankingRemotePolicyWindTunnelService.exactSharedStorageEnabled(hostileEnvironment))
                .isFalse();
        assertThat(BankingRemotePolicyWindTunnelService.exactWorkspaceRoot(hostileEnvironment))
                .isEmpty();

        hostileEnvironment.put("ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED", "false");
        hostileEnvironment.put("ASTRAFORM_WORKSPACE_ROOT", "/var/lib/astraform/chart-owned");

        assertThat(BankingRemotePolicyWindTunnelService.exactSharedStorageEnabled(hostileEnvironment))
                .isFalse();
        assertThat(BankingRemotePolicyWindTunnelService.exactWorkspaceRoot(hostileEnvironment))
                .isEqualTo("/var/lib/astraform/chart-owned");
    }

    @Test
    void storageTrustBoundaryRequiresExactTrueToken() {
        assertThat(BankingRemotePolicyWindTunnelService.exactSharedStorageEnabled(
                        Map.of("ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED", "true")))
                .isTrue();
        assertThat(BankingRemotePolicyWindTunnelService.exactSharedStorageEnabled(
                        Map.of("ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED", "TRUE")))
                .isFalse();
        assertThat(BankingRemotePolicyWindTunnelService.exactSharedStorageEnabled(
                        Map.of("ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED", " true ")))
                .isFalse();
    }

    @Test
    void readsCorrelationCapabilityOnlyFromTheCurrentPrivateRequestChannel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                "X-Astraform-Opportunity-Correlation-Capability",
                "private-five-minute-capability");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(BankingRemotePolicyWindTunnelService.privateOpportunityCorrelationCapability())
                    .isEqualTo("private-five-minute-capability");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(BankingRemotePolicyWindTunnelService.privateOpportunityCorrelationCapability())
                .isEmpty();
    }

    @Test
    void ordinaryAndV2LaunchesExposeNativeUnavailableThroughThePublishedSdkWithoutEffects() throws Exception {
        AtomicInteger outboundRequests = new AtomicInteger();
        HttpServer outbound = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        outbound.createContext("/", exchange -> {
            outboundRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        outbound.start();
        try {
            String outboundUrl = "http://127.0.0.1:" + outbound.getAddress().getPort();
            ObjectMapper mapper = new ObjectMapper().rebuild().findAndAddModules().build();
            for (boolean sharedStorageReady : List.of(false, true)) {
                BankingRemotePolicyWindTunnelService service = new BankingRemotePolicyWindTunnelService(
                        mapper, workspaceRoot.resolve("http-" + sharedStorageReady).toString(),
                        outboundUrl, outboundUrl, outboundUrl, sharedStorageReady);
                var mvc = MockMvcBuilders.standaloneSetup(new PolicyWindTunnelControllerAdapter(service))
                        .setControllerAdvice(new RemoteDomainExceptionHandler())
                        .build();
                for (Map<String, Object> payload : List.<Map<String, Object>>of(
                        Map.of("executionMode", "CLOCK_DRIVEN_HYBRID", "presetId", "saver-repricing",
                                "simulationRunId", "ordinary-unavailable"),
                        Map.of("executionMode", "CLOCK_DRIVEN_HYBRID", "presetId", "saver-repricing",
                                "simulationRunId", "v2-unavailable",
                                "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"))) {
                    Map<String, String> before = workspaceSnapshot();
                    var response = mvc.perform(post("/policy-wind-tunnel/runs")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header("X-Astraform-Execution-Role", "PLATFORM_ADMIN")
                                    .content(mapper.writeValueAsString(payload)))
                            .andReturn().getResponse();
                    String body = response.getContentAsString();
                    assertThat(response.getStatus()).as("SDK launch response: %s", body).isEqualTo(400);
                    Map<?, ?> envelope = mapper.readValue(body, Map.class);
                    assertThat(envelope.get("operation")).isEqualTo("scenario-lab-launch");
                    assertThat(envelope.get("requestId")).isNotNull();
                    Map<?, ?> error = (Map<?, ?>) envelope.get("error");
                    assertThat(error.get("code")).isEqualTo("scenario_lab_native_execution_unavailable");
                    assertThat(error.get("category")).isEqualTo("invalid_request");
                    assertThat(error.get("retryable")).isEqualTo(false);
                    assertThat(error.get("message").toString()).contains("Native Scenario Lab execution is not available yet");
                    assertThat(error.get("details")).isEqualTo(Map.of("requiredCapability", "nativeScenarioLabExecution"));
                    assertThat(workspaceSnapshot()).isEqualTo(before);
                    assertThat(outboundRequests).hasValue(0);
                }
            }
        } finally {
            outbound.stop(0);
        }
    }

    @Test
    void ordinaryAndV2LaunchesRejectBeforeDurableRunAdmissionForEitherStorageMode() throws Exception {
        for (boolean sharedStorageReady : List.of(false, true)) {
            BankingRemotePolicyWindTunnelService service = service(sharedStorageReady);
            for (Map<String, Object> payload : List.<Map<String, Object>>of(
                    Map.of("executionMode", "CLOCK_DRIVEN_HYBRID", "simulationRunId", "ordinary-unavailable"),
                    Map.of("executionMode", "CLOCK_DRIVEN_HYBRID", "simulationRunId", "v2-unavailable",
                            "opportunityGenerationFinalizationMode", "PLATFORM_OWNED_V2"))) {
                Map<String, String> before = workspaceSnapshot();
                RemoteDomainProtocolException error = org.junit.jupiter.api.Assertions.assertThrows(
                        RemoteDomainProtocolException.class, () -> service.startRun(payload, "PLATFORM_ADMIN"));
                assertThat(error.status().value()).isEqualTo(400);
                assertThat(error.code()).isEqualTo("scenario_lab_native_execution_unavailable");
                assertThat(error.retryable()).isFalse();
                assertThat(error.details()).containsEntry("requiredCapability", "nativeScenarioLabExecution");
                assertThat(workspaceSnapshot()).isEqualTo(before);
            }
        }
    }

    private Map<String, String> workspaceSnapshot() throws java.io.IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (var paths = Files.walk(workspaceRoot)) {
            for (Path path : paths.sorted().toList()) {
                snapshot.put(workspaceRoot.relativize(path).toString(), Files.isDirectory(path)
                        ? "directory" : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return snapshot;
    }

    private BankingRemotePolicyWindTunnelService service(boolean sharedStorageEnabled) {
        return new BankingRemotePolicyWindTunnelService(
                new ObjectMapper().rebuild().findAndAddModules().build(),
                workspaceRoot.toString(),
                "http://agent-runtime:8082",
                "http://sim:8083",
                "http://localhost:8094",
                sharedStorageEnabled);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scorecards(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("outcomeScorecards");
    }
}
