package banking.simulator.remote.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Domain-owned immutable plan for the first clock-driven banking intervention slice. */
public final class BankingClockDrivenRateChangePlan {

    public static final String PLAN_SCHEMA_VERSION = "banking.clock-driven-rate-change-plan.v1";
    public static final String SNAPSHOT_SCHEMA_VERSION =
            "banking.clock-driven-intervention-plan-snapshot.v1";
    public static final String INSTALLATION_MANIFEST_SCHEMA_VERSION =
            "banking.clock-driven-rate-change-installation.v1";
    public static final String ACTION_ID = "schedule-rate-change-plan";
    public static final String WORK_KIND = "credit_card_rate_change";
    public static final String INTERVENTION_ID = "policy_rate_candidate_shift";
    public static final String INTERVENTION_TYPE = "RATE_CHANGE";
    public static final String SUPPORTED_PRESET_ID = "credit-apr-lift";
    public static final String SUPPORTED_PRODUCT_CODE = "CC001";
    public static final String SUPPORTED_PREVIOUS_RATE = "0.19";
    public static final String SUPPORTED_NEW_RATE = "0.24";
    public static final int SCHEDULED_OFFSET_DAYS = 10;
    public static final long MAX_IJSON_INTEGER = 9_007_199_254_740_991L;

    private static final long DAY_MS = 86_400_000L;
    private static final Set<String> PLAN_FIELDS = Set.of(
            "schemaVersion",
            "simulationRunId",
            "interventionId",
            "interventionType",
            "productCode",
            "expectedPreviousRate",
            "newRate",
            "scheduledOffsetDays",
            "simulationStartEpochMs",
            "scheduledForEpochMs",
            "effectiveAtEpochMs",
            "mutationOperationId",
            "workId");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of("schemaVersion", "planDigest", "plan");
    private static final Set<String> INSTALLATION_MANIFEST_FIELDS = Set.of(
            "schemaVersion",
            "manifestDigest",
            "simulationRunId",
            "planDigest",
            "installOperationId",
            "cohortArtifactDigest",
            "agentIdentitySetDigest",
            "installedAgentCount",
            "eligibleCustomerCount",
            "customers");
    private static final Set<String> INSTALLATION_CUSTOMER_FIELDS = Set.of(
            "cohortAgentId",
            "providerRuntimeAgentId",
            "opportunityAgentId",
            "eligibleAccountCount");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper().rebuild()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private BankingClockDrivenRateChangePlan() {
    }

    public static Optional<Snapshot> compileForLaunch(
            String simulationRunId,
            String presetId,
            Map<String, Object> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        if (!SUPPORTED_PRESET_ID.equals(text(presetId))) {
            return Optional.empty();
        }
        requireExactText(parameters, "productCode", SUPPORTED_PRODUCT_CODE);
        requireExactDecimal(parameters, "baselineRate", SUPPORTED_PREVIOUS_RATE);
        requireExactDecimal(parameters, "candidateRate", SUPPORTED_NEW_RATE);
        requireExactInteger(parameters, "prePolicyDays", SCHEDULED_OFFSET_DAYS);
        requireNoExcludedOverlay(parameters);

        long startEpochMs = requireIjsonLong(parameters.get("simulationStartEpochMs"), "simulationStartEpochMs", 1L);
        long scheduledForEpochMs;
        try {
            scheduledForEpochMs = Math.addExact(startEpochMs, Math.multiplyExact(SCHEDULED_OFFSET_DAYS, DAY_MS));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Rate-change scheduled time overflows the supported integer range", overflow);
        }
        if (scheduledForEpochMs > MAX_IJSON_INTEGER) {
            throw new IllegalArgumentException("Rate-change scheduled time exceeds the I-JSON integer range");
        }
        String runId = requireText(simulationRunId, "simulationRunId");
        String identityDigest = sha256Hex(String.join("|",
                runId,
                INTERVENTION_ID,
                SUPPORTED_PRODUCT_CODE,
                SUPPORTED_PREVIOUS_RATE,
                SUPPORTED_NEW_RATE,
                Long.toString(scheduledForEpochMs)).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", PLAN_SCHEMA_VERSION);
        plan.put("simulationRunId", runId);
        plan.put("interventionId", INTERVENTION_ID);
        plan.put("interventionType", INTERVENTION_TYPE);
        plan.put("productCode", SUPPORTED_PRODUCT_CODE);
        plan.put("expectedPreviousRate", SUPPORTED_PREVIOUS_RATE);
        plan.put("newRate", SUPPORTED_NEW_RATE);
        plan.put("scheduledOffsetDays", SCHEDULED_OFFSET_DAYS);
        plan.put("simulationStartEpochMs", startEpochMs);
        plan.put("scheduledForEpochMs", scheduledForEpochMs);
        plan.put("effectiveAtEpochMs", scheduledForEpochMs);
        plan.put("mutationOperationId", "rate-change:" + identityDigest);
        plan.put("workId", "rate-change-work:" + identityDigest.substring(0, 32));
        return Optional.of(snapshot(plan));
    }

    public static Snapshot requireSnapshot(Object rawSnapshot) {
        Map<String, Object> snapshot = requireMap(rawSnapshot, "rate-change plan snapshot");
        requireExactFields(snapshot, SNAPSHOT_FIELDS, "rate-change plan snapshot");
        requireExactText(snapshot, "schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        String planDigest = requireSha256(snapshot.get("planDigest"), "planDigest");
        Map<String, Object> plan = requireMap(snapshot.get("plan"), "rate-change plan");
        validatePlan(plan);
        Map<String, Object> normalizedPlan = normalizedPlan(plan);
        String computedDigest = sha256Hex(canonicalBytes(normalizedPlan));
        if (!MessageDigest.isEqual(
                computedDigest.getBytes(StandardCharsets.US_ASCII),
                planDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Rate-change plan digest does not match its canonical plan");
        }
        return new Snapshot(planDigest, normalizedPlan);
    }

    public static Snapshot requireSnapshot(byte[] content) {
        if (content == null || content.length == 0 || content.length > 65_536) {
            throw new IllegalArgumentException("Rate-change plan snapshot must be between 1 and 65536 bytes");
        }
        try {
            return requireSnapshot(CANONICAL_MAPPER.readValue(content, MAP_TYPE));
        } catch (JacksonException invalidJson) {
            throw new IllegalArgumentException("Rate-change plan snapshot must be strict UTF-8 JSON", invalidJson);
        }
    }

    public static String installOperationId(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "install-rate-change:" + snapshot.planDigest();
    }

    public static Map<String, Object> controlActionInputs(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.asMap();
    }

    public static byte[] canonicalSnapshotBytes(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return canonicalBytes(snapshot.asMap());
    }

    public static InstallationManifest installationManifest(
            Snapshot snapshot,
            List<InstallationCustomer> rawCustomers,
            String cohortArtifactDigest) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normalizedCohortArtifactDigest = requireSha256(
                cohortArtifactDigest, "cohortArtifactDigest");
        List<InstallationCustomer> customers = normalizedInstallationCustomers(rawCustomers);
        Map<String, Object> facts = installationFacts(
                snapshot, customers, normalizedCohortArtifactDigest);
        return new InstallationManifest(
                sha256Hex(canonicalBytes(facts)),
                text(snapshot.plan().get("simulationRunId")),
                snapshot.planDigest(),
                installOperationId(snapshot),
                normalizedCohortArtifactDigest,
                agentIdentitySetDigest(customers.stream()
                        .map(InstallationCustomer::cohortAgentId)
                        .toList()),
                customers.size(),
                Math.toIntExact(customers.stream()
                        .filter(customer -> customer.eligibleAccountCount() > 0)
                        .count()),
                customers);
    }

    public static InstallationManifest requireInstallationManifest(
            Object rawManifest,
            Snapshot expectedPlan) {
        Objects.requireNonNull(expectedPlan, "expectedPlan");
        Map<String, Object> manifest = requireMap(rawManifest, "rate-change installation manifest");
        requireExactFields(
                manifest,
                INSTALLATION_MANIFEST_FIELDS,
                "rate-change installation manifest");
        requireExactText(
                manifest,
                "schemaVersion",
                INSTALLATION_MANIFEST_SCHEMA_VERSION);
        String manifestDigest = requireSha256(manifest.get("manifestDigest"), "manifestDigest");
        String simulationRunId = requireText(manifest.get("simulationRunId"), "simulationRunId");
        String planDigest = requireSha256(manifest.get("planDigest"), "planDigest");
        String operationId = requireText(manifest.get("installOperationId"), "installOperationId");
        String cohortArtifactDigest = requireSha256(
                manifest.get("cohortArtifactDigest"), "cohortArtifactDigest");
        String agentIdentitySetDigest = requireSha256(
                manifest.get("agentIdentitySetDigest"), "agentIdentitySetDigest");
        int installedAgentCount = Math.toIntExact(requireExactIjsonLong(
                manifest.get("installedAgentCount"), "installedAgentCount", 0L));
        int eligibleCustomerCount = Math.toIntExact(requireExactIjsonLong(
                manifest.get("eligibleCustomerCount"), "eligibleCustomerCount", 0L));
        if (!(manifest.get("customers") instanceof List<?> rawCustomers)) {
            throw new IllegalArgumentException("rate-change installation customers must be an array");
        }
        List<InstallationCustomer> customers = new ArrayList<>();
        for (Object rawCustomer : rawCustomers) {
            Map<String, Object> customer = requireMap(rawCustomer, "rate-change installation customer");
            requireExactFields(
                    customer,
                    INSTALLATION_CUSTOMER_FIELDS,
                    "rate-change installation customer");
            customers.add(new InstallationCustomer(
                    requireText(customer.get("cohortAgentId"), "cohortAgentId"),
                    requireText(customer.get("providerRuntimeAgentId"), "providerRuntimeAgentId"),
                    requireText(customer.get("opportunityAgentId"), "opportunityAgentId"),
                    Math.toIntExact(requireExactIjsonLong(
                            customer.get("eligibleAccountCount"),
                            "eligibleAccountCount",
                            0L))));
        }
        List<InstallationCustomer> normalizedCustomers = normalizedInstallationCustomers(customers);
        if (!customers.equals(normalizedCustomers)
                || installedAgentCount != customers.size()
                || eligibleCustomerCount != customers.stream()
                        .filter(customer -> customer.eligibleAccountCount() > 0)
                        .count()
                || !simulationRunId.equals(text(expectedPlan.plan().get("simulationRunId")))
                || !planDigest.equals(expectedPlan.planDigest())
                || !operationId.equals(installOperationId(expectedPlan))
                || !agentIdentitySetDigest.equals(agentIdentitySetDigest(
                        customers.stream().map(InstallationCustomer::cohortAgentId).toList()))) {
            throw new IllegalArgumentException(
                    "Rate-change installation manifest contradicts the launch-pinned plan");
        }
        InstallationManifest normalized = new InstallationManifest(
                manifestDigest,
                simulationRunId,
                planDigest,
                operationId,
                cohortArtifactDigest,
                agentIdentitySetDigest,
                installedAgentCount,
                eligibleCustomerCount,
                normalizedCustomers);
        Map<String, Object> facts = new LinkedHashMap<>(normalized.asMap());
        facts.remove("manifestDigest");
        String computed = sha256Hex(canonicalBytes(facts));
        if (!MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.US_ASCII),
                manifestDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(
                    "Rate-change installation manifest digest does not match its canonical facts");
        }
        return normalized;
    }

    public static InstallationManifest requireInstallationManifest(
            byte[] content,
            Snapshot expectedPlan) {
        if (content == null || content.length == 0 || content.length > 262_144) {
            throw new IllegalArgumentException(
                    "Rate-change installation manifest must be between 1 and 262144 bytes");
        }
        try {
            return requireInstallationManifest(CANONICAL_MAPPER.readValue(content, MAP_TYPE), expectedPlan);
        } catch (JacksonException invalidJson) {
            throw new IllegalArgumentException(
                    "Rate-change installation manifest must be strict UTF-8 JSON",
                    invalidJson);
        }
    }

    public static byte[] canonicalInstallationManifestBytes(InstallationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return canonicalBytes(manifest.asMap());
    }

    private static Map<String, Object> installationFacts(
            Snapshot snapshot,
            List<InstallationCustomer> customers,
            String cohortArtifactDigest) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", INSTALLATION_MANIFEST_SCHEMA_VERSION);
        facts.put("simulationRunId", snapshot.plan().get("simulationRunId"));
        facts.put("planDigest", snapshot.planDigest());
        facts.put("installOperationId", installOperationId(snapshot));
        facts.put("cohortArtifactDigest", cohortArtifactDigest);
        facts.put("agentIdentitySetDigest", agentIdentitySetDigest(
                customers.stream().map(InstallationCustomer::cohortAgentId).toList()));
        facts.put("installedAgentCount", customers.size());
        facts.put("eligibleCustomerCount", customers.stream()
                .filter(customer -> customer.eligibleAccountCount() > 0)
                .count());
        facts.put("customers", customers.stream().map(InstallationCustomer::asMap).toList());
        return facts;
    }

    private static List<InstallationCustomer> normalizedInstallationCustomers(
            List<InstallationCustomer> rawCustomers) {
        if (rawCustomers == null || rawCustomers.isEmpty() || rawCustomers.size() > 1_000) {
            throw new IllegalArgumentException(
                    "Rate-change installation requires between 1 and 1000 customers");
        }
        List<InstallationCustomer> customers = rawCustomers.stream()
                .map(customer -> new InstallationCustomer(
                        requireText(customer.cohortAgentId(), "cohortAgentId"),
                        requireText(customer.providerRuntimeAgentId(), "providerRuntimeAgentId"),
                        requireText(customer.opportunityAgentId(), "opportunityAgentId"),
                        customer.eligibleAccountCount()))
                .sorted(Comparator.comparing(InstallationCustomer::cohortAgentId))
                .toList();
        if (new HashSet<>(customers.stream().map(InstallationCustomer::cohortAgentId).toList()).size()
                        != customers.size()
                || new HashSet<>(customers.stream()
                                .map(InstallationCustomer::providerRuntimeAgentId)
                                .toList()).size()
                        != customers.size()
                || new HashSet<>(customers.stream()
                                .map(InstallationCustomer::opportunityAgentId)
                                .toList()).size()
                        != customers.size()) {
            throw new IllegalArgumentException(
                    "Rate-change installation customer identities must be unique");
        }
        return List.copyOf(customers);
    }

    public static String agentIdentitySetDigest(List<String> rawAgentIds) {
        if (rawAgentIds == null || rawAgentIds.isEmpty() || rawAgentIds.size() > 1_000) {
            throw new IllegalArgumentException("Agent identity set must contain between 1 and 1000 identities");
        }
        List<String> agentIds = rawAgentIds.stream()
                .map(agentId -> requireText(agentId, "agentId"))
                .sorted()
                .toList();
        if (new HashSet<>(agentIds).size() != agentIds.size()) {
            throw new IllegalArgumentException("Agent identity set must contain unique identities");
        }
        return sha256Hex(canonicalValueBytes(agentIds));
    }

    public static Map<String, Integer> eligibleAccountCountsFromCohortArtifact(byte[] content) {
        if (content == null || content.length == 0 || content.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Pinned cohort artifact must be between 1 byte and 16 MiB");
        }
        Object decoded;
        try {
            decoded = CANONICAL_MAPPER.readValue(content, Object.class);
        } catch (JacksonException invalidJson) {
            throw new IllegalArgumentException("Pinned cohort artifact must be strict UTF-8 JSON", invalidJson);
        }
        if (!(decoded instanceof List<?> agents) || agents.isEmpty() || agents.size() > 1_000) {
            throw new IllegalArgumentException("Pinned cohort artifact must contain between 1 and 1000 agents");
        }
        Map<String, Integer> eligibility = new java.util.TreeMap<>();
        for (Object rawAgent : agents) {
            Map<String, Object> agent = requireMap(rawAgent, "pinned cohort agent");
            String cohortAgentId = requireText(agent.get("personaName"), "personaName");
            Object rawAccounts = agent.get("initialAccounts");
            if (!(rawAccounts instanceof List<?> accounts)) {
                throw new IllegalArgumentException("Pinned cohort initialAccounts must be an array");
            }
            int eligibleAccountCount = 0;
            for (Object rawAccount : accounts) {
                Map<String, Object> account = requireMap(rawAccount, "pinned cohort account");
                if (SUPPORTED_PRODUCT_CODE.equalsIgnoreCase(text(account.get("productCode")))) {
                    eligibleAccountCount = Math.addExact(eligibleAccountCount, 1);
                }
            }
            if (eligibility.putIfAbsent(cohortAgentId, eligibleAccountCount) != null) {
                throw new IllegalArgumentException("Pinned cohort contains duplicate personaName identities");
            }
        }
        return Map.copyOf(eligibility);
    }

    public static String canonicalDecimal(Object value, String field) {
        if (value == null || value instanceof Boolean || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            throw new IllegalArgumentException(field + " must be a finite decimal");
        }
        try {
            BigDecimal decimal = new BigDecimal(String.valueOf(value).trim());
            if (decimal.precision() > 38 || decimal.scale() > 38 || decimal.scale() < -38) {
                throw new IllegalArgumentException(field + " exceeds the supported decimal bounds");
            }
            String plain = decimal.stripTrailingZeros().toPlainString();
            return "-0".equals(plain) ? "0" : plain;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be a finite decimal", invalid);
        }
    }

    private static Snapshot snapshot(Map<String, Object> plan) {
        validatePlan(plan);
        return new Snapshot(sha256Hex(canonicalBytes(plan)), normalizedPlan(plan));
    }

    private static void validatePlan(Map<String, Object> plan) {
        requireExactFields(plan, PLAN_FIELDS, "rate-change plan");
        requireExactText(plan, "schemaVersion", PLAN_SCHEMA_VERSION);
        String runId = requireText(plan.get("simulationRunId"), "simulationRunId");
        requireExactText(plan, "interventionId", INTERVENTION_ID);
        requireExactText(plan, "interventionType", INTERVENTION_TYPE);
        requireExactText(plan, "productCode", SUPPORTED_PRODUCT_CODE);
        requireExactDecimalText(plan, "expectedPreviousRate", SUPPORTED_PREVIOUS_RATE);
        requireExactDecimalText(plan, "newRate", SUPPORTED_NEW_RATE);
        requireExactJsonInteger(plan, "scheduledOffsetDays", SCHEDULED_OFFSET_DAYS);
        long start = requireExactIjsonLong(
                plan.get("simulationStartEpochMs"), "simulationStartEpochMs", 1L);
        long scheduled = requireExactIjsonLong(
                plan.get("scheduledForEpochMs"), "scheduledForEpochMs", start + 1L);
        long effective = requireExactIjsonLong(
                plan.get("effectiveAtEpochMs"), "effectiveAtEpochMs", start + 1L);
        long expectedScheduled = Math.addExact(start, Math.multiplyExact(SCHEDULED_OFFSET_DAYS, DAY_MS));
        if (scheduled != expectedScheduled || effective != scheduled) {
            throw new IllegalArgumentException("Rate-change scheduled time does not match the day-10 offset");
        }
        String identityDigest = sha256Hex(String.join("|",
                runId,
                INTERVENTION_ID,
                SUPPORTED_PRODUCT_CODE,
                SUPPORTED_PREVIOUS_RATE,
                SUPPORTED_NEW_RATE,
                Long.toString(scheduled)).getBytes(StandardCharsets.UTF_8));
        requireExactText(plan, "mutationOperationId", "rate-change:" + identityDigest);
        requireExactText(plan, "workId", "rate-change-work:" + identityDigest.substring(0, 32));
    }

    private static Map<String, Object> normalizedPlan(Map<String, Object> plan) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("schemaVersion", requireText(plan.get("schemaVersion"), "schemaVersion"));
        normalized.put("simulationRunId", requireText(plan.get("simulationRunId"), "simulationRunId"));
        normalized.put("interventionId", requireText(plan.get("interventionId"), "interventionId"));
        normalized.put("interventionType", requireText(plan.get("interventionType"), "interventionType"));
        normalized.put("productCode", requireText(plan.get("productCode"), "productCode"));
        normalized.put("expectedPreviousRate", canonicalDecimal(plan.get("expectedPreviousRate"), "expectedPreviousRate"));
        normalized.put("newRate", canonicalDecimal(plan.get("newRate"), "newRate"));
        normalized.put("scheduledOffsetDays", Math.toIntExact(requireIjsonLong(
                plan.get("scheduledOffsetDays"), "scheduledOffsetDays", 0L)));
        normalized.put("simulationStartEpochMs", requireIjsonLong(
                plan.get("simulationStartEpochMs"), "simulationStartEpochMs", 1L));
        normalized.put("scheduledForEpochMs", requireIjsonLong(
                plan.get("scheduledForEpochMs"), "scheduledForEpochMs", 1L));
        normalized.put("effectiveAtEpochMs", requireIjsonLong(
                plan.get("effectiveAtEpochMs"), "effectiveAtEpochMs", 1L));
        normalized.put("mutationOperationId", requireText(
                plan.get("mutationOperationId"), "mutationOperationId"));
        normalized.put("workId", requireText(plan.get("workId"), "workId"));
        return immutableCopy(normalized);
    }

    private static void requireNoExcludedOverlay(Map<String, Object> parameters) {
        if (hasText(parameters.get("baselineLimit"))
                || hasText(parameters.get("candidateLimit"))
                || decimalOrZero(parameters.get("candidateStimulusAmount")).signum() != 0
                || booleanValue(parameters.get("candidateCrash"))
                || booleanValue(parameters.get("candidateHardshipScan"))
                || booleanValue(parameters.get("candidateFraudAlert"))) {
            throw new IllegalArgumentException(
                    "credit-apr-lift clock execution supports only the day-10 CC001 rate change");
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(text(value));
    }

    private static BigDecimal decimalOrZero(Object value) {
        return value == null || !hasText(value) ? BigDecimal.ZERO : new BigDecimal(text(value));
    }

    private static void requireExactDecimal(Map<String, Object> values, String field, String expected) {
        if (!expected.equals(canonicalDecimal(values.get(field), field))) {
            throw new IllegalArgumentException(field + " must equal " + expected + " for the rate-change slice");
        }
    }

    private static void requireExactDecimalText(
            Map<String, Object> values,
            String field,
            String expected) {
        Object raw = values.get(field);
        if (!(raw instanceof String text)
                || !expected.equals(text)
                || !expected.equals(canonicalDecimal(text, field))) {
            throw new IllegalArgumentException(field + " must be the canonical decimal string " + expected);
        }
    }

    private static void requireExactInteger(Map<String, Object> values, String field, int expected) {
        long value = requireIjsonLong(values.get(field), field, 0L);
        if (value != expected) {
            throw new IllegalArgumentException(field + " must equal " + expected + " for the rate-change slice");
        }
    }

    private static void requireExactJsonInteger(
            Map<String, Object> values,
            String field,
            int expected) {
        Object raw = values.get(field);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(field + " must be an exact JSON integer");
        }
        long value = requireIjsonLong(raw, field, 0L);
        if (value != expected) {
            throw new IllegalArgumentException(field + " must equal " + expected + " for the rate-change slice");
        }
    }

    private static long requireExactIjsonLong(Object raw, String field, long minimum) {
        if (!(raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long
                || raw instanceof BigInteger)) {
            throw new IllegalArgumentException(field + " must be an exact JSON integer");
        }
        return requireIjsonLong(raw, field, minimum);
    }

    private static long requireIjsonLong(Object raw, String field, long minimum) {
        if (raw instanceof Boolean || raw == null) {
            throw new IllegalArgumentException(field + " must be an I-JSON-safe integer");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be an I-JSON-safe integer", invalid);
        }
        long value;
        try {
            value = decimal.longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(field + " must be an I-JSON-safe integer", invalid);
        }
        if (value < minimum || value > MAX_IJSON_INTEGER) {
            throw new IllegalArgumentException(field + " is outside the supported I-JSON integer range");
        }
        return value;
    }

    private static String requireSha256(Object raw, String field) {
        String value = requireText(raw, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static void requireExactText(Map<String, Object> values, String field, String expected) {
        String value = requireText(values.get(field), field);
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    private static String requireText(Object raw, String field) {
        if (!(raw instanceof String value) || value.isBlank() || !value.equals(value.trim()) || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be a trimmed nonblank string of at most 256 characters");
        }
        return value;
    }

    private static void requireExactFields(Map<String, Object> values, Set<String> expected, String label) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(label + " must contain exactly " + expected);
        }
    }

    private static Map<String, Object> requireMap(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " keys must be strings");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    private static byte[] canonicalBytes(Map<String, Object> value) {
        return canonicalValueBytes(value);
    }

    private static byte[] canonicalValueBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Rate-change plan could not be serialized canonically", failure);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record Snapshot(String planDigest, Map<String, Object> plan) {
        public Snapshot {
            requireSha256(planDigest, "planDigest");
            plan = immutableCopy(Objects.requireNonNull(plan, "plan"));
        }

        public Map<String, Object> asMap() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
            snapshot.put("planDigest", planDigest);
            snapshot.put("plan", plan);
            return snapshot;
        }
    }

    public record InstallationCustomer(
            String cohortAgentId,
            String providerRuntimeAgentId,
            String opportunityAgentId,
            int eligibleAccountCount) {
        public InstallationCustomer {
            requireText(cohortAgentId, "cohortAgentId");
            requireText(providerRuntimeAgentId, "providerRuntimeAgentId");
            requireText(opportunityAgentId, "opportunityAgentId");
            if (eligibleAccountCount < 0 || eligibleAccountCount > 1_000) {
                throw new IllegalArgumentException(
                        "eligibleAccountCount must be between 0 and 1000");
            }
        }

        public Map<String, Object> asMap() {
            return Map.of(
                    "cohortAgentId", cohortAgentId,
                    "providerRuntimeAgentId", providerRuntimeAgentId,
                    "opportunityAgentId", opportunityAgentId,
                    "eligibleAccountCount", eligibleAccountCount);
        }
    }

    public record InstallationManifest(
            String manifestDigest,
            String simulationRunId,
            String planDigest,
            String installOperationId,
            String cohortArtifactDigest,
            String agentIdentitySetDigest,
            int installedAgentCount,
            int eligibleCustomerCount,
            List<InstallationCustomer> customers) {
        public InstallationManifest {
            requireSha256(manifestDigest, "manifestDigest");
            requireText(simulationRunId, "simulationRunId");
            requireSha256(planDigest, "planDigest");
            requireText(installOperationId, "installOperationId");
            requireSha256(cohortArtifactDigest, "cohortArtifactDigest");
            requireSha256(agentIdentitySetDigest, "agentIdentitySetDigest");
            customers = List.copyOf(Objects.requireNonNull(customers, "customers"));
            if (installedAgentCount != customers.size()
                    || eligibleCustomerCount != customers.stream()
                            .filter(customer -> customer.eligibleAccountCount() > 0)
                            .count()) {
                throw new IllegalArgumentException(
                        "Rate-change installation counters contradict its customer manifest");
            }
        }

        public Map<String, Object> asMap() {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", INSTALLATION_MANIFEST_SCHEMA_VERSION);
            manifest.put("manifestDigest", manifestDigest);
            manifest.put("simulationRunId", simulationRunId);
            manifest.put("planDigest", planDigest);
            manifest.put("installOperationId", installOperationId);
            manifest.put("cohortArtifactDigest", cohortArtifactDigest);
            manifest.put("agentIdentitySetDigest", agentIdentitySetDigest);
            manifest.put("installedAgentCount", installedAgentCount);
            manifest.put("eligibleCustomerCount", eligibleCustomerCount);
            manifest.put("customers", customers.stream().map(InstallationCustomer::asMap).toList());
            return manifest;
        }
    }
}
