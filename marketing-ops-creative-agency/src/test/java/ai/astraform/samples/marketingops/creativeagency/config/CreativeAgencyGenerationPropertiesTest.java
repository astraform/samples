package ai.astraform.samples.marketingops.creativeagency.config;

import org.junit.jupiter.api.Test;

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
    void noneAuthAcceptsLocalEndpointWithoutKey() {
        CreativeAgencyGenerationProperties properties = validLlmProperties();
        properties.getLlm().setBaseUrl("http://localhost:11434/v1");
        properties.getLlm().setAuthType(CreativeAgencyGenerationProperties.AuthType.NONE);
        properties.getLlm().setApiKey("");
        properties.getLlm().setProviderId("ollama");
        properties.getLlm().setModel("qwen3:8b");

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
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
