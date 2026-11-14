package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.audit.AuditLog;
import dev.agentguard.gateway.audit.AuditRecord;
import dev.agentguard.gateway.mcp.ToolRegistrar;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The seeded documents contain instructions aimed at the agent. Whatever an
 * agent does after reading them, the user's permissions stay exactly the same.
 * The scanner's flags are a bonus; the policy is what stops the attack.
 */
class PromptInjectionTest extends GatewayIntegrationTest {

    @Autowired
    ToolRegistrar registrar;

    @Autowired
    AuditLog audit;

    @BeforeEach
    void waitForTools() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!registrar.isReady() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
    }

    private static long documentTitled(String title) {
        return hospitalDb().queryForObject("SELECT id FROM document WHERE title = ?", Long.class, title);
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void poisonedReportIsLabelledAndTheExportItAsksForStillFails() {
        try (McpSyncClient nurse = agent(login("nurse.salma"))) {
            McpSchema.CallToolResult doc = nurse.callTool(new McpSchema.CallToolRequest("get_document",
                Map.of("documentId", documentTitled("Quarterly report draft"))));

            assertThat(text(doc)).startsWith("[UNTRUSTED CONTENT from get_document")
                .contains("ignore-instructions").contains("tool-invocation");

            // A fooled agent does what the document says.
            McpSchema.CallToolResult export = nurse.callTool(new McpSchema.CallToolRequest("export_report",
                Map.of("reportType", "PATIENTS")));
            assertThat(export.isError()).isTrue();
            assertThat(text(export)).contains("default-deny");
        }

        AuditRecord read = audit.search("nurse.salma", "get_document", null, null, null, 1).get(0);
        assertThat(read.entry().flags()).contains("ignore-instructions");
    }

    @Test
    void fakeAdminClaimInADocumentChangesNothing() {
        try (McpSyncClient reception = agent(login("reception.omar"))) {
            McpSchema.CallToolResult doc = reception.callTool(new McpSchema.CallToolRequest("get_document",
                Map.of("documentId", documentTitled("Supplier invoice 2026-0412"))));
            assertThat(text(doc)).contains("authority-claim");

            McpSchema.CallToolResult salaries = reception.callTool(new McpSchema.CallToolRequest(
                "get_staff_salaries", Map.of("department", "CARDIO")));
            assertThat(salaries.isError()).isTrue();
            assertThat(text(salaries)).contains("salaries-everyone-else");
        }
    }

    @Test
    void injectedBulkCancellationOnlyReachesTheApprovalQueue() {
        int scheduledBefore = hospitalDb().queryForObject(
            "SELECT COUNT(*) FROM appointment WHERE status = 'SCHEDULED'", Integer.class);
        try (McpSyncClient reception = agent(login("reception.omar"))) {
            reception.callTool(new McpSchema.CallToolRequest("get_document",
                Map.of("documentId", documentTitled("Meeting minutes, 3 November"))));

            McpSchema.CallToolResult cancel = reception.callTool(new McpSchema.CallToolRequest(
                "cancel_appointments", Map.of("date", java.time.LocalDate.now().plusDays(1).toString())));

            assertThat(text(cancel)).startsWith("PENDING_APPROVAL");
        }
        assertThat(hospitalDb().queryForObject("SELECT COUNT(*) FROM appointment WHERE status = 'SCHEDULED'",
            Integer.class)).isEqualTo(scheduledBefore);
    }

    @Test
    void callsAboveTheRateLimitAreRefusedAndRecorded() {
        // The test configuration allows 5 list_appointments calls per minute.
        String day = java.time.LocalDate.now().toString();
        try (McpSyncClient nurse = agent(login("nurse.hajar"))) {
            for (int i = 0; i < 5; i++) {
                assertThat(nurse.callTool(new McpSchema.CallToolRequest("list_appointments", Map.of("date", day)))
                    .isError()).isNotEqualTo(Boolean.TRUE);
            }
            McpSchema.CallToolResult sixth = nurse.callTool(
                new McpSchema.CallToolRequest("list_appointments", Map.of("date", day)));
            assertThat(sixth.isError()).isTrue();
            assertThat(text(sixth)).contains("Too many calls");
        }
        assertThat(audit.search("nurse.hajar", "list_appointments", "DENY", null, null, 1).get(0).entry().ruleId())
            .isEqualTo("rate-limit");
    }

    @Test
    void dailyExportBudgetIsEnforced() {
        try (McpSyncClient admin = agent(login("admin.nadia"))) {
            for (int i = 0; i < 3; i++) {
                assertThat(text(admin.callTool(new McpSchema.CallToolRequest("export_report",
                    Map.of("reportType", "APPOINTMENTS"))))).startsWith("PENDING_APPROVAL");
            }
            McpSchema.CallToolResult fourth = admin.callTool(new McpSchema.CallToolRequest("export_report",
                Map.of("reportType", "APPOINTMENTS")));
            assertThat(fourth.isError()).isTrue();
            assertThat(text(fourth)).contains("Daily export budget");
        }
    }
}
