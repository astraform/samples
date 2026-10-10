package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyGenerationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CreativeAgencyA2AAuthenticationFilterTest {

    private static final String TOKEN = "test-partner-token-with-32-characters-minimum";

    @Test
    void rejectsMissingTokenBeforeRequestBodyCanReachMvc() throws Exception {
        CreativeAgencyA2AAuthenticationFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        request.setContent("{very-large-or-invalid-body".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsValidPartnerToken() throws Exception {
        CreativeAgencyA2AAuthenticationFilter filter = filter();
        MockHttpServletRequest first = authenticatedRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(first, firstResponse, firstChain);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(firstChain.getRequest()).isNotNull();
    }

    @Test
    void deterministicModeEnforcesAnExplicitlyConfiguredPartnerToken() throws Exception {
        CreativeAgencyA2aProperties a2aProperties = new CreativeAgencyA2aProperties();
        a2aProperties.setInboundToken(TOKEN);
        CreativeAgencyGenerationProperties generationProperties =
                new CreativeAgencyGenerationProperties();
        generationProperties.setMode(CreativeAgencyGenerationProperties.Mode.DETERMINISTIC);
        CreativeAgencyA2AAuthenticationFilter filter =
                new CreativeAgencyA2AAuthenticationFilter(a2aProperties, generationProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void deterministicModeRemainsPublicWhenNoPartnerTokenIsConfigured() throws Exception {
        CreativeAgencyA2aProperties a2aProperties = new CreativeAgencyA2aProperties();
        CreativeAgencyGenerationProperties generationProperties =
                new CreativeAgencyGenerationProperties();
        generationProperties.setMode(CreativeAgencyGenerationProperties.Mode.DETERMINISTIC);
        CreativeAgencyA2AAuthenticationFilter filter =
                new CreativeAgencyA2AAuthenticationFilter(a2aProperties, generationProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private CreativeAgencyA2AAuthenticationFilter filter() {
        CreativeAgencyA2aProperties a2aProperties = new CreativeAgencyA2aProperties();
        a2aProperties.setInboundToken(TOKEN);
        CreativeAgencyGenerationProperties generationProperties = new CreativeAgencyGenerationProperties();
        generationProperties.setMode(CreativeAgencyGenerationProperties.Mode.LLM);
        return new CreativeAgencyA2AAuthenticationFilter(a2aProperties, generationProperties);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a");
        request.addHeader(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER, TOKEN);
        return request;
    }
}
