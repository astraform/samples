package ai.astraform.samples.marketingops.creativeagency.config;

import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyResponseGenerator;
import ai.astraform.samples.marketingops.creativeagency.service.DeterministicCreativeAgencyResponseGenerator;
import ai.astraform.samples.marketingops.creativeagency.service.OpenAiCompatibleCreativeAgencyResponseGenerator;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CreativeAgencyResponseGenerationConfiguration {

    @Bean
    public CreativeAgencyResponseGenerator creativeAgencyResponseGenerator(
            CreativeAgencyGenerationProperties generationProperties,
            CreativeAgencyA2aProperties a2aProperties,
            ObjectMapper objectMapper) {
        if (generationProperties.getMode() == CreativeAgencyGenerationProperties.Mode.LLM) {
            validateInboundToken(a2aProperties);
            validateProviderBudget(generationProperties.getLlm(), a2aProperties);
        }
        return switch (generationProperties.getMode()) {
            case DETERMINISTIC -> new DeterministicCreativeAgencyResponseGenerator();
            case LLM -> new OpenAiCompatibleCreativeAgencyResponseGenerator(
                    generationProperties,
                    objectMapper,
                    a2aProperties.getVersion());
        };
    }

    private void validateInboundToken(CreativeAgencyA2aProperties properties) {
        if (properties.getInboundToken() == null || properties.getInboundToken().isBlank()) {
            throw new IllegalArgumentException("CREATIVE_AGENCY_A2A_TOKEN is required in LLM mode");
        }
    }

    private void validateProviderBudget(
            CreativeAgencyGenerationProperties.Llm llm,
            CreativeAgencyA2aProperties a2a) {
        long perAttemptMillis = llm.getConnectTimeout().plus(llm.getReadTimeout()).toMillis();
        long retryMillis = 0;
        for (int attempt = 1; attempt < llm.getMaxAttempts(); attempt++) {
            retryMillis += Math.min(2_000L, llm.getRetryBackoff().toMillis() * (1L << (attempt - 1)));
        }
        long providerBudgetMillis = Math.addExact(
                Math.multiplyExact(perAttemptMillis, llm.getMaxAttempts()),
                retryMillis);
        long a2aBudgetMillis = a2a.getBlockingAgentTimeoutSeconds() * 1_000L;
        if (providerBudgetMillis + 2_000L >= a2aBudgetMillis) {
            throw new IllegalArgumentException(
                    "LLM timeout/retry budget must fit inside the A2A blocking agent timeout");
        }
    }
}
