package dev.agentguard.gateway.mcp;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The MCP SDK can validate tool arguments against the published schema before
 * calling a handler. AgentGuard turns that off and validates in
 * GatewayToolHandler instead, with stricter rules, so that every refused
 * call, including a malformed one, goes through the same path and gets an
 * audit record. The published schema still tells clients what is accepted.
 *
 * immediateExecution keeps Spring AI's servlet default: handlers run on the
 * request thread.
 */
@Configuration
public class McpServerCustomization {

    @Bean
    @Primary
    McpSyncServerCustomizer gatewayServerCustomizer() {
        return spec -> spec.immediateExecution(true).validateToolInputs(false);
    }
}
