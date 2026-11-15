package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.mcp.ToolRegistrar;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * What comes back through the gateway, compared with what is in the database.
 * Limits are enforced in SQL and on the result, never by asking the model.
 */
class RowScopeAndMaskingTest extends GatewayIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ToolRegistrar registrar;

    @BeforeEach
    void waitForTools() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!registrar.isReady() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
    }

    private static long supplierIn(String department) {
        return financeDb().queryForObject("""
            SELECT MIN(p.id) FROM supplier p JOIN department d ON d.id = p.department_id WHERE d.code = ?""",
            Long.class, department);
    }

    private McpSchema.CallToolResult call(String user, String tool, Map<String, Object> args) {
        try (McpSyncClient client = agent(login(user))) {
            return client.callTool(new McpSchema.CallToolRequest(tool, args));
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(McpSchema.CallToolResult result) {
        return (List<Map<String, Object>>) JSON.readValue(text(result), List.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> record(McpSchema.CallToolResult result) {
        return (Map<String, Object>) JSON.readValue(text(result), Map.class);
    }

    @Test
    void accountantSearchReturnsOnlyHerDepartment() {
        List<Map<String, Object>> found = rows(call("accountant.salma", "search_suppliers", Map.of("limit", 100)));
        Integer acctSuppliers = financeDb().queryForObject("""
            SELECT COUNT(*) FROM supplier p JOIN department d ON d.id = p.department_id WHERE d.code = 'ACCT'""",
            Integer.class);

        assertThat(found).isNotEmpty().allSatisfy(r -> assertThat(r).containsEntry("DEPARTMENT", "ACCT"));
        assertThat(found).hasSize(Math.min(100, acctSuppliers));
    }

    @Test
    void accountantAskingForAnotherDepartmentsSupplierGetsNothing() {
        Map<String, Object> result = record(call("accountant.salma", "get_supplier_record",
            Map.of("supplierId", supplierIn("TREAS"))));

        assertThat(result).containsEntry("found", false);
    }

    @Test
    void accountantRecordHasNoAddressNoIbanAndMaskedIdentifiers() {
        Map<String, Object> result = record(call("accountant.salma", "get_supplier_record",
            Map.of("supplierId", supplierIn("ACCT"))));

        assertThat(result).containsKey("FULL_NAME").doesNotContainKeys("ADDRESS", "IBAN");
        assertThat((String) result.get("TAX_ID")).startsWith("******");
    }

    @Test
    void managerSeesTheFullRecordOfHisDepartment() {
        Map<String, Object> result = record(call("fm.amrani", "get_supplier_record",
            Map.of("supplierId", supplierIn("ACCT"))));

        assertThat(result).containsKeys("IBAN", "ADDRESS");
        assertThat((String) result.get("TAX_ID")).startsWith("ZZ");
    }

    @Test
    void clerkSeesOnlyBookingFields() {
        List<Map<String, Object>> found = rows(call("clerk.omar", "search_suppliers", Map.of("limit", 5)));

        assertThat(found).isNotEmpty().allSatisfy(r ->
            assertThat(r.keySet()).isSubsetOf("ID", "FULL_NAME", "DEPARTMENT", "MANAGER_ID", "ONBOARDED_ON"));
    }

    @Test
    void malformedArgumentsAreRefusedBeforeAnyQuery() {
        McpSchema.CallToolResult result = call("fm.amrani", "list_invoices", Map.of("date", "2026-02-31"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("must be a date");
    }

    @Test
    void adminAggregateQueryRunsWithAForcedLimit() {
        McpSchema.CallToolResult result = call("admin.youssef", "run_query", Map.of(
            "sql", "SELECT department_id, COUNT(*) AS suppliers FROM supplier GROUP BY department_id", "maxRows", 3));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(rows(result)).hasSize(3);
    }

    @Test
    void sqlGuardRefusesSensitiveColumnsAndWrites() {
        assertThat(text(call("admin.youssef", "run_query", Map.of("sql", "SELECT salary_mad FROM employee"))))
            .contains("SQL refused: column SALARY_MAD is not allowed");
        assertThat(text(call("admin.youssef", "run_query", Map.of("sql", "DELETE FROM supplier"))))
            .contains("SQL refused: only SELECT is allowed");
        assertThat(financeDb().queryForObject("SELECT COUNT(*) FROM supplier", Integer.class)).isEqualTo(300);
    }
}
