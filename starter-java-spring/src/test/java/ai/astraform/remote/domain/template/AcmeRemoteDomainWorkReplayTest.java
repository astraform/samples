package ai.astraform.remote.domain.template;

import static org.assertj.core.api.Assertions.assertThat;

import ai.astraform.remote.domain.openapi.models.*;
import ai.astraform.remote.domain.sdk.RemoteDomainEnvelopeBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AcmeRemoteDomainWorkReplayTest {
    private final AcmeRemoteDomainService service = new AcmeRemoteDomainService();

    @Test
    void mixedNewAndReplayWorkKeepsStableHistoryAndLastReplayEvidence() {
        List<String> history = List.of("first", "old", "old", " ");
        var request = request(history, List.of("old", "second", "second", "third", "first"), 7);

        var response = service.executeWork(request);

        assertThat(response.getNextState().getData().get("appliedWorkIds"))
                .isEqualTo(List.of("first", "old", "second", "third"));
        assertThat(response.getNextState().getData().get("completedWorkCount")).isEqualTo(9);
        assertThat(response.getResult().getToolResult()).containsEntry("idempotentReplay", true).containsEntry("workId", "first");
        assertThat(response.getResult().getEvidenceEvents().getFirst()).containsEntry("workId", "first");
        assertThat(request.getState().getData().get("appliedWorkIds")).isEqualTo(history);
        assertThat(request.getState().getData().get("completedWorkCount")).isEqualTo(7);
    }

    @Test
    void replayOnlyWorkPreservesExistingOpaqueStateExactly() {
        List<String> history = List.of("old", "old", " ");
        var request = request(history, List.of("old", "old"), 8);

        var response = service.executeWork(request);

        assertThat(response.getNextState().getData()).isEqualTo(request.getState().getData());
        assertThat(response.getResult().getToolResult()).containsEntry("idempotentReplay", true).containsEntry("workId", "old");
    }

    @Test
    void largeHistoricalStateAndNewBatchRemainAcceptedInStableOrder() {
        List<String> history = IntStream.range(0, 20_000).mapToObj(i -> "historic-" + i).toList();
        List<String> work = IntStream.range(0, 30_000).mapToObj(i -> "new-" + i).toList();
        var request = request(history, work, 20_000);

        var response = service.executeWork(request);

        var expected = new ArrayList<>(history);
        expected.addAll(work);
        assertThat(response.getNextState().getData().get("appliedWorkIds")).isEqualTo(expected);
        assertThat(response.getNextState().getData().get("completedWorkCount")).isEqualTo(50_000);
        assertThat(request.getState().getData().get("appliedWorkIds")).isEqualTo(history);
    }

    @Test
    void equalHashWorkIdsRemainDistinctAndReplayCorrectly() {
        List<String> colliding = IntStream.range(0, 2_048).mapToObj(i -> {
            StringBuilder id = new StringBuilder();
            for (int bit = 0; bit < 11; bit++) {
                id.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
            }
            return id.toString();
        }).toList();
        assertThat(colliding.stream().map(String::hashCode).distinct()).hasSize(1);
        var request = request(List.of(), colliding, 0);
        var first = service.executeWork(request);
        var replay = request(colliding, colliding, 2_048);
        var second = service.executeWork(replay);

        assertThat(first.getNextState().getData().get("appliedWorkIds")).isEqualTo(colliding);
        assertThat(first.getNextState().getData().get("completedWorkCount")).isEqualTo(2_048);
        assertThat(second.getNextState().getData()).isEqualTo(replay.getState().getData());
        assertThat(second.getResult().getToolResult()).containsEntry("workId", colliding.getLast());
    }

    private static RemoteDomainExecuteWorkRequest request(List<String> history, List<String> ids, int completed) {
        List<RemoteDomainDueWorkItem> work = ids.stream()
                .map(id -> new RemoteDomainDueWorkItem(id, "SCHEDULED_ACTION", id, 1L, 1)).toList();
        return new RemoteDomainExecuteWorkRequest()
                .protocolVersion(RemoteDomainExecuteWorkRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1)
                .requestId(UUID.randomUUID()).operation(RemoteDomainExecuteWorkRequest.OperationEnum.EXECUTE_WORK)
                .idempotencyKey("replay-test").sentAtEpochMs(1L).deadlineEpochMs(2L)
                .domain(new RemoteDomainContext("acme-remote"))
                .agent(new RemoteDomainAgentContext("agent", "Persona"))
                .state(RemoteDomainEnvelopeBuilder.opaqueState("acme.state.v1",
                        Map.of("completedWorkCount", completed, "appliedWorkIds", history, "lastUpdatedEpochMs", 0L)))
                .payload(new RemoteDomainExecuteWorkRequestAllOfPayload(new RemoteDomainTurnContext(
                        new RemoteDomainTurnContextTimeWindow(0L, 1L), work)));
    }
}
