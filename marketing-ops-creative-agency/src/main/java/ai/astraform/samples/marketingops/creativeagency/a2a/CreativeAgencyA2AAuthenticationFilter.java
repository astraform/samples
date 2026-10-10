package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyGenerationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CreativeAgencyA2AAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Astraform-Agent-Token";
    public static final String AUTHENTICATED_ATTRIBUTE =
            CreativeAgencyA2AAuthenticationFilter.class.getName() + ".authenticated";

    private final byte[] expectedToken;
    private final boolean authenticationRequired;

    public CreativeAgencyA2AAuthenticationFilter(
            CreativeAgencyA2aProperties a2aProperties,
            CreativeAgencyGenerationProperties generationProperties) {
        String configuredToken = a2aProperties.getInboundToken();
        this.expectedToken = configuredToken == null
                ? new byte[0]
                : configuredToken.getBytes(StandardCharsets.UTF_8);
        this.authenticationRequired = generationProperties.getMode()
                        == CreativeAgencyGenerationProperties.Mode.LLM
                || expectedToken.length > 0;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Protect every present and future POST route before MVC reads or deserializes
        // its body whenever this deployment configures a partner token. LLM mode is
        // always protected; deterministic mode remains public only when no token is set.
        return !authenticationRequired || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        if (presented == null || !constantTimeMatches(presented)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED");
            return;
        }
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeMatches(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, candidate);
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
