package dev.agentguard.gateway.admin;

import dev.agentguard.gateway.policy.PolicyEngine;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the active rules, in evaluation order. ADMIN only (see SecurityConfig). */
@RestController
@RequestMapping("/admin/policies")
public class PolicyController {

    private final PolicyEngine engine;

    public PolicyController(PolicyEngine engine) {
        this.engine = engine;
    }

    @GetMapping
    public List<RuleView> list() {
        return engine.rules().stream().map(r -> new RuleView(
            r.id(), r.priority(), r.roles().stream().map(Enum::name).sorted().toList(),
            r.tools().stream().sorted().toList(), r.when().describe(), r.decision().name(),
            r.scope().name(), r.maskProfile(), r.reason())).toList();
    }

    public record RuleView(String id, int priority, List<String> roles, List<String> tools, String when,
                           String decision, String scope, String maskProfile, String reason) {}
}
