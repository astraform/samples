package ai.astraform.samples.marketingops.creativeagency.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marketing-ops-creative-agency.a2a")
public class CreativeAgencyA2aProperties implements InitializingBean {

    private String name = "Creative Agency Agent";
    private String version = "0.2.0";
    private String description =
            "External creative agency for rework, review, and launch-blocker coordination; LLM-driven advice; deterministic mode is an explicit fixture.";
    private String baseUrl = "http://localhost:8091";
    private String documentationUrl = "https://astraform.ai/developers.html";
    private int blockingAgentTimeoutSeconds = 28;
    private int blockingConsumptionTimeoutSeconds = 5;
    private int blockingReconciliationTimeoutSeconds = 2;
    private int maxConcurrentRequests = 8;
    private String inboundToken = "";
    private ResponseFormat responseFormat = ResponseFormat.TEXT;

    public enum ResponseFormat { TEXT, JSON }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDocumentationUrl() {
        return documentationUrl;
    }

    public void setDocumentationUrl(String documentationUrl) {
        this.documentationUrl = documentationUrl;
    }

    public int getBlockingAgentTimeoutSeconds() {
        return blockingAgentTimeoutSeconds;
    }

    public void setBlockingAgentTimeoutSeconds(int blockingAgentTimeoutSeconds) {
        this.blockingAgentTimeoutSeconds = blockingAgentTimeoutSeconds;
    }

    public int getBlockingConsumptionTimeoutSeconds() {
        return blockingConsumptionTimeoutSeconds;
    }

    public void setBlockingConsumptionTimeoutSeconds(int blockingConsumptionTimeoutSeconds) {
        this.blockingConsumptionTimeoutSeconds = blockingConsumptionTimeoutSeconds;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public int getBlockingReconciliationTimeoutSeconds() {
        return blockingReconciliationTimeoutSeconds;
    }

    public void setBlockingReconciliationTimeoutSeconds(int blockingReconciliationTimeoutSeconds) {
        this.blockingReconciliationTimeoutSeconds = blockingReconciliationTimeoutSeconds;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public String getInboundToken() {
        return inboundToken;
    }

    public void setInboundToken(String inboundToken) {
        this.inboundToken = inboundToken;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
    }

    @Override
    public void afterPropertiesSet() {
        if (responseFormat == null) {
            throw new IllegalArgumentException("response-format must be text or json");
        }
        if (blockingAgentTimeoutSeconds < 5 || blockingAgentTimeoutSeconds > 900) {
            throw new IllegalArgumentException("blocking-agent-timeout-seconds must be between 5 and 900");
        }
        if (blockingConsumptionTimeoutSeconds < 1 || blockingConsumptionTimeoutSeconds > 30) {
            throw new IllegalArgumentException("blocking-consumption-timeout-seconds must be between 1 and 30");
        }
        if (blockingReconciliationTimeoutSeconds < 1 || blockingReconciliationTimeoutSeconds > 30) {
            throw new IllegalArgumentException("blocking-reconciliation-timeout-seconds must be between 1 and 30");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 64) {
            throw new IllegalArgumentException("max-concurrent-requests must be between 1 and 64");
        }
        if (inboundToken != null && !inboundToken.isBlank()) {
            String token = inboundToken.trim();
            if (!token.equals(inboundToken)
                    || token.length() < 32
                    || token.length() > 512
                    || token.chars().anyMatch(Character::isWhitespace)
                    || token.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_A2A_TOKEN must be 32-512 non-whitespace characters");
            }
        }
    }
}
