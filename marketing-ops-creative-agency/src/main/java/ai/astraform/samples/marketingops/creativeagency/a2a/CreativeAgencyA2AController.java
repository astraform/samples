package ai.astraform.samples.marketingops.creativeagency.a2a;

import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class CreativeAgencyA2AController {

    static final int MAX_A2A_REQUEST_BYTES = 65_536;

    private final AgentCard agentCard;
    private final CreativeAgencyCallContextFactory callContextFactory;
    private final CreativeAgencyJsonRpcV1Adapter jsonRpcAdapter;

    public CreativeAgencyA2AController(
            AgentCard agentCard,
            CreativeAgencyCallContextFactory callContextFactory,
            CreativeAgencyJsonRpcV1Adapter jsonRpcAdapter) {
        this.agentCard = agentCard;
        this.callContextFactory = callContextFactory;
        this.jsonRpcAdapter = jsonRpcAdapter;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAgentCard() throws JsonProcessingException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtil.toJson(agentCard));
    }

    @GetMapping("/a2a/health")
    public Map<String, Object> getA2AHealth() {
        return Map.of(
                "status", "UP",
                "service", "marketing-ops-creative-agency",
                "protocol", "A2A 1.0 JSON-RPC");
    }

    @PostMapping(value = "/a2a", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> invoke(
            HttpServletRequest request) {
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_A2A_REQUEST_BYTES + 1);
        } catch (IOException failure) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonRpcAdapter.parseError("Unable to read JSON-RPC request"));
        }
        if (body.length > MAX_A2A_REQUEST_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"A2A_REQUEST_TOO_LARGE\"}");
        }

        String rawBody;
        try {
            rawBody = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException failure) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonRpcAdapter.parseError("JSON-RPC request must be valid UTF-8"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonRpcAdapter.invoke(rawBody, callContextFactory.create(request)));
    }
}
