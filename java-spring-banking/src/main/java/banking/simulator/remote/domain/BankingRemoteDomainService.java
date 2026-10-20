package banking.simulator.remote.domain;

import banking.simulator.remote.domain.contract.BankingPlannedTransactionContractSupport;
import banking.simulator.remote.domain.contract.BankingRuntimePersonaContractSupport;
import banking.simulator.remote.domain.contract.BankingRuntimeProjectionContractSupport;
import banking.simulator.remote.domain.contract.BankingRuntimeScheduleContractSupport;
import banking.simulator.remote.domain.contract.models.AccountDto;
import banking.simulator.domain.banking.BankingDomainIds;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.BankingRuntimeIdentitySupport;
import banking.simulator.domain.banking.BankingRuntimeProjectionSupport;
import banking.simulator.domain.banking.BankingRuntimeScheduleSupport;
import banking.simulator.domain.banking.BankingSeedAccountPlan;
import banking.simulator.domain.banking.BankingSeedAccountSpec;
import banking.simulator.remote.domain.contract.models.AgentPersona;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionDescriptor;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainControlActionTarget;
import ai.astraform.remote.domain.openapi.models.RemoteDomainDueWorkItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainExecuteWorkResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainInspectionResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainManifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import org.springframework.beans.factory.annotation.Value;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareRequestAllOfPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainPrepareResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainProjection;
import ai.astraform.remote.domain.openapi.models.RemoteDomainScheduleItem;
import ai.astraform.remote.domain.openapi.models.RemoteDomainScheduledWorkKind;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainShutdownResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainStatusResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowResult;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowRequest;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowPayload;
import ai.astraform.remote.domain.openapi.models.RemoteDomainSystemWorkWindowResponse;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiCreatorSection;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiField;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiOption;
import ai.astraform.remote.domain.openapi.models.RemoteDomainUiProfile;
import ai.astraform.remote.domain.sdk.RemoteDomainEnvelopeBuilder;
import ai.astraform.remote.domain.sdk.RemoteDomainManifestBuilder;
import ai.astraform.remote.domain.sdk.PopulationCatalogs;
import ai.astraform.remote.domain.sdk.RemoteDomainProtocolException;
import ai.astraform.remote.domain.sdk.RemoteDomainRequestValidator;
import ai.astraform.remote.domain.sdk.RemoteDomainService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class BankingRemoteDomainService implements RemoteDomainService {
    static final String DOMAIN_ID = BankingDomainIds.BANKING;
    static final String REFERENCE_PROFILE = "remote-domain.native-reference-state.v1";
    static final String NATIVE_PROFILE = "remote-domain.native-state-transform.v1";
    static final String NATIVE_PROVIDER_REVISION = BankingPolicyWindTunnelProviderIdentity.revision();
    private static final String NATIVE_STATEMENT_KIND = "credit_card_statement_close";
    private static final long MAX_NATIVE_TIME = 253402300799998L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final BankingCustomerDecisionTriggerPolicy decisionTriggerPolicy;
    private final boolean nativeSimulationEnabled;
    private final String nativeProfile;
    private final BankingAccountRepository accountRepository;

    public BankingRemoteDomainService(ObjectMapper objectMapper) {
        this(objectMapper, false);
    }

    public BankingRemoteDomainService(ObjectMapper objectMapper, boolean nativeSimulationEnabled) {
        this(objectMapper, nativeSimulationEnabled, NATIVE_PROFILE, (BankingAccountRepository) null);
    }

    @Autowired
    public BankingRemoteDomainService(ObjectMapper objectMapper,
            @Value("${astraform.native-simulation.enabled:false}") boolean nativeSimulationEnabled,
            @Value("${astraform.native-simulation.profile:remote-domain.native-state-transform.v1}") String nativeProfile,
            ObjectProvider<BankingAccountRepository> repository) {
        this(objectMapper, nativeSimulationEnabled, nativeProfile, repository.getIfAvailable());
    }

    BankingRemoteDomainService(ObjectMapper objectMapper, boolean nativeSimulationEnabled,
            String nativeProfile, BankingAccountRepository repository) {
        this.objectMapper = objectMapper;
        this.decisionTriggerPolicy = new BankingCustomerDecisionTriggerPolicy();
        this.nativeSimulationEnabled = nativeSimulationEnabled;
        this.nativeProfile = nativeProfile;
        this.accountRepository = repository;
        if (!Set.of(NATIVE_PROFILE, REFERENCE_PROFILE).contains(nativeProfile)
                || nativeSimulationEnabled && REFERENCE_PROFILE.equals(nativeProfile) && repository == null) {
            throw new IllegalArgumentException("Native reference profile requires the bank backend; unknown profiles are rejected");
        }
    }

    @Override
    public RemoteDomainManifest manifest() {
        RemoteDomainScheduledWorkKind transaction = new RemoteDomainScheduledWorkKind("transaction", "Transaction");
        transaction.setDescription("Banking scheduled transaction work carried over the remote-domain.v1 boundary.");
        RemoteDomainScheduledWorkKind monthlyInterest =
                new RemoteDomainScheduledWorkKind("monthly_interest_accrual", "Monthly interest accrual");
        monthlyInterest.setDescription("Apply simple monthly interest to deposit-style banking balances.");
        RemoteDomainScheduledWorkKind loanInterest =
                new RemoteDomainScheduledWorkKind("loan_interest_accrual", "Loan interest accrual");
        loanInterest.setDescription("Apply monthly loan interest and optional home-loan repayment progression.");
        RemoteDomainScheduledWorkKind creditCardInterest =
                new RemoteDomainScheduledWorkKind("credit_card_interest_accrual", "Credit card interest accrual");
        creditCardInterest.setDescription("Apply monthly credit-card interest and minimum-payment calculation.");
        RemoteDomainScheduledWorkKind creditCardStatement =
                new RemoteDomainScheduledWorkKind("credit_card_statement_close", "Credit card statement close");
        creditCardStatement.setDescription("Close a credit-card statement, generate the bill, and roll the statement window.");
        RemoteDomainScheduledWorkKind creditCardRateChange =
                new RemoteDomainScheduledWorkKind("credit_card_rate_change", "Credit card rate change");
        creditCardRateChange.setDescription(
                "Apply a launch-pinned one-time credit-card rate change at its scheduled simulation time.");

        RemoteDomainManifestBuilder manifest = RemoteDomainManifestBuilder.remoteDomain()
                .domain(DOMAIN_ID, "Banking Domain Provider", "Java banking domain provider implementing remote-domain.v1.",
                        BankingRemoteDomainState.SCHEMA_VERSION)
                .supportedAgentTypes(List.of("Customer", "Policy"))
                .supportedInteractionModes(List.of("SIMULATION", "HYBRID"))
                .remoteProviderLanguage("java")
                .supportsInitialAccounts(true)
                .supportsScheduledWork(true, List.of(transaction))
                .supportsDomainSystemWorkWindow(true, List.of(
                        monthlyInterest,
                        loanInterest,
                        creditCardInterest,
                        creditCardStatement,
                        creditCardRateChange))
                .supportsControlActions(true, controlActionDescriptors())
                .uiProfile(bankingUiProfile())
                .tool(
                        "get_product_list",
                        "Retrieves the banking products available in the remote domain.",
                        Map.of("type", "object", "additionalProperties", false))
                .tool(
                        "post_double_entry_transaction",
                        "Posts a balanced double-entry transaction against the remote banking state.",
                        Map.of(
                                "type", "object",
                                "required", List.of("debits", "credits"),
                                "properties", Map.of(
                                        "debits", Map.of("type", "array", "items", Map.of("type", "object")),
                                        "credits", Map.of("type", "array", "items", Map.of("type", "object")))));
        if (nativeSimulationEnabled) {
            if (REFERENCE_PROFILE.equals(nativeProfile)) {
                manifest.nativeReferenceState(NATIVE_PROVIDER_REVISION, Set.of(NATIVE_STATEMENT_KIND), 1);
            } else {
                manifest.nativeStateTransform(NATIVE_PROVIDER_REVISION, Set.of(NATIVE_STATEMENT_KIND), 1)
                        .readOnlyCustomerTool(
                                "get_customer_accounts",
                                "Read this customer's current simulated accounts, including credit limits, and latest statement. "
                                        + "Uses the accepted customer checkpoint; accepts no customer or account selectors.",
                                Map.of("type", "object", "additionalProperties", false, "properties", Map.of()),
                                nativeCustomerAccountsOutputSchema(),
                                "banking.customer-accounts.v1");
            }
        }
        return manifest.build();
    }

    private static Map<String, Object> nativeCustomerAccountsOutputSchema() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> amount = Map.of("type", "number");
        Map<String, Object> account = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("name", "productCode", "balance", "currency"),
                "properties", Map.of("accountId", text, "name", text, "productCode", text,
                        "balance", amount, "currency", text, "currentMinPayment", amount, "limit", amount));
        Map<String, Object> statement = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("billId", "accountId", "closingAmount", "minimumAmountDue",
                        "currency", "statementStartDate", "statementEndDate", "dueDate"),
                "properties", Map.of("billId", text, "accountId", Map.of("type", List.of("string", "integer")),
                        "closingAmount", amount, "minimumAmountDue", amount, "currency", text,
                        "statementStartDate", text, "statementEndDate", text, "dueDate", text));
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("toolName", "success", "message", "accounts", "statements"),
                "properties", Map.of("toolName", Map.of("type", "string", "const", "get_customer_accounts"),
                        "success", Map.of("type", "boolean", "const", true), "message", text,
                        "accounts", Map.of("type", "array", "maxItems", 1, "items", account),
                        "statements", Map.of("type", "array", "maxItems", 1, "items", statement)));
    }

    private static List<RemoteDomainControlActionDescriptor> controlActionDescriptors() {
        RemoteDomainControlActionDescriptor broadcast = new RemoteDomainControlActionDescriptor(
                "broadcast-message",
                "Broadcast message",
                RemoteDomainControlActionDescriptor.CategoryEnum.BROADCAST,
                Map.of(
                        "type", "object",
                        "required", List.of("message"),
                        "properties", Map.of(
                                "message", Map.of("type", "string"),
                                "severity", Map.of("type", "string"))));
        broadcast.setDescription("Apply a domain-owned operator message to targeted banking customers.");
        broadcast.setSupportedModes(List.of("CLOCK_DRIVEN_HYBRID"));
        broadcast.setTargetScopes(List.of(
                RemoteDomainControlActionDescriptor.TargetScopesEnum.BROADCAST,
                RemoteDomainControlActionDescriptor.TargetScopesEnum.SEGMENT,
                RemoteDomainControlActionDescriptor.TargetScopesEnum.AGENT));
        broadcast.setSideEffectLevel(RemoteDomainControlActionDescriptor.SideEffectLevelEnum.LOW);

        RemoteDomainControlActionDescriptor rateChange = new RemoteDomainControlActionDescriptor(
                BankingClockDrivenRateChangePlan.ACTION_ID,
                "Install rate-change plan",
                RemoteDomainControlActionDescriptor.CategoryEnum.POLICY_CHANGE,
                rateChangeControlActionSchema());
        rateChange.setDescription("Install the launch-pinned clock-driven rate-change plan.");
        rateChange.setSupportedModes(List.of("CLOCK_DRIVEN_HYBRID"));
        rateChange.setTargetScopes(List.of(RemoteDomainControlActionDescriptor.TargetScopesEnum.BROADCAST));
        rateChange.setUiSchema(Map.of("fields", List.of(), "readOnly", true));
        rateChange.setSideEffectLevel(RemoteDomainControlActionDescriptor.SideEffectLevelEnum.HIGH);
        rateChange.setEvidenceRequirements(List.of("updated opaque state", "installed plan digest"));
        rateChange.setLimitations(List.of("This action accepts only the canonical day-10 CC001 plan."));
        return List.of(broadcast, rateChange);
    }

    private static Map<String, Object> rateChangeControlActionSchema() {
        Map<String, Object> planProperties = new LinkedHashMap<>();
        planProperties.put("schemaVersion", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.PLAN_SCHEMA_VERSION));
        planProperties.put("simulationRunId", Map.of("type", "string", "minLength", 1));
        planProperties.put("interventionId", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.INTERVENTION_ID));
        planProperties.put("interventionType", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.INTERVENTION_TYPE));
        planProperties.put("productCode", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.SUPPORTED_PRODUCT_CODE));
        planProperties.put("expectedPreviousRate", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.SUPPORTED_PREVIOUS_RATE));
        planProperties.put("newRate", Map.of(
                "type", "string", "const", BankingClockDrivenRateChangePlan.SUPPORTED_NEW_RATE));
        planProperties.put("scheduledOffsetDays", Map.of(
                "type", "integer", "const", BankingClockDrivenRateChangePlan.SCHEDULED_OFFSET_DAYS));
        for (String field : List.of("simulationStartEpochMs", "scheduledForEpochMs", "effectiveAtEpochMs")) {
            planProperties.put(field, Map.of(
                    "type", "integer", "minimum", 1, "maximum", BankingClockDrivenRateChangePlan.MAX_IJSON_INTEGER));
        }
        planProperties.put("mutationOperationId", Map.of("type", "string", "pattern", "^rate-change:[0-9a-f]{64}$"));
        planProperties.put("workId", Map.of("type", "string", "pattern", "^rate-change-work:[0-9a-f]{32}$"));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("schemaVersion", "planDigest", "plan"),
                "properties", Map.of(
                        "schemaVersion", Map.of(
                                "type", "string",
                                "const", BankingClockDrivenRateChangePlan.SNAPSHOT_SCHEMA_VERSION),
                        "planDigest", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"),
                        "plan", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.copyOf(planProperties.keySet()),
                                "properties", planProperties)));
    }

    @Override
    public Map<String, Object> populationCatalog() {
        return PopulationCatalogs.fromClasspath(
                BankingRemoteDomainService.class,
                "/population-catalogs/banking.population-catalog.json");
    }

    private RemoteDomainUiProfile bankingUiProfile() {
        List<RemoteDomainUiOption> productOptions = BankingRemoteDomainState.defaultProductCatalog().stream()
                .map(product -> new RemoteDomainUiOption(
                        String.valueOf(product.get("productCode")),
                        String.valueOf(product.get("name"))))
                .toList();
        RemoteDomainUiField productCodeField = new RemoteDomainUiField(
                "initialAccountProductCode",
                "initialAccounts.productCode",
                "Initial Account Product Code",
                RemoteDomainUiField.ControlEnum.SELECT)
                .required(true)
                .helpText("Product codes available when seeding initial banking accounts.")
                .options(productOptions);
        RemoteDomainUiCreatorSection accountCatalogSection = new RemoteDomainUiCreatorSection(
                "initialAccountCatalog",
                "Initial account catalog",
                List.of(productCodeField));
        accountCatalogSection.setDescription("Shared account seeding choices published by the banking remote domain.");
        return new RemoteDomainUiProfile(RemoteDomainUiProfile.KindEnum.SHARED_DEFAULT)
                .schemaVersion("domain-ui-profile.v1")
                .title("Banking setup")
                .description("Banking remote-domain account seed metadata.")
                .creatorSections(List.of(accountCatalogSection));
    }

    @Override
    public RemoteDomainPrepareResponse prepare(RemoteDomainPrepareRequest request) {
        RemoteDomainRequestValidator.validatePrepare(request, DOMAIN_ID);
        if (REFERENCE_PROFILE.equals(mapValue(request.getPayload().getInitialProjectionHints() == null ? null
                : request.getPayload().getInitialProjectionHints().get("nativeSimulation")).get("profile"))) {
            return referenceCall(request.getRequestId(), "prepare", () -> prepareReference(request));
        }

        AgentPersona persona = persona(request);
        validateUniqueInitialAccountIds(request, persona.getInitialAccounts());
        long customerId = deterministicCustomerId(request.getAgent().getAgentId());
        Map<String, Object> data = initialStateData(request, persona, customerId);
        BankingRemoteDomainState state = new BankingRemoteDomainState(data);
        String runtimeIdentity = BankingRuntimeIdentitySupport.preparedRuntimeIdentity(state, customerId)
                .orElse(Long.toString(customerId));
        data.put("runtimeIdentity", runtimeIdentity);
        state = new BankingRemoteDomainState(data);

        Map<String, Object> nativeRequest = request.getPayload().getInitialProjectionHints() == null
                ? Map.of() : mapValue(request.getPayload().getInitialProjectionHints().get("nativeSimulation"));
        List<Map<String, Object>> initialEvidence = List.of();
        if (!nativeRequest.isEmpty()) {
            requireNativeSimulationEnabled();
            requireNativeIdentity(nativeRequest, 0L);
            long start = nativeLong(nativeRequest, "logicalTimeEpochMs");
            long horizon = nativeLong(nativeRequest, "logicalHorizonEpochMs");
            requireNativeTimes(start, horizon);
            if (!mapList(data.get("scheduledWork")).isEmpty() || mapList(data.get("accounts")).size() > 1) {
                throw new IllegalArgumentException("Native statement profile supports at most one account and no persona scheduledWork");
            }
            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneOffset.UTC);
            if (time.getDayOfMonth() == 1 && time.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                    && mapList(data.get("accounts")).stream().anyMatch(BankingRemoteDomainService::isIndebtedCreditCardAccount)) {
                state = state.withExecutedWork(List.of(monthlyStatementWork(time)), time, start);
                initialEvidence = state.lastEvidenceEvents();
            }
            Map<String, Object> next = state.data();
            next.put("lastUpdatedEpochMs", start);
            next.put("nativeSimulation", nativeState(nativeRequest, 1L, start, horizon, next));
            state = new BankingRemoteDomainState(next);
            requireNativeStateSize(state);
        }
        long projectionTime = nativeRequest.isEmpty() ? request.getSentAtEpochMs()
                : nativeLong(nativeRequest, "logicalTimeEpochMs");
        var response = RemoteDomainEnvelopeBuilder.prepareResponse(
                request.getRequestId(), runtimeIdentity,
                projection(state, projectionTime, request.getAgent().getAgentId()), state.toOpaqueState());
        if (!nativeRequest.isEmpty()) {
            response.getResult().setEvidenceEvents(initialEvidence);
            response.getResult().setDomainSystemWork(nativeInitialWorkResult(state, initialEvidence, projectionTime));
        }
        return response;
    }

    private RemoteDomainPrepareResponse prepareReference(RemoteDomainPrepareRequest request) {
        requireReferenceProfile();
        Map<String, Object> hints = mapValue(request.getPayload().getInitialProjectionHints().get("nativeSimulation"));
        Map<String, Object> customer = referenceCustomer(hints);
        return accountRepository.transact((String) customer.get("contextRef"), (String) customer.get("subjectRef"),
                request.getIdempotencyKey(), request, request.getDeadlineEpochMs(), RemoteDomainPrepareResponse.class, resource -> {
            requireReferenceFields(hints, Set.of("profile", "contextId", "expectedStateVersion", "logicalTimeEpochMs", "logicalHorizonEpochMs", "customerContext"));
            String contextId = referenceContextId(hints);
            long start = nativeLong(hints, "logicalTimeEpochMs");
            long horizon = nativeLong(hints, "logicalHorizonEpochMs");
            requireNativeTimes(start, horizon);
            if (nativeLong(hints, "expectedStateVersion") != 0 || request.getState() != null
                    || !resource.metadata().isEmpty() || resource.logicalTime() != start) {
                throw new IllegalArgumentException("Reference prepare must attach an unowned prepopulated customer at its inclusive logical time");
            }
            Map<String, Object> configuration = request.getPayload().getPersonaConfiguration() == null
                    ? Map.of() : request.getPayload().getPersonaConfiguration();
            if (!Set.of("decisionTriggerPolicy").containsAll(configuration.keySet())) {
                throw new IllegalArgumentException("Reference preparation accepts notification policy only; banking records must already be populated");
            }
            decisionTriggerPolicy.normalizedConfiguration(configuration.get("decisionTriggerPolicy"));
            var metadata = referenceMetadata(contextId, customer, 1, resource.version(), start, horizon, resource.state());
            var response = RemoteDomainEnvelopeBuilder.prepareResponse(request.getRequestId(), resource.subjectRef(),
                    referenceProjection(metadata, List.of()), referenceCheckpoint(metadata));
            response.getResult().setEvidenceEvents(List.of());
            response.getResult().setDomainSystemWork(new RemoteDomainSystemWorkWindowResult(start, start, 0, 0));
            return new BankingAccountRepository.Transition<>(response, resource.state(), metadata,
                    Map.of("agentId", request.getAgent().getAgentId(), "personaConfiguration", configuration));
        });
    }

    private RemoteDomainSystemWorkWindowResponse executeReferenceWindow(RemoteDomainSystemWorkWindowRequest request) {
        requireReferenceProfile();
        Map<String, Object> hints = mapValue(request.getPayload().getContext().get("nativeSimulation"));
        Map<String, Object> customer = referenceCustomer(hints);
        return accountRepository.transact((String) customer.get("contextRef"), (String) customer.get("subjectRef"),
                request.getIdempotencyKey(), request, request.getDeadlineEpochMs(), RemoteDomainSystemWorkWindowResponse.class, resource -> {
            requireReferenceFields(hints, Set.of("profile", "contextId", "expectedStateVersion", "logicalHorizonEpochMs", "customerContext", "resourceVersion"));
            Map<String, Object> previous = resource.metadata();
            long from = request.getPayload().getTimeWindow().getPreviousTimeEpochMs();
            long through = request.getPayload().getTimeWindow().getCurrentTimeEpochMs();
            if (previous.isEmpty() || !referenceContextId(hints).equals(previous.get("contextId"))
                    || !customer.equals(previous.get("customerContext"))
                    || !Objects.equals(resource.configuration().get("agentId"), request.getAgent().getAgentId())
                    || nativeLong(hints, "expectedStateVersion") != nativeLong(previous, "stateVersion")
                    || nativeLong(hints, "resourceVersion") != resource.version()
                    || nativeLong(hints, "logicalHorizonEpochMs") != nativeLong(previous, "logicalHorizonEpochMs")
                    || request.getState() == null || !BankingRemoteDomainState.SCHEMA_VERSION.equals(request.getState().getSchemaVersion())
                    || !objectMapper.readTree(objectMapper.writeValueAsString(Map.of("nativeSimulation", previous)))
                            .equals(objectMapper.readTree(objectMapper.writeValueAsString(request.getState().getData())))
                    || from != resource.logicalTime() || through <= from
                    || through > nativeLong(previous, "logicalHorizonEpochMs")
                    || through > nativeLong(previous, previous.containsKey("nextDueEpochMs") ? "nextDueEpochMs" : "noWorkBeforeEpochMs")
                    || !List.of(NATIVE_STATEMENT_KIND).equals(request.getPayload().getWorkKinds())
                    || !Integer.valueOf(1).equals(request.getPayload().getMaxWorkItems())
                    || request.getPayload().getExecutionMode() != RemoteDomainSystemWorkWindowPayload.ExecutionModeEnum.EVALUATE_AND_EXECUTE) {
                throw new IllegalArgumentException("Reference window must match its authoritative customer, owner, versions and earliest logical boundary");
            }
            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(through), ZoneOffset.UTC);
            var due = domainSystemDueWork(resource.state(), request, time);
            var next = resource.state().withExecutedWork(due, time, through);
            var events = next.lastEvidenceEvents();
            var workResults = next.lastDomainSystemWorkResults();
            if (workResults.stream().anyMatch(result -> "REJECTED".equals(result.get("outcome")))) {
                throw new IllegalArgumentException("Banking statement update was rejected; transaction will roll back");
            }
            var metadata = referenceMetadata((String) previous.get("contextId"), customer,
                    Math.addExact(nativeLong(previous, "stateVersion"), 1), Math.addExact(resource.version(), 1),
                    through, nativeLong(previous, "logicalHorizonEpochMs"), next);
            var work = new RemoteDomainSystemWorkWindowResult(from, through, due.size(), appliedWorkResultCount(workResults));
            work.setWorkResults(due.stream().map(item -> domainSystemWorkResult(item, false, workResults)).toList());
            work.setStateDeltas(events);
            work.setNextDueHints(List.of(metadata.containsKey("nextDueEpochMs")
                    ? Map.of("nextDueEpochMs", metadata.get("nextDueEpochMs"), "workKinds", List.of(NATIVE_STATEMENT_KIND))
                    : Map.of("noWorkBeforeEpochMs", metadata.get("noWorkBeforeEpochMs"))));
            var configuration = mapValue(resource.configuration().get("personaConfiguration"));
            var triggers = decisionTriggerPolicy.evaluate(events, through, configuration.get("decisionTriggerPolicy"));
            for (var trigger : triggers) {
                var notification = new LinkedHashMap<>(trigger.getPayload());
                notification.keySet().retainAll(Set.of("eventType", "accountId", "billId", "statementStartDate", "statementEndDate"));
                trigger.setPayload(notification);
            }
            work.setDecisionTriggers(triggers);
            var response = RemoteDomainEnvelopeBuilder.domainSystemWorkWindowResponse(request.getRequestId(), resource.subjectRef(),
                    referenceProjection(metadata, events), referenceCheckpoint(metadata), work, events);
            return new BankingAccountRepository.Transition<>(response, next, metadata, resource.configuration());
        });
    }

    private <T> T referenceCall(java.util.UUID requestId, String operation, java.util.function.Supplier<T> call) {
        try { return call.get(); }
        catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException
                | org.springframework.dao.DataIntegrityViolationException rejected) {
            String message = rejected instanceof org.springframework.web.server.ResponseStatusException status
                    ? status.getReason() : rejected instanceof org.springframework.dao.DataIntegrityViolationException
                    ? "Banking context or statement identity conflicts with committed records" : rejected.getMessage();
            throw RemoteDomainProtocolException.domainRejected(requestId, operation,
                    "banking_reference_rejected", message, Map.of());
        }
    }

    private void requireReferenceProfile() {
        requireNativeSimulationEnabled();
        if (!REFERENCE_PROFILE.equals(nativeProfile) || accountRepository == null) {
            throw new IllegalArgumentException("The bank's native reference profile is not enabled");
        }
    }

    private static Map<String, Object> referenceCustomer(Map<String, Object> hints) {
        Map<String, Object> customer = mapValue(hints.get("customerContext"));
        requireReferenceFields(customer, Set.of("contextRef", "subjectRef"));
        BankingAccountRepository.reference(customer.get("contextRef"));
        BankingAccountRepository.reference(customer.get("subjectRef"));
        return customer;
    }

    private static String referenceContextId(Map<String, Object> hints) {
        if (!REFERENCE_PROFILE.equals(hints.get("profile")) || !(hints.get("contextId") instanceof String value)
                || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("Invalid native reference context identity");
        return value;
    }

    private static void requireReferenceFields(Map<String, Object> value, Set<String> fields) {
        if (value == null || !value.keySet().equals(fields)) throw new IllegalArgumentException("Invalid native reference fields: " + fields);
    }

    private static Map<String, Object> referenceMetadata(String contextId, Map<String, Object> customer,
            long stateVersion, long resourceVersion, long through, long horizon, BankingRemoteDomainState state) {
        if (stateVersion > 9_007_199_254_740_991L || resourceVersion > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("Native reference version exceeds the exact JSON integer range");
        }
        var metadata = new LinkedHashMap<>(nativeState(Map.of("contextId", contextId), stateVersion, through, horizon, state.data()));
        metadata.put("profile", REFERENCE_PROFILE);
        metadata.put("customerContext", customer);
        metadata.put("resourceVersion", resourceVersion);
        return metadata;
    }

    private static RemoteDomainOpaqueState referenceCheckpoint(Map<String, Object> metadata) {
        return new RemoteDomainOpaqueState(BankingRemoteDomainState.SCHEMA_VERSION, Map.of("nativeSimulation", metadata));
    }

    private static RemoteDomainProjection referenceProjection(Map<String, Object> metadata, List<Map<String, Object>> events) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("domainId", DOMAIN_ID);
        observation.put("observedAtEpochMs", metadata.get("processedThroughEpochMs"));
        observation.put("observedAtUtc", Instant.ofEpochMilli(nativeLong(metadata, "processedThroughEpochMs")).toString());
        if (metadata.containsKey("nextDueEpochMs")) {
            observation.put("nextStatementAtEpochMs", metadata.get("nextDueEpochMs"));
            observation.put("nextStatementAtUtc", Instant.ofEpochMilli(nativeLong(metadata, "nextDueEpochMs")).toString());
        }
        observation.put("notifications", events.stream().map(event -> {
            Map<String, Object> notification = new LinkedHashMap<>();
            for (String key : List.of("eventType", "accountId", "billId", "statementStartDate", "statementEndDate")) {
                if (event.containsKey(key)) notification.put(key, event.get(key));
            }
            return notification;
        }).toList());
        var result = new LinkedHashMap<>(metadata);
        result.put("observation", observation);
        return RemoteDomainEnvelopeBuilder.projection(Map.of("nativeSimulation", result), List.of(), List.of(), Map.of(), Map.of());
    }

    private static void validateUniqueInitialAccountIds(
            RemoteDomainPrepareRequest request,
            List<Object> rawAccounts) {
        Set<String> seen = new java.util.HashSet<>();
        if (rawAccounts == null) {
            return;
        }
        for (int index = 0; index < rawAccounts.size(); index++) {
            String accountId = externalAccountId(rawAccounts, index);
            if (hasText(accountId) && !seen.add(accountId.trim())) {
                throw RemoteDomainProtocolException.invalidRequest(
                        request.getRequestId(),
                        "prepare",
                        "duplicate_initial_account_id",
                        "Banking initialAccounts must not contain duplicate accountId values.",
                        Map.of("accountId", accountId.trim()));
            }
        }
    }

    @Override
    public RemoteDomainExecuteWorkResponse executeWork(RemoteDomainExecuteWorkRequest request) {
        RemoteDomainRequestValidator.validateExecuteWork(request, DOMAIN_ID);

        ZonedDateTime currentTime = currentTime(request);
        BankingRemoteDomainState state = BankingRemoteDomainState.from(request.getState());
        Map<String, Object> nativeState = mapValue(state.data().get("nativeSimulation"));
        if (!nativeState.isEmpty() || dueWork(request).stream().anyMatch(work ->
                "get_customer_accounts".equals(mapValue(work.get("payload")).get("toolName")))) {
            requireNativeSimulationEnabled();
            validateNativeReadOnlyTool(request, nativeState);
        }
        state = state.withExecutedWork(dueWork(request), currentTime, request.getSentAtEpochMs());
        return RemoteDomainEnvelopeBuilder.executeWorkResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                projection(state, currentTime.toInstant().toEpochMilli(), request.getAgent().getAgentId()),
                state.toOpaqueState(),
                state.lastToolResult(),
                state.lastEvidenceEvents());
    }

    @Override
    public RemoteDomainSystemWorkWindowResponse executeDomainSystemWorkWindow(
            RemoteDomainSystemWorkWindowRequest request) {
        RemoteDomainRequestValidator.validateDomainSystemWorkWindow(request, DOMAIN_ID);
        if (REFERENCE_PROFILE.equals(mapValue(request.getPayload().getContext() == null ? null
                : request.getPayload().getContext().get("nativeSimulation")).get("profile"))) {
            return referenceCall(request.getRequestId(), "domain-system-work-window", () -> executeReferenceWindow(request));
        }

        ZonedDateTime currentTime = currentTime(request);
        long previousEpochMs = request.getPayload().getTimeWindow().getPreviousTimeEpochMs();
        long currentEpochMs = request.getPayload().getTimeWindow().getCurrentTimeEpochMs();
        BankingRemoteDomainState state = BankingRemoteDomainState.from(request.getState());
        Map<String, Object> nativeState = mapValue(state.data().get("nativeSimulation"));
        Map<String, Object> nativeRequest = request.getPayload().getContext() == null
                ? Map.of() : mapValue(request.getPayload().getContext().get("nativeSimulation"));
        if (!nativeState.isEmpty() || !nativeRequest.isEmpty()) {
            requireNativeSimulationEnabled();
            validateNativeWindow(request, nativeState, nativeRequest);
        }
        List<Map<String, Object>> dueWork = domainSystemDueWork(state, request, currentTime);
        boolean evaluateOnly = request.getPayload().getExecutionMode() != null
                && "EVALUATE_ONLY".equals(request.getPayload().getExecutionMode().getValue());
        BankingRemoteDomainState nextState = evaluateOnly
                ? state
                : state.withExecutedWork(dueWork, currentTime, request.getSentAtEpochMs());
        List<Map<String, Object>> evidenceEvents = evaluateOnly ? List.of() : nextState.lastEvidenceEvents();
        List<Map<String, Object>> workResultRecords = evaluateOnly ? List.of() : nextState.lastDomainSystemWorkResults();
        if (!nativeState.isEmpty()) {
            Map<String, Object> next = nextState.data();
            next.put("lastUpdatedEpochMs", currentEpochMs);
            next.put("nativeSimulation", nativeState(nativeRequest,
                    Math.addExact(nativeLong(nativeState, "stateVersion"), 1L), currentEpochMs,
                    nativeLong(nativeState, "logicalHorizonEpochMs"), next));
            nextState = new BankingRemoteDomainState(next);
            requireNativeStateSize(nextState);
        }

        RemoteDomainSystemWorkWindowResult domainSystemWork = new RemoteDomainSystemWorkWindowResult(
                previousEpochMs,
                currentEpochMs,
                dueWork.size(),
                evaluateOnly ? 0 : appliedWorkResultCount(workResultRecords));
        domainSystemWork.setWorkResults(dueWork.stream()
                .map(item -> domainSystemWorkResult(item, evaluateOnly, workResultRecords))
                .toList());
        domainSystemWork.setStateDeltas(evidenceEvents);
        domainSystemWork.setCheckpointRefs(List.of(Map.of(
                "schemaVersion", BankingRemoteDomainState.SCHEMA_VERSION,
                "stateScope", "agent",
                "runtimeIdentity", runtimeIdentity(request.getAgent().getRuntimeIdentity(), nextState))));
        domainSystemWork.setNextDueHints(domainSystemWorkNextDueHints(
                currentTime,
                dueWork,
                nextState.data(),
                request.getPayload().getWorkKinds()));
        domainSystemWork.setDecisionTriggers(evaluateOnly
                ? List.of()
                : decisionTriggerPolicy.evaluate(
                        evidenceEvents,
                        currentEpochMs,
                        nextState.data().get("decisionTriggerPolicy")));

        return RemoteDomainEnvelopeBuilder.domainSystemWorkWindowResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent().getRuntimeIdentity(), nextState),
                projection(nextState, currentEpochMs, request.getAgent().getAgentId()),
                nextState.toOpaqueState(),
                domainSystemWork,
                evidenceEvents);
    }

    @Override
    public RemoteDomainControlActionResponse executeControlAction(RemoteDomainControlActionRequest request) {
        RemoteDomainRequestValidator.validateControlAction(request, DOMAIN_ID);
        String actionId = request.getPayload().getActionId();
        String operationId = request.getPayload().getOperationId().trim();
        if (!"broadcast-message".equals(actionId)
                && !BankingClockDrivenRateChangePlan.ACTION_ID.equals(actionId)) {
            throw RemoteDomainProtocolException.domainRejected(
                    request.getRequestId(),
                    "control-action",
                    "unsupported_control_action",
                    "Unsupported banking control action '" + actionId + "'.",
                    Map.of("actionId", actionId));
        }
        Map<String, Object> inputs = request.getPayload().getInputs() != null
                ? new LinkedHashMap<>(request.getPayload().getInputs())
                : new LinkedHashMap<>();
        Map<String, Object> target = controlActionTargetMap(request.getPayload().getTarget());
        String targetScope = firstText(target.get("scope"), "AGENT");
        BankingRemoteDomainState state = BankingRemoteDomainState.from(request.getState());
        var existingAction = state.controlActionByOperationId(operationId);
        if (existingAction.isPresent()) {
            if (!BankingRemoteDomainState.sameControlAction(existingAction.get(), actionId, target, inputs)) {
                throw RemoteDomainProtocolException.invalidRequest(
                        request.getRequestId(),
                        "control-action",
                        "control_action_idempotency_conflict",
                        "control-action operationId was already applied with different action, target, or inputs.",
                        Map.of("operationId", operationId));
            }
            return RemoteDomainEnvelopeBuilder.controlActionResponse(
                    request.getRequestId(),
                    runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                    projection(state, request.getSentAtEpochMs(), request.getAgent().getAgentId()),
                    state.toOpaqueState(),
                    existingAction.get(),
                    BankingRemoteDomainState.evidenceEventsForControlAction(existingAction.get()));
        }
        if (BankingClockDrivenRateChangePlan.ACTION_ID.equals(actionId)) {
            BankingClockDrivenRateChangePlan.Snapshot snapshot =
                    BankingClockDrivenRateChangePlan.requireSnapshot(inputs);
            String expectedOperationId = BankingClockDrivenRateChangePlan.installOperationId(snapshot);
            Map<String, Object> selector = mapValue(target.get("selector"));
            if (!expectedOperationId.equals(operationId)
                    || !"BROADCAST".equalsIgnoreCase(targetScope)
                    || !firstText(snapshot.plan().get("simulationRunId"), "")
                            .equals(firstText(selector.get("simulationRunId"), ""))) {
                throw RemoteDomainProtocolException.invalidRequest(
                        request.getRequestId(),
                        "control-action",
                        "invalid_rate_change_plan_installation",
                        "Rate-change plan installation requires its digest-derived operationId and BROADCAST scope.",
                        Map.of("operationId", operationId, "targetScope", targetScope));
            }
            state = state.withClockDrivenRateChangePlan(snapshot, request.getSentAtEpochMs())
                    .withRateChangePlanControlAction(
                            operationId,
                            request.getIdempotencyKey(),
                            actionId,
                            target,
                            inputs,
                            snapshot,
                            request.getSentAtEpochMs());
            return RemoteDomainEnvelopeBuilder.controlActionResponse(
                    request.getRequestId(),
                    runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                    projection(state, request.getSentAtEpochMs(), request.getAgent().getAgentId()),
                    state.toOpaqueState(),
                    state.lastControlActionResult(),
                    state.lastEvidenceEvents());
        }
        String message = firstText(inputs.get("message"), null);
        if (!hasText(message)) {
            throw RemoteDomainProtocolException.domainRejected(
                    request.getRequestId(),
                    "control-action",
                    "invalid_control_action_input",
                    "broadcast-message requires a non-empty message input.",
                    Map.of("actionId", actionId, "missingInput", "message"));
        }
        state = state.withControlAction(
                operationId,
                request.getIdempotencyKey(),
                actionId,
                target,
                inputs,
                request.getSentAtEpochMs());
        return RemoteDomainEnvelopeBuilder.controlActionResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                projection(state, request.getSentAtEpochMs(), request.getAgent().getAgentId()),
                state.toOpaqueState(),
                state.lastControlActionResult(),
                state.lastEvidenceEvents());
    }

    @Override
    public RemoteDomainStatusResponse status(RemoteDomainStatusRequest request) {
        RemoteDomainRequestValidator.validateStatus(request, DOMAIN_ID);

        BankingRemoteDomainState state = BankingRemoteDomainState.from(request.getState());
        return RemoteDomainEnvelopeBuilder.statusResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                projection(state, request.getSentAtEpochMs(), request.getAgent().getAgentId()));
    }

    @Override
    public RemoteDomainInspectionResponse inspection(RemoteDomainInspectionRequest request) {
        RemoteDomainRequestValidator.validateInspection(request, DOMAIN_ID);

        BankingRemoteDomainState state = BankingRemoteDomainState.from(request.getState());
        return RemoteDomainEnvelopeBuilder.inspectionResponse(
                request.getRequestId(),
                runtimeIdentity(request.getAgent().getRuntimeIdentity(), state),
                projection(state, request.getSentAtEpochMs(), request.getAgent().getAgentId()));
    }

    @Override
    public RemoteDomainShutdownResponse shutdown(RemoteDomainShutdownRequest request) {
        RemoteDomainRequestValidator.validateShutdown(request, DOMAIN_ID);

        return RemoteDomainEnvelopeBuilder.shutdownResponse(request.getRequestId(), true);
    }

    private Map<String, Object> initialStateData(
            RemoteDomainPrepareRequest request,
            AgentPersona persona,
            long customerId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domainId", DOMAIN_ID);
        data.put("schemaVersion", BankingRemoteDomainState.SCHEMA_VERSION);
        data.put("agentId", request.getAgent().getAgentId());
        data.put("conversationId", request.getAgent().getConversationId());
        data.put("personaName", persona.getPersonaName());
        data.put("agentType", persona.getAgentType() != null ? persona.getAgentType().getValue() : "Customer");
        data.put("interactionMode", persona.getInteractionMode() != null
                ? persona.getInteractionMode().getValue()
                : "SIMULATION");
        data.put("bankingCustomerId", customerId);
        data.put("customerProfile", convert(BankingRuntimePersonaContractSupport.customerProfile(persona), MAP_TYPE));
        data.put("accounts", accountState(
                BankingRuntimePersonaContractSupport.initialAccountPlans(persona),
                persona.getInitialAccounts(),
                customerId));
        data.put("scheduledWork", scheduledWorkState(persona));
        Object rawDecisionTriggerPolicy = request.getPayload() != null
                        && request.getPayload().getPersonaConfiguration() != null
                ? request.getPayload().getPersonaConfiguration().get("decisionTriggerPolicy")
                : null;
        try {
            data.put(
                    "decisionTriggerPolicy",
                    decisionTriggerPolicy.normalizedConfiguration(rawDecisionTriggerPolicy));
        } catch (IllegalArgumentException invalidPolicy) {
            throw RemoteDomainProtocolException.invalidRequest(
                    request.getRequestId(),
                    "prepare",
                    "invalid_decision_trigger_policy",
                    invalidPolicy.getMessage(),
                    Map.of("field", "payload.personaConfiguration.decisionTriggerPolicy"));
        }
        data.put("executionLog", List.of());
        data.put("executedWorkCount", 0);
        data.put("missedPaymentsLast3m", 0);
        data.put("hardshipFlag", false);
        data.put("daysSinceLastOutreach", -1);
        data.put("lastAction", "prepare");
        data.put("lastUpdatedEpochMs", request.getSentAtEpochMs());
        return data;
    }

    private AgentPersona persona(RemoteDomainPrepareRequest request) {
        RemoteDomainPrepareRequestAllOfPayload payload = request.getPayload();
        Map<String, Object> configuration = payload != null && payload.getPersonaConfiguration() != null
                ? payload.getPersonaConfiguration()
                : Map.of();
        try {
            Map<String, Object> personaConfiguration = new LinkedHashMap<>(configuration);
            personaConfiguration.remove("decisionTriggerPolicy");
            AgentPersona persona = objectMapper.convertValue(personaConfiguration, AgentPersona.class);
            persona.setDomainId(DOMAIN_ID);
            if (!hasText(persona.getPersonaName())) {
                persona.setPersonaName(request.getAgent().getPersonaName());
            }
            if (!hasText(persona.getPersonaDescription())) {
                persona.setPersonaDescription(hasText(request.getAgent().getPersonaDescription())
                        ? request.getAgent().getPersonaDescription()
                        : "Banking remote-domain proof customer.");
            }
            if (persona.getDateOfBirth() == null) {
                persona.setDateOfBirth(LocalDate.of(1990, 1, 1));
            }
            if (persona.getAgentType() == null && hasText(request.getAgent().getAgentType())) {
                persona.setAgentType(AgentPersona.AgentTypeEnum.fromValue(request.getAgent().getAgentType()));
            }
            if (persona.getInteractionMode() == null && hasText(request.getAgent().getInteractionMode())) {
                persona.setInteractionMode(AgentPersona.InteractionModeEnum.fromValue(request.getAgent().getInteractionMode()));
            }
            return persona;
        } catch (IllegalArgumentException ex) {
            throw RemoteDomainProtocolException.domainRejected(
                    request.getRequestId(),
                    "prepare",
                    "invalid_banking_persona",
                    ex.getMessage(),
                    Map.of());
        }
    }

    private RemoteDomainProjection projection(BankingRemoteDomainState state, long epochMs, String agentId) {
        List<RemoteDomainScheduleItem> lifecycleSchedule = lifecycleSchedule(state);
        List<RemoteDomainScheduleItem> inspectionSchedule = inspectionSchedule(state);

        Map<String, Object> runtimeMetadata = new LinkedHashMap<>(BankingRuntimeProjectionSupport.runtimeMetadata(state));
        runtimeMetadata.put("domainId", DOMAIN_ID);
        runtimeMetadata.put("schemaVersion", BankingRemoteDomainState.SCHEMA_VERSION);
        runtimeMetadata.put("transitionalRemoteProof", true);
        runtimeMetadata.put("knownContextFacts", BankingRuntimeProjectionSupport.a2aKnownContextFacts(state));

        Map<String, Object> statusView = new LinkedHashMap<>();
        statusView.put("customerState", convert(BankingRuntimeProjectionContractSupport.statusState(
                state,
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC),
                agentId), MAP_TYPE));
        statusView.put("accounts", convert(BankingRuntimeProjectionContractSupport.statusAccounts(state), MAP_LIST_TYPE));
        statusView.put("balances", BankingRuntimeProjectionSupport.eventBalancesSnapshot(state));
        statusView.put("scheduledTransactions", convert(
                BankingRuntimeScheduleContractSupport.statusSchedule(state),
                MAP_LIST_TYPE));
        statusView.put("executedWorkCount", state.data().get("executedWorkCount"));
        statusView.put("recentTransactions", state.recentTransactionPostings(100));
        statusView.put("creditCardBills", state.creditCardBills(100));
        statusView.put("lastControlAction", state.lastControlActionResult());

        Map<String, Object> inspectionView = new LinkedHashMap<>();
        inspectionView.put("runtimeIdentity", state.runtimeIdentity());
        inspectionView.put("bankingCustomerId", state.bankingCustomerId());
        inspectionView.put("customerProfile", state.data().get("customerProfile"));
        inspectionView.put("scheduledWork", convert(BankingRuntimeScheduleContractSupport.inspectionSchedule(
                BankingRuntimeScheduleSupport.inspectionSchedule(state)), MAP_LIST_TYPE));
        inspectionView.put("stateKeys", new ArrayList<>(state.data().keySet()));
        inspectionView.put("lastAction", state.data().get("lastAction"));
        inspectionView.put("creditCardBills", state.creditCardBills(100));
        inspectionView.put("controlActions", state.data().getOrDefault("controlActions", List.of()));

        Map<String, Object> nativeState = mapValue(state.data().get("nativeSimulation"));
        if (!nativeState.isEmpty()) {
            Map<String, Object> nativeResult = new LinkedHashMap<>(nativeState);
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("domainId", DOMAIN_ID);
            observation.put("observedAtEpochMs", epochMs);
            observation.put("observedAtUtc", Instant.ofEpochMilli(epochMs).toString());
            observation.put("accounts", state.customerAccounts(false));
            observation.put("statements", state.creditCardBills(1));
            nativeResult.put("observation", observation);
            runtimeMetadata.put("nativeSimulation", nativeResult);
        }
        return RemoteDomainEnvelopeBuilder.projection(
                runtimeMetadata,
                lifecycleSchedule,
                inspectionSchedule,
                statusView,
                inspectionView);
    }

    private List<RemoteDomainScheduleItem> lifecycleSchedule(BankingRemoteDomainState state) {
        return BankingRuntimeScheduleSupport.lifecycleSchedule(state).stream()
                .filter(Objects::nonNull)
                .map(entry -> scheduleItem(entry.description(), entry.type(), entry.frequency(), null, entry.amount()))
                .toList();
    }

    private List<RemoteDomainScheduleItem> inspectionSchedule(BankingRemoteDomainState state) {
        return BankingRuntimeScheduleSupport.inspectionSchedule(state).stream()
                .filter(Objects::nonNull)
                .map(entry -> scheduleItem(entry.description(), entry.type(), null, entry.nextExecution(), entry.amount()))
                .toList();
    }

    private static RemoteDomainScheduleItem scheduleItem(
            String description,
            String type,
            String frequency,
            String nextExecution,
            Double amount) {
        RemoteDomainScheduleItem item = new RemoteDomainScheduleItem();
        item.setDescription(description);
        item.setType(type);
        item.setFrequency(frequency);
        item.setNextExecution(parseOffsetDateTime(nextExecution));
        item.setAmount(amount == null ? null : BigDecimal.valueOf(amount));
        return item;
    }

    private static OffsetDateTime parseOffsetDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> accountState(
            List<BankingSeedAccountPlan> plans,
            List<Object> rawAccounts,
            long customerId) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        long nextId = customerId * 1_000L;
        for (int index = 0; index < plans.size(); index++) {
            BankingSeedAccountPlan plan = plans.get(index);
            if (plan == null || plan.seedAccount() == null) {
                continue;
            }
            BankingSeedAccountSpec seed = plan.seedAccount();
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("id", nextId++);
            putIfPresent(account, "accountId", externalAccountId(rawAccounts, index));
            account.put("name", seed.name());
            account.put("productCode", seed.productCode());
            String productCategory = productCategoryForCode(seed.productCode());
            account.put("productCategory", productCategory);
            account.put("category", productCategory);
            account.put("balance", seed.balance() != null ? seed.balance() : 0.0);
            account.put("limit", seed.limit());
            account.put("bsb", seed.bsb() != null ? seed.bsb() : 633000);
            account.put("currency", hasText(seed.currency()) ? seed.currency() : "AUD");
            account.put("debitCardRequested", plan.debitCardRequested());
            accounts.add(account);
        }
        return accounts;
    }

    private static String externalAccountId(List<Object> rawAccounts, int index) {
        if (rawAccounts == null || index < 0 || index >= rawAccounts.size()) {
            return null;
        }
        Object rawAccount = rawAccounts.get(index);
        if (rawAccount instanceof Map<?, ?> map) {
            return firstText(map.get("accountId"), map.get("id"));
        }
        if (rawAccount instanceof AccountDto dto) {
            Long id = dto.getId();
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    private static String productCategoryForCode(String productCode) {
        if (!hasText(productCode)) {
            return "REMOTE_ACCOUNT_PRODUCT";
        }
        for (Map<String, Object> product : BankingRemoteDomainState.defaultProductCatalog()) {
            if (productCode.trim().equalsIgnoreCase(firstText(product.get("productCode"), ""))) {
                return firstText(product.get("productCategory"), "REMOTE_ACCOUNT_PRODUCT");
            }
        }
        return "REMOTE_ACCOUNT_PRODUCT";
    }

    private static List<Map<String, Object>> scheduledWorkState(AgentPersona persona) {
        List<Map<String, Object>> scheduledWork = new ArrayList<>();
        List<Map<String, Object>> rawScheduledWork = persona.getScheduledWork();
        if (rawScheduledWork != null) {
            for (int index = 0; index < rawScheduledWork.size(); index++) {
                Map<String, Object> normalized = scheduledWorkState(rawScheduledWork.get(index), index);
                if (!normalized.isEmpty()) {
                    scheduledWork.add(normalized);
                }
            }
        }
        if (!scheduledWork.isEmpty()) {
            return scheduledWork;
        }
        return scheduledWorkState(BankingRuntimePersonaContractSupport.transactionScheduledWorkContracts(persona));
    }

    private static Map<String, Object> scheduledWorkState(Map<String, Object> raw, int index) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = flattenedPayload(raw.get("payload"));
        Map<String, Object> scheduledWork = new LinkedHashMap<>();
        putIfPresent(scheduledWork, "workId", firstText(raw.get("workId"), "banking-remote-work-" + (index + 1)));
        putIfPresent(scheduledWork, "kind", firstText(raw.get("kind"), firstText(payload.get("kind"), "transaction")));
        putIfPresent(scheduledWork, "description", firstText(raw.get("description"),
                firstText(payload.get("description"), "Banking scheduled work")));
        for (String field : List.of("frequency", "time", "startDate", "endDate")) {
            putIfPresent(scheduledWork, field, firstPresent(raw.get(field), payload.get(field)));
        }
        Map<String, Object> contractPayload = new LinkedHashMap<>(payload);
        for (String field : List.of("kind", "description", "frequency", "time", "startDate", "endDate")) {
            contractPayload.remove(field);
        }
        if (!contractPayload.isEmpty()) {
            scheduledWork.put("payload", contractPayload);
        }
        return scheduledWork;
    }

    private static Map<String, Object> flattenedPayload(Object rawPayload) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattenPayload(rawPayload, flattened);
        return flattened;
    }

    private static void flattenPayload(Object rawPayload, Map<String, Object> flattened) {
        if (!(rawPayload instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            if (key != null && !"payload".equals(String.valueOf(key)) && value != null) {
                flattened.putIfAbsent(String.valueOf(key), value);
            }
        });
        flattenPayload(map.get("payload"), flattened);
    }

    private static List<Map<String, Object>> scheduledWorkState(List<BankingPlannedTransactionContract> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return List.of();
        }
        return contracts.stream()
                .filter(Objects::nonNull)
                .map(BankingPlannedTransactionContractSupport::toScheduledWork)
                .filter(work -> !work.isEmpty())
                .toList();
    }

    private static List<Map<String, Object>> dueWork(RemoteDomainExecuteWorkRequest request) {
        if (request.getPayload() == null
                || request.getPayload().getTurnContext() == null
                || request.getPayload().getTurnContext().getDueWork() == null) {
            return List.of();
        }
        return request.getPayload().getTurnContext().getDueWork().stream()
                .filter(Objects::nonNull)
                .map(BankingRemoteDomainService::dueWorkItem)
                .toList();
    }

    private static Map<String, Object> dueWorkItem(RemoteDomainDueWorkItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workId", item.getWorkId());
        value.put("workType", item.getWorkType());
        value.put("occurrenceKey", item.getOccurrenceKey());
        value.put("scheduledForEpochMs", item.getScheduledForEpochMs());
        value.put("attemptNumber", item.getAttemptNumber());
        value.put("description", item.getDescription());
        value.put("payload", item.getPayload() == null ? Map.of() : new LinkedHashMap<>(item.getPayload()));
        return value;
    }

    private static List<Map<String, Object>> domainSystemDueWork(
            BankingRemoteDomainState state,
            RemoteDomainSystemWorkWindowRequest request,
            ZonedDateTime currentTime) {
        Map<String, Object> data = state.data();
        List<Map<String, Object>> scheduledWork = new ArrayList<>(mapList(data.get("scheduledWork")));
        Set<String> requestedKinds = request.getPayload().getWorkKinds() == null
                ? Set.of()
                : request.getPayload().getWorkKinds().stream()
                        .filter(Objects::nonNull)
                        .map(value -> String.valueOf(value).trim().toLowerCase(Locale.ROOT))
                .filter(BankingRemoteDomainService::hasText)
                .collect(java.util.stream.Collectors.toSet());
        int maxWorkItems = Math.max(1, request.getPayload().getMaxWorkItems() == null
                ? 100
                : request.getPayload().getMaxWorkItems());
        List<Map<String, Object>> dueWork = new ArrayList<>();
        for (Map<String, Object> work : scheduledWork) {
            String kind = firstText(work.get("kind"), mapValue(work.get("payload")).get("kind"));
            if (!isDomainSystemWorkKind(kind)) {
                continue;
            }
            String normalizedKind = kind.trim().toLowerCase(Locale.ROOT);
            if (!requestedKinds.isEmpty() && !requestedKinds.contains(normalizedKind)) {
                continue;
            }
            Map<String, Object> workItem = "credit_card_rate_change".equals(normalizedKind)
                    ? rateChangeDueWorkItem(
                            work,
                            request.getPayload().getTimeWindow().getCurrentTimeEpochMs())
                    : domainSystemDueWorkItem(work, normalizedKind, currentTime);
            if (workItem.isEmpty()) {
                continue;
            }
            if (hasCompletedDomainSystemWork(data, workItem)) {
                continue;
            }
            dueWork.add(workItem);
        }
        List<Map<String, Object>> defaultStatements = defaultCreditCardStatementDueWork(
                data,
                scheduledWork,
                requestedKinds,
                request.getPayload().getTimeWindow().getPreviousTimeEpochMs(),
                request.getPayload().getTimeWindow().getCurrentTimeEpochMs());
        if (dueWork.size() + defaultStatements.size() > maxWorkItems) {
            throw RemoteDomainProtocolException.domainRejected(
                    request.getRequestId(),
                    "domain-system-work-window",
                    "domain_system_work_window_capacity_exceeded",
                    "The banking domain-system work window cannot publish every completed monthly statement "
                            + "inside maxWorkItems without losing evidence.",
                    Map.of(
                            "maxWorkItems", maxWorkItems,
                            "requiredWorkItems", dueWork.size() + defaultStatements.size()));
        }
        dueWork.addAll(defaultStatements);
        dueWork.sort(Comparator
                .comparingLong(BankingRemoteDomainService::scheduledForEpochMs)
                .thenComparingInt(BankingRemoteDomainService::domainSystemWorkPriority));
        return dueWork;
    }

    private static long scheduledForEpochMs(Map<String, Object> work) {
        Object raw = work == null ? null : work.get("scheduledForEpochMs");
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Domain-system work scheduledForEpochMs must be numeric");
        }
        return number.longValue();
    }

    private static int domainSystemWorkPriority(Map<String, Object> work) {
        String kind = firstText(work.get("kind"), mapValue(work.get("payload")).get("kind"));
        return BankingClockDrivenRateChangePlan.WORK_KIND.equalsIgnoreCase(kind) ? 0 : 1;
    }

    private static List<Map<String, Object>> defaultCreditCardStatementDueWork(
            Map<String, Object> data,
            List<Map<String, Object>> scheduledWork,
            Set<String> requestedKinds,
            long previousTimeEpochMs,
            long currentTimeEpochMs) {
        if ((!requestedKinds.isEmpty() && !requestedKinds.contains("credit_card_statement_close"))
                || scheduledWork.stream().anyMatch(BankingRemoteDomainService::isCreditCardStatementWork)
                || previousTimeEpochMs <= 0
                || currentTimeEpochMs <= previousTimeEpochMs
                || mapList(data.get("accounts")).stream()
                        .noneMatch(BankingRemoteDomainService::isIndebtedCreditCardAccount)) {
            return List.of();
        }
        ZonedDateTime currentTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(currentTimeEpochMs), ZoneOffset.UTC);
        ZonedDateTime boundary = ZonedDateTime.ofInstant(Instant.ofEpochMilli(previousTimeEpochMs), ZoneOffset.UTC)
                .toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC);
        List<Map<String, Object>> dueWork = new ArrayList<>();
        while (!boundary.isAfter(currentTime)) {
            Map<String, Object> workItem = monthlyStatementWork(boundary);
            if (!hasCompletedDomainSystemWork(data, workItem)) {
                dueWork.add(workItem);
            }
            boundary = boundary.plusMonths(1);
        }
        return dueWork;
    }

    private static Map<String, Object> monthlyStatementWork(ZonedDateTime boundary) {
        LocalDate statementStart = boundary.toLocalDate().minusMonths(1).withDayOfMonth(1);
        LocalDate statementEnd = boundary.toLocalDate().minusDays(1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", NATIVE_STATEMENT_KIND);
        payload.put("statementStartDate", statementStart.toString());
        payload.put("statementEndDate", statementEnd.toString());
        payload.put("dueDays", 14);
        payload.put("minPaymentPercentage", 0.04);
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("workId", "auto-credit-card-statements");
        work.put("kind", NATIVE_STATEMENT_KIND);
        work.put("description", "Monthly credit card statement close");
        work.put("frequency", "MONTHLY");
        work.put("payload", payload);
        return domainSystemDueWorkItem(work, NATIVE_STATEMENT_KIND, boundary);
    }

    private void requireNativeSimulationEnabled() {
        if (!nativeSimulationEnabled) {
            throw new IllegalArgumentException("Native simulation profile requires astraform.native-simulation.enabled=true");
        }
    }

    private static void requireNativeIdentity(Map<String, Object> metadata, long expectedVersion) {
        if (!NATIVE_PROFILE.equals(metadata.get("profile"))
                || !(metadata.get("contextId") instanceof String contextId) || contextId.isBlank()
                || contextId.length() > 256 || nativeLong(metadata, "expectedStateVersion") != expectedVersion) {
            throw new IllegalArgumentException("Native profile context identity or expected state version is invalid");
        }
    }

    private static long nativeLong(Map<String, Object> metadata, String field) {
        Object value = metadata.get(field);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Native " + field + " must be an integer");
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Native " + field + " must be an exact 64-bit integer", invalid);
        }
    }

    private static void requireNativeTimes(long start, long horizon) {
        if (start < 0 || horizon < start || horizon > MAX_NATIVE_TIME) {
            throw new IllegalArgumentException("Native logical time must be within epoch zero and year 9999, with horizon at or after start");
        }
    }

    private static Map<String, Object> nativeState(Map<String, Object> request, long version,
            long processedThrough, long horizon, Map<String, Object> state) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", NATIVE_PROFILE);
        metadata.put("contextId", request.get("contextId"));
        metadata.put("stateVersion", version);
        metadata.put("processedThroughEpochMs", processedThrough);
        metadata.put("logicalHorizonEpochMs", horizon);
        boolean futureStatements = mapList(state.get("accounts")).stream()
                .anyMatch(BankingRemoteDomainService::isIndebtedCreditCardAccount);
        long nextDue = ZonedDateTime.ofInstant(Instant.ofEpochMilli(processedThrough), ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        if (futureStatements && nextDue <= horizon) metadata.put("nextDueEpochMs", nextDue);
        else metadata.put("noWorkBeforeEpochMs", Math.addExact(horizon, 1L));
        return metadata;
    }

    private void requireNativeStateSize(BankingRemoteDomainState state) {
        if (objectMapper.writeValueAsBytes(state.toOpaqueState()).length > 262144) {
            throw new IllegalArgumentException("Native opaque checkpoint exceeds 262144 bytes");
        }
    }

    private static void validateNativeWindow(RemoteDomainSystemWorkWindowRequest request,
            Map<String, Object> state, Map<String, Object> metadata) {
        long version = nativeLong(state, "stateVersion");
        requireNativeIdentity(metadata, version);
        long start = request.getPayload().getTimeWindow().getPreviousTimeEpochMs();
        long end = request.getPayload().getTimeWindow().getCurrentTimeEpochMs();
        long horizon = nativeLong(state, "logicalHorizonEpochMs");
        long boundary = nativeLong(state, state.containsKey("nextDueEpochMs") ? "nextDueEpochMs" : "noWorkBeforeEpochMs");
        if (!NATIVE_PROFILE.equals(state.get("profile")) || !Objects.equals(state.get("contextId"), metadata.get("contextId"))
                || nativeLong(metadata, "logicalHorizonEpochMs") != horizon
                || start != nativeLong(state, "processedThroughEpochMs") || end <= start || end > boundary || end > horizon
                || !List.of(NATIVE_STATEMENT_KIND).equals(request.getPayload().getWorkKinds())
                || !Integer.valueOf(1).equals(request.getPayload().getMaxWorkItems())
                || request.getPayload().getExecutionMode() != RemoteDomainSystemWorkWindowPayload.ExecutionModeEnum.EVALUATE_AND_EXECUTE) {
            throw new IllegalArgumentException("Native window must match the frozen context, horizon and one earliest statement boundary");
        }
    }

    private static void validateNativeReadOnlyTool(RemoteDomainExecuteWorkRequest request,
            Map<String, Object> state) {
        if (!NATIVE_PROFILE.equals(state.get("profile"))
                || !(state.get("contextId") instanceof String contextId) || contextId.isBlank()
                || nativeLong(state, "stateVersion") < 1L
                || !BankingRemoteDomainState.SCHEMA_VERSION.equals(request.getState().getSchemaVersion())) {
            throw new IllegalArgumentException("Native tool calls require a prepared customer checkpoint");
        }
        var turn = request.getPayload().getTurnContext();
        long current = nativeLong(state, "processedThroughEpochMs");
        requireNativeTimes(current, nativeLong(state, "logicalHorizonEpochMs"));
        if (turn.getDueWork().size() != 1
                || turn.getTimeWindow().getPreviousTimeEpochMs() != current
                || turn.getTimeWindow().getCurrentTimeEpochMs() != current) {
            throw new IllegalArgumentException("Native tool calls require one read at the accepted logical instant");
        }
        var work = turn.getDueWork().getFirst();
        Map<String, Object> payload = work.getPayload();
        if (!"TOOL_CALL".equals(work.getWorkType()) || work.getScheduledForEpochMs() != current
                || !Integer.valueOf(1).equals(work.getAttemptNumber())
                || payload == null || !payload.keySet().equals(Set.of("kind", "toolName", "arguments"))
                || !"tool-call".equals(payload.get("kind"))
                || !"get_customer_accounts".equals(payload.get("toolName"))
                || !(payload.get("arguments") instanceof Map<?, ?> arguments) || !arguments.isEmpty()) {
            throw new IllegalArgumentException("Native execute-work permits only the declared argumentless customer account read");
        }
    }

    private RemoteDomainSystemWorkWindowResult nativeInitialWorkResult(BankingRemoteDomainState state,
            List<Map<String, Object>> events, long start) {
        var result = new RemoteDomainSystemWorkWindowResult(start, start, events.isEmpty() ? 0 : 1, events.isEmpty() ? 0 : 1);
        result.setStateDeltas(events);
        result.setDecisionTriggers(decisionTriggerPolicy.evaluate(events, start, state.data().get("decisionTriggerPolicy")));
        return result;
    }

    private static boolean isCreditCardStatementWork(Map<String, Object> work) {
        return "credit_card_statement_close".equalsIgnoreCase(
                firstText(work.get("kind"), mapValue(work.get("payload")).get("kind")));
    }

    private static boolean isCreditCardAccount(Map<String, Object> account) {
        String productCode = firstText(account.get("productCode"), "").toUpperCase(Locale.ROOT);
        String category = firstText(account.get("productCategory"), account.get("category"));
        return productCode.startsWith("CC") || "CRED_AND_CHRG_CARDS".equalsIgnoreCase(category);
    }

    private static boolean isIndebtedCreditCardAccount(Map<String, Object> account) {
        if (!isCreditCardAccount(account) || account.get("balance") == null) {
            return false;
        }
        try {
            return new BigDecimal(String.valueOf(account.get("balance"))).signum() < 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static List<Map<String, Object>> domainSystemWorkNextDueHints(
            ZonedDateTime currentTime,
            List<Map<String, Object>> dueWork,
            Map<String, Object> data,
            List<String> requestedWorkKinds) {
        ZonedDateTime nextMonthlyBoundary = currentTime.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC);
        List<String> kinds = dueWork == null
                ? new ArrayList<>()
                : dueWork.stream()
                        .map(item -> firstText(mapValue(item.get("payload")).get("kind"), item.get("kind")))
                        .filter(BankingRemoteDomainService::hasText)
                        .distinct()
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean statementRequested = requestedWorkKinds == null
                || requestedWorkKinds.isEmpty()
                || requestedWorkKinds.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .anyMatch("credit_card_statement_close"::equalsIgnoreCase);
        if (statementRequested
                && mapList(data.get("accounts")).stream().anyMatch(BankingRemoteDomainService::isIndebtedCreditCardAccount)
                && !kinds.contains("credit_card_statement_close")) {
            kinds.add("credit_card_statement_close");
        }
        List<Map<String, Object>> hints = new ArrayList<>();
        Map<String, Object> monthlyHint = new LinkedHashMap<>();
        monthlyHint.put("cadence", "MONTHLY");
        monthlyHint.put("nextDueEpochMs", nextMonthlyBoundary.toInstant().toEpochMilli());
        monthlyHint.put("nextDueAt", nextMonthlyBoundary.toInstant().toString());
        monthlyHint.put("reason", "banking-domain-system-work-window");
        monthlyHint.put("workKinds", kinds);
        hints.add(monthlyHint);

        boolean rateChangeRequested = requestedWorkKinds == null
                || requestedWorkKinds.isEmpty()
                || requestedWorkKinds.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .anyMatch(BankingClockDrivenRateChangePlan.WORK_KIND::equalsIgnoreCase);
        if (rateChangeRequested) {
            mapList(data.get("scheduledWork")).stream()
                    .filter(work -> BankingClockDrivenRateChangePlan.WORK_KIND.equalsIgnoreCase(
                            firstText(work.get("kind"), mapValue(work.get("payload")).get("kind"))))
                    .map(work -> rateChangeDueWorkItem(
                            work,
                            longValue(work.get("scheduledForEpochMs")) != null
                                    ? longValue(work.get("scheduledForEpochMs"))
                                    : Long.MAX_VALUE))
                    .filter(work -> !work.isEmpty())
                    .filter(work -> !hasCompletedDomainSystemWork(data, work))
                    .min(java.util.Comparator.comparingLong(work -> longValue(work.get("scheduledForEpochMs"))))
                    .ifPresent(work -> {
                        long scheduledForEpochMs = longValue(work.get("scheduledForEpochMs"));
                        Map<String, Object> onceHint = new LinkedHashMap<>();
                        onceHint.put("cadence", "ONCE");
                        onceHint.put("nextDueEpochMs", scheduledForEpochMs);
                        onceHint.put("nextDueAt", Instant.ofEpochMilli(scheduledForEpochMs).toString());
                        onceHint.put("reason", "launch-pinned-rate-change");
                        onceHint.put("workKinds", List.of(BankingClockDrivenRateChangePlan.WORK_KIND));
                        hints.add(onceHint);
                    });
        }
        return List.copyOf(hints);
    }

    private static boolean hasCompletedDomainSystemWork(
            Map<String, Object> data,
            Map<String, Object> workItem) {
        String occurrenceKey = firstText(workItem.get("occurrenceKey"), null);
        if (!hasText(occurrenceKey)) {
            return false;
        }
        String kind = firstText(mapValue(workItem.get("payload")).get("kind"), workItem.get("kind"));
        for (Map<String, Object> logItem : mapList(data.get("executionLog"))) {
            if (!occurrenceKey.equals(firstText(logItem.get("occurrenceKey"), null))) {
                continue;
            }
            String loggedKind = firstText(mapValue(logItem.get("payload")).get("kind"), logItem.get("kind"));
            if (!hasText(kind) || kind.equalsIgnoreCase(firstText(loggedKind, ""))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> domainSystemDueWorkItem(
            Map<String, Object> work,
            String kind,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = new LinkedHashMap<>(mapValue(work.get("payload")));
        payload.putIfAbsent("kind", kind);
        String workId = firstText(work.get("workId"), "domain-system-" + kind);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workId", workId);
        item.put("kind", kind);
        item.put("workType", "DOMAIN_SYSTEM_WORK");
        item.put("occurrenceKey", "banking|domain-system|" + workId + "|" + currentTime.toLocalDate().withDayOfMonth(1));
        item.put("scheduledForEpochMs", currentTime.toInstant().toEpochMilli());
        item.put("attemptNumber", 1);
        item.put("description", firstText(work.get("description"), kind));
        item.put("payload", payload);
        return item;
    }

    private static Map<String, Object> rateChangeDueWorkItem(
            Map<String, Object> work,
            long currentTimeEpochMs) {
        Map<String, Object> payload = new LinkedHashMap<>(mapValue(work.get("payload")));
        Long scheduledForEpochMs = longValue(work.get("scheduledForEpochMs"));
        if (scheduledForEpochMs == null) {
            scheduledForEpochMs = longValue(payload.get("scheduledForEpochMs"));
        }
        if (scheduledForEpochMs == null || currentTimeEpochMs < scheduledForEpochMs) {
            return Map.of();
        }
        payload.putIfAbsent("kind", BankingClockDrivenRateChangePlan.WORK_KIND);
        String workId = firstText(work.get("workId"), "domain-system-credit-card-rate-change");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workId", workId);
        item.put("kind", BankingClockDrivenRateChangePlan.WORK_KIND);
        item.put("workType", "DOMAIN_SYSTEM_WORK");
        item.put("occurrenceKey", firstText(
                work.get("occurrenceKey"),
                "banking|domain-system|" + workId + "|" + scheduledForEpochMs));
        item.put("scheduledForEpochMs", scheduledForEpochMs);
        item.put("attemptNumber", 1);
        item.put("description", firstText(work.get("description"), "Credit card rate change"));
        item.put("payload", payload);
        return item;
    }

    private static RemoteDomainSystemWorkResult domainSystemWorkResult(
            Map<String, Object> item,
            boolean evaluateOnly,
            List<Map<String, Object>> workResultRecords) {
        String workId = firstText(item.get("workId"), "domain-system-work");
        String kind = firstText(mapValue(item.get("payload")).get("kind"), item.get("kind"));
        Map<String, Object> workResultRecord = matchingWorkResultRecord(item, workResultRecords);
        RemoteDomainSystemWorkResult result = new RemoteDomainSystemWorkResult(
                workId,
                kind,
                evaluateOnly
                        ? RemoteDomainSystemWorkResult.OutcomeEnum.EVALUATED
                        : domainSystemWorkOutcome(firstText(workResultRecord.get("outcome"), "SKIPPED")));
        result.setOccurrenceKey(firstText(item.get("occurrenceKey"), null));
        result.setScheduledForEpochMs(longValue(item.get("scheduledForEpochMs")));
        Map<String, Object> details = mapValue(workResultRecord.get("details"));
        Long evidenceEventCount = longValue(details.get("evidenceEventCount"));
        result.setDetails(Map.of(
                "description", firstText(item.get("description"), kind),
                "evidenceEventCount", evidenceEventCount == null ? 0 : evidenceEventCount.intValue()));
        return result;
    }

    private static Map<String, Object> matchingWorkResultRecord(
            Map<String, Object> item,
            List<Map<String, Object>> workResultRecords) {
        String workId = firstText(item.get("workId"), "domain-system-work");
        String kind = firstText(mapValue(item.get("payload")).get("kind"), item.get("kind"));
        String occurrenceKey = firstText(item.get("occurrenceKey"), null);
        for (Map<String, Object> record : workResultRecords == null ? List.<Map<String, Object>>of() : workResultRecords) {
            if (workId.equals(firstText(record.get("workId"), "domain-system-work"))
                    && kind.equals(firstText(record.get("kind"), ""))
                    && Objects.equals(occurrenceKey, firstText(record.get("occurrenceKey"), null))) {
                return record;
            }
        }
        return Map.of();
    }

    private static int appliedWorkResultCount(List<Map<String, Object>> workResultRecords) {
        int count = 0;
        for (Map<String, Object> record : workResultRecords == null ? List.<Map<String, Object>>of() : workResultRecords) {
            if ("APPLIED".equalsIgnoreCase(firstText(record.get("outcome"), ""))) {
                count++;
            }
        }
        return count;
    }

    private static RemoteDomainSystemWorkResult.OutcomeEnum domainSystemWorkOutcome(String outcome) {
        if ("APPLIED".equalsIgnoreCase(outcome)) {
            return RemoteDomainSystemWorkResult.OutcomeEnum.APPLIED;
        }
        if ("EVALUATED".equalsIgnoreCase(outcome)) {
            return RemoteDomainSystemWorkResult.OutcomeEnum.EVALUATED;
        }
        if ("REJECTED".equalsIgnoreCase(outcome)) {
            return RemoteDomainSystemWorkResult.OutcomeEnum.REJECTED;
        }
        return RemoteDomainSystemWorkResult.OutcomeEnum.SKIPPED;
    }

    private static boolean isDomainSystemWorkKind(String kind) {
        String normalizedKind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        return "credit_card_interest_accrual".equals(normalizedKind)
                || "monthly_interest_accrual".equals(normalizedKind)
                || "loan_interest_accrual".equals(normalizedKind)
                || "credit_card_statement_close".equals(normalizedKind)
                || BankingClockDrivenRateChangePlan.WORK_KIND.equals(normalizedKind);
    }

    private static ZonedDateTime currentTime(RemoteDomainExecuteWorkRequest request) {
        Long currentTimeEpochMs = request.getPayload() != null
                && request.getPayload().getTurnContext() != null
                && request.getPayload().getTurnContext().getTimeWindow() != null
                        ? request.getPayload().getTurnContext().getTimeWindow().getCurrentTimeEpochMs()
                        : null;
        long epochMs = currentTimeEpochMs != null ? currentTimeEpochMs : request.getSentAtEpochMs();
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static ZonedDateTime currentTime(RemoteDomainSystemWorkWindowRequest request) {
        Long currentTimeEpochMs = request.getPayload() != null
                && request.getPayload().getTimeWindow() != null
                        ? request.getPayload().getTimeWindow().getCurrentTimeEpochMs()
                        : null;
        long epochMs = currentTimeEpochMs != null ? currentTimeEpochMs : request.getSentAtEpochMs();
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private String runtimeIdentity(String requestedRuntimeIdentity, BankingRemoteDomainState state) {
        if (hasText(requestedRuntimeIdentity)) {
            return requestedRuntimeIdentity.trim();
        }
        return BankingRuntimeIdentitySupport.runtimeIdentity(state)
                .orElseGet(() -> state.bankingCustomerId() != null ? state.bankingCustomerId().toString() : "banking");
    }

    private <T> T convert(Object value, TypeReference<T> type) {
        return objectMapper.convertValue(value, type);
    }

    private static long deterministicCustomerId(String agentId) {
        String value = hasText(agentId) ? agentId.trim() : "remote-banking-agent";
        return 10_000L + Integer.toUnsignedLong(value.hashCode());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private static String firstText(Object first, Object fallback) {
        String value = first == null ? null : String.valueOf(first);
        if (hasText(value)) {
            return value;
        }
        value = fallback == null ? null : String.valueOf(fallback);
        return hasText(value) ? value : null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> controlActionTargetMap(RemoteDomainControlActionTarget target) {
        if (target == null) {
            return Map.of("scope", "AGENT");
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("scope", target.getScope() == null ? "AGENT" : target.getScope().getValue());
        if (target.getAgentIds() != null && !target.getAgentIds().isEmpty()) {
            value.put("agentIds", List.copyOf(target.getAgentIds()));
        }
        if (target.getSegmentIds() != null && !target.getSegmentIds().isEmpty()) {
            value.put("segmentIds", List.copyOf(target.getSegmentIds()));
        }
        putIfPresent(value, "cohortId", target.getCohortId());
        if (target.getSelector() != null && !target.getSelector().isEmpty()) {
            value.put("selector", new LinkedHashMap<>(target.getSelector()));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                map.forEach((key, entryValue) -> {
                    if (key != null) {
                        value.put(String.valueOf(key), entryValue);
                    }
                });
                values.add(value);
            }
        }
        return values;
    }

    private static Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> {
            if (key != null) {
                value.put(String.valueOf(key), entryValue);
            }
        });
        return value;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
