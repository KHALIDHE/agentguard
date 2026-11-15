package dev.agentguard.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RateLimiter limiter = new RateLimiter(
        new RateLimiter.Settings(3, Map.of("run_query", 1), 3), clock);

    @Test
    void limitTriggersAfterTheBudget() {
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isFalse();
    }

    @Test
    void budgetRefillsOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("accountant", "search_suppliers");
        }
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isFalse();

        clock.advance(Duration.ofSeconds(20)); // 3 per minute = one every 20 seconds
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isFalse();

        clock.advance(Duration.ofMinutes(5)); // never more than the full budget
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("accountant", "search_suppliers")).isFalse();
    }

    @Test
    void bucketsArePerUserAndPerTool() {
        assertThat(limiter.tryAcquire("admin", "run_query")).isTrue();
        assertThat(limiter.tryAcquire("admin", "run_query")).isFalse();

        assertThat(limiter.tryAcquire("admin", "search_suppliers")).isTrue();
        assertThat(limiter.tryAcquire("other-admin", "run_query")).isTrue();
    }
}
