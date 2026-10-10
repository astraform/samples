package banking.simulator.remote.domain;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Partner-side setup/read API. Customer agents receive only the scoped MCP tool. */
@RestController
@RequestMapping("/api/banking/v1/contexts/{contextRef}/customers/{subjectRef}")
@ConditionalOnProperty(name = "banking.backend.enabled", havingValue = "true")
public class BankingAccountController {
    private final BankingAccountRepository accounts;

    public BankingAccountController(BankingAccountRepository accounts) { this.accounts = accounts; }

    @PutMapping
    public Map<String, Object> populate(@PathVariable String contextRef, @PathVariable String subjectRef,
            @RequestBody Map<String, Object> request) {
        try {
            return accounts.seed(contextRef, subjectRef, request);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping("/accounts")
    public Map<String, Object> read(@PathVariable String contextRef, @PathVariable String subjectRef,
            @RequestParam(required = false) String contextId, @RequestParam(required = false) Long resourceVersion,
            @RequestParam(required = false) Long logicalTimeEpochMs,
            @RequestParam(required = false) String statementPeriod) {
        try {
            Map<String, Object> guard = null;
            if (contextId != null || resourceVersion != null || logicalTimeEpochMs != null) {
                if (contextId == null || resourceVersion == null || logicalTimeEpochMs == null) {
                    throw new IllegalArgumentException("All three read context fields are required together");
                }
                guard = Map.of("contextId", contextId, "resourceVersion", resourceVersion, "logicalTimeEpochMs", logicalTimeEpochMs);
            }
            return accounts.read(contextRef, subjectRef, guard, statementPeriod);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }
    // The SDK's global advice owns remote-domain errors, not this bank REST surface.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> bankingError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error", error.getReason() == null ? "Banking request rejected" : error.getReason()));
    }

}
