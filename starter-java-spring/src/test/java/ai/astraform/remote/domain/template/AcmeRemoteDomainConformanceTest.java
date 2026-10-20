package ai.astraform.remote.domain.template;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceHarness;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceScenario;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceReport;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AcmeRemoteDomainConformanceTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void templateServiceConformsToRemoteDomainV1() throws Exception {
        RemoteDomainConformanceReport report = new RemoteDomainConformanceHarness()
                .assertConforms(
                        mockMvc,
                        objectMapper,
                        RemoteDomainConformanceScenario.forDomain("acme-remote").withPopulationCatalog());

        assertThat(report.mediaType()).isEqualTo(RemoteDomainProtocol.MEDIA_TYPE);
        assertThat(report.manifest().getDomain().getDomainId()).isEqualTo("acme-remote");
        assertThat(report.populationCatalog()).isNotNull();
        assertThat(report.populationCatalog().get("schemaVersion")).isEqualTo("domain_population_catalog.v1");
        assertThat(report.populationCatalog().get("domainId")).isEqualTo("acme-remote");
        assertThat(report.prepareResponse().getNextState().getSchemaVersion()).isEqualTo("acme.state.v1");
        assertThat(report.executeWorkResponse().getResult().getRuntimeIdentity()).isNotBlank();
        assertThat(report.executeWorkReplayResponse()).isNull();
    }
}
