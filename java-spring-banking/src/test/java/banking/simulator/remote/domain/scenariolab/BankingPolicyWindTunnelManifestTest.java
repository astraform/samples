package banking.simulator.remote.domain.scenariolab;

import banking.simulator.domain.banking.BankingDomainIds;
import org.junit.jupiter.api.Test;
import ai.astraform.remote.domain.sdk.CelPolicyExpressionEvaluator;
import ai.astraform.remote.domain.sdk.PolicyExpressionContext;
import ai.astraform.remote.domain.sdk.PolicyExpressionDefinition;
import ai.astraform.remote.domain.sdk.PolicyExpressionStage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankingPolicyWindTunnelManifestTest {

    private final BankingPolicyWindTunnelManifest manifest = new BankingPolicyWindTunnelManifest();

    @Test
    void publishesCelPolicyExpressionsForBankingDecisionGates() {
        List<PolicyExpressionDefinition> expressions = manifest.policyExpressions();
        assertFalse(expressions.isEmpty());

        CelPolicyExpressionEvaluator evaluator = new CelPolicyExpressionEvaluator();
        PolicyExpressionContext context = PolicyExpressionContext.create(
                BankingDomainIds.BANKING,
                BankingPolicyWindTunnelManifest.PACK_ID,
                PolicyExpressionStage.BRANCH_GUARD,
                "candidate",
                Map.of(),
                Map.of(
                        "baselineRate", 0.03,
                        "candidateRate", 0.08,
                        "candidateHardshipScan", true,
                        "candidateHardshipThreshold", 250.0),
                Map.of(),
                Map.of(),
                Map.of());

        assertTrue(expressions.stream()
                .allMatch(expression -> evaluator.evaluate(expression, context).passed()));
    }
}
