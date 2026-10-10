package platform.marketingops.remote.api;

import ai.astraform.remote.domain.conformance.RemoteDomainConformanceHarness;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceReport;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceScenario;
import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceHarness;
import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceReport;
import ai.astraform.remote.domain.conformance.PolicyWindTunnelConformanceScenario;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "astraform.workspace.root=target/marketing-ops-remote-policy-wind-tunnel-test"
})
class MarketingOpsRemotePolicyWindTunnelConformanceTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void remoteServiceConformsToPopulationCatalogContract() throws Exception {
        RemoteDomainConformanceReport report = new RemoteDomainConformanceHarness().assertConforms(
                mockMvc,
                objectMapper,
                new RemoteDomainConformanceScenario(
                        "marketing-ops",
                        "Taylor",
                        "Protocol conformance persona",
                        "agent-remote-1",
                        "runtime-remote-1",
                        "Customer",
                        "SIMULATION",
                        java.util.List.of("protocol-aware"),
                        System.currentTimeMillis() + 60_000,
                        true));

        assertThat(report.populationCatalog())
                .containsEntry("schemaVersion", "domain_population_catalog.v1")
                .containsEntry("domainId", "marketing-ops");
    }

    @Test
    void remoteServiceConformsToPolicyWindTunnelProviderContract() throws Exception {
        PolicyWindTunnelConformanceReport report = new PolicyWindTunnelConformanceHarness().assertConforms(
                mockMvc,
                objectMapper,
                new PolicyWindTunnelConformanceScenario(
                        "marketing-ops-policy-pack",
                        "launch-readiness-pressure"));

        assertThat(report.toMap())
                .containsEntry("packId", "marketing-ops-policy-pack")
                .containsEntry("bundleSchemaVersion", "policy_wind_tunnel_bundle.v1")
                .containsEntry("policyExpressionCount", 2);
    }
}
