package ai.astraform.samples.marketingops.creativeagency.service;

public class CreativeAgencyGenerationException extends RuntimeException {

    private final Code code;

    public CreativeAgencyGenerationException(Code code) {
        this(code, null);
    }

    public CreativeAgencyGenerationException(Code code, Throwable cause) {
        super("Creative response generation failed: " + code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        AUTH_REJECTED,
        CAPACITY_EXHAUSTED,
        CONTEXT_BUSY,
        INTERRUPTED,
        INVALID_RESPONSE,
        RATE_LIMITED,
        REQUEST_REJECTED,
        TIMEOUT,
        UPSTREAM_UNAVAILABLE
    }
}
