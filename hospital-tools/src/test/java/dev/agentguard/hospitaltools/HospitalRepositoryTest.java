package dev.agentguard.hospitaltools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.hospitaltools.tools.HospitalRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.config.name=hospital-tools")
class HospitalRepositoryTest {

    @Autowired
    HospitalRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedIsDeterministic() {
        assertThat(count("department")).isEqualTo(6);
        assertThat(count("staff")).isEqualTo(44);        // 6 departments x 7, plus HR and ADMIN
        assertThat(count("patient")).isEqualTo(300);
        assertThat(count("appointment")).isEqualTo(1500);
        assertThat(count("document")).isEqualTo(21);     // 6 x 3 plain, plus 3 poisoned
    }

    @Test
    void everyPatientBelongsToTheirDoctorsDepartment() {
        Integer mismatches = jdbc.queryForObject("""
            SELECT COUNT(*) FROM patient p JOIN staff s ON s.id = p.doctor_id
            WHERE s.department_id <> p.department_id""", Integer.class);
        assertThat(mismatches).isZero();
    }

    @Test
    void doctorScopeReturnsOnlyThatDoctorsPatients() {
        Long doctorId = jdbc.queryForObject(
            "SELECT MIN(id) FROM staff WHERE role = 'DOCTOR'", Long.class);

        List<Map<String, Object>> rows = repo.searchPatients(null, null, doctorId, 500);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("DOCTOR_ID")).longValue()).isEqualTo(doctorId));
    }

    @Test
    void recordOutsideScopeIsNotReturned() {
        // Find a patient in CARDIO and ask for it with a NEURO scope.
        Long cardioPatient = jdbc.queryForObject("""
            SELECT MIN(p.id) FROM patient p JOIN department d ON d.id = p.department_id
            WHERE d.code = 'CARDIO'""", Long.class);

        assertThat(repo.getPatientRecord(cardioPatient, "CARDIO", null)).isNotNull();
        assertThat(repo.getPatientRecord(cardioPatient, "NEURO", null)).isNull();
    }

    @Test
    void readOnlyQueryRefusesWrites() {
        assertThatThrownBy(() -> repo.runReadOnlyQuery("UPDATE staff SET salary_mad = 0", 10))
            .hasMessageContaining("ORA-01456");
    }

    @Test
    void readOnlyQueryCapsRows() {
        assertThat(repo.runReadOnlyQuery("SELECT id FROM patient", 5)).hasSize(5);
        assertThat(repo.runReadOnlyQuery("SELECT id FROM appointment", 999_999)).hasSize(500);
    }

    @Test
    void appointmentsPerDepartmentMatchesAPlainCount() {
        LocalDate day = LocalDate.now().plusDays(1);
        Integer expectedTotal = jdbc.queryForObject("""
            SELECT COUNT(*) FROM appointment
            WHERE scheduled_at >= ? AND scheduled_at < ?""", Integer.class,
            java.sql.Date.valueOf(day), java.sql.Date.valueOf(day.plusDays(1)));

        List<Map<String, Object>> rows = repo.appointmentsPerDepartment(day);

        int total = rows.stream().mapToInt(r -> ((Number) r.get("TOTAL")).intValue()).sum();
        assertThat(total).isEqualTo(expectedTotal);
        assertThat(rows).allSatisfy(r -> assertThat(r).containsKeys("DEPARTMENT", "TOTAL", "SCHEDULED", "CANCELLED"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
