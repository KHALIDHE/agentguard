package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;

/** No MCP call reaches a tool without a valid, unexpired token signed by the gateway. */
class IdentityTest extends GatewayIntegrationTest {

    @Autowired
    JwtEncoder gatewayEncoder;

    @Test
    void demoUsersCanLogIn() {
        assertThat(login("accountant.salma")).isNotBlank();
        assertThat(login("fm.amrani")).isNotBlank();
    }

    @Test
    void wrongPasswordIs401() {
        int status = RestClient.create("http://localhost:" + port).post().uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("username", "accountant.salma", "password", "wrong-password"))
            .exchange((req, res) -> res.getStatusCode().value());
        assertThat(status).isEqualTo(401);
    }

    @Test
    void mcpWithoutTokenIsRefused() {
        assertThatThrownBy(() -> agent(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mcpWithExpiredTokenIsRefused() {
        Instant past = Instant.now().minus(3, ChronoUnit.HOURS);
        String expired = sign(gatewayEncoder, claims(past, past.plus(30, ChronoUnit.MINUTES), "agentguard"));

        assertThatThrownBy(() -> agent(expired)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mcpWithForgedTokenIsRefused() {
        var otherKey = new SecretKeySpec(
            "an-attacker-key-that-is-not-the-gateway-key!!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String forged = sign(new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)),
            claims(Instant.now(), Instant.now().plusSeconds(600), "agentguard"));

        assertThatThrownBy(() -> agent(forged)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void mcpWithValidTokenConnects() {
        try (var client = agent(login("accountant.salma"))) {
            assertThat(client.ping()).isNotNull();
        }
    }

    private static JwtClaimsSet claims(Instant issued, Instant expires, String issuer) {
        return JwtClaimsSet.builder()
            .issuer(issuer).subject("admin.youssef").issuedAt(issued).expiresAt(expires)
            .claim("uid", 1).claim("role", "ADMIN").build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }
}
