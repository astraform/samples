package platform.marketingops.remote.api;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import platform.marketingops.remote.service.MarketingOpsRemoteDomainService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ai.astraform.remote.domain.openapi.models.RemoteDomainAgentContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainHostContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTurnContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTurnContextTimeWindow;
import ai.astraform.remote.domain.openapi.models.RemoteDomainDueWorkItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "astraform.native-simulation.enabled=true")
@AutoConfigureMockMvc
class MarketingOpsRemoteDomainControllerTest {
    private static final String REMOTE_MEDIA_TYPE = "application/vnd.astraform.remote-domain.v1+json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void manifest_exposesRemoteMarketingOpsCapability() throws Exception {
        mockMvc.perform(get("/api/remote-domain/v1/manifest")
                        .accept(REMOTE_MEDIA_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocolVersion").value("remote-domain.v1"))
                .andExpect(jsonPath("$.domain.domainId").value("marketing-ops"))
                .andExpect(jsonPath("$.uiProfile.kind").value("schema-form-v1"))
                .andExpect(jsonPath("$.uiProfile.creatorSections[0].fields[0].path").value("campaignKey"))
                .andExpect(jsonPath("$.transport.contentType").value(REMOTE_MEDIA_TYPE))
                .andExpect(jsonPath("$.operations.prepare.path").value("/api/remote-domain/v1/prepare"))
                .andExpect(jsonPath("$.capabilities.statusProjection").value(true))
                .andExpect(jsonPath("$.capabilities.tools[0].name").value("get_campaign_brief"))
                .andExpect(jsonPath("$.capabilities.tools[4].name").value("post_review_decision"))
                .andExpect(jsonPath("$.capabilities.tools[4].effectClass").doesNotExist())
                .andExpect(jsonPath("$.capabilities.nativeSimulation.profile").value(MarketingOpsRemoteDomainService.NATIVE_PROFILE))
                .andExpect(header().string("X-Astraform-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION));
    }

    @Test
    void populationCatalog_exposesProviderOwnedArchetypesAndRecipes() throws Exception {
        mockMvc.perform(get("/api/remote-domain/v1/population/catalog")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("domain_population_catalog.v1"))
                .andExpect(jsonPath("$.domainId").value("marketing-ops"))
                .andExpect(jsonPath("$.archetypeCatalog.archetypes[0].archetypeId").value("new-category-evaluator"))
                .andExpect(jsonPath("$.populationRecipes.recipes[0].recipeId").value("campaign-readiness-100"))
                .andExpect(jsonPath("$.populationRecipes.recipes[0].segmentMix[0].archetypeIds[0]").value("new-category-evaluator"))
                .andExpect(jsonPath("$.populationRecipes.recipes[0].segmentMix[0].memberTemplate.domainConfig.campaignKey")
                        .value("pb-{cohortId}-{segmentCode}-{customerIndex}"))
                .andExpect(jsonPath("$.populationRecipes.recipes[0].memberValidation.requiredDomainConfigFields[0]")
                        .value("campaignKey"))
                .andExpect(jsonPath("$.runPathEligibility.status").value("SCENARIO_READY"))
                .andExpect(jsonPath("$.runPathEligibility.windTunnelReady").value(true))
                .andExpect(jsonPath("$.runPathEligibility.windTunnelConsumesPopulation").value(true));
    }

    @Test
    void prepareExecuteAndStatus_supportDeterministicLifecycle() throws Exception {
        long baseNow = futureEpochMs(60_000);
        RemoteDomainPrepareRequest prepareRequest = prepareRequest("Launch Coordinator Avery", baseNow);
        String prepareBody = mockMvc.perform(post("/api/remote-domain/v1/prepare")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(prepareRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.runtimeIdentity").value("marketing-ops::Launch Coordinator Avery"))
                .andExpect(jsonPath("$.nextState.schemaVersion").value("marketing-ops.state.v1"))
                .andExpect(jsonPath("$.result.projection.runtimeMetadata.campaignKey").value("spring-launch"))
                .andExpect(jsonPath("$.result.projection.statusView.pendingApprovals").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        RemoteDomainPrepareResponse prepareResponse =
                objectMapper.readValue(prepareBody, RemoteDomainPrepareResponse.class);

        RemoteDomainExecuteWorkRequest executeWorkRequest = executeWorkRequest(
                prepareResponse.getNextState(),
                baseNow + 60_000);
        String executeBody = mockMvc.perform(post("/api/remote-domain/v1/execute-work")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(executeWorkRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.projection.statusView.completedWorkCount").value(1))
                .andExpect(jsonPath("$.result.projection.statusView.pendingApprovals").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        RemoteDomainExecuteWorkResponse executeResponse =
                objectMapper.readValue(executeBody, RemoteDomainExecuteWorkResponse.class);

        RemoteDomainStatusRequest statusRequest = statusRequest(
                executeResponse.getNextState(),
                baseNow + 70_000);
        String statusBody = mockMvc.perform(post("/api/remote-domain/v1/status")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(statusRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.projection.statusView.queueState").value("active"))
                .andExpect(jsonPath("$.result.projection.statusView.queueMode").value("readiness"))
                .andExpect(jsonPath("$.result.projection.inspectionView.pendingApprovals[0].state").value("READY_FOR_LAUNCH"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        RemoteDomainStatusResponse statusResponse =
                objectMapper.readValue(statusBody, RemoteDomainStatusResponse.class);
        assertThat(statusResponse.getResult().getProjection().getStatusView())
                .containsEntry("completedWorkCount", 1);
    }

    @Test
    void executeWork_routesManifestDeclaredToolCalls() throws Exception {
        long baseNow = futureEpochMs(60_000);
        String prepareBody = mockMvc.perform(post("/api/remote-domain/v1/prepare")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(prepareRequest("Review Lead Riley", baseNow))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        RemoteDomainPrepareResponse prepareResponse =
                objectMapper.readValue(prepareBody, RemoteDomainPrepareResponse.class);

        RemoteDomainExecuteWorkRequest toolCallRequest = toolCallRequest(
                prepareResponse.getNextState(),
                "post_review_decision",
                Map.of(
                        "blockerId", "spring-launch-review",
                        "decision", "APPROVE",
                        "note", "Approved from remote-domain tool path"),
                baseNow + 10_000);

        mockMvc.perform(post("/api/remote-domain/v1/execute-work")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(toolCallRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.toolResult.toolName").value("post_review_decision"))
                .andExpect(jsonPath("$.result.toolResult.success").value(true))
                .andExpect(jsonPath("$.result.toolResult.remainingApprovals").value(1))
                .andExpect(jsonPath("$.result.projection.statusView.pendingApprovals").value(1));
    }

    @Test
    void prepare_withoutDomainConfig_fallsBackToLegacyPersonaHints() throws Exception {
        RemoteDomainPrepareRequest prepareRequest = prepareRequest("Fallback Avery", futureEpochMs(60_000));
        prepareRequest.getPayload().setPersonaConfiguration(new java.util.LinkedHashMap<>(Map.of(
                "personaName", "Fallback Avery",
                "traits", List.of("segment:creative-review", "external-escalation"),
                "goals", List.of(Map.of("goalId", "creative-q4-launch")))));

        mockMvc.perform(post("/api/remote-domain/v1/prepare")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(prepareRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.projection.runtimeMetadata.campaignKey").value("creative-q4-launch"))
                .andExpect(jsonPath("$.result.projection.statusView.queueMode").value("reviewer"));
    }

    @Test
    void shutdown_acknowledgesRemoteSessionEnd() throws Exception {
        long now = futureEpochMs(60_000);
        Map<String, Object> shutdownRequest = Map.of(
                "protocolVersion", "remote-domain.v1",
                "requestId", UUID.randomUUID(),
                "operation", "shutdown",
                "sentAtEpochMs", now,
                "deadlineEpochMs", now + 2_000,
                "host", Map.of(
                        "service", "agent-runtime",
                        "instanceId", "test-host",
                        "platformVersion", "0.1.0"),
                "domain", Map.of("domainId", "marketing-ops"),
                "agent", Map.of(
                        "agentId", "agent-42",
                        "personaName", "Launch Coordinator Avery",
                        "interactionMode", "HYBRID"),
                "state", Map.of(
                        "schemaVersion", "marketing-ops.state.v1",
                        "data", Map.of(
                                "personaName", "Launch Coordinator Avery",
                                "focusArea", "campaign-ops",
                                "queueMode", "readiness",
                                "completedWorkCount", 1,
                                "lastAction", "ready",
                                "lastActionAtEpochMs", now,
                                "pendingApprovals", List.of(),
                                "recurringActions", List.of(),
                                "activityLog", List.of())));

        mockMvc.perform(post("/api/remote-domain/v1/shutdown")
                        .contentType(REMOTE_MEDIA_TYPE)
                        .accept(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(shutdownRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.operation").value("shutdown"));
    }

    @Test
    void nativeWindowsUseLogicalTimeAndReplayWithoutDuplicateBusinessWork() throws Exception {
        long start = Instant.parse("2020-01-01T08:30:00Z").toEpochMilli();
        long review = start + 30 * 60_000;
        String reviewFinding = "Internal creative review is complete. Orion has not delivered signed image usage approval; the launch remains blocked.";
        var schedule = new LinkedHashMap<>(nativeSchedule("review", "task", "ESCALATE"));
        schedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFinding", reviewFinding));
        var request = nativePrepare(start, review + 60_000, List.of(schedule));
        JsonNode prepared = nativePost("prepare", request);
        assertThat(nativePost("prepare", request)).isEqualTo(prepared); // Frozen historical deadline also replays.
        assertThat(prepared.at("/nextState/data/lastActionAtEpochMs").longValue()).isEqualTo(start);
        assertThat(prepared.at("/nextState/data/nativeSimulation/nextDueEpochMs").longValue()).isEqualTo(review);
        assertThat(prepared.at("/result/projection/runtimeMetadata/nativeSimulation/observation/pendingApprovals/0/nextAction").asText())
                .isEqualTo("review_asset_package");
        assertThat(prepared.at("/result/projection/runtimeMetadata/nativeSimulation/observation").toString()).doesNotContain(reviewFinding);

        JsonNode empty = nativePost("domain-system-work-window", nativeWindow(prepared, start + 60_000, List.of("task"), 1));
        assertThat(empty.at("/nextState/data/activityLog")).isEqualTo(prepared.at("/nextState/data/activityLog"));
        assertThat(empty.at("/result/projection/runtimeMetadata/nativeSimulation/observation").toString()).doesNotContain(reviewFinding);
        Map<String, Object> window = nativeWindow(empty, review, List.of("task"), 1);
        JsonNode reviewed = nativePost("domain-system-work-window", window);
        assertThat(nativePost("domain-system-work-window", window)).isEqualTo(reviewed);
        assertThat(reviewed.at("/nextState/data/completedWorkCount").intValue()).isEqualTo(1);
        assertThat(reviewed.at("/result/projection/runtimeMetadata/nativeSimulation/observation/queueState").asText()).isEqualTo("blocked");
        JsonNode observation = reviewed.at("/result/projection/runtimeMetadata/nativeSimulation/observation");
        assertThat(observation.at("/pendingApprovals/0/summary").asText()).isEqualTo(reviewFinding);
        assertThat(observation.at("/pendingApprovals/0/lastUpdatedEpochMs").longValue()).isEqualTo(review);
        assertThat(observation.get("lastAction").asText())
                .contains("Recorded an external launch blocker", reviewFinding).doesNotContain("Escalated");
        assertThat(reviewed.at("/result/domainSystemWork/decisionTriggers/0/scheduledForEpochMs").longValue()).isEqualTo(review);
        assertThat(reviewed.at("/nextState/data/nativeSimulation/stateVersion").longValue()).isEqualTo(3);
        assertThat(reviewed.at("/nextState/data/nativeSimulation/noWorkBeforeEpochMs").longValue()).isEqualTo(review + 60_001);

        JsonNode finished = nativePost("domain-system-work-window", nativeWindow(reviewed, review + 60_000, List.of("task"), 1));
        assertThat(finished.at("/nextState/data/completedWorkCount").intValue()).isEqualTo(1);
    }

    @Test
    void nativePrepareIncludesStartAndRejectsPartialOrSkippedBoundaries() throws Exception {
        long review = Instant.parse("2020-01-01T09:00:00Z").toEpochMilli();
        List<Map<String, Object>> schedules = List.of(nativeSchedule("first", "task", "REVIEW"), nativeSchedule("second", "action", "ESCALATE"));
        JsonNode atStart = nativePost("prepare", nativePrepare(review, review + 86_400_000L, schedules));
        assertThat(atStart.at("/nextState/data/completedWorkCount").intValue()).isEqualTo(2);
        assertThat(atStart.at("/result/domainSystemWork/workResults/0/workId").asText()).isEqualTo("first");
        assertThat(atStart.at("/result/domainSystemWork/workResults/1/workId").asText()).isEqualTo("second");
        assertThat(atStart.at("/nextState/data/nativeSimulation/nextDueEpochMs").longValue()).isEqualTo(review + 86_400_000L);

        String currentFinding = "Review completed at launch time: the partner's signed asset licence is still missing.";
        var currentSchedule = new LinkedHashMap<>(nativeSchedule("review-now", "task", "ESCALATE"));
        currentSchedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFinding", currentFinding));
        JsonNode currentOnly = nativePost("prepare", nativePrepare(review, review, List.of(currentSchedule)));
        assertThat(currentOnly.at("/result/projection/runtimeMetadata/nativeSimulation/observation/queueState").asText())
                .isEqualTo("blocked");
        assertThat(currentOnly.at("/result/projection/runtimeMetadata/nativeSimulation/observation/pendingApprovals/0/summary").asText())
                .isEqualTo(currentFinding);
        assertThat(currentOnly.at("/nextState/data/nativeSimulation/processedThroughEpochMs").longValue()).isEqualTo(review);
        assertThat(currentOnly.at("/nextState/data/nativeSimulation/noWorkBeforeEpochMs").longValue()).isEqualTo(review + 1);

        JsonNode before = nativePost("prepare", nativePrepare(review - 60_000, review + 86_400_000L, schedules));
        for (Map<String, Object> invalid : List.of(
                nativeWindow(before, review, List.of("task", "action"), 1),
                nativeWindow(before, review, List.of("task"), 2),
                nativeWindow(before, review + 1, List.of("task", "action"), 2))) {
            mockMvc.perform(post("/api/remote-domain/v1/domain-system-work-window").contentType(REMOTE_MEDIA_TYPE)
                            .header("X-Astraform-Expected-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION)
                            .content(objectMapper.writeValueAsBytes(invalid)))
                    .andExpect(status().isUnprocessableEntity());
        }
        JsonNode applied = nativePost("domain-system-work-window", nativeWindow(before, review, List.of("task", "action"), 2));
        assertThat(applied.at("/nextState/data/completedWorkCount").intValue()).isEqualTo(2);
    }

    @Test
    void nativeModeRejectsUnsupportedPoliciesAndLegacyToolMutation() throws Exception {
        long start = Instant.parse("2020-01-01T08:30:00Z").toEpochMilli();
        var monthly = new LinkedHashMap<>(nativeSchedule("review", "task", "ESCALATE"));
        monthly.put("frequency", "MONTHLY");
        var submillisecond = new LinkedHashMap<>(nativeSchedule("review", "task", "ESCALATE"));
        submillisecond.put("time", "09:00:00.000000001");
        var malformedPayload = new LinkedHashMap<>(nativeSchedule("review", "task", "ESCALATE"));
        malformedPayload.put("payload", "not-an-object");
        var invalidFindings = List.of(" ", 42, "x".repeat(1201)).stream().map(finding -> {
            var schedule = new LinkedHashMap<>(nativeSchedule("review", "task", "ESCALATE"));
            schedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFinding", finding));
            return List.of(schedule);
        }).toList();
        for (Object schedules : java.util.stream.Stream.concat(
                java.util.stream.Stream.of("not-an-array", List.of("not-an-object"), List.of(monthly),
                        List.of(submillisecond), List.of(malformedPayload), List.of(nativeSchedule("review", "task", "PROMPT"))),
                invalidFindings.stream()).toList()) {
            var invalid = nativePrepare(start, start + 86_400_000L, List.of());
            invalid.getPayload().getPersonaConfiguration().put("scheduledWork", schedules);
            mockMvc.perform(post("/api/remote-domain/v1/prepare").contentType(REMOTE_MEDIA_TYPE)
                            .header("X-Astraform-Expected-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION)
                            .content(objectMapper.writeValueAsBytes(invalid)))
                    .andExpect(status().isUnprocessableEntity());
        }
        JsonNode prepared = nativePost("prepare", nativePrepare(start, start + 86_400_000L, List.of(nativeSchedule("review", "task", "ESCALATE"))));
        var state = objectMapper.treeToValue(prepared.get("nextState"), RemoteDomainOpaqueState.class);
        mockMvc.perform(post("/api/remote-domain/v1/execute-work").contentType(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(toolCallRequest(state, "post_review_decision", Map.of("decision", "APPROVE"), start))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/remote-domain/v1/manifest").header("X-Astraform-Expected-Provider-Revision", "old-provider"))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/remote-domain/v1/prepare").contentType(REMOTE_MEDIA_TYPE)
                        .content(objectMapper.writeValueAsBytes(nativePrepare(start, start + 86_400_000L, List.of(nativeSchedule("review", "task", "ESCALATE"))))))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void nativeReviewFacts_materializeOnlyWhenDueAndKeepTheirSelectedApproval() throws Exception {
        long start = Instant.parse("2020-01-01T08:30:00Z").toEpochMilli();
        long review = start + 30 * 60_000;
        JsonNode seeded = nativePost("prepare", nativePrepare(start, review + 60_000, List.of()));
        String targetId = seeded.at("/nextState/data/pendingApprovals/1/approvalId").asText();
        String untouchedId = seeded.at("/nextState/data/pendingApprovals/0/approvalId").asText();
        String finding = "Readiness review completed; the signed usage licence is missing.";
        var schedule = new LinkedHashMap<>(nativeSchedule("readiness-review", "task", "ESCALATE"));
        schedule.put("payload", Map.of("actionType", "ESCALATE", "approvalId", targetId,
                "reviewFinding", finding, "reviewFacts", blockedReviewFacts()));
        JsonNode prepared = nativePost("prepare", nativePrepare(start, review + 60_000, List.of(schedule)));
        JsonNode initial = prepared.at("/result/projection/runtimeMetadata/nativeSimulation/observation/selectedReview");
        assertThat(initial.at("/reviewFacts/reviewCompletionStatus").asText()).isEqualTo("UNREPORTED");
        assertThat(initial.at("/reviewFacts/documentSupplier/knowledge").asText()).isEqualTo("UNREPORTED");
        assertThat(initial.toString()).doesNotContain(finding, "external production studio");

        JsonNode reviewed = nativePost("domain-system-work-window", nativeWindow(prepared, review, List.of("task"), 1));
        JsonNode selected = reviewed.at("/result/projection/runtimeMetadata/nativeSimulation/observation/selectedReview");
        assertThat(selected.get("approvalId").asText()).isEqualTo(targetId);
        assertThat(selected.get("queueState").asText()).isEqualTo("blocked");
        assertThat(selected.get("reviewSummary").asText()).isEqualTo(finding);
        assertThat(selected.at("/reviewFacts/reviewCompletionStatus").asText()).isEqualTo("COMPLETED");
        assertThat(selected.at("/reviewFacts/launchApprovalStatus").asText()).isEqualTo("BLOCKED");
        assertThat(selected.at("/reviewFacts/licenceDocument/value").asText()).isEqualTo("MISSING");
        assertThat(selected.at("/reviewFacts/documentSupplier/value").asText()).isEqualTo("external production studio");
        assertThat(selected.at("/reviewFacts/coordinationOwner/knowledge").asText()).isEqualTo("UNKNOWN");
        assertThat(selected.at("/reviewFacts/coordinationOwner/value").isMissingNode()).isTrue();
        assertThat(selected.at("/reviewFacts/rightsHolder/knowledge").asText()).isEqualTo("UNREPORTED");
        assertThat(selected.get("reviewedAtEpochMs").longValue()).isEqualTo(review);
        assertThat(reviewed.at("/nextState/data/pendingApprovals/0/approvalId").asText()).isEqualTo(untouchedId);
        assertThat(reviewed.at("/nextState/data/pendingApprovals/0/state").asText())
                .isEqualTo(prepared.at("/nextState/data/pendingApprovals/0/state").asText());
        assertThat(reviewed.at("/nextState/data/selectedReview")).isEqualTo(selected);

        JsonNode advanced = nativePost("domain-system-work-window", nativeWindow(reviewed, review + 60_000, List.of("task"), 1));
        assertThat(advanced.at("/result/projection/runtimeMetadata/nativeSimulation/observation/selectedReview"))
                .isEqualTo(selected);
        var saved = objectMapper.treeToValue(advanced.get("nextState"), RemoteDomainOpaqueState.class);
        JsonNode status = nativePost("status", statusRequest(saved, futureEpochMs(60_000)));
        assertThat(status.at("/result/projection/statusView/selectedReview")).isEqualTo(selected);
    }

    @Test
    void historicalStateWithoutReviewFacts_remainsUnreportedInsteadOfInferringFromProse() throws Exception {
        long start = Instant.parse("2020-01-01T09:00:00Z").toEpochMilli();
        JsonNode prepared = nativePost("prepare", nativePrepare(start, start + 60_000, List.of()));
        var historical = prepared.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) historical.at("/nextState/data")).remove("selectedReview");
        var saved = objectMapper.treeToValue(historical.get("nextState"), RemoteDomainOpaqueState.class);
        JsonNode status = nativePost("status", statusRequest(saved, futureEpochMs(60_000)));
        JsonNode selected = status.at("/result/projection/statusView/selectedReview");
        assertThat(selected.get("approvalId").asText()).isEqualTo(historical.at("/nextState/data/pendingApprovals/0/approvalId").asText());
        assertThat(selected.at("/reviewFacts/reviewCompletionStatus").asText()).isEqualTo("UNREPORTED");
        assertThat(selected.at("/reviewFacts/launchApprovalStatus").asText()).isEqualTo("UNREPORTED");
        assertThat(selected.at("/reviewFacts/licenceDocument/knowledge").asText()).isEqualTo("UNREPORTED");
        assertThat(selected.at("/reviewFacts/licenceDocument/value").isMissingNode()).isTrue();
        nativePost("domain-system-work-window", nativeWindow(historical, start + 60_000, List.of("task"), 1));
    }

    @Test
    void nativeReviewFacts_rejectFalseKnowledgeAndConflictingOrMissingApprovalTargets() throws Exception {
        long review = Instant.parse("2020-01-01T09:00:00Z").toEpochMilli();
        List<Map<String, Object>> invalidFacts = List.of(
                Map.of("coordinationOwner", Map.of("knowledge", "UNKNOWN", "value", "unassigned")),
                Map.of("documentSupplier", Map.of("knowledge", "KNOWN")),
                Map.of("licenceDocument", Map.of("knowledge", "KNOWN", "value", "APPROVED")),
                Map.of("reviewCompletionStatus", "DONE"),
                Map.of("launchApprovalStatus", "APPROVED"));
        for (Map<String, Object> facts : invalidFacts) {
            var schedule = new LinkedHashMap<>(nativeSchedule("readiness-review", "task", "ESCALATE"));
            schedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFacts", facts));
            nativeRejected("prepare", nativePrepare(review, review, List.of(schedule)));
        }
        var schedule = new LinkedHashMap<>(nativeSchedule("readiness-review", "task", "ESCALATE"));
        schedule.put("payload", Map.of("actionType", "ESCALATE", "approvalId", "missing-approval",
                "reviewFacts", blockedReviewFacts()));
        nativeRejected("prepare", nativePrepare(review, review, List.of(schedule)));

        schedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFacts", Map.of("launchApprovalStatus", "UNKNOWN")));
        JsonNode uncertain = nativePost("prepare", nativePrepare(review, review, List.of(schedule)));
        assertThat(uncertain.at("/result/projection/runtimeMetadata/nativeSimulation/observation/selectedReview/reviewFacts/launchApprovalStatus").asText())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void persistedReviewFacts_rejectMalformedKnowledgeAndNonIntegralObservationTime() throws Exception {
        long review = Instant.parse("2020-01-01T09:00:00Z").toEpochMilli();
        var schedule = new LinkedHashMap<>(nativeSchedule("readiness-review", "task", "ESCALATE"));
        schedule.put("payload", Map.of("actionType", "ESCALATE", "reviewFacts", blockedReviewFacts()));
        JsonNode prepared = nativePost("prepare", nativePrepare(review, review + 60_000, List.of(schedule)));
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("reviewFacts", Map.of("coordinationOwner", Map.of("knowledge", "UNKNOWN", "value", "unassigned"))),
                Map.<String, Object>of("reviewFacts", Map.of("reviewCompletionStatus", "DONE")),
                Map.<String, Object>of("reviewedAtEpochMs", 1.5))) {
            var saved = objectMapper.treeToValue(prepared.get("nextState"), RemoteDomainOpaqueState.class);
            var selected = new LinkedHashMap<>((Map<String, Object>) saved.getData().get("selectedReview"));
            selected.putAll(invalid);
            saved.getData().put("selectedReview", selected);
            nativeRejected("status", statusRequest(saved, futureEpochMs(60_000)));
        }
    }

    private Map<String, Object> blockedReviewFacts() {
        return Map.of("reviewCompletionStatus", "COMPLETED", "launchApprovalStatus", "BLOCKED",
                "licenceDocument", Map.of("knowledge", "KNOWN", "value", "MISSING"),
                "documentSupplier", Map.of("knowledge", "KNOWN", "value", "external production studio"),
                "coordinationOwner", Map.of("knowledge", "UNKNOWN"));
    }

    private void nativeRejected(String operation, Object request) throws Exception {
        mockMvc.perform(post("/api/remote-domain/v1/" + operation).contentType(REMOTE_MEDIA_TYPE)
                        .header("X-Astraform-Expected-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity());
    }

    private Map<String, Object> nativeSchedule(String id, String kind, String action) {
        return Map.of("workId", id, "description", "Scheduled campaign review", "kind", kind,
                "frequency", "DAILY", "time", "09:00", "payload", Map.of("actionType", action));
    }

    private RemoteDomainPrepareRequest nativePrepare(long start, long horizon, List<Map<String, Object>> schedules) {
        var request = prepareRequest("Launch Coordinator Avery", start - 86_400_000L);
        request.getPayload().getPersonaConfiguration().remove("actions");
        request.getPayload().getPersonaConfiguration().put("scheduledWork", schedules);
        request.getPayload().setInitialProjectionHints(Map.of("nativeSimulation", Map.of(
                "profile", MarketingOpsRemoteDomainService.NATIVE_PROFILE, "contextId", "marketing-test",
                "expectedStateVersion", 0, "logicalTimeEpochMs", start, "logicalHorizonEpochMs", horizon)));
        return request;
    }

    private JsonNode nativePost(String operation, Object request) throws Exception {
        String response = mockMvc.perform(post("/api/remote-domain/v1/" + operation).contentType(REMOTE_MEDIA_TYPE)
                        .header("X-Astraform-Expected-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Astraform-Provider-Revision", MarketingOpsRemoteDomainService.NATIVE_PROVIDER_REVISION))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Map<String, Object> nativeWindow(JsonNode accepted, long through, List<String> kinds, int maxWork) {
        JsonNode saved = accepted.at("/nextState/data/nativeSimulation");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocolVersion", "remote-domain.v1");
        request.put("requestId", UUID.randomUUID());
        request.put("operation", "domain-system-work-window");
        request.put("idempotencyKey", "window:" + saved.get("stateVersion"));
        request.put("sentAtEpochMs", 1L);
        request.put("deadlineEpochMs", 2L);
        request.put("host", Map.of("service", "agent-runtime", "instanceId", "test-host", "platformVersion", "0.1.0"));
        request.put("domain", Map.of("domainId", "marketing-ops"));
        request.put("agent", Map.of("agentId", "agent-42", "personaName", "Launch Coordinator Avery"));
        request.put("state", accepted.get("nextState"));
        request.put("payload", Map.of("timeWindow", Map.of("previousTimeEpochMs", saved.get("processedThroughEpochMs"), "currentTimeEpochMs", through),
                "workKinds", kinds, "maxWorkItems", maxWork, "executionMode", "EVALUATE_AND_EXECUTE", "context", Map.of("nativeSimulation", Map.of(
                        "profile", MarketingOpsRemoteDomainService.NATIVE_PROFILE, "contextId", "marketing-test",
                        "expectedStateVersion", saved.get("stateVersion"), "logicalHorizonEpochMs", saved.get("logicalHorizonEpochMs")))));
        return request;
    }

    private RemoteDomainPrepareRequest prepareRequest(String personaName, long now) {
        RemoteDomainPrepareRequest request = new RemoteDomainPrepareRequest();
        request.setProtocolVersion(RemoteDomainPrepareRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainPrepareRequest.OperationEnum.PREPARE);
        request.setIdempotencyKey("prepare:" + personaName);
        request.setSentAtEpochMs(now);
        request.setDeadlineEpochMs(now + 10_000);
        request.setHost(new RemoteDomainHostContext("agent-runtime", "test-host", "0.1.0"));
        request.setDomain(new RemoteDomainContext("marketing-ops"));
        request.setAgent(new RemoteDomainAgentContext("agent-42", personaName));
        request.getAgent().setConversationId("conv-42");
        request.getAgent().setInteractionMode("HYBRID");
        request.getAgent().setAgentType("Customer");
        request.getAgent().setTraits(new java.util.ArrayList<>(List.of(
                "cross-functional",
                "launch-focused",
                "segment:campaign-ops")));
        RemoteDomainPrepareRequestAllOfPayload payload = new RemoteDomainPrepareRequestAllOfPayload();
        payload.setPersonaConfiguration(new java.util.LinkedHashMap<>(Map.of(
                "personaName", personaName,
                "traits", List.of("cross-functional", "launch-focused", "segment:campaign-ops"),
                "domainConfig", Map.of(
                        "campaignKey", "spring-launch",
                        "focusArea", "launch-ops",
                        "queueMode", "readiness",
                        "seedApprovalCount", 2),
                "goals", List.of(Map.of(
                        "goalId", "launch_without_slippage",
                        "description", "Ship campaigns on time without bypassing review gates.")),
                "actions", List.of(Map.of(
                        "description", "Morning launch-readiness sweep",
                        "frequency", "DAILY",
                        "actionType", "DETERMINISTIC_TURN",
                        "time", "09:15")),
                "tools", List.of("get_campaign_brief", "list_launch_blockers"))));
        request.setPayload(payload);
        return request;
    }

    private RemoteDomainExecuteWorkRequest executeWorkRequest(RemoteDomainOpaqueState state, long now) {
        RemoteDomainExecuteWorkRequest request = new RemoteDomainExecuteWorkRequest();
        request.setProtocolVersion(RemoteDomainExecuteWorkRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainExecuteWorkRequest.OperationEnum.EXECUTE_WORK);
        request.setIdempotencyKey("execute:agent-42:1");
        request.setSentAtEpochMs(now);
        request.setDeadlineEpochMs(now + 5_000);
        request.setHost(new RemoteDomainHostContext("agent-runtime", "test-host", "0.1.0"));
        request.setDomain(new RemoteDomainContext("marketing-ops"));
        request.setAgent(new RemoteDomainAgentContext("agent-42", "Launch Coordinator Avery"));
        request.getAgent().setConversationId("conv-42");
        request.getAgent().setInteractionMode("HYBRID");
        request.getAgent().setAgentType("Customer");
        request.setState(state);

        RemoteDomainTurnContextTimeWindow timeWindow = new RemoteDomainTurnContextTimeWindow(now - 60_000, now);
        RemoteDomainTurnContext turnContext = new RemoteDomainTurnContext(timeWindow, new java.util.ArrayList<>());
        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                "work-1",
                "SCHEDULED_ACTION",
                "launch-readiness|2026-04-16T14:10:00Z",
                now,
                1);
        dueWork.setDescription("Launch readiness sweep");
        dueWork.setPayload(new java.util.LinkedHashMap<>(Map.of(
                "actionType", "RUN_READINESS_CHECK",
                "campaignId", "launch-without-slippage")));
        turnContext.setDueWork(new java.util.ArrayList<>(List.of(dueWork)));

        RemoteDomainExecuteWorkRequestAllOfPayload payload = new RemoteDomainExecuteWorkRequestAllOfPayload(turnContext);
        request.setPayload(payload);
        return request;
    }

    private RemoteDomainExecuteWorkRequest toolCallRequest(
            RemoteDomainOpaqueState state,
            String toolName,
            Map<String, Object> arguments,
            long now) {
        RemoteDomainExecuteWorkRequest request = executeWorkRequest(state, now);
        request.setIdempotencyKey("execute:agent-42:tool:" + toolName);
        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                "tool-" + toolName,
                "TOOL_CALL",
                "marketing-ops:tool:" + toolName,
                now,
                1);
        dueWork.setDescription("Tool call " + toolName);
        dueWork.setPayload(new java.util.LinkedHashMap<>(Map.of(
                "kind", "tool-call",
                "toolName", toolName,
                "arguments", arguments)));
        RemoteDomainTurnContext turnContext = new RemoteDomainTurnContext(
                new RemoteDomainTurnContextTimeWindow(now - 1_000, now),
                new java.util.ArrayList<>(List.of(dueWork)));
        request.setPayload(new RemoteDomainExecuteWorkRequestAllOfPayload(turnContext));
        return request;
    }

    private RemoteDomainStatusRequest statusRequest(RemoteDomainOpaqueState state, long now) {
        RemoteDomainStatusRequest request = new RemoteDomainStatusRequest();
        request.setProtocolVersion(RemoteDomainStatusRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainStatusRequest.OperationEnum.STATUS);
        request.setSentAtEpochMs(now);
        request.setDeadlineEpochMs(now + 2_000);
        request.setHost(new RemoteDomainHostContext("agent-runtime", "test-host", "0.1.0"));
        request.setDomain(new RemoteDomainContext("marketing-ops"));
        request.setAgent(new RemoteDomainAgentContext("agent-42", "Launch Coordinator Avery"));
        request.getAgent().setConversationId("conv-42");
        request.getAgent().setInteractionMode("HYBRID");
        request.setState(state);
        return request;
    }

    private long futureEpochMs(long offsetMs) {
        return Instant.now().toEpochMilli() + offsetMs;
    }
}
