package ai.astraform.samples.marketingops.creativeagency.a2a;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.a2aproject.sdk.common.A2AHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.PROTOCOL_VERSION;
import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.sendMessagePayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "marketing-ops-creative-agency.generation.mode=llm",
        "marketing-ops-creative-agency.a2a.inbound-token=integration-a2a-token-that-is-long-enough",
        "marketing-ops-creative-agency.generation.llm.provider-id=fixture-provider",
        "marketing-ops-creative-agency.generation.llm.auth-type=bearer",
        "marketing-ops-creative-agency.generation.llm.api-key=integration-secret-key",
        "marketing-ops-creative-agency.generation.llm.model=fixture-requested-model",
        "marketing-ops-creative-agency.generation.llm.binding-revision=integration-binding-v1",
        "marketing-ops-creative-agency.generation.llm.connect-timeout=1s",
        "marketing-ops-creative-agency.generation.llm.read-timeout=2s",
        "marketing-ops-creative-agency.generation.llm.max-attempts=1",
        "marketing-ops-creative-agency.a2a.blocking-agent-timeout-seconds=10"
})
@AutoConfigureMockMvc
class CreativeAgencyLlmA2AControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicReference<String> RECEIVED_AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<JsonNode> RECEIVED_REQUEST = new AtomicReference<>();
    private static final HttpServer LLM_FIXTURE = startLlmFixture();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void llmEndpoint(DynamicPropertyRegistry registry) {
        registry.add(
                "marketing-ops-creative-agency.generation.llm.base-url",
                () -> "http://127.0.0.1:" + LLM_FIXTURE.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopLlmFixture() {
        LLM_FIXTURE.stop(0);
    }

    @Test
    void completesA2aTaskWithTheModelDecisionInsteadOfThePreviousKeywordRule() throws Exception {
        mockMvc.perform(post("/a2a")
                        .header(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER,
                                "integration-a2a-token-that-is-long-enough")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "llm-request-1",
                                "llm-context-1",
                                "Review this guaranteed claim and missing disclaimer.",
                                Map.of("scenario", "asset-review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Scenario: asset-review")))
                .andExpect(jsonPath("$.result.task.status.message.parts[0].text")
                        .value(org.hamcrest.Matchers.containsString("Decision: APPROVE_WITH_NOTES")))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationDecisionSource").value("llm"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationProvider")
                        .value("fixture-provider"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationModel")
                        .value("fixture-requested-model"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationBindingRevision")
                        .value("integration-binding-v1"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.llmCompletionId")
                        .value("chatcmpl-integration"));

        assertThat(RECEIVED_AUTHORIZATION).hasValue("Bearer integration-secret-key");
        assertThat(RECEIVED_REQUEST.get().path("messages").get(1).path("content").stringValue())
                .contains("requestedScenario", "asset-review", "guaranteed claim")
                .doesNotContain("authoritativeDecision", "REQUEST_CHANGES");
    }

    @Test
    void rejectsLlmBackedA2aCallBeforeBodyHandlingWhenPartnerTokenIsMissing() throws Exception {
        mockMvc.perform(post("/a2a")
                        .contentType("application/json")
                        .content("{not-even-valid-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void advertisesRequiredPartnerTokenInLlmModeAgentCard() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securitySchemes.astraformAgentToken.apiKeySecurityScheme.location")
                        .value("header"))
                .andExpect(jsonPath("$.securitySchemes.astraformAgentToken.apiKeySecurityScheme.name")
                        .value(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER));
    }

    private static HttpServer startLlmFixture() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", CreativeAgencyLlmA2AControllerTest::respond);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        RECEIVED_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        RECEIVED_REQUEST.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));

        ObjectNode output = OBJECT_MAPPER.createObjectNode();
        output.put("scenario", "asset-review");
        output.put("disposition", "APPROVE_WITH_NOTES");
        output.put("summary", "The concept can proceed with the supplied notes.");
        output.putArray("actions").add("Remove the guarantee.").add("Add the required disclaimer.");
        output.putArray("needsFromUser").add("approved disclaimer copy");

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("id", "chatcmpl-integration");
        root.put("object", "chat.completion");
        root.put("model", "fixture-resolved-model");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", OBJECT_MAPPER.writeValueAsString(output));
        root.putObject("usage").put("total_tokens", 20);

        byte[] body = OBJECT_MAPPER.writeValueAsBytes(root);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
