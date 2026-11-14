package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class HealthEndpointTest extends GatewayIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheGatewayMigrations() {
        String schema = jdbc.queryForObject(
            "SELECT meta_value FROM app_meta WHERE meta_key = 'schema'", String.class);
        assertThat(schema).isEqualTo("agentguard");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(7);
    }

    @Test
    void gatewayUserCannotReadHospitalTables() {
        // Two schemas, two users: the gateway's database account has no grant on HOSPITAL.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.queryForObject("SELECT COUNT(*) FROM hospital.staff", Integer.class))
            .rootCause().hasMessageContaining("ORA-00942");
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
