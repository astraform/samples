package banking.simulator.remote.domain.scenariolab;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class BankingPolicyScenarioLabPresetService {

    private static final String DEFAULT_COMPARE_MODE = "SEEDED_DETERMINISTIC";
    private static final String RESERVED_HYBRID_COMPARE_MODE = "HYBRID_MICRODECISION_BANDED";
    private static final String DEFAULT_RUN_MODE = "DEMO_FAST";
    private static final String DEFAULT_CADENCE_PROFILE = "DEMO_FAST_FORK_FOCUSED";
    private static final List<String> ALLOWED_RUN_MODES = List.of("DEMO_FAST", "DETAILED_ANALYSIS");
    private static final List<String> ALLOWED_CADENCE_PROFILES =
            List.of("DEMO_FAST_FORK_FOCUSED", "ADAPTIVE_15M_FIRST_24H_THEN_1H");
    private static final List<String> ALLOWED_COMPARE_MODES = List.of(DEFAULT_COMPARE_MODE, RESERVED_HYBRID_COMPARE_MODE);
    private static final List<String> ALLOWED_FRAUD_SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> ALLOWED_AGENT_DECISION_CADENCES =
            List.of("EVENT_ONLY", "HOURLY", "DAILY", "WEEKLY");

    private final Map<String, Object> catalogDocument;
    private final Map<String, PresetDefinition> presetsById;
    private final Map<String, Object> defaultValues;

    public BankingPolicyScenarioLabPresetService(ObjectMapper objectMapper) {
        this.catalogDocument = loadCatalogDocument(objectMapper);
        this.defaultValues = objectMap(catalogDocument.get("defaults"));
        this.presetsById = loadPresetDefinitions(objectMapper, catalogDocument);
    }

    public Map<String, Object> catalogPayload() {
        return deepCopyMap(catalogDocument);
    }

    public BankingPolicyScenarioLabRequest resolveRequest(Map<String, Object> payload, String executionRole) {
        String requestedPresetId = normalizePresetId(payload != null ? payload.get("presetId") : null);
        PresetDefinition preset = resolvePreset(requestedPresetId);
        boolean platformAdmin = "PLATFORM_ADMIN".equalsIgnoreCase(stringValue(executionRole));

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.putAll(defaultValues);
        effective.putAll(preset.values());
        if (payload != null) {
            effective.putAll(payload);
        }
        effective.put("presetId", preset.presetId());

        String compareMode = normalizeUpperToken(
                effective.get("compareMode"),
                DEFAULT_COMPARE_MODE,
                "compareMode",
                ALLOWED_COMPARE_MODES);
        if (RESERVED_HYBRID_COMPARE_MODE.equals(compareMode)) {
            if (!booleanValue(effective.get("experimentalHybridAcknowledged"), false)) {
                throw new IllegalArgumentException(
                        "compareMode HYBRID_MICRODECISION_BANDED is experimental. "
                                + "Set experimentalHybridAcknowledged=true only for engineering runs, not policy sign-off.");
            }
            throw new IllegalArgumentException(
                    "Hybrid compare mode is not yet supported by the async experiment-service runner.");
        }

        String runMode = normalizeUpperToken(
                effective.get("runMode"),
                DEFAULT_RUN_MODE,
                "runMode",
                ALLOWED_RUN_MODES);
        String cadenceProfile = normalizeUpperToken(
                effective.get("cadenceProfile"),
                defaultCadenceProfileForRunMode(runMode),
                "cadenceProfile",
                ALLOWED_CADENCE_PROFILES);

        String simulationHorizonDays = normalizePositiveInteger(
                effective.get("simulationHorizonDays"),
                numericString(defaultValues.get("simulationHorizonDays"), "30"),
                "simulationHorizonDays");
        String prePolicyDays = normalizeNonNegativeInteger(
                effective.get("prePolicyDays"),
                numericString(defaultValues.get("prePolicyDays"), "10"),
                "prePolicyDays");
        String postPolicyDays = resolvePostPolicyDays(
                effective.get("postPolicyDays"),
                simulationHorizonDays,
                prePolicyDays,
                numericString(defaultValues.get("postPolicyDays"), "20"));

        String productCode = normalizeProductCode(effective.get("productCode"));
        String baselineRate = normalizeRate(
                effective.get("baselineRate"),
                numericString(preset.values().get("baselineRate"), "0"),
                "baselineRate");
        String candidateRate = normalizeRate(
                effective.get("candidateRate"),
                numericString(preset.values().get("candidateRate"), "0"),
                "candidateRate");
        String baselineLimit = normalizeOptionalNonNegativeDecimal(effective.get("baselineLimit"), "baselineLimit");
        String candidateLimit = normalizeOptionalNonNegativeDecimal(effective.get("candidateLimit"), "candidateLimit");
        String candidateStimulusAmount = normalizeNonNegativeDecimal(
                effective.get("candidateStimulusAmount"),
                numericString(defaultValues.get("candidateStimulusAmount"), "0"),
                "candidateStimulusAmount");
        String candidateCrash = normalizeBooleanFlag(
                effective.get("candidateCrash"),
                booleanString(defaultValues.get("candidateCrash"), "false"),
                "candidateCrash");
        String candidateHardshipScan = normalizeBooleanFlag(
                effective.get("candidateHardshipScan"),
                booleanString(defaultValues.get("candidateHardshipScan"), "false"),
                "candidateHardshipScan");
        String candidateHardshipThreshold = normalizeOptionalNonNegativeDecimal(
                effective.get("candidateHardshipThreshold"),
                "candidateHardshipThreshold");
        String candidateHardshipSubsidyAmount = normalizeOptionalPositiveDecimal(
                effective.get("candidateHardshipSubsidyAmount"),
                "candidateHardshipSubsidyAmount");
        String candidateFraudAlert = normalizeBooleanFlag(
                effective.get("candidateFraudAlert"),
                booleanString(defaultValues.get("candidateFraudAlert"), "false"),
                "candidateFraudAlert");
        String candidateFraudSeverity = normalizeUpperToken(
                effective.get("candidateFraudSeverity"),
                stringValue(defaultValues.get("candidateFraudSeverity")).isBlank()
                        ? "HIGH"
                        : stringValue(defaultValues.get("candidateFraudSeverity")),
                "candidateFraudSeverity",
                ALLOWED_FRAUD_SEVERITIES);
        String candidateFraudChannel = normalizeNonBlankToken(
                effective.get("candidateFraudChannel"),
                stringValue(defaultValues.get("candidateFraudChannel")).isBlank()
                        ? "CARD"
                        : stringValue(defaultValues.get("candidateFraudChannel")),
                "candidateFraudChannel");
        String candidateFraudMessage = normalizeOptionalText(effective.get("candidateFraudMessage"));

        if ((baselineLimit == null) != (candidateLimit == null)) {
            throw new IllegalArgumentException(
                    "baselineLimit and candidateLimit must both be provided when using the limit lever.");
        }
        if (Integer.parseInt(prePolicyDays) + Integer.parseInt(postPolicyDays) != Integer.parseInt(simulationHorizonDays)) {
            throw new IllegalArgumentException("prePolicyDays + postPolicyDays must equal simulationHorizonDays.");
        }

        ScenarioLabRunParameters parameters = new ScenarioLabRunParameters()
                .presetId(preset.presetId())
                .productCode(productCode)
                .baselineRate(baselineRate)
                .candidateRate(candidateRate)
                .baselineLimit(baselineLimit)
                .candidateLimit(candidateLimit)
                .candidateStimulusAmount(candidateStimulusAmount)
                .candidateCrash(ScenarioLabRunParameters.CandidateCrashEnum.fromValue(candidateCrash))
                .candidateHardshipScan(ScenarioLabRunParameters.CandidateHardshipScanEnum.fromValue(candidateHardshipScan))
                .candidateHardshipThreshold(candidateHardshipThreshold)
                .candidateHardshipSubsidyAmount(candidateHardshipSubsidyAmount)
                .candidateFraudAlert(ScenarioLabRunParameters.CandidateFraudAlertEnum.fromValue(candidateFraudAlert))
                .candidateFraudSeverity(candidateFraudSeverity)
                .candidateFraudChannel(candidateFraudChannel)
                .candidateFraudMessage(candidateFraudMessage)
                .simulationHorizonDays(simulationHorizonDays)
                .prePolicyDays(prePolicyDays)
                .postPolicyDays(postPolicyDays)
                .runMode(runMode)
                .cadenceProfile(cadenceProfile)
                .compareMode(compareMode);

        if (!platformAdmin) {
            enforceGuidedRestrictions(preset, parameters);
        }

        Map<String, Object> normalizedPayload = buildNormalizedPayload(parameters);
        normalizedPayload.put(
                "simulationStartEpochMs",
                normalizeSimulationStartEpochMs(effective.get("simulationStartEpochMs")));
        copyIfPresent(effective, normalizedPayload, "executionMode");
        copyIfPresent(effective, normalizedPayload, "runPath");
        copyIfPresent(effective, normalizedPayload, "agentDecisionMode");
        copyIfPresent(effective, normalizedPayload, "realLlmOptIn");
        copyIfPresent(effective, normalizedPayload, "realLlmBudget");
        copyIfPresent(effective, normalizedPayload, "llmSamplingPolicy");
        copyAgentDecisionCadence(effective, normalizedPayload);
        copyIfPresent(effective, normalizedPayload, "lifecycleModel");
        copyIfPresent(effective, normalizedPayload, "opportunityGenerationFinalizationMode");
        copyIfPresent(effective, normalizedPayload, "simulationRunId");
        copyCohortSelection(effective, normalizedPayload);

        return new BankingPolicyScenarioLabRequest(
                preset,
                parameters,
                platformAdmin,
                normalizedPayload);
    }

    private void enforceGuidedRestrictions(PresetDefinition preset, ScenarioLabRunParameters parameters) {
        if (!preset.certified()) {
            throw new IllegalArgumentException(
                    "Team Operator runs are limited to certified templates. Requested preset: " + preset.presetId() + ".");
        }
        Set<String> editable = new LinkedHashSet<>(preset.guidedEditableFields());
        editable.addAll(List.of("simulationHorizonDays", "prePolicyDays", "postPolicyDays"));
        enforceImmutableField(preset.presetId(), editable, "productCode", parameters.getProductCode(), stringValue(preset.values().get("productCode")));
        enforceImmutableField(preset.presetId(), editable, "baselineRate", parameters.getBaselineRate(), numericString(preset.values().get("baselineRate"), null));
        enforceImmutableField(preset.presetId(), editable, "candidateRate", parameters.getCandidateRate(), numericString(preset.values().get("candidateRate"), null));
        enforceImmutableField(preset.presetId(), editable, "baselineLimit", parameters.getBaselineLimit(), optionalNumericString(preset.values().get("baselineLimit")));
        enforceImmutableField(preset.presetId(), editable, "candidateLimit", parameters.getCandidateLimit(), optionalNumericString(preset.values().get("candidateLimit")));
        enforceImmutableField(preset.presetId(), editable, "candidateStimulusAmount", parameters.getCandidateStimulusAmount(), numericString(preset.values().get("candidateStimulusAmount"), "0"));
        enforceImmutableField(preset.presetId(), editable, "candidateCrash", enumValue(parameters.getCandidateCrash()), booleanString(preset.values().get("candidateCrash"), "false"));
        enforceImmutableField(preset.presetId(), editable, "candidateHardshipScan", enumValue(parameters.getCandidateHardshipScan()), booleanString(preset.values().get("candidateHardshipScan"), "false"));
        enforceImmutableField(preset.presetId(), editable, "candidateHardshipThreshold", parameters.getCandidateHardshipThreshold(), optionalNumericString(preset.values().get("candidateHardshipThreshold")));
        enforceImmutableField(preset.presetId(), editable, "candidateHardshipSubsidyAmount", parameters.getCandidateHardshipSubsidyAmount(), optionalNumericString(preset.values().get("candidateHardshipSubsidyAmount")));
        enforceImmutableField(preset.presetId(), editable, "candidateFraudAlert", enumValue(parameters.getCandidateFraudAlert()), booleanString(preset.values().get("candidateFraudAlert"), "false"));
        enforceImmutableField(preset.presetId(), editable, "candidateFraudSeverity", parameters.getCandidateFraudSeverity(), stringValue(preset.values().get("candidateFraudSeverity")));
        enforceImmutableField(preset.presetId(), editable, "candidateFraudChannel", parameters.getCandidateFraudChannel(), stringValue(preset.values().get("candidateFraudChannel")));
        enforceImmutableField(preset.presetId(), editable, "candidateFraudMessage", parameters.getCandidateFraudMessage(), stringValue(preset.values().get("candidateFraudMessage")));
        enforceImmutableField(preset.presetId(), editable, "simulationHorizonDays", parameters.getSimulationHorizonDays(), numericString(defaultValues.get("simulationHorizonDays"), "30"));
        enforceImmutableField(preset.presetId(), editable, "prePolicyDays", parameters.getPrePolicyDays(), numericString(defaultValues.get("prePolicyDays"), "10"));
        enforceImmutableField(preset.presetId(), editable, "postPolicyDays", parameters.getPostPolicyDays(), numericString(defaultValues.get("postPolicyDays"), "20"));
        enforceImmutableField(preset.presetId(), editable, "runMode", parameters.getRunMode(), DEFAULT_RUN_MODE);
        enforceImmutableField(preset.presetId(), editable, "cadenceProfile", parameters.getCadenceProfile(), DEFAULT_CADENCE_PROFILE);
        enforceImmutableField(preset.presetId(), editable, "compareMode", parameters.getCompareMode(), DEFAULT_COMPARE_MODE);
    }

    private void enforceImmutableField(String presetId, Set<String> editable, String fieldName, String actualValue, String expectedValue) {
        if (editable.contains(fieldName)) {
            return;
        }
        if (Objects.equals(blankToNull(actualValue), blankToNull(expectedValue))) {
            return;
        }
        throw new IllegalArgumentException(
                "Team Operator runs for preset " + presetId + " may only modify "
                        + String.join(", ", editable) + ". Field " + fieldName
                        + " must remain " + formatValue(expectedValue) + ".");
    }

    private Map<String, Object> buildNormalizedPayload(ScenarioLabRunParameters parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("presetId", parameters.getPresetId());
        payload.put("productCode", parameters.getProductCode());
        payload.put("baselineRate", parameters.getBaselineRate());
        payload.put("candidateRate", parameters.getCandidateRate());
        payload.put("baselineLimit", parameters.getBaselineLimit());
        payload.put("candidateLimit", parameters.getCandidateLimit());
        payload.put("candidateStimulusAmount", parameters.getCandidateStimulusAmount());
        payload.put("candidateCrash", enumValue(parameters.getCandidateCrash()));
        payload.put("candidateHardshipScan", enumValue(parameters.getCandidateHardshipScan()));
        payload.put("candidateHardshipThreshold", parameters.getCandidateHardshipThreshold());
        payload.put("candidateHardshipSubsidyAmount", parameters.getCandidateHardshipSubsidyAmount());
        payload.put("candidateFraudAlert", enumValue(parameters.getCandidateFraudAlert()));
        payload.put("candidateFraudSeverity", parameters.getCandidateFraudSeverity());
        payload.put("candidateFraudChannel", parameters.getCandidateFraudChannel());
        payload.put("candidateFraudMessage", parameters.getCandidateFraudMessage());
        payload.put("simulationHorizonDays", parameters.getSimulationHorizonDays());
        payload.put("prePolicyDays", parameters.getPrePolicyDays());
        payload.put("postPolicyDays", parameters.getPostPolicyDays());
        payload.put("runMode", parameters.getRunMode());
        payload.put("cadenceProfile", parameters.getCadenceProfile());
        payload.put("compareMode", parameters.getCompareMode());
        return payload;
    }

    private void copyCohortSelection(Map<String, Object> effective, Map<String, Object> normalizedPayload) {
        copyIfPresent(effective, normalizedPayload, "cohortId");
        copyIfPresent(effective, normalizedPayload, "cohortDomainId");
        copyIfPresent(effective, normalizedPayload, "cohortRecipeId");
        copyIfPresent(effective, normalizedPayload, "cohortTargetSize");
        copyIfPresent(effective, normalizedPayload, "cohortArtifactRefs");
        copyIfPresent(effective, normalizedPayload, "cohortArtifactDigest");
        copyIfPresent(effective, normalizedPayload, "cohortAgentIdentitySetDigest");
        copyIfPresent(effective, normalizedPayload, "cohortArtifactSnapshot");
        copyIfPresent(effective, normalizedPayload, "activationId");
        copyIfPresent(effective, normalizedPayload, "activationSettingsDigest");
        copyIfPresent(effective, normalizedPayload, "activationSettingsSnapshot");
        copyIfPresent(effective, normalizedPayload, "cohortSummary");
        copyIfPresent(effective, normalizedPayload, "cohortSelection");
    }

    private void copyAgentDecisionCadence(Map<String, Object> effective, Map<String, Object> normalizedPayload) {
        String agentDecisionMode = stringValue(normalizedPayload.get("agentDecisionMode"))
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        boolean llmDecisionMode = "STUB_LLM".equals(agentDecisionMode) || "REAL_LLM".equals(agentDecisionMode);
        if (!llmDecisionMode && !effective.containsKey("agentDecisionCadence")) {
            return;
        }
        String cadence = normalizeUpperToken(
                effective.get("agentDecisionCadence"),
                "DAILY",
                "agentDecisionCadence",
                ALLOWED_AGENT_DECISION_CADENCES);
        normalizedPayload.put("agentDecisionCadence", cadence);
        normalizedPayload.put("agentDecisionIntervalMinutes", agentDecisionIntervalMinutes(cadence));
    }

    private int agentDecisionIntervalMinutes(String cadence) {
        return switch (cadence) {
            case "EVENT_ONLY" -> 0;
            case "HOURLY" -> 60;
            case "WEEKLY" -> 7 * 24 * 60;
            default -> 24 * 60;
        };
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String fieldName) {
        if (source.containsKey(fieldName)) {
            target.put(fieldName, source.get(fieldName));
        }
    }

    private PresetDefinition resolvePreset(String presetId) {
        PresetDefinition preset = presetsById.get(presetId);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown scenario-lab preset: " + presetId);
        }
        if (!preset.aliasOf().isBlank()) {
            PresetDefinition target = presetsById.get(preset.aliasOf());
            if (target != null) {
                return target;
            }
        }
        return preset;
    }

    private String resolvePostPolicyDays(Object value, String simulationHorizonDays, String prePolicyDays, String defaultValue) {
        if (value == null || stringValue(value).isBlank()) {
            int derived = Math.max(Integer.parseInt(simulationHorizonDays) - Integer.parseInt(prePolicyDays), 0);
            return Integer.toString(derived);
        }
        return normalizeNonNegativeInteger(value, defaultValue, "postPolicyDays");
    }

    private String defaultCadenceProfileForRunMode(String runMode) {
        return "DETAILED_ANALYSIS".equalsIgnoreCase(runMode)
                ? "ADAPTIVE_15M_FIRST_24H_THEN_1H"
                : DEFAULT_CADENCE_PROFILE;
    }

    private String normalizePresetId(Object value) {
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return stringValue(defaultValues.get("presetId"));
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("presetId must contain at least one alphanumeric character.");
        }
        return normalized;
    }

    private String normalizeProductCode(Object value) {
        String normalized = stringValue(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("productCode must contain at least one alphanumeric character.");
        }
        return normalized;
    }

    private String normalizeRate(Object value, String defaultValue, String fieldName) {
        BigDecimal rate = parseDecimal(value, defaultValue, fieldName);
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 1 inclusive.");
        }
        return rate.stripTrailingZeros().toPlainString();
    }

    private String normalizeNonNegativeDecimal(Object value, String defaultValue, String fieldName) {
        BigDecimal amount = parseDecimal(value, defaultValue, fieldName);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0.");
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String normalizeOptionalNonNegativeDecimal(Object value, String fieldName) {
        if (value == null || stringValue(value).isBlank()) {
            return null;
        }
        BigDecimal amount = parseDecimal(value, null, fieldName);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0.");
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String normalizeOptionalPositiveDecimal(Object value, String fieldName) {
        if (value == null || stringValue(value).isBlank()) {
            return null;
        }
        BigDecimal amount = parseDecimal(value, null, fieldName);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0.");
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String normalizePositiveInteger(Object value, String defaultValue, String fieldName) {
        int parsed = parseInteger(value, defaultValue, fieldName);
        if (parsed <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0.");
        }
        return Integer.toString(parsed);
    }

    private String normalizeNonNegativeInteger(Object value, String defaultValue, String fieldName) {
        int parsed = parseInteger(value, defaultValue, fieldName);
        if (parsed < 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0.");
        }
        return Integer.toString(parsed);
    }

    private long normalizeSimulationStartEpochMs(Object value) {
        if (value == null) {
            return System.currentTimeMillis();
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("simulationStartEpochMs must be a JSON number.");
        }
        if ((number instanceof Double || number instanceof Float)
                && (!Double.isFinite(number.doubleValue())
                || Math.abs(number.doubleValue()) > 9_007_199_254_740_991d)) {
            throw new IllegalArgumentException(
                    "simulationStartEpochMs decimal/exponent values must be within the exact IEEE-754 integer range.");
        }
        try {
            long parsed = new BigDecimal(number.toString()).longValueExact();
            if (parsed <= 0L) {
                throw new IllegalArgumentException("simulationStartEpochMs must be greater than 0.");
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "simulationStartEpochMs must be a positive signed-64-bit epoch-millisecond integer.");
        }
    }

    private String normalizeUpperToken(Object value, String defaultValue, String fieldName, List<String> allowedValues) {
        String normalized = stringValue(value).isBlank() ? defaultValue : stringValue(value).toUpperCase(Locale.ROOT);
        if (!allowedValues.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + " must be one of " + String.join(", ", allowedValues) + ".");
        }
        return normalized;
    }

    private String normalizeNonBlankToken(Object value, String defaultValue, String fieldName) {
        String normalized = stringValue(value).isBlank() ? defaultValue : stringValue(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank.");
        }
        return normalized;
    }

    private String normalizeBooleanFlag(Object value, String defaultValue, String fieldName) {
        if (value == null || stringValue(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        String normalized = stringValue(value).toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "false".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(fieldName + " must be true or false.");
    }

    private String normalizeOptionalText(Object value) {
        String normalized = stringValue(value);
        return normalized.isBlank() ? null : normalized;
    }

    private BigDecimal parseDecimal(Object value, String defaultValue, String fieldName) {
        String raw = stringValue(value);
        String candidate = raw.isBlank() ? defaultValue : raw;
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a valid decimal amount.");
        }
        try {
            return new BigDecimal(candidate);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be a valid decimal amount.");
        }
    }

    private int parseInteger(Object value, String defaultValue, String fieldName) {
        String raw = stringValue(value);
        String candidate = raw.isBlank() ? defaultValue : raw;
        try {
            return Integer.parseInt(candidate);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " must be a valid integer.");
        }
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String formatValue(String value) {
        return value == null || value.isBlank() ? "blank" : value;
    }

    private String optionalNumericString(Object value) {
        String normalized = stringValue(value);
        if (normalized.isBlank() || "null".equalsIgnoreCase(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return normalized;
        }
    }

    private String numericString(Object value, String fallback) {
        String normalized = optionalNumericString(value);
        return normalized == null ? fallback : normalized;
    }

    private String booleanString(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        String normalized = stringValue(value).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String enumValue(Enum<?> value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> loadCatalogDocument(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource("banking-scenario-lab/preset-catalog.json");
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load banking Scenario Lab preset catalog.", e);
        }
    }

    private Map<String, PresetDefinition> loadPresetDefinitions(ObjectMapper objectMapper, Map<String, Object> catalog) {
        List<Map<String, Object>> rawPresets = objectMapper.convertValue(
                catalog.get("presets"),
                new TypeReference<List<Map<String, Object>>>() {
                });
        Map<String, PresetDefinition> loaded = new LinkedHashMap<>();
        for (Map<String, Object> rawPreset : rawPresets) {
            String presetId = stringValue(rawPreset.get("presetId"));
            if (presetId.isBlank()) {
                continue;
            }
            loaded.put(presetId, new PresetDefinition(
                    presetId,
                    stringValue(rawPreset.get("aliasOf")),
                    booleanValue(rawPreset.get("certified"), false),
                    stringValue(rawPreset.get("label")),
                    stringValue(rawPreset.get("description")),
                    stringValue(rawPreset.get("deliveryStage")),
                    toStringList(rawPreset.get("guidedEditableFields")),
                    rawPreset));
        }
        return loaded;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> rawMap) {
                copy.put(key, deepCopyMap(objectMap(rawMap)));
            } else if (value instanceof List<?> list) {
                copy.put(key, new ArrayList<>(list));
            } else {
                copy.put(key, value);
            }
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> normalized.put(String.valueOf(key), entryValue));
            return normalized;
        }
        return Map.of();
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    public record PresetDefinition(
            String presetId,
            String aliasOf,
            boolean certified,
            String label,
            String description,
            String deliveryStage,
            List<String> guidedEditableFields,
            Map<String, Object> values) {
    }

}
