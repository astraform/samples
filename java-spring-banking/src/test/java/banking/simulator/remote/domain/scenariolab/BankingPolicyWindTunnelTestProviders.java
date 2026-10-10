package banking.simulator.remote.domain.scenariolab;

import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.springframework.web.client.RestTemplate;

/** Test-only access to the provider's deterministic admission boundary. */
public final class BankingPolicyWindTunnelTestProviders {
    private BankingPolicyWindTunnelTestProviders() {
    }

    public static BankingPolicyWindTunnelExecutionProvider withAdmissionBoundary(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            Executor executor,
            LongSupplier currentTimeMillis,
            Runnable afterLaunchAdmission) {
        return new BankingPolicyWindTunnelExecutionProvider(
                objectMapper,
                restTemplate,
                executor,
                currentTimeMillis,
                afterLaunchAdmission);
    }
}
