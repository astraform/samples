package platform.marketingops.remote.api;

import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import platform.marketingops.remote.service.RemoteDomainProtocolException;
import ai.astraform.remote.domain.openapi.models.RemoteDomainErrorEnvelope;
import ai.astraform.remote.domain.openapi.models.RemoteDomainErrorEnvelopeError;

@RestControllerAdvice
public class RemoteDomainExceptionHandler {

    @ExceptionHandler(RemoteDomainProtocolException.class)
    public ResponseEntity<RemoteDomainErrorEnvelope> handleProtocolException(RemoteDomainProtocolException error) {
        RemoteDomainErrorEnvelopeError payload = new RemoteDomainErrorEnvelopeError();
        payload.setCode(error.code());
        payload.setCategory(RemoteDomainErrorEnvelopeError.CategoryEnum.fromValue(error.category()));
        payload.setMessage(error.getMessage());
        payload.setRetryable(error.retryable());
        payload.setDetails(new LinkedHashMap<>(error.details()));

        RemoteDomainErrorEnvelope envelope = new RemoteDomainErrorEnvelope();
        envelope.setProtocolVersion(RemoteDomainErrorEnvelope.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        envelope.setRequestId(error.requestId());
        envelope.setOperation(error.operation());
        envelope.setError(payload);
        return ResponseEntity.status(error.status()).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RemoteDomainErrorEnvelope> handleUnhandledException(Exception error) {
        RemoteDomainErrorEnvelopeError payload = new RemoteDomainErrorEnvelopeError();
        payload.setCode("internal_error");
        payload.setCategory(RemoteDomainErrorEnvelopeError.CategoryEnum.INTERNAL_ERROR);
        payload.setMessage(error.getMessage() == null ? "Unexpected remote domain failure." : error.getMessage());
        payload.setRetryable(Boolean.TRUE);
        payload.setDetails(new LinkedHashMap<>());

        RemoteDomainErrorEnvelope envelope = new RemoteDomainErrorEnvelope();
        envelope.setProtocolVersion(RemoteDomainErrorEnvelope.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        envelope.setOperation("unknown");
        envelope.setError(payload);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }
}
