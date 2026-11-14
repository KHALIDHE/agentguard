package dev.agentguard.gateway.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The gateway's own definition of a tool: the parameters agents may send, and
 * the scope parameters only the gateway may add. Agents see this schema, not
 * the upstream one, so a change on the upstream server cannot widen what an
 * agent is allowed to send.
 */
public record ToolSchema(String name, List<ParamSpec> params, Set<String> scopeParams) {

    public ToolSchema {
        params = List.copyOf(params);
        scopeParams = Set.copyOf(scopeParams);
    }

    public Optional<String> validate(Map<String, Object> arguments) {
        Set<String> known = ParamSpec.names(params);
        for (String arg : arguments.keySet()) {
            if (!known.contains(arg)) {
                return Optional.of("Argument '" + arg + "' is not accepted by this tool");
            }
        }
        for (ParamSpec p : params) {
            Object value = arguments.get(p.name());
            if (value == null) {
                if (p.required()) {
                    return Optional.of("Argument '" + p.name() + "' is required");
                }
                continue;
            }
            String error = p.check(value);
            if (error != null) {
                return Optional.of("Argument '" + p.name() + "' " + error);
            }
        }
        return Optional.empty();
    }

    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParamSpec p : params) {
            properties.put(p.name(), p.jsonSchema());
            if (p.required()) {
                required.add(p.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    public Set<String> parameterNames() {
        return ParamSpec.names(params);
    }
}
