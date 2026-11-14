package dev.agentguard.gateway.policy;

public enum DecisionType {
    /** Forward the call as it is (scope still applies). */
    ALLOW,
    /** Refuse the call. */
    DENY,
    /** Forward the call, then remove or hide sensitive fields in the result. */
    MASK,
    /** Hold the call until an authorised human approves it. */
    REQUIRE_APPROVAL
}
