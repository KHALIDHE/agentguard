package dev.agentguard.hospitaltools.tools;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * All SQL for the hospital tools lives here, so the tool class stays about
 * MCP and this class stays about Oracle.
 *
 * Scope arguments (department code, doctor id) are optional filters. When
 * the gateway sets them they become WHERE conditions, so a caller can only
 * ever receive rows inside its scope. This is row-level security done in
 * SQL, not by asking the model to behave.
 */
@Repository
public class HospitalRepository {

    static final int HARD_ROW_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public HospitalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> searchPatients(String name, String scopeDepartment, Long scopeDoctorId, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.full_name, d.code AS department, p.doctor_id, p.admitted_on
            FROM patient p JOIN department d ON d.id = p.department_id
            WHERE LOWER(p.full_name) LIKE ?""");
        List<Object> params = new ArrayList<>();
        params.add("%" + (name == null ? "" : name.toLowerCase()) + "%");
        appendScope(sql, params, "p", scopeDepartment, scopeDoctorId);
        sql.append(" ORDER BY p.id FETCH FIRST ? ROWS ONLY");
        params.add(clamp(limit));
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> getPatientRecord(long patientId, String scopeDepartment, Long scopeDoctorId) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.full_name, p.national_id, p.birth_date, p.phone, p.address, p.diagnosis,
                   p.admitted_on, d.code AS department, p.doctor_id, s.full_name AS doctor_name
            FROM patient p
            JOIN department d ON d.id = p.department_id
            JOIN staff s ON s.id = p.doctor_id
            WHERE p.id = ?""");
        List<Object> params = new ArrayList<>();
        params.add(patientId);
        appendScope(sql, params, "p", scopeDepartment, scopeDoctorId);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listAppointments(LocalDate day, String status, String scopeDepartment, Long scopeDoctorId) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.id, a.scheduled_at, a.status, a.notes, a.patient_id, p.full_name AS patient_name,
                   a.doctor_id, d.code AS department
            FROM appointment a
            JOIN patient p ON p.id = a.patient_id
            JOIN department d ON d.id = a.department_id
            WHERE a.scheduled_at >= ? AND a.scheduled_at < ?""");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(day));
        params.add(Date.valueOf(day.plusDays(1)));
        if (status != null) {
            sql.append(" AND a.status = ?");
            params.add(status);
        }
        appendScope(sql, params, "a", scopeDepartment, scopeDoctorId);
        sql.append(" ORDER BY a.scheduled_at FETCH FIRST ? ROWS ONLY");
        params.add(HARD_ROW_LIMIT);
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional
    public int updateAppointment(long appointmentId, String status, String notes, String scopeDepartment) {
        StringBuilder sql = new StringBuilder("UPDATE appointment a SET a.status = ?, a.notes = COALESCE(?, a.notes) WHERE a.id = ?");
        List<Object> params = new ArrayList<>();
        params.add(status);
        params.add(notes);
        params.add(appointmentId);
        appendScope(sql, params, "a", scopeDepartment, null);
        return jdbc.update(sql.toString(), params.toArray());
    }

    @Transactional
    public int cancelAppointmentsOn(LocalDate day, String scopeDepartment) {
        StringBuilder sql = new StringBuilder("""
            UPDATE appointment a SET a.status = 'CANCELLED'
            WHERE a.status = 'SCHEDULED' AND a.scheduled_at >= ? AND a.scheduled_at < ?""");
        List<Object> params = new ArrayList<>();
        params.add(Date.valueOf(day));
        params.add(Date.valueOf(day.plusDays(1)));
        appendScope(sql, params, "a", scopeDepartment, null);
        return jdbc.update(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> staffDirectory(String department) {
        return jdbc.queryForList("""
            SELECT s.id, s.full_name, s.role, s.email, d.code AS department
            FROM staff s JOIN department d ON d.id = s.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY d.code, s.role, s.full_name""", department, department);
    }

    public List<Map<String, Object>> staffSalaries(String department) {
        return jdbc.queryForList("""
            SELECT s.id, s.full_name, s.role, s.salary_mad, s.hired_on, d.code AS department
            FROM staff s JOIN department d ON d.id = s.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY s.salary_mad DESC""", department, department);
    }

    public List<Map<String, Object>> exportPatients(String scopeDepartment) {
        return jdbc.queryForList("""
            SELECT p.id, p.full_name, p.national_id, p.phone, p.diagnosis, d.code AS department
            FROM patient p JOIN department d ON d.id = p.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY p.id""", scopeDepartment, scopeDepartment);
    }

    public List<Map<String, Object>> exportAppointments(String scopeDepartment) {
        return jdbc.queryForList("""
            SELECT a.id, a.scheduled_at, a.status, a.patient_id, a.doctor_id, d.code AS department
            FROM appointment a JOIN department d ON d.id = a.department_id
            WHERE (? IS NULL OR d.code = ?)
            ORDER BY a.scheduled_at""", scopeDepartment, scopeDepartment);
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

    /** Appointment counts per department for one day, computed in SQL rather than in Java. */
    public List<Map<String, Object>> appointmentsPerDepartment(LocalDate day) {
        return jdbc.queryForList("""
            SELECT d.code AS department,
                   COUNT(*) AS total,
                   SUM(CASE WHEN a.status = 'SCHEDULED' THEN 1 ELSE 0 END) AS scheduled,
                   SUM(CASE WHEN a.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled
            FROM appointment a JOIN department d ON d.id = a.department_id
            WHERE a.scheduled_at >= ? AND a.scheduled_at < ?
            GROUP BY d.code
            ORDER BY d.code""", Date.valueOf(day), Date.valueOf(day.plusDays(1)));
    }

    private static void appendScope(StringBuilder sql, List<Object> params, String alias,
                                    String scopeDepartment, Long scopeDoctorId) {
        if (scopeDepartment != null) {
            sql.append(" AND ").append(alias).append(".department_id = (SELECT id FROM department WHERE code = ?)");
            params.add(scopeDepartment);
        }
        if (scopeDoctorId != null) {
            sql.append(" AND ").append(alias).append(".doctor_id = ?");
            params.add(scopeDoctorId);
        }
    }

    private static int clamp(int limit) {
        return limit <= 0 ? 20 : Math.min(limit, HARD_ROW_LIMIT);
    }
}
