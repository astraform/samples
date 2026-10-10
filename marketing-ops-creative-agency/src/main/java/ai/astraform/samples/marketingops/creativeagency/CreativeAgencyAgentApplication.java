package ai.astraform.samples.marketingops.creativeagency;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyGenerationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({CreativeAgencyA2aProperties.class, CreativeAgencyGenerationProperties.class})
public class CreativeAgencyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreativeAgencyAgentApplication.class, args);
    }
}
