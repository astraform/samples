package platform.marketingops.remote.api;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ModelAttribute;
import platform.marketingops.remote.service.RemoteDomainProtocolException;
import org.springframework.http.HttpStatus;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import platform.marketingops.remote.service.MarketingOpsRemoteDomainService;
import ai.astraform.remote.domain.openapi.controller.RemoteDomainProtocolApi;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusResponse;

@RestController
@CrossOrigin(origins = "*")
public class MarketingOpsRemoteDomainController implements RemoteDomainProtocolApi {
    private final MarketingOpsRemoteDomainService remoteDomainService;
    @Autowired
    private HttpServletRequest servletRequest;

    @ModelAttribute
    public void verifyProviderRevision(HttpServletRequest request, HttpServletResponse response) {
        String expected = request.getHeader("X-Astraform-Expected-Provider-Revision");
        if (request.getRequestURI().endsWith("/manifest") || expected != null) {
            response.setHeader("X-Astraform-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION);
            if (expected != null && !MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION.equals(expected)) {
                throw revisionMismatch("Provider revision does not match the pinned contract");
            }
        }
    }

    private RemoteDomainProtocolException revisionMismatch(String message) {
        return new RemoteDomainProtocolException(null, "unknown", HttpStatus.PRECONDITION_FAILED,
                "provider_revision_mismatch", "domain_rejected", false, message, Map.of());
    }

    private void requirePinnedRevision() {
        if (!MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION.equals(
                servletRequest.getHeader("X-Astraform-Expected-Provider-Revision"))) {
            throw revisionMismatch("Native requests require the pinned provider revision");
        }
    }

    public MarketingOpsRemoteDomainController(MarketingOpsRemoteDomainService remoteDomainService) {
        this.remoteDomainService = remoteDomainService;
    }

    @Override
    public ResponseEntity<RemoteDomainManifest> getRemoteDomainManifest() {
        return ResponseEntity.ok(remoteDomainService.manifest());
    }

    @GetMapping("/api/remote-domain/v1/population/catalog")
    public ResponseEntity<Map<String, Object>> getPopulationCatalog() {
        return ResponseEntity.ok(remoteDomainService.populationCatalog());
    }

    @Override
    public ResponseEntity<RemoteDomainPrepareResponse> prepareRemoteDomainAgent(
            RemoteDomainPrepareRequest remoteDomainPrepareRequest) {
        var hints = remoteDomainPrepareRequest.getPayload() == null ? null
                : remoteDomainPrepareRequest.getPayload().getInitialProjectionHints();
        if (hints != null && hints.containsKey("nativeSimulation")) requirePinnedRevision();
        return ResponseEntity.ok(remoteDomainService.prepare(remoteDomainPrepareRequest));
    }

    @Override
    public ResponseEntity<RemoteDomainSystemWorkWindowResponse> executeRemoteDomainSystemWorkWindow(
            RemoteDomainSystemWorkWindowRequest request) {
        requirePinnedRevision();
        return ResponseEntity.ok(remoteDomainService.domainSystemWorkWindow(request));
    }

    @Override
    public ResponseEntity<RemoteDomainExecuteWorkResponse> executeRemoteDomainWork(
            RemoteDomainExecuteWorkRequest remoteDomainExecuteWorkRequest) {
        return ResponseEntity.ok(remoteDomainService.executeWork(remoteDomainExecuteWorkRequest));
    }

    @Override
    public ResponseEntity<RemoteDomainStatusResponse> projectRemoteDomainStatus(
            RemoteDomainStatusRequest remoteDomainStatusRequest) {
        return ResponseEntity.ok(remoteDomainService.status(remoteDomainStatusRequest));
    }

    @Override
    public ResponseEntity<RemoteDomainInspectionResponse> projectRemoteDomainInspection(
            RemoteDomainInspectionRequest remoteDomainInspectionRequest) {
        return ResponseEntity.ok(remoteDomainService.inspection(remoteDomainInspectionRequest));
    }

    @Override
    public ResponseEntity<RemoteDomainShutdownResponse> shutdownRemoteDomainAgent(
            RemoteDomainShutdownRequest remoteDomainShutdownRequest) {
        return ResponseEntity.ok(remoteDomainService.shutdown(remoteDomainShutdownRequest));
    }
}
