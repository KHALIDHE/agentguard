package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.policy.KnownTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the upstream tools on the gateway's own MCP endpoint, so agents
 * see the same tools but every call lands in GatewayToolHandler first.
 *
 * Scope parameters (scopeDepartment, scopeDoctorId) are removed from the
 * published schemas: they are set by the gateway from the user's identity
 * and an agent must not even know they exist.
 *
 * Retries every 15 seconds until the upstream answers, so the gateway can
 * start before the tools service.
 */
@Component
public class ToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrar.class);

    private final UpstreamClient upstream;
    private final McpSyncServer server;
    private final GatewayToolHandler handler;
    private final Map<String, McpSchema.Tool> published = new ConcurrentHashMap<>();

    public ToolRegistrar(UpstreamClient upstream, McpSyncServer server, GatewayToolHandler handler) {
        this.upstream = upstream;
        this.server = server;
        this.handler = handler;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 15_000)
    public void registerWhenUpstreamIsReady() {
        if (!published.isEmpty()) {
            return;
        }
        try {
            for (McpSchema.Tool tool : upstream.listTools()) {
                if (!KnownTools.ALL.contains(tool.name())) {
                    log.warn("Upstream tool {} is not in the gateway's catalogue and stays hidden", tool.name());
                    continue;
                }
                McpSchema.Tool visible = withoutScopeParameters(tool);
                server.addTool(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(visible)
                    .callHandler((exchange, request) -> handler.handle(visible, exchange, request))
                    .build());
                published.put(visible.name(), visible);
            }
            server.notifyToolsListChanged();
            log.info("Published {} upstream tools: {}", published.size(), published.keySet());
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("Upstream tools not reachable yet ({}: {}), retrying in 15 s", e.getMessage(), root.getMessage());
        }
    }

    public boolean isReady() {
        return !published.isEmpty();
    }

    static boolean isScopeParameter(String name) {
        return name.startsWith("scope");
    }

    @SuppressWarnings("unchecked")
    static McpSchema.Tool withoutScopeParameters(McpSchema.Tool tool) {
        Map<String, Object> in = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        Map<String, Object> properties = new LinkedHashMap<>();
        Object inProps = in.get("properties");
        if (inProps instanceof Map<?, ?> props) {
            ((Map<String, Object>) props).forEach((k, v) -> {
                if (!isScopeParameter(k)) {
                    properties.put(k, v);
                }
            });
        }
        List<String> required = new ArrayList<>();
        if (in.get("required") instanceof List<?> req) {
            req.stream().map(String::valueOf).filter(r -> !isScopeParameter(r)).forEach(required::add);
        }
        Map<String, Object> out = new LinkedHashMap<>(in);
        out.put("type", "object");
        out.put("properties", properties);
        out.put("required", required);
        // additionalProperties=false tells clients that unknown arguments are not accepted.
        out.put("additionalProperties", false);
        return McpSchema.Tool.builder()
            .name(tool.name())
            .title(tool.title())
            .description(tool.description())
            .inputSchema(out)
            .annotations(tool.annotations())
            .build();
    }
}
