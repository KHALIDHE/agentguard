package dev.agentguard.gateway.approval;

/**
 * PENDING -> APPROVED -> EXECUTED (or FAILED)
 * PENDING -> REFUSED
 * PENDING -> EXPIRED      (no answer in time counts as refused)
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REFUSED,
    EXPIRED,
    EXECUTED,
    FAILED
}
