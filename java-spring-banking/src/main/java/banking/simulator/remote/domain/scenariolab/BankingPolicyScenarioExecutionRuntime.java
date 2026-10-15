package banking.simulator.remote.domain.scenariolab;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

final class BankingPolicyScenarioExecutionRuntime {

    private static final Logger log = LoggerFactory.getLogger(BankingPolicyScenarioExecutionRuntime.class);

    private static final String SEEDED_AGENTS_CLASSPATH_RESOURCE = "banking-demo-agents.json";
    private static final String SEEDED_AGENTS_WORKSPACE_PATH =
            "scenarios/demo/seeded-cohorts/banking-demo-agents.json";
    private static final String GENERATED_COHORTS_WORKSPACE_PATH = "output/cohorts";
    private static final int MAX_FAST_COMPARE_GENERATED_COHORT_SIZE = 250;
    private static final String BANKING_POLICY_PACK_SERVICE = "banking-policy-pack";
    private static final String AGENT_DECISION_MODE_DETERMINISTIC = "DETERMINISTIC";
    private static final String AGENT_DECISION_MODE_STUB_LLM = "STUB_LLM";
    private static final String AGENT_DECISION_MODE_REAL_LLM = "REAL_LLM";
    private static final String REAL_LLM_DEFAULT_PROVIDER = stringEnv(
            "ASTRAFORM_REAL_LLM_CANARY_PROVIDER",
            "openai");
    private static final String REAL_LLM_DEFAULT_MODEL = stringEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MODEL",
            "gemini-2.5-flash-lite");
    private static final int REAL_LLM_DEFAULT_MAX_CALLS = intEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_CALLS",
            70);
    private static final double REAL_LLM_DEFAULT_MAX_USD = doubleEnv(
            "ASTRAFORM_REAL_LLM_CANARY_MAX_USD",
            1.0d);
    private static final String AGENT_DECISION_CADENCE_EVENT_ONLY = "EVENT_ONLY";
    private static final String AGENT_DECISION_CADENCE_HOURLY = "HOURLY";
    private static final String AGENT_DECISION_CADENCE_DAILY = "DAILY";
    private static final String AGENT_DECISION_CADENCE_WEEKLY = "WEEKLY";

    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final String bankingProductEndpointUrl;
    private final RestTemplate restTemplate;
    private final BankingPolicyInterventionCompiler interventionCompiler;

    BankingPolicyScenarioExecutionRuntime(
            ObjectMapper objectMapper,
            Path workspaceRoot,
            String agentRuntimeUrl,
            String simulatorUrl,
            String bankingProductEndpointUrl,
            RestTemplate restTemplate) {
        this.objectMapper = objectMapper.rebuild()
                .findAndAddModules()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
        this.workspaceRoot = workspaceRoot;
        this.bankingProductEndpointUrl = bankingProductEndpointUrl;
        this.restTemplate = restTemplate;
        this.interventionCompiler = new BankingPolicyInterventionCompiler();
    }

    ExecutionPlan materializeExecutionPlan(ScenarioLabRunParameters parameters) throws IOException {
        return materializeExecutionPlan(parameters, Map.of());
    }

    ExecutionPlan materializeExecutionPlan(
            ScenarioLabRunParameters parameters,
            Map<String, Object> normalizedPayload) throws IOException {
        BankingPolicyInterventionCompiler.InterventionPlan interventions =
                interventionCompiler.compile(parameters);
        return new ExecutionPlan(
                fetchProductMetadata(parameters.getProductCode()),
                loadScenarioAgents(
                        parameters,
                        normalizedPayload == null ? Map.of() : normalizedPayload),
                interventions.baseline().stream().map(this::toScenarioEvent).toList(),
                interventions.candidate().stream().map(this::toScenarioEvent).toList(),
                buildBatchSteps(
                        intValue(parameters.getSimulationHorizonDays(), 30),
                        intValue(parameters.getPrePolicyDays(), 10),
                        parameters.getCadenceProfile()));
    }

    ScenarioExecutionResult executeScenario(
            String suiteRunId,
            String branchType,
            Path scenarioRoot,
            List<Map<String, Object>> agents,
            List<ScenarioEvent> events,
            List<BatchStep> batchSteps,
            ScenarioLabRunParameters parameters) throws IOException {
        throw nativeScenarioLabExecutionUnavailable("scenario-lab-execution");
    }

    private List<Map<String, Object>> loadScenarioAgents(
            ScenarioLabRunParameters parameters,
            Map<String, Object> normalizedPayload) throws IOException {
        String cohortId = selectedCohortId(normalizedPayload);
        if (!cohortId.isBlank()) {
            return loadGeneratedCohortAgents(cohortId);
        }
        return loadSeededAgents(DEFAULT_COHORT_SIZE, booleanValue(parameters.getCandidateHardshipScan(), false));
    }

    private String selectedCohortId(Map<String, Object> normalizedPayload) {
        String direct = stringValue(normalizedPayload.get("cohortId"));
        if (!direct.isBlank()) {
            return direct;
        }
        Map<String, Object> selection = objectMap(normalizedPayload.get("cohortSelection"));
        return stringValue(selection.get("cohortId"));
    }

    private List<Map<String, Object>> loadGeneratedCohortAgents(String cohortId) throws IOException {
        return loadGeneratedCohortAgents(cohortId, MAX_FAST_COMPARE_GENERATED_COHORT_SIZE);
    }

    List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(String cohortId) throws IOException {
        return loadGeneratedCohortAgentsForStreaming(cohortId, AGENT_DECISION_MODE_DETERMINISTIC);
    }

    List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(
            String cohortId,
            String agentDecisionMode) throws IOException {
        return loadGeneratedCohortAgentsForStreaming(cohortId, agentDecisionMode, Map.of());
    }

    List<Map<String, Object>> loadGeneratedCohortAgentsForStreaming(
            String cohortId,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) throws IOException {
        return loadGeneratedCohortAgents(cohortId, null, agentDecisionMode, agentDecisionSettings);
    }

    List<Map<String, Object>> loadPinnedCohortAgentsForStreaming(
            String cohortId,
            byte[] artifactBytes,
            String expectedArtifactDigest,
            String expectedIdentitySetDigest,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) throws IOException {
        if (artifactBytes == null || artifactBytes.length == 0) {
            throw new IllegalArgumentException("EVENT_ONLY cohort snapshot is required.");
        }
        String actualArtifactDigest = sha256Hex(artifactBytes);
        if (!actualArtifactDigest.equals(expectedArtifactDigest)) {
            throw new IllegalArgumentException("EVENT_ONLY cohort snapshot digest does not match the pinned manifest.");
        }
        List<Map<String, Object>> rawAgents = objectMapper.readValue(
                artifactBytes,
                new TypeReference<>() {
                });
        if (rawAgents.isEmpty()) {
            throw new IllegalArgumentException("Generated cohort " + cohortId + " has no customers.");
        }
        TreeSet<String> identities = new TreeSet<>();
        for (Map<String, Object> rawAgent : rawAgents) {
            String identity = stringValue(rawAgent != null ? rawAgent.get("personaName") : null);
            if (identity.isBlank() || !identities.add(identity)) {
                throw new IllegalArgumentException("EVENT_ONLY cohort snapshot contains invalid persona identities.");
            }
        }
        String actualIdentitySetDigest = sha256Hex(objectMapper.writeValueAsBytes(List.copyOf(identities)));
        if (!actualIdentitySetDigest.equals(expectedIdentitySetDigest)) {
            throw new IllegalArgumentException("EVENT_ONLY cohort identity set does not match the pinned manifest.");
        }
        return normalizeAgents(
                rawAgents,
                "stub",
                agentDecisionMode,
                agentDecisionSettings == null ? Map.of() : agentDecisionSettings);
    }

    private List<Map<String, Object>> loadGeneratedCohortAgents(String cohortId, Integer maxCustomerCount) throws IOException {
        return loadGeneratedCohortAgents(cohortId, maxCustomerCount, AGENT_DECISION_MODE_DETERMINISTIC, Map.of());
    }

    private List<Map<String, Object>> loadGeneratedCohortAgents(
            String cohortId,
            Integer maxCustomerCount,
            String agentDecisionMode) throws IOException {
        return loadGeneratedCohortAgents(cohortId, maxCustomerCount, agentDecisionMode, Map.of());
    }

    private List<Map<String, Object>> loadGeneratedCohortAgents(
            String cohortId,
            Integer maxCustomerCount,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) throws IOException {
        String safeCohortId = safeCohortId(cohortId);
        Path customersPath = workspaceRoot
                .resolve(GENERATED_COHORTS_WORKSPACE_PATH)
                .resolve(safeCohortId)
                .resolve("customers.json")
                .normalize();
        if (!customersPath.startsWith(workspaceRoot.normalize())) {
            throw new IllegalArgumentException("Invalid generated cohort path for cohortId: " + cohortId);
        }
        if (!Files.exists(customersPath)) {
            throw new java.nio.file.NoSuchFileException(customersPath.toString());
        }
        List<Map<String, Object>> rawAgents = objectMapper.readValue(
                Files.readString(customersPath),
                new TypeReference<>() {
                });
        if (rawAgents.isEmpty()) {
            throw new IllegalArgumentException("Generated cohort " + cohortId + " has no customers.");
        }
        if (maxCustomerCount != null && rawAgents.size() > maxCustomerCount) {
            throw new IllegalArgumentException(
                    "Generated cohort " + safeCohortId + " has " + rawAgents.size()
                            + " customers, but this Scenario Lab execution supports at most "
                            + maxCustomerCount
                            + ". Use a validated cohort within the configured Clock-Driven Simulation limit.");
        }
        log.info("Loading banking Scenario Lab generated cohort {} with {} customers.",
                safeCohortId,
                rawAgents.size());
        return normalizeAgents(rawAgents, "stub", agentDecisionMode, agentDecisionSettings);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private List<Map<String, Object>> loadSeededAgents(int cohortSize, boolean includeHardshipCohort) throws IOException {
        List<Map<String, Object>> rawAgents = objectMapper.readValue(readSeededAgentsJson(), new TypeReference<>() {
        });
        List<Map<String, Object>> normalized = normalizeAgents(
                rawAgents.subList(0, Math.min(cohortSize, rawAgents.size())),
                "deterministic");
        if (includeHardshipCohort) {
            normalized.add(policyReliefDesk());
            normalized.add(hardshipEligibleHousehold());
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeAgents(List<Map<String, Object>> rawAgents, String defaultModel) {
        return normalizeAgents(rawAgents, defaultModel, AGENT_DECISION_MODE_DETERMINISTIC);
    }

    private List<Map<String, Object>> normalizeAgents(
            List<Map<String, Object>> rawAgents,
            String defaultModel,
            String agentDecisionMode) {
        return normalizeAgents(rawAgents, defaultModel, agentDecisionMode, Map.of());
    }

    private List<Map<String, Object>> normalizeAgents(
            List<Map<String, Object>> rawAgents,
            String defaultModel,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) {
        String normalizedDecisionMode = normalizeAgentDecisionMode(agentDecisionMode);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> rawAgent : rawAgents) {
            Map<String, Object> agent = deepCopy(rawAgent);
            agent.put("domainId", "banking");
            agent.put("provider", firstNonBlank(stringValue(agent.get("provider")), "stub"));
            agent.put("model", firstNonBlank(stringValue(agent.get("model")), defaultModel));
            agent.put("interactionMode", firstNonBlank(stringValue(agent.get("interactionMode")), "SIMULATION"));
            agent.put("autonomousDecisionEnabled",
                    booleanValue(agent.get("autonomousDecisionEnabled"), false));
            agent.putIfAbsent("autonomousDecisionIntervalMinutes", 60);
            applyAgentDecisionMode(agent, normalizedDecisionMode, agentDecisionSettings);
            agent.put("goals", normalizeGoals(listOfMaps(agent.get("goals"))));
            agent.put("initialAccounts", normalizeAccounts(listOfMaps(agent.get("initialAccounts"))));
            normalized.add(agent);
        }
        return normalized;
    }

    private String normalizeAgentDecisionMode(String agentDecisionMode) {
        String normalized = firstNonBlank(agentDecisionMode, AGENT_DECISION_MODE_DETERMINISTIC)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case AGENT_DECISION_MODE_STUB_LLM -> AGENT_DECISION_MODE_STUB_LLM;
            case AGENT_DECISION_MODE_REAL_LLM -> AGENT_DECISION_MODE_REAL_LLM;
            default -> AGENT_DECISION_MODE_DETERMINISTIC;
        };
    }

    private void applyAgentDecisionMode(
            Map<String, Object> agent,
            String agentDecisionMode,
            Map<String, Object> agentDecisionSettings) {
        if (!AGENT_DECISION_MODE_STUB_LLM.equals(agentDecisionMode)
                && !AGENT_DECISION_MODE_REAL_LLM.equals(agentDecisionMode)) {
            return;
        }
        Map<String, Object> settings = agentDecisionSettings == null ? Map.of() : agentDecisionSettings;
        String cadence = normalizeAgentDecisionCadence(settings.get("agentDecisionCadence"));
        int intervalMinutes = agentDecisionIntervalMinutes(cadence);

        if (AGENT_DECISION_MODE_REAL_LLM.equals(agentDecisionMode)) {
            Map<String, Object> realLlmBudget = realLlmBudget(settings);
            agent.put("provider", realLlmBudget.get("provider"));
            agent.put("model", realLlmBudget.get("model"));
            agent.put("realLlmMaxDecisionCalls", realLlmBudget.get("maxCalls"));
            agent.put("realLlmBudget", realLlmBudget);
        } else {
            agent.put("provider", "stub");
            agent.put("model", "stub");
        }
        agent.put("interactionMode", "HYBRID");
        agent.put("autonomousDecisionEnabled", true);
        agent.put("autonomousDecisionCadence", cadence);
        agent.put("autonomousDecisionIntervalMinutes", intervalMinutes);
        agent.remove("autonomousDecisionPhaseOffsetMinutes");

        LinkedHashSet<String> traits = new LinkedHashSet<>(stringList(agent.get("traits")));
        traits.add("scenario-lab:hybrid-eligible");
        traits.add("scenario-lab:decision-policy:bounded-savings-micro-v1");
        traits.add(AGENT_DECISION_MODE_REAL_LLM.equals(agentDecisionMode)
                ? "agent-decision-mode:real-llm-canary"
                : "agent-decision-mode:stub-llm");
        agent.put("traits", new ArrayList<>(traits));
    }

    private Map<String, Object> realLlmBudget(Map<String, Object> settings) {
        Map<String, Object> supplied = objectMap(objectMap(settings).get("realLlmBudget"));
        requireRealLlmCanaryModel(supplied);
        int maxCalls = requiredIntValue(supplied, "maxCalls", "REAL_LLM canary budget");
        double maxUsd = requiredFiniteDoubleValue(supplied, "maxUsd", "REAL_LLM canary budget");
        int maxInputTokens = requiredIntValue(supplied, "maxInputTokens", "REAL_LLM canary budget");
        int maxOutputTokens = requiredIntValue(supplied, "maxOutputTokens", "REAL_LLM canary budget");
        if (maxCalls <= 0 || maxCalls > REAL_LLM_DEFAULT_MAX_CALLS) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxCalls must be between 1 and " + REAL_LLM_DEFAULT_MAX_CALLS + ".");
        }
        if (maxUsd <= 0.0d || maxUsd > REAL_LLM_DEFAULT_MAX_USD) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary maxUsd must be between 0 and " + REAL_LLM_DEFAULT_MAX_USD + ".");
        }
        if (maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("REAL_LLM canary token budgets must be positive.");
        }
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("provider", REAL_LLM_DEFAULT_PROVIDER);
        budget.put("model", REAL_LLM_DEFAULT_MODEL);
        budget.put("maxCalls", maxCalls);
        budget.put("maxUsd", maxUsd);
        budget.put("maxInputTokens", maxInputTokens);
        budget.put("maxOutputTokens", maxOutputTokens);
        budget.put("stopPolicy", firstNonBlank(stringValue(supplied.get("stopPolicy")), "STOP_AT_BUDGET"));
        return budget;
    }

    private void requireRealLlmCanaryModel(Map<String, Object> suppliedBudget) {
        String provider = requiredStringValue(suppliedBudget, "provider", "REAL_LLM canary budget");
        String model = requiredStringValue(suppliedBudget, "model", "REAL_LLM canary budget");
        if (!REAL_LLM_DEFAULT_PROVIDER.equals(provider) || !REAL_LLM_DEFAULT_MODEL.equals(model)) {
            throw new IllegalArgumentException(
                    "REAL_LLM canary runs are restricted to "
                            + REAL_LLM_DEFAULT_PROVIDER
                            + "/"
                            + REAL_LLM_DEFAULT_MODEL
                            + ".");
        }
    }

    private String normalizeAgentDecisionCadence(Object cadence) {
        String normalized = firstNonBlank(stringValue(cadence), AGENT_DECISION_CADENCE_DAILY)
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        return switch (normalized) {
            case AGENT_DECISION_CADENCE_EVENT_ONLY -> AGENT_DECISION_CADENCE_EVENT_ONLY;
            case AGENT_DECISION_CADENCE_HOURLY -> AGENT_DECISION_CADENCE_HOURLY;
            case AGENT_DECISION_CADENCE_WEEKLY -> AGENT_DECISION_CADENCE_WEEKLY;
            default -> AGENT_DECISION_CADENCE_DAILY;
        };
    }

    private int agentDecisionIntervalMinutes(String cadence) {
        return switch (cadence) {
            case AGENT_DECISION_CADENCE_EVENT_ONLY -> 0;
            case AGENT_DECISION_CADENCE_HOURLY -> 60;
            case AGENT_DECISION_CADENCE_WEEKLY -> 7 * 24 * 60;
            default -> 24 * 60;
        };
    }

    private String safeCohortId(String cohortId) {
        String normalized = stringValue(cohortId);
        if (normalized.isBlank() || normalized.contains("..")
                || normalized.contains("/")
                || normalized.contains("\\")
                || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid generated cohort id: " + cohortId);
        }
        return normalized;
    }

    private String readSeededAgentsJson() throws IOException {
        Path demoAgentsPath = workspaceRoot.resolve(SEEDED_AGENTS_WORKSPACE_PATH);
        if (Files.exists(demoAgentsPath)) {
            return Files.readString(demoAgentsPath);
        }

        ClassPathResource resource = new ClassPathResource(SEEDED_AGENTS_CLASSPATH_RESOURCE);
        if (resource.exists()) {
            log.info("Loading banking Scenario Lab seeded agents from classpath resource {}", SEEDED_AGENTS_CLASSPATH_RESOURCE);
            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        throw new java.nio.file.NoSuchFileException(demoAgentsPath.toString());
    }

    private List<Map<String, Object>> normalizeGoals(List<Map<String, Object>> goals) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> goal : goals) {
            Map<String, Object> copy = deepCopy(goal);
            if (!copy.containsKey("targetAmount") && copy.containsKey("targetMetric")) {
                copy.put("targetAmount", toDouble(copy.get("targetMetric"), 0.0));
                copy.remove("targetMetric");
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeAccounts(List<Map<String, Object>> accounts) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> account : accounts) {
            Map<String, Object> copy = deepCopy(account);
            copy.putIfAbsent("currency", "AUD");
            copy.putIfAbsent("bsb", "123456");
            if (!copy.containsKey("limit") && copy.containsKey("creditLimit")) {
                copy.put("limit", copy.get("creditLimit"));
                copy.remove("creditLimit");
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private Map<String, Object> policyReliefDesk() {
        return Map.of(
                "agentType", "Policy",
                "domainId", "banking",
                "personaName", "Policy Relief Desk",
                "personaDescription", "Deterministic policy agent that issues hardship relief when household liquidity drops below the safety threshold.",
                "dateOfBirth", "1970-01-01",
                "traits", List.of("segment:policy-ops", "policy:hardship-relief"),
                "goals", List.of(),
                "model", "deterministic",
                "initialAccounts", List.of(Map.of(
                        "name", "policy-relief-fund",
                        "productCode", "TS001",
                        "balance", 10_000,
                        "currency", "AUD",
                        "bsb", "123456")),
                "autonomousDecisionEnabled", false);
    }

    private Map<String, Object> hardshipEligibleHousehold() {
        return Map.of(
                "personaName", "Hana Relief Eligible",
                "domainId", "banking",
                "personaDescription", "Synthetic household with very low liquid cash that qualifies for hardship relief.",
                "dateOfBirth", "1992-04-03",
                "traits", List.of("segment:hardship", "channel:assisted"),
                "goals", List.of(Map.of(
                        "goalId", "recover_liquidity",
                        "description", "Stabilize daily liquidity and avoid missed payments.",
                        "targetAmount", 1000,
                        "priority", "high")),
                "initialAccounts", List.of(
                        Map.of(
                                "name", "hana-everyday",
                                "productCode", "TS001",
                                "balance", 45,
                                "currency", "AUD",
                                "bsb", "123456"),
                        Map.of(
                                "name", "hana-card",
                                "productCode", "CC001",
                                "balance", -900,
                                "limit", 2000,
                                "currency", "AUD",
                                "bsb", "123456")),
                "autonomousDecisionEnabled", false);
    }

    private ProductMetadata fetchProductMetadata(String productCode) {
        String normalizedProductCode = firstNonBlank(productCode, "UNKNOWN").toUpperCase(Locale.ROOT);
        String productCatalogUrl = stringValue(bankingProductEndpointUrl).isBlank()
                ? ""
                : bankingProductEndpointUrl + "/api/products";
        if (!productCatalogUrl.isBlank()) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        productCatalogUrl, HttpMethod.GET, HttpEntity.EMPTY, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    List<Map<String, Object>> products = listOfMaps(nestedValue(parseJson(response.getBody()), "data.products"));
                    for (Map<String, Object> product : products) {
                        String catalogProductCode = firstNonBlank(
                                stringValue(product.get("productCode")),
                                stringValue(product.get("productId")));
                        if (normalizedProductCode.equalsIgnoreCase(catalogProductCode)) {
                            String category = stringValue(product.get("productCategory"));
                            return new ProductMetadata(
                                    catalogProductCode.toUpperCase(Locale.ROOT),
                                    stringValue(product.get("name")),
                                    category,
                                    balanceSheetSideForCategory(category));
                        }
                    }
                } else {
                    log.info("Banking product endpoint unavailable at {} (status {}); using banking domain defaults.",
                            productCatalogUrl,
                            response.getStatusCode().value());
                }
            } catch (RuntimeException error) {
                log.info("Banking product endpoint unavailable at {}; using banking domain defaults: {}",
                        productCatalogUrl,
                        error.getMessage());
            }
        }
        return defaultProductMetadata(normalizedProductCode);
    }

    private ProductMetadata defaultProductMetadata(String productCode) {
        return switch (productCode) {
            case "TS001" -> new ProductMetadata(productCode, "Standard Saving Product", "TRANS_AND_SAVINGS_ACCOUNTS", "LIABILITY");
            case "SAV001" -> new ProductMetadata(productCode, "NetBank Saver", "TRANS_AND_SAVINGS_ACCOUNTS", "LIABILITY");
            case "CC001" -> new ProductMetadata(productCode, "Standard Credit Card", "CRED_AND_CHRG_CARDS", "ASSET");
            case "CC002" -> new ProductMetadata(productCode, "Low Rate credit card", "CRED_AND_CHRG_CARDS", "ASSET");
            case "HL001" -> new ProductMetadata(productCode, "Standard Home Loan", "RESIDENTIAL_MORTGAGES", "ASSET");
            case "SL001" -> new ProductMetadata(productCode, "Student Loan", "PERS_LOANS", "ASSET");
            case "CL001" -> new ProductMetadata(productCode, "Car Loan", "PERS_LOANS", "ASSET");
            case "BL001" -> new ProductMetadata(productCode, "Business Loan", "BUSINESS_LOANS", "ASSET");
            case "TD001" -> new ProductMetadata(productCode, "Term Deposit", "TERM_DEPOSITS", "LIABILITY");
            default -> new ProductMetadata(productCode, productCode, "UNKNOWN", "UNKNOWN");
        };
    }

    private String balanceSheetSideForCategory(String category) {
        String normalized = stringValue(category).toUpperCase(Locale.ROOT);
        return normalized.contains("CRED")
                || normalized.contains("LOAN")
                || normalized.contains("MORTGAGE")
                ? "ASSET"
                : "LIABILITY";
    }

    private Map<String, Object> bankingPolicyPackEventResponse(
            ScenarioEvent event,
            List<BankingPolicyScenarioAgentSnapshot> agents) {
        String operation = (event.name() + " " + event.path()).toLowerCase(Locale.ROOT);
        Map<String, Object> body = objectMap(event.body());
        if (operation.contains("rate-change")) {
            String productCode = stringValue(body.get("productCode")).toUpperCase(Locale.ROOT);
            BigDecimal rate = decimal(body.get("rate"));
            return linkedMap(
                    "message", "Rate updated successfully",
                    "executionMode", "remote-domain-policy-pack",
                    "productCode", productCode,
                    "newRate", rate,
                    "affectedAgents", countAgentsWithProduct(agents, productCode));
        }
        if (operation.contains("limit-change")) {
            String productCode = stringValue(body.get("productCode")).toUpperCase(Locale.ROOT);
            BigDecimal limit = decimal(body.get("limit"));
            return linkedMap(
                    "message", "Credit limit updated successfully",
                    "executionMode", "remote-domain-policy-pack",
                    "productCode", productCode,
                    "newLimit", limit,
                    "affectedAgents", countAgentsWithProduct(agents, productCode));
        }
        if (operation.contains("stimulus")) {
            BigDecimal amount = decimal(body.get("amount"));
            int notifiedAgents = (int) agents.stream().filter(agent -> !agent.policyAgent()).count();
            return linkedMap(
                    "message", "Stimulus distributed",
                    "executionMode", "remote-domain-policy-pack",
                    "amount", amount,
                    "fundingSource", stringValue(body.get("fundingSource")),
                    "injectedAgents", countAgentsWithDepositAccount(agents),
                    "notifiedAgents", notifiedAgents);
        }
        if (operation.contains("crash")) {
            int notifiedAgents = (int) agents.stream().filter(agent -> !agent.policyAgent()).count();
            return linkedMap(
                    "message", "Market crash broadcasted",
                    "executionMode", "remote-domain-policy-pack",
                    "notifiedAgents", notifiedAgents);
        }
        if (operation.contains("hardship")) {
            BigDecimal threshold = body.containsKey("hardshipThreshold")
                    ? decimal(body.get("hardshipThreshold"))
                    : BigDecimal.valueOf(100);
            BigDecimal subsidyAmount = body.containsKey("subsidyAmount")
                    ? decimal(body.get("subsidyAmount"))
                    : BigDecimal.valueOf(500);
            List<BankingPolicyScenarioAgentSnapshot> reliefRecipients = agents.stream()
                    .filter(agent -> !agent.policyAgent())
                    .filter(agent -> liquidCashBalance(agent).compareTo(threshold) < 0)
                    .toList();
            List<String> recipientNames = reliefRecipients.stream()
                    .map(agent -> firstNonBlank(agent.personaName(), agent.agentId()))
                    .toList();
            List<String> policyAgentNames = agents.stream()
                    .filter(BankingPolicyScenarioAgentSnapshot::policyAgent)
                    .map(agent -> firstNonBlank(agent.personaName(), agent.agentId()))
                    .toList();
            int analysedAgents = (int) agents.stream().filter(agent -> !agent.policyAgent()).count();
            return linkedMap(
                    "message", "Hardship scan completed",
                    "executionMode", "remote-domain-policy-pack",
                    "activeAgentsSeen", agents.size(),
                    "policyAgentsScanned", policyAgentNames.size(),
                    "policyAgentNames", policyAgentNames,
                    "analysedAgents", analysedAgents,
                    "hardshipDetections", reliefRecipients.size(),
                    "subsidiesIssued", reliefRecipients.size(),
                    "hardshipThreshold", threshold,
                    "subsidyAmount", subsidyAmount,
                    "fundingSource", stringValue(body.get("fundingSource")),
                    "reliefRecipients", recipientNames,
                    "skippedCooldownCount", 0,
                    "insufficientFundCount", 0);
        }
        if (operation.contains("fraud")) {
            String productCode = stringValue(body.get("productCode")).toUpperCase(Locale.ROOT);
            String severity = firstNonBlank(stringValue(body.get("severity")), "HIGH");
            String channel = firstNonBlank(stringValue(body.get("channel")), "CARD");
            return linkedMap(
                    "message", "Fraud alert broadcasted",
                    "executionMode", "remote-domain-policy-pack",
                    "productCode", productCode,
                    "severity", severity,
                    "channel", channel,
                    "alertMessage", firstNonBlank(stringValue(body.get("message")), ""),
                    "alertedAgents", countAgentsWithProduct(agents, productCode),
                    "affectedAccounts", countAccountsWithProduct(agents, productCode));
        }
        return linkedMap(
                "message", "Policy event applied",
                "executionMode", "remote-domain-policy-pack");
    }

    private int countAgentsWithProduct(List<BankingPolicyScenarioAgentSnapshot> agents, String productCode) {
        return (int) agents.stream()
                .filter(agent -> agent.accounts().stream()
                        .anyMatch(account -> productCode.equalsIgnoreCase(account.productCode())))
                .count();
    }

    private int countAccountsWithProduct(List<BankingPolicyScenarioAgentSnapshot> agents, String productCode) {
        return (int) agents.stream()
                .flatMap(agent -> agent.accounts().stream())
                .filter(account -> productCode.equalsIgnoreCase(account.productCode()))
                .count();
    }

    private int countAgentsWithDepositAccount(List<BankingPolicyScenarioAgentSnapshot> agents) {
        return (int) agents.stream()
                .filter(agent -> !agent.policyAgent())
                .filter(agent -> agent.accounts().stream()
                        .anyMatch(account -> account.limit().compareTo(BigDecimal.ZERO) <= 0))
                .count();
    }

    private BigDecimal liquidCashBalance(BankingPolicyScenarioAgentSnapshot agent) {
        return agent.accounts().stream()
                .filter(account -> account.limit().compareTo(BigDecimal.ZERO) <= 0)
                .map(BankingPolicyScenarioAgentSnapshot.BankingPolicyScenarioAccountSnapshot::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<String> evaluateExpectations(Object payload, Map<String, Object> expectations) {
        List<String> failures = new ArrayList<>();
        Map<String, Object> min = objectMap(expectations.get("min"));
        for (Map.Entry<String, Object> entry : min.entrySet()) {
            if (toDouble(nestedValue(payload, entry.getKey()), Double.NaN) < toDouble(entry.getValue(), 0.0)) {
                failures.add("min:" + entry.getKey());
            }
        }
        Map<String, Object> equals = objectMap(expectations.get("equals"));
        for (Map.Entry<String, Object> entry : equals.entrySet()) {
            if (!Objects.equals(stringValue(nestedValue(payload, entry.getKey())), stringValue(entry.getValue()))) {
                failures.add("equals:" + entry.getKey());
            }
        }
        String errorContains = stringValue(expectations.get("errorContains"));
        if (!errorContains.isBlank() && !objectMapper.valueToTree(payload).toString().contains(errorContains)) {
            failures.add("errorContains");
        }
        return failures;
    }

    private ScenarioEvent toScenarioEvent(
            BankingPolicyInterventionCompiler.PolicyIntervention intervention) {
        Map<String, Object> facts = intervention.facts();
        return switch (intervention.type()) {
            case RATE_CHANGE -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/rate-change",
                    facts,
                    Map.of(
                            "min", Map.of("affectedAgents", 1),
                            "equals", Map.of(
                                    "message", "Rate updated successfully",
                                    "productCode", facts.get("productCode"))),
                    intervention.scheduledAfterSeconds());
            case LIMIT_CHANGE -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/limit-change",
                    facts,
                    Map.of(
                            "min", Map.of("affectedAgents", 1),
                            "equals", Map.of(
                                    "message", "Credit limit updated successfully",
                                    "productCode", facts.get("productCode"))),
                    intervention.scheduledAfterSeconds());
            case STIMULUS -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/stimulus",
                    facts,
                    Map.of(
                            "min", Map.of("notifiedAgents", 1),
                            "equals", Map.of("message", "Stimulus distributed")),
                    intervention.scheduledAfterSeconds());
            case MARKET_CRASH -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/crash",
                    facts,
                    Map.of(
                            "min", Map.of("notifiedAgents", 1),
                            "equals", Map.of("message", "Market crash broadcasted")),
                    intervention.scheduledAfterSeconds());
            case HARDSHIP_SCAN -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/hardship-scan",
                    facts,
                    Map.of("equals", Map.of("message", "Hardship scan completed")),
                    intervention.scheduledAfterSeconds());
            case FRAUD_ALERT -> new ScenarioEvent(
                    intervention.interventionId(),
                    BANKING_POLICY_PACK_SERVICE,
                    "POST",
                    "/policy/fraud-alert",
                    facts,
                    Map.of("equals", Map.of("message", "Fraud alert broadcasted")),
                    intervention.scheduledAfterSeconds());
        };
    }

    private List<BatchStep> buildBatchSteps(int simulationHorizonDays, int prePolicyDays, String cadenceProfile) {
        return switch (stringValue(cadenceProfile)) {
            case "ADAPTIVE_15M_FIRST_24H_THEN_1H" -> buildDetailedBatchSteps(simulationHorizonDays);
            default -> buildDemoFastBatchSteps(simulationHorizonDays, prePolicyDays);
        };
    }

    private List<BatchStep> buildDetailedBatchSteps(int simulationHorizonDays) {
        int totalMinutes = simulationHorizonDays * 1440;
        List<Integer> boundaries = new ArrayList<>();
        for (int boundary = 15; boundary <= Math.min(1440, totalMinutes); boundary += 15) {
            boundaries.add(boundary);
        }
        for (int boundary = 1500; boundary <= totalMinutes; boundary += 60) {
            boundaries.add(boundary);
        }
        if (boundaries.isEmpty() || boundaries.getLast() != totalMinutes) {
            boundaries.add(totalMinutes);
        }
        return toBatchSteps(boundaries, false, 0, totalMinutes);
    }

    private List<BatchStep> buildDemoFastBatchSteps(int simulationHorizonDays, int prePolicyDays) {
        int totalMinutes = simulationHorizonDays * 1440;
        int forkMinutes = Math.min(Math.max(prePolicyDays, 0) * 1440, totalMinutes);
        int fastWindowStart = Math.max(forkMinutes - 720, 0);
        int fastWindowEnd = Math.min(forkMinutes + 1440, totalMinutes);
        int fastStepMinutes = 360;
        Set<Integer> boundaries = new LinkedHashSet<>();
        for (int boundary = 1440; boundary < fastWindowStart; boundary += 1440) {
            boundaries.add(boundary);
        }
        if (fastWindowStart > 0) {
            boundaries.add(fastWindowStart);
        }
        for (int boundary = fastWindowStart + fastStepMinutes; boundary <= fastWindowEnd; boundary += fastStepMinutes) {
            boundaries.add(boundary);
        }
        int nextDailyBoundary = ((fastWindowEnd / 1440) + 1) * 1440;
        for (int boundary = nextDailyBoundary; boundary <= totalMinutes; boundary += 1440) {
            boundaries.add(boundary);
        }
        boundaries.add(totalMinutes);
        List<Integer> sortedBoundaries = new ArrayList<>(boundaries);
        sortedBoundaries.sort(Integer::compareTo);
        return toBatchSteps(sortedBoundaries, true, forkMinutes, fastWindowEnd);
    }

    private List<BatchStep> toBatchSteps(List<Integer> boundaries, boolean demoFast, int forkMinutes, int fastWindowEnd) {
        List<BatchStep> steps = new ArrayList<>();
        int previous = 0;
        for (Integer boundary : boundaries) {
            int stepMinutes = boundary - previous;
            int finalBoundary = boundaries.getLast();
            boolean capture = !demoFast
                    || boundary % 1440 == 0
                    || boundary == forkMinutes
                    || boundary == fastWindowEnd
                    || boundary.equals(finalBoundary);
            if (demoFast) {
                capture = boundary == 1440
                        || boundary == forkMinutes
                        || boundary == fastWindowEnd
                        || boundary.equals(finalBoundary);
            }
            steps.add(new BatchStep(stepMinutes * 60L, formatElapsedLabel(boundary), capture));
            previous = boundary;
        }
        return steps;
    }

    private String formatElapsedLabel(int elapsedMinutes) {
        int days = elapsedMinutes / 1440;
        int remainingMinutes = elapsedMinutes % 1440;
        int hours = remainingMinutes / 60;
        int minutes = remainingMinutes % 60;
        if (days > 0 && hours == 0 && minutes == 0) {
            return "D" + days;
        }
        StringBuilder suffix = new StringBuilder();
        if (hours > 0) {
            suffix.append(hours).append("h");
        }
        if (minutes > 0) {
            suffix.append(minutes).append("m");
        }
        if (suffix.isEmpty()) {
            suffix.append("0m");
        }
        return days > 0 ? "D" + days + "+" + suffix : "T+" + suffix;
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private void writeAgentSnapshotsJson(Path path, List<BankingPolicyScenarioAgentSnapshot> agents) throws IOException {
        writeJson(path, agents.stream().map(BankingPolicyScenarioAgentSnapshot::rawPayload).toList());
    }

    private Object parseJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return Map.of("raw", value);
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> value) {
        return objectMapper.convertValue(value, new TypeReference<>() {
        });
    }

    private static int intEnv(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double doubleEnv(String name, double defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String stringEnv(String name, String defaultValue) {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? defaultValue : raw.trim();
    }

    record ExecutionPlan(
            ProductMetadata productMetadata,
            List<Map<String, Object>> seededAgents,
            List<ScenarioEvent> baselineEvents,
            List<ScenarioEvent> candidateEvents,
            List<BatchStep> batchSteps) {
    }

    record ScenarioEvent(
            String name,
            String service,
            String method,
            String path,
            Map<String, Object> body,
            Map<String, Object> expectations,
            long afterElapsedSeconds) {
    }

    record ScenarioEventResult(
            String name,
            String service,
            String method,
            String path,
            long afterElapsedSeconds,
            Map<String, Object> requestPayload,
            boolean ok,
            Map<String, Object> responsePayload,
            String failureReason) {
    }

    record BatchStep(long seconds, String label, boolean capture) {
    }

    record CheckpointSnapshot(
            String label,
            long elapsedSeconds,
            long currentTimeEpochMs,
            List<BankingPolicyScenarioAgentSnapshot> agents) {
        Map<String, Object> asMap() {
            return linkedMap(
                    "label", label,
                    "elapsedSeconds", elapsedSeconds,
                    "currentTimeEpochMs", currentTimeEpochMs,
                    "agents", agents.stream().map(BankingPolicyScenarioAgentSnapshot::rawPayload).toList());
        }
    }

    record ScenarioExecutionResult(
            String branchType,
            String suiteRunId,
            Map<String, Object> suiteResult,
            List<BankingPolicyScenarioAgentSnapshot> agentsInitial,
            List<BankingPolicyScenarioAgentSnapshot> agentsPostEvents,
            List<BankingPolicyScenarioAgentSnapshot> agentsAfter,
            List<CheckpointSnapshot> checkpoints,
            List<ScenarioEventResult> eventResults,
            Path scenarioRoot) {
    }

    record ProductMetadata(
            String productCode,
            String productName,
            String productCategory,
            String balanceSheetSide) {
    }
}
