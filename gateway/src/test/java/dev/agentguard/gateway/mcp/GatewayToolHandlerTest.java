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
import dev.agentguard.gateway.policy.Condition;
import dev.agentguard.gateway.policy.DecisionType;
import dev.agentguard.gateway.policy.PolicyEngine;
import dev.agentguard.gateway.policy.PolicyRule;
import dev.agentguard.gateway.policy.RowScope;
import dev.agentguard.gateway.tools.ToolCatalog;
import java.util.Set;
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
    private final PolicyEngine policies = new PolicyEngine();
    private final GatewayToolHandler handler = new GatewayToolHandler(upstream, policies,
        mock(dev.agentguard.gateway.approval.ApprovalService.class));

    {
        policies.replaceRules(List.of(new PolicyRule("nurse-search", 1, Set.of(Role.NURSE), Set.of("search_patients"),
            Condition.ALWAYS, DecisionType.ALLOW, RowScope.SAME_DEPARTMENT, null, "test")));
    }

    private final McpSchema.Tool tool = ToolRegistrar.published(
        McpSchema.Tool.builder().name("search_patients").inputSchema(Map.of("type", "object")).build(),
        ToolCatalog.find("search_patients").orElseThrow());

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
    void allowedCallIsForwardedWithTheScopeFromTheIdentity() {
        McpSchema.CallToolResult upstreamResult = McpSchema.CallToolResult.builder().addTextContent("[]").build();
        when(upstream.call("search_patients", Map.of("name", "a", "scopeDepartment", "CARDIO"))).thenReturn(upstreamResult);

        var result = handler.handle(tool, exchangeWith(NURSE),
            new McpSchema.CallToolRequest("search_patients", Map.of("name", "a")));

        assertThat(result).isSameAs(upstreamResult);
    }

    @Test
    void deniedCallNeverReachesUpstream() {
        UserIdentity hr = new UserIdentity(5, "hr", Role.HR, null, null);

        var result = handler.handle(tool, exchangeWith(hr),
            new McpSchema.CallToolRequest("search_patients", Map.of("name", "a")));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("default-deny");
        verify(upstream, never()).call(any(), anyMap());
    }
}
