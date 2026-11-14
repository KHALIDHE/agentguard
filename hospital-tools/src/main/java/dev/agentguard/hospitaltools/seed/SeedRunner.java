package dev.agentguard.hospitaltools.seed;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills the hospital schema with synthetic data on first start.
 *
 * The data is generated from a fixed random seed, so every machine gets the
 * same 6 departments, 42 staff, 300 patients, 1500 appointments and 24
 * documents. That matters for the evaluation harness later: a scenario that
 * says "patient 42 belongs to CARDIO" must stay true everywhere.
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    static final long SEED = 42L;
    static final int PATIENTS = 300;
    static final int APPOINTMENTS = 1500;

    private final JdbcTemplate jdbc;

    public SeedRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM department", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Hospital data already present, seed skipped");
            return;
        }
        Random rnd = new Random(SEED);
        SyntheticData data = new SyntheticData(rnd);

        List<Long> departmentIds = insertDepartments();
        List<long[]> doctors = insertStaff(departmentIds, data);          // [staffId, departmentId]
        List<long[]> patients = insertPatients(departmentIds, doctors, data); // [patientId, departmentId, doctorId]
        insertAppointments(patients, data);
        insertDocuments(departmentIds);

        log.info("Seeded {} departments, {} doctors, {} patients, {} appointments",
            departmentIds.size(), doctors.size(), patients.size(), APPOINTMENTS);
    }

    private List<Long> insertDepartments() {
        List<Long> ids = new ArrayList<>();
        for (String[] d : SyntheticData.DEPARTMENTS) {
            jdbc.update("INSERT INTO department (code, name) VALUES (?, ?)", d[0], d[1]);
            ids.add(jdbc.queryForObject("SELECT id FROM department WHERE code = ?", Long.class, d[0]));
        }
        return ids;
    }

    private List<long[]> insertStaff(List<Long> departmentIds, SyntheticData data) {
        List<long[]> doctors = new ArrayList<>();
        int n = 0;
        for (long deptId : departmentIds) {
            // 3 doctors, 3 nurses and 1 receptionist per department
            for (int i = 0; i < 7; i++) {
                String role = i < 3 ? "DOCTOR" : i < 6 ? "NURSE" : "RECEPTIONIST";
                long id = insertOneStaff(deptId, role, data, ++n);
                if (role.equals("DOCTOR")) doctors.add(new long[] {id, deptId});
            }
        }
        // HR and ADMIN sit in the first department for simplicity
        insertOneStaff(departmentIds.get(0), "HR", data, ++n);
        insertOneStaff(departmentIds.get(0), "ADMIN", data, ++n);
        return doctors;
    }

    private long insertOneStaff(long deptId, String role, SyntheticData data, int n) {
        String name = data.personName();
        String email = name.toLowerCase().replace(' ', '.') + n + "@hospital.example";
        jdbc.update("INSERT INTO staff (department_id, full_name, role, email, salary_mad, hired_on) VALUES (?, ?, ?, ?, ?, ?)",
            deptId, name, role, email, data.salaryFor(role), Date.valueOf(data.pastDate(365 * 15)));
        return jdbc.queryForObject("SELECT id FROM staff WHERE email = ?", Long.class, email);
    }

    private List<long[]> insertPatients(List<Long> departmentIds, List<long[]> doctors, SyntheticData data) {
        List<long[]> patients = new ArrayList<>();
        for (int i = 0; i < PATIENTS; i++) {
            long[] doctor = doctors.get(data.rnd().nextInt(doctors.size()));
            long deptId = doctor[1];
            String nationalId = data.nationalId(i);
            jdbc.update("""
                INSERT INTO patient (department_id, doctor_id, full_name, national_id, birth_date,
                                     phone, address, diagnosis, admitted_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                deptId, doctor[0], data.personName(), nationalId, Date.valueOf(data.pastDate(365 * 80)),
                data.phone(), data.address(), data.diagnosisFor(departmentIds.indexOf(deptId)),
                Date.valueOf(data.pastDate(400)));
            long id = jdbc.queryForObject("SELECT id FROM patient WHERE national_id = ?", Long.class, nationalId);
            patients.add(new long[] {id, deptId, doctor[0]});
        }
        return patients;
    }

    private void insertAppointments(List<long[]> patients, SyntheticData data) {
        List<Object[]> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < APPOINTMENTS; i++) {
            long[] p = patients.get(data.rnd().nextInt(patients.size()));
            // Spread over the past 60 days and the next 14 days, so "tomorrow" always has some.
            int dayOffset = data.rnd().nextInt(75) - 60;
            LocalDateTime when = today.plusDays(dayOffset).atTime(8 + data.rnd().nextInt(9), data.rnd().nextBoolean() ? 0 : 30);
            String status = dayOffset < 0 ? (data.rnd().nextInt(10) < 8 ? "DONE" : "CANCELLED") : "SCHEDULED";
            rows.add(new Object[] {p[0], p[2], p[1], Timestamp.valueOf(when), status, data.appointmentNote()});
        }
        jdbc.batchUpdate("""
            INSERT INTO appointment (patient_id, doctor_id, department_id, scheduled_at, status, notes)
            VALUES (?, ?, ?, ?, ?, ?)""", rows);
    }

    private void insertDocuments(List<Long> departmentIds) {
        for (long deptId : departmentIds) {
            for (String[] doc : SyntheticData.PLAIN_DOCUMENTS) {
                jdbc.update("INSERT INTO document (department_id, title, body, uploaded_on) VALUES (?, ?, ?, ?)",
                    deptId, doc[0], doc[1], Date.valueOf(LocalDate.now().minusDays(deptId)));
            }
        }
        // Documents with hidden instructions, used by the prompt-injection scenarios.
        // The gateway must keep the agent's permissions unchanged whatever these say.
        for (String[] doc : SyntheticData.POISONED_DOCUMENTS) {
            jdbc.update("INSERT INTO document (department_id, title, body, uploaded_on) VALUES (?, ?, ?, ?)",
                departmentIds.get(0), doc[0], doc[1], Date.valueOf(LocalDate.now().minusDays(1)));
        }
    }
}
