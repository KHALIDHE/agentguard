package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.gateway.audit.AuditEntry;
import dev.agentguard.gateway.audit.AuditLog;
import dev.agentguard.gateway.audit.AuditRecord;
import dev.agentguard.gateway.mcp.ToolRegistrar;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every decision is recorded, the chain verifies, and editing history is caught. */
class AuditTrailTest extends GatewayIntegrationTest {

    @Autowired
    AuditLog audit;

    @Autowired
    JdbcTemplate gatewayDb;

    @Autowired
    ToolRegistrar registrar;

    @BeforeEach
    void waitForTools() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!registrar.isReady() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
    }

    private void callAs(String user, String tool, Map<String, Object> args) {
        try (McpSyncClient client = agent(login(user))) {
            client.callTool(new McpSchema.CallToolRequest(tool, args));
        }
    }

    private AuditRecord latestFor(String user) {
        return audit.search(user, null, null, null, null, 1).get(0);
    }

    @Test
    void aDeniedCallIsRecordedWithItsRuleAndReason() {
        callAs("accountant.salma", "get_employee_salaries", Map.of());

        AuditRecord r = latestFor("accountant.salma");
        assertThat(r.entry().tool()).isEqualTo("get_employee_salaries");
        assertThat(r.entry().decision()).isEqualTo("DENY");
        assertThat(r.entry().ruleId()).isEqualTo("salaries-everyone-else");
        assertThat(r.entry().reason()).contains("restricted to HR");
        assertThat(r.entry().role()).isEqualTo("ACCOUNTANT");
        assertThat(r.entry().latencyMs()).isNotNull();
    }

    @Test
    void anAllowedAndMaskedCallIsRecordedToo() {
        callAs("accountant.hajar", "search_suppliers", Map.of("name", "Salma", "limit", 3));

        AuditRecord r = latestFor("accountant.hajar");
        assertThat(r.entry().decision()).isEqualTo("MASK");
        assertThat(r.entry().ruleId()).isEqualTo("suppliers-accountant");
        // The searched name is personal data: only its end is kept in the log.
        assertThat(r.entry().arguments()).contains("\"name\":\"***ma\"").doesNotContain("Salma");
    }

    @Test
    void validationRefusalsAreRecordedAsWell() {
        callAs("fm.tazi", "get_supplier_record", Map.of("supplierId", "abc"));

        AuditRecord r = latestFor("fm.tazi");
        assertThat(r.entry().decision()).isEqualTo("DENY");
        assertThat(r.entry().ruleId()).isEqualTo("validation");
    }

    @Test
    void theChainVerifies() {
        callAs("hr.imane", "get_employee_directory", Map.of());

        AuditLog.Verification v = audit.verify();

        assertThat(v.intact()).as(v.message()).isTrue();
        assertThat(v.rowsChecked()).isPositive();
    }

    @Test
    void theDatabaseRefusesUpdatesAndDeletes() {
        callAs("hr.imane", "get_employee_directory", Map.of());

        assertThatThrownBy(() -> gatewayDb.update("UPDATE audit_event SET reason = 'x' WHERE seq = 1"))
            .rootCause().hasMessageContaining("ORA-20001");
        assertThatThrownBy(() -> gatewayDb.update("DELETE FROM audit_event WHERE seq = 1"))
            .rootCause().hasMessageContaining("ORA-20001");
    }

    @Test
    void editingAnOldRecordBreaksTheChainAtThatRecord() {
        callAs("fm.amrani", "get_employee_directory", Map.of());
        callAs("fm.amrani", "get_employee_salaries", Map.of());
        long target = latestFor("fm.amrani").seq() - 1;
        String original = gatewayDb.queryForObject("SELECT reason FROM audit_event WHERE seq = ?", String.class, target);

        // Someone with DBA rights turns the trigger off and rewrites history.
        gatewayDb.execute("ALTER TRIGGER audit_event_append_only DISABLE");
        try {
            gatewayDb.update("UPDATE audit_event SET reason = 'nothing to see here' WHERE seq = ?", target);

            AuditLog.Verification v = audit.verify();
            assertThat(v.intact()).isFalse();
            assertThat(v.firstBadSeq()).isEqualTo(target);
            assertThat(v.message()).contains("was modified");
        } finally {
            gatewayDb.update("UPDATE audit_event SET reason = ? WHERE seq = ?", original, target);
            gatewayDb.execute("ALTER TRIGGER audit_event_append_only ENABLE");
        }
        assertThat(audit.verify().intact()).isTrue();
    }

    @Test
    void deletingARecordIsDetected() {
        callAs("fm.amrani", "get_employee_directory", Map.of());
        long target = latestFor("fm.amrani").seq();
        // Add one more record after it, so the deleted one is in the middle of the chain.
        callAs("hr.imane", "get_employee_directory", Map.of());

        // Keep an exact copy inside Oracle, to put the row back afterwards.
        gatewayDb.execute("CREATE TABLE audit_event_backup AS SELECT * FROM audit_event WHERE seq = " + target);
        gatewayDb.execute("ALTER TRIGGER audit_event_append_only DISABLE");
        try {
            gatewayDb.update("DELETE FROM audit_event WHERE seq = ?", target);

            AuditLog.Verification v = audit.verify();
            assertThat(v.intact()).isFalse();
            assertThat(v.firstBadSeq()).isEqualTo(target);
            assertThat(v.message()).contains("missing");
        } finally {
            gatewayDb.update("INSERT INTO audit_event SELECT * FROM audit_event_backup");
            gatewayDb.execute("DROP TABLE audit_event_backup");
            gatewayDb.execute("ALTER TRIGGER audit_event_append_only ENABLE");
        }
        assertThat(audit.verify().intact()).isTrue();
    }

    @Test
    void parallelWritesKeepOneUnbrokenChain() throws Exception {
        long before = gatewayDb.queryForObject("SELECT last_seq FROM audit_head", Long.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AuditRecord>> writes = IntStream.range(0, 40).mapToObj(i -> pool.submit(() -> audit.record(
                new AuditEntry(AuditEntry.TOOL_CALL, "load-" + i, "load.test", "ADMIN", "get_employee_directory",
                    "{}", "ALLOW", "directory-everyone", "parallel write " + i, 1L, null, null)))).toList();
            for (Future<AuditRecord> w : writes) {
                w.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(gatewayDb.queryForObject("SELECT last_seq FROM audit_head", Long.class)).isGreaterThanOrEqualTo(before + 40);
        AuditLog.Verification v = audit.verify();
        assertThat(v.intact()).as(v.message()).isTrue();
    }
}
