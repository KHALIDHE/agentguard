package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.policy.Decision;
import dev.agentguard.gateway.policy.DecisionType;
import dev.agentguard.gateway.policy.PolicyEngine;
import dev.agentguard.gateway.policy.ToolCallContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every tool call from an agent arrives here:
 *   1. a verified user identity must be attached to the call;
 *   2. only the arguments the published schema declares are accepted;
 *   3. the policy engine decides (deny by default);
 * and only then is the call forwarded upstream.
 */
@Component
public class GatewayToolHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolHandler.class);

    private final UpstreamClient upstream;
    private final PolicyEngine policies;

    public GatewayToolHandler(UpstreamClient upstream, PolicyEngine policies) {
        this.upstream = upstream;
        this.policies = policies;
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

        ToolCallContext call = ToolCallContext.of(identity, tool.name(), args);
        Decision decision = policies.evaluate(call);
        if (decision.type() == DecisionType.DENY) {
            return refuse(tool.name(), identity, "Denied by policy " + decision.ruleId() + ": " + decision.reason(), started);
        }
        if (decision.type() == DecisionType.REQUIRE_APPROVAL) {
            return refuse(tool.name(), identity,
                "Needs a human approval (policy " + decision.ruleId() + "): " + decision.reason(), started);
        }

        McpSchema.CallToolResult result = upstream.call(tool.name(), args);
        log.info("{} user={} role={} tool={} rule={} ms={}", decision.type(), identity.username(), identity.role(),
            tool.name(), decision.ruleId(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    public static Set<String> publishedParameters(McpSchema.Tool tool) {
        if (tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> props) {
            return props.keySet().stream().map(String::valueOf).collect(Collectors.toSet());
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
