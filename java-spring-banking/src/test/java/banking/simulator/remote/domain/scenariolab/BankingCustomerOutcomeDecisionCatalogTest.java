package banking.simulator.remote.domain.scenariolab;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankingCustomerOutcomeDecisionCatalogTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void resolvesHardshipComparisonFromCustomerOutcomeManifest() throws Exception {
        Path manifest = workspaceRoot.resolve(BankingCustomerOutcomeDecisionCatalog.MANIFEST_PATH);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {
                  "decisionScenarios": [
                    {
                      "scenarioId": "hardship-handling",
                      "label": "Hardship Handling",
                      "decisionUnderTest": "Should the bank change hardship policy?",
                      "implementedPresetIds": ["hardship-relief", "hardship-threshold-tradeoff"],
                      "baseline": {
                        "branchId": "baseline-current-hardship-policy",
                        "presetId": "hardship-relief",
                        "description": "Current hardship policy.",
                        "parameters": { "candidateHardshipThreshold": 100 }
                      },
                      "proposed": {
                        "branchId": "proposed-wider-hardship-relief",
                        "presetId": "hardship-threshold-tradeoff",
                        "description": "Wider hardship policy.",
                        "parameters": { "candidateHardshipThreshold": 250 }
                      },
                      "requiredReplayEvidence": ["customerProfile", "timeline"]
                    }
                  ],
                  "demoComparisonTarget": {
                    "comparisonId": "hardship-relief-threshold-baseline-vs-proposed",
                    "scenarioId": "hardship-handling",
                    "title": "Hardship Relief Threshold Review",
                    "decisionQuestion": "Should wider hardship eligibility launch?",
                    "baselineBranchId": "baseline-current-hardship-policy",
                    "proposedBranchId": "proposed-wider-hardship-relief"
                  }
                }
                """);

        BankingCustomerOutcomeDecisionCatalog.DecisionComparisonDefinition definition =
                new BankingCustomerOutcomeDecisionCatalog(new ObjectMapper(), workspaceRoot)
                        .resolve(new ScenarioLabRunParameters()
                                .presetId("hardship-threshold-tradeoff"), PolicyType.HARDSHIP);

        assertEquals("hardship-relief-threshold-baseline-vs-proposed", definition.comparisonId());
        assertEquals("hardship-handling", definition.scenarioId());
        assertEquals("Hardship Relief Threshold Review", definition.title());
        assertEquals("baseline-current-hardship-policy", definition.baseline().branchId());
        assertEquals("proposed-wider-hardship-relief", definition.proposed().branchId());
        assertEquals("hardship-relief", definition.baseline().presetId());
        assertEquals("hardship-threshold-tradeoff", definition.proposed().presetId());
        assertTrue(definition.requiredEvidence().contains("timeline"));
    }

    @Test
    void fallsBackWhenManifestIsAbsent() {
        BankingCustomerOutcomeDecisionCatalog.DecisionComparisonDefinition definition =
                new BankingCustomerOutcomeDecisionCatalog(new ObjectMapper(), workspaceRoot)
                        .resolve(new ScenarioLabRunParameters()
                                .presetId("saver-repricing"), PolicyType.RATE);

        assertEquals("saver-repricing-baseline-vs-proposed", definition.comparisonId());
        assertEquals("banking_scenario_lab_saver_repricing", definition.scenarioId());
        assertEquals("baseline-current-policy", definition.baseline().branchId());
        assertEquals("proposed-candidate-policy", definition.proposed().branchId());
        assertTrue(definition.requiredEvidence().contains("stateChangeDiff"));
    }
}
