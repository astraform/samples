package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.model.CreativeAgencyReply;
import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import tools.jackson.databind.ObjectMapper;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyCommand;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyGenerationException;
import ai.astraform.samples.marketingops.creativeagency.service.CreativeAgencyScenarioService;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CreativeAgencyAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(CreativeAgencyAgentExecutor.class);

    // Provider-owned labels accompany the accepted reply; model text remains unchanged.
    private static final Map<String, Object> RESULT_PRESENTATION = Map.of(
            "schemaVersion", "astraform.result-presentation.v1",
            "fields", List.of(
                    Map.of("pointer", "/disposition", "label", "Agency review disposition"),
                    Map.of("pointer", "/scenario", "label", "Review scenario"),
                    Map.of("pointer", "/metadata/turnCount", "label", "Agency conversation turn"),
                    Map.of("pointer", "/metadata/followUp", "label", "Follow-up reply"),
                    Map.of("pointer", "/metadata/generationFallback", "label", "Fallback used"),
                    Map.of("pointer", "/metadata/generationContentAuthoritative", "label", "Provider content-authority flag")));

    private final CreativeAgencyScenarioService scenarioService;
    private final CreativeAgencyA2AAdmissionGate admissionGate;
    private final CreativeAgencyA2aProperties properties;
    private final ObjectMapper objectMapper;

    public CreativeAgencyAgentExecutor(
            CreativeAgencyScenarioService scenarioService,
            CreativeAgencyA2AAdmissionGate admissionGate,
            CreativeAgencyA2aProperties properties,
            ObjectMapper objectMapper) {
        this.scenarioService = scenarioService;
        this.admissionGate = admissionGate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        CreativeAgencyA2AAdmissionGate.Lease admissionLease = admissionGate.executionLease(context);
        if (admissionLease != null && !admissionLease.beginExecution()) {
            emitter.fail(new InternalError("A2A request admission expired before execution"));
            return;
        }

        try {
            if (context.getTask() == null) {
                emitter.submit();
            }
            emitter.startWork();

            try {
                CreativeAgencyReply reply = scenarioService.buildReply(toCommand(context));
                Message response = emitter.newAgentMessage(
                        List.of(new TextPart(responseText(reply))),
                        reply.metadata());
                emitter.complete(response);
            } catch (CreativeAgencyGenerationException failure) {
                log.warn("Creative agency generation failed safely: code={}", failure.code());
                Message response = emitter.newAgentMessage(
                        List.of(new TextPart("""
                                Creative Agency Response
                                Generation failed safely; no deterministic fallback was substituted.
                                Failure code: %s
                                """.formatted(failure.code()).trim())),
                        Map.of(
                                "service", "marketing-ops-creative-agency",
                                "disposition", "GENERATION_FAILED",
                                "generationFailureCode", failure.code().name(),
                                "generationFallback", false));
                emitter.fail(response);
            }
        } finally {
            if (admissionLease != null) {
                admissionLease.completeExecution();
            }
        }
    }

    private String responseText(CreativeAgencyReply reply) {
        if (properties.getResponseFormat() == CreativeAgencyA2aProperties.ResponseFormat.TEXT) {
            return reply.responseText();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "marketing.creative-agency.response.v1");
        response.put("scenario", reply.scenario().value());
        response.put("disposition", reply.disposition());
        response.put("responseText", reply.responseText());
        response.put("metadata", reply.metadata());
        response.put("presentation", RESULT_PRESENTATION);
        return objectMapper.writeValueAsString(response);
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
        // A remote OpenAI-compatible transport cannot guarantee interruption once a call
        // is in flight. Reject cancellation rather than report a false terminal
        // state that a late provider response could overwrite.
        throw new TaskNotCancelableError();
    }

    private CreativeAgencyCommand toCommand(RequestContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context.getMessage() != null && context.getMessage().metadata() != null) {
            metadata.putAll(context.getMessage().metadata());
        }
        if (context.getMetadata() != null) {
            metadata.putAll(context.getMetadata());
        }
        return new CreativeAgencyCommand(
                context.getContextId(),
                context.getUserInput("\n"),
                metadata);
    }
}
