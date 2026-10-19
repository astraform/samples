package ai.astraform.samples.marketingops.creativeagency.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "marketing-ops-creative-agency.generation")
public class CreativeAgencyGenerationProperties implements InitializingBean {

    private Mode mode = Mode.LLM;
    private final Llm llm = new Llm();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Llm getLlm() {
        return llm;
    }

    @Override
    public void afterPropertiesSet() {
        if (mode == null) {
            throw new IllegalArgumentException("marketing-ops-creative-agency.generation.mode is required");
        }
        if (mode == Mode.LLM) {
            llm.validate();
        }
    }

    public enum Mode {
        DETERMINISTIC,
        LLM;

        public static Mode from(String value) {
            return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public static final class Llm {

        private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/-]{1,160}");
        private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

        private String providerId = "";
        private String baseUrl = "";
        private AuthType authType = AuthType.BEARER;
        private String apiKey = "";
        private String model = "";
        private String bindingRevision = "";
        private boolean allowInsecureHttp;
        private StructuredOutputMode structuredOutputMode = StructuredOutputMode.JSON_SCHEMA;
        private TokenLimitParameter tokenLimitParameter = TokenLimitParameter.MAX_TOKENS;
        private String reasoningEffort = "";
        private Double temperature;
        private Double topP;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(15);
        private int maxAttempts = 1;
        private Duration retryBackoff = Duration.ofMillis(250);
        private int maxOutputTokens = 800;
        private int maxResponseBytes = 131_072;
        private int maxConcurrency = 4;
        private int maxRequestsPerMinute = 60;

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public AuthType getAuthType() {
            return authType;
        }

        public void setAuthType(AuthType authType) {
            this.authType = authType;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBindingRevision() {
            return bindingRevision;
        }

        public void setBindingRevision(String bindingRevision) {
            this.bindingRevision = bindingRevision;
        }

        public boolean isAllowInsecureHttp() {
            return allowInsecureHttp;
        }

        public void setAllowInsecureHttp(boolean allowInsecureHttp) {
            this.allowInsecureHttp = allowInsecureHttp;
        }

        public StructuredOutputMode getStructuredOutputMode() {
            return structuredOutputMode;
        }

        public void setStructuredOutputMode(StructuredOutputMode structuredOutputMode) {
            this.structuredOutputMode = structuredOutputMode;
        }

        public TokenLimitParameter getTokenLimitParameter() {
            return tokenLimitParameter;
        }

        public void setTokenLimitParameter(TokenLimitParameter tokenLimitParameter) {
            this.tokenLimitParameter = tokenLimitParameter;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Double getTopP() {
            return topP;
        }

        public void setTopP(Double topP) {
            this.topP = topP;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }

        public void validate() {
            if (authType == null) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_AUTH_TYPE is required in LLM mode");
            }
            if (structuredOutputMode == null) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_STRUCTURED_OUTPUT_MODE is required in LLM mode");
            }
            if (tokenLimitParameter == null) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER is required in LLM mode");
            }
            requireSafeIdentifier(providerId, "CREATIVE_AGENCY_LLM_PROVIDER_ID");
            requireBaseUrl(baseUrl);
            requireSafeIdentifier(model, "CREATIVE_AGENCY_LLM_MODEL");
            requireSafeIdentifier(bindingRevision, "CREATIVE_AGENCY_LLM_BINDING_REVISION");
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                requireSafeIdentifier(reasoningEffort, "CREATIVE_AGENCY_LLM_REASONING_EFFORT");
            }
            if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_TEMPERATURE must be a finite number between 0 and 2");
            }
            if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_TOP_P must be a finite number between 0 and 1");
            }
            if (authType == AuthType.BEARER) {
                requireText(apiKey, "CREATIVE_AGENCY_LLM_API_KEY", 512, false);
                if (!apiKey.equals(apiKey.trim()) || apiKey.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_API_KEY is invalid");
                }
            } else if (apiKey != null && !apiKey.isBlank()) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_API_KEY must be empty when CREATIVE_AGENCY_LLM_AUTH_TYPE=none");
            }
            requireDuration(connectTimeout, "CREATIVE_AGENCY_LLM_CONNECT_TIMEOUT", Duration.ofSeconds(30));
            requireDuration(readTimeout, "CREATIVE_AGENCY_LLM_READ_TIMEOUT", Duration.ofMinutes(15));
            requireDuration(retryBackoff, "CREATIVE_AGENCY_LLM_RETRY_BACKOFF", Duration.ofSeconds(5));
            if (maxAttempts < 1 || maxAttempts > 3) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_MAX_ATTEMPTS must be between 1 and 3");
            }
            if (maxOutputTokens < 64 || maxOutputTokens > 32_768) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS must be between 64 and 32768");
            }
            if (maxResponseBytes < 4_096 || maxResponseBytes > 1_048_576) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_MAX_RESPONSE_BYTES must be between 4096 and 1048576");
            }
            if (maxConcurrency < 1 || maxConcurrency > 32) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_MAX_CONCURRENCY must be between 1 and 32");
            }
            if (maxRequestsPerMinute < 1 || maxRequestsPerMinute > 6_000) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_MAX_REQUESTS_PER_MINUTE must be between 1 and 6000");
            }
        }

        public String normalizedBaseUrl() {
            String value = baseUrl.trim();
            return value.endsWith("/") ? value : value + "/";
        }

        private void requireBaseUrl(String value) {
            requireText(value, "CREATIVE_AGENCY_LLM_BASE_URL", 1_024, true);
            URI uri;
            try {
                uri = URI.create(value.trim());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_BASE_URL must be a valid URI", failure);
            }
            String scheme = normalize(uri.getScheme());
            String host = normalize(uri.getHost());
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_BASE_URL cannot contain credentials, a query, or a fragment");
            }
            if (host.isBlank() || !("https".equals(scheme) || "http".equals(scheme))) {
                throw new IllegalArgumentException("CREATIVE_AGENCY_LLM_BASE_URL must use HTTP or HTTPS");
            }
            if ("http".equals(scheme)
                    && !LOOPBACK_HOSTS.contains(host)
                    && !(allowInsecureHttp && authType == AuthType.NONE)) {
                throw new IllegalArgumentException(
                        "CREATIVE_AGENCY_LLM_BASE_URL must use HTTPS; non-loopback HTTP requires "
                                + "auth type none and CREATIVE_AGENCY_LLM_ALLOW_INSECURE_HTTP=true");
            }
        }

        private static void requireSafeIdentifier(String value, String name) {
            requireText(value, name, 160, true);
            if (!SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
                throw new IllegalArgumentException(name + " contains unsupported characters");
            }
        }

        private static void requireText(String value, String name, int maxLength, boolean trim) {
            String checked = trim && value != null ? value.trim() : value;
            if (checked == null || checked.isBlank()) {
                throw new IllegalArgumentException(name + " is required in LLM mode");
            }
            if (checked.length() > maxLength || checked.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }

        private static void requireDuration(Duration value, String name, Duration maximum) {
            if (value == null || value.isNegative() || value.isZero() || value.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " must be positive and no greater than " + maximum);
            }
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public enum AuthType {
        BEARER,
        NONE
    }

    public enum StructuredOutputMode {
        JSON_SCHEMA,
        JSON_OBJECT,
        PROMPT_ONLY
    }

    public enum TokenLimitParameter {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        OMIT(null);

        private final String jsonField;

        TokenLimitParameter(String jsonField) {
            this.jsonField = jsonField;
        }

        public String jsonField() {
            return jsonField;
        }

        public boolean isEmitted() {
            return jsonField != null;
        }

        public String receiptValue() {
            return jsonField == null ? "omit" : jsonField;
        }
    }
}
