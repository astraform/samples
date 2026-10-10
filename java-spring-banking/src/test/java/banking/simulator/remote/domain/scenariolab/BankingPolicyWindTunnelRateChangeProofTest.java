package banking.simulator.remote.domain.scenariolab;

import ai.astraform.remote.domain.sdk.PolicyWindTunnelExecutionContext;
import banking.simulator.remote.domain.BankingClockDrivenRateChangePlan;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BankingPolicyWindTunnelRateChangeProofTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BankingPolicyWindTunnelExecutionProvider provider =
            new BankingPolicyWindTunnelExecutionProvider(
                    objectMapper,
                    mock(RestTemplate.class),
                    Runnable::run);

    @Test
    void sharedNoTokenAuditProvesTechnicalDecisionsWithoutClaimingRealizedReactions()
            throws Exception {
        Map<String, Object> fixture = fixture();
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.requireInstallationManifest(
                        fixture.get("expectedInstallationManifest"),
                        plan);

        BankingPolicyWindTunnelExecutionProvider.RateChangeProofAssessment assessment =
                provider.verifyClockDrivenRateChangeProof(
                        plan,
                        installation,
                        map(fixture.get("expectedCorrelationAudit")),
                        "STUB_LLM");

        assertThat(assessment.required()).isTrue();
        assertThat(assessment.chainIntegrityVerified()).isTrue();
        assertThat(assessment.realizedReactionVerified()).isFalse();
        assertThat(assessment.verified()).isTrue();
        assertThat(assessment.evidence())
                .containsEntry("status", "TECHNICAL_VALIDATION_ONLY")
                .containsEntry("chainIntegrityVerified", true)
                .containsEntry("realizedReactionVerified", false)
                .containsEntry("reactionEvidenceType", "TECHNICAL_NO_TOKEN_VALIDATION")
                .containsEntry("generatedOpportunities", 1)
                .containsEntry("correlatedDecisions", 1)
                .containsEntry("realizedCustomerReactions", 0)
                .containsEntry("technicalValidationDecisions", 1);
    }

    @Test
    void sharedRemoteAuthorizedAuditRequiresFencedRealLlmDecisionOwnership() throws Exception {
        Map<String, Object> fixture = fixture();
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.requireInstallationManifest(
                        fixture.get("expectedInstallationManifest"),
                        plan);
        Map<String, Object> audit = deepCopyMap(fixture.get("expectedCorrelationAudit"));
        opportunity(audit).put("decisionMode", "REAL_LLM");

        BankingPolicyWindTunnelExecutionProvider.RateChangeProofAssessment assessment =
                provider.verifyClockDrivenRateChangeProof(
                        plan,
                        installation,
                        audit,
                        "REAL_LLM");

        assertThat(assessment.chainIntegrityVerified()).isTrue();
        assertThat(assessment.realizedReactionVerified()).isTrue();
        assertThat(assessment.evidence())
                .containsEntry("status", "VERIFIED")
                .containsEntry("chainIntegrityVerified", true)
                .containsEntry("realizedReactionVerified", true)
                .containsEntry("reactionEvidenceType", "REMOTE_AUTHORIZED_REAL_LLM")
                .containsEntry("realizedCustomerReactions", 1)
                .containsEntry("technicalValidationDecisions", 0);

        opportunity(audit).put("decisionAttempt", 2);
        assertThatThrownBy(() -> provider.verifyClockDrivenRateChangeProof(
                        plan,
                        installation,
                        audit,
                        "REAL_LLM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fenced valid decision evidence");
    }

    @Test
    void missingOrContradictoryCustomerCoverageFailsClosed() throws Exception {
        Map<String, Object> fixture = fixture();
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.requireInstallationManifest(
                        fixture.get("expectedInstallationManifest"),
                        plan);
        Map<String, Object> missing = deepCopyMap(fixture.get("expectedCorrelationAudit"));
        missing.put("opportunityCount", 0);
        missing.put("opportunities", List.of());

        assertThatThrownBy(() -> provider.verifyClockDrivenRateChangeProof(
                        plan,
                        installation,
                        missing,
                        "STUB_LLM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");

        Map<String, Object> contradictory = deepCopyMap(fixture.get("expectedCorrelationAudit"));
        map(opportunity(contradictory).get("payload")).put("operationId", "contradictory-operation");
        assertThatThrownBy(() -> provider.verifyClockDrivenRateChangeProof(
                        plan,
                        installation,
                        contradictory,
                        "STUB_LLM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload is contradictory");

        BankingClockDrivenRateChangePlan.InstallationManifest zeroEligibleInstallation =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001", "provider-customer-001", "opportunity-customer-001", 0),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002", "provider-customer-002", "opportunity-customer-002", 0)),
                        "c".repeat(64));
        assertThatThrownBy(() -> provider.verifyClockDrivenRateChangeProof(
                        plan,
                        zeroEligibleInstallation,
                        missing,
                        "STUB_LLM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
    }

    @Test
    void supportedRateChangePresetCannotLoseItsPinnedPlanAndBecomeNotApplicable() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));

        BankingPolicyWindTunnelExecutionProvider.RateChangeProofAssessment assessment =
                provider.assessClockDrivenRateChangeProof(
                        mock(PolicyWindTunnelExecutionContext.class),
                        String.valueOf(input.get("simulationRunId")),
                        String.valueOf(input.get("presetId")),
                        map(input.get("parameters")),
                        "");

        assertThat(assessment.required()).isTrue();
        assertThat(assessment.verified()).isFalse();
        assertThat(assessment.evidence()).containsEntry("status", "UNVERIFIED");
    }

    @Test
    void rewrittenInstallationDenominatorDowngradesFinalizedProof(@TempDir Path workspaceRoot)
            throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> parameters = deepCopyMap(input.get("parameters"));
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        parameters.put("clockDrivenInterventionPlanDigest", plan.planDigest());
        parameters.put(
                "clockDrivenInterventionPlanSchemaVersion",
                BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION);
        byte[] cohortArtifact = cohortArtifact();
        parameters.put("cohortArtifactDigest", sha256(cohortArtifact));
        parameters.put(
                "cohortAgentIdentitySetDigest",
                BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                        List.of("customer-001", "customer-002")));
        BankingClockDrivenRateChangePlan.InstallationManifest rewritten =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001",
                                        "provider-customer-001",
                                        "opportunity-customer-001",
                                        0),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002",
                                        "provider-customer-002",
                                        "opportunity-customer-002",
                                        1)),
                        String.valueOf(parameters.get("cohortArtifactDigest")));
        String runId = String.valueOf(input.get("simulationRunId"));
        Path runRoot = workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId);
        Files.createDirectories(runRoot);
        Files.write(
                runRoot.resolve("clock-driven-intervention-plan.json"),
                BankingClockDrivenRateChangePlan.canonicalSnapshotBytes(plan));
        Files.write(
                runRoot.resolve("clock-driven-rate-change-installation.json"),
                BankingClockDrivenRateChangePlan.canonicalInstallationManifestBytes(rewritten));
        writeCohortSnapshot(workspaceRoot, runId, cohortArtifact);
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        when(context.workspaceRoot()).thenReturn(workspaceRoot);

        BankingPolicyWindTunnelExecutionProvider.RateChangeProofAssessment assessment =
                provider.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "unused-private-capability");

        assertThat(assessment.required()).isTrue();
        assertThat(assessment.verified()).isFalse();
        assertThat(assessment.evidence()).containsEntry("status", "UNVERIFIED");
    }

    @Test
    void unavailableCorrelationAuditAbortsBeforeFinalizationCanPublish(@TempDir Path workspaceRoot)
            throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> parameters = deepCopyMap(input.get("parameters"));
        String runId = String.valueOf(input.get("simulationRunId"));
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        byte[] cohortArtifact = cohortArtifact();
        String cohortDigest = sha256(cohortArtifact);
        parameters.put("cohortArtifactDigest", cohortDigest);
        parameters.put(
                "cohortAgentIdentitySetDigest",
                BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                        List.of("customer-001", "customer-002")));
        parameters.put(
                "clockDrivenInterventionPlanDigest",
                plan.planDigest());
        parameters.put(
                "clockDrivenInterventionPlanSchemaVersion",
                BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION);
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001",
                                        "provider-customer-001",
                                        "opportunity-customer-001",
                                        1),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002",
                                        "provider-customer-002",
                                        "opportunity-customer-002",
                                        0)),
                        cohortDigest);
        writeProofInputs(workspaceRoot, runId, plan, installation, cohortArtifact);
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        when(context.workspaceRoot()).thenReturn(workspaceRoot);
        when(context.agentRuntimeUrl()).thenReturn("http://agent-runtime");
        RestTemplate unavailableRuntime = new RestTemplate();
        MockRestServiceServer unavailableServer = MockRestServiceServer
                .bindTo(unavailableRuntime)
                .build();
        unavailableServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        BankingPolicyWindTunnelExecutionProvider providerWithUnavailableAudit =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        unavailableRuntime,
                        Runnable::run);

        assertThatThrownBy(() -> providerWithUnavailableAudit.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability"))
                .isInstanceOf(
                        BankingPolicyWindTunnelExecutionProvider
                                .OpportunityCorrelationAuditUnavailableException.class)
                .hasMessageContaining("403");
        unavailableServer.verify();

        RestTemplate malformedRuntime = new RestTemplate();
        MockRestServiceServer malformedServer = MockRestServiceServer
                .bindTo(malformedRuntime)
                .build();
        malformedServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"simulationRunId\":\"first\",\"simulationRunId\":\"second\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        MediaType.APPLICATION_JSON));
        BankingPolicyWindTunnelExecutionProvider providerWithMalformedAudit =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        malformedRuntime,
                        Runnable::run);

        assertThatThrownBy(() -> providerWithMalformedAudit.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability"))
                .isInstanceOf(
                        BankingPolicyWindTunnelExecutionProvider
                                .OpportunityCorrelationAuditUnavailableException.class)
                .hasMessageContaining("strict UTF-8 JSON");
        malformedServer.verify();
        assertThat(workspaceRoot.resolve("output/policy-wind-tunnel/runs/" + runId + "/summary"))
                .doesNotExist();
    }

    @Test
    void correlationAuditRequiresExactJsonMediaTypeAndStreamsWithinFourMiB(
            @TempDir Path workspaceRoot) throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> parameters = deepCopyMap(input.get("parameters"));
        String runId = String.valueOf(input.get("simulationRunId"));
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        byte[] cohortArtifact = cohortArtifact();
        String cohortDigest = sha256(cohortArtifact);
        parameters.put("cohortArtifactDigest", cohortDigest);
        parameters.put(
                "cohortAgentIdentitySetDigest",
                BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                        List.of("customer-001", "customer-002")));
        parameters.put("clockDrivenInterventionPlanDigest", plan.planDigest());
        parameters.put(
                "clockDrivenInterventionPlanSchemaVersion",
                BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION);
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001",
                                        "provider-customer-001",
                                        "opportunity-customer-001",
                                        1),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002",
                                        "provider-customer-002",
                                        "opportunity-customer-002",
                                        0)),
                        cohortDigest);
        writeProofInputs(workspaceRoot, runId, plan, installation, cohortArtifact);
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        when(context.workspaceRoot()).thenReturn(workspaceRoot);
        when(context.agentRuntimeUrl()).thenReturn("http://agent-runtime");
        byte[] validAudit = objectMapper.writeValueAsBytes(fixture.get("expectedCorrelationAudit"));

        RestTemplate extraParameterRuntime = new RestTemplate();
        MockRestServiceServer extraParameterServer = MockRestServiceServer
                .bindTo(extraParameterRuntime)
                .build();
        extraParameterServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        validAudit,
                        MediaType.parseMediaType("application/json;charset=UTF-8;profile=proof")));
        BankingPolicyWindTunnelExecutionProvider extraParameterProvider =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        extraParameterRuntime,
                        Runnable::run);
        assertThatThrownBy(() -> extraParameterProvider.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability"))
                .isInstanceOf(
                        BankingPolicyWindTunnelExecutionProvider
                                .OpportunityCorrelationAuditUnavailableException.class)
                .hasMessageContaining("exact JSON proof response");
        extraParameterServer.verify();

        RestTemplate oversizedRuntime = new RestTemplate();
        MockRestServiceServer oversizedServer = MockRestServiceServer
                .bindTo(oversizedRuntime)
                .build();
        oversizedServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(new byte[4 * 1024 * 1024 + 1], MediaType.APPLICATION_JSON));
        BankingPolicyWindTunnelExecutionProvider oversizedProvider =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        oversizedRuntime,
                        Runnable::run);
        assertThatThrownBy(() -> oversizedProvider.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability"))
                .isInstanceOf(
                        BankingPolicyWindTunnelExecutionProvider
                                .OpportunityCorrelationAuditUnavailableException.class)
                .hasMessageContaining("exceeds the 4 MiB transport limit");
        oversizedServer.verify();

        RestTemplate utf8Runtime = new RestTemplate();
        MockRestServiceServer utf8Server = MockRestServiceServer.bindTo(utf8Runtime).build();
        utf8Server.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        validAudit,
                        MediaType.parseMediaType("application/json;charset=UTF-8")));
        BankingPolicyWindTunnelExecutionProvider utf8Provider =
                new BankingPolicyWindTunnelExecutionProvider(
                        objectMapper,
                        utf8Runtime,
                        Runnable::run);
        assertThat(utf8Provider.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability").required())
                .isTrue();
        utf8Server.verify();
    }

    @Test
    void correlationAuditProviderRevisionIsEncodedExactlyOnce(@TempDir Path workspaceRoot)
            throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> parameters = deepCopyMap(input.get("parameters"));
        String runId = String.valueOf(input.get("simulationRunId"));
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        byte[] cohortArtifact = cohortArtifact();
        String cohortDigest = sha256(cohortArtifact);
        parameters.put("cohortArtifactDigest", cohortDigest);
        parameters.put(
                "cohortAgentIdentitySetDigest",
                BankingClockDrivenRateChangePlan.agentIdentitySetDigest(
                        List.of("customer-001", "customer-002")));
        parameters.put("clockDrivenInterventionPlanDigest", plan.planDigest());
        parameters.put(
                "clockDrivenInterventionPlanSchemaVersion",
                BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION);
        BankingClockDrivenRateChangePlan.InstallationManifest installation =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001",
                                        "provider-customer-001",
                                        "opportunity-customer-001",
                                        1),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002",
                                        "provider-customer-002",
                                        "opportunity-customer-002",
                                        0)),
                        cohortDigest);
        writeProofInputs(workspaceRoot, runId, plan, installation, cohortArtifact);
        PolicyWindTunnelExecutionContext context = mock(PolicyWindTunnelExecutionContext.class);
        when(context.workspaceRoot()).thenReturn(workspaceRoot);
        when(context.agentRuntimeUrl()).thenReturn("http://agent-runtime");
        RestTemplate runtime = new RestTemplate();
        MockRestServiceServer runtimeServer = MockRestServiceServer.bindTo(runtime).build();
        java.util.concurrent.atomic.AtomicReference<URI> uri =
                new java.util.concurrent.atomic.AtomicReference<>();
        byte[] audit = objectMapper.writeValueAsBytes(fixture.get("expectedCorrelationAudit"));
        runtimeServer.expect(request -> uri.set(request.getURI()))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(audit, MediaType.APPLICATION_JSON));
        BankingPolicyWindTunnelExecutionProvider providerWithRuntime =
                new BankingPolicyWindTunnelExecutionProvider(objectMapper, runtime, Runnable::run);

        BankingPolicyWindTunnelExecutionProvider.RateChangeProofAssessment assessment =
                providerWithRuntime.assessClockDrivenRateChangeProof(
                        context,
                        runId,
                        String.valueOf(input.get("presetId")),
                        parameters,
                        "fresh-private-capability");

        assertThat(assessment.required()).isTrue();
        runtimeServer.verify();
        assertThat(uri.get().getRawQuery())
                .contains("providerRevision=build-sha256%3A")
                .doesNotContain("%253A");
        assertThat(uri.get().getQuery())
                .contains("providerRevision=build-sha256:");
    }

    private void writeProofInputs(
            Path workspaceRoot,
            String runId,
            BankingClockDrivenRateChangePlan.Snapshot plan,
            BankingClockDrivenRateChangePlan.InstallationManifest installation,
            byte[] cohortArtifact) throws Exception {
        Path runRoot = workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve(runId);
        Files.createDirectories(runRoot);
        Files.write(
                runRoot.resolve("clock-driven-intervention-plan.json"),
                BankingClockDrivenRateChangePlan.canonicalSnapshotBytes(plan));
        Files.write(
                runRoot.resolve("clock-driven-rate-change-installation.json"),
                BankingClockDrivenRateChangePlan.canonicalInstallationManifestBytes(installation));
        writeCohortSnapshot(workspaceRoot, runId, cohortArtifact);
    }

    private void writeCohortSnapshot(Path workspaceRoot, String runId, byte[] content) throws Exception {
        Path snapshotRoot = workspaceRoot
                .resolve(BankingPolicyWindTunnelManifest.ARTIFACT_ROOT)
                .resolve("cohort-snapshots");
        Files.createDirectories(snapshotRoot);
        Files.write(snapshotRoot.resolve(sha256(runId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".customers.json"), content);
    }

    private byte[] cohortArtifact() throws Exception {
        return objectMapper.writeValueAsBytes(List.of(
                new LinkedHashMap<>(Map.of(
                        "personaName", "customer-001",
                        "initialAccounts", List.of(Map.of("productCode", "CC001")))),
                new LinkedHashMap<>(Map.of(
                        "personaName", "customer-002",
                        "initialAccounts", List.of(Map.of("productCode", "TS001"))))));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private Map<String, Object> fixture() throws Exception {
        return objectMapper.readValue(fixturePath().toFile(), new TypeReference<>() {});
    }

    private Map<String, Object> deepCopyMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() {});
    }

    private static Path fixturePath() {
        return List.of(
                        Path.of("test-fixtures/banking-clock-driven-rate-change-v1.json"),
                        Path.of("../test-fixtures/banking-clock-driven-rate-change-v1.json"))
                .stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("rate-change fixture was not found"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> opportunity(Map<String, Object> audit) {
        return map(((List<?>) audit.get("opportunities")).getFirst());
    }
}
