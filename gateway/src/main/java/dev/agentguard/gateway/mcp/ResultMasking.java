package dev.agentguard.gateway.mcp;

import dev.agentguard.gateway.tools.FieldMasker;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Applies a mask profile to a whole MCP result. Used for direct calls and
 * for approved calls executed later, so both paths mask the same way.
 */
public final class ResultMasking {

    private ResultMasking() {
    }

    /**
     * Masks every text part. If a part is not text or cannot be parsed as
     * JSON, the whole result is withheld: returning it unmasked would defeat
     * the policy. structuredContent is dropped because it would carry the
     * unmasked fields.
     */
    public static McpSchema.CallToolResult mask(McpSchema.CallToolResult result, String profile) {
        McpSchema.CallToolResult.Builder masked = McpSchema.CallToolResult.builder().isError(result.isError());
        for (McpSchema.Content part : result.content()) {
            if (!(part instanceof McpSchema.TextContent text)) {
                return withheld();
            }
            try {
                masked.addTextContent(FieldMasker.maskJson(text.text(), profile));
            } catch (RuntimeException e) {
                return withheld();
            }
        }
        return masked.build();
    }

    private static McpSchema.CallToolResult withheld() {
        return McpSchema.CallToolResult.builder().isError(true)
            .addTextContent("Refused by AgentGuard: the result could not be masked and was withheld").build();
    }
}
