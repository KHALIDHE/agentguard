package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.approval.ApprovalRequest;
import dev.agentguard.gateway.approval.ApprovalService;
import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.policy.Decision;
import dev.agentguard.gateway.policy.DecisionType;
import dev.agentguard.gateway.policy.KnownTools;
import dev.agentguard.gateway.policy.PolicyEngine;
import dev.agentguard.gateway.policy.ToolCallContext;
import dev.agentguard.gateway.tools.ScopeResolver;
import dev.agentguard.gateway.tools.SqlGuard;
import dev.agentguard.gateway.tools.ToolCatalog;
import dev.agentguard.gateway.tools.ToolSchema;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every tool call from an agent goes through these steps, in this order:
 *
 *   1. identity     a verified user must be attached to the call
 *   2. validation   arguments must match the gateway's strict schema
 *   3. SQL guard    run_query must be one safe SELECT; a row limit is forced
 *   4. policy       first matching rule decides, deny by default
 *   5. row scope    department / doctor limits are added from the identity
 *      approval     REQUIRE_APPROVAL stores the scoped call and stops here
 *   6. upstream     the call is forwarded with the service token
 *   7. masking      sensitive fields are removed before the agent sees them
 *
 * Any step can stop the call. A fixed sequence is easier to read and to audit
 * than a configurable chain, and the order matters: no step that touches data
 * runs before the identity, validation and policy steps have passed.
 */
@Component
public class GatewayToolHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolHandler.class);
    private static final int DEFAULT_SQL_ROWS = 50;

    private final UpstreamClient upstream;
    private final PolicyEngine policies;
    private final ApprovalService approvals;

    public GatewayToolHandler(UpstreamClient upstream, PolicyEngine policies, ApprovalService approvals) {
        this.upstream = upstream;
        this.policies = policies;
        this.approvals = approvals;
    }

    public McpSchema.CallToolResult handle(McpSchema.Tool tool, McpSyncServerExchange exchange,
                                           McpSchema.CallToolRequest request) {
        long started = System.nanoTime();

        // 1. identity
        Object raw = exchange.transportContext().get(McpServerConfig.IDENTITY);
        if (!(raw instanceof UserIdentity identity)) {
            return refuse(tool.name(), null, "No verified user identity on this call", started);
        }

        // 2. validation
        Optional<ToolSchema> schema = ToolCatalog.find(tool.name());
        if (schema.isEmpty()) {
            return refuse(tool.name(), identity, "Unknown tool", started);
        }
        Map<String, Object> args = new HashMap<>(request.arguments() == null ? Map.of() : request.arguments());
        Optional<String> invalid = schema.get().validate(args);
        if (invalid.isPresent()) {
            return refuse(tool.name(), identity, invalid.get(), started);
        }

        // 3. SQL guard
        if (tool.name().equals(KnownTools.RUN_QUERY)) {
            int maxRows = args.get("maxRows") instanceof Number n ? n.intValue() : DEFAULT_SQL_ROWS;
            SqlGuard.Result sql = SqlGuard.check((String) args.get("sql"), maxRows);
            if (!sql.accepted()) {
                return refuse(tool.name(), identity, "SQL refused: " + sql.reason(), started);
            }
            args.put("sql", sql.rewrittenSql());
        }

        // 4. policy
        Decision decision = policies.evaluate(ToolCallContext.of(identity, tool.name(), args));
        if (decision.type() == DecisionType.DENY) {
            return refuse(tool.name(), identity, "Denied by policy " + decision.ruleId() + ": " + decision.reason(), started);
        }

        // 5. row scope
        Map<String, Object> upstreamArgs;
        try {
            upstreamArgs = ScopeResolver.apply(decision.scope(), identity, schema.get(), args);
        } catch (ScopeResolver.ScopeException e) {
            return refuse(tool.name(), identity, e.getMessage(), started);
        }

        // 5b. hold for a human: the scoped call is stored and executed later by the gateway itself
        if (decision.type() == DecisionType.REQUIRE_APPROVAL) {
            ApprovalRequest pending = approvals.create(identity, tool.name(), upstreamArgs, decision);
            log.info("PENDING user={} tool={} rule={} approval={}", identity.username(), tool.name(),
                decision.ruleId(), pending.getId());
            return McpSchema.CallToolResult.builder()
                .addTextContent("PENDING_APPROVAL: request " + pending.getId() + " is waiting for a human decision. "
                    + "Reason (policy " + decision.ruleId() + "): " + decision.reason() + " "
                    + "It expires at " + pending.getExpiresAt() + "; without a decision it is refused. "
                    + "Follow it with " + ApprovalStatusTool.NAME + " {\"approvalId\": " + pending.getId() + "}.")
                .build();
        }

        // 6. upstream
        McpSchema.CallToolResult result = upstream.call(tool.name(), upstreamArgs);

        // 7. masking
        if (decision.maskProfile() != null && !Boolean.TRUE.equals(result.isError())) {
            result = ResultMasking.mask(result, decision.maskProfile());
        }

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
            .content(List.of(new McpSchema.TextContent("Refused by AgentGuard: " + reason)))
            .build();
    }
}
