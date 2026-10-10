package banking.simulator.remote.domain.scenariolab;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.firstNonBlank;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.listOfMaps;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.objectMap;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.scenarioIdForPreset;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.stringList;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.stringValue;

final class BankingCustomerOutcomeDecisionCatalog {

    static final Path MANIFEST_PATH = Path.of("scenarios/customer-outcome/banking-wind-tunnel/manifest.json");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;

    BankingCustomerOutcomeDecisionCatalog(ObjectMapper objectMapper, Path workspaceRoot) {
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
        this.workspaceRoot = workspaceRoot;
    }

    DecisionComparisonDefinition resolve(ScenarioLabRunParameters parameters, PolicyType policyType) {
        Map<String, Object> manifest = loadManifest();
        String presetId = stringValue(parameters.getPresetId());
        for (Map<String, Object> scenario : listOfMaps(manifest.get("decisionScenarios"))) {
            if (!scenarioMatchesPreset(scenario, presetId)) {
                continue;
            }
            return fromScenarioDefinition(
                    manifest,
                    scenario,
                    parameters,
                    policyType);
        }
        return fallbackDefinition(parameters, policyType);
    }

    private DecisionComparisonDefinition fromScenarioDefinition(
            Map<String, Object> manifest,
            Map<String, Object> scenario,
            ScenarioLabRunParameters parameters,
            PolicyType policyType) {
        Map<String, Object> demoTarget = objectMap(manifest.get("demoComparisonTarget"));
        boolean demoTargetMatches = stringValue(demoTarget.get("scenarioId")).equals(stringValue(scenario.get("scenarioId")));
        String comparisonId = demoTargetMatches
                ? stringValue(demoTarget.get("comparisonId"))
                : stringValue(scenario.get("scenarioId")) + "-baseline-vs-proposed";
        String title = demoTargetMatches
                ? stringValue(demoTarget.get("title"))
                : stringValue(scenario.get("label"));
        String decisionQuestion = firstNonBlank(
                demoTargetMatches ? stringValue(demoTarget.get("decisionQuestion")) : "",
                stringValue(scenario.get("decisionUnderTest")),
                defaultDecisionQuestion(parameters, policyType));

        Map<String, Object> baseline = objectMap(scenario.get("baseline"));
        Map<String, Object> proposed = objectMap(scenario.get("proposed"));
        return new DecisionComparisonDefinition(
                firstNonBlank(comparisonId, stringValue(parameters.getPresetId()) + "-baseline-vs-proposed"),
                firstNonBlank(stringValue(scenario.get("scenarioId")), scenarioIdForPreset(parameters.getPresetId())),
                firstNonBlank(title, stringValue(parameters.getPresetId()) + " Decision Review"),
                decisionQuestion,
                branchDefinition(
                        firstNonBlank(
                                demoTargetMatches ? stringValue(demoTarget.get("baselineBranchId")) : "",
                                stringValue(baseline.get("branchId")),
                                "baseline-current-policy"),
                        "baseline",
                        "Baseline",
                        firstNonBlank(stringValue(baseline.get("description")), "Current branch behavior."),
                        firstNonBlank(stringValue(baseline.get("presetId")), stringValue(parameters.getPresetId())),
                        objectMap(baseline.get("parameters"))),
                branchDefinition(
                        firstNonBlank(
                                demoTargetMatches ? stringValue(demoTarget.get("proposedBranchId")) : "",
                                stringValue(proposed.get("branchId")),
                                "proposed-candidate-policy"),
                        "proposed",
                        "Proposed",
                        firstNonBlank(stringValue(proposed.get("description")), "Candidate branch behavior."),
                        firstNonBlank(stringValue(proposed.get("presetId")), stringValue(parameters.getPresetId())),
                        objectMap(proposed.get("parameters"))),
                stringList(scenario.get("requiredReplayEvidence")));
    }

    private DecisionComparisonDefinition fallbackDefinition(ScenarioLabRunParameters parameters, PolicyType policyType) {
        String presetId = stringValue(parameters.getPresetId());
        String scenarioId = scenarioIdForPreset(presetId);
        return new DecisionComparisonDefinition(
                presetId + "-baseline-vs-proposed",
                scenarioId,
                presetId + " Decision Review",
                defaultDecisionQuestion(parameters, policyType),
                branchDefinition(
                        "baseline-current-policy",
                        "baseline",
                        "Baseline",
                        "Current branch behavior before the candidate change.",
                        presetId,
                        Map.of()),
                branchDefinition(
                        "proposed-candidate-policy",
                        "proposed",
                        "Proposed",
                        "Candidate branch behavior after the proposed change.",
                        presetId,
                        Map.of()),
                List.of(
                        "customerProfile",
                        "timeline",
                        "toolApiLedger",
                        "policyCheckLedger",
                        "stateChangeDiff",
                        "failureReason"));
    }

    private boolean scenarioMatchesPreset(Map<String, Object> scenario, String presetId) {
        return stringList(scenario.get("implementedPresetIds")).contains(presetId)
                || stringList(scenario.get("plannedPresetIds")).contains(presetId);
    }

    private String defaultDecisionQuestion(ScenarioLabRunParameters parameters, PolicyType policyType) {
        return switch (policyType) {
            case RATE -> "Should the proposed rate decision replace the current policy for this customer cohort?";
            case LIMIT -> "Should the proposed credit-line decision replace the current policy for this customer cohort?";
            case HARDSHIP -> "Should the proposed hardship policy replace the current policy for vulnerable customers?";
            case FRAUD -> "Should the proposed fraud or dispute workflow replace the current operating rule?";
        };
    }

    private DecisionBranchDefinition branchDefinition(
            String branchId,
            String branchRole,
            String label,
            String description,
            String presetId,
            Map<String, Object> parameters) {
        return new DecisionBranchDefinition(
                branchId,
                branchRole,
                label,
                description,
                presetId,
                new LinkedHashMap<>(parameters));
    }

    private Map<String, Object> loadManifest() {
        Path path = workspaceRoot.resolve(MANIFEST_PATH).normalize();
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(path.toFile(), MAP_TYPE);
        } catch (JacksonException ignored) {
            return Map.of();
        }
    }

    record DecisionComparisonDefinition(
            String comparisonId,
            String scenarioId,
            String title,
            String decisionQuestion,
            DecisionBranchDefinition baseline,
            DecisionBranchDefinition proposed,
            List<String> requiredEvidence) {
    }

    record DecisionBranchDefinition(
            String branchId,
            String branchRole,
            String label,
            String description,
            String presetId,
            Map<String, Object> parameters) {
    }
}
