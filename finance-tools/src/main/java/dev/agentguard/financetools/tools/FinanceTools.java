package dev.agentguard.financetools.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tools an agent can call. Spring AI scans the @McpTool methods and
 * publishes them on /mcp with a JSON schema built from the parameters.
 *
 * The scope* parameters are meant to be set by AgentGuard, never by the
 * agent. In phase 2 this server will accept calls only with the gateway's
 * service token, and the gateway strips any scope value an agent sends.
 */
@Component
public class FinanceTools {

    private final FinanceRepository repo;

    public FinanceTools(FinanceRepository repo) {
        this.repo = repo;
    }

    @McpTool(name = "search_suppliers", description = "Search suppliers by (part of) their name. Returns id, name, department and manager id.")
    public List<Map<String, Object>> searchSuppliers(
            @McpToolParam(description = "Part of the supplier's name, case-insensitive", required = false) String name,
            @McpToolParam(description = "Maximum rows, default 20, max 500", required = false) Integer limit,
            @McpToolParam(description = "Set by the gateway: department code the caller may see", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway: manager id the caller may see", required = false) Long scopeManagerId) {
        return repo.searchSuppliers(name, scopeDepartment, scopeManagerId, limit == null ? 20 : limit);
    }

    @McpTool(name = "get_supplier_record", description = "Full record of one supplier: identity, contact, iban, manager.")
    public Map<String, Object> getSupplierRecord(
            @McpToolParam(description = "Supplier id") long supplierId,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway", required = false) Long scopeManagerId) {
        Map<String, Object> record = repo.getSupplierRecord(supplierId, scopeDepartment, scopeManagerId);
        return record == null ? Map.of("found", false) : record;
    }

    @McpTool(name = "list_invoices", description = "Invoices on one day (YYYY-MM-DD), optionally filtered by status PENDING, PAID or CANCELLED.")
    public List<Map<String, Object>> listInvoices(
            @McpToolParam(description = "Day, format YYYY-MM-DD") String date,
            @McpToolParam(description = "PENDING, PAID or CANCELLED", required = false) String status,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway", required = false) Long scopeManagerId) {
        return repo.listInvoices(LocalDate.parse(date), status, scopeDepartment, scopeManagerId);
    }

    @McpTool(name = "update_invoice", description = "Change the status and optionally the notes of one invoice. Returns the number of rows changed.")
    public Map<String, Object> updateInvoice(
            @McpToolParam(description = "Invoice id") long invoiceId,
            @McpToolParam(description = "New status: PENDING, PAID or CANCELLED") String status,
            @McpToolParam(description = "New notes", required = false) String notes,
            @McpToolParam(description = "Amount in MAD, required when the status is PAID and equal to the invoice amount", required = false) Long amountMad,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return Map.of("updated", repo.updateInvoice(invoiceId, status, notes, amountMad, scopeDepartment));
    }

    @McpTool(name = "cancel_invoices", description = "Cancel every pending invoice on one day. Bulk write: returns how many were cancelled.")
    public Map<String, Object> cancelInvoices(
            @McpToolParam(description = "Day, format YYYY-MM-DD") String date,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return Map.of("cancelled", repo.cancelInvoicesOn(LocalDate.parse(date), scopeDepartment));
    }

    @McpTool(name = "get_employee_directory", description = "Names, roles and emails of employee, optionally for one department code.")
    public List<Map<String, Object>> getEmployeeDirectory(
            @McpToolParam(description = "Department code such as ACCT", required = false) String department) {
        return repo.employeeDirectory(department);
    }

    @McpTool(name = "get_employee_salaries", description = "Employee salaries in MAD, optionally for one department code. Sensitive.")
    public List<Map<String, Object>> getEmployeeSalaries(
            @McpToolParam(description = "Department code such as ACCT", required = false) String department) {
        return repo.employeeSalaries(department);
    }

    @McpTool(name = "export_report", description = "Bulk export of SUPPLIERS or INVOICES as rows. Large result.")
    public List<Map<String, Object>> exportReport(
            @McpToolParam(description = "SUPPLIERS or INVOICES") String reportType,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return switch (reportType) {
            case "SUPPLIERS" -> repo.exportSuppliers(scopeDepartment);
            case "INVOICES" -> repo.exportInvoices(scopeDepartment);
            default -> throw new IllegalArgumentException("reportType must be SUPPLIERS or INVOICES");
        };
    }

    @McpTool(name = "get_document", description = "Title and full text of one internal document.")
    public Map<String, Object> getDocument(@McpToolParam(description = "Document id") long documentId) {
        Map<String, Object> doc = repo.getDocument(documentId);
        return doc == null ? Map.of("found", false) : doc;
    }

    @McpTool(name = "run_query", description = "Run a read-only SQL SELECT against the finance schema. The gateway validates the SQL first.")
    public List<Map<String, Object>> runQuery(
            @McpToolParam(description = "A single SELECT statement") String sql,
            @McpToolParam(description = "Maximum rows, default 50, max 500", required = false) Integer maxRows) {
        return repo.runReadOnlyQuery(sql, maxRows == null ? 50 : maxRows);
    }
}
