package dev.agentguard.gateway.tools;

import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.policy.RowScope;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns the decision's row scope into upstream arguments, from the verified
 * identity only. The upstream SQL puts them in its WHERE clause, so rows
 * outside the scope are never read, let alone returned.
 *
 * Fails closed: if a scope cannot be applied (no department on the user, or
 * a tool that does not accept the scope), the call is refused.
 */
public final class ScopeResolver {

    private ScopeResolver() {
    }

    public static Map<String, Object> apply(RowScope scope, UserIdentity user, ToolSchema tool,
                                            Map<String, Object> arguments) {
        Map<String, Object> out = new HashMap<>(arguments);
        switch (scope) {
            case NONE -> { }
            case SAME_DEPARTMENT -> {
                require(user.department() != null, "the user has no department");
                require(tool.scopeParams().contains(ToolCatalog.SCOPE_DEPARTMENT),
                    tool.name() + " cannot be limited to a department");
                out.put(ToolCatalog.SCOPE_DEPARTMENT, user.department());
            }
            case OWN_PATIENTS -> {
                require(user.staffId() != null, "the user is not linked to a staff record");
                require(tool.scopeParams().contains(ToolCatalog.SCOPE_DOCTOR),
                    tool.name() + " cannot be limited to a doctor's patients");
                out.put(ToolCatalog.SCOPE_DOCTOR, user.staffId());
            }
        }
        return out;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw new ScopeException("Row scope cannot be applied: " + reason);
        }
    }

    public static class ScopeException extends RuntimeException {
        public ScopeException(String message) {
            super(message);
        }
    }
}
