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
        assertThat(error("get_supplier_record", Map.of("supplierId", 42))).isNull();
        assertThat(error("list_invoices", Map.of("date", "2026-11-15", "status", "PENDING"))).isNull();
        assertThat(error("search_suppliers", Map.of())).isNull();
    }

    @ParameterizedTest(name = "{0} {1}={2} -> {3}")
    @CsvSource(delimiter = '|', value = {
        "get_supplier_record | supplierId     | abc                 | must be a whole number",
        "get_supplier_record | supplierId     | 0                   | must be at least 1",
        "get_supplier_record | supplierId     | 99999999999         | must be at most",
        "get_supplier_record | supplierId     | 4.5                 | must be a whole number",
        "list_invoices  | date          | 2026-13-45          | must be a date",
        "list_invoices  | date          | tomorrow            | must be a date",
        "list_invoices  | status        | MAYBE               | must be one of",
        "update_invoice | status        | pending           | must be one of",
        "get_employee_directory| department    | acct              | invalid format",
        "get_employee_directory| department    | ACCT; DROP TABLE users | longer than 16",
        "export_report      | reportType    | EVERYTHING          | must be one of",
        "search_suppliers    | limit         | 1000                | must be at most 100",
    })
    void malformedValuesAreRefused(String tool, String arg, String value, String expected) {
        Map<String, Object> args = baseArgs(tool);
        args.put(arg, parse(value));

        assertThat(error(tool, args)).contains(expected);
    }

    @Test
    void oversizedStringsAreRefused() {
        assertThat(error("search_suppliers", Map.of("name", "a".repeat(81)))).contains("longer than 80");
        assertThat(error("run_query", Map.of("sql", "SELECT id FROM supplier WHERE " + "x".repeat(2000))))
            .contains("longer than 2000");
    }

    @Test
    void missingRequiredArgumentsAreRefused() {
        assertThat(error("get_supplier_record", Map.of())).contains("'supplierId' is required");
    }

    @Test
    void unknownAndScopeArgumentsAreRefused() {
        assertThat(error("search_suppliers", Map.of("scopeDepartment", "TREAS"))).contains("not accepted");
        assertThat(error("get_document", Map.of("documentId", 1, "raw", true))).contains("not accepted");
    }

    private static Map<String, Object> baseArgs(String tool) {
        Map<String, Object> args = new HashMap<>();
        switch (tool) {
            case "list_invoices" -> args.put("date", "2026-11-15");
            case "update_invoice" -> { args.put("invoiceId", 1); args.put("status", "PAID"); }
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
