package banking.simulator.remote.domain;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankingClockDrivenRateChangePlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compilesTheSharedCanonicalDayTenPlan() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));

        BankingClockDrivenRateChangePlan.Snapshot snapshot =
                BankingClockDrivenRateChangePlan.compileForLaunch(
                                String.valueOf(input.get("simulationRunId")),
                                String.valueOf(input.get("presetId")),
                                map(input.get("parameters")))
                        .orElseThrow();

        assertThat(snapshot.asMap()).isEqualTo(map(fixture.get("expectedSnapshot")));
        assertThat(BankingClockDrivenRateChangePlan.installOperationId(snapshot))
                .isEqualTo(fixture.get("expectedInstallOperationId"));
        assertThat(BankingClockDrivenRateChangePlan.requireSnapshot(
                        BankingClockDrivenRateChangePlan.canonicalSnapshotBytes(snapshot)))
                .isEqualTo(snapshot);
    }

    @Test
    void publishesTheSharedFrozenControlActionDescriptor() throws Exception {
        var descriptor = new BankingRemoteDomainService(objectMapper)
                .manifest()
                .getCapabilities()
                .getControlActions()
                .stream()
                .filter(action -> BankingClockDrivenRateChangePlan.ACTION_ID.equals(action.getActionId()))
                .findFirst()
                .orElseThrow();

        assertThat(objectMapper.convertValue(descriptor, new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(map(fixture().get("expectedControlActionDescriptor")));
    }

    @Test
    void doesNotCompileOtherPresetsAndRejectsExcludedOverlays() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> input = map(fixture.get("input"));
        Map<String, Object> parameters = map(input.get("parameters"));

        assertThat(BankingClockDrivenRateChangePlan.compileForLaunch(
                String.valueOf(input.get("simulationRunId")),
                "credit-stress",
                parameters)).isEmpty();

        Map<String, Object> withStimulus = new LinkedHashMap<>(parameters);
        withStimulus.put("candidateStimulusAmount", "1");
        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.compileForLaunch(
                String.valueOf(input.get("simulationRunId")),
                String.valueOf(input.get("presetId")),
                withStimulus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only the day-10 CC001 rate change");
    }

    @Test
    void rejectsTamperedOrNonCanonicalSnapshots() throws Exception {
        Map<String, Object> expected = map(fixture().get("expectedSnapshot"));
        Map<String, Object> tampered = new LinkedHashMap<>(expected);
        Map<String, Object> plan = map(tampered.get("plan"));
        plan.put("newRate", "0.25");
        tampered.put("plan", plan);

        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.requireSnapshot(tampered))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] trailing = (new String(objectMapper.writeValueAsBytes(expected)) + " {}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.requireSnapshot(trailing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strict UTF-8 JSON");

        Map<String, Object> coercible = map(fixture().get("expectedSnapshot"));
        Map<String, Object> coerciblePlan = map(coercible.get("plan"));
        coerciblePlan.put("scheduledOffsetDays", "10");
        coercible.put("plan", coerciblePlan);
        ObjectMapper canonical = new ObjectMapper().rebuild()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        coercible.put(
                "planDigest",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.writeValueAsBytes(coerciblePlan))));
        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.requireSnapshot(coercible))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact JSON integer");

        Map<String, Object> losslessTransport = map(fixture().get("expectedSnapshot"));
        Map<String, Object> transportedPlan = map(losslessTransport.get("plan"));
        transportedPlan.put("scheduledOffsetDays", new java.math.BigDecimal("1E+1"));
        losslessTransport.put("plan", transportedPlan);
        assertThat(BankingClockDrivenRateChangePlan.requireSnapshot(losslessTransport)
                        .plan().get("scheduledOffsetDays"))
                .isEqualTo(10);

        Map<String, Object> springBoundTransport = map(fixture().get("expectedSnapshot"));
        Map<String, Object> springBoundPlan = map(springBoundTransport.get("plan"));
        springBoundPlan.put("scheduledOffsetDays", 10.0d);
        springBoundTransport.put("plan", springBoundPlan);
        assertThat(BankingClockDrivenRateChangePlan.requireSnapshot(springBoundTransport)
                        .plan().get("scheduledOffsetDays"))
                .isEqualTo(10);

        springBoundPlan.put("scheduledOffsetDays", 10.5d);
        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.requireSnapshot(springBoundTransport))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("I-JSON-safe integer");

        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.canonicalDecimal(
                        "1e100000",
                        "rate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported decimal bounds");
    }

    @Test
    void emitsTheCompleteSharedRateChangeTriggerPayload() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> event = map(fixture.get("event"));

        var trigger = new BankingCustomerDecisionTriggerPolicy()
                .evaluate(
                        List.of(event),
                        ((Number) event.get("scheduledForEpochMs")).longValue(),
                        null)
                .getFirst();

        assertThat(objectMapper.convertValue(trigger, new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(map(fixture.get("expectedTrigger")));
    }

    @Test
    void persistsTheSharedExactInstallationDenominator() throws Exception {
        Map<String, Object> fixture = fixture();
        BankingClockDrivenRateChangePlan.Snapshot plan =
                BankingClockDrivenRateChangePlan.requireSnapshot(fixture.get("expectedSnapshot"));
        BankingClockDrivenRateChangePlan.InstallationManifest manifest =
                BankingClockDrivenRateChangePlan.installationManifest(
                        plan,
                        List.of(
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-002", "provider-customer-002", "opportunity-customer-002", 0),
                                new BankingClockDrivenRateChangePlan.InstallationCustomer(
                                        "customer-001", "provider-customer-001", "opportunity-customer-001", 1)),
                        "c".repeat(64));

        assertThat(manifest.asMap()).isEqualTo(map(fixture.get("expectedInstallationManifest")));
        assertThat(BankingClockDrivenRateChangePlan.requireInstallationManifest(
                        BankingClockDrivenRateChangePlan.canonicalInstallationManifestBytes(manifest),
                        plan))
                .isEqualTo(manifest);

        Map<String, Object> tampered = map(fixture.get("expectedInstallationManifest"));
        tampered.put("eligibleCustomerCount", 2);
        assertThatThrownBy(() -> BankingClockDrivenRateChangePlan.requireInstallationManifest(
                tampered,
                plan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstructsEligibilityWithTheSameCaseInsensitiveProductSemanticsAsExecution()
            throws Exception {
        byte[] cohort = objectMapper.writeValueAsBytes(List.of(Map.of(
                "personaName", "customer-lowercase-product",
                "initialAccounts", List.of(Map.of("productCode", "cc001")))));

        assertThat(BankingClockDrivenRateChangePlan.eligibleAccountCountsFromCohortArtifact(cohort))
                .containsExactly(Map.entry("customer-lowercase-product", 1));
    }

    private Map<String, Object> fixture() throws Exception {
        return objectMapper.readValue(fixturePath().toFile(), new TypeReference<>() {});
    }

    private static Path fixturePath() {
        return List.of(
                        Path.of("test-fixtures/banking-clock-driven-rate-change-v1.json"),
                        Path.of("../test-fixtures/banking-clock-driven-rate-change-v1.json"))
                .stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("rate-change fixture was not found"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }
}
