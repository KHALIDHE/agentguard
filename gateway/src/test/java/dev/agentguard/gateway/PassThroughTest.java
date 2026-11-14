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
                .hasSize(10);
            assertThat(tools).allSatisfy(tool -> {
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

            // Refused twice over: the MCP SDK validates against the published schema
            // (additionalProperties: false), and GatewayToolHandler checks again
            // (see GatewayToolHandlerTest). Either way the call never reaches upstream.
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("scopeDepartment");
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
}
