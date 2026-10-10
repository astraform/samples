package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BankingPolicyWindTunnelRevisionFilterTest {
    private final BankingPolicyWindTunnelRevisionFilter filter =
            new BankingPolicyWindTunnelRevisionFilter();

    @Test
    void provesRevisionForInitialPackMetadataRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/policy-wind-tunnel/pack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeaders("X-Astraform-Provider-Revision"))
                .containsExactly(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
    }

    @Test
    void rejectsLifecycleRequestsWithoutPinnedProviderRevision() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/policy-wind-tunnel/runs/run-1/lifecycle");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(412);
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsExactPinnedProviderRevision() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/policy-wind-tunnel/runs/run-1");
        request.addHeader(
                BankingPolicyWindTunnelRevisionFilter.EXPECTED_PROVIDER_REVISION_HEADER,
                BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader("X-Astraform-Provider-Revision"))
                .isEqualTo(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
    }

    @Test
    void provesRevisionForPinnedPresetCatalogRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/policy-wind-tunnel/presets");
        request.addHeader(
                BankingPolicyWindTunnelRevisionFilter.EXPECTED_PROVIDER_REVISION_HEADER,
                BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader("X-Astraform-Provider-Revision"))
                .isEqualTo(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
    }

    @Test
    void rejectsMismatchedPinnedPresetCatalogRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/policy-wind-tunnel/presets");
        request.addHeader(
                BankingPolicyWindTunnelRevisionFilter.EXPECTED_PROVIDER_REVISION_HEADER,
                "build-sha256:" + "0".repeat(64));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(412);
        verifyNoInteractions(chain);
        assertThat(response.getHeader("X-Astraform-Provider-Revision"))
                .isEqualTo(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION);
    }

    @Test
    void nativeManifestAndOperationUseTheirOwnPinnedRevision() throws Exception {
        var manifest = new MockHttpServletRequest("GET", "/api/remote-domain/v1/manifest");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(manifest, response, chain);
        verify(chain).doFilter(manifest, response);
        assertThat(response.getHeader("X-Astraform-Provider-Revision"))
                .isEqualTo(BankingRemoteDomainService.NATIVE_PROVIDER_REVISION);
        var changed = new MockHttpServletRequest("POST", "/api/remote-domain/v1/prepare");
        changed.addHeader(BankingPolicyWindTunnelRevisionFilter.EXPECTED_PROVIDER_REVISION_HEADER, "different-revision");
        var rejected = new MockHttpServletResponse();
        FilterChain blocked = mock(FilterChain.class);
        filter.doFilter(changed, rejected, blocked);
        assertThat(rejected.getStatus()).isEqualTo(412);
        verifyNoInteractions(blocked);
    }

    @Test
    void protectsRunScopedEvidenceRoutes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/policy-wind-tunnel/runs/run-1/evidence-pack");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(412);
        verifyNoInteractions(chain);
        assertThat(BankingRemotePolicyWindTunnelService.PROVIDER_REVISION)
                .matches("^(build|oci)-sha256:[0-9a-f]{64}$");
    }
}
