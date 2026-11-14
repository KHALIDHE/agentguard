package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ArgumentValidationTest {

    private static String error(String tool, Map<String, Object> args) {
        return ToolCatalog.find(tool).orElseThrow().validate(args).orElse(null);
    }

    @Test
    void validArgumentsPass() {
        assertThat(error("get_patient_record", Map.of("patientId", 42))).isNull();
        assertThat(error("list_appointments", Map.of("date", "2026-11-15", "status", "SCHEDULED"))).isNull();
        assertThat(error("search_patients", Map.of())).isNull();
    }

    @ParameterizedTest(name = "{0} {1}={2} -> {3}")
    @CsvSource(delimiter = '|', value = {
        "get_patient_record | patientId     | abc                 | must be a whole number",
        "get_patient_record | patientId     | 0                   | must be at least 1",
        "get_patient_record | patientId     | 99999999999         | must be at most",
        "get_patient_record | patientId     | 4.5                 | must be a whole number",
        "list_appointments  | date          | 2026-13-45          | must be a date",
        "list_appointments  | date          | tomorrow            | must be a date",
        "list_appointments  | status        | MAYBE               | must be one of",
        "update_appointment | status        | scheduled           | must be one of",
        "get_staff_directory| department    | cardio              | invalid format",
        "get_staff_directory| department    | CARDIO; DROP TABLE  | longer than 16",
        "export_report      | reportType    | EVERYTHING          | must be one of",
        "search_patients    | limit         | 1000                | must be at most 100",
    })
    void malformedValuesAreRefused(String tool, String arg, String value, String expected) {
        Map<String, Object> args = baseArgs(tool);
        args.put(arg, parse(value));

        assertThat(error(tool, args)).contains(expected);
    }

    @Test
    void oversizedStringsAreRefused() {
        assertThat(error("search_patients", Map.of("name", "a".repeat(81)))).contains("longer than 80");
        assertThat(error("run_query", Map.of("sql", "SELECT id FROM patient WHERE " + "x".repeat(2000))))
            .contains("longer than 2000");
    }

    @Test
    void missingRequiredArgumentsAreRefused() {
        assertThat(error("get_patient_record", Map.of())).contains("'patientId' is required");
    }

    @Test
    void unknownAndScopeArgumentsAreRefused() {
        assertThat(error("search_patients", Map.of("scopeDepartment", "NEURO"))).contains("not accepted");
        assertThat(error("get_document", Map.of("documentId", 1, "raw", true))).contains("not accepted");
    }

    private static Map<String, Object> baseArgs(String tool) {
        Map<String, Object> args = new HashMap<>();
        switch (tool) {
            case "list_appointments" -> args.put("date", "2026-11-15");
            case "update_appointment" -> { args.put("appointmentId", 1); args.put("status", "DONE"); }
            default -> { }
        }
        return args;
    }

    private static Object parse(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e2) {
                return v;
            }
        }
    }
}
