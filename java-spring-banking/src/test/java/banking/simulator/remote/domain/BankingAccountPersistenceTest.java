package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class BankingAccountPersistenceTest {
    @TempDir Path directory;

    @Test
    void bankOwnsRecordsAcrossRestartAndSeedCannotReassignAnotherCustomersAccount() {
        var mapper = JsonMapper.builder().build();
        String url = "jdbc:h2:file:" + directory.resolve("bank");
        var first = new BankingAccountRepository(mapper, url, "sa", "", 2);
        Map<String, Object> expected;
        try {
            expected = first.seed("bank-trial", "customer-a", seed("a-account", "3000"));
            first.seed("bank-trial", "customer-b", seed("b-account", "4500"));
            assertThatThrownBy(() -> first.seed("bank-trial", "customer-b", seed("a-account", "4500")))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> first.seed("bank-trial", "customer-c", seed("a-account", "4500")))
                    .isInstanceOf(ResponseStatusException.class);
        } finally { first.close(); }
        var restarted = new BankingAccountRepository(mapper, url, "sa", "", 2);
        try {
            assertThat(restarted.read("bank-trial", "customer-a")).isEqualTo(expected);
            assertThat(restarted.seed("bank-trial", "customer-a", seed("a-account", "3000"))).isEqualTo(expected);
            assertThat(restarted.read("bank-trial", "customer-b").get("accounts"))
                    .isEqualTo(seed("b-account", "4500").get("accounts"));
            assertThatThrownBy(() -> restarted.read("other-trial", "customer-a"))
                    .isInstanceOf(ResponseStatusException.class);
        } finally { restarted.close(); }
    }

    @Test
    void calendarStatementsUpdateBankOnceAndGuardedReadsSurviveRestart() {
        var mapper = JsonMapper.builder().build();
        String url = "jdbc:h2:file:" + directory.resolve("events");
        var bank = new BankingAccountRepository(mapper, url, "sa", "", 2);
        long start = Instant.parse("2026-01-15T00:00:00.123Z").toEpochMilli();
        long february = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli();
        long march = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli();
        var initial = new LinkedHashMap<>(seed("a-account", "3000"));
        initial.put("logicalTimeEpochMs", start);
        initial.put("statements", List.of());
        bank.seed("events", "a", initial);
        bank.seed("events", "b", seed("b-account", "4500"));
        var service = new BankingRemoteDomainService(mapper, true, BankingRemoteDomainService.REFERENCE_PROFILE, bank);
        var prepare = prepare(mapper, "events", "a", "native-a", start);
        var prepared = service.prepare(prepare);
        assertThat(prepared.getNextState().getData()).containsOnlyKeys("nativeSimulation");
        var preparedObservation = mapper.valueToTree(prepared.getResult().getProjection())
                .at("/runtimeMetadata/nativeSimulation/observation");
        assertThat(preparedObservation.path("nextStatementAtEpochMs").longValue()).isEqualTo(february);
        assertThat(preparedObservation.path("observedAtUtc").asText()).isEqualTo("2026-01-15T00:00:00.123Z");
        assertThat(preparedObservation.path("nextStatementAtUtc").asText()).isEqualTo("2026-02-01T00:00:00Z");
        assertThat(mapper.writeValueAsString(prepared.getResult().getProjection())).doesNotContain("a-account", "-1200");
        assertThatThrownBy(() -> service.prepare(prepare(mapper, "events", "a", "competing", start)))
                .isInstanceOf(ai.astraform.remote.domain.sdk.RemoteDomainProtocolException.class);
        var firstRequest = window(mapper, prepared.getNextState(), february);
        var first = service.executeDomainSystemWorkWindow(firstRequest);
        assertThat(mapper.valueToTree(first.getResult().getProjection())
                .at("/runtimeMetadata/nativeSimulation/observation/observedAtUtc").asText())
                .isEqualTo("2026-02-01T00:00:00Z");
        var firstRead = bank.read("events", "a", guard(first.getNextState()));
        var statement = ((List<Map<String, Object>>) firstRead.get("statements")).getFirst();
        assertThat(statement).containsEntry("minimumAmountDue", "48").containsEntry("dueDate", "2026-02-14")
                .containsEntry("statementStartDate", "2026-01-01").containsEntry("statementEndDate", "2026-01-31");
        assertThat(bank.read("events", "b").get("accounts")).isEqualTo(seed("b-account", "4500").get("accounts"));
        var second = service.executeDomainSystemWorkWindow(window(mapper, first.getNextState(), march));
        assertThatThrownBy(() -> bank.read("events", "a", guard(first.getNextState())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> bank.read("events", "a")).isInstanceOf(IllegalArgumentException.class);
        var expected = bank.read("events", "a", guard(second.getNextState()));
        assertThat((List<?>) expected.get("statements")).hasSize(2);
        assertThat(((List<Map<String, Object>>) expected.get("statements")).getLast())
                .containsEntry("statementStartDate", "2026-02-01").containsEntry("statementEndDate", "2026-02-28")
                .containsEntry("dueDate", "2026-03-14");
        var january = bank.read("events", "a", guard(second.getNextState()), "2026-01");
        var februaryRead = bank.read("events", "a", guard(second.getNextState()), "2026-02");
        assertThat(january).containsEntry("statementPeriod", "2026-01")
                .containsEntry("accounts", expected.get("accounts")).containsEntry("version", expected.get("version"))
                .containsEntry("logicalTimeEpochMs", expected.get("logicalTimeEpochMs"));
        assertThat(january.get("statements")).isEqualTo(List.of(statement));
        assertThat(februaryRead.get("statements")).isEqualTo(List.of(((List<?>) expected.get("statements")).getLast()));
        assertThat((List<?>) bank.read("events", "a", guard(second.getNextState()), "2026-03").get("statements")).isEmpty();
        assertThat(((List<Map<String, Object>>) bank.read("events", "b", null, "2026-01").get("statements")))
                .extracting(value -> value.get("accountId")).containsExactly("b-account");
        assertThatThrownBy(() -> bank.read("events", "a", guard(first.getNextState()), "2026-01"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> bank.read("events", "a", null, "2026-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bank.read("events", "b", guard(second.getNextState()), "2026-01"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        String firstResponse = mapper.writeValueAsString(first);
        bank.close();
        var restarted = new BankingAccountRepository(mapper, url, "sa", "", 2);
        try {
            var recovered = new BankingRemoteDomainService(mapper, true, BankingRemoteDomainService.REFERENCE_PROFILE, restarted);
            assertThat(mapper.writeValueAsString(recovered.executeDomainSystemWorkWindow(firstRequest))).isEqualTo(firstResponse);
            assertThat(restarted.read("events", "a", guard(second.getNextState()))).isEqualTo(expected);
            firstRequest.getPayload().getTimeWindow().setCurrentTimeEpochMs(february + 1);
            assertThatThrownBy(() -> recovered.executeDomainSystemWorkWindow(firstRequest))
                    .isInstanceOf(ai.astraform.remote.domain.sdk.RemoteDomainProtocolException.class);
            var expired = window(mapper, second.getNextState(), Instant.parse("2026-04-01T00:00:00Z").toEpochMilli());
            expired.setSentAtEpochMs(1L); expired.setDeadlineEpochMs(2L);
            assertThatThrownBy(() -> recovered.executeDomainSystemWorkWindow(expired)).isInstanceOf(ai.astraform.remote.domain.sdk.RemoteDomainProtocolException.class);
            assertThat(restarted.read("events", "a", guard(second.getNextState()))).isEqualTo(expected);
        } finally { restarted.close(); }
    }

    @Test
    void statementPeriodHttpContractRejectsMalformedValuesAndPreservesDefaultReads() throws Exception {
        var bank = new BankingAccountRepository(JsonMapper.builder().build(),
                "jdbc:h2:file:" + directory.resolve("http-periods"), "sa", "", 2);
        try {
            bank.seed("periods", "a", seed("a-account", "3000"));
            var mvc = MockMvcBuilders.standaloneSetup(new BankingAccountController(bank)).build();
            String route = "/api/banking/v1/contexts/periods/customers/a/accounts";
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.statementPeriod").doesNotExist())
                    .andExpect(jsonPath("$.statements[0].statementEndDate").value("2026-01-31"));
            mvc.perform(get(route).queryParam("statementPeriod", "2026-01")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.statementPeriod").value("2026-01"))
                    .andExpect(jsonPath("$.statements[0].statementEndDate").value("2026-01-31"));
            for (String validEmpty : List.of("0001-01", "2026-02", "9999-12")) {
                mvc.perform(get(route).queryParam("statementPeriod", validEmpty)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.statementPeriod").value(validEmpty))
                        .andExpect(jsonPath("$.statements").isEmpty());
            }
            for (String invalid : List.of("", "0000-01", "10000-01", "2026-00", "2026-13", "2026-1", " 2026-01", "2026-01 ", "2026-01-31", "2026-01\n")) {
                mvc.perform(get(route).queryParam("statementPeriod", invalid)).andExpect(status().isBadRequest());
            }
            mvc.perform(get(route).queryParam("statementPeriod", "2026-01").queryParam("contextId", "incomplete"))
                    .andExpect(status().isBadRequest());
        } finally { bank.close(); }
    }

    @Test
    void defaultReadReturnsLatestTwelveWhileRequestedPeriodUsesFullHistory() throws Exception {
        var mapper = JsonMapper.builder().build();
        String url = "jdbc:h2:file:" + directory.resolve("history");
        var bank = new BankingAccountRepository(mapper, url, "sa", "", 2);
        try {
            var initial = new LinkedHashMap<>(seed("a-account", "3000"));
            LocalDate oldEnd = LocalDate.of(2010, 1, 31);
            var oldest = statement("a-account", "oldest", oldEnd.withDayOfMonth(1), oldEnd);
            initial.put("statements", List.of(oldest));
            bank.seed("history", "a", initial);
            var fixture = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
            for (int index = 1; index <= 100; index++) {
                YearMonth month = YearMonth.of(2010, 1).plusMonths(index);
                persistStatement(fixture, mapper, statement("a-account", "newer-" + index, month.atDay(1), month.atEndOfMonth()));
            }
            assertThat((List<Map<String, Object>>) bank.read("history", "a").get("statements"))
                    .extracting(item -> item.get("billId"))
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(89, 100)
                            .mapToObj(index -> "newer-" + index).toList());
            var mvc = MockMvcBuilders.standaloneSetup(new BankingAccountController(bank)).build();
            mvc.perform(get("/api/banking/v1/contexts/history/customers/a/accounts"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.statements.length()").value(12))
                    .andExpect(jsonPath("$.statements[0].billId").value("newer-89"))
                    .andExpect(jsonPath("$.statements[11].billId").value("newer-100"));
            assertThat(bank.read("history", "a", null, "2010-01").get("statements")).isEqualTo(List.of(oldest));
            // Distinct stored statement windows may share a closing month; never silently drop a matching row.
            for (int index = 0; index < 100; index++) {
                persistStatement(fixture, mapper, statement("a-account", "same-month-" + index,
                        LocalDate.of(2009, 9, 1).plusDays(index), oldEnd));
                if (index == 98) {
                    assertThat((List<?>) bank.read("history", "a", null, "2010-01").get("statements")).hasSize(100);
                }
            }
            assertThatThrownBy(() -> bank.read("history", "a", null, "2010-01"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat((List<?>) bank.read("history", "a").get("statements")).hasSize(12);
        } finally { bank.close(); }
    }

    private static Map<String, Object> statement(String accountId, String billId, LocalDate start, LocalDate end) {
        return Map.of("billId", billId, "accountId", accountId, "closingAmount", "-1200", "minimumAmountDue", "48",
                "currency", "AUD", "statementStartDate", start.toString(), "statementEndDate", end.toString(),
                "dueDate", end.plusDays(14).toString());
    }

    private static void persistStatement(JdbcTemplate jdbc, ObjectMapper mapper, Map<String, Object> statement) {
        jdbc.update("INSERT INTO banking_statement(context_ref,subject_ref,bill_id,account_id,statement_data,statement_start_date,statement_end_date) VALUES(?,?,?,?,?,?,?)",
                "history", "a", statement.get("billId"), statement.get("accountId"), mapper.writeValueAsString(statement),
                statement.get("statementStartDate"), statement.get("statementEndDate"));
    }

    static RemoteDomainPrepareRequest prepare(ObjectMapper mapper, String context, String subject, String nativeContext, long start) {
        var request = envelope("prepare");
        request.put("payload", Map.of("personaConfiguration", Map.of(), "initialProjectionHints", Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.REFERENCE_PROFILE, "contextId", nativeContext, "expectedStateVersion", 0,
                "logicalTimeEpochMs", start, "logicalHorizonEpochMs", Instant.parse("2026-05-01T00:00:00Z").toEpochMilli(),
                "customerContext", Map.of("contextRef", context, "subjectRef", subject)))));
        return mapper.convertValue(request, RemoteDomainPrepareRequest.class);
    }

    static RemoteDomainSystemWorkWindowRequest window(ObjectMapper mapper, RemoteDomainOpaqueState state, long through) {
        var prior = (Map<String, Object>) state.getData().get("nativeSimulation");
        var hints = new LinkedHashMap<String, Object>();
        for (String key : List.of("profile", "contextId", "logicalHorizonEpochMs", "customerContext", "resourceVersion")) hints.put(key, prior.get(key));
        hints.put("expectedStateVersion", prior.get("stateVersion"));
        var request = envelope("domain-system-work-window");
        request.put("state", state);
        request.put("payload", Map.of("timeWindow", Map.of("previousTimeEpochMs", prior.get("processedThroughEpochMs"), "currentTimeEpochMs", through),
                "context", Map.of("nativeSimulation", hints), "workKinds", List.of("credit_card_statement_close"),
                "maxWorkItems", 1, "executionMode", "EVALUATE_AND_EXECUTE"));
        return mapper.convertValue(request, RemoteDomainSystemWorkWindowRequest.class);
    }

    static Map<String, Object> guard(RemoteDomainOpaqueState state) {
        var metadata = (Map<String, Object>) state.getData().get("nativeSimulation");
        return Map.of("contextId", metadata.get("contextId"), "resourceVersion", metadata.get("resourceVersion"),
                "logicalTimeEpochMs", metadata.get("processedThroughEpochMs"));
    }

    private static Map<String, Object> envelope(String operation) {
        var request = new LinkedHashMap<String, Object>();
        String id = UUID.randomUUID().toString();
        request.put("protocolVersion", "remote-domain.v1"); request.put("requestId", id); request.put("idempotencyKey", id);
        request.put("operation", operation); request.put("sentAtEpochMs", System.currentTimeMillis());
        request.put("deadlineEpochMs", System.currentTimeMillis() + 60_000);
        request.put("host", Map.of("service", "agent-runtime", "instanceId", "test", "platformVersion", "test"));
        request.put("domain", Map.of("domainId", "banking"));
        request.put("agent", Map.of("agentId", "customer-agent", "personaName", "Customer"));
        return request;
    }

    static Map<String, Object> seed(String accountId, String limit) {
        return Map.of("logicalTimeEpochMs", 1769904000000L, "accounts", List.of(Map.of(
                "accountId", accountId, "name", "Everyday credit", "productCode", "CC001",
                "balance", "-1200", "currency", "AUD", "limit", limit, "currentMinPayment", "48")),
                "statements", List.of(Map.of("billId", accountId + "-2026-01", "accountId", accountId,
                        "closingAmount", "-1200", "minimumAmountDue", "48", "currency", "AUD",
                        "statementStartDate", "2026-01-01", "statementEndDate", "2026-01-31", "dueDate", "2026-02-14")));
    }
}
