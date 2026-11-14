package dev.agentguard.gateway.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionHashTest {

    @Test
    void argumentOrderDoesNotChangeTheHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("date", "2026-11-20");
        a.put("scopeDepartment", "CARDIO");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("scopeDepartment", "CARDIO");
        b.put("date", "2026-11-20");

        assertThat(ApprovalService.canonicalJson(a)).isEqualTo(ApprovalService.canonicalJson(b));
    }

    @Test
    void anyChangeToUserToolOrArgumentsChangesTheHash() {
        String args = ApprovalService.canonicalJson(Map.of("date", "2026-11-20"));
        String base = ApprovalService.actionHash(5, "cancel_appointments", args);

        assertThat(base).hasSize(64);
        assertThat(ApprovalService.actionHash(6, "cancel_appointments", args)).isNotEqualTo(base);
        assertThat(ApprovalService.actionHash(5, "update_appointment", args)).isNotEqualTo(base);
        assertThat(ApprovalService.actionHash(5, "cancel_appointments",
            ApprovalService.canonicalJson(Map.of("date", "2026-11-21")))).isNotEqualTo(base);
    }
}
