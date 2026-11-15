package dev.agentguard.gateway.tools;

import static dev.agentguard.gateway.policy.KnownTools.CANCEL_INVOICES;
import static dev.agentguard.gateway.policy.KnownTools.EXPORT_REPORT;
import static dev.agentguard.gateway.policy.KnownTools.GET_DOCUMENT;
import static dev.agentguard.gateway.policy.KnownTools.GET_SUPPLIER_RECORD;
import static dev.agentguard.gateway.policy.KnownTools.GET_EMPLOYEE_DIRECTORY;
import static dev.agentguard.gateway.policy.KnownTools.GET_EMPLOYEE_SALARIES;
import static dev.agentguard.gateway.policy.KnownTools.LIST_INVOICES;
import static dev.agentguard.gateway.policy.KnownTools.RUN_QUERY;
import static dev.agentguard.gateway.policy.KnownTools.SEARCH_SUPPLIERS;
import static dev.agentguard.gateway.policy.KnownTools.UPDATE_INVOICE;
import static dev.agentguard.gateway.tools.ParamSpec.code;
import static dev.agentguard.gateway.tools.ParamSpec.date;
import static dev.agentguard.gateway.tools.ParamSpec.integer;
import static dev.agentguard.gateway.tools.ParamSpec.oneOf;
import static dev.agentguard.gateway.tools.ParamSpec.string;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict schemas for every tool the gateway exposes. */
public final class ToolCatalog {

    public static final String SCOPE_DEPARTMENT = "scopeDepartment";
    public static final String SCOPE_MANAGER = "scopeManagerId";

    private static final List<String> STATUSES = List.of("PENDING", "PAID", "CANCELLED");
    private static final long MAX_ID = 10_000_000L;

    private static final Map<String, ToolSchema> SCHEMAS = Map.of(
        SEARCH_SUPPLIERS, new ToolSchema(SEARCH_SUPPLIERS, List.of(
            string("name", false, 80, "Part of the supplier's name"),
            integer("limit", false, 1, 100, "Maximum rows, 1 to 100")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_MANAGER)),
        GET_SUPPLIER_RECORD, new ToolSchema(GET_SUPPLIER_RECORD, List.of(
            integer("supplierId", true, 1, MAX_ID, "Supplier id")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_MANAGER)),
        LIST_INVOICES, new ToolSchema(LIST_INVOICES, List.of(
            date("date", true, "Day, YYYY-MM-DD"),
            oneOf("status", false, STATUSES, "Filter by status")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_MANAGER)),
        UPDATE_INVOICE, new ToolSchema(UPDATE_INVOICE, List.of(
            integer("invoiceId", true, 1, MAX_ID, "Invoice id"),
            oneOf("status", true, STATUSES, "New status"),
            string("notes", false, 300, "New notes"),
            integer("amountMad", false, 0, 100_000_000L, "Invoice amount in MAD, required to pay")),
            Set.of(SCOPE_DEPARTMENT)),
        CANCEL_INVOICES, new ToolSchema(CANCEL_INVOICES, List.of(
            date("date", true, "Day whose pending invoices are cancelled")),
            Set.of(SCOPE_DEPARTMENT)),
        GET_EMPLOYEE_DIRECTORY, new ToolSchema(GET_EMPLOYEE_DIRECTORY, List.of(
            code("department", false, "Department code such as ACCT")),
            Set.of()),
        GET_EMPLOYEE_SALARIES, new ToolSchema(GET_EMPLOYEE_SALARIES, List.of(
            code("department", false, "Department code such as ACCT")),
            Set.of()),
        EXPORT_REPORT, new ToolSchema(EXPORT_REPORT, List.of(
            oneOf("reportType", true, List.of("SUPPLIERS", "INVOICES"), "What to export")),
            Set.of(SCOPE_DEPARTMENT)),
        GET_DOCUMENT, new ToolSchema(GET_DOCUMENT, List.of(
            integer("documentId", true, 1, MAX_ID, "Document id")),
            Set.of()),
        RUN_QUERY, new ToolSchema(RUN_QUERY, List.of(
            string("sql", true, 2000, "One read-only SELECT on the allowed finance tables"),
            integer("maxRows", false, 1, 500, "Maximum rows, 1 to 500")),
            Set.of()));

    private ToolCatalog() {
    }

    public static Optional<ToolSchema> find(String tool) {
        return Optional.ofNullable(SCHEMAS.get(tool));
    }

    public static Set<String> toolNames() {
        return SCHEMAS.keySet();
    }
}
