package ai.astraform.samples.marketingops.creativeagency.model;

import java.util.Arrays;
import java.util.Optional;

public enum CreativeAgencyScenarioKey {
    CREATIVE_REWORK("creative-rework"),
    ASSET_REVIEW("asset-review"),
    LAUNCH_BLOCKER("launch-blocker");

    private final String value;

    CreativeAgencyScenarioKey(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CreativeAgencyScenarioKey> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
