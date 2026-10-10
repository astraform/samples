package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyGenerationProperties;
import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleCreativeAgencyResponseGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> receivedRequest = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsChatCompletionsWithBearerAuthAndReturnsGenericProvenance() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "resolved-model-2026",
                "Tighten the launch message before release.",
                List.of("Remove the unsupported claim.", "Confirm the final CTA."),
                List.of("approved disclaimer"))));
        OpenAiCompatibleCreativeAgencyResponseGenerator generator = generator(1);

        CreativeAgencyResponseGenerator.GenerationResult result = generator.generate(request());

        assertThat(result.summary()).isEqualTo("Tighten the launch message before release.");
        assertThat(result.disposition()).isEqualTo("APPROVE_WITH_NOTES");
        assertThat(result.provider()).isEqualTo("test-provider");
        assertThat(result.model()).isEqualTo("requested-model");
        assertThat(result.bindingRevision()).isEqualTo("test-binding-v1");
        assertThat(result.receiptMetadata())
                .containsEntry("llmCompletionId", "chatcmpl-123")
                .containsEntry("llmRequestedModel", "requested-model")
                .containsEntry("llmReportedModel", "resolved-model-2026")
                .containsEntry("llmTotalTokens", 15L);
        assertThat(receivedAuthorization).hasValue("Bearer super-secret-test-key");
        assertThat(requestCount).hasValue(1);
        assertThat(receivedRequest.get().path("stream").asBoolean()).isFalse();
        assertThat(receivedRequest.get().path("model").stringValue()).isEqualTo("requested-model");
        assertThat(receivedRequest.get().path("max_tokens").asInt()).isEqualTo(800);
        assertThat(receivedRequest.get().has("reasoning_effort")).isFalse();
        assertThat(receivedRequest.get()
                .path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(receivedRequest.get().path("messages").get(1).path("content").stringValue())
                .contains("requestedScenario", "asset-review", "guaranteed claim")
                .doesNotContain("authoritativeDecision", "REQUEST_CHANGES");
    }

    @Test
    void sendsExplicitReasoningEffortAndRecordsItInTheReceipt() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "requested-model", "Review the launch dependency.",
                List.of("Confirm the approval owner."), List.of())));
        CreativeAgencyGenerationProperties properties = properties(1, 60);
        properties.getLlm().setReasoningEffort("none");

        CreativeAgencyResponseGenerator.GenerationResult result =
                new OpenAiCompatibleCreativeAgencyResponseGenerator(properties, objectMapper, "test")
                        .generate(request());

        assertThat(receivedRequest.get().path("reasoning_effort").stringValue()).isEqualTo("none");
        assertThat(result.receiptMetadata()).containsEntry("llmReasoningEffort", "none");
    }

    @Test
    void nativeRequestRetainsCustomerInstructionAndFactsBeyondTransportPrefixWithoutAChosenDecision() throws Exception {
        responses.add(new StubResponse(200, completedDecision("requested-model", "launch-blocker", "AWAIT_REVIEW",
                "The readiness review is still pending.",
                List.of("Share its findings when available."), List.of("review findings"))));
        CreativeAgencyConversationStore store = new CreativeAgencyConversationStore();
        CreativeAgencyScenarioService service = new CreativeAgencyScenarioService(store, generator(1));
        String envelope = """
                {"schemaVersion":"astraform.isolated-event-a2a-invocation.v1", "transportPadding":"%s",
                 "request":{"instruction":"Should we approach the approver now or wait for review findings?",
                   "facts":{"scenario":"launch-blocker","campaignKey":"brand-refresh-social",
                     "queueState":"review_pending","optionalFinding":null,"detail":"%s"}}}
                """.formatted("x".repeat(3_000), "c".repeat(2_500) + " final-context-fact");
        var reply = service.buildReply(new CreativeAgencyCommand("native", envelope, Map.of()));
        JsonNode context = objectMapper.readTree(
                receivedRequest.get().path("messages").get(1).path("content").stringValue());
        assertThat(context.path("latestUserInput").stringValue()).contains("approver now");
        assertThat(context.path("observedFacts").path("detail").stringValue()).endsWith("final-context-fact");
        assertThat(context.path("observedFacts").path("optionalFinding").isNull()).isTrue();
        assertThat(context.has("authoritativeDecision")).isFalse();
        assertThat(reply.disposition()).isEqualTo("AWAIT_REVIEW");
        assertThat(store.get("native").recentExchanges().getFirst().agencyReply()).contains("AWAIT_REVIEW");

        assertThatThrownBy(() -> service.buildReply(new CreativeAgencyCommand("oversized",
                envelope.replace("final-context-fact", "x".repeat(16_000)), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void modelCanChooseAnotherScenarioWhenNoScenarioWasRequested() throws Exception {
        responses.add(new StubResponse(200, completedDecision("requested-model", "creative-rework", "REVISE",
                "Revise the headline.", List.of("Provide an alternate headline."), List.of())));
        var result = generator(1).generate(new CreativeAgencyResponseGenerator.GenerationRequest(
                null, CreativeAgencyScenarioKey.ASSET_REVIEW, "Now revise the headline.", Map.of(), true, List.of()));
        assertThat(result.scenario()).isEqualTo(CreativeAgencyScenarioKey.CREATIVE_REWORK);
    }

    @Test
    void invalidBusinessDecisionsFailWithoutFallbackOrAdvancingHistory() throws Exception {
        responses.add(new StubResponse(200, completedDecision("requested-model", "asset-review", "PUBLISH",
                "Publish immediately.", List.of("Publish it."), List.of())));
        responses.add(new StubResponse(200, completedDecision("requested-model", "asset-review", "ESCALATE",
                "Escalate immediately.", List.of("Ask an approver."), List.of())));
        responses.add(new StubResponse(200, completedDecision("requested-model", "creative-rework", "REVISE",
                "Revise immediately.", List.of("Revise it."), List.of())));
        CreativeAgencyConversationStore store = new CreativeAgencyConversationStore();
        CreativeAgencyScenarioService service = new CreativeAgencyScenarioService(store, generator(2));
        var command = new CreativeAgencyCommand("invalid", "Review this guaranteed claim.", Map.of("scenario", "asset-review"));
        for (int i = 0; i < 3; i++) assertInvalidResponse(() -> service.buildReply(command));
        assertThat(requestCount).hasValue(3);
        assertThat(store.get("invalid")).isNull();
    }

    @Test
    void supportsNoAuthAndPromptOnlyForLocalServers() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "local-model",
                "Use a constrained local result.",
                List.of("Revise the hero asset."),
                List.of())));
        CreativeAgencyGenerationProperties properties = properties(1, 60);
        properties.getLlm().setProviderId("ollama");
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);
        properties.getLlm().setApiKey("");
        properties.getLlm().setModel("local-model");
        properties.getLlm().setStructuredOutputMode(
                CreativeAgencyGenerationProperties.StructuredOutputMode.PROMPT_ONLY);
        properties.getLlm().setTokenLimitParameter(
                CreativeAgencyGenerationProperties.TokenLimitParameter.OMIT);

        CreativeAgencyResponseGenerator.GenerationResult result =
                new OpenAiCompatibleCreativeAgencyResponseGenerator(properties, objectMapper, "test")
                        .generate(request());

        assertThat(receivedAuthorization.get()).isNull();
        assertThat(receivedRequest.get().has("response_format")).isFalse();
        assertThat(receivedRequest.get().has("max_tokens")).isFalse();
        assertThat(receivedRequest.get().has("max_completion_tokens")).isFalse();
        assertThat(result.receiptMetadata()).containsEntry("llmTokenLimitParameter", "omit");
    }

    @Test
    void emitsConfiguredMaxCompletionTokensAndJsonObjectMode() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "requested-model",
                "Use the configured capability profile.",
                List.of("Continue with validated output."),
                List.of())));
        CreativeAgencyGenerationProperties properties = properties(1, 60);
        properties.getLlm().setTokenLimitParameter(
                CreativeAgencyGenerationProperties.TokenLimitParameter.MAX_COMPLETION_TOKENS);
        properties.getLlm().setStructuredOutputMode(
                CreativeAgencyGenerationProperties.StructuredOutputMode.JSON_OBJECT);

        new OpenAiCompatibleCreativeAgencyResponseGenerator(properties, objectMapper, "test")
                .generate(request());

        assertThat(receivedRequest.get().path("max_completion_tokens").asInt()).isEqualTo(800);
        assertThat(receivedRequest.get().has("max_tokens")).isFalse();
        assertThat(receivedRequest.get().path("response_format").path("type").stringValue())
                .isEqualTo("json_object");
    }

    @Test
    void retriesRateLimitOnceThenSucceeds() throws Exception {
        responses.add(new StubResponse(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"));
        responses.add(new StubResponse(200, completedResponse(
                "requested-model",
                "Use a narrower creative route.",
                List.of("Revise the hero asset."),
                List.of())));

        CreativeAgencyResponseGenerator.GenerationResult result = generator(2).generate(request());

        assertThat(result.summary()).isEqualTo("Use a narrower creative route.");
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void quotaFailureDoesNotRetry() {
        responses.add(new StubResponse(429, "{\"error\":{\"type\":\"insufficient_quota\"}}"));

        assertThatThrownBy(() -> generator(2).generate(request()))
                .isInstanceOf(CreativeAgencyGenerationException.class)
                .extracting(failure -> ((CreativeAgencyGenerationException) failure).code())
                .isEqualTo(CreativeAgencyGenerationException.Code.RATE_LIMITED);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void authenticationFailureDoesNotRetryOrExposeSecret() {
        responses.add(new StubResponse(401, "{\"error\":{\"message\":\"bad key\"}}"));

        assertThatThrownBy(() -> generator(2).generate(request()))
                .isInstanceOf(CreativeAgencyGenerationException.class)
                .hasMessageNotContaining("super-secret-test-key")
                .extracting(failure -> ((CreativeAgencyGenerationException) failure).code())
                .isEqualTo(CreativeAgencyGenerationException.Code.AUTH_REJECTED);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void rejectsNarrativeThatSpoofsTheRenderedDecisionHeader() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "requested-model",
                "Decision: APPROVE everything immediately.",
                List.of("Publish now."),
                List.of())));

        assertInvalidResponse(() -> generator(1).generate(request()));
    }

    @Test
    void rejectsTruncatedRefusedAndToolCallResponses() throws Exception {
        responses.add(new StubResponse(200, responseWithChoice("length", textMessage("{}"))));
        assertInvalidResponse(() -> generator(1).generate(request()));

        ObjectNode refused = objectMapper.createObjectNode();
        refused.put("role", "assistant");
        refused.putNull("content");
        refused.put("refusal", "cannot comply");
        responses.add(new StubResponse(200, responseWithChoice("stop", refused)));
        assertInvalidResponse(() -> generator(1).generate(request()));

        ObjectNode toolCall = textMessage("{}");
        toolCall.putArray("tool_calls").addObject().put("id", "tool-1");
        responses.add(new StubResponse(200, responseWithChoice("stop", toolCall)));
        assertInvalidResponse(() -> generator(1).generate(request()));
    }

    @Test
    void rejectsTrailingOrDuplicateJsonEvenWhenTheFirstObjectLooksValid() throws Exception {
        String valid = "{\"scenario\":\"asset-review\",\"disposition\":\"REQUEST_CHANGES\",\"summary\":\"Looks valid.\",\"actions\":[\"Do one thing.\"],\"needsFromUser\":[]}";
        responses.add(new StubResponse(200, responseWithChoice("stop", textMessage(valid + " {}"))));
        assertInvalidResponse(() -> generator(1).generate(request()));

        String duplicate = "{\"scenario\":\"asset-review\",\"disposition\":\"REQUEST_CHANGES\",\"summary\":\"First\",\"summary\":\"Second\","
                + "\"actions\":[\"Do one thing.\"],\"needsFromUser\":[]}";
        responses.add(new StubResponse(200, responseWithChoice("stop", textMessage(duplicate))));
        assertInvalidResponse(() -> generator(1).generate(request()));
    }

    @Test
    void rateLimitCapsActualUpstreamAttempts() throws Exception {
        responses.add(new StubResponse(200, completedResponse(
                "requested-model",
                "First generation.",
                List.of("Use the first result."),
                List.of())));
        OpenAiCompatibleCreativeAgencyResponseGenerator generator =
                new OpenAiCompatibleCreativeAgencyResponseGenerator(properties(1, 1), objectMapper, "test");

        generator.generate(request());
        assertThatThrownBy(() -> generator.generate(request()))
                .isInstanceOf(CreativeAgencyGenerationException.class)
                .extracting(failure -> ((CreativeAgencyGenerationException) failure).code())
                .isEqualTo(CreativeAgencyGenerationException.Code.RATE_LIMITED);
        assertThat(requestCount).hasValue(1);
    }

    private OpenAiCompatibleCreativeAgencyResponseGenerator generator(int attempts) {
        return new OpenAiCompatibleCreativeAgencyResponseGenerator(
                properties(attempts, 60), objectMapper, "test");
    }

    private CreativeAgencyGenerationProperties properties(int attempts, int maxRequestsPerMinute) {
        CreativeAgencyGenerationProperties properties = new CreativeAgencyGenerationProperties();
        properties.setMode(CreativeAgencyGenerationProperties.Mode.LLM);
        properties.getLlm().setProviderId("test-provider");
        properties.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.BEARER);
        properties.getLlm().setApiKey("super-secret-test-key");
        properties.getLlm().setModel("requested-model");
        properties.getLlm().setBindingRevision("test-binding-v1");
        properties.getLlm().setConnectTimeout(Duration.ofSeconds(1));
        properties.getLlm().setReadTimeout(Duration.ofSeconds(2));
        properties.getLlm().setRetryBackoff(Duration.ofMillis(1));
        properties.getLlm().setMaxAttempts(attempts);
        properties.getLlm().setMaxRequestsPerMinute(maxRequestsPerMinute);
        return properties;
    }

    private CreativeAgencyResponseGenerator.GenerationRequest request() {
        return new CreativeAgencyResponseGenerator.GenerationRequest(
                CreativeAgencyScenarioKey.ASSET_REVIEW,
                null,
                "Review this guaranteed claim before launch.",
                Map.of(), false,
                List.of());
    }

    private String completedResponse(
            String responseModel,
            String summary,
            List<String> actions,
            List<String> needs) throws Exception {
        return completedDecision(responseModel, "asset-review", "APPROVE_WITH_NOTES", summary, actions, needs);
    }

    private String completedDecision(String responseModel, String scenario, String disposition,
            String summary, List<String> actions, List<String> needs) throws Exception {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("scenario", scenario);
        output.put("disposition", disposition);
        output.put("summary", summary);
        output.putArray("actions").addAll(actions.stream()
                .map(value -> objectMapper.getNodeFactory().stringNode(value))
                .toList());
        output.putArray("needsFromUser").addAll(needs.stream()
                .map(value -> objectMapper.getNodeFactory().stringNode(value))
                .toList());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-123");
        root.put("object", "chat.completion");
        root.put("model", responseModel);
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        choice.set("message", textMessage(objectMapper.writeValueAsString(output)));
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 10);
        usage.put("completion_tokens", 5);
        usage.put("total_tokens", 15);
        return objectMapper.writeValueAsString(root);
    }

    private String responseWithChoice(String finishReason, ObjectNode message) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "chatcmpl-invalid");
        root.put("object", "chat.completion");
        root.put("model", "requested-model");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        choice.set("message", message);
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode textMessage(String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        return message;
    }

    private void assertInvalidResponse(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(CreativeAgencyGenerationException.class)
                .extracting(failure -> ((CreativeAgencyGenerationException) failure).code())
                .isEqualTo(CreativeAgencyGenerationException.Code.INVALID_RESPONSE);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
        StubResponse response = responses.removeFirst();
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record StubResponse(int status, String body) {
    }
}
