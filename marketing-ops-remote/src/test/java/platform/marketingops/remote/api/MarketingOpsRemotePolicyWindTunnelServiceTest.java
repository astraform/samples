package platform.marketingops.remote.api;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketingOpsRemotePolicyWindTunnelServiceTest {
    private final MarketingOpsRemotePolicyWindTunnelService service =
            new MarketingOpsRemotePolicyWindTunnelService(
                    new ObjectMapper().rebuild().findAndAddModules().build(),
                    "target/marketing-ops-remote-policy-wind-tunnel-service-test",
                    "http://agent-runtime:8082",
                    "http://sim:8083");

    @Test
    void exposesMarketingOpsPolicyWindTunnelMetadataFromRemoteDomainService() {
        Map<String, Object> body = service.pack();

        assertThat(body)
                .containsEntry("packId", "marketing-ops-policy-pack")
                .containsEntry("domainId", "marketing-ops")
                .containsEntry("label", "Marketing Ops Policy Pack")
                .containsKeys(
                        "capabilities",
                        "controlSchema",
                        "uiSchema",
                        "domainLabels",
                        "reportLabels",
                        "outcomeSchema",
                        "outcomeScorecard",
                        "outcomeScorecards",
                        "policyExpressions",
                        "summarySchemaVersion");
        assertThat(scorecards(body))
                .extracting(scorecard -> scorecard.get("scorecardId"))
                .contains("marketing-ops.launch-readiness.v1", "marketing-ops.evidence-readiness.v1");
        assertThat(String.valueOf(((Map<?, ?>) body.get("domainLabels")).get("actorPlural")))
                .isEqualTo("campaigns");
        assertThat(String.valueOf(((Map<?, ?>) body.get("capabilities")).get("remoteScorecardMetadata")))
                .isEqualTo("true");
        assertThat(String.valueOf(((Map<?, ?>) body.get("capabilities")).get("remoteProviderReady")))
                .isEqualTo("true");
    }

    @Test
    void exposesMarketingOpsPolicyWindTunnelPresetCatalogFromRemoteDomainService() {
        Map<String, Object> body = service.presets();

        assertThat(body)
                .containsEntry("schemaVersion", "scenario_lab_presets.v1")
                .containsKey("presets")
                .containsEntry("summarySchemaVersion", "policy_wind_tunnel_summary.v1");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scorecards(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("outcomeScorecards");
    }
}
