package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.mcp.GatewayToolHandler;
import dev.agentguard.gateway.mcp.ToolRegistrar;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A tool call goes agent -> gateway -> hospital tools -> Oracle and back. */
class PassThroughTest extends GatewayIntegrationTest {

    @Autowired
    ToolRegistrar registrar;

    @BeforeEach
    void waitForUpstreamTools() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!registrar.isReady() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
        assertThat(registrar.isReady()).as("gateway published the upstream tools").isTrue();
    }

    @Test
    void agentSeesTheUpstreamToolsWithoutScopeParameters() {
        try (McpSyncClient client = agent(login("nurse.salma"))) {
            List<McpSchema.Tool> tools = client.listTools().tools();

            assertThat(tools).extracting(McpSchema.Tool::name)
                .contains("search_patients", "get_patient_record", "get_staff_salaries", "run_query")
                .contains("get_approval_status")
                .hasSize(11);
            assertThat(tools).filteredOn(t -> !t.name().equals("get_approval_status")).allSatisfy(tool -> {
                assertThat(GatewayToolHandler.publishedParameters(tool)).noneMatch(p -> p.startsWith("scope"));
                assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            });
        }
    }

    @Test
    void callIsForwardedAndTheRealDataComesBack() {
        try (McpSyncClient client = agent(login("dr.amrani"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_staff_directory", Map.of("department", "CARDIO")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("@hospital.example").contains("CARDIO");
        }
    }

    @Test
    void agentCannotInjectScopeParameters() {
        try (McpSyncClient client = agent(login("nurse.salma"))) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "search_patients", Map.of("name", "a", "scopeDepartment", "NEURO")));

            // GatewayToolHandler refuses it (and audits the refusal) before any upstream call.
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).startsWith("Refused by AgentGuard").contains("scopeDepartment");
        }
    }

    @Test
    void unknownArgumentsAreRefused() {
        try (McpSyncClient client = agent(login("nurse.salma"))) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "get_document", Map.of("documentId", 1, "includeHidden", true)));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("includeHidden");
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    void nurseIsDeniedSalariesWithTheRuleAndReason() {
        try (McpSyncClient client = agent(login("nurse.salma"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_staff_salaries", Map.of()));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("salaries-everyone-else").contains("restricted to HR");
        }
    }

    @Test
    void hrGetsSalaries() {
        try (McpSyncClient client = agent(login("hr.imane"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_staff_salaries", Map.of("department", "CARDIO")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("SALARY_MAD");
        }
    }

    @Test
    void bulkCancellationWaitsForApproval() {
        try (McpSyncClient client = agent(login("reception.omar"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("cancel_appointments", Map.of("date", "2030-01-01")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).startsWith("PENDING_APPROVAL").contains("cancel-day-reception");
        }
    }

    @Test
    void adminCanReadThePolicyAndOthersCannot() {
        var http = org.springframework.web.client.RestClient.create("http://localhost:" + port);

        int asAdmin = http.get().uri("/admin/policies").header("Authorization", "Bearer " + login("admin.youssef"))
            .exchange((req, res) -> res.getStatusCode().value());
        int asNurse = http.get().uri("/admin/policies").header("Authorization", "Bearer " + login("nurse.salma"))
            .exchange((req, res) -> res.getStatusCode().value());

        assertThat(asAdmin).isEqualTo(200);
        assertThat(asNurse).isEqualTo(403);
    }
}
