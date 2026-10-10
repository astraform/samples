package platform.marketingops.remote.api;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelBinaryPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import platform.marketingops.remote.scenariolab.MarketingOpsPolicyWindTunnelDefinition;
import platform.marketingops.remote.scenariolab.MarketingOpsPolicyWindTunnelExecutionProvider;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArchivePayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelArtifactPayload;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelRunRecord;

@Service
public class MarketingOpsRemotePolicyWindTunnelService implements PolicyWindTunnelService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MarketingOpsPolicyWindTunnelDefinition definition = new MarketingOpsPolicyWindTunnelDefinition();
    private final MarketingOpsPolicyWindTunnelExecutionProvider provider = new MarketingOpsPolicyWindTunnelExecutionProvider();
    private final MarketingOpsRemotePolicyWindTunnelExecutionContext executionContext;
    private final ObjectMapper objectMapper;

    public MarketingOpsRemotePolicyWindTunnelService(
            ObjectMapper objectMapper,
            @Value("${astraform.workspace.root:${ASTRAFORM_WORKSPACE_ROOT:}}") String workspaceRoot,
            @Value("${agent.runtime.url:${AGENT_RUNTIME_URL:http://agent-runtime:8082}}") String agentRuntimeUrl,
            @Value("${simulator.url:${SIMULATOR_URL:http://sim:8083}}") String simulatorUrl) {
        this.objectMapper = objectMapper.rebuild().findAndAddModules().build();
        this.executionContext = MarketingOpsRemotePolicyWindTunnelExecutionContext.fromConfiguration(
                objectMapper,
                workspaceRoot,
                agentRuntimeUrl,
                simulatorUrl);
    }

    @Override
    public Map<String, Object> pack() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packId", MarketingOpsPolicyWindTunnelDefinition.PACK_ID);
        metadata.put("domainId", MarketingOpsPolicyWindTunnelDefinition.DOMAIN_ID);
        metadata.put("label", MarketingOpsPolicyWindTunnelDefinition.LABEL);
        metadata.put("description", MarketingOpsPolicyWindTunnelDefinition.DESCRIPTION);
        metadata.put("experimentId", MarketingOpsPolicyWindTunnelDefinition.EXPERIMENT_ID);
        metadata.put("scenarioId", MarketingOpsPolicyWindTunnelDefinition.SCENARIO_ID);
        metadata.put("capabilities", capabilities());
        metadata.put("controlSchema", definition.controlSchema());
        metadata.put("uiSchema", definition.uiSchema());
        metadata.put("domainLabels", domainLabels());
        metadata.put("reportLabels", reportLabels());
        metadata.put("outcomeSchema", definition.outcomeSchema());
        metadata.put("outcomeScorecard", definition.outcomeScorecard());
        metadata.put("outcomeScorecards", definition.outcomeScorecards());
        metadata.put("policyExpressions", definition.policyExpressions().stream()
                .map(policyExpression -> policyExpression.asMap())
                .toList());
        metadata.put("summarySchemaVersion", definition.summarySchemaVersion());
        return metadata;
    }

    @Override
    public Map<String, Object> presets() {
        return definition.presetCatalog();
    }

    @Override
    public Map<String, Object> startRun(Map<String, Object> payload, String executionRole) {
        return toMap(provider.startRun(
                executionContext,
                payload == null ? Map.of() : payload,
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
        return toMap(provider.controlRun(executionContext, runId, action, controlPayload));
    }

    @Override
    public Map<String, Object> bundle(String runId) {
        try {
            return provider.loadBundle(executionContext, runId);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load Marketing Ops Wind Tunnel bundle for run " + runId + ".", error);
        }
    }

    @Override
    public PolicyWindTunnelBinaryPayload artifact(String runId, String artifactType) {
        try {
            PolicyWindTunnelArtifactPayload payload = provider.readArtifact(executionContext, runId, artifactType);
            return new PolicyWindTunnelBinaryPayload(payload.content(), payload.contentType(), payload.filename());
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Failed to load Marketing Ops Wind Tunnel artifact " + artifactType + " for run " + runId + ".",
                    error);
        }
    }

    @Override
    public PolicyWindTunnelBinaryPayload evidencePack(String runId) {
        try {
            PolicyWindTunnelArchivePayload payload = provider.buildEvidencePack(executionContext, runId);
            return new PolicyWindTunnelBinaryPayload(payload.content(), "application/zip", payload.baseName() + ".zip");
        } catch (IOException error) {
            throw new IllegalStateException("Failed to build Marketing Ops Wind Tunnel evidence pack for run " + runId + ".", error);
        }
    }

    private Map<String, Object> toMap(PolicyWindTunnelRunRecord record) {
        return objectMapper.convertValue(record, MAP_TYPE);
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>(definition.capabilities());
        capabilities.put("scorecards", true);
        capabilities.put("remoteScorecardMetadata", true);
        capabilities.put("remoteProviderReady", true);
        return Map.copyOf(capabilities);
    }

    private static Map<String, Object> domainLabels() {
        return Map.of(
                "actorSingular", "campaign",
                "actorPlural", "campaigns",
                "simulatedActorsLabel", "Simulated campaigns",
                "impactedActorsLabel", "At-risk campaigns",
                "exposureMetricLabel", "Launch exposure",
                "outcomeDeltaLabel", "Launch readiness delta",
                "counterMetricDeltaLabel", "Operational pressure delta",
                "operationalStressLabel", "Approval queue stress",
                "stateChangesLabel", "Marketing Ops state changes");
    }

    private static Map<String, Object> reportLabels() {
        return Map.of(
                "decisionQuestion",
                "Should the candidate launch policy move forward for this campaign operating window?",
                "currentLabel",
                "Current launch process",
                "candidateLabel",
                "Candidate launch process",
                "evidenceLabel",
                "Marketing Ops evidence");
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
