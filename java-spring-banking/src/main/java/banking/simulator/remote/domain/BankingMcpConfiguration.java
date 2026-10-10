package banking.simulator.remote.domain;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Official MCP transport; account selection is supplied by the trusted host binding. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "banking.backend.enabled", havingValue = "true")
public class BankingMcpConfiguration {
    @Bean
    HttpServletStreamableServerTransportProvider bankingMcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> bankingMcpServlet(
            HttpServletStreamableServerTransportProvider bankingMcpTransport) {
        var registration = new ServletRegistrationBean<>(bankingMcpTransport, "/mcp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer bankingMcpServer(HttpServletStreamableServerTransportProvider bankingMcpTransport,
            BankingAccountRepository accounts, ObjectMapper mapper) throws IOException {
        return McpServer.sync(bankingMcpTransport)
                .serverInfo("astraform-sample-banking", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(customerAccountsTool(accounts, mapper)).build();
    }

    static McpServerFeatures.SyncToolSpecification customerAccountsTool(
            BankingAccountRepository accounts, ObjectMapper mapper) throws IOException {
        Map<String, Object> outputSchema;
        try (var stream = BankingMcpConfiguration.class.getResourceAsStream("/schemas/banking-customer-accounts.v1.schema.json")) {
            if (stream == null) throw new IllegalStateException("Banking account output schema missing");
            outputSchema = mapper.readValue(stream, new TypeReference<>() {});
        }
        var tool = McpSchema.Tool.builder("get_customer_accounts", Map.of(
                        "type", "object", "properties", Map.of(), "additionalProperties", false))
                .description("Read your own accounts and statements from the bank. No account or customer selectors are accepted; your configured customer context determines access.")
                .outputSchema(outputSchema)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false)
                        .idempotentHint(true).openWorldHint(false).build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                if (request.arguments() == null || !request.arguments().isEmpty()) {
                    throw new IllegalArgumentException("get_customer_accounts accepts exactly {}; customer/account selectors are not allowed");
                }
                Object raw = request.meta() == null ? null : request.meta().get("astraform.customerContext");
                if (!(raw instanceof Map<?, ?> context) || !context.keySet().containsAll(Set.of("contextRef", "subjectRef"))
                        || !Set.of("contextRef", "subjectRef", "readContext").containsAll(context.keySet())) {
                    throw new IllegalArgumentException("A trusted astraform.customerContext binding is required");
                }
                Map<String, Object> guard = null;
                if (context.containsKey("readContext")) {
                    if (!(context.get("readContext") instanceof Map<?, ?>)) throw new IllegalArgumentException("Invalid readContext");
                    guard = mapper.convertValue(context.get("readContext"), new TypeReference<>() {});
                }
                Map<String, Object> result = accounts.read(BankingAccountRepository.reference(context.get("contextRef")),
                        BankingAccountRepository.reference(context.get("subjectRef")), guard);
                return McpSchema.CallToolResult.builder().structuredContent(result)
                        .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(result)))).isError(false).build();
            } catch (IllegalArgumentException | ResponseStatusException invalid) {
                String message = invalid instanceof ResponseStatusException status ? status.getReason() : invalid.getMessage();
                return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(message)))
                        .isError(true).build();
            }
        });
    }
}
