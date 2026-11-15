package dev.agentguard.gateway.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.policy.RowScope;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScopeResolverTest {

    private static final ToolSchema SEARCH = ToolCatalog.find("search_suppliers").orElseThrow();
    private static final ToolSchema DIRECTORY = ToolCatalog.find("get_employee_directory").orElseThrow();

    @Test
    void departmentComesFromTheIdentity() {
        UserIdentity accountant = new UserIdentity(1, "n", Role.ACCOUNTANT, "ACCT", null);

        Map<String, Object> args = ScopeResolver.apply(RowScope.SAME_DEPARTMENT, accountant, SEARCH, Map.of("name", "a"));

        assertThat(args).containsEntry("scopeDepartment", "ACCT").containsEntry("name", "a");
    }

    @Test
    void ownSuppliersUsesTheEmployeeId() {
        UserIdentity manager = new UserIdentity(1, "d", Role.FINANCE_MANAGER, "ACCT", 7L);

        assertThat(ScopeResolver.apply(RowScope.OWN_SUPPLIERS, manager, SEARCH, Map.of()))
            .containsEntry("scopeManagerId", 7L);
    }

    @Test
    void failsClosedWhenTheScopeCannotBeApplied() {
        UserIdentity hr = new UserIdentity(1, "h", Role.HR, null, null);
        UserIdentity accountant = new UserIdentity(1, "n", Role.ACCOUNTANT, "ACCT", null);

        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.SAME_DEPARTMENT, hr, SEARCH, Map.of()))
            .hasMessageContaining("no department");
        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.OWN_SUPPLIERS, accountant, SEARCH, Map.of()))
            .hasMessageContaining("not linked to a employee record");
        assertThatThrownBy(() -> ScopeResolver.apply(RowScope.SAME_DEPARTMENT, accountant, DIRECTORY, Map.of()))
            .hasMessageContaining("cannot be limited");
    }
}
