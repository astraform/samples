package banking.simulator.remote.domain.scenariolab;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BankingPolicyInterventionCompilerTest {

    @Test
    void compilesExistingFastBatchPolicyEventsWithoutChangingOrderOrTiming() {
        ScenarioLabRunParameters parameters = new ScenarioLabRunParameters()
                .presetId("saver-repricing")
                .productCode("TS001")
                .baselineRate("0.03")
                .candidateRate("0.08")
                .baselineLimit("1000")
                .candidateLimit("1500")
                .candidateStimulusAmount("250")
                .candidateCrash(ScenarioLabRunParameters.CandidateCrashEnum.TRUE)
                .candidateHardshipScan(ScenarioLabRunParameters.CandidateHardshipScanEnum.TRUE)
                .candidateHardshipThreshold("100")
                .candidateHardshipSubsidyAmount("75")
                .candidateFraudAlert(ScenarioLabRunParameters.CandidateFraudAlertEnum.TRUE)
                .candidateFraudSeverity("HIGH")
                .candidateFraudChannel("SMS")
                .candidateFraudMessage("Review recent activity")
                .prePolicyDays("10");

        BankingPolicyInterventionCompiler.InterventionPlan plan =
                new BankingPolicyInterventionCompiler().compile(parameters);

        assertThat(plan.baseline())
                .extracting(BankingPolicyInterventionCompiler.PolicyIntervention::interventionId)
                .containsExactly("policy_rate_baseline_start", "policy_limit_baseline_start");
        assertThat(plan.candidate())
                .extracting(BankingPolicyInterventionCompiler.PolicyIntervention::interventionId)
                .containsExactly(
                        "policy_rate_candidate_shared_start",
                        "policy_limit_candidate_shared_start",
                        "policy_rate_candidate_shift",
                        "policy_limit_candidate_shift",
                        "policy_candidate_stimulus",
                        "policy_candidate_crash",
                        "policy_candidate_hardship_scan",
                        "policy_candidate_fraud_alert");
        assertThat(plan.candidate().subList(0, 2))
                .extracting(BankingPolicyInterventionCompiler.PolicyIntervention::scheduledAfterSeconds)
                .containsOnly(0L);
        assertThat(plan.candidate().subList(2, plan.candidate().size()))
                .extracting(BankingPolicyInterventionCompiler.PolicyIntervention::scheduledAfterSeconds)
                .containsOnly(864_000L);
        assertThat(plan.candidate().get(2).facts())
                .isEqualTo(Map.of("productCode", "TS001", "rate", new BigDecimal("0.08")));
        assertThat(plan.candidate().get(4).facts())
                .isEqualTo(Map.of(
                        "amount", new BigDecimal("250"),
                        "fundingSource", "EXTERNAL",
                        "description", "Wind-tunnel preset 250 stimulus overlay"));
        assertThat(plan.candidate().get(6).facts())
                .isEqualTo(Map.of(
                        "hardshipThreshold", new BigDecimal("100"),
                        "subsidyAmount", new BigDecimal("75"),
                        "fundingSource", "BANK"));
        assertThat(plan.candidate().get(7).facts())
                .isEqualTo(Map.of(
                        "productCode", "TS001",
                        "severity", "HIGH",
                        "channel", "SMS",
                        "message", "Review recent activity"));
        assertThat(plan.candidate()).isUnmodifiable();
    }

    @Test
    void preservesLargeAcceptedOffsetsWithoutIntegerOverflow() {
        ScenarioLabRunParameters parameters = new ScenarioLabRunParameters()
                .productCode("TS001")
                .baselineRate("0.03")
                .candidateRate("0.08")
                .candidateStimulusAmount("0")
                .prePolicyDays("24856");

        BankingPolicyInterventionCompiler.InterventionPlan plan =
                new BankingPolicyInterventionCompiler().compile(parameters);

        assertThat(plan.candidate())
                .filteredOn(intervention -> intervention.interventionId().equals("policy_rate_candidate_shift"))
                .extracting(BankingPolicyInterventionCompiler.PolicyIntervention::scheduledAfterSeconds)
                .containsExactly(2_147_558_400L);
    }
}
