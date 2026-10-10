package ai.astraform.samples.marketingops.creativeagency.a2a;

import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyA2aProperties;
import ai.astraform.samples.marketingops.creativeagency.config.CreativeAgencyGenerationProperties;
import org.a2aproject.sdk.server.config.A2AConfigProvider;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.AuthorizationRequestHandlerDecorator;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class CreativeAgencyA2AConfiguration {

    static final String AGENT_EXECUTOR = "creativeAgencyA2aAgentExecutor";
    static final String EVENT_CONSUMER_EXECUTOR = "creativeAgencyA2aEventConsumerExecutor";

    @Bean
    public A2AConfigProvider creativeAgencyA2AConfigProvider(CreativeAgencyA2aProperties properties) {
        Map<String, String> values = Map.of(
                "a2a.blocking.agent.timeout.seconds", String.valueOf(properties.getBlockingAgentTimeoutSeconds()),
                "a2a.blocking.consumption.timeout.seconds", String.valueOf(properties.getBlockingConsumptionTimeoutSeconds()),
                "a2a.blocking.reconciliation.timeout.seconds", String.valueOf(properties.getBlockingReconciliationTimeoutSeconds()),
                "a2a.request-context.populate-referred-tasks", "false");
        return new A2AConfigProvider() {
            @Override
            public String getValue(String name) {
                String value = values.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("Unsupported A2A configuration key: " + name);
                }
                return value;
            }

            @Override
            public Optional<String> getOptionalValue(String name) {
                return Optional.ofNullable(values.get(name));
            }
        };
    }

    @Bean(name = AGENT_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService creativeAgencyA2aAgentExecutor(CreativeAgencyA2aProperties properties) {
        int capacity = properties.getMaxConcurrentRequests();
        return new ThreadPoolExecutor(
                capacity,
                capacity,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().name("creative-agency-agent-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = EVENT_CONSUMER_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService creativeAgencyA2aEventConsumerExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("creative-agency-events-", 0).factory());
    }

    @Bean
    public InMemoryTaskStore creativeAgencyTaskStore(
            TaskAuthorizationProvider creativeAgencyTaskAuthorizationProvider) {
        return new InMemoryTaskStore(creativeAgencyTaskAuthorizationProvider);
    }

    @Bean
    public MainEventBus creativeAgencyMainEventBus() {
        return new MainEventBus();
    }

    @Bean
    public QueueManager creativeAgencyQueueManager(
            InMemoryTaskStore creativeAgencyTaskStore,
            MainEventBus creativeAgencyMainEventBus) {
        return new InMemoryQueueManager(creativeAgencyTaskStore, creativeAgencyMainEventBus);
    }

    @Bean
    public PushNotificationConfigStore creativeAgencyPushNotificationConfigStore() {
        return new InMemoryPushNotificationConfigStore();
    }

    @Bean
    public PushNotificationSender creativeAgencyPushNotificationSender() {
        return (event, taskSnapshot) -> {
            // The card advertises pushNotifications=false, so delivery is intentionally disabled.
        };
    }

    @Bean(initMethod = "ensureStarted")
    public MainEventBusProcessor creativeAgencyMainEventBusProcessor(
            MainEventBus creativeAgencyMainEventBus,
            InMemoryTaskStore creativeAgencyTaskStore,
            PushNotificationSender creativeAgencyPushNotificationSender,
            QueueManager creativeAgencyQueueManager) {
        return new MainEventBusProcessor(
                creativeAgencyMainEventBus,
                creativeAgencyTaskStore,
                creativeAgencyPushNotificationSender,
                creativeAgencyQueueManager);
    }

    @Bean
    public AgentCard creativeAgencyAgentCard(
            CreativeAgencyA2aProperties a2aProperties,
            CreativeAgencyGenerationProperties generationProperties) {
        String endpoint = withoutTrailingSlash(a2aProperties.getBaseUrl()) + "/a2a";
        AgentCard.Builder builder = AgentCard.builder()
                .name(a2aProperties.getName())
                .description(a2aProperties.getDescription())
                .provider(new AgentProvider("Astraform", a2aProperties.getDocumentationUrl()))
                .version(a2aProperties.getVersion())
                .documentationUrl(a2aProperties.getDocumentationUrl())
                .supportedInterfaces(List.of(new AgentInterface(
                        TransportProtocol.JSONRPC.asString(),
                        endpoint)))
                .capabilities(AgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .extendedAgentCard(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(
                        skill("creative-rework", "Creative Rework",
                                "Turns vague or weak asset requests into a constrained rework plan.",
                                List.of("creative", "revision", "marketing-ops"),
                                "Rework this launch asset for paid social by tomorrow."),
                        skill("asset-review", "Asset Review",
                                "Returns approval notes or change requests for brand/compliance review.",
                                List.of("review", "brand", "compliance"),
                                "Review this hero banner for approval risk."),
                        skill("launch-blocker", "Launch Blocker Triage",
                                "Produces a concrete unblock plan or escalation path for launch blockers.",
                                List.of("launch", "blocker", "handoff"),
                                "We are blocked on vendor approval and the launch is at risk.")));

        if (generationProperties.getMode() == CreativeAgencyGenerationProperties.Mode.LLM
                || (a2aProperties.getInboundToken() != null
                        && !a2aProperties.getInboundToken().isBlank())) {
            String scheme = "astraformAgentToken";
            builder.securitySchemes(Map.of(
                            scheme,
                            APIKeySecurityScheme.builder()
                                    .location(APIKeySecurityScheme.Location.HEADER)
                                    .name(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER)
                                    .description("Partner token required for protected A2A calls.")
                                    .build()))
                    .securityRequirements(List.of(
                            SecurityRequirement.builder()
                                    .scheme(scheme, List.of())
                                    .build()));
        }
        return builder.build();
    }

    @Bean
    public DefaultRequestHandler creativeAgencyRequestHandler(
            CreativeAgencyAgentExecutor creativeAgencyAgentExecutor,
            InMemoryTaskStore creativeAgencyTaskStore,
            QueueManager creativeAgencyQueueManager,
            PushNotificationConfigStore creativeAgencyPushNotificationConfigStore,
            MainEventBusProcessor creativeAgencyMainEventBusProcessor,
            @Qualifier(AGENT_EXECUTOR) Executor agentExecutor,
            @Qualifier(EVENT_CONSUMER_EXECUTOR) Executor eventConsumerExecutor) {
        return new DefaultRequestHandler(
                creativeAgencyAgentExecutor,
                creativeAgencyTaskStore,
                creativeAgencyQueueManager,
                creativeAgencyPushNotificationConfigStore,
                creativeAgencyMainEventBusProcessor,
                agentExecutor,
                eventConsumerExecutor);
    }

    @Bean
    public JSONRPCHandler creativeAgencyJsonRpcHandler(
            AgentCard creativeAgencyAgentCard,
            DefaultRequestHandler creativeAgencyRequestHandler,
            TaskAuthorizationProvider creativeAgencyTaskAuthorizationProvider,
            @Qualifier(AGENT_EXECUTOR) Executor agentExecutor) {
        return new JSONRPCHandler(
                creativeAgencyAgentCard,
                new AuthorizationRequestHandlerDecorator(
                        creativeAgencyRequestHandler,
                        creativeAgencyTaskAuthorizationProvider),
                agentExecutor);
    }

    private AgentSkill skill(
            String id,
            String name,
            String description,
            List<String> tags,
            String example) {
        return AgentSkill.builder()
                .id(id)
                .name(name)
                .description(description)
                .tags(tags)
                .examples(List.of(example))
                .inputModes(List.of("text"))
                .outputModes(List.of("text"))
                .build();
    }

    private String withoutTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
