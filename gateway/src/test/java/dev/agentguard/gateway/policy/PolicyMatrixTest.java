package dev.agentguard.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every role against every tool, with the real policies.yml. If someone edits
 * the policy, this table shows exactly which permissions changed.
 */
class PolicyMatrixTest {

    private static final PolicyEngine engine = new PolicyEngine();

    @BeforeAll
    static void loadRealPolicy() throws Exception {
        try (InputStream in = PolicyMatrixTest.class.getResourceAsStream("/policies.yml")) {
            engine.replaceRules(PolicyParser.parse(in, KnownTools.ALL, KnownTools.MASK_PROFILES));
        }
    }

    private static Decision decide(Role role, String tool, Map<String, Object> args) {
        UserIdentity user = new UserIdentity(1, "someone", role,
            role == Role.HR || role == Role.ADMIN ? null : "CARDIO", null);
        return engine.evaluate(ToolCallContext.of(user, tool, args));
    }

    @ParameterizedTest(name = "{0} calling {1} -> {2}")
    @CsvSource({
        // role,        tool,                 expected
        "DOCTOR,       search_patients,       ALLOW",
        "DOCTOR,       get_patient_record,    ALLOW",
        "DOCTOR,       list_appointments,     ALLOW",
        "DOCTOR,       update_appointment,    ALLOW",
        "DOCTOR,       cancel_appointments,   DENY",
        "DOCTOR,       get_staff_directory,   ALLOW",
        "DOCTOR,       get_staff_salaries,    DENY",
        "DOCTOR,       export_report,         DENY",
        "DOCTOR,       get_document,          ALLOW",
        "DOCTOR,       run_query,             DENY",

        "NURSE,        search_patients,       MASK",
        "NURSE,        get_patient_record,    MASK",
        "NURSE,        list_appointments,     ALLOW",
        "NURSE,        update_appointment,    DENY",
        "NURSE,        cancel_appointments,   DENY",
        "NURSE,        get_staff_directory,   ALLOW",
        "NURSE,        get_staff_salaries,    DENY",
        "NURSE,        export_report,         DENY",
        "NURSE,        get_document,          ALLOW",
        "NURSE,        run_query,             DENY",

        "RECEPTIONIST, search_patients,       MASK",
        "RECEPTIONIST, get_patient_record,    DENY",
        "RECEPTIONIST, list_appointments,     ALLOW",
        "RECEPTIONIST, update_appointment,    ALLOW",
        "RECEPTIONIST, cancel_appointments,   REQUIRE_APPROVAL",
        "RECEPTIONIST, get_staff_directory,   ALLOW",
        "RECEPTIONIST, get_staff_salaries,    DENY",
        "RECEPTIONIST, export_report,         DENY",
        "RECEPTIONIST, get_document,          ALLOW",
        "RECEPTIONIST, run_query,             DENY",

        "HR,           search_patients,       DENY",
        "HR,           get_patient_record,    DENY",
        "HR,           list_appointments,     DENY",
        "HR,           update_appointment,    DENY",
        "HR,           cancel_appointments,   DENY",
        "HR,           get_staff_directory,   ALLOW",
        "HR,           get_staff_salaries,    ALLOW",
        "HR,           export_report,         DENY",
        "HR,           get_document,          ALLOW",
        "HR,           run_query,             DENY",

        "ADMIN,        search_patients,       DENY",
        "ADMIN,        get_patient_record,    DENY",
        "ADMIN,        list_appointments,     DENY",
        "ADMIN,        update_appointment,    DENY",
        "ADMIN,        cancel_appointments,   REQUIRE_APPROVAL",
        "ADMIN,        get_staff_directory,   ALLOW",
        "ADMIN,        get_staff_salaries,    DENY",
        "ADMIN,        export_report,         REQUIRE_APPROVAL",
        "ADMIN,        get_document,          ALLOW",
        "ADMIN,        run_query,             ALLOW",
    })
    void everyRoleAgainstEveryTool(Role role, String tool, DecisionType expected) {
        Map<String, Object> args = new HashMap<>();
        if (tool.equals(KnownTools.EXPORT_REPORT)) {
            args.put("reportType", "PATIENTS");
        }
        assertThat(decide(role, tool, args).type()).isEqualTo(expected);
    }

    @Test
    void doctorsMayExportAppointmentsButOnlyWithApproval() {
        Decision appointments = decide(Role.DOCTOR, KnownTools.EXPORT_REPORT, Map.of("reportType", "APPOINTMENTS"));
        Decision patients = decide(Role.DOCTOR, KnownTools.EXPORT_REPORT, Map.of("reportType", "PATIENTS"));

        assertThat(appointments.type()).isEqualTo(DecisionType.REQUIRE_APPROVAL);
        assertThat(appointments.ruleId()).isEqualTo("export-appointments-doctor");
        assertThat(patients.ruleId()).isEqualTo(Decision.DEFAULT_DENY_RULE);
    }

    @Test
    void everyDecisionNamesItsRuleAndReason() {
        Decision denied = decide(Role.NURSE, KnownTools.GET_STAFF_SALARIES, Map.of());

        assertThat(denied.ruleId()).isEqualTo("salaries-everyone-else");
        assertThat(denied.reason()).isEqualTo("Salary data is restricted to HR.");
    }

    @Test
    void scopeAndMaskTravelWithTheDecision() {
        Decision nurse = decide(Role.NURSE, KnownTools.GET_PATIENT_RECORD, Map.of("patientId", 1));

        assertThat(nurse.scope()).isEqualTo(RowScope.SAME_DEPARTMENT);
        assertThat(nurse.maskProfile()).isEqualTo("nurse-patient");
    }
}
