package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelResolvedRequest;

import java.util.Map;

public record BankingPolicyScenarioLabRequest(
        BankingPolicyScenarioLabPresetService.PresetDefinition preset,
        ScenarioLabRunParameters parameters,
        boolean platformAdmin,
        Map<String, Object> normalizedPayload) implements PolicyWindTunnelResolvedRequest {
}
