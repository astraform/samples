package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CreativeAgencyConversationStore {

    private static final int MAX_EXCHANGES = 5;

    private final ConcurrentMap<String, ConversationState> conversations = new ConcurrentHashMap<>();

    public Snapshot get(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return null;
        }
        ConversationState state = conversations.get(contextId);
        return state == null ? null : state.snapshot(contextId);
    }

    public Snapshot record(String contextId, CreativeAgencyScenarioKey scenario, String message, String agencyReply) {
        if (contextId == null || contextId.isBlank()) {
            return new Snapshot(null, scenario, 1, List.of(new Exchange(message, agencyReply)), Instant.now());
        }
        ConversationState state = conversations.compute(contextId, (ignored, existing) -> {
            ConversationState next = existing == null ? new ConversationState() : existing;
            next.scenario = scenario;
            next.turnCount++;
            next.updatedAt = Instant.now();
            Exchange exchange = new Exchange(message, agencyReply);
            if (!exchange.customerInput().isBlank()) {
                if (next.recentExchanges.size() == MAX_EXCHANGES) {
                    next.recentExchanges.removeFirst();
                }
                next.recentExchanges.addLast(exchange);
            }
            return next;
        });
        return state.snapshot(contextId);
    }

    public record Snapshot(
            String contextId,
            CreativeAgencyScenarioKey scenario,
            int turnCount,
            List<Exchange> recentExchanges,
            Instant updatedAt
    ) {
        public boolean followUp() {
            return turnCount > 1;
        }
    }

    public record Exchange(String customerInput, String agencyReply) {
        public Exchange {
            customerInput = customerInput == null ? "" : customerInput;
            agencyReply = agencyReply == null ? "" : agencyReply;
        }
    }

    private static final class ConversationState {
        private CreativeAgencyScenarioKey scenario;
        private int turnCount;
        private final Deque<Exchange> recentExchanges = new ArrayDeque<>();
        private Instant updatedAt = Instant.now();

        private Snapshot snapshot(String contextId) {
            return new Snapshot(contextId, scenario, turnCount, List.copyOf(new ArrayList<>(recentExchanges)), updatedAt);
        }
    }
}
