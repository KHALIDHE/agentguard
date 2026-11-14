package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.approval.ApprovalRequest;
import dev.agentguard.gateway.approval.ApprovalService;
import dev.agentguard.gateway.auth.UserIdentity;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool served by the gateway itself, not forwarded upstream: lets an agent
 * follow its own approval requests. A user only ever sees their own requests;
 * someone else's id answers "not found".
 */
@Component
public class ApprovalStatusTool {

    public static final String NAME = "get_approval_status";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApprovalService approvals;

    public ApprovalStatusTool(ApprovalService approvals) {
        this.approvals = approvals;
    }

    public McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
            .name(NAME)
            .description("Status of an action waiting for human approval. Returns the result once it was approved and executed.")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of("approvalId", Map.of("type", "integer", "minimum", 1,
                    "description", "The id returned when the action was put on hold")),
                "required", List.of("approvalId"),
                "additionalProperties", false))
            .build();
    }

    public McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Object raw = exchange.transportContext().get(McpServerConfig.IDENTITY);
        if (!(raw instanceof UserIdentity identity)) {
            return error("No verified user identity on this call");
        }
        Object id = request.arguments() == null ? null : request.arguments().get("approvalId");
        if (!(id instanceof Number n) || n.longValue() < 1) {
            return error("Argument 'approvalId' must be a positive whole number");
        }
        return approvals.findForRequester(n.longValue(), identity.userId())
            .map(ApprovalStatusTool::view)
            .orElseGet(() -> error("No approval request " + n.longValue() + " for this user"));
    }

    private static McpSchema.CallToolResult view(ApprovalRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", r.getId());
        out.put("tool", r.getTool());
        out.put("status", r.getStatus().name());
        out.put("decidedBy", r.getDecidedBy());
        out.put("note", r.getDecisionNote());
        out.put("expiresAt", r.getExpiresAt().toString());
        switch (r.getStatus()) {
            case EXECUTED -> out.put("result", r.getResultJson());
            case FAILED -> out.put("error", r.getResultJson());
            default -> { }
        }
        return McpSchema.CallToolResult.builder().addTextContent(JSON.writeValueAsString(out)).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent("Refused by AgentGuard: " + message).build();
    }
}
