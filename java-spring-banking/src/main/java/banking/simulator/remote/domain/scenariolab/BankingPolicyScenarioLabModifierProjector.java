package banking.simulator.remote.domain.scenariolab;

import java.util.List;
import java.util.Map;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

final class BankingPolicyScenarioLabModifierProjector {

    ScenarioModifierSummary project(List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> eventResults) {
        List<String> eventNames = eventResults.stream()
                .map(BankingPolicyScenarioExecutionRuntime.ScenarioEventResult::name)
                .toList();
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult rateEvent = findEvent(eventResults, "rate");
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult limitEvent = findEvent(eventResults, "limit");
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult hardshipEvent = findEvent(eventResults, "hardship");
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult fraudEvent = findEvent(eventResults, "fraud");
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult stimulusEvent = findEvent(eventResults, "stimulus");
        BankingPolicyScenarioExecutionRuntime.ScenarioEventResult crashEvent = findEvent(eventResults, "crash");

        return new ScenarioModifierSummary(
                eventResults.size(),
                eventNames,
                0,
                List.of(),
                toPolicyLeverEventSummary(rateEvent),
                toPolicyLeverEventSummary(limitEvent),
                stimulusEvent == null ? 0 : 1,
                stimulusEvent == null ? 0 : toDouble(stimulusEvent.responsePayload().get("amount"), 0.0),
                hardshipEvent != null,
                hardshipEvent == null ? 0 : 1,
                fraudEvent != null,
                fraudEvent == null ? 0 : 1,
                crashEvent != null,
                crashEvent == null ? 0 : 1,
                List.of(),
                toHardshipScanSummary(hardshipEvent));
    }

    private BankingPolicyScenarioExecutionRuntime.ScenarioEventResult findEvent(
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> eventResults,
            String token) {
        return eventResults.stream()
                .filter(result -> result.name().contains(token))
                .findFirst()
                .orElse(null);
    }

    private PolicyLeverEventSummary toPolicyLeverEventSummary(
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult eventResult) {
        if (eventResult == null) {
            return null;
        }
        Map<String, Object> payload = objectMap(eventResult.responsePayload());
        return new PolicyLeverEventSummary(
                stringValue(payload.get("message")),
                stringValue(payload.get("productCode")),
                intValue(payload.get("affectedAgents"), 0));
    }

    private HardshipScanSummary toHardshipScanSummary(
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult hardshipEvent) {
        if (hardshipEvent == null) {
            return new HardshipScanSummary(0, 0, 0, 0, null, null, List.of(), 0, 0);
        }
        Map<String, Object> payload = objectMap(hardshipEvent.responsePayload());
        return new HardshipScanSummary(
                intValue(payload.get("policyAgentsScanned"), 0),
                intValue(payload.get("analysedAgents"), 0),
                intValue(payload.get("hardshipDetections"), 0),
                intValue(payload.get("subsidiesIssued"), 0),
                payload.get("hardshipThreshold") == null ? null : toDouble(payload.get("hardshipThreshold"), 0.0),
                payload.get("subsidyAmount") == null ? null : toDouble(payload.get("subsidyAmount"), 0.0),
                stringList(payload.get("reliefRecipients")),
                intValue(payload.get("skippedCooldownCount"), 0),
                intValue(payload.get("insufficientFundCount"), 0));
    }
}
