package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.a2aproject.sdk.common.A2AHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.PROTOCOL_VERSION;
import static ai.astraform.samples.marketingops.creativeagency.a2a.CreativeAgencyA2ATestFixtures.sendMessagePayload;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "marketing-ops-creative-agency.generation.mode=deterministic",
        "marketing-ops-creative-agency.a2a.inbound-token="
                + CreativeAgencyProtectedDeterministicA2AControllerTest.TOKEN
})
@AutoConfigureMockMvc
class CreativeAgencyProtectedDeterministicA2AControllerTest {

    static final String TOKEN = "deterministic-partner-token-with-32-characters";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void advertisesAndEnforcesConfiguredPartnerTokenWithoutCallingAnLlm() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securitySchemes.astraformAgentToken.apiKeySecurityScheme.location")
                        .value("header"))
                .andExpect(jsonPath("$.securitySchemes.astraformAgentToken.apiKeySecurityScheme.name")
                        .value(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER));

        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .contentType("application/json")
                        .content("{not-valid-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        mockMvc.perform(post("/a2a")
                        .header(A2AHeaders.A2A_VERSION, PROTOCOL_VERSION)
                        .header(CreativeAgencyA2AAuthenticationFilter.TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(sendMessagePayload(
                                "protected-deterministic-request",
                                "protected-deterministic-context",
                                "Rework this hero around one promise and one CTA.",
                                Map.of(
                                        "scenario", "creative-rework",
                                        "personaName", "Agency Liaison Rowan",
                                        "agentKey", "creative-agency"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.task.status.state")
                        .value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.result.task.status.message.metadata.generationProvider")
                        .value("deterministic"));
    }
}
