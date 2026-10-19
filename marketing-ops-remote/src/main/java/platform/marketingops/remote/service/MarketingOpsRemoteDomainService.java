package platform.marketingops.remote.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ai.astraform.remote.domain.openapi.controller.RemoteDomainProtocolApi;
import ai.astraform.remote.domain.openapi.models.RemoteDomainAgentContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainDueWorkItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainNativeSimulationProfile;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifestCapabilities;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifestDomain;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifestOperations;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifestTransport;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOperationDescriptor;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainProjection;
import ai.astraform.remote.domain.openapi.models.RemoteDomainResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainScheduleItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainScheduledWorkKind;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainToolDescriptor;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiCreatorSection;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiField;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiFieldDefaultValue;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiOption;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiProfile;
import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import ai.astraform.remote.domain.sdk.RemoteDomainEnvelopeBuilder;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainCustomerDecisionTrigger;

@Service
public class MarketingOpsRemoteDomainService {
    public static final String NATIVE_PROFILE = "remote-domain.native-state-transform.v1";
    public static final String NATIVE_PROVIDER_REVISION = "marketing-ops.native-events.v4";
    private static final int MAX_NATIVE_WORK = 32;
    private static final long MAX_NATIVE_TIME = 253402300799999L;
    private static final List<String> NATIVE_WORK_KINDS = List.of("task", "action");
    private static final String PROTOCOL_VERSION = "remote-domain.v1";
    private static final String DOMAIN_ID = "marketing-ops";
    private static final String DISPLAY_NAME = "Marketing Operations Remote Domain";
    private static final String SCHEMA_VERSION = "marketing-ops.state.v1";
    private static final String TRANSPORT_CONTENT_TYPE = "application/vnd.astraform.remote-domain.v1+json";
    private static final int MAX_OPAQUE_STATE_BYTES = 262_144;
    private static final Set<String> FOCUS_AREAS = Set.of("campaign-ops", "creative-review", "launch-ops");
    private static final Set<String> QUEUE_MODES = Set.of("readiness", "reviewer", "escalation");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final boolean nativeSimulationEnabled;

    public MarketingOpsRemoteDomainService(ObjectMapper objectMapper) {
        this(objectMapper, false);
    }

    @Autowired
    public MarketingOpsRemoteDomainService(ObjectMapper objectMapper,
            @Value("${astraform.native-simulation.enabled:false}") boolean nativeSimulationEnabled) {
        this.objectMapper = objectMapper;
        this.nativeSimulationEnabled = nativeSimulationEnabled;
    }

    public RemoteDomainManifest manifest() {
        RemoteDomainManifestDomain domain = new RemoteDomainManifestDomain(
                DOMAIN_ID,
                DISPLAY_NAME);
        domain.setDescription("Protocol-native marketing operations proof service.");
        domain.setSchemaVersion(SCHEMA_VERSION);

        RemoteDomainManifestTransport transport = new RemoteDomainManifestTransport(
                RemoteDomainManifestTransport.ModeEnum.SYNCHRONOUS_HTTP_JSON,
                TRANSPORT_CONTENT_TYPE);

        RemoteDomainManifestOperations operations = new RemoteDomainManifestOperations(
                operation(RemoteDomainOperationDescriptor.MethodEnum.POST, RemoteDomainProtocolApi.PATH_PREPARE_REMOTE_DOMAIN_AGENT, 10_000),
                operation(RemoteDomainOperationDescriptor.MethodEnum.POST, RemoteDomainProtocolApi.PATH_EXECUTE_REMOTE_DOMAIN_WORK, 5_000),
                operation(RemoteDomainOperationDescriptor.MethodEnum.POST, RemoteDomainProtocolApi.PATH_PROJECT_REMOTE_DOMAIN_STATUS, 2_000),
                operation(RemoteDomainOperationDescriptor.MethodEnum.POST, RemoteDomainProtocolApi.PATH_PROJECT_REMOTE_DOMAIN_INSPECTION, 3_000));
        operations.setShutdown(operation(
                RemoteDomainOperationDescriptor.MethodEnum.POST,
                RemoteDomainProtocolApi.PATH_SHUTDOWN_REMOTE_DOMAIN_AGENT,
                2_000));

        RemoteDomainManifestCapabilities capabilities = new RemoteDomainManifestCapabilities(
                Boolean.TRUE,
                Boolean.TRUE,
                MAX_OPAQUE_STATE_BYTES,
                new ArrayList<>(List.of("Customer", "Operator", "Policy")),
                new ArrayList<>(List.of("SIMULATION", "HYBRID", "A2A_SESSION")));
        capabilities.setShutdownNotification(Boolean.TRUE);
        capabilities.setSupportsScheduledWork(Boolean.TRUE);
        capabilities.setScheduledWorkKinds(new ArrayList<>(List.of(
                recurringTaskKind(),
                recurringActionKind())));
        capabilities.setTools(new ArrayList<>(List.of(
                tool(
                        "get_campaign_brief",
                        "Retrieve the operating brief for a marketing campaign, including owner, launch target, audience, and current stage.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "campaignKey", Map.of("type", "string")),
                                "additionalProperties", false)),
                tool(
                        "list_launch_blockers",
                        "List current blockers holding up a campaign launch, including owner, severity, and expected next action.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "campaignKey", Map.of("type", "string")),
                                "additionalProperties", false)),
                tool(
                        "get_channel_capacity",
                        "Inspect current channel operating capacity for a marketing channel such as email, paid-social, or lifecycle crm.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "channel", Map.of("type", "string")),
                                "additionalProperties", false)),
                tool(
                        "get_review_queue",
                        "Return a summary of the marketing review queue, including work waiting, SLA risk, and the highest-priority item.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reviewLane", Map.of("type", "string")),
                                "additionalProperties", false)),
                tool(
                        "post_review_decision",
                        "Post a deterministic review decision for a campaign blocker.",
                        Map.of(
                                "type", "object",
                                "required", List.of("decision"),
                                "properties", Map.of(
                                        "campaignKey", Map.of("type", "string"),
                                        "blockerId", Map.of("type", "string"),
                                        "decision", Map.of("type", "string", "enum", List.of("APPROVE", "REQUEST_CHANGES", "ESCALATE")),
                                        "note", Map.of("type", "string")),
                                "additionalProperties", false)))));

        RemoteDomainManifest manifest = new RemoteDomainManifest(
                RemoteDomainManifest.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                domain,
                transport,
                operations,
                capabilities);
        manifest.setUiProfile(marketingOpsUiProfile());
        if (nativeSimulationEnabled) {
            operations.setDomainSystemWorkWindow(operation(RemoteDomainOperationDescriptor.MethodEnum.POST,
                    RemoteDomainProtocolApi.PATH_EXECUTE_REMOTE_DOMAIN_SYSTEM_WORK_WINDOW, 5_000));
            capabilities.setSupportsDomainSystemWorkWindow(true);
            capabilities.setDomainSystemWorkKinds(NATIVE_WORK_KINDS.stream().map(kind -> {
                var descriptor = new RemoteDomainScheduledWorkKind();
                descriptor.setKind(kind);
                descriptor.setLabel("Scheduled marketing " + kind);
                descriptor.setDescription("Existing deterministic queue rules on bounded DAILY UTC schedules.");
                descriptor.setFields(List.of());
                return descriptor;
            }).toList());
            capabilities.setNativeSimulation(new RemoteDomainNativeSimulationProfile(
                    NATIVE_PROFILE, NATIVE_PROVIDER_REVISION,
                    RemoteDomainNativeSimulationProfile.MutationModeEnum.PURE_STATE_TRANSFORM,
                    RemoteDomainNativeSimulationProfile.StateScopeEnum.AGENT,
                    new LinkedHashSet<>(NATIVE_WORK_KINDS), MAX_NATIVE_WORK,
                    RemoteDomainNativeSimulationProfile.InitialBoundaryModeEnum.PREPARE_INCLUDES_START));
        }
        return manifest;
    }

    public RemoteDomainSystemWorkWindowResponse domainSystemWorkWindow(RemoteDomainSystemWorkWindowRequest request) {
        try {
            requireNativeEnabled();
            validateMutatingRequest(request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                    request.getRequestId(), "domain-system-work-window",
                    request.getDomain() == null ? null : request.getDomain().getDomainId(), request.getIdempotencyKey(), null);
            MarketingOpsRemoteState current = readState(request.getRequestId(), "domain-system-work-window", request.getState());
            requireNativeStateSize(request.getState());
            validateNativeSchedules(current);
            var payload = request.getPayload();
            if (payload == null || payload.getTimeWindow() == null || payload.getTimeWindow().getPreviousTimeEpochMs() == null
                    || payload.getTimeWindow().getCurrentTimeEpochMs() == null) {
                throw new IllegalArgumentException("Native window requires explicit logical boundaries");
            }
            Map<String, Object> hints = mapValue(mapValue(payload.getContext()).get("nativeSimulation"));
            Map<String, Object> saved = current.nativeSimulation();
            long version = nativeLong(saved, "stateVersion");
            requireNativeIdentity(hints, version);
            long previous = payload.getTimeWindow().getPreviousTimeEpochMs();
            long through = payload.getTimeWindow().getCurrentTimeEpochMs();
            long horizon = nativeLong(saved, "logicalHorizonEpochMs");
            requireNativeTimes(previous, horizon);
            long boundary = nativeLong(saved, saved.containsKey("nextDueEpochMs") ? "nextDueEpochMs" : "noWorkBeforeEpochMs");
            if (version < 1 || !NATIVE_PROFILE.equals(saved.get("profile"))
                    || !Objects.equals(saved.get("contextId"), hints.get("contextId"))
                    || horizon != nativeLong(hints, "logicalHorizonEpochMs")
                    || previous != nativeLong(saved, "processedThroughEpochMs")
                    || through <= previous || through > horizon || through > boundary
                    || payload.getExecutionMode() != RemoteDomainSystemWorkWindowPayload.ExecutionModeEnum.EVALUATE_AND_EXECUTE
                    || payload.getMaxWorkItems() == null || payload.getMaxWorkItems() < 1 || payload.getMaxWorkItems() > MAX_NATIVE_WORK
                    || payload.getWorkKinds() == null || payload.getWorkKinds().isEmpty()
                    || payload.getWorkKinds().size() != Set.copyOf(payload.getWorkKinds()).size()
                    || !NATIVE_WORK_KINDS.containsAll(payload.getWorkKinds())) {
                throw new IllegalArgumentException("Native window must match the accepted context, version, horizon and earliest work boundary");
            }
            List<RemoteDomainDueWorkItem> due = nativeDueWork(current, through, true, NATIVE_WORK_KINDS);
            if (due.size() > payload.getMaxWorkItems()
                    || due.stream().anyMatch(item -> !payload.getWorkKinds().contains(item.getWorkType().toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException("All co-due marketing work must be selected and fit maxWorkItems before advancing");
            }
            MarketingOpsRemoteState next = due.isEmpty() ? current : applyDueWork(current, due, through).state();
            next = withNativeMetadata(next, hints, Math.addExact(version, 1L), through, horizon);
            List<Map<String, Object>> evidence = nativeEvidence(hints, due, next, through);
            return RemoteDomainEnvelopeBuilder.domainSystemWorkWindowResponse(request.getRequestId(), runtimeIdentity(request.getAgent()),
                    projection(next, through), opaqueState(next), nativeWorkResult(previous, through, due, evidence), evidence);
        } catch (IllegalArgumentException invalid) {
            throw nativeRejected(request.getRequestId(), "domain-system-work-window", invalid.getMessage());
        }
    }

    private void requireNativeEnabled() {
        if (!nativeSimulationEnabled) throw new IllegalArgumentException("Native simulation requires astraform.native-simulation.enabled=true");
    }

    private void requireNativeIdentity(Map<String, Object> hints, long version) {
        if (!NATIVE_PROFILE.equals(hints.get("profile"))
                || !(hints.get("contextId") instanceof String contextId) || contextId.isBlank() || contextId.length() > 256
                || nativeLong(hints, "expectedStateVersion") != version) {
            throw new IllegalArgumentException("Native context identity or expected state version is invalid");
        }
    }

    private long nativeLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Native " + key + " must be an integer");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Native " + key + " must be an exact 64-bit integer", invalid);
        }
    }

    private void requireNativeTimes(long start, long horizon) {
        if (start < 0 || horizon < start || horizon > MAX_NATIVE_TIME) {
            throw new IllegalArgumentException("Native horizon must follow start, within epoch zero and year 9999");
        }
    }

    private void validateNativeConfiguration(Map<String, Object> persona) {
        if (persona == null) throw new IllegalArgumentException("Native personaConfiguration must be an object");
        for (String key : List.of("scheduledWork", "actions")) {
            if (persona.containsKey(key) && (!(persona.get(key) instanceof List<?> items)
                    || items.stream().anyMatch(item -> !(item instanceof Map<?, ?>)))) {
                throw new IllegalArgumentException("Native " + key + " must be an array of objects");
            }
        }
        if (persona.containsKey("domainConfig") && !(persona.get("domainConfig") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Native domainConfig must be an object");
        }
        Map<String, Object> config = mapValue(persona == null ? null : persona.get("domainConfig"));
        if (!Set.of("campaignKey", "focusArea", "queueMode", "seedApprovalCount").containsAll(config.keySet())) {
            throw new IllegalArgumentException("Native marketing domainConfig supports campaignKey, focusArea, queueMode and seedApprovalCount only");
        }
        if (positiveInteger(config.get("seedApprovalCount"), 2) > MAX_NATIVE_WORK
                || mapList(persona == null ? null : persona.get("scheduledWork")).size() > MAX_NATIVE_WORK) {
            throw new IllegalArgumentException("Native marketing supports at most 32 approvals and 32 recurring schedules per customer");
        }
        if (!mapList(persona == null ? null : persona.get("actions")).isEmpty()) {
            throw new IllegalArgumentException("Native marketing requires scheduledWork; legacy prompt actions remain on the legacy path");
        }
        for (Map<String, Object> item : mapList(persona == null ? null : persona.get("scheduledWork"))) {
            if (!Set.of("workId", "description", "kind", "frequency", "time", "payload").containsAll(item.keySet())) {
                throw new IllegalArgumentException("Native marketing supports DAILY UTC scheduledWork without extra recurrence policies");
            }
            if (item.containsKey("payload") && !(item.get("payload") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Native scheduledWork payload must be an object");
            }
            Map<String, Object> payload = mapValue(item.get("payload"));
            if (!Set.of("actionType", "applyTimeJitter", "segmentId", "priority", "reviewFinding", "reviewFacts", "approvalId").containsAll(payload.keySet())
                    || payload.containsKey("applyTimeJitter") && !Boolean.FALSE.equals(payload.get("applyTimeJitter"))
                    || !Set.of("", "DETERMINISTIC_TURN", "REVIEW", "ESCALATE", "BLOCKER", "READINESS").contains(upper(trim(payload.get("actionType")), ""))) {
                throw new IllegalArgumentException("Native marketing supports deterministic domain rules only; prompt execution and time jitter are unsupported");
            }
            reviewFinding(payload);
            reviewFacts(payload);
            if (payload.containsKey("approvalId") && (!(payload.get("approvalId") instanceof String id)
                    || id.isBlank() || id.length() > 256)) {
                throw new IllegalArgumentException("Scheduled work approvalId must be a nonblank string of at most 256 characters");
            }
        }
    }

    private Map<String, Object> reviewFacts(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("reviewFacts");
        if (payload != null && payload.containsKey("reviewFacts") && !(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Scheduled work reviewFacts must be an object");
        }
        Map<String, Object> facts = mapValue(value);
        if (!Set.of("reviewCompletionStatus", "launchApprovalStatus", "licenceDocument", "documentSupplier",
                "coordinationOwner", "rightsHolder", "licensingAuthority", "deliveryDate").containsAll(facts.keySet())) {
            throw new IllegalArgumentException("Scheduled work reviewFacts contains unsupported fields");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("reviewCompletionStatus", factStatus(facts, "reviewCompletionStatus",
                Set.of("UNREPORTED", "UNKNOWN", "PENDING", "IN_PROGRESS", "COMPLETED")));
        normalized.put("launchApprovalStatus", factStatus(facts, "launchApprovalStatus",
                Set.of("UNREPORTED", "UNKNOWN", "PENDING_REVIEW", "BLOCKED", "APPROVED", "READY_FOR_LAUNCH")));
        for (String field : List.of("licenceDocument", "documentSupplier", "coordinationOwner", "rightsHolder",
                "licensingAuthority", "deliveryDate")) {
            if (!facts.containsKey(field)) {
                normalized.put(field, Map.of("knowledge", "UNREPORTED"));
                continue;
            }
            if (!(facts.get(field) instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Review fact " + field + " must be an object");
            }
            Map<String, Object> fact = mapValue(facts.get(field));
            if (!Set.of("knowledge", "value").containsAll(fact.keySet())
                    || !(fact.get("knowledge") instanceof String knowledge)
                    || !Set.of("KNOWN", "UNKNOWN", "UNREPORTED").contains(knowledge)) {
                throw new IllegalArgumentException("Review fact " + field + " requires KNOWN, UNKNOWN or UNREPORTED knowledge");
            }
            if ("KNOWN".equals(knowledge)) {
                if (!(fact.get("value") instanceof String known) || known.isBlank() || known.length() > 1_200) {
                    throw new IllegalArgumentException("KNOWN review fact " + field + " requires a nonblank value of at most 1200 characters");
                }
                if ("licenceDocument".equals(field) && !Set.of("MISSING", "RECEIVED", "VERIFIED").contains(known)) {
                    throw new IllegalArgumentException("KNOWN licenceDocument must be MISSING, RECEIVED or VERIFIED");
                }
                normalized.put(field, Map.of("knowledge", knowledge, "value", known));
            } else {
                if (fact.containsKey("value")) {
                    throw new IllegalArgumentException("Unknown or unreported review fact " + field + " cannot carry a value");
                }
                normalized.put(field, Map.of("knowledge", knowledge));
            }
        }
        return normalized;
    }

    private String factStatus(Map<String, Object> facts, String field, Set<String> allowed) {
        if (!facts.containsKey(field)) return "UNREPORTED";
        if (!(facts.get(field) instanceof String status) || !allowed.contains(status)) {
            throw new IllegalArgumentException("Review fact " + field + " has an unsupported status");
        }
        return (String) facts.get(field);
    }

    private String reviewFinding(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey("reviewFinding")) return "";
        if (!(payload.get("reviewFinding") instanceof String finding)
                || finding.isBlank() || finding.length() > 1_200) {
            throw new IllegalArgumentException("Scheduled work reviewFinding must be a nonblank string of at most 1200 characters");
        }
        return finding;
    }

    private void validateNativeSchedules(MarketingOpsRemoteState state) {
        if (state.recurringActions().size() > MAX_NATIVE_WORK || state.pendingApprovals().size() > MAX_NATIVE_WORK) {
            throw new IllegalArgumentException("Native marketing state exceeds the configured work bound");
        }
        Set<String> identities = new java.util.HashSet<>();
        for (int index = 0; index < state.recurringActions().size(); index++) {
            MarketingOpsRecurringAction action = state.recurringActions().get(index);
            if (!"DAILY".equals(action.frequency()) || !Set.of("TASK", "ACTION").contains(action.actionType())
                    || !identities.add(nativeWorkId(action, index))) {
                throw new IllegalArgumentException("Native marketing requires unique work identities and DAILY task/action schedules");
            }
            try {
                if (LocalTime.parse(action.time()).getNano() % 1_000_000 != 0) {
                    throw new IllegalArgumentException("Native scheduling supports millisecond precision");
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Native DAILY schedules require a UTC local time with at most millisecond precision", invalid);
            }
        }
    }

    private String nativeWorkId(MarketingOpsRecurringAction action, int index) {
        return StringUtils.hasText(action.workId()) ? action.workId() : "marketing-schedule-" + index;
    }

    private long nextNativeOccurrence(MarketingOpsRecurringAction action, long after, boolean inclusive) {
        ZonedDateTime instant = Instant.ofEpochMilli(after).atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = instant.toLocalDate().atTime(LocalTime.parse(action.time())).atZone(ZoneOffset.UTC);
        if (candidate.toInstant().toEpochMilli() < after || !inclusive && candidate.toInstant().toEpochMilli() == after) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    private List<RemoteDomainDueWorkItem> nativeDueWork(MarketingOpsRemoteState state, long at, boolean inclusive,
            List<String> workKinds) {
        List<RemoteDomainDueWorkItem> due = new ArrayList<>();
        for (int index = 0; index < state.recurringActions().size(); index++) {
            MarketingOpsRecurringAction action = state.recurringActions().get(index);
            if (nextNativeOccurrence(action, at, inclusive) != at || !workKinds.contains(action.actionType().toLowerCase(Locale.ROOT))) continue;
            String workId = nativeWorkId(action, index);
            RemoteDomainDueWorkItem item = new RemoteDomainDueWorkItem();
            item.setWorkId(workId);
            item.setWorkType(action.actionType());
            item.setDescription(action.description());
            item.setOccurrenceKey(workId + "@" + at);
            item.setScheduledForEpochMs(at);
            item.setAttemptNumber(1);
            item.setPayload(action.payload() == null ? Map.of() : action.payload());
            due.add(item);
        }
        return List.copyOf(due);
    }

    private MarketingOpsRemoteState withNativeMetadata(MarketingOpsRemoteState state, Map<String, Object> hints,
            long version, long through, long horizon) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", NATIVE_PROFILE);
        metadata.put("contextId", hints.get("contextId"));
        metadata.put("stateVersion", version);
        metadata.put("processedThroughEpochMs", through);
        metadata.put("logicalHorizonEpochMs", horizon);
        long next = state.recurringActions().stream().mapToLong(action -> nextNativeOccurrence(action, through, false)).min().orElse(horizon + 1);
        metadata.put(next <= horizon ? "nextDueEpochMs" : "noWorkBeforeEpochMs", Math.min(next, horizon + 1));
        MarketingOpsRemoteState result = new MarketingOpsRemoteState(state.personaName(), state.campaignKey(), state.focusArea(),
                state.queueMode(), state.seedApprovalCount(), state.completedWorkCount(), state.lastAction(), state.lastActionAtEpochMs(),
                state.pendingApprovals(), state.recurringActions(), state.activityLog(), metadata, state.selectedReview());
        requireNativeStateSize(opaqueState(result));
        return result;
    }

    private void requireNativeStateSize(RemoteDomainOpaqueState state) {
        if (objectMapper.writeValueAsBytes(state).length > MAX_OPAQUE_STATE_BYTES) {
            throw new IllegalArgumentException("Native opaque checkpoint exceeds 262144 bytes");
        }
    }

    private List<Map<String, Object>> nativeEvidence(Map<String, Object> hints, List<RemoteDomainDueWorkItem> due,
            MarketingOpsRemoteState state, long at) {
        return due.stream().map(item -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", hints.get("contextId") + ":" + item.getOccurrenceKey());
            event.put("eventType", "marketing_queue_reviewed");
            event.put("workId", item.getWorkId());
            event.put("occurrenceKey", item.getOccurrenceKey());
            event.put("scheduledForEpochMs", at);
            event.put("campaignKey", state.campaignKey());
            event.put("queueState", queueState(state.pendingApprovals()));
            event.put("pendingApprovals", state.pendingApprovals().size());
            return event;
        }).toList();
    }

    private RemoteDomainSystemWorkWindowResult nativeWorkResult(long previous, long through,
            List<RemoteDomainDueWorkItem> due, List<Map<String, Object>> evidence) {
        var result = new RemoteDomainSystemWorkWindowResult(previous, through, due.size(), due.size());
        result.setWorkResults(java.util.stream.IntStream.range(0, due.size()).mapToObj(index -> {
            var item = due.get(index);
            return new RemoteDomainSystemWorkResult(item.getWorkId(), item.getWorkType().toLowerCase(Locale.ROOT),
                    RemoteDomainSystemWorkResult.OutcomeEnum.APPLIED).occurrenceKey(item.getOccurrenceKey())
                    .scheduledForEpochMs(through).evidenceRef(evidence.get(index).get("eventId").toString());
        }).toList());
        result.setStateDeltas(evidence);
        result.setDecisionTriggers(evidence.stream().map(event -> {
            String id = event.get("eventId").toString();
            var trigger = new RemoteDomainCustomerDecisionTrigger(
                    RemoteDomainCustomerDecisionTrigger.SchemaVersionEnum.CUSTOMER_DECISION_TRIGGER_V1,
                    "marketing-queue:" + id, "marketing_queue_review", id, through, "NORMAL",
                    "A scheduled marketing review updated the campaign queue.", new LinkedHashMap<>(event));
            trigger.setEvidenceRefs(List.of(id));
            return trigger;
        }).toList());
        return result;
    }

    private RemoteDomainProtocolException nativeRejected(UUID requestId, String operation, String message) {
        return protocolException(requestId, operation, HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_native_operation",
                "domain_rejected", false, message, Map.of());
    }

    public Map<String, Object> populationCatalog() {
        return PopulationCatalogs.fromClasspath(
                MarketingOpsRemoteDomainService.class,
                "/population-catalogs/marketing-ops.population-catalog.json");
    }

    public RemoteDomainPrepareResponse prepare(RemoteDomainPrepareRequest request) {
        Map<String, Object> hints = request.getPayload() == null ? Map.of()
                : mapValue(mapValue(request.getPayload().getInitialProjectionHints()).get("nativeSimulation"));
        boolean nativeRequested = request.getPayload() != null && mapValue(request.getPayload().getInitialProjectionHints()).containsKey("nativeSimulation");
        validatePrepareRequest(request, nativeRequested);
        if (!nativeRequested) {
            MarketingOpsRemoteState state = seedState(request, request.getSentAtEpochMs());
            return prepareResponse(request.getRequestId(), runtimeIdentity(request.getAgent()), state, request.getSentAtEpochMs());
        }
        try {
            requireNativeEnabled();
            requireNativeIdentity(hints, 0L);
            long start = nativeLong(hints, "logicalTimeEpochMs");
            long horizon = nativeLong(hints, "logicalHorizonEpochMs");
            requireNativeTimes(start, horizon);
            validateNativeConfiguration(request.getPayload().getPersonaConfiguration());
            MarketingOpsRemoteState state = seedState(request, start);
            validateNativeSchedules(state);
            List<RemoteDomainDueWorkItem> due = nativeDueWork(state, start, true, NATIVE_WORK_KINDS);
            MarketingOpsRemoteState next = due.isEmpty() ? state : applyDueWork(state, due, start).state();
            next = withNativeMetadata(next, hints, 1L, start, horizon);
            var response = prepareResponse(request.getRequestId(), runtimeIdentity(request.getAgent()), next, start);
            List<Map<String, Object>> evidence = nativeEvidence(hints, due, next, start);
            response.getResult().setEvidenceEvents(evidence);
            response.getResult().setDomainSystemWork(nativeWorkResult(start, start, due, evidence));
            return response;
        } catch (IllegalArgumentException invalid) {
            throw nativeRejected(request.getRequestId(), "prepare", invalid.getMessage());
        }
    }

    public RemoteDomainExecuteWorkResponse executeWork(RemoteDomainExecuteWorkRequest request) {
        if (request.getState() != null && !mapValue(mapValue(request.getState().getData()).get("nativeSimulation")).isEmpty()) {
            throw nativeRejected(request.getRequestId(), "execute-work",
                    "Marketing native tools are not enabled; legacy execute-work cannot mutate a native checkpoint.");
        }
        validateMutatingRequest(
                request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                request.getRequestId(),
                request.getOperation() == null ? null : request.getOperation().getValue(),
                request.getDomain() == null ? null : request.getDomain().getDomainId(),
                request.getIdempotencyKey(),
                request.getDeadlineEpochMs());
        MarketingOpsRemoteState current = readState(
                request.getRequestId(),
                request.getOperation().getValue(),
                request.getState());
        long referenceEpochMs = currentTimeEpochMs(request.getSentAtEpochMs(), request.getPayload().getTurnContext().getTimeWindow().getCurrentTimeEpochMs());
        MarketingOpsExecution execution;
        try {
            execution = applyDueWork(current, request.getPayload().getTurnContext().getDueWork(), referenceEpochMs);
        } catch (IllegalArgumentException invalid) {
            throw protocolException(request.getRequestId(), "execute-work", HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_due_work", "domain_rejected", false, invalid.getMessage(), Map.of());
        }
        return executeResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent()),
                execution.state(),
                referenceEpochMs,
                execution.toolResult());
    }

    public RemoteDomainStatusResponse status(RemoteDomainStatusRequest request) {
        validateProjectionRequest(
                request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                request.getRequestId(),
                request.getOperation().getValue(),
                request.getDomain() == null ? null : request.getDomain().getDomainId(),
                request.getDeadlineEpochMs());
        MarketingOpsRemoteState state = readState(request.getRequestId(), request.getOperation().getValue(), request.getState());
        RemoteDomainResult result = result(runtimeIdentity(request.getAgent()), state, request.getSentAtEpochMs());
        return new RemoteDomainStatusResponse(
                RemoteDomainStatusResponse.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                request.getRequestId(),
                RemoteDomainStatusResponse.OperationEnum.STATUS,
                result,
                List.of());
    }

    public RemoteDomainInspectionResponse inspection(RemoteDomainInspectionRequest request) {
        validateProjectionRequest(
                request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                request.getRequestId(),
                request.getOperation().getValue(),
                request.getDomain() == null ? null : request.getDomain().getDomainId(),
                request.getDeadlineEpochMs());
        MarketingOpsRemoteState state = readState(request.getRequestId(), request.getOperation().getValue(), request.getState());
        RemoteDomainResult result = result(runtimeIdentity(request.getAgent()), state, request.getSentAtEpochMs());
        return new RemoteDomainInspectionResponse(
                RemoteDomainInspectionResponse.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                request.getRequestId(),
                RemoteDomainInspectionResponse.OperationEnum.INSPECTION,
                result,
                List.of());
    }

    public RemoteDomainShutdownResponse shutdown(RemoteDomainShutdownRequest request) {
        validateProjectionRequest(
                request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                request.getRequestId(),
                request.getOperation().getValue(),
                request.getDomain() == null ? null : request.getDomain().getDomainId(),
                request.getDeadlineEpochMs());
        return new RemoteDomainShutdownResponse(
                RemoteDomainShutdownResponse.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                request.getRequestId(),
                RemoteDomainShutdownResponse.OperationEnum.SHUTDOWN,
                Boolean.TRUE);
    }

    private void validatePrepareRequest(RemoteDomainPrepareRequest request, boolean nativeRequest) {
        validateMutatingRequest(
                request.getProtocolVersion() == null ? null : request.getProtocolVersion().getValue(),
                request.getRequestId(),
                request.getOperation() == null ? null : request.getOperation().getValue(),
                request.getDomain() == null ? null : request.getDomain().getDomainId(),
                request.getIdempotencyKey(),
                nativeRequest ? null : request.getDeadlineEpochMs());
        if (request.getPayload() == null) {
            throw protocolException(
                    request.getRequestId(),
                    "prepare",
                    HttpStatus.BAD_REQUEST,
                    "missing_payload",
                    "invalid_request",
                    false,
                    "Prepare payload is required.",
                    Map.of("field", "payload"));
        }
        if (!StringUtils.hasText(personaName(request.getAgent(), request.getPayload()))) {
            throw protocolException(
                    request.getRequestId(),
                    "prepare",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported_persona_configuration",
                    "domain_rejected",
                    false,
                    "marketing-ops remote domain requires a personaName seed.",
                    Map.of("field", "agent.personaName"));
        }
        validateDomainConfig(request.getRequestId(), request.getPayload().getPersonaConfiguration());
    }

    private void validateMutatingRequest(
            String protocolVersion,
            UUID requestId,
            String operation,
            String domainId,
            String idempotencyKey,
            Long deadlineEpochMs) {
        validateBaseRequest(protocolVersion, requestId, operation, domainId, deadlineEpochMs);
        if (!StringUtils.hasText(idempotencyKey)) {
            throw protocolException(
                    requestId,
                    operation,
                    HttpStatus.BAD_REQUEST,
                    "missing_idempotency_key",
                    "invalid_request",
                    false,
                    "Mutating requests must include idempotencyKey.",
                    Map.of("field", "idempotencyKey"));
        }
    }

    private void validateProjectionRequest(
            String protocolVersion,
            UUID requestId,
            String operation,
            String domainId,
            Long deadlineEpochMs) {
        validateBaseRequest(protocolVersion, requestId, operation, domainId, deadlineEpochMs);
    }

    private void validateBaseRequest(
            String protocolVersion,
            UUID requestId,
            String operation,
            String domainId,
            Long deadlineEpochMs) {
        if (!PROTOCOL_VERSION.equals(trim(protocolVersion))) {
            throw protocolException(
                    requestId,
                    fallbackOperation(operation),
                    HttpStatus.BAD_REQUEST,
                    "unsupported_protocol_version",
                    "unsupported_protocol_version",
                    false,
                    "Unsupported protocolVersion '%s'.".formatted(trim(protocolVersion)),
                    Map.of("expected", PROTOCOL_VERSION));
        }
        if (!DOMAIN_ID.equals(trim(domainId))) {
            throw protocolException(
                    requestId,
                    fallbackOperation(operation),
                    HttpStatus.BAD_REQUEST,
                    "invalid_domain",
                    "invalid_request",
                    false,
                    "Remote domain '%s' does not serve domainId '%s'.".formatted(DOMAIN_ID, trim(domainId)),
                    Map.of("expectedDomainId", DOMAIN_ID));
        }
        if (deadlineEpochMs != null && deadlineEpochMs < Instant.now().toEpochMilli()) {
            throw protocolException(
                    requestId,
                    fallbackOperation(operation),
                    HttpStatus.REQUEST_TIMEOUT,
                    "deadline_exceeded",
                    "deadline_exceeded",
                    true,
                    "Request deadline was already exceeded before processing started.",
                    Map.of("deadlineEpochMs", deadlineEpochMs));
        }
    }

    private MarketingOpsRemoteState seedState(RemoteDomainPrepareRequest request, long preparedAtEpochMs) {
        RemoteDomainPrepareRequestAllOfPayload payload = request.getPayload();
        Map<String, Object> personaConfiguration = payload.getPersonaConfiguration() == null
                ? Map.of()
                : payload.getPersonaConfiguration();
        Map<String, Object> domainConfig = mapValue(personaConfiguration.get("domainConfig"));
        List<String> traits = stringList(personaConfiguration.get("traits"));
        List<Map<String, Object>> goals = mapList(personaConfiguration.get("goals"));
        List<Map<String, Object>> scheduledWork = mapList(personaConfiguration.get("scheduledWork"));
        List<Map<String, Object>> actions = mapList(personaConfiguration.get("actions"));
        List<String> tools = stringList(personaConfiguration.get("tools"));
        String personaName = personaName(request.getAgent(), payload);
        String focusArea = configValue(domainConfig, "focusArea", FOCUS_AREAS, firstSegment(traits));
        String queueMode = configValue(domainConfig, "queueMode", QUEUE_MODES, queueMode(traits, tools));
        String campaignKey = sanitizeId(firstNonBlank(
                trim(domainConfig.get("campaignKey")),
                firstCampaignTrait(traits, goals, personaName)));
        int seedApprovalCount = positiveInteger(domainConfig.get("seedApprovalCount"), 2);

        List<MarketingOpsApproval> pendingApprovals =
                seedApprovals(campaignKey, focusArea, queueMode, seedApprovalCount, preparedAtEpochMs);

        List<MarketingOpsRecurringAction> recurringActions = !scheduledWork.isEmpty()
                ? scheduledWork.stream()
                        .map(this::toRecurringScheduledWork)
                        .toList()
                : actions.isEmpty()
                        ? List.of(new MarketingOpsRecurringAction(
                                "Daily marketing-ops queue sweep",
                                "DAILY",
                                "09:00",
                                "TASK"))
                        : actions.stream()
                                .map(action -> new MarketingOpsRecurringAction(
                                        trim(action.get("description")),
                                        upper(trim(action.get("frequency")), "DAILY"),
                                        trim(action.get("time")),
                                        upper(trim(action.get("kind")), "ACTION")))
                                .toList();

        List<MarketingOpsActivity> activityLog = List.of(new MarketingOpsActivity(
                preparedAtEpochMs,
                "prepare",
                "Seeded %d pending approval items for %s (%s).".formatted(
                        pendingApprovals.size(),
                        personaName,
                        campaignKey)));

        return new MarketingOpsRemoteState(
                personaName,
                campaignKey,
                focusArea,
                queueMode,
                seedApprovalCount,
                0,
                "prepared_remote_domain_state",
                preparedAtEpochMs,
                pendingApprovals,
                recurringActions,
                activityLog,
                Map.of(),
                Map.of());
    }

    private MarketingOpsExecution applyDueWork(
            MarketingOpsRemoteState current,
            List<RemoteDomainDueWorkItem> dueWork,
            long referenceEpochMs) {
        List<MarketingOpsApproval> approvals = new ArrayList<>(current.pendingApprovals());
        int completedWorkCount = current.completedWorkCount();
        List<MarketingOpsActivity> activityLog = new ArrayList<>(current.activityLog());
        String lastAction = "awaiting_host_due_work";
        Map<String, Object> lastToolResult = Map.of();
        Map<String, Object> selectedReview = current.selectedReview();

        for (RemoteDomainDueWorkItem item : dueWork == null ? List.<RemoteDomainDueWorkItem>of() : dueWork) {
            completedWorkCount += 1;
            if (isToolCall(item)) {
                MarketingOpsToolOutcome outcome = applyToolCall(current, approvals, item, referenceEpochMs);
                approvals = new ArrayList<>(outcome.approvals());
                lastAction = trim(outcome.result().get("message"));
                lastToolResult = outcome.result();
                activityLog.add(new MarketingOpsActivity(
                        referenceEpochMs,
                        "tool-call",
                        "%s: %s".formatted(trim(outcome.result().get("toolName")), lastAction)));
                continue;
            }

            String actionType = upper(trim(item.getPayload() != null ? item.getPayload().get("actionType") : null), item.getWorkType());
            String reviewFinding = reviewFinding(item.getPayload());
            Map<String, Object> facts = reviewFacts(item.getPayload());
            String approvalId = trim(item.getPayload() == null ? null : item.getPayload().get("approvalId"));
            int targetIndex = scheduledApprovalIndex(approvals, approvalId);
            if (!approvals.isEmpty()) {
                MarketingOpsApproval target = approvals.get(targetIndex);
                if (actionType.contains("ESCALATE") || actionType.contains("BLOCKER") || "escalation".equals(current.queueMode())) {
                    approvals.set(targetIndex, target.withState(
                            "BLOCKED_EXTERNAL",
                            "await_external_partner",
                            referenceEpochMs,
                            firstNonBlank(reviewFinding, target.summary())));
                    lastAction = (StringUtils.hasText(reviewFinding)
                            ? "Recorded an external launch blocker for campaign %s."
                            : "Escalated launch blocker for campaign %s.").formatted(target.campaignId());
                } else if (actionType.contains("REVIEW") || "reviewer".equals(current.queueMode())) {
                    approvals.remove(targetIndex);
                    lastAction = "Approved queued review for campaign %s.".formatted(target.campaignId());
                } else {
                    approvals.set(targetIndex, target.withState(
                            "READY_FOR_LAUNCH",
                            "launch_when_capacity_clears",
                            referenceEpochMs,
                            firstNonBlank(reviewFinding, target.summary())));
                    lastAction = "Advanced launch-readiness checklist for campaign %s.".formatted(target.campaignId());
                }
                if (StringUtils.hasText(reviewFinding)) {
                    lastAction += " Completed review finding: " + reviewFinding;
                }
                MarketingOpsApproval updated = approvals.stream()
                        .filter(approval -> approval.approvalId().equals(target.approvalId())).findFirst().orElse(null);
                String approvalStatus = updated == null ? "APPROVED"
                        : "BLOCKED_EXTERNAL".equals(updated.state()) ? "BLOCKED"
                        : "READY_FOR_LAUNCH".equals(updated.state()) ? "READY_FOR_LAUNCH" : "PENDING_REVIEW";
                String statedApproval = (String) facts.get("launchApprovalStatus");
                if (!Set.of("UNREPORTED", "UNKNOWN").contains(statedApproval) && !approvalStatus.equals(statedApproval)) {
                    throw new IllegalArgumentException("Review launchApprovalStatus conflicts with the scheduled approval transition");
                }
                selectedReview = new LinkedHashMap<>(Map.of(
                        "approvalId", target.approvalId(), "campaignKey", target.campaignId(),
                        "queueState", updated == null ? "clear" : queueState(List.of(updated)),
                        "reviewSummary", firstNonBlank(reviewFinding, target.summary()),
                        "reviewFacts", facts, "reviewedAtEpochMs", referenceEpochMs));
            } else {
                lastAction = "Processed host due work with no pending approvals remaining.";
            }
            activityLog.add(new MarketingOpsActivity(
                    referenceEpochMs,
                    item.getWorkType(),
                    "%s (%s)".formatted(firstNonBlank(item.getDescription(), "Processed scheduled work"), item.getOccurrenceKey())));
        }

        if (activityLog.isEmpty()) {
            activityLog = List.of();
        }
        if (StringUtils.hasText(lastAction) && (dueWork == null || dueWork.isEmpty())) {
            activityLog.add(new MarketingOpsActivity(referenceEpochMs, "heartbeat", "Host tick arrived without due work."));
        }

        MarketingOpsRemoteState next = new MarketingOpsRemoteState(
                current.personaName(),
                current.campaignKey(),
                current.focusArea(),
                current.queueMode(),
                current.seedApprovalCount(),
                completedWorkCount,
                lastAction,
                referenceEpochMs,
                approvals,
                current.recurringActions(),
                tail(activityLog, 8),
                current.nativeSimulation(),
                selectedReview);
        return new MarketingOpsExecution(next, lastToolResult);
    }

    private int scheduledApprovalIndex(List<MarketingOpsApproval> approvals, String approvalId) {
        if (!StringUtils.hasText(approvalId)) return 0;
        for (int index = 0; index < approvals.size(); index++) {
            if (approvalId.equals(approvals.get(index).approvalId())) return index;
        }
        throw new IllegalArgumentException("Scheduled work approvalId must identify an existing pending approval");
    }

    private MarketingOpsRemoteState readState(UUID requestId, String operation, RemoteDomainOpaqueState opaqueState) {
        if (opaqueState == null) {
            throw protocolException(
                    requestId,
                    operation,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "missing_state",
                    "domain_rejected",
                    false,
                    "Opaque state is required for this operation.",
                    Map.of("field", "state"));
        }
        if (!SCHEMA_VERSION.equals(trim(opaqueState.getSchemaVersion()))) {
            throw protocolException(
                    requestId,
                    operation,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "unsupported_state_schema",
                    "domain_rejected",
                    false,
                    "Unsupported opaque state schema '%s'.".formatted(trim(opaqueState.getSchemaVersion())),
                    Map.of("expectedSchemaVersion", SCHEMA_VERSION));
        }
        Map<String, Object> stateData = new LinkedHashMap<>(mapValue(opaqueState.getData()));
        if (stateData.containsKey("selectedReview")) {
            try {
                stateData.put("selectedReview", normalizeSelectedReview(stateData.get("selectedReview")));
            } catch (IllegalArgumentException invalid) {
                throw protocolException(requestId, operation, HttpStatus.UNPROCESSABLE_ENTITY, "invalid_state",
                        "domain_rejected", false, invalid.getMessage(), Map.of("field", "state.data.selectedReview"));
            }
        }
        MarketingOpsRemoteState state = objectMapper.convertValue(stateData, MarketingOpsRemoteState.class);
        if (state == null || !StringUtils.hasText(state.personaName())) {
            throw protocolException(
                    requestId,
                    operation,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_state",
                    "domain_rejected",
                    false,
                    "Opaque state could not be decoded into the marketing-ops state model.",
                    Map.of("schemaVersion", SCHEMA_VERSION));
        }
        return state.normalized();
    }

    private Map<String, Object> normalizeSelectedReview(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Persisted selectedReview must be an object");
        }
        Map<String, Object> selected = mapValue(value);
        if (selected.isEmpty()) return Map.of();
        if (!Set.of("approvalId", "campaignKey", "queueState", "reviewSummary", "reviewFacts", "reviewedAtEpochMs")
                .containsAll(selected.keySet())) {
            throw new IllegalArgumentException("Persisted selectedReview contains unsupported fields");
        }
        for (String field : List.of("approvalId", "campaignKey", "reviewSummary")) {
            if (!(selected.get(field) instanceof String text) || text.isBlank() || text.length() > 1_200) {
                throw new IllegalArgumentException("Persisted selectedReview " + field + " must be a nonblank bounded string");
            }
        }
        if (!(selected.get("queueState") instanceof String queue)
                || !Set.of("clear", "blocked", "review_pending", "active").contains(queue)) {
            throw new IllegalArgumentException("Persisted selectedReview queueState is invalid");
        }
        long at = nativeLong(selected, "reviewedAtEpochMs");
        if (at < 0 || at > MAX_NATIVE_TIME) {
            throw new IllegalArgumentException("Persisted selectedReview reviewedAtEpochMs is outside the supported time range");
        }
        if (!selected.containsKey("reviewFacts")) {
            throw new IllegalArgumentException("Persisted selectedReview requires reviewFacts");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(selected);
        normalized.put("reviewFacts", reviewFacts(selected));
        return normalized;
    }

    private RemoteDomainPrepareResponse prepareResponse(
            UUID requestId,
            String runtimeIdentity,
            MarketingOpsRemoteState state,
            long referenceEpochMs) {
        return new RemoteDomainPrepareResponse(
                RemoteDomainPrepareResponse.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                requestId,
                RemoteDomainPrepareResponse.OperationEnum.PREPARE,
                result(runtimeIdentity, state, referenceEpochMs),
                opaqueState(state),
                List.of());
    }

    private RemoteDomainExecuteWorkResponse executeResponse(
            UUID requestId,
            String runtimeIdentity,
            MarketingOpsRemoteState state,
            long referenceEpochMs) {
        return executeResponse(requestId, runtimeIdentity, state, referenceEpochMs, Map.of());
    }

    private RemoteDomainExecuteWorkResponse executeResponse(
            UUID requestId,
            String runtimeIdentity,
            MarketingOpsRemoteState state,
            long referenceEpochMs,
            Map<String, Object> toolResult) {
        RemoteDomainResult result = result(runtimeIdentity, state, referenceEpochMs);
        if (toolResult != null && !toolResult.isEmpty()) {
            result.setToolResult(new LinkedHashMap<>(toolResult));
        }
        return new RemoteDomainExecuteWorkResponse(
                RemoteDomainExecuteWorkResponse.ProtocolVersionEnum.REMOTE_DOMAIN_V1,
                requestId,
                RemoteDomainExecuteWorkResponse.OperationEnum.EXECUTE_WORK,
                result,
                opaqueState(state),
                List.of());
    }

    private RemoteDomainResult result(
            String runtimeIdentity,
            MarketingOpsRemoteState state,
            long referenceEpochMs) {
        return new RemoteDomainResult(projection(state, referenceEpochMs)).runtimeIdentity(runtimeIdentity);
    }

    private RemoteDomainProjection projection(MarketingOpsRemoteState state, long referenceEpochMs) {
        if (!state.nativeSimulation().isEmpty()) referenceEpochMs = nativeLong(state.nativeSimulation(), "processedThroughEpochMs");
        RemoteDomainProjection projection = new RemoteDomainProjection();
        projection.setRuntimeMetadata(new LinkedHashMap<>(Map.of(
                "domainProfile", "marketing-ops-remote",
                "campaignKey", state.campaignKey(),
                "focusArea", state.focusArea(),
                "queueMode", state.queueMode(),
                "proofMode", "protocol-native",
                "queueDepth", state.pendingApprovals().size(),
                "seedApprovalCount", state.seedApprovalCount(),
                "completedWorkCount", state.completedWorkCount())));
        if (!state.nativeSimulation().isEmpty()) {
            Map<String, Object> nativeResult = new LinkedHashMap<>(state.nativeSimulation());
            nativeResult.put("observation", Map.of(
                    "domainId", DOMAIN_ID, "observedAtEpochMs", referenceEpochMs,
                    "campaignKey", state.campaignKey(), "queueState", queueState(state.pendingApprovals()),
                    "pendingApprovals", state.pendingApprovals().stream().map(MarketingOpsApproval::asMap).toList(),
                    "completedWorkCount", state.completedWorkCount(), "lastAction", state.lastAction(),
                    "selectedReview", selectedReviewProjection(state)));
            projection.getRuntimeMetadata().put("nativeSimulation", nativeResult);
        }
        projection.setLifecycleSchedule(scheduleProjection(state, referenceEpochMs, false));
        projection.setInspectionSchedule(scheduleProjection(state, referenceEpochMs, true));
        projection.setStatusView(new LinkedHashMap<>(Map.of(
                "campaignKey", state.campaignKey(),
                "queueState", queueState(state.pendingApprovals()),
                "pendingApprovals", state.pendingApprovals().size(),
                "blockedApprovals", blockedApprovals(state.pendingApprovals()),
                "completedWorkCount", state.completedWorkCount(),
                "lastAction", state.lastAction(),
                "focusArea", state.focusArea(),
                "queueMode", state.queueMode())));
        projection.setInspectionView(new LinkedHashMap<>(Map.of(
                "campaignKey", state.campaignKey(),
                "pendingApprovals", state.pendingApprovals().stream()
                        .map(MarketingOpsApproval::asMap)
                        .toList(),
                "recurringActions", state.recurringActions().stream()
                        .map(MarketingOpsRecurringAction::asMap)
                        .toList(),
                "activityLog", state.activityLog().stream()
                        .map(MarketingOpsActivity::asMap)
                        .toList(),
                "seedApprovalCount", state.seedApprovalCount(),
                "queueMode", state.queueMode(),
                "lastEvaluatedAtEpochMs", state.lastActionAtEpochMs())));
        projection.getStatusView().put("selectedReview", selectedReviewProjection(state));
        projection.getInspectionView().put("selectedReview", selectedReviewProjection(state));
        return projection;
    }

    private Map<String, Object> selectedReviewProjection(MarketingOpsRemoteState state) {
        if (!state.selectedReview().isEmpty()) return state.selectedReview();
        if (state.pendingApprovals().isEmpty()) return Map.of("reviewFacts", reviewFacts(Map.of()));
        MarketingOpsApproval approval = state.pendingApprovals().getFirst();
        return new LinkedHashMap<>(Map.of("approvalId", approval.approvalId(), "campaignKey", approval.campaignId(),
                "queueState", queueState(List.of(approval)), "reviewSummary", approval.summary(),
                "reviewFacts", reviewFacts(Map.of())));
    }

    private List<RemoteDomainScheduleItem> scheduleProjection(
            MarketingOpsRemoteState state,
            long referenceEpochMs,
            boolean detailed) {
        return state.recurringActions().stream()
                .map(action -> {
                    RemoteDomainScheduleItem item = new RemoteDomainScheduleItem();
                    item.setDescription(action.description());
                    item.setType(action.actionType());
                    item.setFrequency(action.frequency());
                    if (detailed) {
                        item.setNextExecution(state.nativeSimulation().isEmpty() ? nextExecution(referenceEpochMs, action.time())
                                : Instant.ofEpochMilli(nextNativeOccurrence(action, referenceEpochMs, false)).atOffset(ZoneOffset.UTC));
                    }
                    return item;
                })
                .sorted(Comparator.comparing(item -> firstNonBlank(item.getDescription(), "")))
                .toList();
    }

    private RemoteDomainToolDescriptor tool(String name, String description, Map<String, Object> inputSchema) {
        RemoteDomainToolDescriptor descriptor = new RemoteDomainToolDescriptor(name);
        descriptor.setDescription(description);
        descriptor.setInputSchema(inputSchema == null ? Map.of() : new LinkedHashMap<>(inputSchema));
        return descriptor;
    }

    private boolean isToolCall(RemoteDomainDueWorkItem item) {
        Map<String, Object> payload = item != null ? mapValue(item.getPayload()) : Map.of();
        return "TOOL_CALL".equalsIgnoreCase(trim(item != null ? item.getWorkType() : null))
                || "tool-call".equalsIgnoreCase(trim(payload.get("kind")))
                || StringUtils.hasText(trim(payload.get("toolName")));
    }

    private MarketingOpsToolOutcome applyToolCall(
            MarketingOpsRemoteState current,
            List<MarketingOpsApproval> approvals,
            RemoteDomainDueWorkItem item,
            long referenceEpochMs) {
        Map<String, Object> payload = item != null ? mapValue(item.getPayload()) : Map.of();
        String toolName = trim(payload.get("toolName"));
        Map<String, Object> arguments = mapValue(payload.get("arguments"));
        return switch (toolName) {
            case "get_campaign_brief" -> new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, true, "Campaign brief retrieved.", Map.of(
                            "campaignKey", firstNonBlank(trim(arguments.get("campaignKey")), current.campaignKey()),
                            "stage", queueState(approvals),
                            "focusArea", current.focusArea(),
                            "queueMode", current.queueMode(),
                            "pendingApprovals", approvals.size(),
                            "blockedApprovals", blockedApprovals(approvals),
                            "lastAction", current.lastAction())));
            case "list_launch_blockers" -> new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, true, "Launch blockers listed.", Map.of(
                            "campaignKey", firstNonBlank(trim(arguments.get("campaignKey")), current.campaignKey()),
                            "blockers", approvals.stream()
                                    .map(this::toLaunchBlocker)
                                    .toList())));
            case "get_channel_capacity" -> new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, true, "Channel capacity retrieved.", Map.of(
                            "channel", firstNonBlank(trim(arguments.get("channel")), "email"),
                            "status", approvals.size() > 1 ? "tight" : approvals.isEmpty() ? "available" : "constrained",
                            "openSlots", Math.max(0, 2 - approvals.size()),
                            "pendingApprovals", approvals.size())));
            case "get_review_queue" -> new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, true, "Review queue retrieved.", reviewQueuePayload(approvals, arguments)));
            case "post_review_decision" -> applyReviewDecisionToolCall(toolName, approvals, arguments, referenceEpochMs);
            default -> new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, false, "Unsupported marketing-ops remote tool.", Map.of()));
        };
    }

    private MarketingOpsToolOutcome applyReviewDecisionToolCall(
            String toolName,
            List<MarketingOpsApproval> approvals,
            Map<String, Object> arguments,
            long referenceEpochMs) {
        if (approvals.isEmpty()) {
            return new MarketingOpsToolOutcome(
                    List.of(),
                    toolResult(toolName, false, "No review blockers are queued.", Map.of()));
        }

        String blockerId = trim(arguments.get("blockerId"));
        String decision = upper(trim(arguments.get("decision")), "APPROVE");
        String note = trim(arguments.get("note"));
        int targetIndex = findApprovalIndex(approvals, blockerId);
        MarketingOpsApproval target = approvals.get(targetIndex);
        List<MarketingOpsApproval> nextApprovals = new ArrayList<>(approvals);
        String resultingStage;

        if ("APPROVE".equals(decision)) {
            nextApprovals.remove(targetIndex);
            resultingStage = nextApprovals.isEmpty() ? "ready-for-launch" : "launch-readiness";
        } else if ("REQUEST_CHANGES".equals(decision)) {
            nextApprovals.set(targetIndex, target.withState("CHANGES_REQUESTED", "address_reviewer_feedback", referenceEpochMs));
            resultingStage = target.lane() + "-rework";
        } else if ("ESCALATE".equals(decision)) {
            nextApprovals.set(targetIndex, target.withState("BLOCKED_EXTERNAL", "senior_review_required", referenceEpochMs));
            resultingStage = target.lane() + "-escalation";
        } else {
            return new MarketingOpsToolOutcome(
                    List.copyOf(approvals),
                    toolResult(toolName, false, "Decision must be APPROVE, REQUEST_CHANGES, or ESCALATE.", Map.of(
                            "decision", decision)));
        }

        return new MarketingOpsToolOutcome(
                List.copyOf(nextApprovals),
                toolResult(toolName, true, "Review decision applied.", Map.of(
                        "blockerId", target.approvalId(),
                        "decision", decision,
                        "resultingStage", resultingStage,
                        "note", note,
                        "remainingApprovals", nextApprovals.size())));
    }

    private int findApprovalIndex(List<MarketingOpsApproval> approvals, String blockerId) {
        if (StringUtils.hasText(blockerId)) {
            for (int index = 0; index < approvals.size(); index++) {
                if (blockerId.equals(approvals.get(index).approvalId())) {
                    return index;
                }
            }
        }
        return 0;
    }

    private Map<String, Object> reviewQueuePayload(
            List<MarketingOpsApproval> approvals,
            Map<String, Object> arguments) {
        String requestedLane = trim(arguments.get("reviewLane"));
        List<MarketingOpsApproval> scoped = StringUtils.hasText(requestedLane)
                ? approvals.stream()
                        .filter(approval -> requestedLane.equalsIgnoreCase(approval.lane()))
                        .toList()
                : approvals;
        return Map.of(
                "reviewLane", StringUtils.hasText(requestedLane) ? requestedLane : "all",
                "queuedItems", scoped.size(),
                "highestPriorityItem", scoped.isEmpty() ? "none" : scoped.getFirst().summary(),
                "slaRisk", scoped.size() > 1 ? "elevated" : scoped.isEmpty() ? "clear" : "watch");
    }

    private Map<String, Object> toLaunchBlocker(MarketingOpsApproval approval) {
        return Map.of(
                "blockerId", approval.approvalId(),
                "campaignKey", approval.campaignId(),
                "severity", "BLOCKED_EXTERNAL".equals(approval.state()) ? "high" : "medium",
                "reviewLane", approval.lane(),
                "summary", approval.summary(),
                "nextAction", approval.nextAction(),
                "state", approval.state());
    }

    private Map<String, Object> toolResult(
            String toolName,
            boolean success,
            String message,
            Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolName", StringUtils.hasText(toolName) ? toolName : "unknown");
        result.put("success", success);
        result.put("message", message);
        result.putAll(details == null ? Map.of() : details);
        return result;
    }

    private void validateDomainConfig(UUID requestId, Map<String, Object> personaConfiguration) {
        Map<String, Object> domainConfig = mapValue(personaConfiguration != null
                ? personaConfiguration.get("domainConfig")
                : null);
        List<Map<String, Object>> scheduledWork = mapList(personaConfiguration != null
                ? personaConfiguration.get("scheduledWork")
                : null);
        validateScheduledWork(requestId, scheduledWork);
        if (domainConfig.isEmpty()) {
            if (scheduledWork.isEmpty()) {
                return;
            }
        }
        if (!domainConfig.isEmpty()) {
            if (!StringUtils.hasText(trim(domainConfig.get("campaignKey")))) {
                throw protocolException(
                        requestId,
                        "prepare",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "missing_campaign_key",
                        "domain_rejected",
                        false,
                        "marketing-ops domainConfig requires a non-empty campaignKey.",
                        Map.of("field", "payload.personaConfiguration.domainConfig.campaignKey"));
            }
            validateConfigEnum(requestId, "focusArea", domainConfig.get("focusArea"), FOCUS_AREAS);
            validateConfigEnum(requestId, "queueMode", domainConfig.get("queueMode"), QUEUE_MODES);
            if (domainConfig.containsKey("seedApprovalCount") && positiveInteger(domainConfig.get("seedApprovalCount"), -1) < 1) {
                throw protocolException(
                        requestId,
                        "prepare",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_seed_approval_count",
                        "domain_rejected",
                        false,
                        "marketing-ops domainConfig seedApprovalCount must be a positive integer.",
                        Map.of("field", "payload.personaConfiguration.domainConfig.seedApprovalCount"));
            }
        }
    }

    private void validateScheduledWork(UUID requestId, List<Map<String, Object>> scheduledWork) {
        if (scheduledWork == null || scheduledWork.isEmpty()) {
            return;
        }
        for (int index = 0; index < scheduledWork.size(); index++) {
            Map<String, Object> item = scheduledWork.get(index);
            String kind = trim(item.get("kind"));
            if (!"task".equals(kind) && !"action".equals(kind)) {
                throw protocolException(
                        requestId,
                        "prepare",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_scheduled_work_kind",
                        "domain_rejected",
                        false,
                        "marketing-ops scheduledWork kind must be either 'task' or 'action'.",
                        Map.of("field", "payload.personaConfiguration.scheduledWork[" + index + "].kind"));
            }
            if (!StringUtils.hasText(trim(item.get("description")))) {
                throw protocolException(
                        requestId,
                        "prepare",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "missing_scheduled_work_description",
                        "domain_rejected",
                        false,
                        "marketing-ops scheduledWork items require a description.",
                        Map.of("field", "payload.personaConfiguration.scheduledWork[" + index + "].description"));
            }
        }
    }

    private void validateConfigEnum(UUID requestId, String field, Object value, Set<String> allowedValues) {
        String normalized = trim(value);
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        if (allowedValues.contains(normalized)) {
            return;
        }
        throw protocolException(
                requestId,
                "prepare",
                HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_%s".formatted(field),
                "domain_rejected",
                false,
                "marketing-ops domainConfig field '%s' must be one of %s.".formatted(field, allowedValues),
                Map.of("field", "payload.personaConfiguration.domainConfig." + field));
    }

    private List<MarketingOpsApproval> seedApprovals(
            String campaignKey,
            String focusArea,
            String queueMode,
            int seedApprovalCount,
            long preparedAtEpochMs) {
        List<MarketingOpsApproval> approvals = new ArrayList<>();
        for (int index = 0; index < Math.max(seedApprovalCount, 1); index++) {
            if (index == 0) {
                approvals.add(new MarketingOpsApproval(
                        campaignKey + "-review",
                        campaignKey,
                        "creative-review".equals(focusArea) ? "IN_REVIEW" : "PENDING_REVIEW",
                        "Review asset package before launch window closes.",
                        "reviewer".equals(queueMode) ? "approve_or_request_changes" : "review_asset_package",
                        "creative-review".equals(focusArea) ? "creative" : "campaign",
                        preparedAtEpochMs));
                continue;
            }
            if (index == 1) {
                approvals.add(new MarketingOpsApproval(
                        campaignKey + "-launch",
                        campaignKey,
                        "escalation".equals(queueMode) ? "BLOCKED_EXTERNAL" : "READY_FOR_CHECKLIST",
                        "Confirm launch readiness and unblock cross-functional dependencies.",
                        "escalation".equals(queueMode) ? "escalate_external_partner" : "run_readiness_check",
                        "launch-ops",
                        preparedAtEpochMs));
                continue;
            }
            approvals.add(new MarketingOpsApproval(
                    campaignKey + "-followup-" + index,
                    campaignKey,
                    "PENDING_REVIEW",
                    "Resolve follow-up approval item #%d before the campaign can launch.".formatted(index),
                    "reviewer".equals(queueMode) ? "approve_followup" : "review_followup_item",
                    focusArea,
                    preparedAtEpochMs));
        }
        return List.copyOf(approvals);
    }

    private RemoteDomainUiProfile marketingOpsUiProfile() {
        RemoteDomainUiField campaignKey = new RemoteDomainUiField(
                "campaignKey",
                "campaignKey",
                "Campaign Key",
                RemoteDomainUiField.ControlEnum.TEXT);
        campaignKey.setRequired(Boolean.TRUE);
        campaignKey.setHelpText("Stable identifier used to seed launch approvals and status metadata.");
        campaignKey.setOptions(List.of());

        RemoteDomainUiField focusArea = new RemoteDomainUiField(
                "focusArea",
                "focusArea",
                "Focus Area",
                RemoteDomainUiField.ControlEnum.SELECT);
        focusArea.setRequired(Boolean.TRUE);
        focusArea.setHelpText("Choose the operating lane this persona should optimize first.");
        focusArea.setDefaultValue(stringDefaultValue("campaign-ops"));
        focusArea.setOptions(List.of(
                new RemoteDomainUiOption("campaign-ops", "Campaign Ops"),
                new RemoteDomainUiOption("creative-review", "Creative Review"),
                new RemoteDomainUiOption("launch-ops", "Launch Ops")));

        RemoteDomainUiField queueMode = new RemoteDomainUiField(
                "queueMode",
                "queueMode",
                "Queue Mode",
                RemoteDomainUiField.ControlEnum.SELECT);
        queueMode.setRequired(Boolean.TRUE);
        queueMode.setHelpText("Sets the default decision posture for queued approvals.");
        queueMode.setDefaultValue(stringDefaultValue("readiness"));
        queueMode.setOptions(List.of(
                new RemoteDomainUiOption("readiness", "Readiness"),
                new RemoteDomainUiOption("reviewer", "Reviewer"),
                new RemoteDomainUiOption("escalation", "Escalation")));

        RemoteDomainUiField seedApprovalCount = new RemoteDomainUiField(
                "seedApprovalCount",
                "seedApprovalCount",
                "Seed Approval Count",
                RemoteDomainUiField.ControlEnum.NUMBER);
        seedApprovalCount.setRequired(Boolean.FALSE);
        seedApprovalCount.setHelpText("How many approval items should be queued initially for this persona.");
        seedApprovalCount.setDefaultValue(numberDefaultValue(2));
        seedApprovalCount.setOptions(List.of());

        RemoteDomainUiCreatorSection section = new RemoteDomainUiCreatorSection(
                "campaign-configuration",
                "Campaign Configuration",
                List.of(campaignKey, focusArea, queueMode, seedApprovalCount));
        section.setDescription("These fields define the initial approval queue the remote domain will prepare.");

        RemoteDomainUiProfile profile = new RemoteDomainUiProfile(RemoteDomainUiProfile.KindEnum.SCHEMA_FORM_V1);
        profile.setSchemaVersion("domain-ui-profile.v1");
        profile.setTitle("Marketing Operations Setup");
        profile.setDescription("Capture campaign-specific launch posture without forcing this domain through account seeding.");
        profile.setCreatorSections(List.of(section));
        return profile;
    }

    private RemoteDomainUiFieldDefaultValue stringDefaultValue(String value) {
        RemoteDomainUiFieldDefaultValue defaultValue = new RemoteDomainUiFieldDefaultValue();
        defaultValue.setKind(RemoteDomainUiFieldDefaultValue.KindEnum.STRING);
        defaultValue.setStringValue(value);
        return defaultValue;
    }

    private RemoteDomainUiFieldDefaultValue numberDefaultValue(Number value) {
        RemoteDomainUiFieldDefaultValue defaultValue = new RemoteDomainUiFieldDefaultValue();
        defaultValue.setKind(RemoteDomainUiFieldDefaultValue.KindEnum.NUMBER);
        defaultValue.setNumberValue(value == null ? null : new BigDecimal(String.valueOf(value)));
        return defaultValue;
    }

    private RemoteDomainOpaqueState opaqueState(MarketingOpsRemoteState state) {
        RemoteDomainOpaqueState opaqueState = new RemoteDomainOpaqueState();
        opaqueState.setSchemaVersion(SCHEMA_VERSION);
        opaqueState.setData(objectMapper.convertValue(state, MAP_TYPE));
        return opaqueState;
    }

    private String runtimeIdentity(RemoteDomainAgentContext agent) {
        String personaName = trim(agent != null ? agent.getPersonaName() : null);
        if (!StringUtils.hasText(personaName)) {
            return DOMAIN_ID + "::anonymous";
        }
        return DOMAIN_ID + "::" + personaName;
    }

    private OffsetDateTime nextExecution(long referenceEpochMs, String time) {
        ZonedDateTime now = Instant.ofEpochMilli(referenceEpochMs).atZone(ZoneOffset.UTC);
        if (!StringUtils.hasText(time)) {
            return now.plusDays(1).toOffsetDateTime();
        }
        try {
            LocalTime localTime = LocalTime.parse(time.trim());
            ZonedDateTime candidate = now.withHour(localTime.getHour())
                    .withMinute(localTime.getMinute())
                    .withSecond(0)
                    .withNano(0);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            return candidate.toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            return now.plusHours(6).toOffsetDateTime();
        }
    }

    private long currentTimeEpochMs(Long sentAtEpochMs, Long explicitCurrentTimeEpochMs) {
        return explicitCurrentTimeEpochMs != null
                ? explicitCurrentTimeEpochMs
                : sentAtEpochMs != null ? sentAtEpochMs : Instant.now().toEpochMilli();
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(this::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> objectMapper.convertValue(item, MAP_TYPE))
                .toList();
    }

    private Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return objectMapper.convertValue(map, MAP_TYPE);
    }

    private String personaName(RemoteDomainAgentContext agent, RemoteDomainPrepareRequestAllOfPayload payload) {
        String direct = trim(agent != null ? agent.getPersonaName() : null);
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        Map<String, Object> configuration = payload != null && payload.getPersonaConfiguration() != null
                ? payload.getPersonaConfiguration()
                : Map.of();
        return trim(configuration.get("personaName"));
    }

    private String firstSegment(List<String> traits) {
        return traits.stream()
                .filter(trait -> trait.startsWith("segment:"))
                .map(trait -> trait.substring("segment:".length()))
                .findFirst()
                .orElse("campaign-ops");
    }

    private String configValue(
            Map<String, Object> domainConfig,
            String field,
            Set<String> allowedValues,
            String fallback) {
        String candidate = trim(domainConfig.get(field));
        if (allowedValues.contains(candidate)) {
            return candidate;
        }
        return fallback;
    }

    private String firstCampaignTrait(List<String> traits, List<Map<String, Object>> goals, String personaName) {
        for (String trait : traits) {
            if (trait.startsWith("campaign:")) {
                return sanitizeId(trait.substring("campaign:".length()));
            }
        }
        for (Map<String, Object> goal : goals) {
            String goalId = trim(goal.get("goalId"));
            if (StringUtils.hasText(goalId)) {
                return sanitizeId(goalId);
            }
        }
        return sanitizeId(personaName);
    }

    private String queueMode(List<String> traits, List<String> tools) {
        if (traits.stream().anyMatch(trait -> trait.contains("review")) || tools.contains("post_review_decision")) {
            return "reviewer";
        }
        if (traits.stream().anyMatch(trait -> trait.contains("external") || trait.contains("creative"))
                || tools.contains("delegate_to_external_agent")) {
            return "escalation";
        }
        return "readiness";
    }

    private MarketingOpsRecurringAction toRecurringScheduledWork(Map<String, Object> item) {
        return new MarketingOpsRecurringAction(
                trim(item.get("description")),
                upper(trim(item.get("frequency")), "DAILY"),
                trim(item.get("time")),
                upper(trim(item.get("kind")), "TASK"),
                trim(item.get("workId")),
                mapValue(item.get("payload")));
    }

    private RemoteDomainScheduledWorkKind recurringTaskKind() {
        RemoteDomainScheduledWorkKind kind = new RemoteDomainScheduledWorkKind();
        kind.setKind("task");
        kind.setLabel("Recurring Marketing Task");
        kind.setDescription("Recurring queue sweeps, readiness checks, and escalation passes for marketing operations personas.");
        kind.setFields(new ArrayList<>(List.of(actionTypeField("DETERMINISTIC_TURN"), promptField())));
        return kind;
    }

    private RemoteDomainScheduledWorkKind recurringActionKind() {
        RemoteDomainScheduledWorkKind kind = new RemoteDomainScheduledWorkKind();
        kind.setKind("action");
        kind.setLabel("Recurring Marketing Action");
        kind.setDescription("Recurring prompt-style actions preserved for compatibility during migration.");
        kind.setFields(new ArrayList<>(List.of(actionTypeField("PROMPT"), promptField())));
        return kind;
    }

    private RemoteDomainUiField actionTypeField(String defaultValue) {
        RemoteDomainUiField field = new RemoteDomainUiField();
        field.setId("actionType");
        field.setPath("actionType");
        field.setLabel("Execution Strategy");
        field.setControl(RemoteDomainUiField.ControlEnum.SELECT);
        field.setRequired(Boolean.TRUE);
        field.setHelpText("Choose how the host should execute this recurring work.");
        field.setDefaultValue(stringDefaultValue(defaultValue));
        field.setOptions(new ArrayList<>(List.of(
                new RemoteDomainUiOption("PROMPT", "Prompt"),
                new RemoteDomainUiOption("DETERMINISTIC_TURN", "Deterministic Turn"))));
        return field;
    }

    private RemoteDomainUiField promptField() {
        RemoteDomainUiField field = new RemoteDomainUiField();
        field.setId("prompt");
        field.setPath("prompt");
        field.setLabel("Prompt");
        field.setControl(RemoteDomainUiField.ControlEnum.TEXTAREA);
        field.setRequired(Boolean.FALSE);
        field.setHelpText("Optional instruction used when prompt execution is selected.");
        return field;
    }

    private int positiveInteger(Object value, int fallback) {
        String candidate = trim(value);
        if (!StringUtils.hasText(candidate)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(candidate);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int blockedApprovals(List<MarketingOpsApproval> approvals) {
        return (int) approvals.stream()
                .filter(approval -> "BLOCKED_EXTERNAL".equals(approval.state()))
                .count();
    }

    private String queueState(List<MarketingOpsApproval> approvals) {
        if (approvals.isEmpty()) {
            return "clear";
        }
        if (approvals.stream().anyMatch(approval -> "BLOCKED_EXTERNAL".equals(approval.state()))) {
            return "blocked";
        }
        if (approvals.stream().anyMatch(approval -> "IN_REVIEW".equals(approval.state())
                || "PENDING_REVIEW".equals(approval.state()))) {
            return "review_pending";
        }
        return "active";
    }

    private RemoteDomainOperationDescriptor operation(
            RemoteDomainOperationDescriptor.MethodEnum method,
            String path,
            Integer timeoutMs) {
        RemoteDomainOperationDescriptor descriptor = new RemoteDomainOperationDescriptor(method, path);
        descriptor.setTimeoutMs(timeoutMs);
        return descriptor;
    }

    private RemoteDomainProtocolException protocolException(
            UUID requestId,
            String operation,
            HttpStatus status,
            String code,
            String category,
            boolean retryable,
            String message,
            Map<String, Object> details) {
        return new RemoteDomainProtocolException(
                requestId,
                fallbackOperation(operation),
                status,
                code,
                category,
                retryable,
                message,
                details);
    }

    private String fallbackOperation(String operation) {
        return StringUtils.hasText(operation) ? operation.trim() : "unknown";
    }

    private List<MarketingOpsActivity> tail(List<MarketingOpsActivity> items, int maxItems) {
        if (items.size() <= maxItems) {
            return List.copyOf(items);
        }
        return List.copyOf(items.subList(items.size() - maxItems, items.size()));
    }

    private String sanitizeId(String value) {
        String normalized = trim(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return StringUtils.hasText(normalized) ? normalized : "marketing-ops";
    }

    private String upper(String value, String fallback) {
        String candidate = trim(value);
        return StringUtils.hasText(candidate) ? candidate.toUpperCase(Locale.ROOT) : fallback;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String trim(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private record MarketingOpsExecution(
            MarketingOpsRemoteState state,
            Map<String, Object> toolResult) {

        private MarketingOpsExecution {
            toolResult = toolResult == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(toolResult));
        }
    }

    private record MarketingOpsToolOutcome(
            List<MarketingOpsApproval> approvals,
            Map<String, Object> result) {

        private MarketingOpsToolOutcome {
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
        }
    }

    private record MarketingOpsRemoteState(
            String personaName,
            String campaignKey,
            String focusArea,
            String queueMode,
            Integer seedApprovalCount,
            Integer completedWorkCount,
            String lastAction,
            Long lastActionAtEpochMs,
            List<MarketingOpsApproval> pendingApprovals,
            List<MarketingOpsRecurringAction> recurringActions,
            List<MarketingOpsActivity> activityLog,
            Map<String, Object> nativeSimulation,
            Map<String, Object> selectedReview) {

        MarketingOpsRemoteState normalized() {
            return new MarketingOpsRemoteState(
                    StringUtils.hasText(personaName) ? personaName : "Unknown Persona",
                    StringUtils.hasText(campaignKey) ? campaignKey : "marketing-ops",
                    StringUtils.hasText(focusArea) ? focusArea : "campaign-ops",
                    StringUtils.hasText(queueMode) ? queueMode : "readiness",
                    seedApprovalCount == null || seedApprovalCount < 1 ? 2 : seedApprovalCount,
                    completedWorkCount == null ? 0 : completedWorkCount,
                    StringUtils.hasText(lastAction) ? lastAction : "prepared_remote_domain_state",
                    lastActionAtEpochMs == null ? 0L : lastActionAtEpochMs,
                    pendingApprovals == null ? List.of() : List.copyOf(pendingApprovals),
                    recurringActions == null ? List.of() : List.copyOf(recurringActions),
                    activityLog == null ? List.of() : List.copyOf(activityLog),
                    nativeSimulation == null ? Map.of() : Map.copyOf(nativeSimulation),
                    selectedReview == null ? Map.of() : Map.copyOf(selectedReview));
        }
    }

    private record MarketingOpsApproval(
            String approvalId,
            String campaignId,
            String state,
            String summary,
            String nextAction,
            String lane,
            Long lastUpdatedEpochMs) {

        MarketingOpsApproval withState(String nextState, String nextAction, long updatedAt) {
            return withState(nextState, nextAction, updatedAt, summary);
        }

        MarketingOpsApproval withState(String nextState, String nextAction, long updatedAt, String nextSummary) {
            return new MarketingOpsApproval(
                    approvalId,
                    campaignId,
                    nextState,
                    nextSummary,
                    nextAction,
                    lane,
                    updatedAt);
        }

        Map<String, Object> asMap() {
            return new LinkedHashMap<>(Map.of(
                    "approvalId", approvalId,
                    "campaignId", campaignId,
                    "state", state,
                    "summary", summary,
                    "nextAction", nextAction,
                    "lane", lane,
                    "lastUpdatedEpochMs", lastUpdatedEpochMs));
        }
    }

    private record MarketingOpsRecurringAction(
            String description,
            String frequency,
            String time,
            String actionType,
            String workId,
            Map<String, Object> payload) {

        MarketingOpsRecurringAction(String description, String frequency, String time, String actionType) {
            this(description, frequency, time, actionType, "", Map.of());
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", description);
            result.put("frequency", frequency);
            result.put("actionType", actionType);
            if (StringUtils.hasText(time)) {
                result.put("time", time);
            }
            return result;
        }
    }

    private record MarketingOpsActivity(
            Long occurredAtEpochMs,
            String type,
            String message) {

        Map<String, Object> asMap() {
            return new LinkedHashMap<>(Map.of(
                    "occurredAtEpochMs", occurredAtEpochMs,
                    "type", type,
                    "message", message));
        }
    }
}
