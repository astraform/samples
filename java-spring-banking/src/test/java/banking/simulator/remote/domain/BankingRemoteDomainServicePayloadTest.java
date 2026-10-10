package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.conformance.RemoteDomainContractValidator;
import ai.astraform.remote.domain.openapi.models.RemoteDomainAgentContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionTarget;
import ai.astraform.remote.domain.openapi.models.RemoteDomainContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainDueWorkItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainHostContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTurnContext;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTurnContextTimeWindow;
import ai.astraform.remote.domain.openapi.models.RemoteDomainTimeWindow;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankingRemoteDomainServicePayloadTest {
    private static final long NOW_EPOCH_MS = 1_777_000_000_000L;

    private final BankingRemoteDomainService service =
            new BankingRemoteDomainService(new ObjectMapper().rebuild().findAndAddModules().build());

    @Test
    void manifestDeclaresRemoteBankingPersonaTools() {
        var manifest = service.manifest();

        assertThat(manifest.getCapabilities().getRemoteProviderLanguage()).isEqualTo("java");
        assertThat(manifest.getCapabilities().getTools())
                .extracting(tool -> tool.getName())
                .contains("get_product_list", "post_double_entry_transaction");
        assertThat(manifest.getCapabilities().getScheduledWorkKinds())
                .extracting(kind -> kind.getKind())
                .containsExactly("transaction");
        assertThat(manifest.getCapabilities().getSupportsDomainSystemWorkWindow()).isTrue();
        assertThat(manifest.getCapabilities().getDomainSystemWorkKinds())
                .extracting(kind -> kind.getKind())
                .containsExactly(
                        "monthly_interest_accrual",
                        "loan_interest_accrual",
                        "credit_card_interest_accrual",
                        "credit_card_statement_close",
                        "credit_card_rate_change");
        assertThat(manifest.getCapabilities().getSupportsControlActions()).isTrue();
        assertThat(manifest.getOperations().getControlAction().getPath())
                .isEqualTo("/api/remote-domain/v1/control-actions:execute");
        assertThat(manifest.getCapabilities().getControlActions())
                .extracting(action -> action.getActionId())
                .containsExactly("broadcast-message", "schedule-rate-change-plan");
        assertThat(manifest.getUiProfile().getCreatorSections())
                .flatExtracting(section -> section.getFields())
                .filteredOn(field -> "initialAccounts.productCode".equals(field.getPath()))
                .flatExtracting(field -> field.getOptions())
                .extracting(option -> option.getValue())
                .contains("TS001", "SAV001", "CC001", "CC002", "HL001", "SL001", "TD001");
    }

    @Test
    void prepareMapsBankingPersonaConfigurationToBankingStateAndProjection() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        Map<String, Object> state = prepared.getNextState().getData();
        assertThat(prepared.getNextState().getSchemaVersion()).isEqualTo(BankingRemoteDomainState.SCHEMA_VERSION);
        assertThat(state)
                .containsEntry("domainId", "banking")
                .containsEntry("personaName", "Avery Saver")
                .containsEntry("agentType", "Customer")
                .containsEntry("interactionMode", "SIMULATION");

        List<?> accounts = list(state.get("accounts"));
        assertThat(accounts).hasSize(1);
        assertThat(map(accounts.get(0)))
                .containsEntry("name", "Main Account")
                .containsEntry("productCode", "TX001")
                .containsEntry("balance", 2500.0)
                .containsEntry("debitCardRequested", true);

        List<?> scheduledWork = list(state.get("scheduledWork"));
        assertThat(scheduledWork).hasSize(1);
        Map<Object, Object> rentWork = map(scheduledWork.get(0));
        assertThat(rentWork)
                .containsEntry("kind", "transaction")
                .containsEntry("description", "Monthly Rent")
                .containsEntry("frequency", "Monthly")
                .containsEntry("time", "09:30");
        assertThat(map(rentWork.get("payload")))
                .containsEntry("type", "Payment")
                .containsEntry("amount", 1500.0)
                .containsEntry("isAmountExact", true);

        Map<String, Object> statusView = prepared.getResult().getProjection().getStatusView();
        assertThat(list(statusView.get("accounts"))).hasSize(1);
        assertThat(list(statusView.get("scheduledTransactions"))).hasSize(1);
        assertThat(map(list(statusView.get("scheduledTransactions")).getFirst()))
                .containsEntry("description", "Monthly Rent")
                .containsEntry("day", "15")
                .containsEntry("time", "09:30");
        assertThat(prepared.getResult().getProjection().getLifecycleSchedule()).hasSize(1);
        assertThat(prepared.getResult().getProjection().getInspectionSchedule()).hasSize(1);
    }

    @Test
    void preparePersistsValidatedDomainDecisionTriggerPolicy() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("decisionTriggerPolicy", Map.of(
                "monthly_review", Map.of("enabled", true, "priority", "NORMAL"),
                "interest_charged", Map.of("minimumAmount", 15)));

        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        Map<Object, Object> policy = map(prepared.getNextState().getData().get("decisionTriggerPolicy"));
        assertThat(map(policy.get("monthly_review")))
                .containsEntry("enabled", true)
                .containsEntry("priority", "NORMAL");
        assertThat(map(policy.get("interest_charged")))
                .containsEntry("minimumAmount", new BigDecimal("15"));
    }

    @Test
    void prepareRejectsMalformedDomainDecisionTriggerPolicy() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("decisionTriggerPolicy", Map.of(
                "monthly_review", Map.of("enabled", "yes")));

        assertThatThrownBy(() -> service.prepare(prepareRequest(configuration)))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("must be boolean");
    }

    @Test
    void prepareRejectsDuplicateInitialAccountIds() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "duplicate-card",
                        "name", "First Card",
                        "productCode", "CC001",
                        "balance", -1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "duplicate-card",
                        "name", "Second Card",
                        "productCode", "CC001",
                        "balance", -400.0,
                        "currency", "AUD")));

        assertThatThrownBy(() -> service.prepare(prepareRequest(configuration)))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("duplicate accountId");
    }

    @Test
    void prepareAcceptsNestedScheduledWorkPayloadEnvelopeFromAgentInitBoundary() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "rent-work-1",
                "kind", "transaction",
                "description", "Monthly Rent",
                "frequency", "Monthly",
                "time", "09:30",
                "payload", Map.of(
                        "payload", Map.of(
                                "type", "Payment",
                                "amount", 1500.0,
                                "isAmountExact", true,
                                "details", Map.of(
                                        "from", List.of("Main Account"),
                                        "to", List.of("Landlord")))))));

        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        List<?> scheduledWork = list(prepared.getNextState().getData().get("scheduledWork"));
        assertThat(scheduledWork).hasSize(1);
        Map<Object, Object> rentWork = map(scheduledWork.getFirst());
        assertThat(rentWork)
                .containsEntry("description", "Monthly Rent")
                .containsEntry("frequency", "Monthly")
                .containsEntry("time", "09:30");
        assertThat(map(rentWork.get("payload")))
                .containsEntry("type", "Payment")
                .containsEntry("amount", 1500.0)
                .doesNotContainKey("payload");
    }

    @Test
    void preparePreservesNonTransactionDomainSystemScheduledWork() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "cc-interest-work",
                "kind", "credit_card_interest_accrual",
                "description", "Credit card interest",
                "frequency", "MONTHLY",
                "time", "01:00",
                "payload", Map.of(
                        "annualRate", 0.24,
                        "accountName", "Rewards Card"))));

        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        List<?> scheduledWork = list(prepared.getNextState().getData().get("scheduledWork"));
        assertThat(scheduledWork).hasSize(1);
        Map<Object, Object> work = map(scheduledWork.getFirst());
        assertThat(work)
                .containsEntry("workId", "cc-interest-work")
                .containsEntry("kind", "credit_card_interest_accrual")
                .containsEntry("description", "Credit card interest")
                .containsEntry("frequency", "MONTHLY");
        assertThat(map(work.get("payload")))
                .containsEntry("annualRate", 0.24)
                .containsEntry("accountName", "Rewards Card");
    }

    @Test
    void executeWorkCarriesBankingScheduledWorkThroughOpaqueState() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        RemoteDomainExecuteWorkRequest executeRequest = executeWorkRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity());
        var executed = service.executeWork(executeRequest);

        Map<String, Object> nextState = executed.getNextState().getData();
        assertThat(nextState)
                .containsEntry("executedWorkCount", 1)
                .containsEntry("lastAction", "execute-work");

        List<?> executionLog = list(nextState.get("executionLog"));
        assertThat(executionLog).hasSize(1);
        Map<Object, Object> loggedWork = map(executionLog.get(0));
        assertThat(loggedWork)
                .containsEntry("workType", "DOMAIN_COMMAND")
                .containsEntry("description", "Monthly Rent")
                .containsEntry("status", "APPLIED");
        assertThat(map(loggedWork.get("payload")))
                .containsEntry("kind", "transaction")
                .containsEntry("amount", 1500.0);

        assertThat(nextState)
                .containsEntry("appliedTransactionCount", 1)
                .containsEntry("rejectedTransactionCount", 0);
        assertThat(map(list(nextState.get("accounts")).get(0)))
                .containsEntry("balance", 1000.0);
        assertThat(list(nextState.get("transactionPostings"))).hasSize(1);
        Map<String, Object> statusView = executed.getResult().getProjection().getStatusView();
        assertThat(statusView).containsEntry("executedWorkCount", 1);
        List<?> recentTransactions = list(statusView.get("recentTransactions"));
        assertThat(recentTransactions).hasSize(1);
        Map<Object, Object> recentTransaction = map(recentTransactions.getFirst());
        assertThat(recentTransaction)
                .containsEntry("workId", "rent-work-1")
                .containsEntry("description", "Monthly Rent")
                .containsEntry("occurrenceKey", "banking|rent|2026-05-01");
        assertThat(list(recentTransaction.get("debits"))).hasSize(1);
        assertThat(list(recentTransaction.get("credits"))).hasSize(1);
    }

    @Test
    void executeWorkDoesNotReapplyAStableScheduledWorkIdentity() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        var first = service.executeWork(executeWorkRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity()));
        RemoteDomainExecuteWorkRequest replayRequest = executeWorkRequest(
                first.getNextState(),
                first.getResult().getRuntimeIdentity());
        replayRequest.setIdempotencyKey("banking-remote-agent:execute-work:attempt-2");
        RemoteDomainDueWorkItem replayWork = replayRequest.getPayload().getTurnContext().getDueWork().getFirst();
        replayWork.setAttemptNumber(2);
        replayWork.setOccurrenceKey("banking|rent|changed-attempt-metadata");

        var replay = service.executeWork(replayRequest);

        Map<String, Object> replayState = replay.getNextState().getData();
        assertThat(replay.getNextState()).isEqualTo(first.getNextState());
        assertThat(list(replayState.get("transactionPostings"))).hasSize(1);
        assertThat(list(replayState.get("executionLog"))).hasSize(1);
        assertThat(replayState).containsEntry("executedWorkCount", 1);
        assertThat(replay.getResult().getToolResult())
                .containsEntry("success", true)
                .containsEntry("idempotentReplay", true)
                .containsEntry("workId", "rent-work-1");
        assertThat(replay.getResult().getEvidenceEvents()).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("eventType", "remote_domain_tool_effect_replayed")
                .containsEntry("outcome", "NOOP")
                .containsEntry("workId", "rent-work-1");
    }

    @Test
    void executeWorkHonorsCreditCardLimitForScheduledPayment() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "card-spend-work-1",
                "kind", "transaction",
                "description", "Client Entertainment",
                "frequency", "Monthly",
                "time", "18:00",
                "payload", Map.of(
                        "type", "Payment",
                        "amount", 250.0,
                        "isAmountExact", true,
                        "details", Map.of(
                                "from", List.of("Rewards Card"),
                                "to", List.of("Restaurant"))))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainExecuteWorkRequest executeRequest = executeWorkRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity());
        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                "card-spend-work-1",
                "DOMAIN_COMMAND",
                "banking|card-spend|2026-05-01",
                NOW_EPOCH_MS,
                1);
        dueWork.setDescription("Client Entertainment");
        dueWork.setPayload(new LinkedHashMap<>(Map.of(
                "kind", "transaction",
                "type", "Payment",
                "amount", 250.0,
                "isAmountExact", true,
                "details", Map.of(
                        "from", List.of("Rewards Card"),
                        "to", List.of("Restaurant")))));
        executeRequest.setPayload(new RemoteDomainExecuteWorkRequestAllOfPayload(new RemoteDomainTurnContext(
                new RemoteDomainTurnContextTimeWindow(NOW_EPOCH_MS, NOW_EPOCH_MS + 60_000),
                List.of(dueWork))));

        var executed = service.executeWork(executeRequest);

        Map<String, Object> nextState = executed.getNextState().getData();
        assertThat(nextState)
                .containsEntry("executedWorkCount", 1)
                .containsEntry("appliedTransactionCount", 1)
                .containsEntry("rejectedTransactionCount", 0);
        Map<Object, Object> account = map(list(nextState.get("accounts")).getFirst());
        assertThat(account)
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("balance", -1450.0)
                .containsEntry("limit", 3000.0);
        Map<Object, Object> loggedWork = map(list(nextState.get("executionLog")).getFirst());
        assertThat(loggedWork)
                .containsEntry("status", "APPLIED")
                .doesNotContainKey("failureMessage");
        assertThat(list(nextState.get("transactionPostings"))).hasSize(1);
    }

    @Test
    void executeWorkAppliesCreditCardInterestAndStatementCloseWithoutDrools() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(creditCardPersonaConfiguration()));

        var interestExecuted = service.executeWork(executeDomainSystemWorkRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "cc-interest-work-1",
                "banking|cc-interest|2026-04",
                "Credit card interest",
                Map.of(
                        "kind", "credit_card_interest_accrual",
                        "accountName", "Rewards Card",
                        "annualRate", 0.24,
                        "minPaymentPercentage", 0.04)));

        Map<String, Object> interestState = interestExecuted.getNextState().getData();
        Map<Object, Object> accountAfterInterest = map(list(interestState.get("accounts")).getFirst());
        Object runtimeAccountId = accountAfterInterest.get("id");
        assertThat(accountAfterInterest)
                .containsEntry("balance", -1224.0)
                .containsEntry("currentMinPayment", 48.96)
                .containsKey("lastCreditCardInterestPostedAt");
        assertThat(list(interestState.get("transactionPostings"))).hasSize(1);
        assertThat(map(list(interestState.get("transactionPostings")).getFirst()))
                .containsEntry("eventType", "remote_domain_credit_card_interest_charged")
                .containsEntry("interestAmount", 24.0)
                .containsEntry("newBalance", -1224.0)
                .containsEntry("minimumPayment", 48.96);
        assertThat(interestExecuted.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .contains("remote_domain_credit_card_interest_charged");
        Map<Object, Object> interestEvidence = map(interestExecuted.getResult().getEvidenceEvents().getFirst());
        assertThat(interestEvidence)
                .containsEntry("occurrenceKey", "banking|cc-interest|2026-04")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("openingBalance", -1200.0)
                .containsEntry("annualRate", 0.24)
                .containsEntry("interestAmount", 24.0)
                .containsEntry("newBalance", -1224.0)
                .containsEntry("minimumPayment", 48.96);

        var statementExecuted = service.executeWork(executeDomainSystemWorkRequest(
                interestExecuted.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "cc-statement-work-1",
                "banking|cc-statement|2026-04",
                "Credit card statement close",
                Map.of(
                        "kind", "credit_card_statement_close",
                        "accountName", "Rewards Card",
                        "statementStartDate", "2026-04-01",
                        "statementEndDate", "2026-04-30",
                        "dueDays", 14,
                        "minPaymentPercentage", 0.04)));

        Map<String, Object> statementState = statementExecuted.getNextState().getData();
        assertThat(statementState)
                .containsEntry("appliedDomainSystemWorkCount", 2);
        List<?> bills = list(statementState.get("creditCardBills"));
        assertThat(bills).hasSize(1);
        assertThat(map(bills.getFirst()))
                .containsEntry("eventType", "remote_domain_credit_card_statement_closed")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("closingAmount", -1224.0)
                .containsEntry("minimumAmountDue", 48.96)
                .containsEntry("statementStartDate", "2026-04-01")
                .containsEntry("statementEndDate", "2026-04-30")
                .containsEntry("dueDate", "2026-05-14");
        assertThat(statementExecuted.getResult().getProjection().getStatusView().get("creditCardBills"))
                .asList()
                .hasSize(1);
        assertThat(statementExecuted.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .contains("remote_domain_credit_card_statement_closed");
        Map<Object, Object> statementEvidence = map(statementExecuted.getResult().getEvidenceEvents().getFirst());
        assertThat(statementEvidence)
                .containsEntry("occurrenceKey", "banking|cc-statement|2026-04")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("closingAmount", -1224.0)
                .containsEntry("minimumAmountDue", 48.96)
                .containsEntry("statementStartDate", "2026-04-01")
                .containsEntry("statementEndDate", "2026-04-30")
                .containsEntry("dueDate", "2026-05-14");
    }

    @Test
    void domainSystemWorkWindowExecutesBankingOwnedMonthlyInterest() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-saver-1",
                        "name", "Everyday Saver",
                        "productCode", "TS001",
                        "productCategory", "TRANS_AND_SAVINGS_ACCOUNTS",
                        "balance", 1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-card-1",
                        "name", "Rewards Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -300.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "savings-interest-work",
                "kind", "monthly_interest_accrual",
                "description", "Monthly savings interest",
                "frequency", "MONTHLY",
                "payload", Map.of("annualRate", 0.03))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "monthly_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind())
                .containsExactly("monthly_interest_accrual");
        List<?> accounts = list(executed.getNextState().getData().get("accounts"));
        assertThat(map(accounts.get(0))).containsEntry("balance", 1203.0);
        assertThat(map(accounts.get(1))).containsEntry("balance", -300.0);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .containsExactly("remote_domain_interest_accrued");
        Map<Object, Object> interestEvidence = map(executed.getResult().getEvidenceEvents().getFirst());
        assertThat(interestEvidence)
                .containsEntry("occurrenceKey", "banking|domain-system|savings-interest-work|2026-04-01")
                .containsEntry("annualRate", 0.03)
                .containsEntry("monthlyRate", 0.0025)
                .containsEntry("openingTotalBalance", 1200.0)
                .containsEntry("interestAmount", 3.0)
                .containsEntry("newTotalBalance", 1203.0)
                .containsEntry("affectedAccountCount", 1);
        Map<Object, Object> accountInterest = map(list(interestEvidence.get("accountInterest")).getFirst());
        assertThat(String.valueOf(accountInterest.get("accountId"))).isNotBlank();
        assertThat(accountInterest)
                .containsEntry("accountName", "Everyday Saver")
                .containsEntry("productCode", "TS001")
                .containsEntry("productCategory", "TRANS_AND_SAVINGS_ACCOUNTS")
                .containsEntry("openingBalance", 1200.0)
                .containsEntry("interestAmount", 3.0)
                .containsEntry("newBalance", 1203.0);
        Map<Object, Object> stateDelta = map(executed.getResult().getDomainSystemWork().getStateDeltas().getFirst());
        assertThat(stateDelta).containsAllEntriesOf(interestEvidence);
        assertThat(list(map(executed.getResult().getDomainSystemWork().getNextDueHints().getFirst()).get("workKinds")))
                .isEqualTo(List.of("monthly_interest_accrual"));
        List<?> executionLog = list(executed.getNextState().getData().get("executionLog"));
        assertThat(executionLog).hasSize(1);
        assertThat(map(executionLog.getFirst()))
                .containsEntry("status", "APPLIED")
                .containsEntry("kind", "monthly_interest_accrual")
                .containsEntry("occurrenceKey", "banking|domain-system|savings-interest-work|2026-04-01");

        RemoteDomainSystemWorkWindowRequest repeatedRequest = domainSystemWorkWindowRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                "monthly_interest_accrual");
        var repeated = service.executeDomainSystemWorkWindow(repeatedRequest);

        assertThat(map(list(repeated.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", 1203.0);
        assertThat(repeated.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(0);
        assertThat(repeated.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(0);
        assertThat(repeated.getResult().getEvidenceEvents()).isEmpty();
        assertThat(list(repeated.getNextState().getData().get("executionLog"))).hasSize(1);
    }

    @Test
    void monthlyInterestSkipsUnknownProductsWithoutAppliedEvidence() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(Map.of(
                "accountId", "acct-unknown-1",
                "name", "Unknown Account",
                "productCode", "ZZ001",
                "balance", 1200.0,
                "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "unknown-interest-work",
                "kind", "monthly_interest_accrual",
                "description", "Monthly unknown-product interest",
                "frequency", "MONTHLY",
                "payload", Map.of("annualRate", 0.03))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "monthly_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(0);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind())
                .containsExactly("monthly_interest_accrual");
        assertThat(map(list(executed.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", 1200.0);
        assertThat(executed.getNextState().getData()).containsEntry("executedWorkCount", 0);
        assertThat(executed.getResult().getEvidenceEvents()).isEmpty();
        assertThat(executed.getResult().getDomainSystemWork().getStateDeltas()).isEmpty();
        assertThat(list(executed.getNextState().getData().get("executionLog"))).isEmpty();

        RemoteDomainSystemWorkWindowRequest secondWindowRequest = domainSystemWorkWindowRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                "monthly_interest_accrual");
        var secondWindow = service.executeDomainSystemWorkWindow(secondWindowRequest);

        assertThat(secondWindow.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(secondWindow.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(0);
        assertThat(secondWindow.getResult().getEvidenceEvents()).isEmpty();
        assertThat(list(secondWindow.getNextState().getData().get("executionLog"))).isEmpty();
    }

    @Test
    void mixedWorkWindowReportsSkippedAndAppliedItemsIndependently() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-unknown-1",
                        "name", "Unknown Account",
                        "productCode", "ZZ001",
                        "balance", 1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-card-1",
                        "name", "Rewards Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -1200.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(
                Map.of(
                        "workId", "unknown-interest-work",
                        "kind", "monthly_interest_accrual",
                        "description", "Monthly unknown-product interest",
                        "frequency", "MONTHLY",
                        "payload", Map.of("annualRate", 0.03)),
                Map.of(
                        "workId", "cc-interest-work",
                        "kind", "credit_card_interest_accrual",
                        "description", "Credit card interest",
                        "frequency", "MONTHLY",
                        "payload", Map.of(
                                "annualRate", 0.24,
                                "accountName", "Rewards Card"))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "monthly_interest_accrual",
                "credit_card_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind() + ":" + result.getOutcome().getValue())
                .containsExactly("monthly_interest_accrual:SKIPPED", "credit_card_interest_accrual:APPLIED");
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> map(result.getDetails()).get("evidenceEventCount"))
                .containsExactly(0, 1);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .containsExactly("remote_domain_credit_card_interest_charged");
        assertThat(executed.getNextState().getData()).containsEntry("executedWorkCount", 1);
    }

    @Test
    void creditCardWorkItemCountsOnceWhenMultipleAccountsEmitEvidence() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-card-1",
                        "name", "Rewards Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-card-2",
                        "name", "Travel Card",
                        "productCode", "CC002",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -600.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "cc-interest-work",
                "kind", "credit_card_interest_accrual",
                "description", "Credit card interest",
                "frequency", "MONTHLY",
                "payload", Map.of("annualRate", 0.24))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "credit_card_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind() + ":" + result.getOutcome().getValue())
                .containsExactly("credit_card_interest_accrual:APPLIED");
        assertThat(map(executed.getResult().getDomainSystemWork().getWorkResults().getFirst().getDetails()))
                .containsEntry("evidenceEventCount", 2);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .containsExactly(
                        "remote_domain_credit_card_interest_charged",
                        "remote_domain_credit_card_interest_charged");
        assertThat(executed.getNextState().getData()).containsEntry("executedWorkCount", 1);
        assertThat(list(executed.getNextState().getData().get("executionLog"))).hasSize(1);
    }

    @Test
    void creditCardAccountIdTargetDoesNotWidenToOtherCards() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-card-1",
                        "name", "Rewards Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-card-2",
                        "name", "Travel Card",
                        "productCode", "CC002",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -600.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "targeted-cc-interest-work",
                "kind", "credit_card_interest_accrual",
                "description", "Targeted credit card interest",
                "frequency", "MONTHLY",
                "payload", Map.of(
                        "accountId", "acct-card-1",
                        "annualRate", 0.24))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "credit_card_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        List<?> accounts = list(executed.getNextState().getData().get("accounts"));
        Object runtimeAccountId = map(accounts.get(0)).get("id");
        assertThat(map(accounts.get(0))).containsEntry("balance", -1224.0);
        assertThat(map(accounts.get(1))).containsEntry("balance", -600.0);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(map(executed.getResult().getDomainSystemWork().getWorkResults().getFirst().getDetails()))
                .containsEntry("evidenceEventCount", 1);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("accountName"))
                .containsExactly("Rewards Card");
        assertThat(map(executed.getResult().getEvidenceEvents().getFirst()))
                .containsEntry("accountId", "acct-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId);
    }

    @Test
    void domainSystemWorkWindowExecutesBankingOwnedLoanInterest() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-home-loan-1",
                        "name", "Home Mortgage",
                        "productCode", "HL001",
                        "productCategory", "RESIDENTIAL_MORTGAGES",
                        "balance", -300000.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-student-loan-1",
                        "name", "Study Loan",
                        "productCode", "SL001",
                        "productCategory", "PERS_LOANS",
                        "balance", -24000.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-car-loan-1",
                        "name", "Car Loan",
                        "productCode", "CL001",
                        "productCategory", "PERS_LOANS",
                        "balance", -12000.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "loan-interest-work",
                "kind", "loan_interest_accrual",
                "description", "Loan interest progression",
                "frequency", "MONTHLY",
                "payload", Map.of(
                        "annualRate", 0.06,
                        "repaymentBaseAmount", 600.0))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "loan_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind() + ":" + result.getOutcome().getValue())
                .containsExactly("loan_interest_accrual:APPLIED");
        assertThat(map(executed.getResult().getDomainSystemWork().getWorkResults().getFirst().getDetails()))
                .containsEntry("evidenceEventCount", 2);
        List<?> accounts = list(executed.getNextState().getData().get("accounts"));
        Object homeRuntimeAccountId = map(accounts.get(0)).get("id");
        Object studentRuntimeAccountId = map(accounts.get(1)).get("id");
        assertThat(map(accounts.get(0))).containsEntry("balance", -299400.0);
        assertThat(map(accounts.get(1))).containsEntry("balance", -24120.0);
        assertThat(map(accounts.get(2))).containsEntry("balance", -12000.0);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("loanKind"))
                .containsExactly("HOME_LOAN", "STUDENT_LOAN");
        Map<Object, Object> homeEvidence = map(executed.getResult().getEvidenceEvents().get(0));
        assertThat(homeEvidence)
                .containsEntry("occurrenceKey", "banking|domain-system|loan-interest-work|2026-04-01")
                .containsEntry("accountId", "acct-home-loan-1")
                .containsEntry("runtimeAccountId", homeRuntimeAccountId)
                .containsEntry("openingBalance", -300000.0)
                .containsEntry("interestAmount", 1500.0)
                .containsEntry("repaymentAmount", 2100.0)
                .containsEntry("principalReductionAmount", 600.0)
                .containsEntry("newBalance", -299400.0);
        Map<Object, Object> studentEvidence = map(executed.getResult().getEvidenceEvents().get(1));
        assertThat(studentEvidence)
                .containsEntry("accountId", "acct-student-loan-1")
                .containsEntry("runtimeAccountId", studentRuntimeAccountId)
                .containsEntry("openingBalance", -24000.0)
                .containsEntry("interestAmount", 120.0)
                .containsEntry("repaymentAmount", 0.0)
                .containsEntry("newBalance", -24120.0);
        assertThat(executed.getNextState().getData()).containsEntry("executedWorkCount", 1);
        assertThat(list(executed.getNextState().getData().get("executionLog"))).hasSize(1);

        RemoteDomainSystemWorkWindowRequest repeatedRequest = domainSystemWorkWindowRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                "loan_interest_accrual");
        var repeated = service.executeDomainSystemWorkWindow(repeatedRequest);

        assertThat(repeated.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(0);
        assertThat(repeated.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(0);
        assertThat(map(list(repeated.getNextState().getData().get("accounts")).get(0)))
                .containsEntry("balance", -299400.0);
        assertThat(map(list(repeated.getNextState().getData().get("accounts")).get(1)))
                .containsEntry("balance", -24120.0);
        assertThat(repeated.getResult().getEvidenceEvents()).isEmpty();
    }

    @Test
    void loanInterestAccountIdTargetDoesNotWidenToOtherLoans() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-home-loan-1",
                        "name", "Home Mortgage",
                        "productCode", "HL001",
                        "balance", -300000.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-student-loan-1",
                        "name", "Study Loan",
                        "productCode", "SL001",
                        "balance", -24000.0,
                        "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "targeted-loan-interest-work",
                "kind", "loan_interest_accrual",
                "description", "Targeted loan interest progression",
                "frequency", "MONTHLY",
                "payload", Map.of(
                        "accountId", "acct-home-loan-1",
                        "annualRate", 0.06,
                        "repaymentBaseAmount", 600.0))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "loan_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        List<?> accounts = list(executed.getNextState().getData().get("accounts"));
        Object homeRuntimeAccountId = map(accounts.get(0)).get("id");
        assertThat(map(accounts.get(0))).containsEntry("balance", -299400.0);
        assertThat(map(accounts.get(1))).containsEntry("balance", -24000.0);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(map(executed.getResult().getDomainSystemWork().getWorkResults().getFirst().getDetails()))
                .containsEntry("evidenceEventCount", 1);
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("accountName"))
                .containsExactly("Home Mortgage");
        assertThat(map(executed.getResult().getEvidenceEvents().getFirst()))
                .containsEntry("accountId", "acct-home-loan-1")
                .containsEntry("runtimeAccountId", homeRuntimeAccountId);
    }

    @Test
    void loanInterestMissingAccountIdTargetHasNoStateDelta() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(personaConfiguration());
        configuration.put("initialAccounts", List.of(Map.of(
                "accountId", "acct-home-loan-1",
                "name", "Home Mortgage",
                "productCode", "HL001",
                "balance", -300000.0,
                "currency", "AUD")));
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "missing-target-loan-interest-work",
                "kind", "loan_interest_accrual",
                "description", "Missing target loan interest progression",
                "frequency", "MONTHLY",
                "payload", Map.of(
                        "accountId", "acct-missing-loan",
                        "annualRate", 0.06,
                        "repaymentBaseAmount", 600.0))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "loan_interest_accrual");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(map(list(executed.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", -300000.0);
        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(0);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind() + ":" + result.getOutcome().getValue())
                .containsExactly("loan_interest_accrual:SKIPPED");
        assertThat(map(executed.getResult().getDomainSystemWork().getWorkResults().getFirst().getDetails()))
                .containsEntry("evidenceEventCount", 0);
        assertThat(executed.getResult().getDomainSystemWork().getStateDeltas()).isEmpty();
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
        assertThat(executed.getResult().getEvidenceEvents()).isEmpty();
    }

    @Test
    void domainSystemWorkWindowExecutesBankingOwnedCreditCardRules() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "cc-interest-work",
                "kind", "credit_card_interest_accrual",
                "description", "Credit card interest",
                "frequency", "MONTHLY",
                "payload", Map.of(
                        "annualRate", 0.24,
                        "accountName", "Rewards Card")),
                Map.of(
                        "workId", "cc-statement-work",
                        "kind", "credit_card_statement_close",
                        "description", "Credit card statement close",
                        "frequency", "MONTHLY",
                        "payload", Map.of(
                                "accountName", "Rewards Card",
                                "statementStartDate", "2026-04-01",
                                "statementEndDate", "2026-04-30",
                                "dueDays", 14,
                                "minPaymentPercentage", 0.04))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "credit_card_interest_accrual",
                "credit_card_statement_close");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getOperation().getValue()).isEqualTo("domain-system-work-window");
        Object runtimeAccountId = map(list(executed.getNextState().getData().get("accounts")).getFirst()).get("id");
        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(2);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(2);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind())
                .containsExactly("credit_card_interest_accrual", "credit_card_statement_close");
        assertThat(map(list(executed.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", -1224.0);
        assertThat(map(list(executed.getNextState().getData().get("creditCardBills")).getFirst()))
                .containsEntry("closingAmount", -1224.0)
                .containsEntry("minimumAmountDue", 48.96)
                .containsEntry("statementStartDate", "2026-04-01")
                .containsEntry("statementEndDate", "2026-04-30")
                .containsEntry("dueDate", "2026-05-14");
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .containsExactly("remote_domain_credit_card_interest_charged", "remote_domain_credit_card_statement_closed");
        assertThat(executed.getResult().getDomainSystemWork().getStateDeltas())
                .extracting(delta -> delta.get("eventType"))
                .containsExactly("remote_domain_credit_card_interest_charged", "remote_domain_credit_card_statement_closed");
        Map<Object, Object> workWindowInterestEvidence = map(executed.getResult().getEvidenceEvents().get(0));
        assertThat(workWindowInterestEvidence)
                .containsEntry("occurrenceKey", "banking|domain-system|cc-interest-work|2026-04-01")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("openingBalance", -1200.0)
                .containsEntry("annualRate", 0.24)
                .containsEntry("interestAmount", 24.0)
                .containsEntry("newBalance", -1224.0)
                .containsEntry("minimumPayment", 48.96);
        Map<Object, Object> workWindowStatementEvidence = map(executed.getResult().getEvidenceEvents().get(1));
        assertThat(workWindowStatementEvidence)
                .containsEntry("occurrenceKey", "banking|domain-system|cc-statement-work|2026-04-01")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("closingAmount", -1224.0)
                .containsEntry("minimumAmountDue", 48.96)
                .containsEntry("statementStartDate", "2026-04-01")
                .containsEntry("statementEndDate", "2026-04-30")
                .containsEntry("dueDate", "2026-05-14");
        Map<Object, Object> workWindowInterestDelta = map(executed.getResult().getDomainSystemWork().getStateDeltas().get(0));
        assertThat(workWindowInterestDelta)
                .containsEntry("occurrenceKey", "banking|domain-system|cc-interest-work|2026-04-01")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("openingBalance", -1200.0)
                .containsEntry("annualRate", 0.24)
                .containsEntry("interestAmount", 24.0)
                .containsEntry("newBalance", -1224.0)
                .containsEntry("minimumPayment", 48.96);
        Map<Object, Object> workWindowStatementDelta = map(executed.getResult().getDomainSystemWork().getStateDeltas().get(1));
        assertThat(workWindowStatementDelta)
                .containsEntry("occurrenceKey", "banking|domain-system|cc-statement-work|2026-04-01")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", runtimeAccountId)
                .containsEntry("accountName", "Rewards Card")
                .containsEntry("productCode", "CC001")
                .containsEntry("closingAmount", -1224.0)
                .containsEntry("minimumAmountDue", 48.96)
                .containsEntry("statementStartDate", "2026-04-01")
                .containsEntry("statementEndDate", "2026-04-30")
                .containsEntry("dueDate", "2026-05-14");
        assertThat(list(map(executed.getResult().getDomainSystemWork().getNextDueHints().getFirst()).get("workKinds")))
                .isEqualTo(List.of("credit_card_interest_accrual", "credit_card_statement_close"));
        var decisionTriggers = executed.getResult().getDomainSystemWork().getDecisionTriggers();
        assertThat(decisionTriggers).hasSize(1);
        var statementTrigger = decisionTriggers.getFirst();
        assertThat(statementTrigger.getSchemaVersion().getValue())
                .isEqualTo("customer_decision_trigger.v1");
        assertThat(statementTrigger.getTriggerId())
                .isEqualTo("statement_close:banking|domain-system|cc-statement-work|2026-04-01|account:acct-rewards-card-1");
        assertThat(statementTrigger.getTriggerType()).isEqualTo("statement_close");
        assertThat(statementTrigger.getTriggerRef())
                .isEqualTo("banking|domain-system|cc-statement-work|2026-04-01|account:acct-rewards-card-1");
        assertThat(statementTrigger.getScheduledForEpochMs())
                .isEqualTo(request.getPayload().getTimeWindow().getCurrentTimeEpochMs());
        assertThat(statementTrigger.getPriority()).isEqualTo("NORMAL");
        assertThat(statementTrigger.getReason()).isEqualTo("Credit card statement closed");
        assertThat(statementTrigger.getEvidenceRefs())
                .isEqualTo(List.of(
                        "banking|domain-system|cc-statement-work|2026-04-01|account:acct-rewards-card-1"));
        assertThat(statementTrigger.getPayload())
                .containsEntry("policyVersion", "banking.customer-decision-trigger-policy.v1")
                .containsEntry("numericEncoding", "canonical_decimal_string.v1")
                .containsEntry("normalizedEventType", "statement_close")
                .containsEntry("eventType", "remote_domain_credit_card_statement_closed")
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("runtimeAccountId", String.valueOf(runtimeAccountId))
                .containsEntry("dueDate", "2026-05-14");
    }

    @Test
    void domainSystemWorkWindowPlansMonthlyStatementFromPreparedCreditCardAccount() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(creditCardPersonaConfiguration()));

        RemoteDomainSystemWorkWindowRequest midMonth = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                NOW_EPOCH_MS,
                NOW_EPOCH_MS + 60_000,
                "credit_card_statement_close");
        var beforeBoundary = service.executeDomainSystemWorkWindow(midMonth);

        assertThat(beforeBoundary.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isZero();
        assertThat(beforeBoundary.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
        assertThat(map(beforeBoundary.getResult().getDomainSystemWork().getNextDueHints().getFirst()))
                .containsEntry("nextDueAt", "2026-05-01T00:00:00Z");

        long justBeforeMay = 1_777_593_540_000L;
        long mayBoundary = 1_777_593_600_000L;
        RemoteDomainSystemWorkWindowRequest boundaryRequest = domainSystemWorkWindowRequest(
                beforeBoundary.getNextState(),
                beforeBoundary.getResult().getRuntimeIdentity(),
                justBeforeMay,
                mayBoundary,
                "credit_card_statement_close");
        var executed = service.executeDomainSystemWorkWindow(boundaryRequest);

        assertThat(executed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(executed.getResult().getDomainSystemWork().getWorkResults()).singleElement().satisfies(result -> {
            assertThat(result.getWorkId()).isEqualTo("auto-credit-card-statements");
            assertThat(result.getKind()).isEqualTo("credit_card_statement_close");
            assertThat(result.getOccurrenceKey())
                    .isEqualTo("banking|domain-system|auto-credit-card-statements|2026-05-01");
            assertThat(result.getScheduledForEpochMs()).isEqualTo(mayBoundary);
            assertThat(result.getOutcome().getValue()).isEqualTo("APPLIED");
        });
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.getTriggerType()).isEqualTo("statement_close");
            assertThat(trigger.getTriggerRef()).isEqualTo(
                    "banking|domain-system|auto-credit-card-statements|2026-05-01"
                            + "|account:acct-rewards-card-1");
            assertThat(trigger.getScheduledForEpochMs()).isEqualTo(mayBoundary);
            assertThat(trigger.getPayload())
                    .containsEntry("accountId", "acct-rewards-card-1")
                    .containsEntry("statementStartDate", "2026-04-01")
                    .containsEntry("statementEndDate", "2026-04-30");
        });

        RemoteDomainSystemWorkWindowRequest replay = domainSystemWorkWindowRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                justBeforeMay,
                mayBoundary,
                "credit_card_statement_close");
        var replayed = service.executeDomainSystemWorkWindow(replay);

        assertThat(replayed.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isZero();
        assertThat(replayed.getResult().getDomainSystemWork().getExecutedWorkCount()).isZero();
        assertThat(replayed.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
        assertThat(replayed.getNextState()).isEqualTo(executed.getNextState());
    }

    @Test
    void domainSystemWorkWindowEnumeratesEveryCompletedStatementMonthOrFailsVisiblyAtCapacity() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(creditCardPersonaConfiguration()));
        long march15 = 1_773_532_800_000L;
        long july1 = 1_782_864_000_000L;

        RemoteDomainSystemWorkWindowRequest request = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                march15,
                july1,
                "credit_card_statement_close");
        var executed = service.executeDomainSystemWorkWindow(request);

        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getOccurrenceKey())
                .containsExactly(
                        "banking|domain-system|auto-credit-card-statements|2026-04-01",
                        "banking|domain-system|auto-credit-card-statements|2026-05-01",
                        "banking|domain-system|auto-credit-card-statements|2026-06-01",
                        "banking|domain-system|auto-credit-card-statements|2026-07-01");
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers()).hasSize(4);
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers())
                .extracting(trigger -> trigger.getScheduledForEpochMs())
                .containsExactly(
                        1_775_001_600_000L,
                        1_777_593_600_000L,
                        1_780_272_000_000L,
                        1_782_864_000_000L);
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers())
                .extracting(trigger -> trigger.getTriggerId())
                .doesNotHaveDuplicates();
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers())
                .extracting(trigger -> trigger.getPayload().get("statementStartDate")
                        + ":" + trigger.getPayload().get("statementEndDate"))
                .containsExactly(
                        "2026-03-01:2026-03-31",
                        "2026-04-01:2026-04-30",
                        "2026-05-01:2026-05-31",
                        "2026-06-01:2026-06-30");

        RemoteDomainSystemWorkWindowRequest undersized = domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                march15,
                july1,
                "credit_card_statement_close");
        undersized.getPayload().setMaxWorkItems(3);

        assertThatThrownBy(() -> service.executeDomainSystemWorkWindow(undersized))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("cannot publish every completed monthly statement");
    }

    @Test
    void autoStatementTriggersOnlyForIndebtedCreditCards() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-debt-card",
                        "name", "Debt Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", -1200.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-credit-card",
                        "name", "Credit Balance Card",
                        "productCode", "CC001",
                        "productCategory", "CRED_AND_CHRG_CARDS",
                        "balance", 100.0,
                        "currency", "AUD")));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));
        var executed = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                1_777_593_540_000L,
                1_777_593_600_000L,
                "credit_card_statement_close"));

        assertThat(executed.getResult().getEvidenceEvents()).hasSize(2);
        assertThat(executed.getResult().getEvidenceEvents())
                .filteredOn(event -> "acct-credit-card".equals(event.get("accountId")))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("minimumAmountDue", 0.0));
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers())
                .singleElement()
                .satisfies(trigger -> assertThat(trigger.getPayload()).containsEntry("accountId", "acct-debt-card"));
    }

    @Test
    void controlActionRecordsDomainOwnedIntervention() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        var executed = service.executeControlAction(controlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity()));

        assertThat(executed.getOperation().getValue()).isEqualTo("control-action");
        assertThat(executed.getResult().getControlAction())
                .containsEntry("actionId", "broadcast-message")
                .containsEntry("targetScope", "AGENT");
        assertThat(executed.getResult().getEvidenceEvents())
                .extracting(event -> event.get("eventType"))
                .contains("remote_domain_control_action_applied");
        assertThat(map(executed.getNextState().getData().get("lastControlAction")))
                .containsEntry("actionId", "broadcast-message");
        assertThat(executed.getResult().getProjection().getStatusView().get("lastControlAction"))
                .isInstanceOf(Map.class);
    }

    @Test
    void clockDrivenRateChangeCommitsAtDayTenBeforeEmittingOneCorrelatedTrigger() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("initialAccounts", List.of(
                Map.of(
                        "accountId", "acct-rewards-card-1",
                        "name", "Rewards Card",
                        "productCode", "CC001",
                        "balance", -1200.0,
                        "limit", 3000.0,
                        "currency", "AUD"),
                Map.of(
                        "accountId", "acct-transaction-1",
                        "name", "Everyday Account",
                        "productCode", "TX001",
                        "balance", 500.0,
                        "currency", "AUD")));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));
        BankingClockDrivenRateChangePlan.Snapshot snapshot = BankingClockDrivenRateChangePlan.compileForLaunch(
                        "run-rate-change-001",
                        "credit-apr-lift",
                        rateChangeParameters())
                .orElseThrow();

        var installed = service.executeControlAction(rateChangeControlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                snapshot));
        List<?> installedAccounts = list(installed.getNextState().getData().get("accounts"));
        assertThat(map(installedAccounts.get(0)))
                .containsEntry("interestRate", "0.19")
                .containsEntry("apr", "0.19");
        assertThat(map(installedAccounts.get(1)))
                .doesNotContainKeys("interestRate", "apr", "ratePlanDigest");

        long scheduledFor = (long) snapshot.plan().get("scheduledForEpochMs");
        var beforeDayTen = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                installed.getNextState(),
                installed.getResult().getRuntimeIdentity(),
                scheduledFor - 86_400_000L,
                scheduledFor - 1L,
                BankingClockDrivenRateChangePlan.WORK_KIND));
        assertThat(beforeDayTen.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isZero();
        assertThat(beforeDayTen.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();

        var atDayTen = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                beforeDayTen.getNextState(),
                beforeDayTen.getResult().getRuntimeIdentity(),
                scheduledFor - 1L,
                scheduledFor,
                BankingClockDrivenRateChangePlan.WORK_KIND));
        List<?> changedAccounts = list(atDayTen.getNextState().getData().get("accounts"));
        assertThat(map(changedAccounts.get(0)))
                .containsEntry("interestRate", "0.24")
                .containsEntry("apr", "0.24")
                .containsEntry("rateChangeOperationId", snapshot.plan().get("mutationOperationId"));
        assertThat(map(changedAccounts.get(1)))
                .doesNotContainKeys("interestRate", "apr", "rateChangeOperationId");
        assertThat(atDayTen.getResult().getDomainSystemWork().getDecisionTriggers())
                .singleElement()
                .satisfies(trigger -> {
                    assertThat(trigger.getTriggerType()).isEqualTo("rate_or_policy_change");
                    assertThat(trigger.getScheduledForEpochMs()).isEqualTo(scheduledFor);
                    assertThat(trigger.getPayload())
                            .containsEntry("simulationRunId", "run-rate-change-001")
                            .containsEntry("customerId", "banking-remote-agent")
                            .containsEntry("interventionId", "policy_rate_candidate_shift")
                            .containsEntry("operationId", snapshot.plan().get("mutationOperationId"))
                            .containsEntry("planDigest", snapshot.planDigest())
                            .containsEntry("oldRate", "0.19")
                            .containsEntry("newRate", "0.24")
                            .containsEntry("affectedAccountCount", "1");
                });
        assertThat(map(list(atDayTen.getNextState().getData()
                .get("appliedInterventionOperations")).getFirst()))
                .containsEntry("workId", snapshot.plan().get("workId"))
                .containsEntry("occurrenceKey", "banking|clock-driven-rate-change|"
                        + snapshot.plan().get("mutationOperationId"))
                .containsEntry("scheduledForEpochMs", scheduledFor)
                .containsEntry("appliedAtEpochMs", scheduledFor)
                .containsEntry("affectedAccountCount", 1)
                .containsEntry("affectedAccountIds", List.of("acct-rewards-card-1"))
                .containsEntry("outcome", "APPLIED");

        Map<String, Object> contradictoryData = new LinkedHashMap<>(
                atDayTen.getNextState().getData());
        Map<String, Object> contradictoryReceipt = new LinkedHashMap<>();
        map(list(contradictoryData.get("appliedInterventionOperations")).getFirst())
                .forEach((key, value) -> contradictoryReceipt.put(String.valueOf(key), value));
        contradictoryReceipt.put("outcome", "SKIPPED");
        contradictoryData.put("appliedInterventionOperations", List.of(contradictoryReceipt));
        contradictoryData.put("executionLog", List.of());
        var contradictoryReplay = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                new RemoteDomainOpaqueState(
                        atDayTen.getNextState().getSchemaVersion(),
                        contradictoryData),
                atDayTen.getResult().getRuntimeIdentity(),
                scheduledFor - 1L,
                scheduledFor,
                BankingClockDrivenRateChangePlan.WORK_KIND));
        assertThat(contradictoryReplay.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
        assertThat(contradictoryReplay.getResult().getDomainSystemWork().getWorkResults())
                .singleElement()
                .satisfies(result -> assertThat(result.getOutcome())
                        .isEqualTo(RemoteDomainSystemWorkResult.OutcomeEnum.REJECTED));

        var retried = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                atDayTen.getNextState(),
                atDayTen.getResult().getRuntimeIdentity(),
                scheduledFor,
                scheduledFor + 86_400_000L,
                BankingClockDrivenRateChangePlan.WORK_KIND));
        assertThat(retried.getResult().getDomainSystemWork().getEvaluatedWorkCount()).isZero();
        assertThat(retried.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
        assertThat(list(retried.getNextState().getData().get("appliedInterventionOperations"))).hasSize(1);

        var interest = service.executeWork(executeDomainSystemWorkRequest(
                retried.getNextState(),
                retried.getResult().getRuntimeIdentity(),
                "post-rate-interest",
                "banking|interest|post-rate",
                "Interest after rate change",
                Map.of(
                        "kind", "credit_card_interest_accrual",
                        "productCode", "CC001",
                        "annualRate", "0.19")));
        assertThat(interest.getResult().getEvidenceEvents())
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("annualRate", 0.24));
    }

    @Test
    void catchUpWindowAppliesRateChangeBeforeInterestAndUsesActualApplicationTime() {
        LinkedHashMap<String, Object> configuration =
                new LinkedHashMap<>(creditCardPersonaConfiguration());
        configuration.put("scheduledWork", List.of(Map.of(
                "workId", "catch-up-interest",
                "kind", "credit_card_interest_accrual",
                "description", "Interest in the catch-up window",
                "payload", Map.of(
                        "kind", "credit_card_interest_accrual",
                        "productCode", "CC001",
                        "annualRate", "0.19"))));
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(configuration));
        BankingClockDrivenRateChangePlan.Snapshot snapshot = BankingClockDrivenRateChangePlan.compileForLaunch(
                        "run-rate-change-catch-up",
                        "credit-apr-lift",
                        rateChangeParameters())
                .orElseThrow();
        var installed = service.executeControlAction(rateChangeControlActionRequest(
                prepared.getNextState(), prepared.getResult().getRuntimeIdentity(), snapshot));
        long scheduledFor = (long) snapshot.plan().get("scheduledForEpochMs");
        long actualApplicationTime = scheduledFor + (2L * 86_400_000L);

        var executed = service.executeDomainSystemWorkWindow(domainSystemWorkWindowRequest(
                installed.getNextState(),
                installed.getResult().getRuntimeIdentity(),
                scheduledFor - 86_400_000L,
                actualApplicationTime,
                BankingClockDrivenRateChangePlan.WORK_KIND,
                "credit_card_interest_accrual"));

        assertThat(executed.getResult().getDomainSystemWork().getWorkResults())
                .extracting(result -> result.getKind())
                .containsExactly(
                        BankingClockDrivenRateChangePlan.WORK_KIND,
                        "credit_card_interest_accrual");
        assertThat(map(list(executed.getNextState().getData()
                        .get("appliedInterventionOperations")).getFirst()))
                .containsEntry("scheduledForEpochMs", scheduledFor)
                .containsEntry("effectiveAtEpochMs", scheduledFor)
                .containsEntry("appliedAtEpochMs", actualApplicationTime);
        assertThat(executed.getResult().getDomainSystemWork().getDecisionTriggers())
                .singleElement()
                .satisfies(trigger -> {
                    assertThat(trigger.getScheduledForEpochMs()).isEqualTo(actualApplicationTime);
                    assertThat(trigger.getPayload())
                            .containsEntry("scheduledForEpochMs", Long.toString(scheduledFor))
                            .containsEntry("effectiveAtEpochMs", Long.toString(scheduledFor))
                            .containsEntry("appliedAtEpochMs", Long.toString(actualApplicationTime));
                });
        assertThat(executed.getResult().getEvidenceEvents())
                .filteredOn(event -> "remote_domain_credit_card_interest_charged"
                        .equals(event.get("eventType")))
                .singleElement()
                .satisfies(event -> assertThat(event).containsEntry("annualRate", 0.24));
    }

    @Test
    void rateChangePlanOperationReuseWithContradictoryFactsFailsClosed() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(creditCardPersonaConfiguration()));
        BankingClockDrivenRateChangePlan.Snapshot snapshot = BankingClockDrivenRateChangePlan.compileForLaunch(
                        "run-rate-change-001",
                        "credit-apr-lift",
                        rateChangeParameters())
                .orElseThrow();
        var installed = service.executeControlAction(rateChangeControlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                snapshot));
        RemoteDomainControlActionRequest contradiction = rateChangeControlActionRequest(
                installed.getNextState(),
                installed.getResult().getRuntimeIdentity(),
                snapshot);
        Map<String, Object> changedInputs = new LinkedHashMap<>(snapshot.asMap());
        changedInputs.put("planDigest", "0".repeat(64));
        contradiction.getPayload().setInputs(changedInputs);

        assertThatThrownBy(() -> service.executeControlAction(contradiction))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("operationId was already applied");
    }

    @Test
    void rateChangePlanRejectsMissingOrCrossRunTargetSelectorBeforeMutation() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest(creditCardPersonaConfiguration()));
        BankingClockDrivenRateChangePlan.Snapshot snapshot = BankingClockDrivenRateChangePlan.compileForLaunch(
                        "run-rate-change-target-binding",
                        "credit-apr-lift",
                        rateChangeParameters())
                .orElseThrow();

        for (Map<String, Object> selector : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("simulationRunId", "other-run"))) {
            RemoteDomainControlActionRequest request = rateChangeControlActionRequest(
                    prepared.getNextState(),
                    prepared.getResult().getRuntimeIdentity(),
                    snapshot);
            request.getPayload().getTarget().setSelector(selector);

            assertThatThrownBy(() -> service.executeControlAction(request))
                    .isInstanceOf(RemoteDomainProtocolException.class)
                    .hasMessageContaining("Rate-change plan installation requires");
        }

        assertThat(prepared.getNextState().getData())
                .doesNotContainKeys("clockDrivenInterventionPlan", "appliedInterventionOperations");
    }

    @Test
    void controlActionIsIdempotentByOperationId() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        var executed = service.executeControlAction(controlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity()));
        var retried = service.executeControlAction(controlActionRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity()));

        assertThat(list(retried.getNextState().getData().get("controlActions"))).hasSize(1);
        assertThat(retried.getResult().getControlAction())
                .containsEntry("operationId", "operator-control-action-" + NOW_EPOCH_MS);
        assertThat(retried.getResult().getEvidenceEvents())
                .extracting(event -> event.get("correlationId"))
                .containsExactly("operator-control-action-" + NOW_EPOCH_MS);
    }

    @Test
    void controlActionNormalizesOperationIdBeforeDedupe() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());
        String paddedOperationId = "  operator-control-action-" + NOW_EPOCH_MS + "  ";

        var executed = service.executeControlAction(controlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                paddedOperationId));
        var retried = service.executeControlAction(controlActionRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                paddedOperationId));

        assertThat(list(retried.getNextState().getData().get("controlActions"))).hasSize(1);
        assertThat(retried.getResult().getControlAction())
                .containsEntry("operationId", "operator-control-action-" + NOW_EPOCH_MS);
        assertThat(retried.getResult().getEvidenceEvents())
                .extracting(event -> event.get("correlationId"))
                .containsExactly("operator-control-action-" + NOW_EPOCH_MS);
    }

    @Test
    void controlActionRejectsOperationIdReuseWithDifferentInputs() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        var executed = service.executeControlAction(controlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity()));
        RemoteDomainControlActionRequest conflicting = controlActionRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity());
        conflicting.getPayload().setInputs(new LinkedHashMap<>(Map.of(
                "message", "Different operator message",
                "severity", "WARN")));

        assertThatThrownBy(() -> service.executeControlAction(conflicting))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("operationId was already applied");
    }

    @Test
    void controlActionRequiresPublishedMessageInput() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());
        RemoteDomainControlActionRequest request = controlActionRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity());
        request.getPayload().setInputs(new LinkedHashMap<>());

        assertThatThrownBy(() -> service.executeControlAction(request))
                .isInstanceOf(RemoteDomainProtocolException.class)
                .hasMessageContaining("requires a non-empty message");
    }

    @Test
    void executeWorkReturnsRemoteToolResultForProductList() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());

        RemoteDomainExecuteWorkRequest executeRequest = executeToolRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "get_product_list",
                Map.of());
        var executed = service.executeWork(executeRequest);

        Map<String, Object> toolResult = executed.getResult().getToolResult();
        assertThat(toolResult)
                .containsEntry("toolName", "get_product_list")
                .containsEntry("success", true);
        assertThat(list(toolResult.get("products")))
                .extracting(product -> map(product).get("productCode"))
                .contains("TS001", "SAV001", "CC001", "CC002", "TX001");
        assertThat(executed.getNextState()).isEqualTo(prepared.getNextState());

        RemoteDomainExecuteWorkRequest replayRequest = executeToolRequest(
                executed.getNextState(),
                executed.getResult().getRuntimeIdentity(),
                "get_product_list",
                Map.of());
        replayRequest.setIdempotencyKey("banking-remote-agent:read-only-attempt-2");
        var replay = service.executeWork(replayRequest);

        assertThat(replay.getNextState()).isEqualTo(executed.getNextState());
        assertThat(replay.getResult().getToolResult())
                .containsEntry("toolName", "get_product_list")
                .containsEntry("success", true)
                .doesNotContainKey("idempotentReplay");
        assertThat(list(replay.getResult().getToolResult().get("products")))
                .hasSameSizeAs(list(toolResult.get("products")));
    }

    @Test
    void executeWorkDoesNotReapplyAStableRemoteToolWorkId() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());
        Map<String, Object> arguments = Map.of(
                "debits", List.of(Map.of("accountName", "Main Account", "amount", 50)),
                "credits", List.of(Map.of("accountName", "Main Account", "amount", 50)));

        RemoteDomainExecuteWorkRequest firstRequest = executeToolRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "post_double_entry_transaction",
                arguments);
        var first = service.executeWork(firstRequest);

        RemoteDomainExecuteWorkRequest replayRequest = executeToolRequest(
                first.getNextState(),
                first.getResult().getRuntimeIdentity(),
                "post_double_entry_transaction",
                arguments);
        replayRequest.setIdempotencyKey("banking-remote-agent:execute-work:attempt-2");
        var replay = service.executeWork(replayRequest);

        Map<String, Object> replayState = replay.getNextState().getData();
        assertThat(replay.getNextState()).isEqualTo(first.getNextState());
        assertThat(list(first.getNextState().getData().get("transactionPostings"))).hasSize(1);
        assertThat(list(replayState.get("transactionPostings"))).hasSize(1);
        assertThat(replayState).containsEntry("executedWorkCount", 1);
        assertThat(replay.getResult().getToolResult())
                .containsEntry("success", true)
                .containsEntry("idempotentReplay", true);
        assertThat(list(replayState.get("executionLog"))).hasSize(1);
        assertThat(map(list(replayState.get("executionLog")).getFirst()))
                .containsEntry("status", "APPLIED");
        assertThat(replay.getResult().getEvidenceEvents()).hasSize(1);
        assertThat(map(replay.getResult().getEvidenceEvents().getFirst()))
                .containsEntry("eventType", "remote_domain_tool_effect_replayed")
                .containsEntry("outcome", "NOOP")
                .containsEntry("workId", "tool-work-1");
    }

    @Test
    void rejectedDoubleEntryToolCallDoesNotPartiallyMutateOrCompoundOnRetry() {
        RemoteDomainPrepareResponse prepared = service.prepare(prepareRequest());
        Map<String, Object> arguments = Map.of(
                "debits", List.of(Map.of("accountName", "Main Account", "amount", 50)),
                "credits", List.of(Map.of("accountName", "Missing Account", "amount", 50)));

        RemoteDomainExecuteWorkRequest firstRequest = executeToolRequest(
                prepared.getNextState(),
                prepared.getResult().getRuntimeIdentity(),
                "post_double_entry_transaction",
                arguments);
        var first = service.executeWork(firstRequest);

        RemoteDomainExecuteWorkRequest retryRequest = executeToolRequest(
                first.getNextState(),
                first.getResult().getRuntimeIdentity(),
                "post_double_entry_transaction",
                arguments);
        retryRequest.setIdempotencyKey("banking-remote-agent:execute-work:failed-attempt-2");
        var retry = service.executeWork(retryRequest);

        assertThat(first.getResult().getToolResult())
                .containsEntry("success", false);
        assertThat(retry.getResult().getToolResult())
                .containsEntry("success", false);
        assertThat(list(first.getNextState().getData().get("transactionPostings"))).isEmpty();
        assertThat(list(retry.getNextState().getData().get("transactionPostings"))).isEmpty();
        assertThat(map(list(first.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", 2500.0);
        assertThat(map(list(retry.getNextState().getData().get("accounts")).getFirst()))
                .containsEntry("balance", 2500.0);
    }

    @Test
    void nativeCustomerAccountToolReadsAcceptedStateWithoutMutationAndReplaysExactly() {
        var mapper = new ObjectMapper().rebuild().findAndAddModules().build();
        var nativeService = new BankingRemoteDomainService(mapper, true);
        long time = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli();
        var prepare = prepareRequest(creditCardPersonaConfiguration());
        prepare.getPayload().setInitialProjectionHints(Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.NATIVE_PROFILE, "contextId", "native-card",
                "expectedStateVersion", 0L, "logicalTimeEpochMs", time, "logicalHorizonEpochMs", time + 86400000L)));
        var prepared = nativeService.prepare(prepare);
        var read = executeToolRequest(prepared.getNextState(), prepared.getResult().getRuntimeIdentity(),
                "get_customer_accounts", Map.of());
        read.getPayload().getTurnContext().setTimeWindow(new RemoteDomainTurnContextTimeWindow(time, time));
        read.getPayload().getTurnContext().getDueWork().getFirst().setScheduledForEpochMs(time);

        var response = nativeService.executeWork(read);
        assertThat(response.getNextState()).isEqualTo(prepared.getNextState());
        assertThat(mapper.<tools.jackson.databind.JsonNode>valueToTree(nativeService.executeWork(read)))
                .isEqualTo(mapper.valueToTree(response));
        var result = response.getResult().getToolResult();
        assertThat(map(list(result.get("accounts")).getFirst()))
                .containsEntry("accountId", "acct-rewards-card-1")
                .containsEntry("balance", -1200.0).containsEntry("limit", 3000.0);
        assertThat(map(list(result.get("statements")).getFirst()))
                .containsEntry("minimumAmountDue", 48.0).containsEntry("dueDate", "2026-02-14");
        assertThat(response.getResult().getProjection().getRuntimeMetadata().get("nativeSimulation"))
                .isEqualTo(prepared.getResult().getProjection().getRuntimeMetadata().get("nativeSimulation"));
        assertThat(response.getResult().getEvidenceEvents()).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("eventType", "remote_domain_customer_accounts_returned"));

        var descriptor = nativeService.manifest().getCapabilities().getTools().stream()
                .filter(tool -> "get_customer_accounts".equals(tool.getName())).findFirst().orElseThrow();
        assertThat(mapper.valueToTree(descriptor).path("effectClass").asText()).isEqualTo("READ_ONLY");
        assertThat(service.manifest().getCapabilities().getTools()).noneMatch(tool -> "get_customer_accounts".equals(tool.getName()));

        read.getPayload().getTurnContext().getDueWork().getFirst().getPayload().put("arguments", Map.of("customerId", "another-customer"));
        assertThatThrownBy(() -> nativeService.executeWork(read)).isInstanceOf(IllegalArgumentException.class);
        read.getPayload().getTurnContext().getDueWork().getFirst().getPayload().put("arguments", Map.of());
        read.getPayload().getTurnContext().getDueWork().getFirst().getPayload().put("toolName", "post_double_entry_transaction");
        assertThatThrownBy(() -> nativeService.executeWork(read)).isInstanceOf(IllegalArgumentException.class);
        read.getPayload().getTurnContext().getDueWork().getFirst().getPayload().put("toolName", "get_customer_accounts");
        read.getPayload().getTurnContext().getTimeWindow().setCurrentTimeEpochMs(time + 1L);
        assertThatThrownBy(() -> nativeService.executeWork(read)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativeProfileReplaysExactStateAndStopsAtEarliestStatementBoundary() {
        var service = new BankingRemoteDomainService(new ObjectMapper().rebuild().findAndAddModules().build(), true);
        long start = java.time.Instant.parse("2026-01-31T00:00:00Z").toEpochMilli();
        long boundary = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli();
        long horizon = java.time.Instant.parse("2026-02-28T00:00:00Z").toEpochMilli();
        var request = prepareRequest(creditCardPersonaConfiguration());
        request.getPayload().setInitialProjectionHints(Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.NATIVE_PROFILE, "contextId", "native-card",
                "expectedStateVersion", 0L, "logicalTimeEpochMs", start, "logicalHorizonEpochMs", horizon)));
        var prepared = service.prepare(request);
        var mapper = new ObjectMapper().rebuild().findAndAddModules().build();
        assertThat(mapper.<tools.jackson.databind.JsonNode>valueToTree(service.prepare(request))).isEqualTo(mapper.valueToTree(prepared));
        var metadata = map(prepared.getResult().getProjection().getRuntimeMetadata().get("nativeSimulation"));
        assertThat(metadata).containsEntry("nextDueEpochMs", boundary);
        assertThat(list(map(metadata.get("observation")).get("statements"))).isEmpty();
        var manifest = mapper.valueToTree(service.manifest());
        new RemoteDomainContractValidator(mapper).assertValid("manifest", manifest);
        assertThat(manifest.at("/capabilities/nativeSimulation/profile").asText())
                .isEqualTo(BankingRemoteDomainService.NATIVE_PROFILE);

        var window = domainSystemWorkWindowRequest(prepared.getNextState(), prepared.getResult().getRuntimeIdentity(),
                start, boundary, "credit_card_statement_close");
        window.getPayload().setMaxWorkItems(1);
        window.getPayload().setContext(Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.NATIVE_PROFILE, "contextId", "native-card",
                "expectedStateVersion", 1L, "logicalHorizonEpochMs", horizon)));
        var result = service.executeDomainSystemWorkWindow(window);
        assertThat(mapper.<tools.jackson.databind.JsonNode>valueToTree(service.executeDomainSystemWorkWindow(window))).isEqualTo(mapper.valueToTree(result));
        assertThat(result.getResult().getDomainSystemWork().getExecutedWorkCount()).isEqualTo(1);
        assertThat(result.getResult().getDomainSystemWork().getDecisionTriggers()).singleElement()
                .satisfies(trigger -> assertThat(trigger.getTriggerType()).isEqualTo("statement_close"));
        var nativeResult = map(result.getResult().getProjection().getRuntimeMetadata().get("nativeSimulation"));
        assertThat(nativeResult).containsEntry("stateVersion", 2L).containsEntry("noWorkBeforeEpochMs", horizon + 1L);
        var bill = map(list(map(nativeResult.get("observation")).get("statements")).getFirst());
        assertThat(bill).containsEntry("minimumAmountDue", 48.0).containsEntry("dueDate", "2026-02-14");
        window.getPayload().getTimeWindow().setCurrentTimeEpochMs(boundary + 1);
        assertThatThrownBy(() -> service.executeDomainSystemWorkWindow(window)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nativePreparationIncludesStatementExactlyAtStartWithoutDuplicatingIt() {
        var service = new BankingRemoteDomainService(new ObjectMapper().rebuild().findAndAddModules().build(), true);
        long start = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli();
        long horizon = java.time.Instant.parse("2026-03-02T00:00:00Z").toEpochMilli();
        var request = prepareRequest(creditCardPersonaConfiguration());
        request.getPayload().setInitialProjectionHints(Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.NATIVE_PROFILE, "contextId", "native-card",
                "expectedStateVersion", 0L, "logicalTimeEpochMs", start, "logicalHorizonEpochMs", horizon)));
        var prepared = service.prepare(request);
        assertThat(prepared.getResult().getEvidenceEvents()).hasSize(1);
        assertThat(prepared.getResult().getDomainSystemWork().getDecisionTriggers()).hasSize(1);
        assertThat(list(prepared.getNextState().getData().get("creditCardBills"))).hasSize(1);
        var window = domainSystemWorkWindowRequest(prepared.getNextState(), prepared.getResult().getRuntimeIdentity(),
                start, start + 1L, "credit_card_statement_close");
        window.getPayload().setMaxWorkItems(1);
        window.getPayload().setContext(Map.of("nativeSimulation", Map.of(
                "profile", BankingRemoteDomainService.NATIVE_PROFILE, "contextId", "native-card",
                "expectedStateVersion", 1L, "logicalHorizonEpochMs", horizon)));
        var result = service.executeDomainSystemWorkWindow(window);
        assertThat(list(result.getNextState().getData().get("creditCardBills"))).hasSize(1);
        assertThat(result.getResult().getDomainSystemWork().getDecisionTriggers()).isEmpty();
    }

    private static RemoteDomainPrepareRequest prepareRequest() {
        return prepareRequest(personaConfiguration());
    }

    private static RemoteDomainPrepareRequest prepareRequest(Map<String, Object> personaConfiguration) {
        RemoteDomainPrepareRequest request = new RemoteDomainPrepareRequest();
        request.setProtocolVersion(RemoteDomainPrepareRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainPrepareRequest.OperationEnum.PREPARE);
        request.setIdempotencyKey("banking-remote-agent:prepare:" + NOW_EPOCH_MS);
        request.setSentAtEpochMs(NOW_EPOCH_MS);
        request.setDeadlineEpochMs(NOW_EPOCH_MS + 5_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(null));
        request.setPayload(new RemoteDomainPrepareRequestAllOfPayload(personaConfiguration));
        return request;
    }

    private static RemoteDomainExecuteWorkRequest executeWorkRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity) {
        RemoteDomainExecuteWorkRequest request = new RemoteDomainExecuteWorkRequest();
        request.setProtocolVersion(RemoteDomainExecuteWorkRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainExecuteWorkRequest.OperationEnum.EXECUTE_WORK);
        request.setIdempotencyKey("banking-remote-agent:execute-work:" + NOW_EPOCH_MS);
        request.setSentAtEpochMs(NOW_EPOCH_MS + 60_000);
        request.setDeadlineEpochMs(NOW_EPOCH_MS + 65_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(runtimeIdentity));
        request.setState(state);

        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                "rent-work-1",
                "DOMAIN_COMMAND",
                "banking|rent|2026-05-01",
                NOW_EPOCH_MS,
                1);
        dueWork.setDescription("Monthly Rent");
        dueWork.setPayload(new LinkedHashMap<>(Map.of(
                "kind", "transaction",
                "type", "Payment",
                "amount", 1500.0,
                "isAmountExact", true,
                "details", Map.of(
                        "from", List.of("Main Account"),
                        "to", List.of("Landlord")))));

        request.setPayload(new RemoteDomainExecuteWorkRequestAllOfPayload(new RemoteDomainTurnContext(
                new RemoteDomainTurnContextTimeWindow(NOW_EPOCH_MS, NOW_EPOCH_MS + 60_000),
                List.of(dueWork))));
        return request;
    }

    private static RemoteDomainExecuteWorkRequest executeToolRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            String toolName,
            Map<String, Object> arguments) {
        RemoteDomainExecuteWorkRequest request = executeWorkRequest(state, runtimeIdentity);
        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                "tool-work-1",
                "TOOL_CALL",
                "banking|tool|" + toolName,
                NOW_EPOCH_MS,
                1);
        dueWork.setDescription("Remote tool call " + toolName);
        dueWork.setPayload(new LinkedHashMap<>(Map.of(
                "kind", "tool-call",
                "toolName", toolName,
                "arguments", arguments)));
        request.setPayload(new RemoteDomainExecuteWorkRequestAllOfPayload(new RemoteDomainTurnContext(
                new RemoteDomainTurnContextTimeWindow(NOW_EPOCH_MS, NOW_EPOCH_MS + 60_000),
                List.of(dueWork))));
        return request;
    }

    private static RemoteDomainExecuteWorkRequest executeDomainSystemWorkRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            String workId,
            String occurrenceKey,
            String description,
            Map<String, Object> payload) {
        RemoteDomainExecuteWorkRequest request = new RemoteDomainExecuteWorkRequest();
        request.setProtocolVersion(RemoteDomainExecuteWorkRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainExecuteWorkRequest.OperationEnum.EXECUTE_WORK);
        request.setIdempotencyKey("banking-remote-agent:execute-domain-work:" + workId);
        request.setSentAtEpochMs(NOW_EPOCH_MS + 60_000);
        request.setDeadlineEpochMs(NOW_EPOCH_MS + 65_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(runtimeIdentity));
        request.setState(state);

        RemoteDomainDueWorkItem dueWork = new RemoteDomainDueWorkItem(
                workId,
                "DOMAIN_COMMAND",
                occurrenceKey,
                NOW_EPOCH_MS + 60_000,
                1);
        dueWork.setDescription(description);
        dueWork.setPayload(new LinkedHashMap<>(payload));
        request.setPayload(new RemoteDomainExecuteWorkRequestAllOfPayload(new RemoteDomainTurnContext(
                new RemoteDomainTurnContextTimeWindow(NOW_EPOCH_MS, NOW_EPOCH_MS + 60_000),
                List.of(dueWork))));
        return request;
    }

    private static RemoteDomainSystemWorkWindowRequest domainSystemWorkWindowRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            String... workKinds) {
        return domainSystemWorkWindowRequest(
                state,
                runtimeIdentity,
                NOW_EPOCH_MS,
                NOW_EPOCH_MS + 60_000,
                workKinds);
    }

    private static RemoteDomainSystemWorkWindowRequest domainSystemWorkWindowRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            long previousTimeEpochMs,
            long currentTimeEpochMs,
            String... workKinds) {
        RemoteDomainSystemWorkWindowRequest request = new RemoteDomainSystemWorkWindowRequest();
        request.setProtocolVersion(RemoteDomainSystemWorkWindowRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainSystemWorkWindowRequest.OperationEnum.DOMAIN_SYSTEM_WORK_WINDOW);
        request.setIdempotencyKey("banking-remote-agent:domain-system-work-window:" + currentTimeEpochMs);
        request.setSentAtEpochMs(currentTimeEpochMs);
        request.setDeadlineEpochMs(currentTimeEpochMs + 5_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(runtimeIdentity));
        request.setState(state);

        RemoteDomainSystemWorkWindowPayload payload = new RemoteDomainSystemWorkWindowPayload(
                new RemoteDomainTimeWindow(previousTimeEpochMs, currentTimeEpochMs));
        payload.setWorkKinds(List.of(workKinds));
        payload.setMaxWorkItems(10);
        payload.setExecutionMode(RemoteDomainSystemWorkWindowPayload.ExecutionModeEnum.EVALUATE_AND_EXECUTE);
        request.setPayload(payload);
        return request;
    }

    private static RemoteDomainControlActionRequest controlActionRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity) {
        return controlActionRequest(
                state,
                runtimeIdentity,
                "operator-control-action-" + NOW_EPOCH_MS);
    }

    private static RemoteDomainControlActionRequest controlActionRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            String operationId) {
        RemoteDomainControlActionRequest request = new RemoteDomainControlActionRequest();
        request.setProtocolVersion(RemoteDomainControlActionRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainControlActionRequest.OperationEnum.CONTROL_ACTION);
        request.setIdempotencyKey("banking-remote-agent:control-action:" + operationId.trim());
        request.setSentAtEpochMs(NOW_EPOCH_MS + 60_000);
        request.setDeadlineEpochMs(NOW_EPOCH_MS + 65_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(runtimeIdentity));
        request.setState(state);
        RemoteDomainControlActionTarget target = new RemoteDomainControlActionTarget(
                RemoteDomainControlActionTarget.ScopeEnum.AGENT);
        target.setAgentIds(List.of("banking-remote-agent"));
        RemoteDomainControlActionPayload payload = new RemoteDomainControlActionPayload(
                "broadcast-message",
                operationId,
                target,
                new LinkedHashMap<>(Map.of(
                        "message", "Branch outage",
                        "severity", "WARN")));
        payload.setRequestedBy("operator");
        payload.setReason("Exercise domain-owned control action.");
        payload.setSimulationTimeEpochMs(NOW_EPOCH_MS + 60_000);
        request.setPayload(payload);
        return request;
    }

    private static RemoteDomainControlActionRequest rateChangeControlActionRequest(
            RemoteDomainOpaqueState state,
            String runtimeIdentity,
            BankingClockDrivenRateChangePlan.Snapshot snapshot) {
        String operationId = BankingClockDrivenRateChangePlan.installOperationId(snapshot);
        RemoteDomainControlActionRequest request = new RemoteDomainControlActionRequest();
        request.setProtocolVersion(RemoteDomainControlActionRequest.ProtocolVersionEnum.REMOTE_DOMAIN_V1);
        request.setRequestId(UUID.randomUUID());
        request.setOperation(RemoteDomainControlActionRequest.OperationEnum.CONTROL_ACTION);
        request.setIdempotencyKey("banking-remote-agent:control-action:" + operationId);
        request.setSentAtEpochMs(NOW_EPOCH_MS);
        request.setDeadlineEpochMs(NOW_EPOCH_MS + 5_000);
        request.setHost(host());
        request.setDomain(new RemoteDomainContext("banking"));
        request.setAgent(agent(runtimeIdentity));
        request.setState(state);
        RemoteDomainControlActionTarget target = new RemoteDomainControlActionTarget(
                RemoteDomainControlActionTarget.ScopeEnum.BROADCAST);
        target.setSelector(Map.of(
                "simulationRunId", snapshot.plan().get("simulationRunId")));
        RemoteDomainControlActionPayload payload = new RemoteDomainControlActionPayload(
                BankingClockDrivenRateChangePlan.ACTION_ID,
                operationId,
                target,
                new LinkedHashMap<>(snapshot.asMap()));
        payload.setRequestedBy("banking-policy-wind-tunnel-provider");
        payload.setReason("Install rate-change plan");
        payload.setSimulationTimeEpochMs(NOW_EPOCH_MS);
        request.setPayload(payload);
        return request;
    }

    private static Map<String, Object> rateChangeParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("productCode", "CC001");
        parameters.put("baselineRate", "0.19");
        parameters.put("candidateRate", "0.24");
        parameters.put("prePolicyDays", "10");
        parameters.put("simulationStartEpochMs", NOW_EPOCH_MS);
        parameters.put("baselineLimit", null);
        parameters.put("candidateLimit", null);
        parameters.put("candidateStimulusAmount", "0");
        parameters.put("candidateCrash", "false");
        parameters.put("candidateHardshipScan", "false");
        parameters.put("candidateFraudAlert", "false");
        return parameters;
    }

    private static RemoteDomainHostContext host() {
        return new RemoteDomainHostContext("agent-runtime", "java-spring-banking-test", "0.1.0");
    }

    private static RemoteDomainAgentContext agent(String runtimeIdentity) {
        RemoteDomainAgentContext agent = new RemoteDomainAgentContext("banking-remote-agent", "Avery Saver");
        agent.setConversationId("conversation-banking-remote-1");
        agent.setRuntimeIdentity(runtimeIdentity);
        agent.setAgentType("Customer");
        agent.setInteractionMode("SIMULATION");
        agent.setPersonaDescription("Careful saver with recurring rent obligations.");
        agent.setTraits(List.of("careful", "budget-minded"));
        return agent;
    }

    private static Map<String, Object> personaConfiguration() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("personaName", "Avery Saver");
        configuration.put("personaDescription", "Careful saver with recurring rent obligations.");
        configuration.put("agentType", "Customer");
        configuration.put("interactionMode", "SIMULATION");
        configuration.put("dateOfBirth", "1991-02-03");
        configuration.put("initialAccounts", List.of(Map.of(
                "name", "Main Account",
                "productCode", "TX001",
                "balance", 2500.0,
                "currency", "AUD",
                "debitCard", true)));
        configuration.put("scheduledWork", List.of(Map.of(
                "kind", "transaction",
                "description", "Monthly Rent",
                "frequency", "Monthly",
                "time", "09:30",
                "startDate", "2026-05-01",
                "payload", Map.of(
                        "type", "Payment",
                        "amount", 1500.0,
                        "day", "15",
                        "isAmountExact", true,
                        "details", Map.of(
                                "from", List.of("Main Account"),
                                "to", List.of("Landlord"))))));
        return configuration;
    }

    private static Map<String, Object> creditCardPersonaConfiguration() {
        LinkedHashMap<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("personaName", "Avery Cardholder");
        configuration.put("personaDescription", "Credit-card customer with a revolving balance.");
        configuration.put("agentType", "Customer");
        configuration.put("interactionMode", "SIMULATION");
        configuration.put("dateOfBirth", "1988-06-14");
        configuration.put("initialAccounts", List.of(Map.of(
                "accountId", "acct-rewards-card-1",
                "name", "Rewards Card",
                "productCode", "CC001",
                "balance", -1200.0,
                "limit", 3000.0,
                "currency", "AUD")));
        configuration.put("scheduledWork", List.of());
        return configuration;
    }

    private static List<?> list(Object value) {
        return assertInstanceOf(List.class, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object value) {
        return (Map<Object, Object>) assertInstanceOf(Map.class, value);
    }
}
