package dev.agentguard.gateway.tools;

import static dev.agentguard.gateway.policy.KnownTools.CANCEL_APPOINTMENTS;
import static dev.agentguard.gateway.policy.KnownTools.EXPORT_REPORT;
import static dev.agentguard.gateway.policy.KnownTools.GET_DOCUMENT;
import static dev.agentguard.gateway.policy.KnownTools.GET_PATIENT_RECORD;
import static dev.agentguard.gateway.policy.KnownTools.GET_STAFF_DIRECTORY;
import static dev.agentguard.gateway.policy.KnownTools.GET_STAFF_SALARIES;
import static dev.agentguard.gateway.policy.KnownTools.LIST_APPOINTMENTS;
import static dev.agentguard.gateway.policy.KnownTools.RUN_QUERY;
import static dev.agentguard.gateway.policy.KnownTools.SEARCH_PATIENTS;
import static dev.agentguard.gateway.policy.KnownTools.UPDATE_APPOINTMENT;
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
    public static final String SCOPE_DOCTOR = "scopeDoctorId";

    private static final List<String> STATUSES = List.of("SCHEDULED", "DONE", "CANCELLED");
    private static final long MAX_ID = 10_000_000L;

    private static final Map<String, ToolSchema> SCHEMAS = Map.of(
        SEARCH_PATIENTS, new ToolSchema(SEARCH_PATIENTS, List.of(
            string("name", false, 80, "Part of the patient's name"),
            integer("limit", false, 1, 100, "Maximum rows, 1 to 100")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_DOCTOR)),
        GET_PATIENT_RECORD, new ToolSchema(GET_PATIENT_RECORD, List.of(
            integer("patientId", true, 1, MAX_ID, "Patient id")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_DOCTOR)),
        LIST_APPOINTMENTS, new ToolSchema(LIST_APPOINTMENTS, List.of(
            date("date", true, "Day, YYYY-MM-DD"),
            oneOf("status", false, STATUSES, "Filter by status")),
            Set.of(SCOPE_DEPARTMENT, SCOPE_DOCTOR)),
        UPDATE_APPOINTMENT, new ToolSchema(UPDATE_APPOINTMENT, List.of(
            integer("appointmentId", true, 1, MAX_ID, "Appointment id"),
            oneOf("status", true, STATUSES, "New status"),
            string("notes", false, 300, "New notes")),
            Set.of(SCOPE_DEPARTMENT)),
        CANCEL_APPOINTMENTS, new ToolSchema(CANCEL_APPOINTMENTS, List.of(
            date("date", true, "Day whose scheduled appointments are cancelled")),
            Set.of(SCOPE_DEPARTMENT)),
        GET_STAFF_DIRECTORY, new ToolSchema(GET_STAFF_DIRECTORY, List.of(
            code("department", false, "Department code such as CARDIO")),
            Set.of()),
        GET_STAFF_SALARIES, new ToolSchema(GET_STAFF_SALARIES, List.of(
            code("department", false, "Department code such as CARDIO")),
            Set.of()),
        EXPORT_REPORT, new ToolSchema(EXPORT_REPORT, List.of(
            oneOf("reportType", true, List.of("PATIENTS", "APPOINTMENTS"), "What to export")),
            Set.of(SCOPE_DEPARTMENT)),
        GET_DOCUMENT, new ToolSchema(GET_DOCUMENT, List.of(
            integer("documentId", true, 1, MAX_ID, "Document id")),
            Set.of()),
        RUN_QUERY, new ToolSchema(RUN_QUERY, List.of(
            string("sql", true, 2000, "One read-only SELECT on the allowed hospital tables"),
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
