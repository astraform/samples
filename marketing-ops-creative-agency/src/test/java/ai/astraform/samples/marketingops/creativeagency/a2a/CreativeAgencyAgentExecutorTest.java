package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyGenerationException;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyCommand;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyScenarioService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyReply;
import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyScenarioKey;
import tools.jackson.databind.json.JsonMapper;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreativeAgencyAgentExecutorTest {

    @Test
    void turnsProviderFailureIntoSafeFailedA2aTaskWithoutFallback() throws Exception {
        CreativeAgencyScenarioService scenarioService = mock(CreativeAgencyScenarioService.class);
        RequestContext context = mock(RequestContext.class);
        AgentEmitter emitter = mock(AgentEmitter.class);
        when(context.getContextId()).thenReturn("context-1");
        when(context.getUserInput("\n")).thenReturn("Review the asset.");
        when(scenarioService.buildReply(any(CreativeAgencyCommand.class))).thenThrow(
                new CreativeAgencyGenerationException(CreativeAgencyGenerationException.Code.AUTH_REJECTED));
        Message failureMessage = Message.builder()
                .role(Message.Role.ROLE_AGENT)
                .messageId("failure-message")
                .contextId("context-1")
                .parts(new TextPart("Generation failed safely."))
                .metadata(Map.of(
                        "generationFailureCode", "AUTH_REJECTED",
                        "generationFallback", false))
                .build();
        when(emitter.newAgentMessage(anyList(), anyMap())).thenReturn(failureMessage);

        new CreativeAgencyAgentExecutor(scenarioService, admissionGate(), new CreativeAgencyA2aProperties(),
                JsonMapper.builder().build()).execute(context, emitter);

        verify(emitter).submit();
        verify(emitter).startWork();
        verify(emitter).fail(failureMessage);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> parts = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(emitter).newAgentMessage(parts.capture(), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("generationFailureCode", "AUTH_REJECTED")
                .containsEntry("generationFallback", false);
        assertThat(((TextPart) parts.getValue().getFirst()).text())
                .doesNotContain("API key", "secret");
    }

    @ParameterizedTest
    @EnumSource(CreativeAgencyA2aProperties.ResponseFormat.class)
    void responseFormatPreservesActualDecisionAndLegacyNarrative(CreativeAgencyA2aProperties.ResponseFormat format) {
        var mapper = JsonMapper.builder().build();
        var properties = new CreativeAgencyA2aProperties();
        properties.setResponseFormat(format);
        CreativeAgencyScenarioService scenarioService = mock(CreativeAgencyScenarioService.class);
        RequestContext context = mock(RequestContext.class);
        AgentEmitter emitter = mock(AgentEmitter.class);
        // The accepted structured decision is serialized independently from narrative text.
        var reply = new CreativeAgencyReply(CreativeAgencyScenarioKey.LAUNCH_BLOCKER, "ESCALATE",
                "Creative Agency Response\nNarrative says \"approved\"; this is not authoritative.",
                Map.of("generationContentAuthoritative", false, "generationFallback", false));
        when(scenarioService.buildReply(any(CreativeAgencyCommand.class))).thenReturn(reply);
        new CreativeAgencyAgentExecutor(scenarioService, admissionGate(), properties, mapper).execute(context, emitter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Part<?>>> parts = ArgumentCaptor.forClass(List.class);
        verify(emitter).newAgentMessage(parts.capture(), org.mockito.ArgumentMatchers.eq(reply.metadata()));
        String text = ((TextPart) parts.getValue().getFirst()).text();
        if (format == CreativeAgencyA2aProperties.ResponseFormat.TEXT) {
            assertThat(text).isEqualTo(reply.responseText());
        } else {
            var serialized = mapper.readTree(text);
            assertThat(serialized.path("disposition").stringValue()).isEqualTo(reply.disposition());
            assertThat(serialized.path("responseText").stringValue()).isEqualTo(reply.responseText());
            assertThat(serialized.path("metadata")).isEqualTo(mapper.valueToTree(reply.metadata()));
            var presentation = serialized.path("presentation");
            assertThat(presentation.path("schemaVersion").stringValue())
                    .isEqualTo("astraform.result-presentation.v1");
            var fields = presentation.path("fields");
            assertThat(fields).anySatisfy(field -> {
                assertThat(field.path("pointer").stringValue()).isEqualTo("/disposition");
                assertThat(field.path("label").stringValue()).isEqualTo("Agency review disposition");
            });
            assertThat(serialized.path("metadata").path("generationContentAuthoritative").asBoolean()).isFalse();
        }
    }

    @Test
    void rejectsCancellationRatherThanReportingFalseProviderCancellation() {
        CreativeAgencyAgentExecutor executor = new CreativeAgencyAgentExecutor(
                mock(CreativeAgencyScenarioService.class),
                admissionGate(), new CreativeAgencyA2aProperties(), JsonMapper.builder().build());
        RequestContext context = mock(RequestContext.class);
        AgentEmitter emitter = mock(AgentEmitter.class);

        assertThrows(TaskNotCancelableError.class, () -> executor.cancel(context, emitter));

        verifyNoInteractions(emitter);
    }

    private CreativeAgencyA2AAdmissionGate admissionGate() {
        return new CreativeAgencyA2AAdmissionGate(new CreativeAgencyA2aProperties());
    }
}
