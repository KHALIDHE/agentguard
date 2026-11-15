package dev.agentguard.financetools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Talks to the running server the way an agent would: a real MCP client over
 * Streamable HTTP. Proves the tools are published and callable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"spring.config.name=finance-tools", "finance.service-token=" + FinanceToolsMcpTest.TOKEN})
class FinanceToolsMcpTest {

    static final String TOKEN = "test-service-token";

    static final Set<String> EXPECTED_TOOLS = Set.of(
        "search_suppliers", "get_supplier_record", "list_invoices", "update_invoice",
        "cancel_invoices", "get_employee_directory", "get_employee_salaries", "export_report",
        "get_document", "run_query");

    @Value("${local.server.port}")
    int port;

    private McpSyncClient client() {
        return client(TOKEN);
    }

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .httpRequestCustomizer((request, method, uri, body, context) -> {
                if (token != null) {
                    request.header("Authorization", "Bearer " + token);
                }
            })
            .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    @Test
    void refusesCallsWithoutTheServiceToken() {
        assertThatThrownBy(() -> client(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client("wrong-token")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void publishesAllTenTools() {
        try (McpSyncClient client = client()) {
            Set<String> names = client.listTools().tools().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
        }
    }

    @Test
    void employeeDirectoryReturnsNoSalary() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_employee_directory", Map.of("department", "ACCT")));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("FINANCE_MANAGER").contains("@finance.example");
            assertThat(text.toLowerCase()).doesNotContain("salary");
        }
    }

    @Test
    void scopeDepartmentLimitsTheSupplierSearch() {
        try (McpSyncClient client = client()) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                "search_suppliers", Map.of("limit", 500, "scopeDepartment", "TREAS")));

            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("TREAS");
            assertThat(text).doesNotContain("ACCT").doesNotContain("PROC");
        }
    }
}
