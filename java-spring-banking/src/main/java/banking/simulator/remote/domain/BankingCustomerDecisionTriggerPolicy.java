package banking.simulator.remote.domain;

import ai.astraform.remote.domain.openapi.models.RemoteDomainCustomerDecisionTrigger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BankingCustomerDecisionTriggerPolicy {
    static final String POLICY_VERSION = "banking.customer-decision-trigger-policy.v1";
    static final String NUMERIC_ENCODING = "canonical_decimal_string.v1";

    private static final Set<String> ALLOWED_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "CRITICAL");
    private static final Set<String> FORBIDDEN_FACT_FIELDS = Set.of(
            "prompt",
            "systemPrompt",
            "rawProviderText",
            "chainOfThought");
    private static final Set<String> NUMERIC_FACT_FIELDS = Set.of(
            "amount",
            "appliedAtEpochMs",
            "affectedAccountCount",
            "balanceAtReview",
            "closingAmount",
            "currentBalance",
            "feeAmount",
            "interestAmount",
            "effectiveAtEpochMs",
            "minimumAmountDue",
            "newCreditLimit",
            "newRate",
            "oldCreditLimit",
            "oldRate",
            "outstandingAmount",
            "scheduledForEpochMs",
            "threshold",
            "thresholdAmount");
    private static final Set<String> IDENTIFIER_FACT_FIELDS = Set.of(
            "accountId",
            "billId",
            "businessEventId",
            "customerId",
            "interventionId",
            "occurrenceKey",
            "operationId",
            "planDigest",
            "policyId",
            "runtimeAccountId",
            "simulationRunId",
            "transactionId",
            "workId");
    private static final int MAX_FACT_DEPTH = 8;
    private static final int MAX_FACT_TEXT_LENGTH = 512;
    private static final int MAX_FACT_DECIMAL_SCALE = 38;
    private static final BigDecimal MAX_ABSOLUTE_FACT_NUMBER = new BigDecimal("1000000000000000000");
    private static final List<String> SAFE_FACT_FIELDS = List.of(
            "eventType",
            "businessEventId",
            "simulationRunId",
            "customerId",
            "interventionId",
            "operationId",
            "planDigest",
            "occurrenceKey",
            "workId",
            "billId",
            "transactionId",
            "accountId",
            "runtimeAccountId",
            "accountName",
            "productCode",
            "productCategory",
            "scheduledForEpochMs",
            "effectiveAtEpochMs",
            "appliedAtEpochMs",
            "affectedAccountCount",
            "transactionType",
            "amount",
            "closingAmount",
            "minimumAmountDue",
            "outstandingAmount",
            "dueDate",
            "interestAmount",
            "feeAmount",
            "oldCreditLimit",
            "newCreditLimit",
            "oldRate",
            "newRate",
            "policyId",
            "threshold",
            "thresholdAmount",
            "balanceAtReview",
            "currentBalance",
            "statementStartDate",
            "statementEndDate",
            "currency",
            "outcome");

    private static final Map<String, Definition> DEFINITIONS = definitions();
    private static final Map<String, String> EVENT_TRIGGER_TYPES = Map.ofEntries(
            Map.entry("remote_domain_credit_card_statement_closed", "statement_close"),
            Map.entry("remote_domain_payment_due", "payment_due"),
            Map.entry("remote_domain_payment_missed", "payment_missed"),
            Map.entry("remote_domain_credit_card_interest_charged", "interest_charged"),
            Map.entry("remote_domain_loan_interest_accrued", "interest_charged"),
            Map.entry("remote_domain_fee_charged", "fee_charged"),
            Map.entry("remote_domain_credit_limit_changed", "credit_limit_change"),
            Map.entry("remote_domain_rate_changed", "rate_or_policy_change"),
            Map.entry("remote_domain_policy_changed", "rate_or_policy_change"),
            Map.entry("remote_domain_hardship_reviewed", "hardship_threshold_crossed"),
            Map.entry("remote_domain_hardship_threshold_crossed", "hardship_threshold_crossed"),
            Map.entry("remote_domain_liquidity_threshold_crossed", "liquidity_signal"),
            Map.entry("remote_domain_scheduled_review_due", "monthly_review"));

    List<RemoteDomainCustomerDecisionTrigger> evaluate(
            List<Map<String, Object>> evidenceEvents,
            long scheduledForEpochMs,
            Object rawConfiguration) {
        if (evidenceEvents == null || evidenceEvents.isEmpty()) {
            return List.of();
        }
        Map<String, PolicyConfiguration> configuration = resolveConfiguration(rawConfiguration);
        List<RemoteDomainCustomerDecisionTrigger> triggers = new ArrayList<>();
        for (Map<String, Object> event : evidenceEvents) {
            rejectForbiddenFields(event, "event", 0);
            Map<String, Object> rawFacts = eventFacts(event);
            Map<String, Object> facts = sanitizedFacts(rawFacts);
            String sourceEventType = text(facts.get("eventType"));
            String triggerType = EVENT_TRIGGER_TYPES.get(sourceEventType);
            if (triggerType == null) {
                continue;
            }
            Definition definition = DEFINITIONS.get(triggerType);
            PolicyConfiguration policy = configuration.get(triggerType);
            if (definition == null || policy == null || !policy.enabled() || !matches(facts, triggerType, policy)) {
                continue;
            }
            String triggerRef = triggerReference(event, facts);
            if (!hasText(triggerRef)) {
                continue;
            }
            Map<String, Object> payload = sanitizedPayload(facts, triggerType, sourceEventType);
            RemoteDomainCustomerDecisionTrigger trigger = new RemoteDomainCustomerDecisionTrigger(
                    RemoteDomainCustomerDecisionTrigger.SchemaVersionEnum.CUSTOMER_DECISION_TRIGGER_V1,
                    triggerType + ":" + triggerRef,
                    triggerType,
                    triggerRef,
                    eventScheduledForEpochMs(rawFacts, scheduledForEpochMs),
                    policy.priority(),
                    definition.reason(),
                    payload);
            setSchedulingPriority(trigger, policy.priority());
            trigger.setEvidenceRefs(new ArrayList<>(List.of(triggerRef)));
            triggers.add(trigger);
        }
        return List.copyOf(triggers);
    }

    private long eventScheduledForEpochMs(Map<String, Object> facts, long fallback) {
        Object raw = facts == null ? null : firstNonNull(
                facts.get("appliedAtEpochMs"),
                facts.get("effectiveAtEpochMs"),
                facts.get("scheduledForEpochMs"));
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value >= 0 ? value : fallback;
        }
        if (raw != null) {
            try {
                long value = Long.parseLong(String.valueOf(raw));
                return value >= 0 ? value : fallback;
            } catch (NumberFormatException ignored) {
                // The shared admission policy still validates the emitted trigger value.
            }
        }
        return fallback;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static void setSchedulingPriority(
            Object trigger,
            String priority) {
        Method typedSetter = null;
        for (Method method : trigger.getClass().getMethods()) {
            if ("setSchedulingPriority".equals(method.getName()) && method.getParameterCount() == 1) {
                typedSetter = method;
                break;
            }
        }
        if (typedSetter == null) {
            if (trigger instanceof RemoteDomainCustomerDecisionTrigger decisionTrigger) {
                decisionTrigger.putAdditionalProperty("schedulingPriority", priority);
                return;
            }
            throw new IllegalStateException(
                    "Generated decision trigger does not expose schedulingPriority or additional properties");
        }
        if (trigger instanceof RemoteDomainCustomerDecisionTrigger decisionTrigger) {
            Map<String, Object> additionalProperties = decisionTrigger.getAdditionalProperties();
            if (additionalProperties != null) {
                additionalProperties.remove("schedulingPriority");
            }
        }
        try {
            typedSetter.invoke(trigger, schedulingPriorityArgument(typedSetter.getParameterTypes()[0], priority));
        } catch (IllegalAccessException | InvocationTargetException reflectionFailure) {
            throw new IllegalStateException("Unable to set the typed schedulingPriority property", reflectionFailure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object schedulingPriorityArgument(Class<?> parameterType, String priority) {
        if (String.class.equals(parameterType)) {
            return priority;
        }
        if (parameterType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) parameterType.asSubclass(Enum.class), priority);
        }
        throw new IllegalStateException(
                "Unsupported generated schedulingPriority type: " + parameterType.getName());
    }

    Map<String, Object> defaultConfiguration() {
        Map<String, Object> configuration = new LinkedHashMap<>();
        DEFINITIONS.forEach((triggerType, definition) -> configuration.put(
                triggerType,
                policyMap(definition.defaultEnabled(), definition.defaultPriority(), definition.defaultMinimumAmount())));
        return configuration;
    }

    Map<String, Object> normalizedConfiguration(Object rawConfiguration) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        resolveConfiguration(rawConfiguration).forEach((triggerType, policy) -> normalized.put(
                triggerType,
                policyMap(policy.enabled(), policy.priority(), policy.minimumAmount())));
        return normalized;
    }

    private Map<String, PolicyConfiguration> resolveConfiguration(Object rawConfiguration) {
        if (rawConfiguration != null && !(rawConfiguration instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Banking decisionTriggerPolicy must be an object");
        }
        Map<String, Object> overrides = mapValue(rawConfiguration);
        for (String key : overrides.keySet()) {
            if (!DEFINITIONS.containsKey(key)) {
                throw new IllegalArgumentException("Unknown banking decision trigger policy: " + key);
            }
        }
        Map<String, PolicyConfiguration> resolved = new LinkedHashMap<>();
        DEFINITIONS.forEach((triggerType, definition) -> {
            Map<String, Object> override = mapValue(overrides.get(triggerType));
            if (overrides.containsKey(triggerType) && override.isEmpty()) {
                throw new IllegalArgumentException(
                        "Banking decision trigger policy '" + triggerType + "' must be an object");
            }
            Set<String> unknownFields = new java.util.HashSet<>(override.keySet());
            unknownFields.removeAll(Set.of("enabled", "priority", "minimumAmount"));
            if (!unknownFields.isEmpty()) {
                throw new IllegalArgumentException(
                        "Banking decision trigger policy '" + triggerType + "' has unknown field '"
                                + unknownFields.iterator().next() + "'");
            }
            boolean enabled = override.containsKey("enabled")
                    ? booleanValue(override.get("enabled"), triggerType + ".enabled")
                    : definition.defaultEnabled();
            String priority = override.containsKey("priority")
                    ? text(override.get("priority")).toUpperCase(Locale.ROOT)
                    : definition.defaultPriority();
            if (!ALLOWED_PRIORITIES.contains(priority)) {
                throw new IllegalArgumentException(
                        "Banking decision trigger policy '" + triggerType + "' has invalid priority");
            }
            BigDecimal minimumAmount = override.containsKey("minimumAmount")
                    ? decimalValue(override.get("minimumAmount"), triggerType + ".minimumAmount")
                    : definition.defaultMinimumAmount();
            if (minimumAmount.signum() < 0) {
                throw new IllegalArgumentException(
                        "Banking decision trigger policy '" + triggerType + "' minimumAmount must not be negative");
            }
            resolved.put(triggerType, new PolicyConfiguration(enabled, priority, minimumAmount));
        });
        return Map.copyOf(resolved);
    }

    private boolean matches(
            Map<String, Object> facts,
            String triggerType,
            PolicyConfiguration policy) {
        if ("hardship_threshold_crossed".equals(triggerType)
                && "remote_domain_hardship_reviewed".equals(text(facts.get("eventType")))) {
            return "APPROVED".equalsIgnoreCase(text(facts.get("outcome")));
        }
        List<String> amountFields = switch (triggerType) {
            case "statement_close" -> List.of("minimumAmountDue", "outstandingAmount", "closingAmount");
            case "payment_due", "payment_missed" -> List.of("outstandingAmount", "minimumAmountDue", "amount");
            case "interest_charged" -> List.of("interestAmount", "amount");
            case "fee_charged" -> List.of("feeAmount", "amount");
            default -> List.of();
        };
        if (amountFields.isEmpty()) {
            return true;
        }
        BigDecimal amount = firstAmount(facts, amountFields);
        return amount != null && amount.abs().compareTo(policy.minimumAmount()) >= 0;
    }

    private Map<String, Object> sanitizedPayload(
            Map<String, Object> facts,
            String triggerType,
            String sourceEventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyVersion", POLICY_VERSION);
        payload.put("numericEncoding", NUMERIC_ENCODING);
        payload.put("normalizedEventType", triggerType);
        payload.put("sourceEventType", sourceEventType);
        for (String key : SAFE_FACT_FIELDS) {
            Object value = facts.get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }
        return payload;
    }

    private String triggerReference(Map<String, Object> event, Map<String, Object> facts) {
        String base = firstText(
                facts.get("businessEventId"),
                facts.get("occurrenceKey"),
                facts.get("transactionId"),
                facts.get("billId"),
                facts.get("workId"),
                eventText(event, "eventId"),
                eventText(event, "correlationId"));
        if (!hasText(base)) {
            return "";
        }
        String subject = firstText(facts.get("accountId"), facts.get("runtimeAccountId"));
        return hasText(subject) ? base + "|account:" + subject : base;
    }

    private Map<String, Object> eventFacts(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> payload = mapValue(event.get("payload"));
        return payload.isEmpty() ? new LinkedHashMap<>(event) : payload;
    }

    private Map<String, Object> sanitizedFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        rejectForbiddenFields(facts, "event facts", 0);
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (String field : SAFE_FACT_FIELDS) {
            if (!facts.containsKey(field) || facts.get(field) == null) {
                continue;
            }
            Object value = facts.get(field);
            sanitized.put(field, NUMERIC_FACT_FIELDS.contains(field)
                    ? canonicalDecimalString(numericFact(value, field))
                    : textFact(value, field));
        }
        return Map.copyOf(sanitized);
    }

    private void rejectForbiddenFields(Object value, String path, int depth) {
        if (value == null) {
            return;
        }
        if (depth > MAX_FACT_DEPTH) {
            throw new IllegalArgumentException("Banking decision trigger facts exceed the maximum nesting depth at " + path);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                if (FORBIDDEN_FACT_FIELDS.contains(key)) {
                    throw new IllegalArgumentException(
                            "Banking decision trigger facts contain forbidden field '" + key + "' at " + path);
                }
                rejectForbiddenFields(entry.getValue(), path + "." + key, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                rejectForbiddenFields(item, path + "[" + index + "]", depth + 1);
                index++;
            }
        }
    }

    private Object textFact(Object value, String field) {
        if (IDENTIFIER_FACT_FIELDS.contains(field) && value instanceof Number) {
            BigDecimal identifier = numericFact(value, field);
            if (identifier.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(
                        "Banking decision trigger fact '" + field + "' must be an integral identifier");
            }
            return canonicalDecimalString(identifier);
        }
        if (!(value instanceof CharSequence) && !(value instanceof Character) && !(value instanceof Enum<?>)) {
            throw new IllegalArgumentException(
                    "Banking decision trigger fact '" + field + "' must be a text scalar");
        }
        String resolved = text(value);
        if (resolved.isBlank() || resolved.length() > MAX_FACT_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Banking decision trigger fact '" + field + "' must be nonblank and at most "
                            + MAX_FACT_TEXT_LENGTH + " characters");
        }
        return resolved;
    }

    private BigDecimal numericFact(Object value, String field) {
        if (!(value instanceof Number) && !(value instanceof CharSequence)) {
            throw new IllegalArgumentException(
                    "Banking decision trigger fact '" + field + "' must be numeric");
        }
        BigDecimal resolved;
        try {
            resolved = new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Banking decision trigger fact '" + field + "' must be numeric",
                    invalid);
        }
        BigDecimal canonical = resolved.stripTrailingZeros();
        int decimalScale = Math.max(0, canonical.scale());
        if (resolved.precision() > 38
                || decimalScale > MAX_FACT_DECIMAL_SCALE
                || resolved.abs().compareTo(MAX_ABSOLUTE_FACT_NUMBER) > 0) {
            throw new IllegalArgumentException(
                    "Banking decision trigger fact '" + field + "' exceeds the supported numeric bounds");
        }
        return resolved;
    }

    private static String canonicalDecimalString(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        return canonical.signum() == 0 ? "0" : canonical.toPlainString();
    }

    private String eventText(Map<String, Object> event, String field) {
        if (event == null || event.get(field) == null) {
            return "";
        }
        return String.valueOf(textFact(event.get(field), field));
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> values = new LinkedHashMap<>();
        values.put("statement_close", new Definition(true, "NORMAL", new BigDecimal("0.01"),
                "Credit card statement closed"));
        values.put("payment_due", new Definition(true, "NORMAL", new BigDecimal("0.01"),
                "Payment is due"));
        values.put("payment_missed", new Definition(true, "HIGH", new BigDecimal("0.01"),
                "Payment was missed"));
        values.put("interest_charged", new Definition(true, "NORMAL", new BigDecimal("25.00"),
                "Material interest was charged"));
        values.put("fee_charged", new Definition(true, "NORMAL", new BigDecimal("10.00"),
                "Material fee was charged"));
        values.put("credit_limit_change", new Definition(true, "NORMAL", BigDecimal.ZERO,
                "Credit limit changed"));
        values.put("rate_or_policy_change", new Definition(true, "NORMAL", BigDecimal.ZERO,
                "Rate or policy changed"));
        values.put("hardship_threshold_crossed", new Definition(true, "HIGH", BigDecimal.ZERO,
                "Hardship threshold crossed"));
        values.put("liquidity_signal", new Definition(true, "HIGH", BigDecimal.ZERO,
                "Liquidity threshold crossed"));
        values.put("monthly_review", new Definition(false, "LOW", BigDecimal.ZERO,
                "Scheduled monthly review is due"));
        return Map.copyOf(values);
    }

    private static Map<String, Object> policyMap(
            boolean enabled,
            String priority,
            BigDecimal minimumAmount) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", enabled);
        policy.put("priority", priority);
        policy.put("minimumAmount", minimumAmount);
        return policy;
    }

    private static BigDecimal firstAmount(Map<String, Object> facts, List<String> fields) {
        for (String field : fields) {
            Object value = facts.get(field);
            if (value == null) {
                continue;
            }
            try {
                return new BigDecimal(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal decimalValue(Object value, String field) {
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Banking decision trigger policy '" + field + "' must be numeric");
        }
    }

    private static boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Banking decision trigger policy '" + field + "' must be boolean");
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String resolved = text(value);
            if (hasText(resolved)) {
                return resolved;
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> {
            if (key != null) {
                copy.put(String.valueOf(key), entryValue);
            }
        });
        return copy;
    }

    private record Definition(
            boolean defaultEnabled,
            String defaultPriority,
            BigDecimal defaultMinimumAmount,
            String reason) {
    }

    private record PolicyConfiguration(
            boolean enabled,
            String priority,
            BigDecimal minimumAmount) {
    }
}
