package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.policy.RowScope;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeResolverTest {

    private static final ToolSchema SEARCH = ToolCatalog.find("search_patients").orElseThrow();
    private static final ToolSchema DIRECTORY = ToolCatalog.find("get_staff_directory").orElseThrow();

    @Test
    void departmentComesFromTheIdentity() {
        UserIdentity nurse = new UserIdentity(1, "n", Role.NURSE, "CARDIO", null);

        Map<String, Object> args = ScopeResolver.apply(RowScope.SAME_DEPARTMENT, nurse, SEARCH, Map.of("name", "a"));

        assertThat(args).containsEntry("scopeDepartment", "CARDIO").containsEntry("name", "a");
    }

    @Test
    void ownPatientsUsesTheStaffId() {
        UserIdentity doctor = new UserIdentity(1, "d", Role.DOCTOR, "CARDIO", 7L);

        assertThat(ScopeResolver.apply(RowScope.OWN_PATIENTS, doctor, SEARCH, Map.of()))
            .containsEntry("scopeDoctorId", 7L);
    }

    @Test
    void failsClosedWhenTheScopeCannotBeApplied() {
        UserIdentity hr = new UserIdentity(1, "h", Role.HR, null, null);
        UserIdentity nurse = new UserIdentity(1, "n", Role.NURSE, "CARDIO", null);

        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.SAME_DEPARTMENT, hr, SEARCH, Map.of()))
            .hasMessageContaining("no department");
        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.OWN_PATIENTS, nurse, SEARCH, Map.of()))
            .hasMessageContaining("not linked to a staff record");
        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.SAME_DEPARTMENT, nurse, DIRECTORY, Map.of()))
            .hasMessageContaining("cannot be limited");
    }
}
