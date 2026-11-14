package dev.agentguard.gateway.audit;

import java.time.Instant;

/** A stored audit row, as read back for search and verification. */
public record AuditRecord(long seq, Instant eventTime, AuditEntry entry, String prevHash, String hash) {}
