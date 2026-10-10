package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreativeAgencyTaskAuthorizationProviderTest {

    @Test
    void scopesRecordedTaskToTheCreatingPrincipal() throws Exception {
        CreativeAgencyTaskAuthorizationProvider provider = new CreativeAgencyTaskAuthorizationProvider();
        ServerCallContext owner = context("owner-a");
        ServerCallContext foreign = context("owner-b");

        assertThat(provider.checkCreate(owner, TaskOperation.MESSAGE_SEND)).isTrue();
        provider.recordOwnership(owner, "task-1", TaskOperation.MESSAGE_SEND);

        assertThat(provider.checkRead(owner, "task-1", TaskOperation.GET_TASK)).isTrue();
        assertThat(provider.checkWrite(owner, "task-1", TaskOperation.CANCEL_TASK)).isTrue();
        assertThat(provider.checkRead(foreign, "task-1", TaskOperation.GET_TASK)).isFalse();
        assertThat(provider.checkWrite(foreign, "task-1", TaskOperation.CANCEL_TASK)).isFalse();
    }

    private ServerCallContext context(String username) {
        return new ServerCallContext(
                new AuthenticatedUser(username),
                new LinkedHashMap<>(),
                Set.of(),
                "1.0");
    }
}
