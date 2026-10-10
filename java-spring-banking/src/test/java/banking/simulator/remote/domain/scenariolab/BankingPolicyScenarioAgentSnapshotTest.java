package banking.simulator.remote.domain.scenariolab;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankingPolicyScenarioAgentSnapshotTest {

    @Test
    void adaptsNeutralCoreAgentStatusPayload() {
        BankingPolicyScenarioAgentSnapshot snapshot = BankingPolicyScenarioAgentSnapshot.from(Map.of(
                "agentId", "agent-1",
                "runtimeIdentity", "runtime-1",
                "persona", Map.of(
                        "personaName", "Nina Saver",
                        "agentType", "Customer",
                        "traits", List.of("segment:savers")),
                "statusView", Map.of(
                        "customerState", Map.of(
                                "currentBalance", 3200,
                                "creditUtilisation", 0.25,
                                "hardshipFlag", false),
                        "accounts", List.of(Map.of(
                                "name", "nina-saver",
                                "productCode", "TS001",
                                "balance", 3200,
                                "limit", 0)))));

        assertEquals("agent-1", snapshot.agentId());
        assertEquals("runtime-1", snapshot.runtimeIdentity());
        assertEquals("Nina Saver", snapshot.personaName());
        assertEquals("savers", snapshot.segment());
        assertEquals(new BigDecimal("3200"), snapshot.currentBalance());
        assertEquals(new BigDecimal("3200"), snapshot.totalProductBalance("TS001"));
        assertEquals(1, snapshot.accounts().size());
    }

    @Test
    void treatsNeutralLifecycleScheduleAsScenarioSchedule() {
        BankingPolicyScenarioAgentSnapshot snapshot = BankingPolicyScenarioAgentSnapshot.from(Map.of(
                "agentId", "agent-1",
                "persona", Map.of(
                        "personaName", "Nina Saver",
                        "traits", List.of("segment:savers")),
                "lifecycleSchedule", List.of(Map.of(
                        "description", "Salary payment",
                        "type", "CREDIT",
                        "frequency", "MONTHLY"))));

        assertEquals(1, ((List<?>) snapshot.rawPayload().get("schedule")).size());
    }
}
