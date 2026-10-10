package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativeAgencyScenarioServiceTest {

    @Test
    void nativeEnquiryAwaitsActualReviewThenUsesTheUpdatedBlockedObservation() {
        var service = new CreativeAgencyScenarioService(new CreativeAgencyConversationStore(),
                new DeterministicCreativeAgencyResponseGenerator());
        CreativeAgencyReply pending = service.buildReply(nativeCommand("review_pending"));
        assertThat(pending.disposition()).isEqualTo("AWAIT_REVIEW");
        assertThat(pending.responseText()).contains("review findings");
        assertThat(pending.responseText()).doesNotContain("Latest input noted:",
                "96a58bd3-9651-49cf-ad46-2766894d622a", "25aee67a-aa65-43b0-87d1-c0a1f3fbeb02");

        // Same conversation, but a later admitted domain observation now establishes the blocker.
        CreativeAgencyReply blocked = service.buildReply(nativeCommand("blocked"));
        assertThat(blocked.disposition()).isEqualTo("ESCALATE");
        assertThat(blocked.metadata()).containsEntry("followUp", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "clear"})
    void nativeNonblockedObservationDoesNotBecomeABlockerFromMessageKeywords(String state) {
        var service = new CreativeAgencyScenarioService(new CreativeAgencyConversationStore(),
                new DeterministicCreativeAgencyResponseGenerator());
        assertThat(service.buildReply(nativeCommand(state)).disposition()).isEqualTo("NO_EXTERNAL_BLOCKER");
    }

    @Test
    void nativeRequestWithoutObservedFactsIsRejectedButLegacyTextStillUsesExistingRules() {
        var service = new CreativeAgencyScenarioService(new CreativeAgencyConversationStore(),
                new DeterministicCreativeAgencyResponseGenerator());
        assertThatThrownBy(() -> service.buildReply(new CreativeAgencyCommand("invalid-native", """
                {"schemaVersion":"astraform.isolated-event-a2a-invocation.v1",
                 "request":{"instruction":"Escalate the external launch blocker.","facts":{}}}
                """, Map.of("scenario", "launch-blocker"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.buildReply(command("legacy", "Escalate the external launch blocker.")).disposition())
                .isEqualTo("ESCALATE");
    }

    private CreativeAgencyCommand nativeCommand(String queueState) {
        return new CreativeAgencyCommand("native-review-context", """
                {"schemaVersion":"astraform.isolated-event-a2a-invocation.v1",
                 "eventId":"96a58bd3-9651-49cf-ad46-2766894d622a",
                 "runId":"25aee67a-aa65-43b0-87d1-c0a1f3fbeb02", "agentId":"customer-1",
                 "invocationKey":"invocation-1", "logicalTimeEpochMs":1768465800000,
                 "request":{"instruction":"What can you tell me about the campaign's external launch blocker?",
                            "facts":{"campaignKey":"brand-refresh-social","queueState":"%s",
                                     "domainId":"marketing-ops","scenario":"launch-blocker"}}}
                """.formatted(queueState), Map.of("scenario", "asset-review", "domainId", "marketing-ops"));
    }

    @Test
    void followUpReceivesTheActualBoundedCustomerAndAgencyExchanges() {
        var requests = new java.util.ArrayList<CreativeAgencyResponseGenerator.GenerationRequest>();
        var deterministic = new DeterministicCreativeAgencyResponseGenerator();
        var service = new CreativeAgencyScenarioService(new CreativeAgencyConversationStore(), request -> {
            requests.add(request);
            return deterministic.generate(request);
        });
        var first = service.buildReply(command("history", "Please revise my campaign asset."));
        service.buildReply(command("history", "Can you clarify your previous recommendation?"));
        var previous = requests.getLast().recentExchanges().getFirst();
        assertThat(previous.customerInput()).isEqualTo("Please revise my campaign asset.");
        assertThat(previous.agencyReply()).contains(first.responseText().replaceAll("\\s+", " "));
        for (int index = 0; index < 6; index++) service.buildReply(command("history", "Please revise asset " + index));
        assertThat(requests.getLast().recentExchanges()).hasSize(5);
    }

    @Test
    void failedGenerationDoesNotAdvanceConversationState() {
        AtomicInteger calls = new AtomicInteger();
        DeterministicCreativeAgencyResponseGenerator deterministic =
                new DeterministicCreativeAgencyResponseGenerator();
        CreativeAgencyResponseGenerator generator = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new CreativeAgencyGenerationException(
                        CreativeAgencyGenerationException.Code.UPSTREAM_UNAVAILABLE);
            }
            return deterministic.generate(request);
        };
        CreativeAgencyScenarioService service = new CreativeAgencyScenarioService(
                new CreativeAgencyConversationStore(),
                generator);
        CreativeAgencyCommand command = command(
                "retry-context",
                "Please rework the launch asset.");

        assertThatThrownBy(() -> service.buildReply(command))
                .isInstanceOf(CreativeAgencyGenerationException.class);

        CreativeAgencyReply retry = service.buildReply(command);
        assertThat(retry.metadata())
                .containsEntry("turnCount", 1)
                .containsEntry("followUp", false);
    }

    @Test
    void rejectsBusyContextThenAllowsSafeRetryAfterTheActiveTurnCommits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> observedFollowUp = new CopyOnWriteArrayList<>();
        CountDownLatch firstGenerationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstGeneration = new CountDownLatch(1);
        DeterministicCreativeAgencyResponseGenerator deterministic =
                new DeterministicCreativeAgencyResponseGenerator();
        CreativeAgencyResponseGenerator generator = request -> {
            int ordinal = calls.getAndIncrement();
            observedFollowUp.add(request.followUp());
            if (ordinal == 0) {
                firstGenerationEntered.countDown();
                try {
                    if (!releaseFirstGeneration.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("first generation was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CreativeAgencyGenerationException(
                            CreativeAgencyGenerationException.Code.INTERRUPTED,
                            failure);
                }
            }
            return deterministic.generate(request);
        };
        CreativeAgencyScenarioService service = new CreativeAgencyScenarioService(
                new CreativeAgencyConversationStore(),
                generator);
        CreativeAgencyCommand first = command("shared-context", "Please revise asset one.");
        CreativeAgencyCommand second = command("shared-context", "Please revise asset two.");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CreativeAgencyReply> firstReply = executor.submit(() -> service.buildReply(first));
            assertThat(firstGenerationEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<CreativeAgencyReply> secondReply = executor.submit(() -> service.buildReply(second));

            ExecutionException busy = assertThrows(
                    ExecutionException.class,
                    () -> secondReply.get(1, TimeUnit.SECONDS));
            assertThat(busy.getCause()).isInstanceOf(CreativeAgencyGenerationException.class);
            assertThat(((CreativeAgencyGenerationException) busy.getCause()).code())
                    .isEqualTo(CreativeAgencyGenerationException.Code.CONTEXT_BUSY);
            releaseFirstGeneration.countDown();

            assertThat(firstReply.get(2, TimeUnit.SECONDS).metadata()).containsEntry("turnCount", 1);
            assertThat(service.buildReply(second).metadata())
                    .containsEntry("turnCount", 2)
                    .containsEntry("followUp", true);
            assertThat(observedFollowUp).containsExactly(false, true);
        } finally {
            releaseFirstGeneration.countDown();
            executor.shutdownNow();
        }
    }

    private CreativeAgencyCommand command(String contextId, String input) {
        return new CreativeAgencyCommand(contextId, input, Map.of());
    }
}
