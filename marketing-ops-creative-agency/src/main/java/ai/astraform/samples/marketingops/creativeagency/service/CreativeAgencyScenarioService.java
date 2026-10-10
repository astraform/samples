package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyReply;
import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;

@Service
public class CreativeAgencyScenarioService {

    private static final Logger log = LoggerFactory.getLogger(CreativeAgencyScenarioService.class);

    private final CreativeAgencyConversationStore conversationStore;
    private final CreativeAgencyResponseGenerator responseGenerator;
    private static final long CONTEXT_LOCK_WAIT_MILLIS = 250;

    private final ReentrantLock[] contextLocks = new ReentrantLock[256];

    public CreativeAgencyScenarioService(
            CreativeAgencyConversationStore conversationStore,
            CreativeAgencyResponseGenerator responseGenerator) {
        this.conversationStore = conversationStore;
        this.responseGenerator = responseGenerator;
        for (int index = 0; index < contextLocks.length; index++) {
            contextLocks[index] = new ReentrantLock();
        }
    }

    public CreativeAgencyReply buildReply(CreativeAgencyCommand command) {
        Map<String, Object> metadata = command.metadata();
        CreativeAgencyCommand.Input input = command.input();
        String contextId = normalize(command.contextId());

        if (contextId.isBlank()) {
            return buildReplyWithinContext(metadata, input, contextId);
        }
        ReentrantLock lock = contextLocks[Math.floorMod(contextId.hashCode(), contextLocks.length)];
        try {
            if (!lock.tryLock(CONTEXT_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new CreativeAgencyGenerationException(
                        CreativeAgencyGenerationException.Code.CONTEXT_BUSY);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CreativeAgencyGenerationException(
                    CreativeAgencyGenerationException.Code.INTERRUPTED,
                    failure);
        }
        try {
            return buildReplyWithinContext(metadata, input, contextId);
        } finally {
            lock.unlock();
        }
    }

    private CreativeAgencyReply buildReplyWithinContext(
            Map<String, Object> metadata,
            CreativeAgencyCommand.Input input,
            String contextId) {
        CreativeAgencyConversationStore.Snapshot existing = conversationStore.get(contextId);
        CreativeAgencyResponseGenerator.GenerationResult generated = responseGenerator.generate(
                new CreativeAgencyResponseGenerator.GenerationRequest(
                        input.requestedScenario(),
                        existing == null ? null : existing.scenario(),
                        input.instruction(),
                        input.facts(),
                        existing != null && existing.turnCount() > 0,
                        existing == null ? List.of() : existing.recentExchanges()));
        String response = renderResponse(generated.scenario(), generated.disposition(), generated);
        CreativeAgencyConversationStore.Snapshot snapshot = conversationStore.record(
                contextId, generated.scenario(), input.instruction(), response);

        log.info("Creative agency response handled: scenario='{}', disposition='{}', turnCount={}, followUp={}, generationProvider='{}', decisionSource='{}'",
                generated.scenario().value(), generated.disposition(), snapshot.turnCount(), snapshot.followUp(),
                generated.provider(), generated.decisionSource());

        return new CreativeAgencyReply(
                generated.scenario(), generated.disposition(), response,
                responseMetadata(metadata, snapshot, generated.disposition(), generated));
    }

    private String renderResponse(
            CreativeAgencyScenarioKey scenario,
            String disposition,
            CreativeAgencyResponseGenerator.GenerationResult generated) {
        StringBuilder response = new StringBuilder()
                .append("Creative Agency Response\n")
                .append("Scenario: ").append(scenario.value()).append('\n')
                .append("Decision: ").append(disposition).append('\n')
                .append("Agency recommendation (advice only):\n")
                .append(generated.summary().trim()).append('\n')
                .append("Suggested next steps (not executed):\n");
        for (int index = 0; index < generated.actions().size(); index++) {
            response.append(index + 1).append(". ").append(generated.actions().get(index).trim()).append('\n');
        }
        if (!generated.needsFromUser().isEmpty()) {
            response.append("Need from you:\n");
            generated.needsFromUser().forEach(item -> response.append("- ").append(item.trim()).append('\n'));
        }
        return response.toString().trim();
    }

    private Map<String, Object> responseMetadata(
            Map<String, Object> requestMetadata,
            CreativeAgencyConversationStore.Snapshot snapshot,
            String disposition,
            CreativeAgencyResponseGenerator.GenerationResult generated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("service", "marketing-ops-creative-agency");
        metadata.put("scenario", snapshot.scenario().value());
        metadata.put("disposition", disposition);
        metadata.put("turnCount", snapshot.turnCount());
        metadata.put("followUp", snapshot.followUp());
        metadata.put("generationProvider", generated.provider());
        metadata.put("generationModel", generated.model());
        metadata.put("generationBindingRevision", generated.bindingRevision());
        metadata.put("generationFallback", false);
        metadata.put("generationContentAuthoritative", false);
        metadata.put("generationDecisionSource", generated.decisionSource());
        metadata.putAll(generated.receiptMetadata());
        copyIfPresent(requestMetadata, metadata, "personaName");
        copyIfPresent(requestMetadata, metadata, "agentKey");
        copyIfPresent(requestMetadata, metadata, "domainId");
        return metadata;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

}
