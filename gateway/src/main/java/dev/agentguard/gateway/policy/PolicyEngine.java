package dev.agentguard.gateway.policy;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Evaluates the rules in priority order; the first rule that matches decides.
 * If none matches, the answer is DENY. Like a firewall: what is not
 * explicitly allowed is forbidden, so a forgotten rule fails safe.
 *
 * The rule list is replaced atomically, so a reload never leaves a call
 * looking at half the old rules and half the new ones.
 */
@Component
public class PolicyEngine {

    private final AtomicReference<List<PolicyRule>> rules = new AtomicReference<>(List.of());

    public Decision evaluate(ToolCallContext call) {
        for (PolicyRule rule : rules.get()) {
            if (rule.matches(call)) {
                return rule.toDecision();
            }
        }
        return Decision.defaultDeny(call.tool());
    }

    public void replaceRules(List<PolicyRule> newRules) {
        rules.set(newRules.stream()
            .sorted(Comparator.comparingInt(PolicyRule::priority).thenComparing(PolicyRule::id))
            .toList());
    }

    public List<PolicyRule> rules() {
        return rules.get();
    }
}
