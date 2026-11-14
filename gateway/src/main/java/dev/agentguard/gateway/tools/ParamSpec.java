package dev.agentguard.gateway.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One parameter of a tool, as the gateway accepts it. Strict on purpose:
 * types, ranges, allowed values and lengths are checked here, before any
 * policy decision or upstream call.
 */
public record ParamSpec(
        String name,
        Type type,
        boolean required,
        String description,
        Integer maxLength,
        Long min,
        Long max,
        List<String> values,
        String pattern) {

    public enum Type { STRING, INTEGER, DATE, ENUM }

    public static ParamSpec string(String name, boolean required, int maxLength, String description) {
        return new ParamSpec(name, Type.STRING, required, description, maxLength, null, null, null, null);
    }

    public static ParamSpec code(String name, boolean required, String description) {
        return new ParamSpec(name, Type.STRING, required, description, 16, null, null, null, "^[A-Z]{2,16}$");
    }

    public static ParamSpec integer(String name, boolean required, long min, long max, String description) {
        return new ParamSpec(name, Type.INTEGER, required, description, null, min, max, null, null);
    }

    public static ParamSpec date(String name, boolean required, String description) {
        return new ParamSpec(name, Type.DATE, required, description, null, null, null, null, null);
    }

    public static ParamSpec oneOf(String name, boolean required, List<String> values, String description) {
        return new ParamSpec(name, Type.ENUM, required, description, null, null, null, List.copyOf(values), null);
    }

    /** Returns an error message, or null when the value is acceptable. */
    String check(Object value) {
        return switch (type) {
            case STRING -> {
                if (!(value instanceof String s)) yield "must be a string";
                if (maxLength != null && s.length() > maxLength) yield "is longer than " + maxLength + " characters";
                if (pattern != null && !Pattern.matches(pattern, s)) yield "has an invalid format";
                yield null;
            }
            case INTEGER -> {
                if (!(value instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue())
                        || Double.isInfinite(n.doubleValue())) {
                    yield "must be a whole number";
                }
                long v = n.longValue();
                if (min != null && v < min) yield "must be at least " + min;
                if (max != null && v > max) yield "must be at most " + max;
                yield null;
            }
            case DATE -> {
                if (!(value instanceof String s)) yield "must be a date (YYYY-MM-DD)";
                try {
                    LocalDate.parse(s);
                    yield null;
                } catch (DateTimeParseException e) {
                    yield "must be a date (YYYY-MM-DD)";
                }
            }
            case ENUM -> values.contains(String.valueOf(value)) && value instanceof String
                ? null : "must be one of " + values;
        };
    }

    Map<String, Object> jsonSchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        switch (type) {
            case STRING -> {
                s.put("type", "string");
                if (maxLength != null) s.put("maxLength", maxLength);
                if (pattern != null) s.put("pattern", pattern);
            }
            case INTEGER -> {
                s.put("type", "integer");
                if (min != null) s.put("minimum", min);
                if (max != null) s.put("maximum", max);
            }
            case DATE -> {
                s.put("type", "string");
                s.put("format", "date");
            }
            case ENUM -> {
                s.put("type", "string");
                s.put("enum", values);
            }
        }
        s.put("description", description);
        return s;
    }

    static Set<String> names(List<ParamSpec> specs) {
        return specs.stream().map(ParamSpec::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
