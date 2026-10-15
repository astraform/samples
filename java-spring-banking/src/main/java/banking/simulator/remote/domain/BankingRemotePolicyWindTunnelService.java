package banking.simulator.remote.domain;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelBinaryPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelService;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelExecutionProvider;
import banking.simulator.remote.domain.scenariolab.BankingPolicyWindTunnelManifest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactMetadata;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;

@Service
public class BankingRemotePolicyWindTunnelService implements PolicyWindTunnelService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String SHARED_STORAGE_ENABLED_ENV =
            "ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED";
    private static final String WORKSPACE_ROOT_ENV = "ASTRAFORM_WORKSPACE_ROOT";
    private static final String OPPORTUNITY_CORRELATION_CAPABILITY_HEADER =
            "X-Astraform-Opportunity-Correlation-Capability";
    public static final String PROVIDER_REVISION = BankingPolicyWindTunnelProviderIdentity.revision();

    private final BankingPolicyWindTunnelManifest manifest = new BankingPolicyWindTunnelManifest();
    private final BankingPolicyWindTunnelExecutionProvider provider;
    private final BankingRemotePolicyWindTunnelExecutionContext executionContext;
    private final ObjectMapper objectMapper;
    private final boolean platformOwnedV2StorageReady;

    @Autowired
    public BankingRemotePolicyWindTunnelService(
            ObjectMapper objectMapper,
            @Value("${agent.runtime.url:${AGENT_RUNTIME_URL:http://agent-runtime:8082}}") String agentRuntimeUrl,
            @Value("${simulator.url:${SIMULATOR_URL:http://sim:8083}}") String simulatorUrl,
            @Value("${banking.product.endpoint.url:${BANKING_PRODUCT_ENDPOINT_URL:http://localhost:8094}}") String bankingProductEndpointUrl) {
        this(
                objectMapper,
                exactWorkspaceRoot(System.getenv()),
                agentRuntimeUrl,
                simulatorUrl,
                bankingProductEndpointUrl,
                exactSharedStorageEnabled(System.getenv()));
    }

    BankingRemotePolicyWindTunnelService(
            ObjectMapper objectMapper,
            String workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl,
            String bankingProductEndpointUrl,
            boolean sharedStorageEnabled) {
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
        this.executionContext = BankingRemotePolicyWindTunnelExecutionContext.fromConfiguration(
                objectMapper,
                workspaceRoot,
                agentRuntimeUrl,
                simulatorUrl,
                bankingProductEndpointUrl);
        this.platformOwnedV2StorageReady = executionContext.configurePlatformOwnedV2Storage(
                sharedStorageEnabled);
        this.provider = new BankingPolicyWindTunnelExecutionProvider();
        if (platformOwnedV2StorageReady) {
            provider.recoverPendingFinalizationNotifications(executionContext);
        }
    }

    BankingRemotePolicyWindTunnelService(
            ObjectMapper objectMapper,
            String workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl,
            String bankingProductEndpointUrl) {
        this(
                objectMapper,
                workspaceRoot,
                agentRuntimeUrl,
                simulatorUrl,
                bankingProductEndpointUrl,
                false);
    }

    static boolean exactSharedStorageEnabled(Map<String, String> environment) {
        return "true".equals(environment.get(SHARED_STORAGE_ENABLED_ENV));
    }

    static String exactWorkspaceRoot(Map<String, String> environment) {
        return environment.getOrDefault(WORKSPACE_ROOT_ENV, "");
    }

    @Override
    public Map<String, Object> pack() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packId", BankingPolicyWindTunnelManifest.PACK_ID);
        metadata.put("providerRevision", PROVIDER_REVISION);
        metadata.put("domainId", BankingPolicyWindTunnelManifest.DOMAIN_ID);
        metadata.put("label", BankingPolicyWindTunnelManifest.LABEL);
        metadata.put("description", BankingPolicyWindTunnelManifest.DESCRIPTION);
        metadata.put("experimentId", BankingPolicyWindTunnelManifest.EXPERIMENT_ID);
        metadata.put("scenarioId", BankingPolicyWindTunnelManifest.SCENARIO_ID);
        metadata.put("capabilities", capabilities());
        metadata.put("controlSchema", manifest.controlSchema());
        metadata.put("uiSchema", manifest.uiSchema());
        metadata.put("domainLabels", domainLabels());
        metadata.put("reportLabels", reportLabels());
        metadata.put("outcomeSchema", manifest.outcomeSchema());
        metadata.put("outcomeScorecard", manifest.outcomeScorecard());
        metadata.put("outcomeScorecards", manifest.outcomeScorecards());
        metadata.put("policyExpressions", manifest.policyExpressions().stream()
                .map(policyExpression -> policyExpression.asMap())
                .toList());
        metadata.put("summarySchemaVersion", manifest.summarySchemaVersion());
        return metadata;
    }

    @Override
    public Map<String, Object> presets() {
        return manifest.presetCatalog();
    }

    @Override
    public Map<String, Object> startRun(Map<String, Object> payload, String executionRole) {
        Map<String, Object> launchPayload = payload == null ? Map.of() : payload;
        return toMap(provider.startRun(
                executionContext,
                launchPayload,
                firstNonBlank(executionRole, "REMOTE_PROVIDER")));
    }

    @Override
    public Map<String, Object> getRun(String runId) {
        return toMap(provider.getRunStatus(executionContext, runId));
    }

    @Override
    public Map<String, Object> controlRun(String runId, Map<String, Object> payload) {
        Map<String, Object> controlPayload = payload == null ? Map.of() : payload;
        String action = firstNonBlank(controlPayload.get("action"), controlPayload.get("lifecycleAction"));
        return toMap(provider.controlRun(
                executionContext,
                runId,
                action,
                controlPayload,
                privateOpportunityCorrelationCapability()));
    }

    static String privateOpportunityCorrelationCapability() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return "";
        }
        String capability = attributes.getRequest().getHeader(
                OPPORTUNITY_CORRELATION_CAPABILITY_HEADER);
        return capability == null ? "" : capability;
    }

    @Override
    public Map<String, Object> bundle(String runId) {
        try {
            return provider.loadBundle(executionContext, runId);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load banking Wind Tunnel bundle for run " + runId + ".", error);
        }
    }

    @Override
    public PolicyWindTunnelBinaryPayload artifact(String runId, String artifactType) {
        try {
            PolicyWindTunnelArtifactPayload payload = provider.readArtifact(executionContext, runId, artifactType);
            return new PolicyWindTunnelBinaryPayload(payload.content(), payload.contentType(), payload.filename());
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Failed to load banking Wind Tunnel artifact " + artifactType + " for run " + runId + ".",
                    error);
        }
    }

    @Override
    public PolicyWindTunnelArtifactMetadata artifactMetadata(String runId, String artifactType) {
        try {
            return provider.readArtifactMetadata(executionContext, runId, artifactType);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Failed to check banking Wind Tunnel artifact " + artifactType + " for run " + runId + ".",
                    error);
        }
    }

    @Override
    public PolicyWindTunnelBinaryPayload evidencePack(String runId) {
        try {
            PolicyWindTunnelArchivePayload payload = provider.buildEvidencePack(executionContext, runId);
            return new PolicyWindTunnelBinaryPayload(payload.content(), "application/zip", payload.baseName() + ".zip");
        } catch (IOException error) {
            throw new IllegalStateException("Failed to build banking Wind Tunnel evidence pack for run " + runId + ".", error);
        }
    }

    @Override
    public PolicyWindTunnelArtifactMetadata evidencePackMetadata(String runId) {
        try {
            return provider.buildEvidencePackMetadata(executionContext, runId);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to check banking Wind Tunnel evidence pack for run " + runId + ".", error);
        }
    }

    private Map<String, Object> toMap(PolicyWindTunnelRunRecord record) {
        Map<String, Object> response = new LinkedHashMap<>(objectMapper.convertValue(record, MAP_TYPE));
        Object finalizationMode = record.parameters().get("opportunityGenerationFinalizationMode");
        if (finalizationMode != null) {
            response.put("finalizationMode", finalizationMode);
        }
        return response;
    }

    private static Map<String, Object> domainLabels() {
        return Map.of(
                "actorSingular", "customer",
                "actorPlural", "customers",
                "simulatedActorsLabel", "Simulated customers",
                "impactedActorsLabel", "Impacted customers",
                "exposureMetricLabel", "Customer exposure",
                "outcomeDeltaLabel", "Customer outcome delta",
                "counterMetricDeltaLabel", "Business counter-metric delta",
                "operationalStressLabel", "Operational stress",
                "stateChangesLabel", "Banking state changes");
    }

    private static Map<String, Object> reportLabels() {
        return Map.of(
                "decisionQuestion",
                "Should the proposed banking policy or agent workflow move forward for customer-facing launch?",
                "currentLabel",
                "Current banking approach",
                "candidateLabel",
                "Candidate banking approach",
                "evidenceLabel",
                "Banking evidence");
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>(manifest.capabilities());
        capabilities.put(
                "opportunityGenerationFinalizationMode",
                platformOwnedV2StorageReady ? "PLATFORM_OWNED_V2" : "LEGACY_V1");
        capabilities.put("platformOwnedV2StorageReady", platformOwnedV2StorageReady);
        capabilities.put("scorecards", true);
        capabilities.put("remoteScorecardMetadata", true);
        capabilities.put("remoteProviderReady", true);
        return Map.copyOf(capabilities);
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = value == null ? "" : String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
