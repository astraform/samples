package ai.astraform.samples.marketingops.creativeagency.model;

import java.util.Map;

public record CreativeAgencyReply(
        CreativeAgencyScenarioKey scenario,
        String disposition,
        String responseText,
        Map<String, Object> metadata
) {}
