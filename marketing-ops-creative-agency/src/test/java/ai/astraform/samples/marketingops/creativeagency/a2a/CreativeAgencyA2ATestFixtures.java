package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendConfiguration;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TextPart;

import java.util.Map;

final class CreativeAgencyA2ATestFixtures {

    static final String PROTOCOL_VERSION = "1.0";

    private CreativeAgencyA2ATestFixtures() {
    }

    static String sendMessagePayload(
            String requestId,
            String contextId,
            String text,
            Map<String, Object> metadata) {
        return sendMessagePayload(requestId, contextId, text, metadata, false);
    }

    static String sendMessagePayload(
            String requestId,
            String contextId,
            String text,
            Map<String, Object> metadata,
            boolean returnImmediately) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId("msg-" + requestId)
                .contextId(contextId)
                .parts(new TextPart(text))
                .build();
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .configuration(MessageSendConfiguration.builder()
                        .returnImmediately(returnImmediately)
                        .build())
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
        return JSONRPCUtils.toJsonRPCRequest(
                requestId,
                "SendMessage",
                ProtoUtils.ToProto.sendMessageRequest(params));
    }

    static String taskOperationPayload(String method, String requestId, String taskId) {
        return switch (method) {
            case "GetTask" -> JSONRPCUtils.toJsonRPCRequest(
                    requestId,
                    method,
                    ProtoUtils.ToProto.getTaskRequest(new TaskQueryParams(taskId)));
            case "ListTasks" -> JSONRPCUtils.toJsonRPCRequest(
                    requestId,
                    method,
                    ProtoUtils.ToProto.listTasksParams(new ListTasksParams(
                            null,
                            null,
                            10,
                            null,
                            0,
                            null,
                            false,
                            "test-tenant")));
            case "CancelTask" -> JSONRPCUtils.toJsonRPCRequest(
                    requestId,
                    method,
                    ProtoUtils.ToProto.cancelTaskRequest(new CancelTaskParams(taskId)));
            default -> throw new IllegalArgumentException("Unsupported test method: " + method);
        };
    }
}
