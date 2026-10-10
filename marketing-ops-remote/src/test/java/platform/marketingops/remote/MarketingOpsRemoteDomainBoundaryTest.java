package platform.marketingops.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MarketingOpsRemoteDomainBoundaryTest {
    @Test
    void pom_staysOnPublicRemoteDomainAuthorKitOnly() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).doesNotContain("<artifactId>simulation-runtime-spi</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>simulation-kernel</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>platform-experiment-api</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>runtime-host-core</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>platform-contracts</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>openapi-generator-maven-plugin</artifactId>");
    }
}
