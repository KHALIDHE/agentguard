package dev.agentguard.gateway.limits;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token bucket per (user, tool): a bucket holds up to N tokens, refills at
 * N per minute, and each call takes one. Bursts up to N are fine; a sustained
 * flood is cut to N per minute.
 *
 * In memory, per gateway instance. With several instances the buckets would
 * move to a shared store (Redis), see DECISIONS.md.
 */
@Component
@EnableConfigurationProperties(RateLimiter.Settings.class)
public class RateLimiter {

    @ConfigurationProperties(prefix = "agentguard.limits")
    public record Settings(Integer callsPerMinute, Map<String, Integer> perTool, Integer exportsPerDay) {
        public Settings {
            if (callsPerMinute == null) callsPerMinute = 30;
            if (perTool == null) perTool = Map.of();
            if (exportsPerDay == null) exportsPerDay = 3;
        }
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final Settings settings;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** Takes one token. Returns false when the user has used up this tool's budget for now. */
    public boolean tryAcquire(String username, String tool) {
        int perMinute = settings.perTool().getOrDefault(tool, settings.callsPerMinute());
        long now = clock.millis() * 1_000_000L;
        Bucket bucket = buckets.computeIfAbsent(username + "|" + tool, k -> new Bucket(perMinute, now));
        synchronized (bucket) {
            double minutes = (now - bucket.lastRefillNanos) / 60_000_000_000.0;
            bucket.tokens = Math.min(perMinute, bucket.tokens + minutes * perMinute);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    public int exportsPerDay() {
        return settings.exportsPerDay();
    }
}
