package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.approval.ApprovalRequest;
import dev.agentguard.gateway.approval.ApprovalService;
import dev.agentguard.gateway.audit.AuditArguments;
import dev.agentguard.gateway.audit.AuditEntry;
import dev.agentguard.gateway.audit.AuditLog;
import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.content.UntrustedContent;
import dev.agentguard.gateway.limits.ExportBudget;
import dev.agentguard.gateway.limits.RateLimiter;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every tool call from an agent goes through these steps, in this order:
 *
 *   1. identity     a verified user must be attached to the call
 *      rate limit   calls per minute per user and tool
 *   2. validation   arguments must match the gateway's strict schema
 *   3. SQL guard    run_query must be one safe SELECT; a row limit is forced
 *   4. policy       first matching rule decides, deny by default
 *   5. row scope    department / doctor limits are added from the identity
 *      approval     REQUIRE_APPROVAL stores the scoped call and stops here
 *   6. upstream     the call is forwarded with the service token
 *   7. masking      sensitive fields are removed before the agent sees them
 *   8. untrusted    the result is labelled as data and scanned for injected instructions
 *
 * Whatever the exit point, exactly one audit record is written.
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
    private final AuditLog audit;
    private final RateLimiter rateLimiter;
    private final ExportBudget exportBudget;
    private final UntrustedContent untrusted;

    public GatewayToolHandler(UpstreamClient upstream, PolicyEngine policies, ApprovalService approvals,
                              AuditLog audit, RateLimiter rateLimiter, ExportBudget exportBudget,
                              UntrustedContent untrusted) {
        this.upstream = upstream;
        this.policies = policies;
        this.approvals = approvals;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.exportBudget = exportBudget;
        this.untrusted = untrusted;
    }

    /** What is known about the call so far; filled in step by step for the audit record. */
    private static final class Call {
        final String requestId = UUID.randomUUID().toString();
        final long started = System.nanoTime();
        final String tool;
        final Map<String, Object> rawArgs;
        UserIdentity identity;
        String flags;

        Call(String tool, Map<String, Object> rawArgs) {
            this.tool = tool;
            this.rawArgs = rawArgs;
        }

        long elapsedMs() {
            return (System.nanoTime() - started) / 1_000_000;
        }
    }

    public McpSchema.CallToolResult handle(McpSchema.Tool tool, McpSyncServerExchange exchange,
                                           McpSchema.CallToolRequest request) {
        Call call = new Call(tool.name(), request.arguments() == null ? Map.of() : request.arguments());

        // 1. identity
        Object raw = exchange.transportContext().get(McpServerConfig.IDENTITY);
        if (!(raw instanceof UserIdentity identity)) {
            return refuse(call, "identity", "No verified user identity on this call");
        }
        call.identity = identity;

        // 1b. rate limit
        if (!rateLimiter.tryAcquire(identity.username(), tool.name())) {
            return refuse(call, "rate-limit", "Too many calls to " + tool.name() + "; try again in a minute");
        }

        // 2. validation
        Optional<ToolSchema> schema = ToolCatalog.find(tool.name());
        if (schema.isEmpty()) {
            return refuse(call, "validation", "Unknown tool");
        }
        Map<String, Object> args = new HashMap<>(call.rawArgs);
        Optional<String> invalid = schema.get().validate(args);
        if (invalid.isPresent()) {
            return refuse(call, "validation", invalid.get());
        }

        // 3. SQL guard
        if (tool.name().equals(KnownTools.RUN_QUERY)) {
            int maxRows = args.get("maxRows") instanceof Number n ? n.intValue() : DEFAULT_SQL_ROWS;
            SqlGuard.Result sql = SqlGuard.check((String) args.get("sql"), maxRows);
            if (!sql.accepted()) {
                return refuse(call, "sql-guard", "SQL refused: " + sql.reason());
            }
            args.put("sql", sql.rewrittenSql());
        }

        // 4. policy
        Decision decision = policies.evaluate(ToolCallContext.of(identity, tool.name(), args));
        if (decision.type() == DecisionType.DENY) {
            return refuse(call, decision.ruleId(), "Denied by policy " + decision.ruleId() + ": " + decision.reason());
        }

        // 5. row scope
        Map<String, Object> upstreamArgs;
        try {
            upstreamArgs = ScopeResolver.apply(decision.scope(), identity, schema.get(), args);
        } catch (ScopeResolver.ScopeException e) {
            return refuse(call, decision.ruleId(), e.getMessage());
        }

        // 5b. hold for a human: the scoped call is stored and executed later by the gateway itself
        if (decision.type() == DecisionType.REQUIRE_APPROVAL) {
            if (tool.name().equals(KnownTools.EXPORT_REPORT) && !exportBudget.hasRoomFor(identity.userId())) {
                return refuse(call, "export-budget", "Daily export budget used up (" + rateLimiter.exportsPerDay()
                    + " export requests per day)");
            }
            ApprovalRequest pending = approvals.create(identity, tool.name(), upstreamArgs, decision);
            record(call, "PENDING", decision.ruleId(), decision.reason(), pending.getId());
            return McpSchema.CallToolResult.builder()
                .addTextContent("PENDING_APPROVAL: request " + pending.getId() + " is waiting for a human decision. "
                    + "Reason (policy " + decision.ruleId() + "): " + decision.reason() + " "
                    + "It expires at " + pending.getExpiresAt() + "; without a decision it is refused. "
                    + "Follow it with " + ApprovalStatusTool.NAME + " {\"approvalId\": " + pending.getId() + "}.")
                .build();
        }

        // 6. upstream
        McpSchema.CallToolResult result;
        try {
            result = upstream.call(tool.name(), upstreamArgs);
        } catch (RuntimeException e) {
            record(call, "ERROR", decision.ruleId(), "Upstream call failed: " + e.getMessage(), null);
            return McpSchema.CallToolResult.builder().isError(true)
                .addTextContent("The tool is not available right now").build();
        }

        // 7. masking
        if (decision.maskProfile() != null && !Boolean.TRUE.equals(result.isError())) {
            result = ResultMasking.mask(result, decision.maskProfile());
        }

        // 8. untrusted content
        UntrustedContent.Labelled labelled = untrusted.label(tool.name(), result);
        if (!labelled.flags().isEmpty()) {
            call.flags = String.join(",", labelled.flags());
        }

        record(call, decision.type().name(), decision.ruleId(), decision.reason(), null);
        return labelled.result();
    }

    private McpSchema.CallToolResult refuse(Call call, String ruleId, String reason) {
        record(call, "DENY", ruleId, reason, null);
        return McpSchema.CallToolResult.builder()
            .isError(true)
            .content(List.of(new McpSchema.TextContent("Refused by AgentGuard: " + reason)))
            .build();
    }

    private void record(Call call, String decision, String ruleId, String reason, Long approvalId) {
        UserIdentity who = call.identity;
        long ms = call.elapsedMs();
        log.info("{} user={} tool={} rule={} ms={}", decision, who == null ? "-" : who.username(), call.tool, ruleId, ms);
        audit.record(new AuditEntry(AuditEntry.TOOL_CALL, call.requestId,
            who == null ? "-" : who.username(), who == null ? null : who.role().name(), call.tool,
            AuditArguments.summarize(call.rawArgs), decision, ruleId, truncate(reason), ms, call.flags, approvalId));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    public static Set<String> publishedParameters(McpSchema.Tool tool) {
        if (tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> props) {
            return props.keySet().stream().map(String::valueOf).collect(Collectors.toSet());
        }
        return Set.of();
    }
}
