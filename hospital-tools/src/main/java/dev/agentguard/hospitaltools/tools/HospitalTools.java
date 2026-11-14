package dev.agentguard.hospitaltools.tools;

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
public class HospitalTools {

    private final HospitalRepository repo;

    public HospitalTools(HospitalRepository repo) {
        this.repo = repo;
    }

    @McpTool(name = "search_patients", description = "Search patients by (part of) their name. Returns id, name, department and doctor id.")
    public List<Map<String, Object>> searchPatients(
            @McpToolParam(description = "Part of the patient's name, case-insensitive", required = false) String name,
            @McpToolParam(description = "Maximum rows, default 20, max 500", required = false) Integer limit,
            @McpToolParam(description = "Set by the gateway: department code the caller may see", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway: doctor id the caller may see", required = false) Long scopeDoctorId) {
        return repo.searchPatients(name, scopeDepartment, scopeDoctorId, limit == null ? 20 : limit);
    }

    @McpTool(name = "get_patient_record", description = "Full record of one patient: identity, contact, diagnosis, doctor.")
    public Map<String, Object> getPatientRecord(
            @McpToolParam(description = "Patient id") long patientId,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway", required = false) Long scopeDoctorId) {
        Map<String, Object> record = repo.getPatientRecord(patientId, scopeDepartment, scopeDoctorId);
        return record == null ? Map.of("found", false) : record;
    }

    @McpTool(name = "list_appointments", description = "Appointments on one day (YYYY-MM-DD), optionally filtered by status SCHEDULED, DONE or CANCELLED.")
    public List<Map<String, Object>> listAppointments(
            @McpToolParam(description = "Day, format YYYY-MM-DD") String date,
            @McpToolParam(description = "SCHEDULED, DONE or CANCELLED", required = false) String status,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment,
            @McpToolParam(description = "Set by the gateway", required = false) Long scopeDoctorId) {
        return repo.listAppointments(LocalDate.parse(date), status, scopeDepartment, scopeDoctorId);
    }

    @McpTool(name = "update_appointment", description = "Change the status and optionally the notes of one appointment. Returns the number of rows changed.")
    public Map<String, Object> updateAppointment(
            @McpToolParam(description = "Appointment id") long appointmentId,
            @McpToolParam(description = "New status: SCHEDULED, DONE or CANCELLED") String status,
            @McpToolParam(description = "New notes", required = false) String notes,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return Map.of("updated", repo.updateAppointment(appointmentId, status, notes, scopeDepartment));
    }

    @McpTool(name = "cancel_appointments", description = "Cancel every scheduled appointment on one day. Bulk write: returns how many were cancelled.")
    public Map<String, Object> cancelAppointments(
            @McpToolParam(description = "Day, format YYYY-MM-DD") String date,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return Map.of("cancelled", repo.cancelAppointmentsOn(LocalDate.parse(date), scopeDepartment));
    }

    @McpTool(name = "get_staff_directory", description = "Names, roles and emails of staff, optionally for one department code.")
    public List<Map<String, Object>> getStaffDirectory(
            @McpToolParam(description = "Department code such as CARDIO", required = false) String department) {
        return repo.staffDirectory(department);
    }

    @McpTool(name = "get_staff_salaries", description = "Staff salaries in MAD, optionally for one department code. Sensitive.")
    public List<Map<String, Object>> getStaffSalaries(
            @McpToolParam(description = "Department code such as CARDIO", required = false) String department) {
        return repo.staffSalaries(department);
    }

    @McpTool(name = "export_report", description = "Bulk export of PATIENTS or APPOINTMENTS as rows. Large result.")
    public List<Map<String, Object>> exportReport(
            @McpToolParam(description = "PATIENTS or APPOINTMENTS") String reportType,
            @McpToolParam(description = "Set by the gateway", required = false) String scopeDepartment) {
        return switch (reportType) {
            case "PATIENTS" -> repo.exportPatients(scopeDepartment);
            case "APPOINTMENTS" -> repo.exportAppointments(scopeDepartment);
            default -> throw new IllegalArgumentException("reportType must be PATIENTS or APPOINTMENTS");
        };
    }

    @McpTool(name = "get_document", description = "Title and full text of one internal document.")
    public Map<String, Object> getDocument(@McpToolParam(description = "Document id") long documentId) {
        Map<String, Object> doc = repo.getDocument(documentId);
        return doc == null ? Map.of("found", false) : doc;
    }

    @McpTool(name = "run_query", description = "Run a read-only SQL SELECT against the hospital schema. The gateway validates the SQL first.")
    public List<Map<String, Object>> runQuery(
            @McpToolParam(description = "A single SELECT statement") String sql,
            @McpToolParam(description = "Maximum rows, default 50, max 500", required = false) Integer maxRows) {
        return repo.runReadOnlyQuery(sql, maxRows == null ? 50 : maxRows);
    }
}
