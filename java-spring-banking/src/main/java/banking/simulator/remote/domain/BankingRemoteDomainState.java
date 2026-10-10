package banking.simulator.remote.domain;

import banking.simulator.remote.domain.contract.BankingPlannedTransactionContractSupport;
import banking.simulator.remote.domain.contract.BankingRuntimeScheduleContractAccess;
import banking.simulator.domain.banking.BankingAccountView;
import banking.simulator.domain.banking.BankingChannelPostingFactory;
import banking.simulator.domain.banking.BankingChannelType;
import banking.simulator.domain.banking.BankingDoubleEntryPosting;
import banking.simulator.domain.banking.BankingPlannedTransactionContract;
import banking.simulator.domain.banking.BankingPostingLeg;
import banking.simulator.domain.banking.BankingRuntimeIdentityAccess;
import banking.simulator.domain.banking.BankingRuntimeProjectionAccess;
import banking.simulator.domain.banking.BankingRuntimeScheduleAccess;
import banking.simulator.domain.banking.BankingRuntimeScheduledWorkPlan;
import banking.simulator.domain.banking.BankingRuntimeStateSnapshot;
import banking.simulator.domain.banking.model.Account;
import banking.simulator.domain.banking.model.CreditAccount;
import banking.simulator.domain.banking.model.Product;
import banking.simulator.domain.banking.model.TransactionAccount;
import banking.simulator.domain.banking.model.transaction.TransactionInstruction;
import banking.simulator.domain.banking.model.transaction.TransactionInstructionType;
import banking.simulator.remote.domain.contract.models.PlannedTransactionEntity;
import ai.astraform.remote.domain.openapi.models.RemoteDomainOpaqueState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class BankingRemoteDomainState
        implements BankingRuntimeIdentityAccess,
                BankingRuntimeProjectionAccess,
                BankingRuntimeScheduleAccess,
                BankingRuntimeScheduleContractAccess {
    static final String SCHEMA_VERSION = "banking.remote-state.v1";

    private final Map<String, Object> data;
    private final Map<String, Object> responseToolResult;
    private final List<Map<String, Object>> responseEvidenceEvents;
    private final List<Map<String, Object>> responseDomainSystemWorkResults;

    BankingRemoteDomainState(Map<String, Object> data) {
        this(data, null, null, null);
    }

    private BankingRemoteDomainState(
            Map<String, Object> data,
            Map<String, Object> responseToolResult,
            List<Map<String, Object>> responseEvidenceEvents,
            List<Map<String, Object>> responseDomainSystemWorkResults) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        this.responseToolResult = responseToolResult == null
                ? null
                : new LinkedHashMap<>(responseToolResult);
        this.responseEvidenceEvents = responseEvidenceEvents == null
                ? null
                : mutableMapList(responseEvidenceEvents);
        this.responseDomainSystemWorkResults = responseDomainSystemWorkResults == null
                ? null
                : mutableMapList(responseDomainSystemWorkResults);
    }

    static BankingRemoteDomainState from(RemoteDomainOpaqueState opaqueState) {
        return new BankingRemoteDomainState(opaqueState == null ? Map.of() : opaqueState.getData());
    }

    RemoteDomainOpaqueState toOpaqueState() {
        return new RemoteDomainOpaqueState(SCHEMA_VERSION, data());
    }

    Map<String, Object> data() {
        return new LinkedHashMap<>(data);
    }

    List<Map<String, Object>> recentTransactionPostings(int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }
        List<Map<String, Object>> postings = mutableMapList(data.get("transactionPostings"));
        int fromIndex = Math.max(0, postings.size() - maxItems);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int index = fromIndex; index < postings.size(); index++) {
            recent.add(new LinkedHashMap<>(postings.get(index)));
        }
        return recent;
    }

    List<Map<String, Object>> creditCardBills(int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }
        List<Map<String, Object>> bills = mutableMapList(data.get("creditCardBills"));
        int fromIndex = Math.max(0, bills.size() - maxItems);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int index = fromIndex; index < bills.size(); index++) {
            recent.add(new LinkedHashMap<>(bills.get(index)));
        }
        return recent;
    }

    List<Map<String, Object>> customerAccounts(boolean includeLimit) {
        return customerAccounts(mutableMapList(data.get("accounts")), includeLimit);
    }

    private static List<Map<String, Object>> customerAccounts(List<Map<String, Object>> accounts, boolean includeLimit) {
        List<String> fields = new ArrayList<>(List.of(
                "accountId", "name", "productCode", "balance", "currency", "currentMinPayment"));
        if (includeLimit) fields.add("limit");
        return accounts.stream().map(account -> selectedFields(account, fields)).toList();
    }

    private static Map<String, Object> selectedFields(Map<String, Object> source, List<String> fields) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : fields) {
            if (source.get(field) != null) selected.put(field, source.get(field));
        }
        return selected;
    }

    BankingRemoteDomainState withExecutedWork(
            List<Map<String, Object>> dueWork,
            ZonedDateTime currentTime,
            long updatedAtEpochMs) {
        Map<String, Object> next = data();
        List<Map<String, Object>> accounts = mutableMapList(next.get("accounts"));
        List<Map<String, Object>> executionLog = mutableMapList(next.get("executionLog"));
        List<Map<String, Object>> transactionPostings = mutableMapList(next.get("transactionPostings"));
        List<Map<String, Object>> creditCardBills = mutableMapList(next.get("creditCardBills"));
        List<Map<String, Object>> evidenceEvents = new ArrayList<>();
        int appliedTransactionCount = intValue(next.get("appliedTransactionCount"));
        int rejectedTransactionCount = intValue(next.get("rejectedTransactionCount"));
        int appliedDomainSystemWorkCount = intValue(next.get("appliedDomainSystemWorkCount"));
        Map<String, Object> lastToolResult = Map.of();
        List<Map<String, Object>> domainSystemWorkResults = new ArrayList<>();
        int executedWorkDelta = 0;
        boolean canonicalStateChanged = dueWork == null || dueWork.isEmpty();
        for (Map<String, Object> item : dueWork == null ? List.<Map<String, Object>>of() : dueWork) {
            Map<String, Object> logItem = new LinkedHashMap<>(item);
            if (!BankingClockDrivenRateChangePlan.WORK_KIND.equals(workKind(item))
                    && isMutatingWork(item)
                    && hasAppliedWork(item, executionLog, transactionPostings)) {
                lastToolResult = idempotentReplayResult(item);
                evidenceEvents.add(toolEffectReplayEvent(item, currentTime));
                continue;
            }
            if (isReadOnlyToolCall(item)) {
                lastToolResult = applyToolCall(item, accounts, transactionPostings, creditCardBills, currentTime);
                evidenceEvents.add(readOnlyToolEvent(item, lastToolResult, currentTime));
                continue;
            }
            canonicalStateChanged = true;
            try {
                if (isToolCall(item)) {
                    lastToolResult = applyToolCall(item, accounts, transactionPostings, creditCardBills, currentTime);
                    logItem.put("status", Boolean.TRUE.equals(lastToolResult.get("success"))
                            ? "APPLIED"
                            : "REJECTED");
                    logItem.put("toolResult", lastToolResult);
                    if (Boolean.TRUE.equals(lastToolResult.get("success"))) {
                        executedWorkDelta++;
                    }
                    if (!Boolean.TRUE.equals(lastToolResult.get("success"))) {
                        rejectedTransactionCount++;
                    }
                    executionLog.add(logItem);
                    continue;
                }
                String workKind = workKind(item);
                if (BankingClockDrivenRateChangePlan.WORK_KIND.equals(workKind)) {
                    boolean replay = hasAppliedRateChange(next, item);
                    List<Map<String, Object>> events = applyCreditCardRateChange(
                            next,
                            item,
                            accounts,
                            currentTime);
                    evidenceEvents.addAll(events);
                    boolean applied = !events.isEmpty();
                    appliedDomainSystemWorkCount += applied && !replay ? 1 : 0;
                    executedWorkDelta += applied && !replay ? 1 : 0;
                    domainSystemWorkResults.add(domainSystemWorkOutcome(
                            item,
                            applied ? "APPLIED" : "SKIPPED",
                            events));
                    logItem.put("status", applied ? "APPLIED" : "SKIPPED");
                    logItem.put("domainSystemEvents", events);
                    if (!replay) {
                        executionLog.add(logItem);
                    }
                    continue;
                }
                if ("monthly_interest_accrual".equals(workKind)) {
                    List<Map<String, Object>> events =
                            applyMonthlyInterestAccrual(item, accounts, currentTime);
                    evidenceEvents.addAll(events);
                    appliedDomainSystemWorkCount += events.isEmpty() ? 0 : 1;
                    executedWorkDelta += events.isEmpty() ? 0 : 1;
                    domainSystemWorkResults.add(domainSystemWorkOutcome(
                            item,
                            events.isEmpty() ? "SKIPPED" : "APPLIED",
                            events));
                    if (!events.isEmpty()) {
                        logItem.put("status", "APPLIED");
                        logItem.put("domainSystemEvents", events);
                        executionLog.add(logItem);
                    }
                    continue;
                }
                if ("loan_interest_accrual".equals(workKind)) {
                    List<Map<String, Object>> events =
                            applyLoanInterestAccrual(item, accounts, transactionPostings, currentTime);
                    evidenceEvents.addAll(events);
                    appliedDomainSystemWorkCount += events.isEmpty() ? 0 : 1;
                    executedWorkDelta += events.isEmpty() ? 0 : 1;
                    domainSystemWorkResults.add(domainSystemWorkOutcome(
                            item,
                            events.isEmpty() ? "SKIPPED" : "APPLIED",
                            events));
                    if (!events.isEmpty()) {
                        logItem.put("status", "APPLIED");
                        logItem.put("domainSystemEvents", events);
                        executionLog.add(logItem);
                    }
                    continue;
                }
                if ("credit_card_interest_accrual".equals(workKind)) {
                    List<Map<String, Object>> events =
                            applyCreditCardInterestAccrual(item, accounts, transactionPostings, currentTime);
                    evidenceEvents.addAll(events);
                    appliedDomainSystemWorkCount += events.isEmpty() ? 0 : 1;
                    executedWorkDelta += events.isEmpty() ? 0 : 1;
                    domainSystemWorkResults.add(domainSystemWorkOutcome(
                            item,
                            events.isEmpty() ? "SKIPPED" : "APPLIED",
                            events));
                    if (!events.isEmpty()) {
                        logItem.put("status", "APPLIED");
                        logItem.put("domainSystemEvents", events);
                        executionLog.add(logItem);
                    }
                    continue;
                }
                if ("credit_card_statement_close".equals(workKind)) {
                    List<Map<String, Object>> events =
                            applyCreditCardStatementClose(item, accounts, creditCardBills, eventTime(item, currentTime));
                    evidenceEvents.addAll(events);
                    appliedDomainSystemWorkCount += events.isEmpty() ? 0 : 1;
                    executedWorkDelta += events.isEmpty() ? 0 : 1;
                    domainSystemWorkResults.add(domainSystemWorkOutcome(
                            item,
                            events.isEmpty() ? "SKIPPED" : "APPLIED",
                            events));
                    if (!events.isEmpty()) {
                        logItem.put("status", "APPLIED");
                        logItem.put("domainSystemEvents", events);
                        executionLog.add(logItem);
                    }
                    continue;
                }
                BankingDoubleEntryPosting posting = postingForDueWork(item, accounts, currentTime);
                if (posting != null) {
                    applyPosting(accounts, posting);
                    transactionPostings.add(postingRecord(item, posting));
                    appliedTransactionCount++;
                    executedWorkDelta++;
                    logItem.put("status", "APPLIED");
                } else {
                    logItem.put("status", "RECORDED");
                }
            } catch (RuntimeException ex) {
                rejectedTransactionCount++;
                logItem.put("status", "REJECTED");
                logItem.put("failureMessage", ex.getMessage());
                if (BankingClockDrivenRateChangePlan.WORK_KIND.equals(workKind(item))) {
                    domainSystemWorkResults.add(domainSystemWorkOutcome(item, "REJECTED", List.of()));
                }
            }
            executionLog.add(logItem);
        }
        if (!canonicalStateChanged) {
            return new BankingRemoteDomainState(
                    data(),
                    lastToolResult,
                    evidenceEvents,
                    domainSystemWorkResults);
        }
        next.put("accounts", accounts);
        next.put("executionLog", executionLog);
        next.put("transactionPostings", transactionPostings);
        next.put("creditCardBills", creditCardBills);
        next.put("executedWorkCount", intValue(next.get("executedWorkCount")) + executedWorkDelta);
        next.put("appliedTransactionCount", appliedTransactionCount);
        next.put("rejectedTransactionCount", rejectedTransactionCount);
        next.put("appliedDomainSystemWorkCount", appliedDomainSystemWorkCount);
        next.remove("lastEvidenceEvents");
        next.remove("lastDomainSystemWorkResults");
        next.remove("lastToolResult");
        next.put("lastAction", "execute-work");
        next.put("lastUpdatedEpochMs", updatedAtEpochMs);
        return new BankingRemoteDomainState(
                next,
                lastToolResult,
                evidenceEvents,
                domainSystemWorkResults);
    }

    @Override
    public String personaName() {
        return text(data.get("personaName"));
    }

    @Override
    public Long bankingCustomerId() {
        return longValue(data.get("bankingCustomerId"));
    }

    @Override
    public String runtimeIdentity() {
        return text(data.get("runtimeIdentity"));
    }

    @Override
    public String conversationId() {
        return text(data.get("conversationId"));
    }

    @Override
    public List<BankingAccountView> statusAccountViewsSnapshot() {
        return mutableMapList(data.get("accounts")).stream()
                .map(BankingRemoteDomainState::accountView)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Map<String, Object> balancesSnapshot() {
        List<BankingAccountView> accounts = statusAccountViewsSnapshot();
        double totalBalance = accounts.stream()
                .map(BankingAccountView::balance)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("accountCount", accounts.size());
        snapshot.put("totalBalance", totalBalance);
        snapshot.put("currency", accounts.stream()
                .map(BankingAccountView::currency)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("AUD"));
        return snapshot;
    }

    @Override
    public BankingRuntimeStateSnapshot stateSnapshot(ZonedDateTime now, String agentId) {
        List<BankingAccountView> accounts = statusAccountViewsSnapshot();
        double currentBalance = accounts.stream()
                .map(BankingAccountView::balance)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double totalCreditLimit = accounts.stream()
                .map(BankingAccountView::limit)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double creditUsed = accounts.stream()
                .map(BankingAccountView::balance)
                .filter(Objects::nonNull)
                .filter(balance -> balance < 0.0)
                .mapToDouble(balance -> Math.abs(balance))
                .sum();
        double creditUtilisation = totalCreditLimit <= 0.0 ? 0.0 : creditUsed / totalCreditLimit;
        return new BankingRuntimeStateSnapshot(
                agentId,
                currentBalance,
                creditUtilisation,
                intValue(data.get("missedPaymentsLast3m")),
                Boolean.TRUE.equals(data.get("hardshipFlag")),
                intValue(data.getOrDefault("daysSinceLastOutreach", -1)));
    }

    @Override
    public List<BankingRuntimeScheduledWorkPlan> plannedBankingScheduledWorkPlans() {
        return mutableMapList(data.get("scheduledWork")).stream()
                .filter(work -> !BankingClockDrivenRateChangePlan.WORK_KIND.equals(workKind(work)))
                .map(BankingPlannedTransactionContractSupport::fromScheduledWork)
                .filter(Objects::nonNull)
                .map(contract -> new BankingRuntimeScheduledWorkPlan(
                        new TransactionInstruction(
                                contract.type() != null ? contract.type() : TransactionInstructionType.PAYMENT,
                                contract.amount() != null ? contract.amount() : 0.0,
                                contract.exactAmount(),
                                (Account) null,
                                (Account) null,
                                contract.resolvedDescription()),
                        contract.frequencyName()))
                .toList();
    }

    @Override
    public List<PlannedTransactionEntity> plannedScheduleEntities() {
        return mutableMapList(data.get("scheduledWork")).stream()
                .filter(work -> !BankingClockDrivenRateChangePlan.WORK_KIND.equals(workKind(work)))
                .map(BankingPlannedTransactionContractSupport::fromScheduledWork)
                .filter(Objects::nonNull)
                .map(BankingPlannedTransactionContractSupport::toEntity)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<PlannedTransactionEntity> pendingScheduleEntities() {
        return List.of();
    }

    Map<String, Object> lastToolResult() {
        if (responseToolResult != null) {
            return new LinkedHashMap<>(responseToolResult);
        }
        return mapValue(data.get("lastToolResult"));
    }

    List<Map<String, Object>> lastDomainSystemWorkResults() {
        if (responseDomainSystemWorkResults != null) {
            return mutableMapList(responseDomainSystemWorkResults);
        }
        return mutableMapList(data.get("lastDomainSystemWorkResults"));
    }

    BankingRemoteDomainState withClockDrivenRateChangePlan(
            BankingClockDrivenRateChangePlan.Snapshot snapshot,
            long updatedAtEpochMs) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Object> next = data();
        Object existingRaw = next.get("clockDrivenInterventionPlan");
        if (existingRaw != null) {
            BankingClockDrivenRateChangePlan.Snapshot existing =
                    BankingClockDrivenRateChangePlan.requireSnapshot(existingRaw);
            if (!existing.equals(snapshot)) {
                throw new IllegalArgumentException(
                        "The banking state is already bound to a contradictory intervention plan");
            }
            return this;
        }

        Map<String, Object> plan = snapshot.plan();
        String productCode = text(plan.get("productCode"));
        String baselineRate = BankingClockDrivenRateChangePlan.canonicalDecimal(
                plan.get("expectedPreviousRate"), "expectedPreviousRate");
        List<Map<String, Object>> accounts = mutableMapList(next.get("accounts"));
        List<Map<String, Object>> eligibleAccounts = accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(text(account.get("productCode"))))
                .toList();
        for (Map<String, Object> account : eligibleAccounts) {
            validateAccountRate(account, "interestRate", baselineRate);
            validateAccountRate(account, "apr", baselineRate);
        }
        for (Map<String, Object> account : eligibleAccounts) {
            validateOrInitializeAccountRate(account, "interestRate", baselineRate);
            validateOrInitializeAccountRate(account, "apr", baselineRate);
            account.put("ratePlanDigest", snapshot.planDigest());
        }

        Map<String, Object> workPayload = new LinkedHashMap<>();
        workPayload.put("kind", BankingClockDrivenRateChangePlan.WORK_KIND);
        workPayload.put("scheduledForEpochMs", plan.get("scheduledForEpochMs"));
        workPayload.put("planSnapshot", snapshot.asMap());
        Map<String, Object> scheduledWork = new LinkedHashMap<>();
        scheduledWork.put("workId", plan.get("workId"));
        scheduledWork.put("kind", BankingClockDrivenRateChangePlan.WORK_KIND);
        scheduledWork.put("frequency", "ONCE");
        scheduledWork.put("scheduledForEpochMs", plan.get("scheduledForEpochMs"));
        scheduledWork.put("occurrenceKey", "banking|clock-driven-rate-change|" + plan.get("mutationOperationId"));
        scheduledWork.put("description", "Apply the launch-pinned CC001 rate change");
        scheduledWork.put("payload", workPayload);

        List<Map<String, Object>> scheduled = mutableMapList(next.get("scheduledWork"));
        for (Map<String, Object> existingWork : scheduled) {
            if (!Objects.equals(text(existingWork.get("workId")), text(plan.get("workId")))) {
                continue;
            }
            if (!existingWork.equals(scheduledWork)) {
                throw new IllegalArgumentException(
                        "Scheduled rate-change workId is already bound to contradictory facts");
            }
            next.put("accounts", accounts);
            next.put("clockDrivenInterventionPlan", snapshot.asMap());
            return new BankingRemoteDomainState(next);
        }
        scheduled.add(scheduledWork);
        next.put("accounts", accounts);
        next.put("scheduledWork", scheduled);
        next.put("clockDrivenInterventionPlan", snapshot.asMap());
        next.put("lastAction", "clock-driven-intervention-plan-installed");
        next.put("lastUpdatedEpochMs", updatedAtEpochMs);
        return new BankingRemoteDomainState(next);
    }

    BankingRemoteDomainState withControlAction(
            String operationId,
            String idempotencyKey,
            String actionId,
            Map<String, Object> target,
            Map<String, Object> inputs,
            long updatedAtEpochMs) {
        Map<String, Object> next = data();
        List<Map<String, Object>> controlActions = mutableMapList(next.get("controlActions"));
        Map<String, Object> targetMap = mapValue(target);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("actionId", firstText(actionId, "control-action"));
        action.put("operationId", normalizedOperationId(operationId));
        action.put("idempotencyKey", firstText(idempotencyKey, ""));
        action.put("targetScope", firstText(targetMap.get("scope"), "AGENT"));
        action.put("target", targetMap);
        action.put("inputs", mapValue(inputs));
        action.put("appliedAtEpochMs", updatedAtEpochMs);
        action.put("personaName", personaName());
        action.put("runtimeIdentity", runtimeIdentity());
        controlActions.add(action);

        List<Map<String, Object>> evidenceEvents = evidenceEventsForControlAction(action);

        next.put("controlActions", controlActions);
        next.put("lastControlAction", action);
        next.put("lastEvidenceEvents", evidenceEvents);
        next.put("lastAction", "control-action");
        next.put("lastUpdatedEpochMs", updatedAtEpochMs);
        return new BankingRemoteDomainState(next);
    }

    BankingRemoteDomainState withRateChangePlanControlAction(
            String operationId,
            String idempotencyKey,
            String actionId,
            Map<String, Object> target,
            Map<String, Object> inputs,
            BankingClockDrivenRateChangePlan.Snapshot snapshot,
            long updatedAtEpochMs) {
        BankingRemoteDomainState recorded = withControlAction(
                operationId,
                idempotencyKey,
                actionId,
                target,
                inputs,
                updatedAtEpochMs);
        Map<String, Object> next = recorded.data();
        List<Map<String, Object>> controlActions = mutableMapList(next.get("controlActions"));
        Map<String, Object> action = new LinkedHashMap<>(controlActions.removeLast());
        long eligibleAccountCount = mutableMapList(next.get("accounts")).stream()
                .filter(account -> BankingClockDrivenRateChangePlan.SUPPORTED_PRODUCT_CODE
                        .equalsIgnoreCase(text(account.get("productCode"))))
                .count();
        action.put("outcome", "PLAN_INSTALLED");
        action.put("planDigest", snapshot.planDigest());
        action.put("eligibleAccountCount", eligibleAccountCount);
        controlActions.add(action);
        next.put("controlActions", controlActions);
        next.put("lastControlAction", action);
        next.put("lastEvidenceEvents", evidenceEventsForControlAction(action));
        return new BankingRemoteDomainState(next);
    }

    Optional<Map<String, Object>> controlActionByOperationId(String operationId) {
        String normalizedOperationId = normalizedOperationId(operationId);
        if (!hasText(normalizedOperationId)) {
            return Optional.empty();
        }
        for (Map<String, Object> action : mutableMapList(data.get("controlActions"))) {
            if (normalizedOperationId.equals(normalizedOperationId(action.get("operationId")))) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    static boolean sameControlAction(
            Map<String, Object> existingAction,
            String actionId,
            Map<String, Object> target,
            Map<String, Object> inputs) {
        Map<String, Object> existingTarget = mapValue(existingAction.get("target"));
        if (existingTarget.isEmpty()) {
            existingTarget = Map.of("scope", firstText(existingAction.get("targetScope"), "AGENT"));
        }
        return Objects.equals(text(existingAction.get("actionId")), firstText(actionId, "control-action"))
                && Objects.equals(existingTarget, mapValue(target))
                && Objects.equals(mapValue(existingAction.get("inputs")), mapValue(inputs));
    }

    static List<Map<String, Object>> evidenceEventsForControlAction(Map<String, Object> action) {
        if (action == null || action.isEmpty()) {
            return List.of();
        }
        String operationId = normalizedOperationId(firstText(action.get("operationId"), action.get("idempotencyKey")));
        if (!hasText(operationId)) {
            operationId = firstText(action.get("actionId"), "control-action");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "control-action:" + operationId + ":remote_domain_control_action_applied");
        event.put("category", "domain-control-action");
        event.put("eventType", "remote_domain_control_action_applied");
        event.put("simTimeEpochMs", action.get("appliedAtEpochMs"));
        event.put("correlationId", operationId);
        event.put("source", "banking-remote-domain");
        event.put("outcome", "APPLIED");
        event.put("severity", "INFO");
        event.put("payload", action);
        return List.of(event);
    }

    private static Map<String, Object> domainSystemWorkOutcome(
            Map<String, Object> item,
            String outcome,
            List<Map<String, Object>> events) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        String workKind = workKind(item);
        result.put("workId", firstText(item != null ? item.get("workId") : null, "domain-system-work"));
        result.put("kind", workKind);
        result.put("occurrenceKey", firstText(item != null ? item.get("occurrenceKey") : null, null));
        result.put("outcome", firstText(outcome, "SKIPPED"));
        result.put("scheduledForEpochMs", item != null ? item.get("scheduledForEpochMs") : null);
        result.put("details", Map.of(
                "description", firstText(item != null ? item.get("description") : null, workKind),
                "evidenceEventCount", events == null ? 0 : events.size()));
        return result;
    }

    Map<String, Object> lastControlActionResult() {
        return mapValue(data.get("lastControlAction"));
    }

    List<Map<String, Object>> lastEvidenceEvents() {
        if (responseEvidenceEvents != null) {
            return mutableMapList(responseEvidenceEvents);
        }
        return mutableMapList(data.get("lastEvidenceEvents"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mutableMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
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

    private static BankingAccountView accountView(Map<String, Object> account) {
        if (account == null || account.isEmpty()) {
            return null;
        }
        return new BankingAccountView(
                longValue(account.get("id")),
                text(account.get("name")),
                text(account.get("productCode")),
                doubleValue(account.get("balance")),
                doubleValue(account.get("limit")),
                integerValue(account.get("bsb")),
                hasText(text(account.get("currency"))) ? text(account.get("currency")).toUpperCase(Locale.ROOT) : "AUD");
    }

    private static List<Map<String, Object>> applyMonthlyInterestAccrual(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        double annualRate = firstPositiveDouble(
                payload.get("annualRate"),
                payload.get("interestRate"),
                payload.get("apr"),
                0.03d);
        double monthlyRate = annualRate / 12.0d;
        double openingTotalBalance = 0.0d;
        double totalInterest = 0.0d;
        List<Map<String, Object>> accountEntries = new ArrayList<>();
        for (Map<String, Object> account : accounts) {
            if (!isDepositInterestAccount(account)) {
                continue;
            }
            double openingBalance = roundCents(doubleOrZero(account.get("balance")));
            if (openingBalance <= 0.0d) {
                continue;
            }
            double interestAmount = roundCents(openingBalance * monthlyRate);
            if (interestAmount < 0.01d) {
                continue;
            }
            double newBalance = roundCents(openingBalance + interestAmount);
            account.put("balance", newBalance);
            account.put("lastInterestAccruedAt", currentTime.toOffsetDateTime().toString());
            openingTotalBalance = roundCents(openingTotalBalance + openingBalance);
            totalInterest = roundCents(totalInterest + interestAmount);

            LinkedHashMap<String, Object> accountEntry = new LinkedHashMap<>();
            accountEntry.put("accountId", firstText(account.get("accountId"), account.get("id")));
            accountEntry.put("accountName", account.get("name"));
            accountEntry.put("productCode", account.get("productCode"));
            accountEntry.put("productCategory", account.get("productCategory"));
            accountEntry.put("openingBalance", openingBalance);
            accountEntry.put("interestAmount", interestAmount);
            accountEntry.put("newBalance", newBalance);
            accountEntry.put("currency", firstText(account.get("currency"), "AUD"));
            accountEntries.add(accountEntry);
        }
        if (accountEntries.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "remote_domain_interest_accrued");
        event.put("outcome", "SUCCESS");
        event.put("severity", "INFO");
        event.put("workId", item != null ? item.get("workId") : null);
        event.put("occurrenceKey", firstText(item != null ? item.get("occurrenceKey") : null,
                item != null ? item.get("workId") : null));
        event.put("description", firstText(item != null ? item.get("description") : null, "Monthly interest accrual"));
        event.put("dateTime", currentTime.toOffsetDateTime().toString());
        event.put("annualRate", annualRate);
        event.put("monthlyRate", monthlyRate);
        event.put("openingTotalBalance", openingTotalBalance);
        event.put("interestAmount", totalInterest);
        event.put("newTotalBalance", roundCents(openingTotalBalance + totalInterest));
        event.put("affectedAccountCount", accountEntries.size());
        event.put("accountInterest", accountEntries);
        return List.of(event);
    }

    private static List<Map<String, Object>> applyCreditCardInterestAccrual(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            List<Map<String, Object>> transactionPostings,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        List<Map<String, Object>> events = new ArrayList<>();
        String occurrenceKey = firstText(item != null ? item.get("occurrenceKey") : null, item != null ? item.get("workId") : null);
        for (Map<String, Object> account : accounts) {
            if (!matchesCreditCardWorkTarget(account, payload)) {
                continue;
            }
            double openingBalance = doubleOrZero(account.get("balance"));
            double annualRate = firstPositiveDouble(
                    account.get("interestRate"),
                    account.get("apr"),
                    payload.get("annualRate"));
            if (openingBalance >= -0.01d || annualRate <= 0.0d) {
                continue;
            }
            if (hasPosting(transactionPostings, occurrenceKey, "remote_domain_credit_card_interest_charged", account)) {
                continue;
            }
            double interestCharge = roundCents(Math.abs(openingBalance) * (annualRate / 12.0d));
            if (interestCharge < 0.01d) {
                continue;
            }
            double newBalance = roundCents(openingBalance - interestCharge);
            double minPaymentPercentage = firstPositiveDouble(
                    payload.get("minPaymentPercentage"),
                    payload.get("minimumPaymentPercentage"),
                    account.get("minPaymentPercentage"),
                    account.get("minimumPaymentPercentage"),
                    0.04d);
            double minimumPayment = roundCents(Math.abs(newBalance) * minPaymentPercentage);
            account.put("balance", newBalance);
            account.put("currentMinPayment", minimumPayment);
            account.put("lastCreditCardInterestPostedAt", currentTime.toOffsetDateTime().toString());

            LinkedHashMap<String, Object> record = new LinkedHashMap<>();
            record.put("workId", item != null ? item.get("workId") : null);
            record.put("occurrenceKey", occurrenceKey);
            record.put("eventType", "remote_domain_credit_card_interest_charged");
            record.put("description", firstText(item != null ? item.get("description") : null, "Credit Card Monthly Interest"));
            record.put("dateTime", currentTime.toOffsetDateTime().toString());
            record.put("accountId", firstPresent(account.get("accountId"), account.get("id")));
            record.put("runtimeAccountId", account.get("id"));
            record.put("accountName", account.get("name"));
            record.put("productCode", account.get("productCode"));
            record.put("amount", -interestCharge);
            record.put("currency", firstText(account.get("currency"), "AUD"));
            record.put("openingBalance", openingBalance);
            record.put("annualRate", annualRate);
            record.put("monthlyRate", annualRate / 12.0d);
            record.put("interestAmount", interestCharge);
            record.put("newBalance", newBalance);
            record.put("minimumPayment", minimumPayment);
            transactionPostings.add(record);
            events.add(record);
        }
        return events;
    }

    private static List<Map<String, Object>> applyCreditCardRateChange(
            Map<String, Object> state,
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            ZonedDateTime eventTime) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        BankingClockDrivenRateChangePlan.Snapshot scheduledSnapshot =
                BankingClockDrivenRateChangePlan.requireSnapshot(payload.get("planSnapshot"));
        BankingClockDrivenRateChangePlan.Snapshot installedSnapshot =
                BankingClockDrivenRateChangePlan.requireSnapshot(state.get("clockDrivenInterventionPlan"));
        if (!scheduledSnapshot.equals(installedSnapshot)) {
            throw new IllegalArgumentException(
                    "Scheduled rate-change facts contradict the installed intervention plan");
        }
        Map<String, Object> plan = installedSnapshot.plan();
        String workId = text(item != null ? item.get("workId") : null);
        String occurrenceKey = text(item != null ? item.get("occurrenceKey") : null);
        long scheduledForEpochMs = longValue(item != null ? item.get("scheduledForEpochMs") : null);
        long effectiveAtEpochMs = longValue(plan.get("effectiveAtEpochMs"));
        long appliedAtEpochMs = eventTime.toInstant().toEpochMilli();
        if (!Objects.equals(workId, text(plan.get("workId")))
                || !Objects.equals(
                        occurrenceKey,
                        "banking|clock-driven-rate-change|" + text(plan.get("mutationOperationId")))
                || scheduledForEpochMs != longValue(plan.get("scheduledForEpochMs"))
                || effectiveAtEpochMs != scheduledForEpochMs
                || appliedAtEpochMs < effectiveAtEpochMs) {
            throw new IllegalArgumentException(
                    "Scheduled rate-change identity contradicts the installed intervention plan");
        }

        String operationId = text(plan.get("mutationOperationId"));
        String productCode = text(plan.get("productCode"));
        String oldRate = BankingClockDrivenRateChangePlan.canonicalDecimal(
                plan.get("expectedPreviousRate"), "expectedPreviousRate");
        String newRate = BankingClockDrivenRateChangePlan.canonicalDecimal(plan.get("newRate"), "newRate");
        List<Map<String, Object>> eligibleAccounts = accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(text(account.get("productCode"))))
                .toList();
        List<String> affectedAccountIds = eligibleAccounts.stream()
                .map(account -> firstText(account.get("accountId"), account.get("id")))
                .toList();
        List<Map<String, Object>> appliedOperations = mutableMapList(state.get("appliedInterventionOperations"));
        for (Map<String, Object> existing : appliedOperations) {
            if (!operationId.equals(text(existing.get("operationId")))) {
                continue;
            }
            Long existingAppliedAtEpochMs = longValue(existing.get("appliedAtEpochMs"));
            if (existingAppliedAtEpochMs == null
                    || existingAppliedAtEpochMs < effectiveAtEpochMs
                    || existingAppliedAtEpochMs > appliedAtEpochMs
                    || !rateChangeOperation(
                                    installedSnapshot,
                                    plan,
                                    workId,
                                    occurrenceKey,
                                    scheduledForEpochMs,
                                    effectiveAtEpochMs,
                                    existingAppliedAtEpochMs,
                                    affectedAccountIds)
                            .equals(existing)) {
                throw new IllegalArgumentException(
                        "Rate-change operationId was already used with contradictory facts");
            }
            for (Map<String, Object> account : eligibleAccounts) {
                validateAppliedRateChangeAccount(
                        account,
                        newRate,
                        installedSnapshot.planDigest(),
                        operationId,
                        existingAppliedAtEpochMs);
            }
            return rateChangeEvents(
                    state,
                    plan,
                    installedSnapshot.planDigest(),
                    existing,
                    existingAppliedAtEpochMs,
                    eventTime.getZone());
        }

        Map<String, Object> operation = rateChangeOperation(
                installedSnapshot,
                plan,
                workId,
                occurrenceKey,
                scheduledForEpochMs,
                effectiveAtEpochMs,
                appliedAtEpochMs,
                affectedAccountIds);

        for (Map<String, Object> account : eligibleAccounts) {
            String currentInterestRate = BankingClockDrivenRateChangePlan.canonicalDecimal(
                    account.get("interestRate"), "account.interestRate");
            String currentApr = BankingClockDrivenRateChangePlan.canonicalDecimal(
                    account.get("apr"), "account.apr");
            if (!oldRate.equals(currentInterestRate)
                    || !oldRate.equals(currentApr)
                    || !installedSnapshot.planDigest().equals(text(account.get("ratePlanDigest")))) {
                throw new IllegalArgumentException(
                        "Eligible account rate does not match the plan's expected previous rate");
            }
        }
        for (Map<String, Object> account : eligibleAccounts) {
            account.put("interestRate", newRate);
            account.put("apr", newRate);
            account.put("rateChangedAtEpochMs", appliedAtEpochMs);
            account.put("rateChangeOperationId", operationId);
        }

        appliedOperations.add(operation);
        state.put("appliedInterventionOperations", appliedOperations);
        return rateChangeEvents(
                state,
                plan,
                installedSnapshot.planDigest(),
                operation,
                appliedAtEpochMs,
                eventTime.getZone());
    }

    private static Map<String, Object> rateChangeOperation(
            BankingClockDrivenRateChangePlan.Snapshot snapshot,
            Map<String, Object> plan,
            String workId,
            String occurrenceKey,
            long scheduledForEpochMs,
            long effectiveAtEpochMs,
            long appliedAtEpochMs,
            List<String> affectedAccountIds) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", plan.get("mutationOperationId"));
        operation.put("planDigest", snapshot.planDigest());
        operation.put("interventionId", plan.get("interventionId"));
        operation.put("workId", workId);
        operation.put("occurrenceKey", occurrenceKey);
        operation.put("scheduledForEpochMs", scheduledForEpochMs);
        operation.put("effectiveAtEpochMs", effectiveAtEpochMs);
        operation.put("appliedAtEpochMs", appliedAtEpochMs);
        operation.put("affectedAccountCount", affectedAccountIds.size());
        operation.put("affectedAccountIds", List.copyOf(affectedAccountIds));
        operation.put("outcome", affectedAccountIds.isEmpty() ? "SKIPPED" : "APPLIED");
        return operation;
    }

    private static List<Map<String, Object>> rateChangeEvents(
            Map<String, Object> state,
            Map<String, Object> plan,
            String planDigest,
            Map<String, Object> operation,
            long appliedAtEpochMs,
            java.time.ZoneId zone) {
        List<String> affectedAccountIds = strictStringList(
                operation.get("affectedAccountIds"), "affectedAccountIds");
        if (affectedAccountIds.isEmpty()) {
            return List.of();
        }
        String operationId = text(operation.get("operationId"));
        String customerId = firstText(state.get("agentId"), state.get("bankingCustomerId"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "remote_domain_rate_changed");
        event.put("businessEventId", operationId + "|customer:" + customerId);
        event.put("occurrenceKey", operation.get("occurrenceKey"));
        event.put("workId", operation.get("workId"));
        event.put("simulationRunId", plan.get("simulationRunId"));
        event.put("customerId", customerId);
        event.put("interventionId", plan.get("interventionId"));
        event.put("operationId", operationId);
        event.put("planDigest", planDigest);
        event.put("productCode", plan.get("productCode"));
        event.put("oldRate", plan.get("expectedPreviousRate"));
        event.put("newRate", plan.get("newRate"));
        event.put("scheduledForEpochMs", operation.get("scheduledForEpochMs"));
        event.put("effectiveAtEpochMs", operation.get("effectiveAtEpochMs"));
        event.put("appliedAtEpochMs", appliedAtEpochMs);
        event.put("dateTime", ZonedDateTime.ofInstant(Instant.ofEpochMilli(appliedAtEpochMs), zone)
                .toOffsetDateTime().toString());
        event.put("affectedAccountCount", affectedAccountIds.size());
        event.put("affectedAccountIds", List.copyOf(affectedAccountIds));
        event.put("outcome", "SUCCESS");
        event.put("severity", "INFO");
        return List.of(event);
    }

    private static boolean hasAppliedRateChange(Map<String, Object> state, Map<String, Object> item) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        Object rawSnapshot = payload.get("planSnapshot");
        if (rawSnapshot == null) {
            return false;
        }
        String operationId = text(BankingClockDrivenRateChangePlan.requireSnapshot(rawSnapshot)
                .plan().get("mutationOperationId"));
        return mutableMapList(state.get("appliedInterventionOperations")).stream()
                .anyMatch(operation -> operationId.equals(text(operation.get("operationId"))));
    }

    private static List<String> strictStringList(Object raw, String field) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> normalized = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String stringValue)
                    || stringValue.isBlank()
                    || !stringValue.equals(stringValue.trim())) {
                throw new IllegalArgumentException(field + " must contain nonblank canonical strings");
            }
            normalized.add(stringValue);
        }
        return List.copyOf(normalized);
    }

    private static void validateAppliedRateChangeAccount(
            Map<String, Object> account,
            String expectedRate,
            String planDigest,
            String operationId,
            long scheduledForEpochMs) {
        if (!expectedRate.equals(BankingClockDrivenRateChangePlan.canonicalDecimal(
                        account.get("interestRate"), "account.interestRate"))
                || !expectedRate.equals(BankingClockDrivenRateChangePlan.canonicalDecimal(
                        account.get("apr"), "account.apr"))
                || !planDigest.equals(text(account.get("ratePlanDigest")))
                || !operationId.equals(text(account.get("rateChangeOperationId")))
                || scheduledForEpochMs != longValue(account.get("rateChangedAtEpochMs"))) {
            throw new IllegalArgumentException(
                    "Applied rate-change receipt contradicts the committed customer state");
        }
    }

    private static void validateOrInitializeAccountRate(
            Map<String, Object> account,
            String field,
            String expectedRate) {
        validateAccountRate(account, field, expectedRate);
        account.put(field, expectedRate);
    }

    private static void validateAccountRate(
            Map<String, Object> account,
            String field,
            String expectedRate) {
        Object raw = account.get(field);
        if (raw != null
                && !expectedRate.equals(BankingClockDrivenRateChangePlan.canonicalDecimal(raw, "account." + field))) {
            throw new IllegalArgumentException(
                    "Eligible account " + field + " contradicts the launch-pinned previous rate");
        }
    }

    private static List<Map<String, Object>> applyLoanInterestAccrual(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            List<Map<String, Object>> transactionPostings,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        List<Map<String, Object>> events = new ArrayList<>();
        String occurrenceKey = firstText(item != null ? item.get("occurrenceKey") : null, item != null ? item.get("workId") : null);
        for (Map<String, Object> account : accounts) {
            if (!matchesLoanWorkTarget(account, payload)) {
                continue;
            }
            double openingBalance = doubleOrZero(account.get("balance"));
            double annualRate = firstPositiveDouble(payload.get("annualRate"), account.get("interestRate"), account.get("apr"));
            if (openingBalance >= -0.01d || annualRate <= 0.0d) {
                continue;
            }
            if (hasPosting(transactionPostings, occurrenceKey, "remote_domain_loan_interest_accrued", account)) {
                continue;
            }
            double interestCharge = roundCents(Math.abs(openingBalance) * (annualRate / 12.0d));
            if (interestCharge < 0.01d) {
                continue;
            }
            double repaymentAmount = 0.0d;
            if (isHomeLoanAccount(account)) {
                double repaymentBase = firstPositiveDouble(
                        payload.get("repaymentBaseAmount"),
                        payload.get("baseRepaymentAmount"),
                        payload.get("repaymentAmount"),
                        account.get("repaymentBaseAmount"),
                        600.0d);
                repaymentAmount = roundCents(repaymentBase + interestCharge);
            }
            double newBalance = roundCents(openingBalance - interestCharge + repaymentAmount);
            account.put("balance", newBalance);
            account.put("lastLoanInterestAccruedAt", currentTime.toOffsetDateTime().toString());
            if (repaymentAmount > 0.0d) {
                account.put("lastLoanRepaymentAppliedAt", currentTime.toOffsetDateTime().toString());
            }

            LinkedHashMap<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "remote_domain_loan_interest_accrued");
            event.put("outcome", "SUCCESS");
            event.put("severity", "INFO");
            event.put("workId", item != null ? item.get("workId") : null);
            event.put("occurrenceKey", occurrenceKey);
            event.put("description", firstText(item != null ? item.get("description") : null, "Loan monthly interest"));
            event.put("dateTime", currentTime.toOffsetDateTime().toString());
            event.put("accountId", firstPresent(account.get("accountId"), account.get("id")));
            event.put("runtimeAccountId", account.get("id"));
            event.put("accountName", account.get("name"));
            event.put("productCode", account.get("productCode"));
            event.put("productCategory", account.get("productCategory"));
            event.put("loanKind", isHomeLoanAccount(account) ? "HOME_LOAN" : "STUDENT_LOAN");
            event.put("currency", firstText(account.get("currency"), "AUD"));
            event.put("openingBalance", openingBalance);
            event.put("annualRate", annualRate);
            event.put("monthlyRate", annualRate / 12.0d);
            event.put("interestAmount", interestCharge);
            event.put("repaymentAmount", repaymentAmount);
            event.put("principalReductionAmount", roundCents(Math.max(0.0d, repaymentAmount - interestCharge)));
            event.put("newBalance", newBalance);
            transactionPostings.add(event);
            events.add(event);
        }
        return events;
    }

    private static List<Map<String, Object>> applyCreditCardStatementClose(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            List<Map<String, Object>> creditCardBills,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        List<Map<String, Object>> events = new ArrayList<>();
        String occurrenceKey = firstText(item != null ? item.get("occurrenceKey") : null, item != null ? item.get("workId") : null);
        for (Map<String, Object> account : accounts) {
            if (!matchesCreditCardWorkTarget(account, payload)) {
                continue;
            }
            if (hasBill(creditCardBills, occurrenceKey, account)) {
                continue;
            }
            double closingBalance = roundCents(doubleOrZero(account.get("balance")));
            double minPaymentPercentage = firstPositiveDouble(
                    payload.get("minPaymentPercentage"),
                    payload.get("minimumPaymentPercentage"),
                    account.get("minPaymentPercentage"),
                    account.get("minimumPaymentPercentage"),
                    0.04d);
            LocalDate statementEnd = firstDate(
                    payload.get("statementEndDate"),
                    account.get("statementEndDate"),
                    currentTime.toLocalDate());
            LocalDate statementStart = firstDate(
                    payload.get("statementStartDate"),
                    account.get("statementStartDate"),
                    statementEnd.minusDays(29));
            int dueDays = Math.max(1, intValue(firstPresent(payload.get("dueDays"), 14)));
            LocalDate dueDate = statementEnd.plusDays(dueDays);
            double minimumPayment = roundCents(Math.max(0.0d, -closingBalance) * minPaymentPercentage);

            LinkedHashMap<String, Object> bill = new LinkedHashMap<>();
            bill.put("eventType", "remote_domain_credit_card_statement_closed");
            bill.put("outcome", "SUCCESS");
            bill.put("severity", "INFO");
            bill.put("billId", safeBillId(occurrenceKey, account));
            bill.put("workId", item != null ? item.get("workId") : null);
            bill.put("occurrenceKey", occurrenceKey);
            bill.put("scheduledForEpochMs", item != null ? item.get("scheduledForEpochMs") : null);
            bill.put("customerId", account.get("customerId"));
            bill.put("accountId", firstPresent(account.get("accountId"), account.get("id")));
            bill.put("runtimeAccountId", account.get("id"));
            bill.put("accountName", account.get("name"));
            bill.put("productCode", account.get("productCode"));
            bill.put("closingAmount", closingBalance);
            bill.put("minimumAmountDue", minimumPayment);
            bill.put("currency", firstText(account.get("currency"), "AUD"));
            bill.put("statementStartDate", statementStart.toString());
            bill.put("statementEndDate", statementEnd.toString());
            bill.put("dueDate", dueDate.toString());
            bill.put("closedAt", currentTime.toOffsetDateTime().toString());
            if (closingBalance < -0.01d) {
                creditCardBills.add(bill);
            }
            account.put("currentMinPayment", minimumPayment);
            account.put("lastStatementClosedAt", currentTime.toOffsetDateTime().toString());
            LocalDate nextStatementStart = statementEnd.plusDays(1);
            account.put("statementStartDate", nextStatementStart.toString());
            account.put("statementEndDate", nextStatementStart.plusDays(29).toString());
            events.add(bill);
        }
        return events;
    }

    private static boolean matchesCreditCardWorkTarget(Map<String, Object> account, Map<String, Object> payload) {
        if (!isCreditCardAccount(account)) {
            return false;
        }
        String accountName = firstText(payload.get("accountName"), payload.get("name"));
        String accountId = firstText(payload.get("accountId"), payload.get("id"));
        String productCode = firstText(payload.get("productCode"), null);
        if (hasText(accountId) && !matchesAccountIdentifier(account, accountId)) {
            return false;
        }
        if (hasText(accountName) && !matchesAccountReference(account, accountName)) {
            return false;
        }
        return !hasText(productCode) || productCode.equalsIgnoreCase(text(account.get("productCode")));
    }

    private static boolean isCreditCardAccount(Map<String, Object> account) {
        String productCode = text(account != null ? account.get("productCode") : null);
        String category = firstText(account != null ? account.get("productCategory") : null,
                account != null ? account.get("category") : null);
        return (hasText(productCode) && productCode.trim().toUpperCase(Locale.ROOT).startsWith("CC"))
                || "CRED_AND_CHRG_CARDS".equalsIgnoreCase(category);
    }

    private static boolean isDepositInterestAccount(Map<String, Object> account) {
        if (isCreditCardAccount(account)) {
            return false;
        }
        String productCode = text(account != null ? account.get("productCode") : null).trim().toUpperCase(Locale.ROOT);
        String category = firstText(account != null ? account.get("productCategory") : null,
                account != null ? account.get("category") : null).trim().toUpperCase(Locale.ROOT);
        if (productCode.startsWith("HL")
                || productCode.startsWith("SL")
                || productCode.startsWith("CL")
                || productCode.startsWith("BL")
                || "RESIDENTIAL_MORTGAGES".equals(category)
                || "PERS_LOANS".equals(category)
                || "BUSINESS_LOANS".equals(category)) {
            return false;
        }
        return productCode.startsWith("TS")
                || productCode.startsWith("SAV")
                || productCode.startsWith("TD")
                || "TRANS_AND_SAVINGS_ACCOUNTS".equals(category)
                || "TERM_DEPOSITS".equals(category);
    }

    private static boolean matchesLoanWorkTarget(Map<String, Object> account, Map<String, Object> payload) {
        if (!isLoanInterestAccount(account)) {
            return false;
        }
        String accountName = firstText(payload.get("accountName"), payload.get("name"));
        String accountId = firstText(payload.get("accountId"), payload.get("id"));
        String productCode = firstText(payload.get("productCode"), null);
        if (hasText(accountId) && !matchesAccountIdentifier(account, accountId)) {
            return false;
        }
        if (hasText(accountName) && !matchesAccountReference(account, accountName)) {
            return false;
        }
        return !hasText(productCode) || productCode.equalsIgnoreCase(text(account.get("productCode")));
    }

    private static boolean isLoanInterestAccount(Map<String, Object> account) {
        return isHomeLoanAccount(account) || isStudentLoanAccount(account);
    }

    private static boolean isHomeLoanAccount(Map<String, Object> account) {
        String productCode = text(account != null ? account.get("productCode") : null).trim().toUpperCase(Locale.ROOT);
        String category = firstText(account != null ? account.get("productCategory") : null,
                account != null ? account.get("category") : null).trim().toUpperCase(Locale.ROOT);
        return "HL001".equals(productCode) || "RESIDENTIAL_MORTGAGES".equals(category);
    }

    private static boolean isStudentLoanAccount(Map<String, Object> account) {
        String productCode = text(account != null ? account.get("productCode") : null).trim().toUpperCase(Locale.ROOT);
        return "SL001".equals(productCode);
    }

    private static boolean hasPosting(
            List<Map<String, Object>> transactionPostings,
            String occurrenceKey,
            String eventType,
            Map<String, Object> account) {
        if (!hasText(occurrenceKey)) {
            return false;
        }
        for (Map<String, Object> posting : transactionPostings) {
            if (occurrenceKey.equals(text(posting.get("occurrenceKey")))
                    && eventType.equals(text(posting.get("eventType")))
                    && postingMatchesAccount(posting, account)) {
                return true;
            }
        }
        return false;
    }

    private static boolean postingMatchesAccount(Map<String, Object> posting, Map<String, Object> account) {
        return matchesAccountIdentifier(account, text(posting.get("accountId")))
                || matchesAccountIdentifier(account, text(posting.get("runtimeAccountId")));
    }

    private static boolean hasBill(List<Map<String, Object>> creditCardBills, String occurrenceKey, Map<String, Object> account) {
        if (!hasText(occurrenceKey)) {
            return false;
        }
        for (Map<String, Object> bill : creditCardBills) {
            if (occurrenceKey.equals(text(bill.get("occurrenceKey")))
                    && postingMatchesAccount(bill, account)) {
                return true;
            }
        }
        return false;
    }

    private static String safeBillId(String occurrenceKey, Map<String, Object> account) {
        String source = firstText(occurrenceKey, "credit-card-statement") + "-" + text(account.get("id"));
        return source.replaceAll("[^A-Za-z0-9_.:-]", "-");
    }

    private static String workKind(Map<String, Object> item) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        String kind = firstText(payload.get("workKind"), payload.get("kind"));
        if (!hasText(kind)) {
            kind = firstText(item != null ? item.get("kind") : null, item != null ? item.get("workType") : null);
        }
        return hasText(kind) ? kind.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static BankingDoubleEntryPosting postingForDueWork(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            ZonedDateTime currentTime) {
        BankingPlannedTransactionContract contract = transactionContract(item);
        if (contract == null || contract.type() == null || contract.amount() == null) {
            return null;
        }
        TransactionInstruction instruction = transactionInstruction(contract, accounts);
        if (instruction == null) {
            return null;
        }
        BankingChannelPostingFactory.validateSufficientFunds(instruction);
        return BankingChannelPostingFactory.create(
                channelType(contract),
                instruction,
                eventTime(item, currentTime));
    }

    private static BankingPlannedTransactionContract transactionContract(Map<String, Object> item) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        if (payload.isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Object> scheduledWork = new LinkedHashMap<>();
        putIfPresent(scheduledWork, "description", firstText(item != null ? item.get("description") : null,
                payload.get("description")));
        putIfPresent(scheduledWork, "frequency", payload.get("frequency"));
        putIfPresent(scheduledWork, "time", payload.get("time"));
        putIfPresent(scheduledWork, "startDate", payload.get("startDate"));
        putIfPresent(scheduledWork, "endDate", payload.get("endDate"));

        LinkedHashMap<String, Object> contractPayload = new LinkedHashMap<>(payload);
        contractPayload.remove("kind");
        contractPayload.remove("description");
        contractPayload.remove("frequency");
        contractPayload.remove("time");
        contractPayload.remove("startDate");
        contractPayload.remove("endDate");
        scheduledWork.put("payload", contractPayload);
        return BankingPlannedTransactionContractSupport.fromScheduledWork(scheduledWork);
    }

    private static TransactionInstruction transactionInstruction(
            BankingPlannedTransactionContract contract,
            List<Map<String, Object>> accounts) {
        Account fromAccount = null;
        Account toAccount = null;
        if (contract.type() != TransactionInstructionType.DEPOSIT) {
            fromAccount = resolveRuntimeAccount(accounts, contract.fromAccounts());
            if (fromAccount == null && usesDefaultSourceAccount(contract.type())) {
                fromAccount = firstRuntimeAccount(accounts);
            }
        }
        if (contract.type() == TransactionInstructionType.DEPOSIT || contract.type() == TransactionInstructionType.TRANSFER) {
            toAccount = resolveRuntimeAccount(accounts, contract.toAccounts());
            if (toAccount == null && contract.type() == TransactionInstructionType.DEPOSIT) {
                toAccount = firstRuntimeAccount(accounts);
            }
        }
        return new TransactionInstruction(
                contract.type(),
                contract.amount(),
                contract.exactAmount(),
                fromAccount,
                toAccount,
                firstText(contract.resolvedDescription(), "banking scheduled transaction"));
    }

    private static boolean usesDefaultSourceAccount(TransactionInstructionType type) {
        return type == TransactionInstructionType.PAYMENT
                || type == TransactionInstructionType.DIRECTDEBIT
                || type == TransactionInstructionType.TRANSFER
                || type == TransactionInstructionType.WITHDRAWAL;
    }

    private static Account resolveRuntimeAccount(List<Map<String, Object>> accounts, List<String> references) {
        if (accounts == null || accounts.isEmpty() || references == null || references.isEmpty()) {
            return null;
        }
        for (String reference : references) {
            if (!hasText(reference)) {
                continue;
            }
            for (Map<String, Object> account : accounts) {
                if (matchesAccountReference(account, reference)) {
                    return runtimeAccount(account);
                }
            }
        }
        return null;
    }

    private static Account firstRuntimeAccount(List<Map<String, Object>> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        return runtimeAccount(accounts.getFirst());
    }

    private static Account runtimeAccount(Map<String, Object> account) {
        Product product = new Product(null, text(account.get("productCode")), text(account.get("name")), null);
        if (isCreditCardAccount(account)) {
            CreditAccount runtimeAccount = new CreditAccount(
                    product,
                    longValue(account.get("id")),
                    doubleValue(account.get("balance")),
                    doubleValue(account.get("limit")),
                    List.of());
            runtimeAccount.setProductCode(text(account.get("productCode")));
            return runtimeAccount;
        }
        TransactionAccount runtimeAccount = new TransactionAccount(
                product,
                longValue(account.get("id")),
                doubleValue(account.get("balance")),
                integerValue(account.get("bsb")),
                hasText(text(account.get("currency"))) ? text(account.get("currency")) : "AUD",
                List.of());
        runtimeAccount.setProductCode(text(account.get("productCode")));
        return runtimeAccount;
    }

    private static boolean matchesAccountReference(Map<String, Object> account, String reference) {
        String candidate = reference.trim();
        return candidate.equalsIgnoreCase(text(account.get("name")))
                || candidate.equalsIgnoreCase(text(account.get("productCode")))
                || candidate.equals(text(account.get("accountId")))
                || candidate.equals(text(account.get("id")));
    }

    private static boolean matchesAccountIdentifier(Map<String, Object> account, String reference) {
        String rawCandidate = text(reference);
        if (!hasText(rawCandidate)) {
            return false;
        }
        String candidate = rawCandidate.trim();
        return hasText(candidate)
                && (candidate.equals(text(account.get("accountId")))
                        || candidate.equals(text(account.get("id"))));
    }

    private static BankingChannelType channelType(BankingPlannedTransactionContract contract) {
        for (String channelName : contract.permittedChannelNames()) {
            if (!hasText(channelName)) {
                continue;
            }
            String normalized = channelName.trim().replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
            for (BankingChannelType channelType : BankingChannelType.values()) {
                if (channelType.name().replace("_", "").equals(normalized)) {
                    return channelType;
                }
            }
        }
        return BankingChannelType.DIRECT;
    }

    private static ZonedDateTime eventTime(Map<String, Object> item, ZonedDateTime fallback) {
        Long scheduledForEpochMs = longValue(item != null ? item.get("scheduledForEpochMs") : null);
        if (scheduledForEpochMs != null) {
            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(scheduledForEpochMs), fallback.getZone());
        }
        return fallback;
    }

    private static void applyPosting(List<Map<String, Object>> accounts, BankingDoubleEntryPosting posting) {
        for (BankingPostingLeg credit : posting.credits()) {
            applyLeg(accounts, credit, 1.0);
        }
        for (BankingPostingLeg debit : posting.debits()) {
            applyLeg(accounts, debit, -1.0);
        }
    }

    private static boolean isToolCall(Map<String, Object> item) {
        if (item == null) {
            return false;
        }
        if ("TOOL_CALL".equalsIgnoreCase(text(item.get("workType")))) {
            return true;
        }
        Map<String, Object> payload = mapValue(item.get("payload"));
        return "tool-call".equalsIgnoreCase(text(payload.get("kind")));
    }

    private static boolean isMutatingWork(Map<String, Object> item) {
        return !isReadOnlyToolCall(item);
    }

    private static boolean isReadOnlyToolCall(Map<String, Object> item) {
        return isToolCall(item)
                && List.of("get_product_list", "get_customer_accounts").contains(
                        text(mapValue(item.get("payload")).get("toolName")).toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> applyToolCall(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            List<Map<String, Object>> transactionPostings,
            List<Map<String, Object>> creditCardBills,
            ZonedDateTime currentTime) {
        Map<String, Object> payload = mapValue(item.get("payload"));
        String toolName = text(payload.get("toolName"));
        Map<String, Object> arguments = mapValue(payload.get("arguments"));
        if ("get_product_list".equalsIgnoreCase(toolName)) {
            return toolResult(toolName, true, "Product list returned.", Map.of(
                    "products", productCatalog(accounts)));
        }
        if ("get_customer_accounts".equals(toolName)) {
            if (!arguments.isEmpty()) {
                throw new IllegalArgumentException("get_customer_accounts accepts no arguments; the supplied checkpoint scopes the customer");
            }
            List<Map<String, Object>> statements = creditCardBills.stream()
                    .skip(Math.max(0, creditCardBills.size() - 1))
                    .map(bill -> selectedFields(bill, List.of("billId", "accountId", "closingAmount",
                            "minimumAmountDue", "currency", "statementStartDate", "statementEndDate", "dueDate")))
                    .toList();
            return toolResult(toolName, true, "Customer accounts returned from the accepted simulation checkpoint.",
                    Map.of("accounts", customerAccounts(accounts, true), "statements", statements));
        }
        if ("post_double_entry_transaction".equalsIgnoreCase(toolName)) {
            return postDoubleEntryToolCall(item, accounts, transactionPostings, arguments, currentTime);
        }
        return toolResult(toolName, false, "Unsupported remote banking tool: " + toolName, Map.of());
    }

    private static boolean hasAppliedWork(
            Map<String, Object> item,
            List<Map<String, Object>> executionLog,
            List<Map<String, Object>> transactionPostings) {
        String workId = text(item != null ? item.get("workId") : null);
        if (!hasText(workId)) {
            return false;
        }
        for (Map<String, Object> logItem : executionLog) {
            if (sameWorkIdentity(item, logItem) && successfulWorkStatus(logItem.get("status"))) {
                return true;
            }
        }
        for (Map<String, Object> posting : transactionPostings) {
            if (sameWorkIdentity(item, posting)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameWorkIdentity(Map<String, Object> requested, Map<String, Object> applied) {
        String requestedWorkId = text(requested != null ? requested.get("workId") : null);
        if (!hasText(requestedWorkId)
                || !requestedWorkId.equals(text(applied != null ? applied.get("workId") : null))) {
            return false;
        }
        if (!"DOMAIN_SYSTEM_WORK".equalsIgnoreCase(text(requested.get("workType")))) {
            return true;
        }
        String requestedOccurrence = text(requested.get("occurrenceKey"));
        return !hasText(requestedOccurrence) || requestedOccurrence.equals(text(applied.get("occurrenceKey")));
    }

    private static boolean successfulWorkStatus(Object rawStatus) {
        String status = text(rawStatus).toUpperCase(Locale.ROOT);
        return "APPLIED".equals(status)
                || "RECORDED".equals(status)
                || "ALREADY_APPLIED".equals(status);
    }

    private static Map<String, Object> idempotentReplayResult(Map<String, Object> item) {
        Map<String, Object> payload = mapValue(item != null ? item.get("payload") : null);
        String operationName = firstText(
                payload.get("toolName"),
                firstText(item != null ? item.get("workType") : null, "execute-work"));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("idempotentReplay", true);
        values.put("workId", item.get("workId"));
        if (hasText(text(item.get("occurrenceKey")))) {
            values.put("occurrenceKey", item.get("occurrenceKey"));
        }
        return toolResult(operationName, true, "Work already applied.", values);
    }

    private static Map<String, Object> toolEffectReplayEvent(
            Map<String, Object> item,
            ZonedDateTime currentTime) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "remote_domain_tool_effect_replayed");
        event.put("outcome", "NOOP");
        event.put("severity", "INFO");
        event.put("workId", item.get("workId"));
        event.put("toolName", text(mapValue(item.get("payload")).get("toolName")));
        event.put("dateTime", currentTime.toOffsetDateTime().toString());
        return event;
    }

    private static Map<String, Object> readOnlyToolEvent(
            Map<String, Object> item,
            Map<String, Object> result,
            ZonedDateTime currentTime) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "get_customer_accounts".equals(result.get("toolName"))
                ? "remote_domain_customer_accounts_returned" : "remote_domain_product_list_returned");
        event.put("outcome", Boolean.TRUE.equals(result.get("success")) ? "SUCCESS" : "REJECTED");
        event.put("severity", Boolean.TRUE.equals(result.get("success")) ? "INFO" : "WARN");
        event.put("workId", item.get("workId"));
        event.put("toolName", text(mapValue(item.get("payload")).get("toolName")));
        event.put("dateTime", currentTime.toOffsetDateTime().toString());
        return event;
    }

    private static Map<String, Object> postDoubleEntryToolCall(
            Map<String, Object> item,
            List<Map<String, Object>> accounts,
            List<Map<String, Object>> transactionPostings,
            Map<String, Object> arguments,
            ZonedDateTime currentTime) {
        try {
            List<Map<String, Object>> debits = mutableMapList(arguments.get("debits"));
            List<Map<String, Object>> credits = mutableMapList(arguments.get("credits"));
            if (debits.isEmpty() || credits.isEmpty()) {
                throw new IllegalArgumentException("debits and credits must be non-empty lists");
            }
            double debitTotal = sumAmounts(debits);
            double creditTotal = sumAmounts(credits);
            if (Math.abs(debitTotal - creditTotal) > 0.00001d) {
                throw new IllegalArgumentException("double-entry transaction is not balanced");
            }
            List<Map<String, Object>> stagedAccounts = mutableMapList(accounts);
            for (Map<String, Object> debit : debits) {
                applyToolLeg(stagedAccounts, debit, -1.0d);
            }
            for (Map<String, Object> credit : credits) {
                applyToolLeg(stagedAccounts, credit, 1.0d);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("workId", item.get("workId"));
            record.put("occurrenceKey", item.get("occurrenceKey"));
            record.put("description", item.get("description"));
            record.put("toolName", "post_double_entry_transaction");
            record.put("dateTime", currentTime.toOffsetDateTime().toString());
            record.put("debits", List.copyOf(debits));
            record.put("credits", List.copyOf(credits));
            accounts.clear();
            accounts.addAll(stagedAccounts);
            transactionPostings.add(record);
            return toolResult("post_double_entry_transaction", true, "Transaction posted.", Map.of(
                    "transaction", record,
                    "accounts", List.copyOf(accounts)));
        } catch (RuntimeException ex) {
            return toolResult("post_double_entry_transaction", false, ex.getMessage(), Map.of());
        }
    }

    private static void applyToolLeg(List<Map<String, Object>> accounts, Map<String, Object> leg, double direction) {
        Long accountId = longValue(firstPresent(leg.get("accountId"), leg.get("id")));
        String accountReference = firstText(leg.get("accountName"), leg.get("name"));
        Double amount = doubleValue(leg.get("amount"));
        if (amount == null || amount < 0.0d) {
            throw new IllegalArgumentException("transaction leg amount must be a positive number");
        }
        for (Map<String, Object> account : accounts) {
            Long candidateId = longValue(account.get("id"));
            if ((accountId != null && accountId.equals(candidateId))
                    || (hasText(accountReference) && matchesAccountReference(account, accountReference))) {
                account.put("balance", doubleOrZero(account.get("balance")) + (direction * amount));
                return;
            }
        }
        throw new IllegalArgumentException("transaction leg account was not found: "
                + (accountId != null ? accountId : accountReference));
    }

    private static double sumAmounts(List<Map<String, Object>> legs) {
        return legs.stream()
                .map(leg -> doubleValue(leg.get("amount")))
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private static List<Map<String, Object>> productCatalog(List<Map<String, Object>> accounts) {
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        for (Map<String, Object> product : defaultProductCatalog()) {
            putProduct(
                    products,
                    text(product.get("productCode")),
                    text(product.get("name")),
                    text(product.get("productCategory")));
        }
        for (Map<String, Object> account : accounts) {
            String productCode = text(account.get("productCode"));
            if (hasText(productCode)) {
                putProduct(products, productCode, text(account.get("name")), "REMOTE_ACCOUNT_PRODUCT");
            }
        }
        return new ArrayList<>(products.values());
    }

    static List<Map<String, Object>> defaultProductCatalog() {
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        putProduct(products, "TS001", "Standard Saving Product", "TRANS_AND_SAVINGS_ACCOUNTS");
        putProduct(products, "SAV001", "NetBank Saver", "TRANS_AND_SAVINGS_ACCOUNTS");
        putProduct(products, "CC001", "Standard Credit Card", "CRED_AND_CHRG_CARDS");
        putProduct(products, "CC002", "Low Rate credit card", "CRED_AND_CHRG_CARDS");
        putProduct(products, "HL001", "Standard Home Loan", "RESIDENTIAL_MORTGAGES");
        putProduct(products, "SL001", "Student Loan", "PERS_LOANS");
        putProduct(products, "CL001", "Car Loan", "PERS_LOANS");
        putProduct(products, "BL001", "Business Loan", "BUSINESS_LOANS");
        putProduct(products, "TD001", "Term Deposit", "TERM_DEPOSITS");
        return new ArrayList<>(products.values());
    }

    private static void putProduct(
            Map<String, Map<String, Object>> products,
            String productCode,
            String name,
            String category) {
        if (!hasText(productCode)) {
            return;
        }
        products.putIfAbsent(productCode.trim().toUpperCase(Locale.ROOT), Map.of(
                "productCode", productCode.trim(),
                "name", hasText(name) ? name.trim() : productCode.trim(),
                "productCategory", hasText(category) ? category.trim() : "REMOTE_ACCOUNT_PRODUCT",
                "category", hasText(category) ? category.trim() : "REMOTE_ACCOUNT_PRODUCT"));
    }

    private static Map<String, Object> toolResult(
            String toolName,
            boolean success,
            String message,
            Map<String, Object> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("toolName", hasText(toolName) ? toolName : "remote-domain-tool");
        result.put("success", success);
        result.put("message", hasText(message) ? message : "");
        if (values != null) {
            result.putAll(values);
        }
        return result;
    }

    private static void applyLeg(List<Map<String, Object>> accounts, BankingPostingLeg leg, double direction) {
        for (Map<String, Object> account : accounts) {
            Long accountId = longValue(account.get("id"));
            if (accountId != null && accountId.equals(leg.accountId())) {
                account.put("balance", doubleOrZero(account.get("balance")) + (direction * leg.amount()));
                return;
            }
        }
    }

    private static Map<String, Object> postingRecord(Map<String, Object> item, BankingDoubleEntryPosting posting) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("workId", item.get("workId"));
        record.put("occurrenceKey", item.get("occurrenceKey"));
        record.put("description", item.get("description"));
        record.put("scheduledForEpochMs", item.get("scheduledForEpochMs"));
        if (!posting.debits().isEmpty()) {
            record.put("dateTime", posting.debits().get(0).dateTime().toString());
        } else if (!posting.credits().isEmpty()) {
            record.put("dateTime", posting.credits().get(0).dateTime().toString());
        }
        record.put("credits", posting.credits().stream().map(BankingRemoteDomainState::legRecord).toList());
        record.put("debits", posting.debits().stream().map(BankingRemoteDomainState::legRecord).toList());
        return record;
    }

    private static Map<String, Object> legRecord(BankingPostingLeg leg) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("accountId", leg.accountId());
        record.put("bsb", leg.bsb());
        record.put("amount", leg.amount());
        record.put("currency", leg.currency());
        record.put("dateTime", leg.dateTime().toString());
        record.put("description", leg.description());
        record.put("merchantId", leg.merchantId());
        return record;
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

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String firstText(Object first, Object fallback) {
        String value = text(first);
        return hasText(value) ? value : text(fallback);
    }

    private static Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizedOperationId(Object value) {
        String text = text(value);
        return text == null ? "" : text.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        return hasText(text) ? Integer.parseInt(text) : 0;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        return hasText(text) ? Long.parseLong(text) : null;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        return hasText(text) ? Integer.parseInt(text) : null;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        return hasText(text) ? Double.parseDouble(text) : null;
    }

    private static double doubleOrZero(Object value) {
        Double parsed = doubleValue(value);
        return parsed != null ? parsed : 0.0;
    }

    private static double firstPositiveDouble(Object... values) {
        if (values == null) {
            return 0.0d;
        }
        for (Object value : values) {
            Double parsed = doubleValue(value);
            if (parsed != null && parsed > 0.0d) {
                return parsed;
            }
        }
        return 0.0d;
    }

    private static LocalDate firstDate(Object... values) {
        if (values == null) {
            return LocalDate.now();
        }
        for (Object value : values) {
            if (value instanceof LocalDate date) {
                return date;
            }
            if (value instanceof ZonedDateTime dateTime) {
                return dateTime.toLocalDate();
            }
            String text = text(value);
            if (!hasText(text)) {
                continue;
            }
            try {
                String candidate = text.trim();
                if (candidate.length() > 10) {
                    candidate = candidate.substring(0, 10);
                }
                return LocalDate.parse(candidate);
            } catch (DateTimeParseException ignored) {
                // Try the next candidate.
            }
        }
        return LocalDate.now();
    }

    private static double roundCents(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
