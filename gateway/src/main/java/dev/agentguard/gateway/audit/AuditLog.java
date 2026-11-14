package dev.agentguard.gateway.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes and verifies the hash-chained audit log.
 *
 * Each write runs in its own transaction (REQUIRES_NEW): the record is kept
 * even if the caller's work fails. AUDIT_HEAD is locked with SELECT ... FOR
 * UPDATE during the write, so concurrent calls line up one after another and
 * the chain never forks.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int PAGE = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public AuditLog(JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock,
                    ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.events = events;
    }

    public AuditRecord record(AuditEntry entry) {
        try {
            AuditRecord written = newTx.execute(status -> append(entry));
            events.publishEvent(written);
            return written;
        } catch (RuntimeException e) {
            // Losing an audit record is serious: log loudly, but do not hide the original outcome from the caller.
            log.error("AUDIT WRITE FAILED for {} {} {}: {}", entry.username(), entry.tool(), entry.decision(), e.getMessage());
            throw e;
        }
    }

    private AuditRecord append(AuditEntry e) {
        Map<String, Object> head = jdbc.queryForMap("SELECT last_seq, last_hash FROM audit_head WHERE id = 1 FOR UPDATE");
        long seq = ((Number) head.get("LAST_SEQ")).longValue() + 1;
        String prev = ((String) head.get("LAST_HASH")).trim();
        // Truncated to milliseconds so the value read back from Oracle hashes the same.
        Instant time = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String hash = AuditHashing.hash(prev, seq, time, e);

        jdbc.update("""
            INSERT INTO audit_event (seq, event_time, event_type, request_id, username, role, tool, arguments,
                                     decision, rule_id, reason, latency_ms, flags, approval_id, prev_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            seq, OffsetDateTime.ofInstant(time, ZoneOffset.UTC), e.eventType(), e.requestId(), e.username(), e.role(),
            e.tool(), e.arguments(), e.decision(), e.ruleId(), e.reason(), e.latencyMs(), e.flags(), e.approvalId(),
            prev, hash);
        jdbc.update("UPDATE audit_head SET last_seq = ?, last_hash = ? WHERE id = 1", seq, hash);
        return new AuditRecord(seq, time, e, prev, hash);
    }

    /** Walks the whole chain in order and reports the first row that does not match. */
    public Verification verify() {
        String expectedPrev = AuditHashing.GENESIS;
        long expectedSeq = 1;
        long checked = 0;
        while (true) {
            List<AuditRecord> page = jdbc.query(
                "SELECT * FROM audit_event WHERE seq >= ? ORDER BY seq FETCH FIRST " + PAGE + " ROWS ONLY",
                AuditLog::map, expectedSeq);
            for (AuditRecord r : page) {
                if (r.seq() != expectedSeq) {
                    return Verification.broken(checked, expectedSeq, "row " + expectedSeq + " is missing");
                }
                if (!r.prevHash().equals(expectedPrev)) {
                    return Verification.broken(checked, r.seq(), "row " + r.seq() + " does not follow the previous row");
                }
                String recomputed = AuditHashing.hash(r.prevHash(), r.seq(), r.eventTime(), r.entry());
                if (!recomputed.equals(r.hash())) {
                    return Verification.broken(checked, r.seq(), "row " + r.seq() + " was modified after it was written");
                }
                expectedPrev = r.hash();
                expectedSeq++;
                checked++;
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        Map<String, Object> head = jdbc.queryForMap("SELECT last_seq, last_hash FROM audit_head WHERE id = 1");
        long headSeq = ((Number) head.get("LAST_SEQ")).longValue();
        if (headSeq != checked || !((String) head.get("LAST_HASH")).trim().equals(expectedPrev)) {
            return Verification.broken(checked, checked + 1, "the latest " + (headSeq - checked) + " row(s) were removed");
        }
        return new Verification(true, checked, null, "chain intact");
    }

    public List<AuditRecord> search(String username, String tool, String decision, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_event WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (username != null) { sql.append(" AND username = ?"); params.add(username); }
        if (tool != null) { sql.append(" AND tool = ?"); params.add(tool); }
        if (decision != null) { sql.append(" AND decision = ?"); params.add(decision); }
        if (from != null) { sql.append(" AND event_time >= ?"); params.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC)); }
        if (to != null) { sql.append(" AND event_time < ?"); params.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC)); }
        sql.append(" ORDER BY seq DESC FETCH FIRST ? ROWS ONLY");
        params.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), AuditLog::map, params.toArray());
    }

    private static AuditRecord map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime time = rs.getObject("event_time", OffsetDateTime.class);
        AuditEntry entry = new AuditEntry(
            rs.getString("event_type"), rs.getString("request_id"), rs.getString("username"), rs.getString("role"),
            rs.getString("tool"), rs.getString("arguments"), rs.getString("decision"), rs.getString("rule_id"),
            rs.getString("reason"), nullableLong(rs, "latency_ms"), rs.getString("flags"),
            nullableLong(rs, "approval_id"));
        return new AuditRecord(rs.getLong("seq"), time.toInstant(), entry,
            rs.getString("prev_hash").trim(), rs.getString("hash").trim());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public record Verification(boolean intact, long rowsChecked, Long firstBadSeq, String message) {
        static Verification broken(long checked, long seq, String message) {
            return new Verification(false, checked, seq, message);
        }
    }
}
