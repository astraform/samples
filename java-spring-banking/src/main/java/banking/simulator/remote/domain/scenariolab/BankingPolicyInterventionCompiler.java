package banking.simulator.remote.domain.scenariolab;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.blank;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.blankToNull;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.booleanValue;
import static banking.simulator.remote.domain.scenariolab.BankingPolicyScenarioSupport.intValue;

final class BankingPolicyInterventionCompiler {

    InterventionPlan compile(ScenarioLabRunParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        List<PolicyIntervention> baseline = new ArrayList<>();
        baseline.add(rate(
                "policy_rate_baseline_start",
                parameters.getProductCode(),
                parameters.getBaselineRate(),
                0));
        if (!blank(parameters.getBaselineLimit())) {
            baseline.add(limit(
                    "policy_limit_baseline_start",
                    parameters.getProductCode(),
                    parameters.getBaselineLimit(),
                    0));
        }

        List<PolicyIntervention> candidate = new ArrayList<>();
        int prePolicyDays = intValue(parameters.getPrePolicyDays(), 10);
        long offsetSeconds = prePolicyDays > 0
                ? Math.multiplyExact((long) prePolicyDays, 86_400L)
                : 0L;
        if (offsetSeconds > 0) {
            candidate.add(rate(
                    "policy_rate_candidate_shared_start",
                    parameters.getProductCode(),
                    parameters.getBaselineRate(),
                    0));
            if (!blank(parameters.getBaselineLimit())) {
                candidate.add(limit(
                        "policy_limit_candidate_shared_start",
                        parameters.getProductCode(),
                        parameters.getBaselineLimit(),
                        0));
            }
            if (!Objects.equals(parameters.getCandidateRate(), parameters.getBaselineRate())) {
                candidate.add(rate(
                        "policy_rate_candidate_shift",
                        parameters.getProductCode(),
                        parameters.getCandidateRate(),
                        offsetSeconds));
            }
            if (!Objects.equals(
                    blankToNull(parameters.getCandidateLimit()),
                    blankToNull(parameters.getBaselineLimit()))
                    && !blank(parameters.getCandidateLimit())) {
                candidate.add(limit(
                        "policy_limit_candidate_shift",
                        parameters.getProductCode(),
                        parameters.getCandidateLimit(),
                        offsetSeconds));
            }
        } else {
            candidate.add(rate(
                    "policy_rate_candidate",
                    parameters.getProductCode(),
                    parameters.getCandidateRate(),
                    0));
            if (!blank(parameters.getCandidateLimit())) {
                candidate.add(limit(
                        "policy_limit_candidate",
                        parameters.getProductCode(),
                        parameters.getCandidateLimit(),
                        0));
            }
        }
        if (!"0".equals(parameters.getCandidateStimulusAmount())) {
            candidate.add(new PolicyIntervention(
                    "policy_candidate_stimulus",
                    InterventionType.STIMULUS,
                    offsetSeconds,
                    Map.of(
                            "amount", new BigDecimal(parameters.getCandidateStimulusAmount()),
                            "fundingSource", "EXTERNAL",
                            "description", "Wind-tunnel preset "
                                    + parameters.getCandidateStimulusAmount()
                                    + " stimulus overlay")));
        }
        if (booleanValue(parameters.getCandidateCrash(), false)) {
            candidate.add(new PolicyIntervention(
                    "policy_candidate_crash",
                    InterventionType.MARKET_CRASH,
                    offsetSeconds,
                    Map.of()));
        }
        if (booleanValue(parameters.getCandidateHardshipScan(), false)) {
            Map<String, Object> facts = new LinkedHashMap<>();
            if (!blank(parameters.getCandidateHardshipThreshold())) {
                facts.put("hardshipThreshold", new BigDecimal(parameters.getCandidateHardshipThreshold()));
            }
            if (!blank(parameters.getCandidateHardshipSubsidyAmount())) {
                facts.put("subsidyAmount", new BigDecimal(parameters.getCandidateHardshipSubsidyAmount()));
            }
            facts.put("fundingSource", "BANK");
            candidate.add(new PolicyIntervention(
                    "policy_candidate_hardship_scan",
                    InterventionType.HARDSHIP_SCAN,
                    offsetSeconds,
                    facts));
        }
        if (booleanValue(parameters.getCandidateFraudAlert(), false)) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("productCode", parameters.getProductCode());
            facts.put("severity", parameters.getCandidateFraudSeverity());
            facts.put("channel", parameters.getCandidateFraudChannel());
            if (!blank(parameters.getCandidateFraudMessage())) {
                facts.put("message", parameters.getCandidateFraudMessage());
            }
            candidate.add(new PolicyIntervention(
                    "policy_candidate_fraud_alert",
                    InterventionType.FRAUD_ALERT,
                    offsetSeconds,
                    facts));
        }
        return new InterventionPlan(baseline, candidate);
    }

    private PolicyIntervention rate(String id, String productCode, String rate, long offsetSeconds) {
        return new PolicyIntervention(
                id,
                InterventionType.RATE_CHANGE,
                offsetSeconds,
                Map.of("productCode", productCode, "rate", new BigDecimal(rate)));
    }

    private PolicyIntervention limit(String id, String productCode, String limit, long offsetSeconds) {
        return new PolicyIntervention(
                id,
                InterventionType.LIMIT_CHANGE,
                offsetSeconds,
                Map.of("productCode", productCode, "limit", new BigDecimal(limit)));
    }

    enum InterventionType {
        RATE_CHANGE,
        LIMIT_CHANGE,
        STIMULUS,
        MARKET_CRASH,
        HARDSHIP_SCAN,
        FRAUD_ALERT
    }

    record PolicyIntervention(
            String interventionId,
            InterventionType type,
            long scheduledAfterSeconds,
            Map<String, Object> facts) {
        PolicyIntervention {
            interventionId = Objects.requireNonNull(interventionId, "interventionId");
            type = Objects.requireNonNull(type, "type");
            facts = Map.copyOf(facts);
        }
    }

    record InterventionPlan(
            List<PolicyIntervention> baseline,
            List<PolicyIntervention> candidate) {
        InterventionPlan {
            baseline = List.copyOf(baseline);
            candidate = List.copyOf(candidate);
        }
    }
}
