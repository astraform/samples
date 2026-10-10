package ai.astraform.samples.marketingops.creativeagency.a2a;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/** Announces the Spring MVC JSON-RPC adapter to the A2A SDK's transport validator. */
public final class CreativeAgencySpringJsonRpcTransportMetadata implements TransportMetadata {

    @Override
    public String getTransportProtocol() {
        return TransportProtocol.JSONRPC.asString();
    }
}
