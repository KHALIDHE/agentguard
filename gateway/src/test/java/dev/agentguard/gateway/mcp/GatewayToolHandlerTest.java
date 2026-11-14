package dev.agentguard.gateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gateway's own checks, without the MCP SDK's schema validation in front.
 * If that validation were ever disabled, these still hold.
 */
class GatewayToolHandlerTest {

    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final GatewayToolHandler handler = new GatewayToolHandler(upstream);

    private final McpSchema.Tool tool = ToolRegistrar.withoutScopeParameters(McpSchema.Tool.builder()
        .name("search_patients")
        .inputSchema(Map.of("type", "object",
            "properties", Map.of("name", Map.of("type", "string"), "scopeDepartment", Map.of("type", "string")),
            "required", List.of()))
        .build());

    private static McpSyncServerExchange exchangeWith(Object identity) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(identity == null
            ? McpTransportContext.EMPTY
            : McpTransportContext.create(Map.of(McpServerConfig.IDENTITY, identity)));
        return exchange;
    }

    private static final UserIdentity NURSE = new UserIdentity(3, "nurse.salma", Role.NURSE, "CARDIO", null);

    @Test
    void callWithoutIdentityNeverReachesUpstream() {
        var result = handler.handle(tool, exchangeWith(null),
            new McpSchema.CallToolRequest("search_patients", Map.of("name", "a")));

        assertThat(result.isError()).isTrue();
        verify(upstream, never()).call(any(), anyMap());
    }

    @Test
    void scopeArgumentNeverReachesUpstream() {
        var result = handler.handle(tool, exchangeWith(NURSE),
            new McpSchema.CallToolRequest("search_patients", Map.of("name", "a", "scopeDepartment", "NEURO")));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
            .startsWith("Refused by AgentGuard").contains("scopeDepartment");
        verify(upstream, never()).call(any(), anyMap());
    }

    @Test
    void allowedArgumentsAreForwardedUnchanged() {
        McpSchema.CallToolResult upstreamResult = McpSchema.CallToolResult.builder().addTextContent("[]").build();
        when(upstream.call("search_patients", Map.of("name", "a"))).thenReturn(upstreamResult);

        var result = handler.handle(tool, exchangeWith(NURSE),
            new McpSchema.CallToolRequest("search_patients", Map.of("name", "a")));

        assertThat(result).isSameAs(upstreamResult);
    }
}
