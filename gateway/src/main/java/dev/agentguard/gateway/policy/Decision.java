package dev.agentguard.gateway.policy;

/**
 * The outcome of a policy evaluation, with the rule that produced it and a
 * reason a human can read. Every audit record stores these three.
 */
public record Decision(DecisionType type, String ruleId, String reason, RowScope scope, String maskProfile) {

    public static final String DEFAULT_DENY_RULE = "default-deny";

    public static Decision defaultDeny(String tool) {
        return new Decision(DecisionType.DENY, DEFAULT_DENY_RULE,
            "No policy allows this role to call " + tool, RowScope.NONE, null);
    }

    public boolean allowsExecution() {
        return type == DecisionType.ALLOW || type == DecisionType.MASK;
    }
}
