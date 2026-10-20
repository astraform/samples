package ai.astraform.remote.domain.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PopulationCatalogPreflightTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesStarterCatalogWithReadableSummary() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = PopulationCatalogPreflight.run(
                new String[] {"src/main/resources/population-catalogs/acme-remote.population-catalog.json"},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("OK: acme-remote population catalog acme-remote.population-catalog.v1")
                .contains("1 archetype, 1 recipe");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void reportsInvalidCatalogWithActionableField() throws Exception {
        Path badCatalog = tempDir.resolve("bad-population-catalog.json");
        Files.writeString(badCatalog, """
                {
                  "schemaVersion": "domain_population_catalog.v1",
                  "domainId": "acme-remote"
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = PopulationCatalogPreflight.run(
                new String[] {badCatalog.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(exitCode).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("Invalid population catalog:")
                .contains("catalog.domainFamily")
                .contains("Fix the field named in the error")
                .doesNotContain("Invalid population catalog: Invalid population catalog:");
    }
}
