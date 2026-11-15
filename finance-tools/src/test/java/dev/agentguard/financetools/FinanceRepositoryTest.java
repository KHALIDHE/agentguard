package dev.agentguard.financetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.financetools.tools.FinanceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.config.name=finance-tools")
class FinanceRepositoryTest {

    @Autowired
    FinanceRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedIsDeterministic() {
        assertThat(count("department")).isEqualTo(6);
        assertThat(count("employee")).isEqualTo(44);        // 6 departments x 7, plus HR and ADMIN
        assertThat(count("supplier")).isEqualTo(300);
        assertThat(count("invoice")).isEqualTo(1500);
        assertThat(count("document")).isEqualTo(21);     // 6 x 3 plain, plus 3 poisoned
    }

    @Test
    void everySupplierBelongsToTheirManagersDepartment() {
        Integer mismatches = jdbc.queryForObject("""
            SELECT COUNT(*) FROM supplier p JOIN employee s ON s.id = p.manager_id
            WHERE s.department_id <> p.department_id""", Integer.class);
        assertThat(mismatches).isZero();
    }

    @Test
    void managerScopeReturnsOnlyThatManagersSuppliers() {
        Long managerId = jdbc.queryForObject(
            "SELECT MIN(id) FROM employee WHERE role = 'FINANCE_MANAGER'", Long.class);

        List<Map<String, Object>> rows = repo.searchSuppliers(null, null, managerId, 500);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("MANAGER_ID")).longValue()).isEqualTo(managerId));
    }

    @Test
    void recordOutsideScopeIsNotReturned() {
        // Find a supplier in ACCT and ask for it with a TREAS scope.
        Long acctSupplier = jdbc.queryForObject("""
            SELECT MIN(p.id) FROM supplier p JOIN department d ON d.id = p.department_id
            WHERE d.code = 'ACCT'""", Long.class);

        assertThat(repo.getSupplierRecord(acctSupplier, "ACCT", null)).isNotNull();
        assertThat(repo.getSupplierRecord(acctSupplier, "TREAS", null)).isNull();
    }

    @Test
    void readOnlyQueryRefusesWrites() {
        assertThatThrownBy(() -> repo.runReadOnlyQuery("UPDATE employee SET salary_mad = 0", 10))
            .hasMessageContaining("ORA-01456");
    }

    @Test
    void readOnlyQueryCapsRows() {
        assertThat(repo.runReadOnlyQuery("SELECT id FROM supplier", 5)).hasSize(5);
        assertThat(repo.runReadOnlyQuery("SELECT id FROM invoice", 999_999)).hasSize(500);
    }

    @Test
    void invoicesPerDepartmentMatchesAPlainCount() {
        LocalDate day = LocalDate.now().plusDays(1);
        Integer expectedTotal = jdbc.queryForObject("""
            SELECT COUNT(*) FROM invoice
            WHERE due_at >= ? AND due_at < ?""", Integer.class,
            java.sql.Date.valueOf(day), java.sql.Date.valueOf(day.plusDays(1)));

        List<Map<String, Object>> rows = repo.invoicesPerDepartment(day);

        int total = rows.stream().mapToInt(r -> ((Number) r.get("TOTAL")).intValue()).sum();
        assertThat(total).isEqualTo(expectedTotal);
        assertThat(rows).allSatisfy(r -> assertThat(r).containsKeys("DEPARTMENT", "TOTAL", "PENDING", "CANCELLED"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
