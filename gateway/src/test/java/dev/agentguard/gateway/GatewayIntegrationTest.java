package dev.agentguard.gateway;

import dev.agentguard.hospitaltools.HospitalToolsApplication;
import dev.agentguard.testsupport.HospitalToolsTestSecurity;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end setup, the same shape as production:
 *
 *   test MCP client --(user JWT)--> gateway --(service token)--> hospital tools --> Oracle
 *
 * One Oracle container with two database users (agentguard, hospital), and
 * the real hospital-tools application started in this JVM on a random port.
 * Subclasses share all of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "agentguard.jwt.secret=test-only-secret-0123456789-abcdefghijklmnopqrstuvwxyz",
    "agentguard.demo.user-password=" + GatewayIntegrationTest.DEMO_PASSWORD,
    "agentguard.upstream.service-token=" + GatewayIntegrationTest.SERVICE_TOKEN,
    "agentguard.limits.calls-per-minute=1000",
    "agentguard.limits.per-tool.get_staff_directory=1000",
    "agentguard.limits.per-tool.list_appointments=5",
})
public abstract class GatewayIntegrationTest {

    static final String DEMO_PASSWORD = "demo-password-123";
    static final String SERVICE_TOKEN = "test-service-token";

    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
        .withUsername("agentguard")
        .withPassword("agentguard")
        .withStartupTimeout(Duration.ofMinutes(5))
        .withReuse(true);

    private static ConfigurableApplicationContext hospitalTools;

    static {
        ORACLE.start();
    }

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
        registry.add("agentguard.upstream.hospital-tools-url", GatewayIntegrationTest::startHospitalTools);
    }

    private static synchronized String startHospitalTools() {
        if (hospitalTools == null) {
            createHospitalDatabaseUser();
            // Command-line arguments, because they outrank the service's own hospital-tools.yml.
            hospitalTools = new SpringApplicationBuilder(HospitalToolsApplication.class, HospitalToolsTestSecurity.class)
                .properties(HospitalToolsApplication.DEFAULTS)
                .run(
                    "--server.port=0",
                    "--spring.datasource.url=" + ORACLE.getJdbcUrl(),
                    "--spring.datasource.username=hospital",
                    "--spring.datasource.password=hospital",
                    "--hospital.service-token=" + SERVICE_TOKEN);
        }
        return "http://localhost:" + hospitalTools.getEnvironment().getProperty("local.server.port");
    }

    /** Same as infra/oracle-init/01-schemas.sh: a separate schema the gateway's user cannot read. */
    private static void createHospitalDatabaseUser() {
        try (Connection c = DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE USER hospital IDENTIFIED BY hospital QUOTA UNLIMITED ON USERS");
            s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO hospital");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1920) { // ORA-01920: user already exists (reused container)
                throw new IllegalStateException(e);
            }
        }
    }

    /** Direct access to the hospital schema, to find test data independently of the gateway. */
    protected static org.springframework.jdbc.core.JdbcTemplate hospitalDb() {
        return new org.springframework.jdbc.core.JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(ORACLE.getJdbcUrl(), "hospital", "hospital"));
    }

    @LocalServerPort
    protected int port;

    protected String login(String username) {
        Map<?, ?> body = RestClient.create("http://localhost:" + port).post().uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("username", username, "password", DEMO_PASSWORD))
            .retrieve().body(Map.class);
        return (String) body.get("accessToken");
    }

    /** An MCP client that talks to the gateway the way an agent would, with the user's token. */
    protected McpSyncClient agent(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .httpRequestCustomizer((request, method, uri, body, context) -> {
                if (token != null) {
                    request.header("Authorization", "Bearer " + token);
                }
            })
            .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }
}
