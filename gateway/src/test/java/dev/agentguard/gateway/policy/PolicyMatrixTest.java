package dev.agentguard.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every role against every tool, with the real policies.yml. If someone edits
 * the policy, this table shows exactly which permissions changed.
 */
class PolicyMatrixTest {

    private static final PolicyEngine engine = new PolicyEngine();

    @BeforeAll
    static void loadRealPolicy() throws Exception {
        try (InputStream in = PolicyMatrixTest.class.getResourceAsStream("/policies.yml")) {
            engine.replaceRules(PolicyParser.parse(in, KnownTools.ALL, KnownTools.MASK_PROFILES));
        }
    }

    private static Decision decide(Role role, String tool, Map<String, Object> args) {
        UserIdentity user = new UserIdentity(1, "someone", role,
            role == Role.HR || role == Role.ADMIN ? null : "ACCT", null);
        return engine.evaluate(ToolCallContext.of(user, tool, args));
    }

    @ParameterizedTest(name = "{0} calling {1} -> {2}")
    @CsvSource({
        // role,        tool,                 expected
        "FINANCE_MANAGER,       search_suppliers,       ALLOW",
        "FINANCE_MANAGER,       get_supplier_record,    ALLOW",
        "FINANCE_MANAGER,       list_invoices,     ALLOW",
        "FINANCE_MANAGER,       update_invoice,    ALLOW",
        "FINANCE_MANAGER,       cancel_invoices,   DENY",
        "FINANCE_MANAGER,       get_employee_directory,   ALLOW",
        "FINANCE_MANAGER,       get_employee_salaries,    DENY",
        "FINANCE_MANAGER,       export_report,         DENY",
        "FINANCE_MANAGER,       get_document,          ALLOW",
        "FINANCE_MANAGER,       run_query,             DENY",

        "ACCOUNTANT,        search_suppliers,       MASK",
        "ACCOUNTANT,        get_supplier_record,    MASK",
        "ACCOUNTANT,        list_invoices,     ALLOW",
        "ACCOUNTANT,        update_invoice,    DENY",
        "ACCOUNTANT,        cancel_invoices,   DENY",
        "ACCOUNTANT,        get_employee_directory,   ALLOW",
        "ACCOUNTANT,        get_employee_salaries,    DENY",
        "ACCOUNTANT,        export_report,         DENY",
        "ACCOUNTANT,        get_document,          ALLOW",
        "ACCOUNTANT,        run_query,             DENY",

        "CLERK, search_suppliers,       MASK",
        "CLERK, get_supplier_record,    DENY",
        "CLERK, list_invoices,     ALLOW",
        "CLERK, update_invoice,    ALLOW",
        "CLERK, cancel_invoices,   REQUIRE_APPROVAL",
        "CLERK, get_employee_directory,   ALLOW",
        "CLERK, get_employee_salaries,    DENY",
        "CLERK, export_report,         DENY",
        "CLERK, get_document,          ALLOW",
        "CLERK, run_query,             DENY",

        "HR,           search_suppliers,       DENY",
        "HR,           get_supplier_record,    DENY",
        "HR,           list_invoices,     DENY",
        "HR,           update_invoice,    DENY",
        "HR,           cancel_invoices,   DENY",
        "HR,           get_employee_directory,   ALLOW",
        "HR,           get_employee_salaries,    ALLOW",
        "HR,           export_report,         DENY",
        "HR,           get_document,          ALLOW",
        "HR,           run_query,             DENY",

        "ADMIN,        search_suppliers,       DENY",
        "ADMIN,        get_supplier_record,    DENY",
        "ADMIN,        list_invoices,     DENY",
        "ADMIN,        update_invoice,    DENY",
        "ADMIN,        cancel_invoices,   REQUIRE_APPROVAL",
        "ADMIN,        get_employee_directory,   ALLOW",
        "ADMIN,        get_employee_salaries,    DENY",
        "ADMIN,        export_report,         REQUIRE_APPROVAL",
        "ADMIN,        get_document,          ALLOW",
        "ADMIN,        run_query,             ALLOW",
    })
    void everyRoleAgainstEveryTool(Role role, String tool, DecisionType expected) {
        Map<String, Object> args = new HashMap<>();
        if (tool.equals(KnownTools.EXPORT_REPORT)) {
            args.put("reportType", "SUPPLIERS");
        }
        assertThat(decide(role, tool, args).type()).isEqualTo(expected);
    }

    @Test
    void managersMayExportInvoicesButOnlyWithApproval() {
        Decision invoices = decide(Role.FINANCE_MANAGER, KnownTools.EXPORT_REPORT, Map.of("reportType", "INVOICES"));
        Decision suppliers = decide(Role.FINANCE_MANAGER, KnownTools.EXPORT_REPORT, Map.of("reportType", "SUPPLIERS"));

        assertThat(invoices.type()).isEqualTo(DecisionType.REQUIRE_APPROVAL);
        assertThat(invoices.ruleId()).isEqualTo("export-invoices-manager");
        assertThat(suppliers.ruleId()).isEqualTo(Decision.DEFAULT_DENY_RULE);
    }

    @Test
    void everyDecisionNamesItsRuleAndReason() {
        Decision denied = decide(Role.ACCOUNTANT, KnownTools.GET_EMPLOYEE_SALARIES, Map.of());

        assertThat(denied.ruleId()).isEqualTo("salaries-everyone-else");
        assertThat(denied.reason()).isEqualTo("Salary data is restricted to HR.");
    }

    @Test
    void scopeAndMaskTravelWithTheDecision() {
        Decision accountant = decide(Role.ACCOUNTANT, KnownTools.GET_SUPPLIER_RECORD, Map.of("supplierId", 1));

        assertThat(accountant.scope()).isEqualTo(RowScope.SAME_DEPARTMENT);
        assertThat(accountant.maskProfile()).isEqualTo("accountant-supplier");
    }
}
