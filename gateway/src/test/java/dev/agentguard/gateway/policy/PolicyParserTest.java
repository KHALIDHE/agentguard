package dev.agentguard.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyParserTest {

    private static List<PolicyRule> parse(String yaml) {
        return PolicyParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
            KnownTools.ALL, KnownTools.MASK_PROFILES);
    }

    private static final String VALID_RULE = """
        rules:
          - id: r1
            priority: 1
            roles: [ACCOUNTANT]
            tools: [get_document]
            decision: ALLOW
            reason: ok
        """;

    @Test
    void parsesAValidRule() {
        PolicyRule rule = parse(VALID_RULE).get(0);

        assertThat(rule.id()).isEqualTo("r1");
        assertThat(rule.roles()).containsExactly(Role.ACCOUNTANT);
        assertThat(rule.scope()).isEqualTo(RowScope.NONE);
    }

    @Test
    void duplicateIdsAreRefused() {
        String yaml = VALID_RULE + """
              - id: r1
                priority: 2
                roles: [HR]
                tools: [get_document]
                decision: DENY
                reason: twice
            """;
        assertThatThrownBy(() -> parse(yaml)).hasMessageContaining("duplicate rule id: r1");
    }

    @Test
    void typosStopTheGateway() {
        assertThatThrownBy(() -> parse(VALID_RULE.replace("get_document", "get_documents")))
            .hasMessageContaining("unknown tool get_documents");
        assertThatThrownBy(() -> parse(VALID_RULE.replace("ACCOUNTANT", "ACCOUNTAN")))
            .hasMessageContaining("unknown role ACCOUNTAN");
        assertThatThrownBy(() -> parse(VALID_RULE.replace("ALLOW", "ALOW")))
            .hasMessageContaining("unknown decision ALOW");
        assertThatThrownBy(() -> parse(VALID_RULE.replace("reason: ok", "reason: ok\n    rol: [HR]")))
            .hasMessageContaining("unknown key 'rol'");
    }

    @Test
    void maskRuleNeedsAKnownProfile() {
        assertThatThrownBy(() -> parse(VALID_RULE.replace("ALLOW", "MASK")))
            .hasMessageContaining("needs a maskProfile");
        assertThatThrownBy(() -> parse(VALID_RULE.replace("ALLOW", "MASK\n    maskProfile: everything")))
            .hasMessageContaining("unknown mask profile everything");
    }

    @Test
    void ruleWithoutReasonIsRefused() {
        assertThatThrownBy(() -> parse(VALID_RULE.replace("    reason: ok\n", "")))
            .hasMessageContaining("missing 'reason'");
    }

    @Test
    void yamlTagsCannotInstantiateJavaObjects() {
        String malicious = "rules: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]";
        assertThatThrownBy(() -> parse(malicious)).isNotInstanceOf(NullPointerException.class);
    }

    @Test
    void conditionsCombine() {
        Condition c = PolicyParser.condition(Map.of("all", List.of(
            Map.of("argument", "reportType", "in", List.of("INVOICES")),
            Map.of("not", Map.of("argument", "limit", "gt", 100)))), "t");

        UserIdentity u = new UserIdentity(1, "u", Role.FINANCE_MANAGER, "ACCT", 1L);
        assertThat(c.test(ToolCallContext.of(u, "export_report", Map.of("reportType", "INVOICES", "limit", 50)))).isTrue();
        assertThat(c.test(ToolCallContext.of(u, "export_report", Map.of("reportType", "INVOICES", "limit", 500)))).isFalse();
        assertThat(c.test(ToolCallContext.of(u, "export_report", Map.of("reportType", "SUPPLIERS")))).isFalse();
        assertThat(c.describe()).isEqualTo("(reportType in [INVOICES]) and (not (limit > 100.0))");
    }
}
