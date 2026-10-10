package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceHarness;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceScenario;
import ai.astraform.remote.domain.conformance.RemoteDomainConformanceReport;
import ai.astraform.remote.domain.conformance.RemoteDomainOpportunityWorkerProfile;
import ai.astraform.remote.domain.conformance.RemoteDomainToolEffectType;
import ai.astraform.remote.domain.conformance.RemoteDomainToolProbe;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocol;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BankingRemoteDomainConformanceTest {
    private static final String PROVIDER_ARTIFACT_IDENTITY = "sha256:" + "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bankingServiceConformsToRemoteDomainV1() throws Exception {
        RemoteDomainConformanceReport report = new RemoteDomainConformanceHarness()
                .assertOpportunityWorkerConforms(
                        mockMvc,
                        objectMapper,
                        RemoteDomainConformanceScenario.forDomain("banking").withPopulationCatalog(),
                        new RemoteDomainOpportunityWorkerProfile(
                                Map.of(
                                        "get_product_list", RemoteDomainToolEffectType.READ_ONLY,
                                        "post_double_entry_transaction", RemoteDomainToolEffectType.MUTATING),
                                List.of(
                                        RemoteDomainToolProbe.of("get_product_list", Map.of()),
                                        RemoteDomainToolProbe.of(
                                                "post_double_entry_transaction",
                                                Map.of(
                                                        "debits", List.of(Map.of(
                                                                "accountName", "Main Account",
                                                                "amount", 25)),
                                                        "credits", List.of(Map.of(
                                                                "accountName", "Main Account",
                                                                "amount", 25))))),
                                Map.of("initialAccounts", List.of(Map.of(
                                        "accountId", "conformance-main",
                                        "name", "Main Account",
                                        "productCode", "TS001",
                                        "productCategory", "TRANS_AND_SAVINGS_ACCOUNTS",
                                        "balance", 1000.0,
                                        "currency", "AUD")))),
                        PROVIDER_ARTIFACT_IDENTITY);

        assertThat(report.mediaType()).isEqualTo(RemoteDomainProtocol.MEDIA_TYPE);
        assertThat(report.manifest().getDomain().getDomainId()).isEqualTo("banking");
        assertThat(report.opportunityWorkerConformanceReport().providerArtifactDigest())
                .isEqualTo(PROVIDER_ARTIFACT_IDENTITY);
        assertThat(report.populationCatalog())
                .containsEntry("schemaVersion", "domain_population_catalog.v1")
                .containsEntry("domainId", "banking");
        assertThat(report.populationCatalog().get("runPathEligibility"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "SCENARIO_READY")
                .containsEntry("windTunnelConsumesPopulation", true);
        assertThat(report.populationCatalog().get("populationRecipes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("recipes")
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("recipeId", "banking-policy-100");
        Map<?, ?> populationRecipes = (Map<?, ?>) report.populationCatalog().get("populationRecipes");
        Map<?, ?> recipe = (Map<?, ?>) ((List<?>) populationRecipes.get("recipes")).getFirst();
        Map<?, ?> memberValidation = (Map<?, ?>) recipe.get("memberValidation");
        assertThat(memberValidation.get("initialAccountsRequired")).isEqualTo(true);
        assertThat(memberValidation.get("scheduledWorkRequired")).isEqualTo(false);
        assertThat((List<?>) recipe.get("segmentMix")).allSatisfy(rawSegment -> {
            Map<?, ?> segment = (Map<?, ?>) rawSegment;
            Map<?, ?> memberTemplate = (Map<?, ?>) segment.get("memberTemplate");
            assertThat((List<?>) memberTemplate.get("initialAccounts")).hasSize(1);
            assertThat((List<?>) memberTemplate.get("scheduledWork")).isEmpty();
        });
        Map<String, Map<?, ?>> segmentsById = ((List<?>) recipe.get("segmentMix")).stream()
                .map(Map.class::cast)
                .collect(java.util.stream.Collectors.toMap(
                        segment -> String.valueOf(segment.get("segmentId")),
                        segment -> segment));
        assertCatalogAccount(segmentsById, "deposit-savers", "TS001", "2500");
        assertCatalogAccount(segmentsById, "credit-cardholders", "CC001", "-1200");
        assertCatalogAccount(segmentsById, "hardship-risk", "TS001", "250");
        assertCatalogAccount(segmentsById, "fraud-friction", "CC001", "-600");
        assertThat(report.prepareResponse().getNextState().getSchemaVersion())
                .isEqualTo(BankingRemoteDomainState.SCHEMA_VERSION);
        assertThat(report.executeWorkResponse().getNextState().getData())
                .containsEntry("executedWorkCount", BigInteger.ZERO);
        assertThat(report.executeWorkResponse().getNextState().getData().get("executionLog"))
                .asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "RECORDED");
        assertThat(report.controlActionResponse()).isNotNull();
        assertThat(report.controlActionResponse().getResult().getControlAction())
                .containsEntry("actionId", "broadcast-message");
    }

    private static void assertCatalogAccount(
            Map<String, Map<?, ?>> segmentsById,
            String segmentId,
            String productCode,
            String balance) {
        Map<?, ?> segment = segmentsById.get(segmentId);
        assertThat(segment).as("catalog segment %s", segmentId).isNotNull();
        Map<?, ?> template = (Map<?, ?>) segment.get("memberTemplate");
        Map<?, ?> account = (Map<?, ?>) ((List<?>) template.get("initialAccounts")).getFirst();
        assertThat(account.get("productCode")).isEqualTo(productCode);
        assertThat(new BigDecimal(String.valueOf(account.get("balance"))))
                .isEqualByComparingTo(new BigDecimal(balance));
    }
}
