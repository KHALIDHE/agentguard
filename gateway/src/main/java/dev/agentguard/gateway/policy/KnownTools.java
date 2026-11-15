package dev.agentguard.gateway.policy;

import java.util.Set;

/**
 * The tools the gateway knows about. The policy file may only name these, and
 * the gateway only publishes upstream tools that appear here: a new tool on
 * the upstream server stays invisible to agents until someone adds it here and
 * writes a policy for it.
 */
public final class KnownTools {

    public static final String SEARCH_SUPPLIERS = "search_suppliers";
    public static final String GET_SUPPLIER_RECORD = "get_supplier_record";
    public static final String LIST_INVOICES = "list_invoices";
    public static final String UPDATE_INVOICE = "update_invoice";
    public static final String CANCEL_INVOICES = "cancel_invoices";
    public static final String GET_EMPLOYEE_DIRECTORY = "get_employee_directory";
    public static final String GET_EMPLOYEE_SALARIES = "get_employee_salaries";
    public static final String EXPORT_REPORT = "export_report";
    public static final String GET_DOCUMENT = "get_document";
    public static final String RUN_QUERY = "run_query";

    public static final Set<String> ALL = Set.of(
        SEARCH_SUPPLIERS, GET_SUPPLIER_RECORD, LIST_INVOICES, UPDATE_INVOICE, CANCEL_INVOICES,
        GET_EMPLOYEE_DIRECTORY, GET_EMPLOYEE_SALARIES, EXPORT_REPORT, GET_DOCUMENT, RUN_QUERY);

    /** Mask profiles a policy may reference. Their field lists live in the masking step. */
    public static final Set<String> MASK_PROFILES = Set.of("accountant-supplier", "clerk-supplier");

    private KnownTools() {
    }
}
