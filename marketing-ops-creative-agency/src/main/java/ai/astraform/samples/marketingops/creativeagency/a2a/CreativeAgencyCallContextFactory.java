package ai.astraform.samples.marketingops.creativeagency.a2a;

import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.extensions.A2AExtensions;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.a2aproject.sdk.server.ServerCallContext.TRANSPORT_KEY;

@Component
public class CreativeAgencyCallContextFactory {

    public ServerCallContext create(HttpServletRequest request) {
        User user = Boolean.TRUE.equals(request.getAttribute(
                CreativeAgencyA2AAuthenticationFilter.AUTHENTICATED_ATTRIBUTE))
                ? new AuthenticatedUser("astraform-a2a-client")
                : UnauthenticatedUser.INSTANCE;

        Map<String, Object> state = new LinkedHashMap<>();
        state.put(TRANSPORT_KEY, TransportProtocol.JSONRPC);
        state.put(JSONRPCContextKeys.HEADERS_KEY, safeHeaders(request));

        List<String> extensionHeaders = Collections.list(request.getHeaders(A2AHeaders.A2A_EXTENSIONS));
        Set<String> requestedExtensions = A2AExtensions.getRequestedExtensions(extensionHeaders);
        return new ServerCallContext(
                user,
                state,
                requestedExtensions,
                request.getHeader(A2AHeaders.A2A_VERSION));
    }

    private Map<String, String> safeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (A2AHeaders.A2A_VERSION.equalsIgnoreCase(name)
                    || A2AHeaders.A2A_EXTENSIONS.equalsIgnoreCase(name)
                    || "content-type".equalsIgnoreCase(name)) {
                headers.put(name, request.getHeader(name));
            }
        }
        return Map.copyOf(headers);
    }
}
