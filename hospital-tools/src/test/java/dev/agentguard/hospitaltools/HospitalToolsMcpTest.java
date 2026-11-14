package dev.agentguard.hospitaltools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Talks to the running server the way an agent would: a real MCP client over
 * Streamable HTTP. Proves the tools are published and callable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"spring.config.name=hospital-tools", "hospital.service-token=" + HospitalToolsMcpTest.TOKEN})
class HospitalToolsMcpTest {

    static final String TOKEN = "test-service-token";

    static final Set<String> EXPECTED_TOOLS = Set.of(
        "search_patients", "get_patient_record", "list_appointments", "update_appointment",
        "cancel_appointments", "get_staff_directory", "get_staff_salaries", "export_report",
        "get_document", "run_query");

    @Value("${local.server.port}")
    int port;

    private McpSyncClient client() {
        return client(TOKEN);
    }

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .httpRequestCustomizer((request, method, uri, body, context) -> {
                if (token != null) {
                    request.header("Authorization", "Bearer " + token);
                }
            })
            .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    @Test
    void refusesCallsWithoutTheServiceToken() {
        assertThatThrownBy(() -> client(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client("wrong-token")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void publishesAllTenTools() {
        try (McpSyncClient client = client()) {
            Set<String> names = client.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
        }
    }

    @Test
    void staffDirectoryReturnsNoSalary() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_staff_directory", Map.of("department", "CARDIO")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("DOCTOR").contains("@hospital.example");
            assertThat(text.toLowerCase()).doesNotContain("salary");
        }
    }

    @Test
    void scopeDepartmentLimitsThePatientSearch() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "search_patients", Map.of("limit", 500, "scopeDepartment", "NEURO")));

            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("NEURO");
            assertThat(text).doesNotContain("CARDIO").doesNotContain("PEDIA");
        }
    }
}
