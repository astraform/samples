package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface CreativeAgencyResponseGenerator {

    GenerationResult generate(GenerationRequest request);

    record GenerationRequest(
            CreativeAgencyScenarioKey requestedScenario,
            CreativeAgencyScenarioKey previousScenario,
            String userInput,
            Map<String, Object> facts,
            boolean followUp,
            List<CreativeAgencyConversationStore.Exchange> recentExchanges
    ) {
        public GenerationRequest {
            facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
            recentExchanges = recentExchanges == null ? List.of() : List.copyOf(recentExchanges);
        }
    }

    record GenerationResult(
            CreativeAgencyScenarioKey scenario,
            String disposition,
            String summary,
            List<String> actions,
            List<String> needsFromUser,
            String provider,
            String model,
            String bindingRevision,
            String decisionSource,
            Map<String, Object> receiptMetadata
    ) {
        public GenerationResult {
            actions = actions == null ? List.of() : List.copyOf(actions);
            needsFromUser = needsFromUser == null ? List.of() : List.copyOf(needsFromUser);
            receiptMetadata = receiptMetadata == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(receiptMetadata));
        }
    }
}
