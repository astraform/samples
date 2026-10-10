package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.*;

record BankingPolicyScenarioAgentSnapshot(
        String agentId,
        String runtimeIdentity,
        String personaName,
        String segment,
        List<String> traits,
        BigDecimal currentBalance,
        BigDecimal creditUtilisation,
        boolean hardshipFlag,
        boolean policyAgent,
        List<String> scheduledWorkFailures,
        List<BankingPolicyScenarioAccountSnapshot> accounts,
        Map<String, Object> rawPayload) {

    static BankingPolicyScenarioAgentSnapshot empty(String personaName) {
        return new BankingPolicyScenarioAgentSnapshot(
                "",
                "",
                firstNonBlank(personaName, "unknown"),
                "unclassified",
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                false,
                List.of(),
                List.of(),
                Map.of());
    }

    static BankingPolicyScenarioAgentSnapshot from(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = objectMap(payload);
        if (!normalizedPayload.containsKey("schedule") && normalizedPayload.containsKey("lifecycleSchedule")) {
            normalizedPayload.put("schedule", normalizedPayload.get("lifecycleSchedule"));
        }
        Map<String, Object> persona = objectMap(normalizedPayload.get("persona"));
        Map<String, Object> statusView = objectMap(normalizedPayload.get("statusView"));
        Map<String, Object> state = objectMap(normalizedPayload.get("state"));
        if (state.isEmpty()) {
            state = objectMap(statusView.get("customerState"));
        }
        List<String> traits = stringList(persona.get("traits"));
        String segment = traits.stream()
                .filter(trait -> trait.startsWith("segment:"))
                .map(trait -> trait.substring("segment:".length()))
                .findFirst()
                .orElse("unclassified");
        String agentType = stringValue(persona.get("agentType"));
        List<Map<String, Object>> accountPayloads = listOfMaps(normalizedPayload.get("accounts"));
        if (accountPayloads.isEmpty()) {
            accountPayloads = listOfMaps(statusView.get("accounts"));
        }
        List<BankingPolicyScenarioAccountSnapshot> accounts = accountPayloads.stream()
                .map(BankingPolicyScenarioAccountSnapshot::from)
                .toList();
        return new BankingPolicyScenarioAgentSnapshot(
                stringValue(normalizedPayload.get("agentId")),
                firstNonBlank(
                        stringValue(normalizedPayload.get("runtimeIdentity")),
                        stringValue(normalizedPayload.get("agentId"))),
                firstNonBlank(stringValue(persona.get("personaName")), "unknown"),
                segment,
                traits,
                decimal(state.get("currentBalance")),
                decimal(state.get("creditUtilisation")),
                booleanValue(state.get("hardshipFlag"), false),
                "Policy".equalsIgnoreCase(agentType),
                stringList(normalizedPayload.get("scheduledWorkFailures")),
                accounts,
                normalizedPayload);
    }

    boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    Map<String, Object> canonicalMap() {
        List<Map<String, Object>> canonicalAccounts = new ArrayList<>();
        for (BankingPolicyScenarioAccountSnapshot account : accounts) {
            canonicalAccounts.add(account.canonicalMap());
        }
        canonicalAccounts.sort(Comparator.comparing(account -> stringValue(account.get("productCode")) + "::" + stringValue(account.get("name"))));
        return linkedMap(
                "agentId", agentId,
                "runtimeIdentity", runtimeIdentity,
                "personaName", personaName,
                "traits", traits,
                "currentBalance", currentBalance.stripTrailingZeros().toPlainString(),
                "creditUtilisation", creditUtilisation.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                "hardshipFlag", hardshipFlag,
                "scheduledWorkFailures", scheduledWorkFailures,
                "accounts", canonicalAccounts);
    }

    BigDecimal totalProductBalance(String productCode) {
        return accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(account.productCode()))
                .map(BankingPolicyScenarioAccountSnapshot::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal totalProductLimit(String productCode) {
        return accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(account.productCode()))
                .map(BankingPolicyScenarioAccountSnapshot::limit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal availableCredit(String productCode) {
        return accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(account.productCode()))
                .map(BankingPolicyScenarioAccountSnapshot::availableCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal creditUtilisation(String productCode) {
        BigDecimal limit = totalProductLimit(productCode);
        if (limit.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal used = accounts.stream()
                .filter(account -> productCode.equalsIgnoreCase(account.productCode()))
                .map(BankingPolicyScenarioAccountSnapshot::balance)
                .filter(balance -> balance.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return used.divide(limit, 6, RoundingMode.HALF_UP);
    }

    BigDecimal availableCredit() {
        return accounts.stream()
                .map(BankingPolicyScenarioAccountSnapshot::availableCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal totalCreditLimit() {
        return accounts.stream()
                .map(BankingPolicyScenarioAccountSnapshot::limit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal totalBalance() {
        return accounts.stream()
                .map(BankingPolicyScenarioAccountSnapshot::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    record BankingPolicyScenarioAccountSnapshot(
            String productCode,
            String name,
            BigDecimal balance,
            BigDecimal limit) {

        static BankingPolicyScenarioAccountSnapshot from(Map<String, Object> payload) {
            return new BankingPolicyScenarioAccountSnapshot(
                    stringValue(payload.get("productCode")),
                    stringValue(payload.get("name")),
                    decimal(payload.get("balance")),
                    decimal(payload.get("limit")));
        }

        BigDecimal availableCredit() {
            return limit.compareTo(BigDecimal.ZERO) > 0 ? limit.add(balance) : BigDecimal.ZERO;
        }

        Map<String, Object> canonicalMap() {
            return linkedMap(
                    "productCode", productCode,
                    "name", name,
                    "balance", balance.stripTrailingZeros().toPlainString(),
                    "limit", limit.stripTrailingZeros().toPlainString());
        }
    }
}
