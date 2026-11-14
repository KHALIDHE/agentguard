package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.policy.KnownTools;
import dev.agentguard.gateway.tools.ToolCatalog;
import dev.agentguard.gateway.tools.ToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
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
 * The published schemas are the gateway's own (ToolCatalog), not the
 * upstream ones: scope parameters never appear, and a change upstream cannot
 * widen what agents may send. Upstream tools missing from the catalogue stay
 * hidden.
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
                var schema = ToolCatalog.find(tool.name());
                if (schema.isEmpty() || !KnownTools.ALL.contains(tool.name())) {
                    log.warn("Upstream tool {} is not in the gateway's catalogue and stays hidden", tool.name());
                    continue;
                }
                McpSchema.Tool visible = published(tool, schema.get());
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

    /**
     * The tool as agents see it: upstream name and description, but the
     * gateway's own strict schema. Scope parameters are never in it.
     */
    static McpSchema.Tool published(McpSchema.Tool upstream, ToolSchema schema) {
        return McpSchema.Tool.builder()
            .name(upstream.name())
            .title(upstream.title())
            .description(upstream.description())
            .inputSchema(schema.jsonSchema())
            .annotations(upstream.annotations())
            .build();
    }
}
