package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.EvaluationRunReport;
import ai.astraform.remote.domain.sdk.RegressionDeltaReport;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;
import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class BankingPolicyScenarioLabRuntime {

    private final BankingPolicyScenarioExecutionRuntime executionRuntime;
    private final BankingPolicyScenarioLabSummaryAssembler summaryAssembler;

    public BankingPolicyScenarioLabRuntime(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            PolicyWindTunnelExecutionContext runtimeContext) {
        String bankingProductEndpointUrl = runtimeContext.dependencyUrl(
                BankingPolicyWindTunnelManifest.BANKING_PRODUCT_ENDPOINT_DEPENDENCY);
        this.executionRuntime = new BankingPolicyScenarioExecutionRuntime(
                objectMapper,
                runtimeContext.workspaceRoot(),
                runtimeContext.agentRuntimeUrl(),
                runtimeContext.simulatorUrl(),
                bankingProductEndpointUrl,
                restTemplate);
        this.summaryAssembler = new BankingPolicyScenarioLabSummaryAssembler(
                objectMapper,
                runtimeContext.workspaceRoot());
    }

    public BankingPolicyScenarioExecutionRuntime.ExecutionPlan materializeExecutionPlan(ScenarioLabRunParameters parameters)
            throws IOException {
        return executionRuntime.materializeExecutionPlan(parameters);
    }

    public BankingPolicyScenarioExecutionRuntime.ExecutionPlan materializeExecutionPlan(
            ScenarioLabRunParameters parameters,
            Map<String, Object> normalizedPayload) throws IOException {
        return executionRuntime.materializeExecutionPlan(parameters, normalizedPayload);
    }

    public List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(String cohortId) throws IOException {
        return executionRuntime.loadGeneratedCohortAgentsForStreaming(cohortId);
    }

    public List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(
            String cohortId,
            String agentDecisionMode) throws IOException {
        return executionRuntime.loadGeneratedCohortAgentsForStreaming(cohortId, agentDecisionMode);
    }

    public List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(
            String cohortId,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) throws IOException {
        return executionRuntime.loadGeneratedCohortAgentsForStreaming(
                cohortId,
                agentDecisionMode,
                agentDecisionSettings);
    }

    public List<Map<String, Object>> loadPinnedCohortAgentsForStreaming(
            String cohortId,
            byte[] artifactBytes,
            String expectedArtifactDigest,
            String expectedIdentitySetDigest,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) throws IOException {
        return executionRuntime.loadPinnedCohortAgentsForStreaming(
                cohortId,
                artifactBytes,
                expectedArtifactDigest,
                expectedIdentitySetDigest,
                agentDecisionMode,
                agentDecisionSettings);
    }

    public BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult executeScenario(
            String suiteRunId,
            String branchType,
            Path scenarioRoot,
            List<Map<String, Object>> agents,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEvent> events,
            List<BankingPolicyScenarioExecutionRuntime.BatchStep> batchSteps,
            ScenarioLabRunParameters parameters) throws IOException {
        return executionRuntime.executeScenario(
                suiteRunId,
                branchType,
                scenarioRoot,
                agents,
                events,
                batchSteps,
                parameters);
    }

    public SummaryArtifacts buildSummaryArtifacts(
            String runId,
            BankingPolicyScenarioLabRequest resolved,
            BankingPolicyScenarioExecutionRuntime.ExecutionPlan executionPlan,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult baseline,
            BankingPolicyScenarioExecutionRuntime.ScenarioExecutionResult candidate,
            Path baselineReportPath,
            Path candidateReportPath,
            Path comparisonReportPath,
            EvaluationRunReport baselineReport,
            EvaluationRunReport candidateReport,
            RegressionDeltaReport comparisonReport,
            Path summaryDir,
            Path baselineDir,
            Path candidateDir) throws IOException {
        return summaryAssembler.buildSummaryArtifacts(
                runId,
                resolved,
                executionPlan,
                baseline,
                candidate,
                baselineReportPath,
                candidateReportPath,
                comparisonReportPath,
                baselineReport,
                candidateReport,
                comparisonReport,
                summaryDir,
                baselineDir,
                candidateDir);
    }
}
