package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.auth.UserIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces Spring AI's default MCP transport with one that copies the
 * caller's verified identity into the MCP transport context.
 *
 * Why: tool handlers do not run on the HTTP request thread, so they cannot
 * rely on Spring Security's thread-local context. The extractor runs while
 * the request is being handled, after the JWT filter, and hands the identity
 * to the handler explicitly.
 */
@Configuration
public class McpServerConfig {

    public static final String IDENTITY = "agentguard.identity";

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        return WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(endpoint)
            .contextExtractor(request -> request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> UserIdentity.from(((JwtAuthenticationToken) p).getToken()))
                .map(identity -> McpTransportContext.create(Map.of(IDENTITY, identity)))
                .orElse(McpTransportContext.EMPTY))
            .build();
    }
}
