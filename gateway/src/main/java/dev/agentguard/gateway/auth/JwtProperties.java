package dev.agentguard.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** agentguard.jwt.* — the secret comes from JWT_SECRET and must be at least 256 bits. */
@ConfigurationProperties(prefix = "agentguard.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "agentguard.jwt.secret (JWT_SECRET) must be set and at least 32 bytes long");
        }
        if (ttl == null) {
            ttl = Duration.ofHours(1);
        }
        if (issuer == null) {
            issuer = "agentguard";
        }
    }
}
