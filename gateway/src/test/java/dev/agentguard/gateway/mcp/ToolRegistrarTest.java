package dev.agentguard.gateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.policy.KnownTools;
import dev.agentguard.gateway.tools.ToolCatalog;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring, no database. */
class ToolRegistrarTest {

    @Test
    void publishedSchemaIsTheGatewaysNotTheUpstreams() {
        McpSchema.Tool upstream = McpSchema.Tool.builder()
            .name("search_suppliers")
            .description("Search suppliers")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "scopeDepartment", Map.of("type", "string"),
                    "dangerousNewFlag", Map.of("type", "boolean")),
                "required", List.of("name", "scopeDepartment")))
            .build();

        McpSchema.Tool published = ToolRegistrar.published(upstream, ToolCatalog.find("search_suppliers").orElseThrow());

        assertThat(published.description()).isEqualTo("Search suppliers");
        assertThat(GatewayToolHandler.publishedParameters(published)).containsOnly("name", "limit");
        assertThat(published.inputSchema()).containsEntry("additionalProperties", false);
    }

    @Test
    void everyKnownToolHasASchema() {
        assertThat(ToolCatalog.toolNames()).containsExactlyInAnyOrderElementsOf(KnownTools.ALL);
    }
}
