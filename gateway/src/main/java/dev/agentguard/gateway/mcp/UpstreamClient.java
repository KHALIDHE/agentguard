package dev.agentguard.gateway.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The gateway's MCP client towards the hospital tools. It authenticates with
 * the service token, which agents never see. The connection is opened on
 * first use and reopened if it breaks.
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final String baseUrl;
    private final String serviceToken;
    private McpSyncClient client;

    public UpstreamClient(@Value("${agentguard.upstream.hospital-tools-url}") String baseUrl,
                          @Value("${agentguard.upstream.service-token:}") String serviceToken) {
        this.baseUrl = baseUrl;
        this.serviceToken = serviceToken;
    }

    public List<McpSchema.Tool> listTools() {
        return withClient(c -> c.listTools().tools());
    }

    public McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return withClient(c -> c.callTool(new McpSchema.CallToolRequest(tool, arguments)));
    }

    private synchronized <T> T withClient(java.util.function.Function<McpSyncClient, T> action) {
        if (client == null) {
            client = connect();
        }
        try {
            return action.apply(client);
        } catch (RuntimeException e) {
            log.warn("Upstream call failed, reconnecting once: {}", e.getMessage());
            close();
            client = connect();
            return action.apply(client);
        }
    }

    private McpSyncClient connect() {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
            .endpoint("/mcp")
            .connectTimeout(Duration.ofSeconds(5))
            .httpRequestCustomizer((request, method, uri, body, context) ->
                request.header("Authorization", "Bearer " + serviceToken))
            .build();
        McpSyncClient c = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .clientInfo(new McpSchema.Implementation("agentguard-gateway", "0.1.0"))
            .build();
        c.initialize();
        log.info("Connected to upstream tools at {}", baseUrl);
        return c;
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // closing a broken connection
            }
            client = null;
        }
    }
}
