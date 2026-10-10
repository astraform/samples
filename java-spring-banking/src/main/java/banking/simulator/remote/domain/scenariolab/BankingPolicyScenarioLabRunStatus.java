package banking.simulator.remote.domain.scenariolab;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record BankingPolicyScenarioLabRunStatus(
        String runId,
        String status,
        String statusUrl,
        String phase,
        Map<String, Object> parameters,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt,
        Double progress,
        List<String> logTail,
        Map<String, Object> error,
        boolean bundleReady,
        String bundleUrl,
        String evidencePackUrl) {
}
