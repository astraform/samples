package ai.astraform.samples.marketingops.creativeagency.service;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Input for one creative-agency turn, including an optional native event envelope. */
public record CreativeAgencyCommand(
        String contextId,
        String userInput,
        Map<String, Object> metadata) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> QUEUE_STATES = Set.of("review_pending", "blocked", "active", "clear");
    private static final int MAX_INPUT_CHARACTERS = 8_000;
    private static final int MAX_FACTS_CHARACTERS = 16_000;

    public CreativeAgencyCommand {
        contextId = contextId == null ? "" : contextId;
        userInput = userInput == null ? "" : userInput;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Extract customer content before applying limits; transport identifiers are not model context. */
    public Input input() {
        if (userInput.length() > 65_536) {
            throw new IllegalArgumentException("Creative agency request is too large");
        }
        JsonNode envelope = null;
        if (userInput.stripLeading().startsWith("{")) {
            try {
                envelope = JSON.readTree(userInput);
            } catch (JacksonException invalidJson) {
                throw new IllegalArgumentException("Creative agency JSON request is invalid", invalidJson);
            }
        }
        if (envelope != null && "astraform.isolated-event-a2a-invocation.v1"
                .equals(envelope.path("schemaVersion").asString())) {
            JsonNode request = envelope.path("request");
            JsonNode facts = request.path("facts");
            JsonNode campaignKey = facts.path("campaignKey");
            JsonNode queueState = facts.path("queueState");
            if (!facts.isObject() || !facts.path("scenario").isString()
                    || !"launch-blocker".equals(facts.path("scenario").stringValue())
                    || !campaignKey.isString() || campaignKey.stringValue().isBlank()
                    || !queueState.isString() || !QUEUE_STATES.contains(queueState.stringValue())
                    || !request.path("instruction").isString()) {
                throw new IllegalArgumentException(
                        "Native marketing request requires an instruction, scenario launch-blocker, campaignKey and a valid queueState");
            }
            String instruction = checkedInput(request.path("instruction").stringValue());
            if (JSON.writeValueAsString(facts).length() > MAX_FACTS_CHARACTERS) {
                throw new IllegalArgumentException("Creative agency facts are too large");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = JSON.convertValue(facts, Map.class);
            return new Input(CreativeAgencyScenarioKey.LAUNCH_BLOCKER, instruction, values);
        }
        Object requested = metadata.get("scenario");
        CreativeAgencyScenarioKey scenario = requested == null ? null
                : CreativeAgencyScenarioKey.fromValue(String.valueOf(requested)).orElseThrow(
                        () -> new IllegalArgumentException("Unknown creative agency scenario"));
        return new Input(scenario, checkedInput(userInput), Map.of());
    }

    private static String checkedInput(String value) {
        String input = value.trim();
        if (input.isBlank() || input.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("Creative agency instruction must contain 1 to 8000 characters");
        }
        return input;
    }

    public record Input(CreativeAgencyScenarioKey requestedScenario, String instruction, Map<String, Object> facts) {
        public Input {
            facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        }
    }
}
