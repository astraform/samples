package banking.simulator.domain.banking;

import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BankingSeededCohortValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void creditCardSeedAccountsMustDefinePositiveLimit() throws Exception {
        Path seededCohort = workspaceRoot().resolve("scenarios/demo/seeded-cohorts/banking-demo-agents.json");
        assertTrue(Files.exists(seededCohort), "banking seeded cohort should exist at " + seededCohort);
        JsonNode root = objectMapper.readTree(Files.readString(seededCohort));

        List<String> violations = new ArrayList<>();
        for (JsonNode persona : root) {
            String personaName = persona.path("personaName").asText("unknown");
            for (JsonNode account : persona.path("initialAccounts")) {
                String productCode = account.path("productCode").asText("");
                if (!productCode.startsWith("CC")) {
                    continue;
                }
                JsonNode limitNode = account.get("limit");
                double limit = limitNode != null && limitNode.isNumber() ? limitNode.asDouble() : 0.0;
                if (limit <= 0.0) {
                    violations.add(personaName + ":" + account.path("name").asText("unnamed-card") + ":" + productCode);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Every seeded credit-card account must define a positive limit. Violations: " + violations);
    }

    private Path workspaceRoot() {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("java-spring-banking".equals(userDir.getFileName().toString())) {
            return userDir.getParent();
        }
        return userDir;
    }
}
