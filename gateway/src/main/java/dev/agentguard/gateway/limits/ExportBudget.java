package dev.agentguard.gateway.limits;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * At most N export requests per user per day (UTC), counted in the database
 * from the approval requests, so the budget survives restarts and holds
 * across gateway instances. Refused and expired requests count too: asking is
 * what is limited.
 */
@Component
public class ExportBudget {

    private final JdbcTemplate jdbc;
    private final RateLimiter limits;
    private final Clock clock;

    public ExportBudget(JdbcTemplate jdbc, RateLimiter limits, Clock clock) {
        this.jdbc = jdbc;
        this.limits = limits;
        this.clock = clock;
    }

    public boolean hasRoomFor(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Integer used = jdbc.queryForObject("""
            SELECT COUNT(*) FROM approval_request
            WHERE requester_id = ? AND tool = 'export_report' AND created_at >= ? AND created_at < ?""",
            Integer.class, userId,
            OffsetDateTime.of(today.atStartOfDay(), ZoneOffset.UTC),
            OffsetDateTime.of(today.plusDays(1).atStartOfDay(), ZoneOffset.UTC));
        return used == null || used < limits.exportsPerDay();
    }
}
