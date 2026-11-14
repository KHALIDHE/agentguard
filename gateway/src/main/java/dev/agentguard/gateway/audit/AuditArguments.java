package dev.agentguard.gateway.audit;

import dev.agentguard.gateway.tools.FieldMasker;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * The arguments as they are stored in the audit log: sorted, with personal
 * values (a searched name, free-text notes) hidden, and capped in length.
 * The log must prove what was asked without becoming a copy of the data.
 */
public final class AuditArguments {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PARTLY_HIDDEN = Set.of("name");
    private static final Set<String> HIDDEN = Set.of("notes");
    private static final int MAX = 3_900;

    private AuditArguments() {
    }

    public static String summarize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        Map<String, Object> safe = new TreeMap<>();
        arguments.forEach((k, v) -> {
            if (HIDDEN.contains(k)) {
                safe.put(k, "[hidden]");
            } else if (PARTLY_HIDDEN.contains(k) && v != null) {
                safe.put(k, FieldMasker.partial(String.valueOf(v)));
            } else {
                safe.put(k, v);
            }
        });
        String json = JSON.writeValueAsString(safe);
        return json.length() <= MAX ? json : json.substring(0, MAX) + "...";
    }
}
