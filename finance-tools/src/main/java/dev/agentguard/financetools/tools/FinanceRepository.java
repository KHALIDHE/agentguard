package dev.agentguard.financetools.tools;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * All SQL for the finance tools lives here, so the tool class stays about
 * MCP and this class stays about Oracle.
 *
 * Scope arguments (department code, manager id) are optional filters. When
 * the gateway sets them they become WHERE conditions, so a caller can only
 * ever receive rows inside its scope. This is row-level security done in
 * SQL, not by asking the model to behave.
 */
@Repository
public class FinanceRepository {

    static final int HARD_ROW_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public FinanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> searchSuppliers(String name, String scopeDepartment, Long scopeManagerId, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.full_name, d.code AS department, p.manager_id, p.onboarded_on
            FROM supplier p JOIN department d ON d.id = p.department_id
            WHERE LOWER(p.full_name) LIKE ?""");
        List<Object> params = new ArrayList<>();
        params.add("%" + (name == null ? "" : name.toLowerCase()) + "%");
        appendScope(sql, params, "p", scopeDepartment, scopeManagerId);
        sql.append(" ORDER BY p.id FETCH FIRST ? ROWS ONLY");
        params.add(clamp(limit));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> getSupplierRecord(long supplierId, String scopeDepartment, Long scopeManagerId) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.full_name, p.tax_id, p.founded_on, p.phone, p.address, p.iban,
                   p.onboarded_on, d.code AS department, p.manager_id, s.full_name AS manager_name
            FROM supplier p
            JOIN department d ON d.id = p.department_id
            JOIN employee s ON s.id = p.manager_id
            WHERE p.id = ?""");
        List<Object> params = new ArrayList<>();
        params.add(supplierId);
        appendScope(sql, params, "p", scopeDepartment, scopeManagerId);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listInvoices(LocalDate day, String status, String scopeDepartment, Long scopeManagerId) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.id, a.due_at, a.amount_mad, a.status, a.notes, a.supplier_id, p.full_name AS supplier_name,
                   a.manager_id, d.code AS department
            FROM invoice a
            JOIN supplier p ON p.id = a.supplier_id
            JOIN department d ON d.id = a.department_id
            WHERE a.due_at >= ? AND a.due_at < ?""");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(day));
        params.add(Date.valueOf(day.plusDays(1)));
        if (status != null) {
            sql.append(" AND a.status = ?");
            params.add(status);
        }
        appendScope(sql, params, "a", scopeDepartment, scopeManagerId);
        sql.append(" ORDER BY a.due_at FETCH FIRST ? ROWS ONLY");
        params.add(HARD_ROW_LIMIT);
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional
    public int updateInvoice(long invoiceId, String status, String notes, Long amountMad, String scopeDepartment) {
        if ("PAID".equals(status)) {
            // The amount the gateway checked against the policy must be the real amount of the invoice.
            List<Map<String, Object>> found = jdbc.queryForList("SELECT amount_mad FROM invoice WHERE id = ?", invoiceId);
            if (!found.isEmpty()) {
                long real = ((Number) found.get(0).get("AMOUNT_MAD")).longValue();
                if (amountMad == null || amountMad != real) {
                    throw new IllegalArgumentException("amountMad must equal the invoice amount (" + real + " MAD)");
                }
            }
        }
        StringBuilder sql = new StringBuilder("UPDATE invoice a SET a.status = ?, a.notes = COALESCE(?, a.notes) WHERE a.id = ?");
        List<Object> params = new ArrayList<>();
        params.add(status);
        params.add(notes);
        params.add(invoiceId);
        appendScope(sql, params, "a", scopeDepartment, null);
        return jdbc.update(sql.toString(), params.toArray());
    }

    @Transactional
    public int cancelInvoicesOn(LocalDate day, String scopeDepartment) {
        StringBuilder sql = new StringBuilder("""
            UPDATE invoice a SET a.status = 'CANCELLED'
            WHERE a.status = 'PENDING' AND a.due_at >= ? AND a.due_at < ?""");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(day));
        params.add(Date.valueOf(day.plusDays(1)));
        appendScope(sql, params, "a", scopeDepartment, null);
        return jdbc.update(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> employeeDirectory(String department) {
        return jdbc.queryForList("""
            SELECT s.id, s.full_name, s.role, s.email, d.code AS department
            FROM employee s JOIN department d ON d.id = s.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY d.code, s.role, s.full_name""", department, department);
    }

    public List<Map<String, Object>> employeeSalaries(String department) {
        return jdbc.queryForList("""
            SELECT s.id, s.full_name, s.role, s.salary_mad, s.hired_on, d.code AS department
            FROM employee s JOIN department d ON d.id = s.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY s.salary_mad DESC""", department, department);
    }

    public List<Map<String, Object>> exportSuppliers(String scopeDepartment) {
        return jdbc.queryForList("""
            SELECT p.id, p.full_name, p.tax_id, p.phone, p.iban, d.code AS department
            FROM supplier p JOIN department d ON d.id = p.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY p.id""", scopeDepartment, scopeDepartment);
    }

    public List<Map<String, Object>> exportInvoices(String scopeDepartment) {
        return jdbc.queryForList("""
            SELECT a.id, a.due_at, a.amount_mad, a.status, a.supplier_id, a.manager_id, d.code AS department
            FROM invoice a JOIN department d ON d.id = a.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY a.due_at""", scopeDepartment, scopeDepartment);
    }

    public Map<String, Object> getDocument(long documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT doc.id, doc.title, doc.body, doc.uploaded_on, d.code AS department
            FROM document doc JOIN department d ON d.id = doc.department_id
            WHERE doc.id = ?""", documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Runs a caller-provided SELECT inside an Oracle read-only transaction.
     *
     * Spring's readOnly flag alone is only a hint: the Oracle JDBC driver
     * ignores Connection.setReadOnly. So the first statement of the
     * transaction is SET TRANSACTION READ ONLY, and Oracle itself then
     * refuses any write with ORA-01456. This is a second wall behind the
     * gateway's SQL guard.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runReadOnlyQuery(String sql, int maxRows) {
        jdbc.execute("SET TRANSACTION READ ONLY");
        jdbc.setMaxRows(clamp(maxRows));
        try {
            return jdbc.queryForList(sql);
        } finally {
            jdbc.setMaxRows(-1);
        }
    }

    /** Invoice counts per department for one day, computed in SQL rather than in Java. */
    public List<Map<String, Object>> invoicesPerDepartment(LocalDate day) {
        return jdbc.queryForList("""
            SELECT d.code AS department,
                   COUNT(*) AS total,
                   SUM(CASE WHEN a.status = 'PENDING' THEN 1 ELSE 0 END) AS pending,
                   SUM(CASE WHEN a.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled
            FROM invoice a JOIN department d ON d.id = a.department_id
            WHERE a.due_at >= ? AND a.due_at < ?
            GROUP BY d.code
            ORDER BY d.code""", Date.valueOf(day), Date.valueOf(day.plusDays(1)));
    }

    private static void appendScope(StringBuilder sql, List<Object> params, String alias,
                                    String scopeDepartment, Long scopeManagerId) {
        if (scopeDepartment != null) {
            sql.append(" AND ").append(alias).append(".department_id = (SELECT id FROM department WHERE code = ?)");
            params.add(scopeDepartment);
        }
        if (scopeManagerId != null) {
            sql.append(" AND ").append(alias).append(".manager_id = ?");
            params.add(scopeManagerId);
        }
    }

    private static int clamp(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, HARD_ROW_LIMIT);
    }
}
