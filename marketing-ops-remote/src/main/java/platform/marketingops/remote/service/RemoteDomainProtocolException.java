package platform.marketingops.remote.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class RemoteDomainProtocolException extends RuntimeException {
    private final UUID requestId;
    private final String operation;
    private final HttpStatus status;
    private final String code;
    private final String category;
    private final boolean retryable;
    private final Map<String, Object> details;

    public RemoteDomainProtocolException(
            UUID requestId,
            String operation,
            HttpStatus status,
            String code,
            String category,
            boolean retryable,
            String message,
            Map<String, Object> details) {
        super(message);
        this.requestId = requestId;
        this.operation = operation;
        this.status = status;
        this.code = code;
        this.category = category;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public UUID requestId() {
        return requestId;
    }

    public String operation() {
        return operation;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
