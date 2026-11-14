package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.auth.UserIdentity;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every tool call from an agent arrives here. Phase 2 checks:
 *   1. a verified user identity is attached to the call;
 *   2. the arguments are only the ones the published schema declares
 *      (so an agent cannot inject scope parameters);
 * then forwards the call upstream. The policy engine, masking, approvals
 * and audit plug in here in the next phases.
 */
@Component
public class GatewayToolHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolHandler.class);

    private final UpstreamClient upstream;

    public GatewayToolHandler(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    public McpSchema.CallToolResult handle(McpSchema.Tool tool, McpSyncServerExchange exchange,
                                           McpSchema.CallToolRequest request) {
        long started = System.nanoTime();
        Object raw = exchange.transportContext().get(McpServerConfig.IDENTITY);
        if (!(raw instanceof UserIdentity identity)) {
            return refuse(tool.name(), null, "No verified user identity on this call", started);
        }

        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        Set<String> allowed = publishedParameters(tool);
        for (String name : args.keySet()) {
            if (!allowed.contains(name)) {
                return refuse(tool.name(), identity, "Argument '" + name + "' is not accepted by this tool", started);
            }
        }

        McpSchema.CallToolResult result = upstream.call(tool.name(), args);
        log.info("ALLOW user={} role={} tool={} ms={}", identity.username(), identity.role(), tool.name(),
            (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    public static Set<String> publishedParameters(McpSchema.Tool tool) {
        if (tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> props) {
            return props.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    private static McpSchema.CallToolResult refuse(String tool, UserIdentity identity, String reason, long started) {
        log.info("DENY user={} tool={} reason=\"{}\" ms={}",
            identity == null ? "-" : identity.username(), tool, reason, (System.nanoTime() - started) / 1_000_000);
        return McpSchema.CallToolResult.builder()
            .isError(true)
            .addTextContent("Refused by AgentGuard: " + reason)
            .build();
    }
}
