package ai.astraform.samples.marketingops.creativeagency.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativeAgencyGenerationPropertiesTest {

    @Test
    void defaultLlmModeNeedsConfigurationAndDeterministicFixtureRequiresExplicitSelection() {
        CreativeAgencyGenerationProperties properties = new CreativeAgencyGenerationProperties();
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_PROVIDER_ID");
        properties.setMode(CreativeAgencyGenerationProperties.Mode.DETERMINISTIC);
        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void bearerLlmModeFailsClosedWithoutKeyAndBindingRevision() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setApiKey("");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_API_KEY");

        properties.getLlm().setApiKey("test-api-key");
        properties.getLlm().setBindingRevision("");
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_BINDING_REVISION");
    }

    @Test
    void localThinkingProfileFitsInsideItsBoundedA2aDeadline() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setBaseUrl("http://localhost:11434/v1");
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);
        properties.getLlm().setApiKey("");
        properties.getLlm().setProviderId("ollama");
        properties.getLlm().setModel("qwen3.5:4b");
        properties.getLlm().setReasoningEffort("high");
        properties.getLlm().setReadTimeout(Duration.ofSeconds(840));
        properties.getLlm().setMaxOutputTokens(32_768);
        properties.getLlm().setMaxResponseBytes(1_048_576);
        CreativeAgencyA2aProperties a2a = new CreativeAgencyA2aProperties();
        a2a.setInboundToken("local-test-inbound-token-with-32-characters");
        a2a.setBlockingAgentTimeoutSeconds(900);

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(a2a::afterPropertiesSet).doesNotThrowAnyException();
        CreativeAgencyResponseGenerationConfiguration configuration = new CreativeAgencyResponseGenerationConfiguration();
        assertThatCode(() -> configuration.creativeAgencyResponseGenerator(properties, a2a, new ObjectMapper()))
                .doesNotThrowAnyException();
        properties.getLlm().setMaxAttempts(2);
        assertThatThrownBy(() -> configuration.creativeAgencyResponseGenerator(properties, a2a, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout/retry budget must fit");
    }

    @Test
    void noneAuthRejectsSilentlyUnusedKey() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be empty");
    }

    @Test
    void nonLoopbackHttpRequiresExplicitNoAuthOptIn() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setBaseUrl("http://ollama:11434/v1");
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);
        properties.getLlm().setApiKey("");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALLOW_INSECURE_HTTP");

        properties.getLlm().setAllowInsecureHttp(true);
        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void acceptsRepresentativeEndpointProfilesWithoutInferringProviderBehavior() {
        CreativeAgencyGenerationProperties openAi = validLlmProperties();
        openAi.getLlm().setBaseUrl("https://api.openai.com/v1");
        openAi.getLlm().setTokenLimitParameter(
                CreativeAgencyGenerationProperties.TokenLimitParameter.MAX_COMPLETION_TOKENS);

        CreativeAgencyGenerationProperties google = validLlmProperties();
        google.getLlm().setProviderId("google-gemini");
        google.getLlm().setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai");
        google.getLlm().setModel("gemini-3.6-flash");
        google.getLlm().setTokenLimitParameter(
                CreativeAgencyGenerationProperties.TokenLimitParameter.OMIT);

        CreativeAgencyGenerationProperties vllm = validLlmProperties();
        vllm.getLlm().setProviderId("vllm");
        vllm.getLlm().setBaseUrl("http://localhost:8000/v1");
        vllm.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);
        vllm.getLlm().setApiKey("");

        assertThatCode(openAi::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(google::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(vllm::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsCredentialsEmbeddedInBaseUrl() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setBaseUrl("https://secret@example.com/v1");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsSamplingAndBudgetsBeyondFiniteProviderAndAgentBounds() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        for (double invalid : new double[]{Double.NaN, 2.1}) {
            properties.getLlm().setTemperature(invalid);
            assertThatThrownBy(properties::afterPropertiesSet)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CREATIVE_AGENCY_LLM_TEMPERATURE");
        }
        properties.getLlm().setTemperature(0.6);
        for (double invalid : new double[]{Double.POSITIVE_INFINITY, 1.1}) {
            properties.getLlm().setTopP(invalid);
            assertThatThrownBy(properties::afterPropertiesSet)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CREATIVE_AGENCY_LLM_TOP_P");
        }
        properties.getLlm().setTopP(0.95);
        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
        properties.getLlm().setReadTimeout(Duration.ofSeconds(901));
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_READ_TIMEOUT");
        properties.getLlm().setReadTimeout(Duration.ofSeconds(900));
        properties.getLlm().setMaxOutputTokens(32_769);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS");
        properties.getLlm().setMaxOutputTokens(32_768);
        properties.getLlm().setMaxResponseBytes(1_048_577);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATIVE_AGENCY_LLM_MAX_RESPONSE_BYTES");
        CreativeAgencyA2aProperties a2a = new CreativeAgencyA2aProperties();
        a2a.setBlockingAgentTimeoutSeconds(901);
        assertThatThrownBy(a2a::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocking-agent-timeout-seconds");
    }

    private CreativeAgencyGenerationProperties validLlmProperties() {
        CreativeAgencyGenerationProperties properties = new CreativeAgencyGenerationProperties();
        properties.setMode(CreativeAgencyGenerationProperties.Mode.LLM);
        properties.getLlm().setProviderId("openai");
        properties.getLlm().setBaseUrl("https://api.openai.com/v1");
        properties.getLlm().setApiKey("test-api-key");
        properties.getLlm().setModel("gpt-4o");
        properties.getLlm().setBindingRevision("test-binding-v1");
        return properties;
    }
}
