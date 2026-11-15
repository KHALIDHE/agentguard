package dev.agentguard.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditHashingTest {

    private static final Instant T = Instant.parse("2026-11-14T10:15:30.123Z");

    private static AuditEntry entry(String reason) {
        return new AuditEntry("TOOL_CALL", "r1", "accountant.salma", "ACCOUNTANT", "get_employee_salaries", "{}",
            "DENY", "salaries-everyone-else", reason, 3L, null, null);
    }

    @Test
    void sameInputSameHash() {
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, 1, T, entry("a")))
            .isEqualTo(AuditHashing.hash(AuditHashing.GENESIS, 1, T, entry("a")))
            .hasSize(64);
    }

    @Test
    void anyFieldChangeChangesTheHash() {
        String base = AuditHashing.hash(AuditHashing.GENESIS, 1, T, entry("a"));

        assertThat(AuditHashing.hash(AuditHashing.GENESIS, 1, T, entry("b"))).isNotEqualTo(base);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, 2, T, entry("a"))).isNotEqualTo(base);
        assertThat(AuditHashing.hash(AuditHashing.GENESIS, 1, T.plusMillis(1), entry("a"))).isNotEqualTo(base);
        assertThat(AuditHashing.hash("1".repeat(64), 1, T, entry("a"))).isNotEqualTo(base);
    }

    @Test
    void fieldsCannotBeShiftedIntoEachOther() {
        // "a" + "bc" and "ab" + "c" must not hash the same.
        AuditEntry one = new AuditEntry("T", "a", "bc", null, null, null, "D", null, null, null, null, null);
        AuditEntry two = new AuditEntry("T", "ab", "c", null, null, null, "D", null, null, null, null, null);

        assertThat(AuditHashing.hash(AuditHashing.GENESIS, 1, T, one))
            .isNotEqualTo(AuditHashing.hash(AuditHashing.GENESIS, 1, T, two));
    }
}
