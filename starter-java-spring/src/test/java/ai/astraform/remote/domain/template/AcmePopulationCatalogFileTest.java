package ai.astraform.remote.domain.template;

import static org.assertj.core.api.Assertions.assertThat;

import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocol;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcmePopulationCatalogFileTest {
    @Test
    void populationCatalogFileIsValidBeforeSpringStarts() {
        Map<String, Object> catalog = PopulationCatalogs.fromPath(Path.of(
                "src/main/resources/population-catalogs/acme-remote.population-catalog.json"));

        assertThat(catalog)
                .containsEntry("schemaVersion", RemoteDomainProtocol.POPULATION_CATALOG_SCHEMA_VERSION)
                .containsEntry("domainId", "acme-remote");
    }
}
