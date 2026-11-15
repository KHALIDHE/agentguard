package dev.agentguard.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentguard.gateway.auth.Role;
import dev.agentguard.gateway.auth.UserIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final UserIdentity ACCOUNTANT = new UserIdentity(1, "n", Role.ACCOUNTANT, "ACCT", null);

    private static PolicyRule rule(String id, int priority, DecisionType decision) {
        return new PolicyRule(id, priority, Set.of(Role.ACCOUNTANT), Set.of("get_document"),
            Condition.ALWAYS, decision, RowScope.NONE, null, "because " + id);
    }

    @Test
    void withNoRulesEverythingIsDenied() {
        PolicyEngine engine = new PolicyEngine();

        Decision d = engine.evaluate(ToolCallContext.of(ACCOUNTANT, "get_document", Map.of()));

        assertThat(d.type()).isEqualTo(DecisionType.DENY);
        assertThat(d.ruleId()).isEqualTo(Decision.DEFAULT_DENY_RULE);
    }

    @Test
    void lowestPriorityNumberWinsWhateverTheFileOrder() {
        PolicyEngine engine = new PolicyEngine();
        engine.replaceRules(List.of(rule("late-allow", 50, DecisionType.ALLOW), rule("early-deny", 10, DecisionType.DENY)));

        assertThat(engine.evaluate(ToolCallContext.of(ACCOUNTANT, "get_document", Map.of())).ruleId()).isEqualTo("early-deny");
    }

    @Test
    void aRuleForAnotherRoleOrToolNeverMatches() {
        PolicyEngine engine = new PolicyEngine();
        engine.replaceRules(List.of(rule("accountant-documents", 1, DecisionType.ALLOW)));
        UserIdentity hr = new UserIdentity(2, "h", Role.HR, null, null);

        assertThat(engine.evaluate(ToolCallContext.of(hr, "get_document", Map.of())).type()).isEqualTo(DecisionType.DENY);
        assertThat(engine.evaluate(ToolCallContext.of(ACCOUNTANT, "run_query", Map.of())).type()).isEqualTo(DecisionType.DENY);
    }
}
