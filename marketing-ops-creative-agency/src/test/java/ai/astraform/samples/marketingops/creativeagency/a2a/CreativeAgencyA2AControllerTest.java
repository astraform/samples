package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.PROTOCOL_VERSION;
import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.sendMessagePayload;
import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.taskOperationPayload;

@SpringBootTest(properties = "marketing-ops-creative-agency.generation.mode=deterministic")
@AutoConfigureMockMvc
class CreativeAgencyA2AControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DefaultRequestHandler requestHandler;

    @Autowired
    @Qualifier(CreativeAgencyA2AConfiguration.AGENT_EXECUTOR)
    private ExecutorService agentExecutor;

    @Autowired
    private CreativeAgencyA2AAdmissionGate admissionGate;

    @Autowired
    private InMemoryQueueManager queueManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesAgentCard() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Creative Agency Agent"))
                .andExpect(jsonPath("$.skills[0].id").value("creative-rework"))
                .andExpect(jsonPath("$.supportedInterfaces[0].url")
                        .value("http://localhost:8091/a2a"))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolVersion")
                        .value(PROTOCOL_VERSION));
    }

    @Test
    void completesCreativeReworkRequestUsingScenarioMetadata() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "req-1",
                                "session-1",
                                "Need urgent asset revision before tomorrow's paid social launch.",
                                Map.of(
                                        "scenario", "creative-rework",
                                        "personaName", "Agency Liaison Rowan",
                                        "agentKey", "creative-agency"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.id").exists())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Decision: REVISE")))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Model narrative"))))
                .andExpect(jsonPath("$.result.task.status.message.metadata.scenario").value("creative-rework"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationProvider").value("deterministic"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationFallback").value(false))
                .andExpect(jsonPath("$.result.task.status.message.metadata.personaName").value("Agency Liaison Rowan"));
    }

    @Test
    void infersLaunchBlockerWithoutExplicitScenarioMetadata() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "req-2",
                                "session-2",
                                "We are blocked on legal approval and the launch deadline is slipping.",
                                Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Scenario: launch-blocker")))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Decision: ESCALATE")));
    }

    @Test
    void usesConfiguredProductionA2aTimeoutsInsteadOfTestFactoryDefaults() {
        org.assertj.core.api.Assertions.assertThat(requestHandler)
                .isInstanceOf(DefaultRequestHandler.class);
        org.assertj.core.api.Assertions.assertThat(
                        ReflectionTestUtils.getField(requestHandler, "agentCompletionTimeoutSeconds"))
                .isEqualTo(28);
        org.assertj.core.api.Assertions.assertThat(
                        ReflectionTestUtils.getField(requestHandler, "consumptionCompletionTimeoutSeconds"))
                .isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(
                        ReflectionTestUtils.getField(requestHandler, "reconciliationTimeoutSeconds"))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetTask", "ListTasks", "CancelTask"})
    void rejectsMissingVersionForEveryTaskOperation(String method) throws Exception {
        mockMvc.perform(post("/a2a")
                        .contentType("application/json")
                        .content(taskOperationPayload(method, "version-" + method, "task-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32009));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.3", "2.0"})
    void rejectsUnsupportedProtocolVersions(String version) throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, version)
                        .contentType("application/json")
                        .content(taskOperationPayload("ListTasks", "wrong-version", "task-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32009));
    }

    @Test
    void mapsEmptyBodyToJsonRpcParseError() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(new byte[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void rejectsOversizedBodyBeforeJsonParsing() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content("x".repeat(CreativeAgencyA2AController.MAX_A2A_REQUEST_BYTES + 1)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error").value("A2A_REQUEST_TOO_LARGE"));
    }

    @Test
    void boundsQueuedAgentWork() {
        assertThat(agentExecutor).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor bounded = (ThreadPoolExecutor) agentExecutor;
        assertThat(bounded.getMaximumPoolSize()).isEqualTo(8);
        assertThat(bounded.getQueue().remainingCapacity()).isEqualTo(8);
        assertThat(admissionGate.capacity()).isEqualTo(bounded.getMaximumPoolSize());
    }

    @Test
    void hidesUnknownTaskBehindTaskNotFound() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(taskOperationPayload("CancelTask", "cancel-rejected", "task-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cancel-rejected"))
                .andExpect(jsonPath("$.error.code").value(-32001));
    }

    @Test
    void rejectsCancellationForKnownTaskWithoutMutatingIt() throws Exception {
        String response = mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "cancel-source",
                                "cancel-context",
                                "Review this asset.",
                                Map.of("scenario", "asset-review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(response)
                .path("result")
                .path("task")
                .path("id")
                .stringValue();

        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(taskOperationPayload("CancelTask", "cancel-known", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cancel-known"))
                .andExpect(jsonPath("$.error.code").value(-32002));

        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(taskOperationPayload("GetTask", "get-after-cancel", taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status.state").value("TASK_STATE_COMPLETED"));
    }

    @Test
    void rejectsSaturatedRequestBeforeCreatingAnSdkTaskQueue() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> queues = (Map<String, Object>) ReflectionTestUtils.getField(queueManager, "queues");
        assertThat(queues).isNotNull();
        int queuesBefore = queues.size();
        List<CreativeAgencyA2AAdmissionGate.Lease> leases = new ArrayList<>();
        for (int index = 0; index < admissionGate.capacity(); index++) {
            CreativeAgencyA2AAdmissionGate.Lease lease = admissionGate.tryAcquire();
            assertThat(lease).isNotNull();
            leases.add(lease);
        }
        try {
            mockMvc.perform(post("/a2a")
                            .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                            .contentType("application/json")
                            .content(sendMessagePayload(
                                    "capacity-rejected",
                                    "capacity-context",
                                    "Review this asset.",
                                    Map.of("scenario", "asset-review"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("capacity-rejected"))
                    .andExpect(jsonPath("$.error.code").value(-32603));
            assertThat(queues).hasSize(queuesBefore);
        } finally {
            leases.forEach(CreativeAgencyA2AAdmissionGate.Lease::cancelBeforeExecution);
        }
        assertThat(admissionGate.availablePermits()).isEqualTo(admissionGate.capacity());
    }

    @Test
    void supportsProtocolNonBlockingExecutionMode() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "immediate-rejected",
                                "immediate-context",
                                "Review this asset.",
                                Map.of("scenario", "asset-review"),
                                true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("immediate-rejected"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.result.task.id").exists());

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (admissionGate.availablePermits() != admissionGate.capacity()
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(admissionGate.availablePermits()).isEqualTo(admissionGate.capacity());
    }
}
