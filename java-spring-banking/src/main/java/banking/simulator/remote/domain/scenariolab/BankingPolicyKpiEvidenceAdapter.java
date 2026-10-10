package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class BankingPolicyKpiEvidenceAdapter {

    AdaptedEvidence adapt(
            List<Map<String, Object>> expectedAgents,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEvent> expectedBaselineEvents,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEvent> expectedCandidateEvents,
            Iterable<BankingPolicyScenarioAgentSnapshot> baselineAgents,
            Iterable<BankingPolicyScenarioAgentSnapshot> candidateAgents,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> baselineResults,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> candidateResults) {
        IdentityIndex identities = new IdentityIndex();
        identities.registerAll(baselineAgents);
        identities.registerAll(candidateAgents);
        requireExactCustomerManifest(expectedAgents, baselineAgents, candidateAgents);

        List<BankingPolicyKpiEvaluator.RealizedAction> actions = new ArrayList<>();
        List<BankingPolicyKpiEvaluator.InterventionEffect> effects = new ArrayList<>();
        adaptBranch(
                BankingPolicyKpiEvaluator.Branch.BASELINE,
                expectedBaselineEvents,
                baselineResults,
                identities,
                actions,
                effects);
        adaptBranch(
                BankingPolicyKpiEvaluator.Branch.CANDIDATE,
                expectedCandidateEvents,
                candidateResults,
                identities,
                actions,
                effects);
        return new AdaptedEvidence(actions, effects, identities.aliases, identities.policyAgents);
    }

    private void adaptBranch(
            BankingPolicyKpiEvaluator.Branch branch,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEvent> expectedEvents,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEventResult> results,
            IdentityIndex identities,
            List<BankingPolicyKpiEvaluator.RealizedAction> actions,
            List<BankingPolicyKpiEvaluator.InterventionEffect> effects) {
        Map<String, BankingPolicyScenarioExecutionRuntime.ScenarioEvent> remainingEvents = expectedEventsById(
                branch,
                expectedEvents);
        if (results == null) {
            throw incomplete("intervention results are missing");
        }
        for (BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result : results) {
            if (result == null) {
                throw incomplete("null intervention result");
            }
            String eventName = requiredText(result.name(), "event name");
            String evidenceId = branch.name().toLowerCase(Locale.ROOT) + ":" + eventName;
            BankingPolicyScenarioExecutionRuntime.ScenarioEvent expected = remainingEvents.remove(eventName);
            if (expected == null) {
                throw incomplete("unexpected or duplicate intervention evidence " + evidenceId);
            }
            requireExactPlanEvidence(expected, result, evidenceId);
            if (!result.ok()) {
                throw incomplete("failed intervention evidence " + evidenceId);
            }

            AdaptedIntervention intervention = switch (requiredText(result.path(), "event path")) {
                case "/policy/rate-change" -> rateChange(branch, evidenceId, result);
                case "/policy/limit-change" -> limitChange(branch, evidenceId, result);
                case "/policy/stimulus" -> stimulus(branch, evidenceId, result);
                case "/policy/crash" -> marketCrash(branch, evidenceId, result);
                case "/policy/hardship-scan" -> hardship(branch, evidenceId, result, identities);
                case "/policy/fraud-alert" -> fraudAlert(branch, evidenceId, result);
                default -> throw incomplete("unsupported intervention path " + result.path());
            };
            actions.add(intervention.action());
            effects.add(intervention.effect());
        }
        if (!remainingEvents.isEmpty()) {
            throw incomplete("missing intervention evidence "
                    + branch.name().toLowerCase(Locale.ROOT)
                    + ":"
                    + remainingEvents.keySet().iterator().next());
        }
    }

    private static Map<String, BankingPolicyScenarioExecutionRuntime.ScenarioEvent> expectedEventsById(
            BankingPolicyKpiEvaluator.Branch branch,
            List<BankingPolicyScenarioExecutionRuntime.ScenarioEvent> expectedEvents) {
        if (expectedEvents == null) {
            throw incomplete(branch.name().toLowerCase(Locale.ROOT) + " intervention plan is missing");
        }
        Map<String, BankingPolicyScenarioExecutionRuntime.ScenarioEvent> indexed = new LinkedHashMap<>();
        for (BankingPolicyScenarioExecutionRuntime.ScenarioEvent event : expectedEvents) {
            if (event == null) {
                throw incomplete("null intervention in " + branch.name().toLowerCase(Locale.ROOT) + " plan");
            }
            String eventId = requiredText(event.name(), "planned event name");
            if (indexed.putIfAbsent(eventId, event) != null) {
                throw incomplete("duplicate planned intervention "
                        + branch.name().toLowerCase(Locale.ROOT)
                        + ":"
                        + eventId);
            }
        }
        return indexed;
    }

    private static void requireExactPlanEvidence(
            BankingPolicyScenarioExecutionRuntime.ScenarioEvent expected,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult actual,
            String evidenceId) {
        if (!Objects.equals(expected.service(), actual.service())
                || !Objects.equals(expected.method(), actual.method())
                || !Objects.equals(expected.path(), actual.path())
                || expected.afterElapsedSeconds() != actual.afterElapsedSeconds()) {
            throw incomplete("intervention evidence does not match the compiled plan " + evidenceId);
        }
        if (!sameJsonValue(expected.body(), actual.requestPayload())) {
            throw incomplete("intervention facts do not match the compiled plan " + evidenceId);
        }
    }

    private static boolean sameJsonValue(Object expected, Object actual) {
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            if (!expectedMap.keySet().equals(actualMap.keySet())) {
                return false;
            }
            for (Object key : expectedMap.keySet()) {
                if (!sameJsonValue(expectedMap.get(key), actualMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            if (expectedList.size() != actualList.size()) {
                return false;
            }
            for (int index = 0; index < expectedList.size(); index++) {
                if (!sameJsonValue(expectedList.get(index), actualList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        BigDecimal expectedNumber = losslessNumber(expected);
        BigDecimal actualNumber = losslessNumber(actual);
        if (expectedNumber != null || actualNumber != null) {
            return expectedNumber != null
                    && actualNumber != null
                    && expectedNumber.compareTo(actualNumber) == 0;
        }
        return Objects.equals(expected, actual);
    }

    private static BigDecimal losslessNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        return null;
    }

    private static void requireExactCustomerManifest(
            List<Map<String, Object>> expectedAgents,
            Iterable<BankingPolicyScenarioAgentSnapshot> baselineAgents,
            Iterable<BankingPolicyScenarioAgentSnapshot> candidateAgents) {
        Map<String, Boolean> expected = expectedCustomerManifest(expectedAgents);
        requireCustomerManifest("baseline", expected, baselineAgents);
        requireCustomerManifest("candidate", expected, candidateAgents);
    }

    private static Map<String, Boolean> expectedCustomerManifest(List<Map<String, Object>> expectedAgents) {
        if (expectedAgents == null) {
            throw incomplete("customer manifest is missing");
        }
        Map<String, Boolean> manifest = new LinkedHashMap<>();
        for (Map<String, Object> agent : expectedAgents) {
            if (agent == null) {
                throw incomplete("customer manifest contains a null entry");
            }
            String identity = requiredText(agent.get("personaName"), "customer manifest personaName");
            boolean policyAgent = "Policy".equalsIgnoreCase(requiredOptionalText(agent.get("agentType")));
            if (manifest.putIfAbsent(identity, policyAgent) != null) {
                throw incomplete("customer manifest contains duplicate identity " + identity);
            }
        }
        return manifest;
    }

    private static void requireCustomerManifest(
            String branch,
            Map<String, Boolean> expected,
            Iterable<BankingPolicyScenarioAgentSnapshot> agents) {
        if (agents == null) {
            throw incomplete(branch + " customer evidence is missing");
        }
        Map<String, Boolean> actual = new LinkedHashMap<>();
        for (BankingPolicyScenarioAgentSnapshot agent : agents) {
            if (agent == null) {
                throw incomplete(branch + " customer evidence contains a null entry");
            }
            String identity = requiredText(agent.personaName(), branch + " customer personaName");
            if (actual.putIfAbsent(identity, agent.policyAgent()) != null) {
                throw incomplete(branch + " customer evidence contains duplicate identity " + identity);
            }
        }
        if (!expected.equals(actual)) {
            throw incomplete(branch + " customer manifest does not match the launch manifest");
        }
    }

    private static String requiredOptionalText(Object value) {
        if (value == null) {
            return "";
        }
        return requiredText(value, "optional text");
    }

    private AdaptedIntervention rateChange(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        Map<String, Object> request = requiredMap(result.requestPayload(), evidenceId + " request");
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        requireSameText(request, "productCode", response, "productCode", evidenceId);
        requireSameDecimal(request, "rate", response, "newRate", evidenceId);
        int affected = requiredCount(response, "affectedAgents", evidenceId);
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.RATE_CHANGE,
                BankingPolicyKpiEvaluator.RealizedActionKind.ACCOUNT_MUTATION,
                List.of(),
                affected,
                0,
                BigDecimal.ZERO,
                1);
    }

    private AdaptedIntervention limitChange(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        Map<String, Object> request = requiredMap(result.requestPayload(), evidenceId + " request");
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        requireSameText(request, "productCode", response, "productCode", evidenceId);
        requireSameDecimal(request, "limit", response, "newLimit", evidenceId);
        int affected = requiredCount(response, "affectedAgents", evidenceId);
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.LIMIT_CHANGE,
                BankingPolicyKpiEvaluator.RealizedActionKind.ACCOUNT_MUTATION,
                List.of(),
                affected,
                0,
                BigDecimal.ZERO,
                1);
    }

    private AdaptedIntervention stimulus(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        Map<String, Object> request = requiredMap(result.requestPayload(), evidenceId + " request");
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        BigDecimal amount = requireSameDecimal(request, "amount", response, "amount", evidenceId);
        BankingPolicyKpiEvaluator.FundingSource fundingSource = requireSameFundingSource(
                request,
                response,
                evidenceId);
        int injected = requiredCount(response, "injectedAgents", evidenceId);
        int notified = requiredCount(response, "notifiedAgents", evidenceId);
        if (injected > notified) {
            throw incomplete(evidenceId + " injectedAgents exceeds notifiedAgents");
        }
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.STIMULUS,
                BankingPolicyKpiEvaluator.RealizedActionKind.ACCOUNT_MUTATION,
                List.of(),
                injected,
                0,
                amount.multiply(BigDecimal.valueOf(injected)),
                injected,
                fundingSource);
    }

    private AdaptedIntervention marketCrash(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        int notified = requiredCount(response, "notifiedAgents", evidenceId);
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.MARKET_CRASH,
                BankingPolicyKpiEvaluator.RealizedActionKind.CUSTOMER_NOTIFICATION,
                List.of(),
                notified,
                0,
                BigDecimal.ZERO,
                1);
    }

    private AdaptedIntervention hardship(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result,
            IdentityIndex identities) {
        Map<String, Object> request = requiredMap(result.requestPayload(), evidenceId + " request");
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        int detections = requiredCount(response, "hardshipDetections", evidenceId);
        int issued = requiredCount(response, "subsidiesIssued", evidenceId);
        int skipped = requiredCount(response, "skippedCooldownCount", evidenceId);
        int insufficient = requiredCount(response, "insufficientFundCount", evidenceId);
        long accounted = (long) issued + skipped + insufficient;
        if (detections != accounted) {
            throw incomplete(evidenceId + " hardship outcome counts are contradictory");
        }
        BigDecimal subsidyAmount = requiredDecimal(response, "subsidyAmount", evidenceId);
        BankingPolicyKpiEvaluator.FundingSource fundingSource = requireSameFundingSource(
                request,
                response,
                evidenceId);
        if (subsidyAmount.signum() < 0) {
            throw incomplete(evidenceId + " subsidyAmount cannot be negative");
        }
        if (request.containsKey("subsidyAmount")) {
            requireEqualDecimal(
                    requiredDecimal(request, "subsidyAmount", evidenceId),
                    subsidyAmount,
                    evidenceId + " subsidyAmount");
        }

        List<String> recipients = requiredTextList(response, "reliefRecipients", evidenceId);
        List<String> canonicalRecipients = new ArrayList<>();
        Set<String> uniqueRecipients = new LinkedHashSet<>();
        for (String recipient : recipients) {
            String canonical = identities.canonical(recipient);
            if (identities.policyAgent(canonical)) {
                throw incomplete(evidenceId + " targets policy agent " + recipient);
            }
            if (!uniqueRecipients.add(canonical)) {
                throw incomplete(evidenceId + " contains duplicate recipient " + recipient);
            }
            canonicalRecipients.add(canonical);
        }
        if (canonicalRecipients.size() != issued) {
            throw incomplete(evidenceId + " reliefRecipients does not match subsidiesIssued");
        }
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.HARDSHIP_RELIEF,
                BankingPolicyKpiEvaluator.RealizedActionKind.ACCOUNT_MUTATION,
                canonicalRecipients,
                issued,
                0,
                subsidyAmount.multiply(BigDecimal.valueOf(issued)),
                issued,
                fundingSource);
    }

    private AdaptedIntervention fraudAlert(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyScenarioExecutionRuntime.ScenarioEventResult result) {
        Map<String, Object> request = requiredMap(result.requestPayload(), evidenceId + " request");
        Map<String, Object> response = requiredMap(result.responsePayload(), evidenceId + " response");
        requireSameText(request, "productCode", response, "productCode", evidenceId);
        requireSameText(request, "severity", response, "severity", evidenceId);
        requireSameText(request, "channel", response, "channel", evidenceId);
        int alertedAgents = requiredCount(response, "alertedAgents", evidenceId);
        int affectedAccounts = requiredCount(response, "affectedAccounts", evidenceId);
        if (affectedAccounts < alertedAgents) {
            throw incomplete(evidenceId + " affectedAccounts is below alertedAgents");
        }
        return intervention(
                branch,
                evidenceId,
                BankingPolicyKpiEvaluator.InterventionKind.FRAUD_ALERT,
                BankingPolicyKpiEvaluator.RealizedActionKind.CUSTOMER_NOTIFICATION,
                List.of(),
                alertedAgents,
                affectedAccounts,
                BigDecimal.ZERO,
                1);
    }

    private AdaptedIntervention intervention(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyKpiEvaluator.InterventionKind kind,
            BankingPolicyKpiEvaluator.RealizedActionKind actionKind,
            List<String> customerIds,
            int customerCount,
            int accountCount,
            BigDecimal amount,
            int occurrences) {
        return intervention(
                branch,
                evidenceId,
                kind,
                actionKind,
                customerIds,
                customerCount,
                accountCount,
                amount,
                occurrences,
                BankingPolicyKpiEvaluator.FundingSource.NONE);
    }

    private AdaptedIntervention intervention(
            BankingPolicyKpiEvaluator.Branch branch,
            String evidenceId,
            BankingPolicyKpiEvaluator.InterventionKind kind,
            BankingPolicyKpiEvaluator.RealizedActionKind actionKind,
            List<String> customerIds,
            int customerCount,
            int accountCount,
            BigDecimal amount,
            int occurrences,
            BankingPolicyKpiEvaluator.FundingSource fundingSource) {
        return new AdaptedIntervention(
                new BankingPolicyKpiEvaluator.RealizedAction(
                        branch, evidenceId, actionKind, kind.name(), true),
                new BankingPolicyKpiEvaluator.InterventionEffect(
                        branch,
                        evidenceId,
                        kind,
                        true,
                        customerIds,
                        customerCount,
                        accountCount,
                        amount,
                        occurrences,
                        fundingSource));
    }

    private static BankingPolicyKpiEvaluator.FundingSource requireSameFundingSource(
            Map<String, Object> request,
            Map<String, Object> response,
            String evidenceId) {
        String expected = requiredText(request.get("fundingSource"), evidenceId + " request.fundingSource");
        String actual = requiredText(response.get("fundingSource"), evidenceId + " response.fundingSource");
        if (!expected.equals(actual)) {
            throw incomplete(evidenceId + " fundingSource is contradictory");
        }
        try {
            BankingPolicyKpiEvaluator.FundingSource fundingSource =
                    BankingPolicyKpiEvaluator.FundingSource.valueOf(actual);
            if (fundingSource == BankingPolicyKpiEvaluator.FundingSource.NONE) {
                throw incomplete(evidenceId + " liquidity fundingSource cannot be NONE");
            }
            return fundingSource;
        } catch (IllegalArgumentException exception) {
            throw incomplete(evidenceId + " fundingSource is unsupported");
        }
    }

    private static void requireSameText(
            Map<String, Object> request,
            String requestKey,
            Map<String, Object> response,
            String responseKey,
            String evidenceId) {
        String expected = requiredText(request.get(requestKey), evidenceId + " request." + requestKey);
        String actual = requiredText(response.get(responseKey), evidenceId + " response." + responseKey);
        if (!expected.equalsIgnoreCase(actual)) {
            throw incomplete(evidenceId + " contradicts " + requestKey);
        }
    }

    private static BigDecimal requireSameDecimal(
            Map<String, Object> request,
            String requestKey,
            Map<String, Object> response,
            String responseKey,
            String evidenceId) {
        BigDecimal expected = requiredDecimal(request, requestKey, evidenceId);
        BigDecimal actual = requiredDecimal(response, responseKey, evidenceId);
        requireEqualDecimal(expected, actual, evidenceId + " " + requestKey);
        return actual;
    }

    private static void requireEqualDecimal(BigDecimal expected, BigDecimal actual, String label) {
        if (expected.compareTo(actual) != 0) {
            throw incomplete(label + " is contradictory");
        }
    }

    private static Map<String, Object> requiredMap(Map<String, Object> value, String label) {
        if (value == null) {
            throw incomplete(label + " is missing");
        }
        return value;
    }

    private static int requiredCount(Map<String, Object> source, String key, String evidenceId) {
        Object raw = source.get(key);
        BigInteger integer;
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            integer = BigInteger.valueOf(((Number) raw).longValue());
        } else if (raw instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else if (raw instanceof BigDecimal decimal) {
            try {
                integer = decimal.toBigIntegerExact();
            } catch (ArithmeticException exception) {
                throw incomplete(evidenceId + " " + key + " must be an integer");
            }
        } else {
            throw incomplete(evidenceId + " " + key + " must be an integer");
        }
        if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw incomplete(evidenceId + " " + key + " is outside the supported range");
        }
        return integer.intValueExact();
    }

    private static BigDecimal requiredDecimal(Map<String, Object> source, String key, String evidenceId) {
        Object raw = source.get(key);
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        throw incomplete(evidenceId + " " + key + " must be a lossless JSON number");
    }

    private static List<String> requiredTextList(Map<String, Object> source, String key, String evidenceId) {
        Object raw = source.get(key);
        if (!(raw instanceof List<?> values)) {
            throw incomplete(evidenceId + " " + key + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            result.add(requiredText(value, evidenceId + " " + key + " entry"));
        }
        return List.copyOf(result);
    }

    private static String requiredText(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
            throw incomplete(label + " must be a nonblank canonical string");
        }
        return text;
    }

    private static KpiEvidenceIncompleteException incomplete(String message) {
        return new KpiEvidenceIncompleteException("KPI evidence is incomplete: " + message);
    }

    record AdaptedEvidence(
            List<BankingPolicyKpiEvaluator.RealizedAction> actions,
            List<BankingPolicyKpiEvaluator.InterventionEffect> effects,
            Map<String, String> aliases,
            Map<String, Boolean> policyAgents) {
        AdaptedEvidence {
            actions = List.copyOf(actions);
            effects = List.copyOf(effects);
            aliases = Map.copyOf(aliases);
            policyAgents = Map.copyOf(policyAgents);
        }

        String canonicalCustomerId(String identity) {
            String canonical = aliases.get(identity);
            if (canonical == null) {
                throw incomplete("unknown customer identity " + identity);
            }
            return canonical;
        }

        boolean policyAgent(String identity) {
            return Boolean.TRUE.equals(policyAgents.get(canonicalCustomerId(identity)));
        }

        boolean affectedBy(
                BankingPolicyKpiEvaluator.Branch branch,
                BankingPolicyKpiEvaluator.InterventionKind kind,
                String identity) {
            String canonical = canonicalCustomerId(identity);
            return effects.stream().anyMatch(effect -> effect.branch() == branch
                    && effect.kind() == kind
                    && effect.applied()
                    && effect.affectedCustomerIds().contains(canonical));
        }
    }

    private record AdaptedIntervention(
            BankingPolicyKpiEvaluator.RealizedAction action,
            BankingPolicyKpiEvaluator.InterventionEffect effect) {
    }

    private static final class IdentityIndex {
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Map<String, Boolean> policyAgents = new LinkedHashMap<>();

        void registerAll(Iterable<BankingPolicyScenarioAgentSnapshot> agents) {
            for (BankingPolicyScenarioAgentSnapshot agent : agents) {
                register(agent);
            }
        }

        void register(BankingPolicyScenarioAgentSnapshot agent) {
            Objects.requireNonNull(agent, "agent");
            String canonical = firstIdentity(agent.agentId(), agent.runtimeIdentity(), agent.personaName());
            Boolean existingPolicyAgent = policyAgents.putIfAbsent(canonical, agent.policyAgent());
            if (existingPolicyAgent != null && existingPolicyAgent != agent.policyAgent()) {
                throw incomplete("customer policy-agent classification changed for " + canonical);
            }
            registerAlias(canonical, canonical);
            registerOptionalAlias(agent.agentId(), canonical);
            registerOptionalAlias(agent.runtimeIdentity(), canonical);
            registerOptionalAlias(agent.personaName(), canonical);
        }

        String canonical(String identity) {
            String value = aliases.get(requiredText(identity, "customer identity"));
            if (value == null) {
                throw incomplete("unknown customer identity " + identity);
            }
            return value;
        }

        boolean policyAgent(String canonical) {
            Boolean value = policyAgents.get(canonical);
            if (value == null) {
                throw incomplete("unknown canonical customer identity " + canonical);
            }
            return value;
        }

        private void registerOptionalAlias(String alias, String canonical) {
            if (alias != null && !alias.isBlank()) {
                registerAlias(requiredText(alias, "customer identity alias"), canonical);
            }
        }

        private void registerAlias(String alias, String canonical) {
            String existing = aliases.putIfAbsent(alias, canonical);
            if (existing != null && !existing.equals(canonical)) {
                throw incomplete("customer identity alias is contradictory: " + alias);
            }
        }

        private static String firstIdentity(String agentId, String runtimeIdentity, String personaName) {
            if (agentId != null && !agentId.isBlank()) {
                return requiredText(agentId, "agentId");
            }
            if (runtimeIdentity != null && !runtimeIdentity.isBlank()) {
                return requiredText(runtimeIdentity, "runtimeIdentity");
            }
            return requiredText(personaName, "personaName");
        }
    }

    static final class KpiEvidenceIncompleteException extends IllegalStateException {
        KpiEvidenceIncompleteException(String message) {
            super(message);
        }
    }
}
