package dev.agentguard.gateway.policy;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Specification pattern: a small object that answers yes or no for a call,
 * and that combines with others (all, any, not). A rule holds one condition
 * tree, built from the policy file, instead of hand-written if statements.
 */
public sealed interface Condition {

    boolean test(ToolCallContext call);

    /** Shown in the dashboard's policy viewer. */
    String describe();

    Condition ALWAYS = new Always();

    record Always() implements Condition {
        public boolean test(ToolCallContext call) { return true; }
        public String describe() { return "always"; }
    }

    /** argument equals one of the given values (compared as strings, case-sensitive). */
    record ArgumentIn(String argument, Set<String> values) implements Condition {
        public ArgumentIn {
            values = Set.copyOf(values);
        }
        public boolean test(ToolCallContext call) {
            Object value = call.argument(argument);
            return value != null && values.contains(String.valueOf(value));
        }
        public String describe() {
            return argument + " in " + values.stream().sorted().collect(Collectors.joining(", ", "[", "]"));
        }
    }

    /** argument is present and numerically greater than a threshold. */
    record ArgumentGreaterThan(String argument, double threshold) implements Condition {
        public boolean test(ToolCallContext call) {
            return call.argument(argument) instanceof Number n && n.doubleValue() > threshold;
        }
        public String describe() { return argument + " > " + threshold; }
    }

    /** argument was sent at all. */
    record ArgumentPresent(String argument) implements Condition {
        public boolean test(ToolCallContext call) { return call.argument(argument) != null; }
        public String describe() { return argument + " is present"; }
    }

    record AllOf(List<Condition> parts) implements Condition {
        public AllOf {
            parts = List.copyOf(parts);
        }
        public boolean test(ToolCallContext call) { return parts.stream().allMatch(p -> p.test(call)); }
        public String describe() { return join(parts, " and "); }
    }

    record AnyOf(List<Condition> parts) implements Condition {
        public AnyOf {
            parts = List.copyOf(parts);
        }
        public boolean test(ToolCallContext call) { return parts.stream().anyMatch(p -> p.test(call)); }
        public String describe() { return join(parts, " or "); }
    }

    record Not(Condition inner) implements Condition {
        public Not {
            Objects.requireNonNull(inner);
        }
        public boolean test(ToolCallContext call) { return !inner.test(call); }
        public String describe() { return "not (" + inner.describe() + ")"; }
    }

    private static String join(Collection<Condition> parts, String separator) {
        return parts.stream().map(p -> "(" + p.describe() + ")").collect(Collectors.joining(separator));
    }
}
