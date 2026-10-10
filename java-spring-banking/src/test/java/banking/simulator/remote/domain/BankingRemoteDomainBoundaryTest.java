package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BankingRemoteDomainBoundaryTest {
    @Test
    void pom_keepsHostAndInProcessBankingAdapterOut() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).doesNotContain("<artifactId>agent-runtime</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>agent-runtime-banking</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>simulation-adapter-banking-runtime</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>simulation-runtime-spi</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>simulation-kernel</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>platform-experiment-api</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>runtime-host-core</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>platform-contracts</artifactId>");
    }
}
