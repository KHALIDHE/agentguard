package dev.agentguard.gateway.policy;

import dev.agentguard.gateway.auth.Role;
import java.util.Set;

/**
 * One line of policy: who (roles), what (tools), when (condition), and the
 * decision. A rule matches a call only when all three match.
 */
public record PolicyRule(
        String id,
        int priority,
        Set<Role> roles,
        Set<String> tools,
        Condition when,
        DecisionType decision,
        RowScope scope,
        String maskProfile,
        String reason) {

    public PolicyRule {
        roles = Set.copyOf(roles);
        tools = Set.copyOf(tools);
        if (when == null) when = Condition.ALWAYS;
        if (scope == null) scope = RowScope.NONE;
    }

    public boolean matches(ToolCallContext call) {
        return roles.contains(call.identity().role())
            && tools.contains(call.tool())
            && when.test(call);
    }

    public Decision toDecision() {
        return new Decision(decision, id, reason, scope, maskProfile);
    }
}
