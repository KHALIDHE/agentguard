package dev.agentguard.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.approval.ApprovalService;
import dev.agentguard.gateway.mcp.ToolRegistrar;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The receptionist asks to cancel a whole day. Nothing happens until another
 * person decides, and the gateway then runs exactly the stored, scoped call.
 */
class ApprovalFlowTest extends GatewayIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ID = Pattern.compile("request (\\d+) is waiting");

    @Autowired
    ToolRegistrar registrar;

    @Autowired
    ApprovalService approvals;

    @Autowired
    JdbcTemplate gatewayDb;

    @BeforeEach
    void waitForTools() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!registrar.isReady() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
        }
    }

    /** Future days with scheduled CARDIO appointments; each test takes a different one. */
    private static List<LocalDate> cardioDaysWithAppointments() {
        return hospitalDb().queryForList("""
            SELECT DISTINCT TRUNC(a.scheduled_at) FROM appointment a JOIN department d ON d.id = a.department_id
            WHERE d.code = 'CARDIO' AND a.status = 'SCHEDULED' AND a.scheduled_at > SYSDATE
            ORDER BY 1""", java.sql.Timestamp.class).stream().map(t -> t.toLocalDateTime().toLocalDate()).toList();
    }

    private static int scheduled(String department, LocalDate day) {
        return hospitalDb().queryForObject("""
            SELECT COUNT(*) FROM appointment a JOIN department d ON d.id = a.department_id
            WHERE d.code = ? AND a.status = 'SCHEDULED' AND a.scheduled_at >= ? AND a.scheduled_at < ?""",
            Integer.class, department, Date.valueOf(day), Date.valueOf(day.plusDays(1)));
    }

    private long requestCancellation(LocalDate day) {
        try (McpSyncClient client = agent(login("reception.omar"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("cancel_appointments", Map.of("date", day.toString())));
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            Matcher m = ID.matcher(text);
            assertThat(m.find()).as(text).isTrue();
            return Long.parseLong(m.group(1));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(String user, long approvalId) {
        try (McpSyncClient client = agent(login(user))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("get_approval_status", Map.of("approvalId", approvalId)));
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            return Boolean.TRUE.equals(result.isError())
                ? Map.<String, Object>of("error", text) : (Map<String, Object>) JSON.readValue(text, Map.class);
        }
    }

    private int decide(String admin, long id, String action) {
        return RestClient.create("http://localhost:" + port).post().uri("/admin/approvals/" + id + "/" + action)
            .header("Authorization", "Bearer " + login(admin))
            .contentType(MediaType.APPLICATION_JSON).body(Map.of("note", "checked by " + admin))
            .exchange((req, res) -> res.getStatusCode().value());
    }

    @Test
    void approvedCancellationRunsOnceAndOnlyInTheRequestersDepartment() {
        LocalDate day = cardioDaysWithAppointments().get(0);
        int neuroBefore = scheduled("NEURO", day);
        long id = requestCancellation(day);

        assertThat(scheduled("CARDIO", day)).as("nothing happens while pending").isPositive();
        assertThat(status("reception.omar", id)).containsEntry("status", "PENDING");

        assertThat(decide("admin.youssef", id, "approve")).isEqualTo(200);

        assertThat(scheduled("CARDIO", day)).isZero();
        assertThat(scheduled("NEURO", day)).as("scope stored with the request").isEqualTo(neuroBefore);
        assertThat(status("reception.omar", id)).containsEntry("status", "EXECUTED").containsKey("result");
        assertThat(decide("admin.nadia", id, "approve")).as("an approval cannot be used twice").isEqualTo(409);
    }

    @Test
    void refusedRequestNeverRuns() {
        LocalDate day = cardioDaysWithAppointments().get(1);
        int before = scheduled("CARDIO", day);
        long id = requestCancellation(day);

        assertThat(decide("admin.youssef", id, "refuse")).isEqualTo(200);

        assertThat(scheduled("CARDIO", day)).isEqualTo(before);
        assertThat(status("reception.omar", id)).containsEntry("status", "REFUSED");
        assertThat(decide("admin.nadia", id, "approve")).isEqualTo(409);
    }

    @Test
    void noAnswerInTimeMeansRefused() {
        LocalDate day = cardioDaysWithAppointments().get(2);
        int before = scheduled("CARDIO", day);
        long id = requestCancellation(day);
        gatewayDb.update("UPDATE approval_request SET expires_at = SYSTIMESTAMP - INTERVAL '1' MINUTE WHERE id = ?", id);

        assertThat(approvals.expirePending()).isGreaterThanOrEqualTo(1);

        assertThat(status("reception.omar", id)).containsEntry("status", "EXPIRED");
        assertThat(decide("admin.youssef", id, "approve")).isEqualTo(409);
        assertThat(scheduled("CARDIO", day)).isEqualTo(before);
    }

    @Test
    void nobodyApprovesTheirOwnRequest() {
        String text;
        try (McpSyncClient client = agent(login("admin.youssef"))) {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("export_report", Map.of("reportType", "APPOINTMENTS")));
            text = ((McpSchema.TextContent) result.content().get(0)).text();
        }
        Matcher m = ID.matcher(text);
        assertThat(m.find()).as(text).isTrue();
        long id = Long.parseLong(m.group(1));

        assertThat(decide("admin.youssef", id, "approve")).isEqualTo(403);
        assertThat(decide("admin.nadia", id, "approve")).isEqualTo(200);
        assertThat(status("admin.youssef", id)).containsEntry("status", "EXECUTED");
    }

    @Test
    void storedCallChangedAfterTheRequestIsNotExecuted() {
        List<LocalDate> days = cardioDaysWithAppointments();
        LocalDate requested = days.get(3);
        LocalDate other = days.get(4);
        int otherBefore = scheduled("CARDIO", other);
        long id = requestCancellation(requested);

        // Someone with database access swaps the date after the request was made.
        gatewayDb.update("UPDATE approval_request SET args_json = REPLACE(args_json, ?, ?) WHERE id = ?",
            requested.toString(), other.toString(), id);

        assertThat(decide("admin.youssef", id, "approve")).isEqualTo(200);
        assertThat(status("reception.omar", id)).containsEntry("status", "FAILED");
        assertThat(scheduled("CARDIO", other)).isEqualTo(otherBefore);
    }

    @Test
    void usersOnlySeeTheirOwnRequests() {
        long id = requestCancellation(cardioDaysWithAppointments().get(5));

        assertThat(status("nurse.salma", id)).containsKey("error");
        assertThat(status("reception.omar", id)).containsEntry("status", "PENDING");
    }
}
