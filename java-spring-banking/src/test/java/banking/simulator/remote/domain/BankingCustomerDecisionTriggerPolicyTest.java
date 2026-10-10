package banking.simulator.remote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.astraform.remote.domain.openapi.models.RemoteDomainCustomerDecisionTrigger;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankingCustomerDecisionTriggerPolicyTest {

    private static final long SCHEDULED_AT = 1_777_000_000_000L;
    private final BankingCustomerDecisionTriggerPolicy policy = new BankingCustomerDecisionTriggerPolicy();

    @Test
    void mapsTheCompleteBankingV1EventSet() throws Exception {
        Map<String, Object> fixture = new ObjectMapper().readValue(
                fixturePath().toFile(),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) fixture.get("events");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expected = (List<Map<String, Object>>) fixture.get("expected");

        List<RemoteDomainCustomerDecisionTrigger> triggers = policy.evaluate(events, SCHEDULED_AT, null);

        assertThat(triggers)
                .extracting(RemoteDomainCustomerDecisionTrigger::getTriggerType)
                .containsExactlyElementsOf(expected.stream()
                        .map(item -> String.valueOf(item.get("triggerType")))
                        .toList());
        assertThat(triggers)
                .extracting(RemoteDomainCustomerDecisionTrigger::getPriority)
                .containsExactlyElementsOf(expected.stream()
                        .map(item -> String.valueOf(item.get("priority")))
                        .toList());
        assertThat(triggers)
                .extracting(RemoteDomainCustomerDecisionTrigger::getReason)
                .containsExactlyElementsOf(expected.stream()
                        .map(item -> String.valueOf(item.get("reason")))
                        .toList());
        assertThat(triggers)
                .extracting(RemoteDomainCustomerDecisionTrigger::getPayload)
                .containsExactlyElementsOf(expected.stream()
                        .map(item -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) item.get("payload");
                            return payload;
                        })
                        .toList());
        assertThat(triggers)
                .allSatisfy(trigger -> {
                    assertThat(trigger.getSchemaVersion().getValue()).isEqualTo("customer_decision_trigger.v1");
                    assertThat(trigger.getScheduledForEpochMs()).isEqualTo(SCHEDULED_AT);
                    assertThat(trigger.getTriggerRef()).endsWith("|account:acct-1");
                    assertThat(trigger.getPayload())
                            .containsEntry(
                                    "policyVersion",
                                    BankingCustomerDecisionTriggerPolicy.POLICY_VERSION)
                            .containsEntry(
                                    "numericEncoding",
                                    BankingCustomerDecisionTriggerPolicy.NUMERIC_ENCODING)
                            .doesNotContainKeys("prompt", "systemPrompt", "rawProviderText", "chainOfThought");
                });
        assertThat(triggers.get(2).getPriority()).isEqualTo("HIGH");
        assertThat(triggers.get(7).getPriority()).isEqualTo("HIGH");
    }

    @Test
    void defaultsSuppressRoutineNoiseAndOptionalReview() {
        List<Map<String, Object>> events = List.of(
                event("remote_domain_interest_accrued", "saver-interest", "interestAmount", 3),
                event("remote_domain_credit_card_interest_charged", "card-interest", "interestAmount", 24),
                event("remote_domain_fee_charged", "small-fee", "feeAmount", 5),
                event("remote_domain_scheduled_review_due", "monthly-review", "currentBalance", 1000),
                event("remote_domain_hardship_reviewed", "hardship-declined", "outcome", "DECLINED"));

        assertThat(policy.evaluate(events, SCHEDULED_AT, null)).isEmpty();
    }

    @Test
    void domainConfigurationCanEnableReviewAndChangeMateriality() {
        Map<String, Object> configuration = Map.of(
                "interest_charged", Map.of("minimumAmount", 20, "priority", "CRITICAL"),
                "monthly_review", Map.of("enabled", true, "priority", "NORMAL"));

        List<RemoteDomainCustomerDecisionTrigger> triggers = policy.evaluate(
                List.of(
                        event("remote_domain_credit_card_interest_charged", "interest-1", "interestAmount", 24),
                        event("remote_domain_scheduled_review_due", "review-1", "currentBalance", 1000)),
                SCHEDULED_AT,
                configuration);

        assertThat(triggers)
                .extracting(RemoteDomainCustomerDecisionTrigger::getTriggerType)
                .containsExactly("interest_charged", "monthly_review");
        assertThat(triggers).extracting(RemoteDomainCustomerDecisionTrigger::getPriority)
                .containsExactly("CRITICAL", "NORMAL");
    }

    @Test
    void serializesExactlyOneSchedulingPriorityProperty() throws Exception {
        RemoteDomainCustomerDecisionTrigger trigger = policy.evaluate(
                List.of(event(
                        "remote_domain_credit_card_interest_charged",
                        "interest-1",
                        "interestAmount",
                        24)),
                SCHEDULED_AT,
                Map.of("interest_charged", Map.of("minimumAmount", 20, "priority", "CRITICAL")))
                .getFirst();

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(trigger);
        JsonNode serialized = objectMapper.readTree(json);

        assertThat(serialized.path("schedulingPriority").asText()).isEqualTo("CRITICAL");
        assertThat(countOccurrences(json, "\"schedulingPriority\"")).isEqualTo(1);
    }

    @Test
    void prefersTheGeneratedTypedSchedulingPriorityPropertyWhenAvailable() throws Exception {
        TypedSchedulingPriorityTrigger trigger = new TypedSchedulingPriorityTrigger();

        BankingCustomerDecisionTriggerPolicy.setSchedulingPriority(trigger, "CRITICAL");

        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(trigger);
        JsonNode serialized = objectMapper.readTree(json);
        assertThat(trigger.getSchedulingPriority()).isEqualTo(TypedSchedulingPriority.CRITICAL);
        assertThat(serialized.path("schedulingPriority").asText()).isEqualTo("CRITICAL");
        assertThat(countOccurrences(json, "\"schedulingPriority\"")).isEqualTo(1);
    }

    @Test
    void accountIdentityPreventsSameOccurrenceFromColliding() {
        Map<String, Object> first = event(
                "remote_domain_credit_card_statement_closed", "statement-window", "minimumAmountDue", 45);
        Map<String, Object> second = new LinkedHashMap<>(first);
        second.put("accountId", "acct-2");

        List<RemoteDomainCustomerDecisionTrigger> triggers = policy.evaluate(
                List.of(first, second),
                SCHEDULED_AT,
                null);

        assertThat(triggers).extracting(RemoteDomainCustomerDecisionTrigger::getTriggerId)
                .containsExactly(
                        "statement_close:statement-window|account:acct-1",
                        "statement_close:statement-window|account:acct-2");
    }

    @Test
    void malformedDomainPolicyFailsClosed() {
        assertThatThrownBy(() -> policy.normalizedConfiguration("monthly_review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an object");
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "monthly_review", Map.of("enabled", "yes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be boolean");
        Map<String, Object> nullEnabled = new LinkedHashMap<>();
        nullEnabled.put("enabled", null);
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "monthly_review", nullEnabled)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be boolean");
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "monthly_review", Map.of("priority", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid priority");
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "monthly_review", Map.of("priority", "URGENT"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid priority");
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "interest_charged", Map.of("minimumAmount", "NaN"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be numeric");
        assertThatThrownBy(() -> policy.normalizedConfiguration(Map.of(
                "made_up_policy", Map.of("enabled", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown banking decision trigger policy");
    }

    @Test
    void malformedOrPromptLikeFactsFailAtTheProviderBoundary() {
        Map<String, Object> nestedPrompt = event(
                "remote_domain_credit_card_statement_closed",
                "statement-nested-prompt",
                "minimumAmountDue",
                45);
        nestedPrompt.put("metadata", Map.of("chainOfThought", "must not escape"));
        assertThatThrownBy(() -> policy.evaluate(List.of(nestedPrompt), SCHEDULED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden field 'chainOfThought'");

        Map<String, Object> structuredAccountId = event(
                "remote_domain_credit_card_statement_closed",
                "statement-structured-account",
                "minimumAmountDue",
                45);
        structuredAccountId.put("accountId", Map.of("metadata", "acct-1"));
        assertThatThrownBy(() -> policy.evaluate(List.of(structuredAccountId), SCHEDULED_AT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId")
                .hasMessageContaining("text scalar");
    }

    @Test
    void encodesNumericRuntimeIdentifiersAsCanonicalDecimalStrings() {
        Map<String, Object> source = event(
                "remote_domain_credit_card_statement_closed",
                "statement-numeric-runtime-id",
                "minimumAmountDue",
                45);
        source.put("runtimeAccountId", 1_896_391_701_000L);

        RemoteDomainCustomerDecisionTrigger trigger = policy.evaluate(List.of(source), SCHEDULED_AT, null).getFirst();

        assertThat(trigger.getPayload()).containsEntry("runtimeAccountId", "1896391701000");
    }

    @Test
    void sharedAdversarialFixtureFailsIdenticallyAcrossProviders() throws Exception {
        Map<String, Object> fixture = new ObjectMapper().readValue(
                fixturePath().toFile(),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidEvents = (List<Map<String, Object>>) fixture.get("invalidEvents");

        for (Map<String, Object> vector : invalidEvents) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) vector.get("event");
            assertThatThrownBy(() -> policy.evaluate(List.of(event), SCHEDULED_AT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(vector.get("expectedMessage")));
        }
    }

    private Map<String, Object> event(
            String eventType,
            String occurrenceKey,
            String factKey,
            Object factValue) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("occurrenceKey", occurrenceKey);
        event.put("accountId", "acct-1");
        event.put(factKey, factValue);
        return event;
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private Path fixturePath() {
        for (Path candidate : List.of(
                Path.of("test-fixtures", "banking-decision-trigger-policy-v1.json"),
                Path.of("..", "test-fixtures", "banking-decision-trigger-policy-v1.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("banking decision-trigger policy fixture was not found");
    }

    private enum TypedSchedulingPriority {
        CRITICAL,
        HIGH,
        NORMAL,
        LOW
    }

    private static final class TypedSchedulingPriorityTrigger {
        private TypedSchedulingPriority schedulingPriority;

        @JsonProperty("schedulingPriority")
        public TypedSchedulingPriority getSchedulingPriority() {
            return schedulingPriority;
        }

        @JsonProperty("schedulingPriority")
        public void setSchedulingPriority(TypedSchedulingPriority schedulingPriority) {
            this.schedulingPriority = schedulingPriority;
        }
    }
}
