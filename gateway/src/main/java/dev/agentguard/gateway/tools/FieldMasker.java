package dev.agentguard.gateway.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes or hides sensitive fields in a tool result before it goes back to
 * the agent, so the model never receives what the user may not see.
 *
 * A profile is either an allow-list (keep only these fields) or a list of
 * fields to remove and fields to partly hide. Allow-lists are safer: a new
 * sensitive column added upstream is hidden until someone allows it.
 */
public final class FieldMasker {

    record Profile(Set<String> keepOnly, Set<String> remove, Set<String> partial) {}

    static final Map<String, Profile> PROFILES = Map.of(
        "nurse-patient", new Profile(null, Set.of("ADDRESS", "DIAGNOSIS"), Set.of("NATIONAL_ID", "PHONE")),
        "reception-patient", new Profile(Set.of("ID", "FULL_NAME", "DEPARTMENT", "DOCTOR_ID", "ADMITTED_ON"),
            Set.of(), Set.of()));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FieldMasker() {
    }

    /** Masks one JSON text. Throws if the text is not JSON: the caller must then withhold the result. */
    public static String maskJson(String json, String profileName) {
        Profile profile = PROFILES.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown mask profile " + profileName);
        }
        Object tree = JSON.readValue(json, Object.class);
        return JSON.writeValueAsString(mask(tree, profile));
    }

    @SuppressWarnings("unchecked")
    private static Object mask(Object node, Profile profile) {
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(mask(item, profile));
            }
            return out;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((key, value) -> {
                String k = key.toUpperCase(Locale.ROOT);
                if (profile.keepOnly() != null && !profile.keepOnly().contains(k) && !k.equals("FOUND")) {
                    return;
                }
                if (profile.remove().contains(k)) {
                    return;
                }
                if (profile.partial().contains(k) && value != null) {
                    out.put(key, partial(String.valueOf(value)));
                    return;
                }
                out.put(key, mask(value, profile));
            });
            return out;
        }
        return node;
    }

    /** Keeps the last two characters: "ZZ100042" becomes "******42". */
    static String partial(String value) {
        if (value.length() <= 2) {
            return "**";
        }
        return "*".repeat(value.length() - 2) + value.substring(value.length() - 2);
    }
}
