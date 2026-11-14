package dev.agentguard.gateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring, no database. */
class ToolRegistrarTest {

    @Test
    void scopeParametersAreRemovedFromThePublishedSchema() {
        McpSchema.Tool upstream = McpSchema.Tool.builder()
            .name("search_patients")
            .description("Search patients")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "scopeDepartment", Map.of("type", "string"),
                    "scopeDoctorId", Map.of("type", "integer")),
                "required", List.of("name", "scopeDepartment")))
            .build();

        McpSchema.Tool published = ToolRegistrar.withoutScopeParameters(upstream);

        assertThat(published.name()).isEqualTo("search_patients");
        assertThat(GatewayToolHandler.publishedParameters(published)).containsOnly("name");
        assertThat(published.inputSchema()).containsEntry("required", List.of("name"))
            .containsEntry("additionalProperties", false);
    }
}
