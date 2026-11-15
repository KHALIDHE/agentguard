package dev.agentguard.gateway.policy;

/** Which rows a call may return. The gateway turns this into scope arguments for the upstream SQL. */
public enum RowScope {
    /** No row restriction (for example the employee directory). */
    NONE,
    /** Only rows of the user's own department. */
    SAME_DEPARTMENT,
    /** Only the user's own suppliers (manager). */
    OWN_SUPPLIERS
}
