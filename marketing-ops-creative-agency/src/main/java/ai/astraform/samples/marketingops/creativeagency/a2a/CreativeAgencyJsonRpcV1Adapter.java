package ai.astraform.samples.marketingops.creativeagency.a2a;

import com.google.gson.JsonSyntaxException;
import com.google.protobuf.Empty;
import com.google.protobuf.MessageOrBuilder;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.NonStreamingJSONRPCRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.server.version.A2AVersionValidator;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.stereotype.Component;

/**
 * Spring MVC bridge for the official A2A protocol-1.0 JSON-RPC codec and handler.
 * The SDK currently ships no Spring HTTP adapter, so this class mirrors its reference route.
 */
@Component
public class CreativeAgencyJsonRpcV1Adapter {

    private final AgentCard agentCard;
    private final JSONRPCHandler handler;
    private final CreativeAgencyA2AAdmissionGate admissionGate;
    private final TaskStore taskStore;
    private final TaskAuthorizationProvider taskAuthorizationProvider;

    public CreativeAgencyJsonRpcV1Adapter(
            AgentCard agentCard,
            JSONRPCHandler handler,
            CreativeAgencyA2AAdmissionGate admissionGate,
            TaskStore taskStore,
            TaskAuthorizationProvider taskAuthorizationProvider) {
        this.agentCard = agentCard;
        this.handler = handler;
        this.admissionGate = admissionGate;
        this.taskStore = taskStore;
        this.taskAuthorizationProvider = taskAuthorizationProvider;
    }

    public String invoke(String rawBody, ServerCallContext context) {
        if (rawBody == null || rawBody.isBlank()) {
            return parseError("JSON-RPC request body is required");
        }
        A2ARequest<?> request = null;
        A2AResponse<?> response;
        CreativeAgencyA2AAdmissionGate.Lease admissionLease = null;
        try {
            request = JSONRPCUtils.parseRequestBody(rawBody, null);
            A2AVersionValidator.validateProtocolVersion(agentCard, context);
            context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, request.getMethod());
            if (request instanceof SendMessageRequest) {
                admissionLease = admissionGate.tryAcquire();
                if (admissionLease == null) {
                    response = new A2AErrorResponse(
                            request.getId(),
                            new InternalError("A2A request capacity exhausted"));
                } else {
                    context.getState().put(CreativeAgencyA2AAdmissionGate.CONTEXT_KEY, admissionLease);
                    response = dispatch((NonStreamingJSONRPCRequest<?>) request, context);
                }
            } else {
                response = request instanceof NonStreamingJSONRPCRequest<?> nonStreaming
                        ? dispatch(nonStreaming, context)
                        : new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
            }
        } catch (InvalidParamsJsonMappingException failure) {
            response = new A2AErrorResponse(
                    failure.getId(),
                    new InvalidParamsError(null, failure.getMessage(), null));
        } catch (MethodNotFoundJsonMappingException failure) {
            response = new A2AErrorResponse(
                    failure.getId(),
                    new MethodNotFoundError(null, failure.getMessage(), null));
        } catch (IdJsonMappingException failure) {
            response = new A2AErrorResponse(
                    failure.getId(),
                    new InvalidRequestError(null, failure.getMessage(), null));
        } catch (JsonMappingException failure) {
            response = new A2AErrorResponse(
                    new InvalidRequestError(null, failure.getMessage(), null));
        } catch (JsonSyntaxException | JsonProcessingException failure) {
            response = new A2AErrorResponse(new JSONParseError(failure.getMessage()));
        } catch (A2AError failure) {
            response = request == null
                    ? new A2AErrorResponse(failure)
                    : new A2AErrorResponse(request.getId(), failure);
        } catch (RuntimeException failure) {
            InternalError internal = new InternalError("A2A request failed");
            response = request == null
                    ? new A2AErrorResponse(internal)
                    : new A2AErrorResponse(request.getId(), internal);
        }
        if (admissionLease != null && response.getError() != null) {
            admissionLease.cancelBeforeExecution();
        }
        return serialize(response);
    }

    String parseError(String message) {
        return JSONRPCUtils.toJsonRPCErrorResponse(null, new JSONParseError(message));
    }

    private A2AResponse<?> dispatch(
            NonStreamingJSONRPCRequest<?> request,
            ServerCallContext context) {
        if (request instanceof GetTaskRequest typed) {
            return handler.onGetTask(typed, context);
        }
        if (request instanceof CancelTaskRequest typed) {
            String taskId = typed.getParams().id();
            if (taskStore.get(taskId) == null
                    || !taskAuthorizationProvider.checkWrite(
                            context,
                            taskId,
                            TaskOperation.CANCEL_TASK)) {
                return new A2AErrorResponse(typed.getId(), new TaskNotFoundError());
            }
            return new A2AErrorResponse(typed.getId(), new TaskNotCancelableError());
        }
        if (request instanceof ListTasksRequest typed) {
            return handler.onListTasks(typed, context);
        }
        if (request instanceof CreateTaskPushNotificationConfigRequest typed) {
            return handler.setPushNotificationConfig(typed, context);
        }
        if (request instanceof GetTaskPushNotificationConfigRequest typed) {
            return handler.getPushNotificationConfig(typed, context);
        }
        if (request instanceof SendMessageRequest typed) {
            return handler.onMessageSend(typed, context);
        }
        if (request instanceof ListTaskPushNotificationConfigsRequest typed) {
            return handler.listPushNotificationConfigs(typed, context);
        }
        if (request instanceof DeleteTaskPushNotificationConfigRequest typed) {
            return handler.deletePushNotificationConfig(typed, context);
        }
        if (request instanceof GetExtendedAgentCardRequest typed) {
            return handler.onGetExtendedCardRequest(typed, context);
        }
        return new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
    }

    private String serialize(A2AResponse<?> response) {
        if (response instanceof A2AErrorResponse error) {
            return JSONRPCUtils.toJsonRPCErrorResponse(error.getId(), error.getError());
        }
        if (response.getError() != null) {
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), toProto(response));
    }

    private MessageOrBuilder toProto(A2AResponse<?> response) {
        if (response instanceof GetTaskResponse typed) {
            return ProtoUtils.ToProto.task(typed.getResult());
        }
        if (response instanceof CancelTaskResponse typed) {
            return ProtoUtils.ToProto.task(typed.getResult());
        }
        if (response instanceof SendMessageResponse typed) {
            return ProtoUtils.ToProto.taskOrMessage(typed.getResult());
        }
        if (response instanceof ListTasksResponse typed) {
            return ProtoUtils.ToProto.listTasksResult(typed.getResult());
        }
        if (response instanceof CreateTaskPushNotificationConfigResponse typed) {
            return ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(typed.getResult());
        }
        if (response instanceof GetTaskPushNotificationConfigResponse typed) {
            return ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(typed.getResult());
        }
        if (response instanceof ListTaskPushNotificationConfigsResponse typed) {
            return ProtoUtils.ToProto.listTaskPushNotificationConfigsResponse(typed.getResult());
        }
        if (response instanceof DeleteTaskPushNotificationConfigResponse) {
            return Empty.getDefaultInstance();
        }
        if (response instanceof GetExtendedAgentCardResponse typed) {
            return ProtoUtils.ToProto.getExtendedCardResponse(typed.getResult());
        }
        throw new IllegalArgumentException("Unsupported A2A response type: " + response.getClass().getName());
    }
}
