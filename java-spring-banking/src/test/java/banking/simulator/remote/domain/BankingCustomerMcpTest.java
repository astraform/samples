package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "banking.backend.enabled=true", "banking.backend.jdbc-url=jdbc:h2:mem:banking-mcp;DB_CLOSE_DELAY=-1",
        "banking.backend.username=sa", "banking.backend.password=",
        "astraform.native-simulation.enabled=true", "astraform.native-simulation.profile=remote-domain.native-reference-state.v1"
})
class BankingCustomerMcpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired BankingAccountRepository bank;
    @Autowired BankingRemoteDomainService service;

    @Test
    void officialMcpTransportScopesEachCallWhilePartnerCanReadBothCustomers() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        var http = HttpClient.newHttpClient();
        for (var customer : Map.of("customer-a", "3000", "customer-b", "4500").entrySet()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(origin +
                            "/api/banking/v1/contexts/bank-trial/customers/" + customer.getKey()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            BankingAccountPersistenceTest.seed(customer.getKey() + "-account", customer.getValue())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        try (var client = McpClient.sync(HttpClientStreamableHttpTransport.builder(origin).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("get_customer_accounts");
            for (String subject : List.of("customer-a", "customer-b", "customer-a")) {
                var result = client.callTool(request(Map.of(), subject));
                assertThat(result.isError()).isFalse();
                var partner = http.send(HttpRequest.newBuilder(URI.create(origin +
                                "/api/banking/v1/contexts/bank-trial/customers/" + subject + "/accounts")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(result.structuredContent()).isEqualTo(mapper.readValue(partner.body(), new TypeReference<Map<String, Object>>() {}));
                var accounts = (List<?>) ((Map<?, ?>) result.structuredContent()).get("accounts");
                assertThat(accounts).hasSize(1);
                assertThat(((Map<?, ?>) accounts.getFirst()).get("accountId")).isEqualTo(subject + "-account");
            }
            assertThat(client.callTool(request(Map.of("subjectRef", "customer-b"), "customer-a")).isError()).isTrue();
            assertThat(client.callTool(request(Map.of("accountId", "customer-b-account"), "customer-a")).isError()).isTrue();
            assertThat(client.callTool(new McpSchema.CallToolRequest("get_customer_accounts", Map.of(), Map.of())).isError()).isTrue();
        }
    }

    @Test
    void ownedMcpAndPartnerReadsUseExactCommittedVersionAndTime() throws Exception {
        long start = java.time.Instant.parse("2026-01-15T00:00:00Z").toEpochMilli();
        var seed = new java.util.LinkedHashMap<>(BankingAccountPersistenceTest.seed("guarded-account", "3000"));
        seed.put("logicalTimeEpochMs", start); seed.put("statements", List.of());
        bank.seed("guarded", "customer", seed);
        var prepared = service.prepare(BankingAccountPersistenceTest.prepare(mapper, "guarded", "customer", "guarded-native", start));
        var response = service.executeDomainSystemWorkWindow(BankingAccountPersistenceTest.window(mapper, prepared.getNextState(),
                java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()));
        var guard = BankingAccountPersistenceTest.guard(response.getNextState());
        String origin = "http://127.0.0.1:" + port;
        try (var client = McpClient.sync(HttpClientStreamableHttpTransport.builder(origin).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            var context = new java.util.LinkedHashMap<String, Object>(Map.of("contextRef", "guarded", "subjectRef", "customer", "readContext", guard));
            var result = client.callTool(new McpSchema.CallToolRequest("get_customer_accounts", Map.of(), Map.of("astraform.customerContext", context)));
            assertThat(result.isError()).isFalse();
            var partner = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin +
                    "/api/banking/v1/contexts/guarded/customers/customer/accounts?contextId=guarded-native&resourceVersion=2&logicalTimeEpochMs=1769904000000"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(partner.statusCode()).isEqualTo(200);
            assertThat(result.structuredContent()).isEqualTo(mapper.readValue(partner.body(), new TypeReference<Map<String, Object>>() {}));
            context.put("readContext", BankingAccountPersistenceTest.guard(prepared.getNextState()));
            assertThat(client.callTool(new McpSchema.CallToolRequest("get_customer_accounts", Map.of(), Map.of("astraform.customerContext", context))).isError()).isTrue();
            context.remove("readContext");
            var missing = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin +
                    "/api/banking/v1/contexts/guarded/customers/customer/accounts")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(400);
            assertThat(client.callTool(new McpSchema.CallToolRequest("get_customer_accounts", Map.of(), Map.of("astraform.customerContext", context))).isError()).isTrue();
        }
    }

    private McpSchema.CallToolRequest request(Map<String, Object> arguments, String subject) {
        return new McpSchema.CallToolRequest("get_customer_accounts", arguments,
                Map.of("astraform.customerContext", Map.of("contextRef", "bank-trial", "subjectRef", subject)));
    }
}
