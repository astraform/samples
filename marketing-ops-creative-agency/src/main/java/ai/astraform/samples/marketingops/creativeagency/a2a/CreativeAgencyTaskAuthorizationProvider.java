package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory ownership ledger matching the sample's in-memory task store.
 * A protected deployment treats the configured partner token as one principal.
 * Deterministic mode is public only when no partner token is configured.
 */
@Component
public final class CreativeAgencyTaskAuthorizationProvider implements TaskAuthorizationProvider {

    private static final String PUBLIC_DEMO_PRINCIPAL = "public-deterministic-demo";

    private final ConcurrentMap<String, String> taskOwners = new ConcurrentHashMap<>();

    @Override
    public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
        return owns(context, taskId);
    }

    @Override
    public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
        return owns(context, taskId);
    }

    @Override
    public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
        return principal(context) != null;
    }

    @Override
    public boolean isTaskRecorded(String taskId) {
        return taskId != null && taskOwners.containsKey(taskId);
    }

    @Override
    public void recordOwnership(
            ServerCallContext context,
            String taskId,
            TaskOperation operation) throws A2AError {
        String principal = principal(context);
        if (principal == null || taskId == null || taskId.isBlank()) {
            throw new TaskNotFoundError();
        }
        String existing = taskOwners.putIfAbsent(taskId, principal);
        if (existing != null && !existing.equals(principal)) {
            throw new TaskNotFoundError();
        }
    }

    private boolean owns(ServerCallContext context, String taskId) {
        String principal = principal(context);
        return principal != null && taskId != null && principal.equals(taskOwners.get(taskId));
    }

    private String principal(ServerCallContext context) {
        if (context == null) {
            return null;
        }
        User user = context.getUser();
        if (user == null) {
            return null;
        }
        if (!user.isAuthenticated()) {
            return PUBLIC_DEMO_PRINCIPAL;
        }
        String username = user.getUsername();
        return username == null || username.isBlank() ? null : "authenticated:" + username;
    }
}
