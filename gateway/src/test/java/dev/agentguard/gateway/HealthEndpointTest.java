package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${local.server.port}")
    int port;

    @Test
    void flywayBaselineRan() {
        String schema = jdbc.queryForObject(
            "SELECT meta_value FROM app_meta WHERE meta_key = 'schema'", String.class);
        assertThat(schema).isEqualTo("agentguard");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthIsPublicAndTheRestIsClosed() {
        RestClient client = RestClient.create("http://localhost:" + port);

        Map<String, Object> health = client.get().uri("/actuator/health").retrieve().body(Map.class);
        assertThat(health).containsEntry("status", "UP");

        int mcpStatus = client.post().uri("/mcp").exchange((req, res) -> res.getStatusCode().value());
        assertThat(mcpStatus).isEqualTo(401);
    }
}
