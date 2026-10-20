package ai.astraform.remote.domain.template;

import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainDueWorkItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainProjection;
import ai.astraform.remote.domain.openapi.models.RemoteDomainScheduledWorkKind;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusResponse;
import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import ai.astraform.remote.domain.sdk.RemoteDomainEnvelopeBuilder;
import ai.astraform.remote.domain.sdk.RemoteDomainManifestBuilder;
import ai.astraform.remote.domain.sdk.RemoteDomainRequestValidator;
import ai.astraform.remote.domain.sdk.RemoteDomainService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AcmeRemoteDomainService implements RemoteDomainService {
    static final String DOMAIN_ID = "acme-remote";
    private static final String SCHEMA_VERSION = "acme.state.v1";

    @Override
    public RemoteDomainManifest manifest() {
        RemoteDomainScheduledWorkKind task = new RemoteDomainScheduledWorkKind("task", "Task");
        task.setDescription("Example scheduled work item handled by the template service.");

        return RemoteDomainManifestBuilder.remoteDomain()
                .domain(DOMAIN_ID, "Acme Remote", "Minimal Java/Spring remote-domain.v1 template.", SCHEMA_VERSION)
                .supportedAgentTypes(List.of("Customer"))
                .supportedInteractionModes(List.of("SIMULATION"))
                .supportsScheduledWork(true, List.of(task))
                .build();
    }

    @Override
    public Map<String, Object> populationCatalog() {
        return PopulationCatalogs.fromClasspath(
                AcmeRemoteDomainService.class,
                "/population-catalogs/acme-remote.population-catalog.json");
    }

    @Override
    public RemoteDomainPrepareResponse prepare(RemoteDomainPrepareRequest request) {
        RemoteDomainRequestValidator.validatePrepare(request, DOMAIN_ID);

        String personaName = personaName(request);
        String runtimeIdentity = DOMAIN_ID + "::" + request.getAgent().getAgentId() + "::" + slug(personaName);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("personaName", personaName);
        data.put("agentId", request.getAgent().getAgentId());
        data.put("completedWorkCount", 0);
        data.put("appliedWorkIds", new ArrayList<>());
        data.put("lastAction", "prepared");
        data.put("lastUpdatedEpochMs", request.getSentAtEpochMs());

        RemoteDomainOpaqueState nextState = RemoteDomainEnvelopeBuilder.opaqueState(SCHEMA_VERSION, data);
        return RemoteDomainEnvelopeBuilder.prepareResponse(
                request.getRequestId(),
                runtimeIdentity,
                projection(data),
                nextState);
    }

    @Override
    public RemoteDomainExecuteWorkResponse executeWork(RemoteDomainExecuteWorkRequest request) {
        RemoteDomainRequestValidator.validateExecuteWork(request, DOMAIN_ID);

        Map<String, Object> data = stateData(request.getState());
        Set<String> appliedWorkIds = stringSet(data.get("appliedWorkIds"));
        List<RemoteDomainDueWorkItem> dueWork = request.getPayload().getTurnContext().getDueWork();
        int newlyApplied = 0;
        String replayedWorkId = null;
        for (RemoteDomainDueWorkItem workItem : dueWork) {
            String workId = workItem.getWorkId();
            if (appliedWorkIds.add(workId)) {
                newlyApplied++;
            } else {
                replayedWorkId = workId;
            }
        }
        if (newlyApplied > 0) {
            data.put("appliedWorkIds", new ArrayList<>(appliedWorkIds));
            data.put("completedWorkCount", intValue(data.get("completedWorkCount")) + newlyApplied);
            data.put("lastAction", "executed");
            data.put("lastUpdatedEpochMs", request.getSentAtEpochMs());
        }

        RemoteDomainOpaqueState nextState = RemoteDomainEnvelopeBuilder.opaqueState(SCHEMA_VERSION, data);
        RemoteDomainExecuteWorkResponse response = RemoteDomainEnvelopeBuilder.executeWorkResponse(
                request.getRequestId(),
                runtimeIdentity(request),
                projection(data),
                nextState);
        if (replayedWorkId != null) {
            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("success", true);
            toolResult.put("idempotentReplay", true);
            toolResult.put("workId", replayedWorkId);
            response.getResult().setToolResult(toolResult);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "remote_domain_tool_effect_replayed");
            event.put("outcome", "NOOP");
            event.put("workId", replayedWorkId);
            response.getResult().setEvidenceEvents(new ArrayList<>(List.of(event)));
        }
        return response;
    }

    @Override
    public RemoteDomainStatusResponse status(RemoteDomainStatusRequest request) {
        RemoteDomainRequestValidator.validateStatus(request, DOMAIN_ID);

        Map<String, Object> data = stateData(request.getState());
        return RemoteDomainEnvelopeBuilder.statusResponse(
                request.getRequestId(),
                runtimeIdentity(request),
                projection(data));
    }

    @Override
    public RemoteDomainInspectionResponse inspection(RemoteDomainInspectionRequest request) {
        RemoteDomainRequestValidator.validateInspection(request, DOMAIN_ID);

        Map<String, Object> data = stateData(request.getState());
        return RemoteDomainEnvelopeBuilder.inspectionResponse(
                request.getRequestId(),
                runtimeIdentity(request),
                projection(data));
    }

    @Override
    public RemoteDomainShutdownResponse shutdown(RemoteDomainShutdownRequest request) {
        RemoteDomainRequestValidator.validateShutdown(request, DOMAIN_ID);

        return RemoteDomainEnvelopeBuilder.shutdownResponse(request.getRequestId(), true);
    }

    private static String personaName(RemoteDomainPrepareRequest request) {
        Object configured = request.getPayload().getPersonaConfiguration().get("personaName");
        if (configured instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        if (hasText(request.getAgent().getPersonaName())) {
            return request.getAgent().getPersonaName().trim();
        }
        return "Remote Persona";
    }

    private static RemoteDomainProjection projection(Map<String, Object> data) {
        Map<String, Object> runtimeMetadata = new LinkedHashMap<>();
        runtimeMetadata.put("domainId", DOMAIN_ID);
        runtimeMetadata.put("schemaVersion", SCHEMA_VERSION);
        runtimeMetadata.put("lastAction", data.get("lastAction"));
        runtimeMetadata.put("completedWorkCount", data.get("completedWorkCount"));

        Map<String, Object> statusView = new LinkedHashMap<>();
        statusView.put("personaName", data.get("personaName"));
        statusView.put("completedWorkCount", data.get("completedWorkCount"));
        statusView.put("lastAction", data.get("lastAction"));

        Map<String, Object> inspectionView = new LinkedHashMap<>();
        inspectionView.put("stateSchemaVersion", SCHEMA_VERSION);
        inspectionView.put("stateDataKeys", new ArrayList<>(data.keySet()));
        inspectionView.put("lastUpdatedEpochMs", data.get("lastUpdatedEpochMs"));

        return RemoteDomainEnvelopeBuilder.projection(
                runtimeMetadata,
                List.of(),
                List.of(),
                statusView,
                inspectionView);
    }

    private static Map<String, Object> stateData(RemoteDomainOpaqueState state) {
        return new LinkedHashMap<>(state.getData());
    }

    private static String runtimeIdentity(RemoteDomainStatusRequest request) {
        return hasText(request.getAgent().getRuntimeIdentity())
                ? request.getAgent().getRuntimeIdentity()
                : DOMAIN_ID + "::" + request.getAgent().getAgentId();
    }

    private static String runtimeIdentity(RemoteDomainInspectionRequest request) {
        return hasText(request.getAgent().getRuntimeIdentity())
                ? request.getAgent().getRuntimeIdentity()
                : DOMAIN_ID + "::" + request.getAgent().getAgentId();
    }

    private static String runtimeIdentity(RemoteDomainExecuteWorkRequest request) {
        return hasText(request.getAgent().getRuntimeIdentity())
                ? request.getAgent().getRuntimeIdentity()
                : DOMAIN_ID + "::" + request.getAgent().getAgentId();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> values)) {
            return new LinkedHashSet<>();
        }
        return values.stream()
                .map(String::valueOf)
                .filter(AcmeRemoteDomainService::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
