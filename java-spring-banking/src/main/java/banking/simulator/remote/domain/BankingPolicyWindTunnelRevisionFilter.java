package banking.simulator.remote.domain;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BankingPolicyWindTunnelRevisionFilter extends OncePerRequestFilter {
    static final String EXPECTED_PROVIDER_REVISION_HEADER = "X-Astraform-Expected-Provider-Revision";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String expectedRevision = request.getHeader(EXPECTED_PROVIDER_REVISION_HEADER);
        if (request.getRequestURI().startsWith("/api/remote-domain/v1/")) {
            if (request.getRequestURI().endsWith("/manifest") || expectedRevision != null) {
                response.setHeader("X-Astraform-Provider-Revision", BankingRemoteDomainService.NATIVE_PROVIDER_REVISION);
                if (expectedRevision != null
                        && !BankingRemoteDomainService.NATIVE_PROVIDER_REVISION.equals(expectedRevision)) {
                    response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED,
                            "Remote-domain provider revision does not match the pinned native contract");
                    return;
                }
            }
            filterChain.doFilter(request, response);
            return;
        }
        boolean pinnedRevisionRequired = requiresPinnedRevision(request);
        if (isPackMetadataRequest(request)
                || pinnedRevisionRequired
                || expectedRevision != null) {
            response.setHeader(
                    "X-Astraform-Provider-Revision",
                    BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
            if ((pinnedRevisionRequired || expectedRevision != null)
                    && !BankingRemotePolicyWindTunnelService.PROVIDER_REVISION.equals(
                            expectedRevision)) {
                response.sendError(
                        HttpServletResponse.SC_PRECONDITION_FAILED,
                        "Policy Wind Tunnel provider revision does not match the pinned run contract");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPackMetadataRequest(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().endsWith("/policy-wind-tunnel/pack");
    }

    private static boolean requiresPinnedRevision(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equals(method) && path.endsWith("/policy-wind-tunnel/runs")) {
            return true;
        }
        return path.contains("/policy-wind-tunnel/runs/");
    }
}
