package dev.agentguard.gateway.policy;

import java.util.Set;

/**
 * The tools the gateway knows about. The policy file may only name these, and
 * the gateway only publishes upstream tools that appear here: a new tool on
 * the upstream server stays invisible to agents until someone adds it here and
 * writes a policy for it.
 */
public final class KnownTools {

    public static final String SEARCH_PATIENTS = "search_patients";
    public static final String GET_PATIENT_RECORD = "get_patient_record";
    public static final String LIST_APPOINTMENTS = "list_appointments";
    public static final String UPDATE_APPOINTMENT = "update_appointment";
    public static final String CANCEL_APPOINTMENTS = "cancel_appointments";
    public static final String GET_STAFF_DIRECTORY = "get_staff_directory";
    public static final String GET_STAFF_SALARIES = "get_staff_salaries";
    public static final String EXPORT_REPORT = "export_report";
    public static final String GET_DOCUMENT = "get_document";
    public static final String RUN_QUERY = "run_query";

    public static final Set<String> ALL = Set.of(
        SEARCH_PATIENTS, GET_PATIENT_RECORD, LIST_APPOINTMENTS, UPDATE_APPOINTMENT, CANCEL_APPOINTMENTS,
        GET_STAFF_DIRECTORY, GET_STAFF_SALARIES, EXPORT_REPORT, GET_DOCUMENT, RUN_QUERY);

    /** Mask profiles a policy may reference. Their field lists live in the masking step. */
    public static final Set<String> MASK_PROFILES = Set.of("nurse-patient", "reception-patient");

    private KnownTools() {
    }
}
