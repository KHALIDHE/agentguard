package dev.agentguard.financetools.seed;

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
 * Fills the finance schema with synthetic data on first start.
 *
 * The data is generated from a fixed random seed, so every machine gets the
 * same 6 departments, 42 employee, 300 suppliers, 1500 invoices and 24
 * documents. That matters for the evaluation harness later: a scenario that
 * says "supplier 42 belongs to ACCT" must stay true everywhere.
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    static final long SEED = 42L;
    static final int SUPPLIERS = 300;
    static final int INVOICES = 1500;

    private final JdbcTemplate jdbc;

    public SeedRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM department", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Finance data already present, seed skipped");
            return;
        }
        Random rnd = new Random(SEED);
        SyntheticData data = new SyntheticData(rnd);

        List<Long> departmentIds = insertDepartments();
        List<long[]> managers = insertEmployee(departmentIds, data);          // [employeeId, departmentId]
        List<long[]> suppliers = insertSuppliers(departmentIds, managers, data); // [supplierId, departmentId, managerId]
        insertInvoices(suppliers, data);
        insertDocuments(departmentIds);

        log.info("Seeded {} departments, {} managers, {} suppliers, {} invoices",
            departmentIds.size(), managers.size(), suppliers.size(), INVOICES);
    }

    private List<Long> insertDepartments() {
        List<Long> ids = new ArrayList<>();
        for (String[] d : SyntheticData.DEPARTMENTS) {
            jdbc.update("INSERT INTO department (code, name) VALUES (?, ?)", d[0], d[1]);
            ids.add(jdbc.queryForObject("SELECT id FROM department WHERE code = ?", Long.class, d[0]));
        }
        return ids;
    }

    private List<long[]> insertEmployee(List<Long> departmentIds, SyntheticData data) {
        List<long[]> managers = new ArrayList<>();
        int n = 0;
        for (long deptId : departmentIds) {
            // 3 managers, 3 accountants and 1 clerk per department
            for (int i = 0; i < 7; i++) {
                String role = i < 3 ? "FINANCE_MANAGER" : i < 6 ? "ACCOUNTANT" : "CLERK";
                long id = insertOneEmployee(deptId, role, data, ++n);
                if (role.equals("FINANCE_MANAGER")) managers.add(new long[] {id, deptId});
            }
        }
        // HR and ADMIN sit in the first department for simplicity
        insertOneEmployee(departmentIds.get(0), "HR", data, ++n);
        insertOneEmployee(departmentIds.get(0), "ADMIN", data, ++n);
        return managers;
    }

    private long insertOneEmployee(long deptId, String role, SyntheticData data, int n) {
        String name = data.personName();
        String email = name.toLowerCase().replace(' ', '.') + n + "@finance.example";
        jdbc.update("INSERT INTO employee (department_id, full_name, role, email, salary_mad, hired_on) VALUES (?, ?, ?, ?, ?, ?)",
            deptId, name, role, email, data.salaryFor(role), Date.valueOf(data.pastDate(365 * 15)));
        return jdbc.queryForObject("SELECT id FROM employee WHERE email = ?", Long.class, email);
    }

    private List<long[]> insertSuppliers(List<Long> departmentIds, List<long[]> managers, SyntheticData data) {
        List<long[]> suppliers = new ArrayList<>();
        for (int i = 0; i < SUPPLIERS; i++) {
            long[] manager = managers.get(data.rnd().nextInt(managers.size()));
            long deptId = manager[1];
            String taxId = data.taxId(i);
            jdbc.update("""
                INSERT INTO supplier (department_id, manager_id, full_name, tax_id, founded_on,
                                     phone, address, iban, onboarded_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                deptId, manager[0], data.companyName(), taxId, Date.valueOf(data.pastDate(365 * 80)),
                data.phone(), data.address(), data.ibanFor(departmentIds.indexOf(deptId)),
                Date.valueOf(data.pastDate(400)));
            long id = jdbc.queryForObject("SELECT id FROM supplier WHERE tax_id = ?", Long.class, taxId);
            suppliers.add(new long[] {id, deptId, manager[0]});
        }
        return suppliers;
    }

    private void insertInvoices(List<long[]> suppliers, SyntheticData data) {
        List<Object[]> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < INVOICES; i++) {
            long[] p = suppliers.get(data.rnd().nextInt(suppliers.size()));
            // Spread over the past 60 days and the next 14 days, so "tomorrow" always has some.
            int dayOffset = data.rnd().nextInt(75) - 60;
            LocalDateTime when = today.plusDays(dayOffset).atTime(8 + data.rnd().nextInt(9), data.rnd().nextBoolean() ? 0 : 30);
            String status = dayOffset < 0 ? (data.rnd().nextInt(10) < 8 ? "PAID" : "CANCELLED") : "PENDING";
            rows.add(new Object[] {p[0], p[2], p[1], Timestamp.valueOf(when), data.invoiceAmount(), status, data.invoiceNote()});
        }
        jdbc.batchUpdate("""
            INSERT INTO invoice (supplier_id, manager_id, department_id, due_at, amount_mad, status, notes)
            VALUES (?, ?, ?, ?, ?, ?, ?)""", rows);
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
