package dev.agentguard.gateway.policy;

import dev.agentguard.gateway.auth.UserIdentity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the policy engine may look at for one tool call. Immutable, and
 * built only from the verified identity and the call itself: nothing the
 * model "says" about who the user is can get in here.
 */
public record ToolCallContext(
        String requestId,
        UserIdentity identity,
        String tool,
        Map<String, Object> arguments,
        Instant receivedAt) {

    public ToolCallContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolCallContext of(UserIdentity identity, String tool, Map<String, Object> arguments) {
        return new ToolCallContext(UUID.randomUUID().toString(), identity, tool, arguments, Instant.now());
    }

    public Object argument(String name) {
        return arguments.get(name);
    }
}
