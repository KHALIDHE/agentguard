package dev.agentguard.gateway.audit;

/**
 * What is recorded about one decision. Built by the gateway; the sequence
 * number, time and hashes are added by AuditLog when the row is written.
 */
public record AuditEntry(
        String eventType,
        String requestId,
        String username,
        String role,
        String tool,
        String arguments,
        String decision,
        String ruleId,
        String reason,
        Long latencyMs,
        String flags,
        Long approvalId) {

    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String APPROVAL = "APPROVAL";
}
