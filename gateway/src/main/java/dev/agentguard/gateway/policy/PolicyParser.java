package dev.agentguard.gateway.policy;

import dev.agentguard.gateway.auth.Role;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads policies.yml into PolicyRule objects and refuses anything unclear:
 * an unknown key, role, decision or tool, a duplicate id, a MASK rule with
 * no mask profile. A typo in a security policy must stop the gateway at
 * startup, not silently allow or deny at runtime.
 */
public final class PolicyParser {

    private static final Set<String> RULE_KEYS = Set.of(
        "id", "priority", "roles", "tools", "when", "decision", "scope", "maskProfile", "reason");

    private PolicyParser() {
    }

    public static List<PolicyRule> parse(InputStream yaml, Set<String> knownTools, Set<String> knownMaskProfiles) {
        // SafeConstructor: only plain maps, lists and scalars, never arbitrary Java objects.
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> doc) || !(doc.get("rules") instanceof List<?> list)) {
            throw new PolicyException("policy file must have a top-level 'rules' list");
        }
        List<PolicyRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : list) {
            PolicyRule rule = parseRule(asMap(item, "rule"), knownTools, knownMaskProfiles);
            if (!ids.add(rule.id())) {
                throw new PolicyException("duplicate rule id: " + rule.id());
            }
            rules.add(rule);
        }
        return rules;
    }

    private static PolicyRule parseRule(Map<String, Object> m, Set<String> knownTools, Set<String> knownMasks) {
        String id = requireString(m, "id");
        for (String key : m.keySet()) {
            if (!RULE_KEYS.contains(key)) {
                throw new PolicyException("rule " + id + ": unknown key '" + key + "'");
            }
        }
        int priority = m.get("priority") instanceof Number n ? n.intValue()
            : fail("rule " + id + ": 'priority' must be a number");

        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String r : stringList(m.get("roles"), id, "roles")) {
            try {
                roles.add(Role.valueOf(r));
            } catch (IllegalArgumentException e) {
                throw new PolicyException("rule " + id + ": unknown role " + r);
            }
        }
        Set<String> tools = new LinkedHashSet<>(stringList(m.get("tools"), id, "tools"));
        for (String t : tools) {
            if (!knownTools.contains(t)) {
                throw new PolicyException("rule " + id + ": unknown tool " + t);
            }
        }
        DecisionType decision = enumValue(DecisionType.class, requireString(m, "decision"), id, "decision");
        RowScope scope = m.containsKey("scope")
            ? enumValue(RowScope.class, requireString(m, "scope"), id, "scope") : RowScope.NONE;
        String mask = m.get("maskProfile") == null ? null : String.valueOf(m.get("maskProfile"));
        if (decision == DecisionType.MASK && mask == null) {
            throw new PolicyException("rule " + id + ": a MASK rule needs a maskProfile");
        }
        if (mask != null && !knownMasks.contains(mask)) {
            throw new PolicyException("rule " + id + ": unknown mask profile " + mask);
        }
        Condition when = m.containsKey("when") ? condition(m.get("when"), id) : Condition.ALWAYS;
        return new PolicyRule(id, priority, roles, tools, when, decision, scope, mask, requireString(m, "reason"));
    }

    /**
     * Condition grammar:
     *   { argument: x, in: [a, b] }   { argument: x, gt: 10 }   { argument: x, present: true }
     *   { all: [...] }   { any: [...] }   { not: {...} }
     */
    static Condition condition(Object node, String ruleId) {
        Map<String, Object> m = asMap(node, "condition in rule " + ruleId);
        if (m.containsKey("all")) {
            return new Condition.AllOf(conditions(m.get("all"), ruleId));
        }
        if (m.containsKey("any")) {
            return new Condition.AnyOf(conditions(m.get("any"), ruleId));
        }
        if (m.containsKey("not")) {
            return new Condition.Not(condition(m.get("not"), ruleId));
        }
        String argument = requireString(m, "argument");
        if (m.containsKey("in")) {
            return new Condition.ArgumentIn(argument, new HashSet<>(stringList(m.get("in"), ruleId, "in")));
        }
        if (m.get("gt") instanceof Number n) {
            return new Condition.ArgumentGreaterThan(argument, n.doubleValue());
        }
        if (Boolean.TRUE.equals(m.get("present"))) {
            return new Condition.ArgumentPresent(argument);
        }
        throw new PolicyException("rule " + ruleId + ": condition on '" + argument + "' needs in, gt or present");
    }

    private static List<Condition> conditions(Object node, String ruleId) {
        if (!(node instanceof Collection<?> c) || c.isEmpty()) {
            throw new PolicyException("rule " + ruleId + ": all/any needs a non-empty list");
        }
        return c.stream().map(n -> condition(n, ruleId)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String what) {
        if (node instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new PolicyException(what + " must be a mapping");
    }

    private static String requireString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new PolicyException("missing '" + key + "' in " + m);
        }
        return String.valueOf(v);
    }

    private static List<String> stringList(Object node, String ruleId, String key) {
        if (!(node instanceof Collection<?> c) || c.isEmpty()) {
            throw new PolicyException("rule " + ruleId + ": '" + key + "' must be a non-empty list");
        }
        return c.stream().map(String::valueOf).toList();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String ruleId, String key) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new PolicyException("rule " + ruleId + ": unknown " + key + " " + value);
        }
    }

    private static <T> T fail(String message) {
        throw new PolicyException(message);
    }

    public static class PolicyException extends RuntimeException {
        public PolicyException(String message) {
            super(message);
        }
    }
}
