package dev.agentguard.gateway.policy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads policies.yml at startup, validates it, hands the rules to the engine
 * and mirrors them into POLICY_RULE. An invalid file stops the application.
 */
@Component
public class PolicyLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyLoader.class);

    private final PolicyEngine engine;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Resource file;

    public PolicyLoader(PolicyEngine engine, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                        @Value("${agentguard.policies.file:classpath:policies.yml}") Resource file) {
        this.engine = engine;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.file = file;
    }

    /** Runs while the application starts, before the MCP endpoint takes calls. */
    @PostConstruct
    public void load() throws IOException {
        List<PolicyRule> rules;
        try (InputStream in = file.getInputStream()) {
            rules = PolicyParser.parse(in, KnownTools.ALL, KnownTools.MASK_PROFILES);
        }
        engine.replaceRules(rules);

        tx.executeWithoutResult(status -> mirror(rules));
        log.info("Loaded {} policy rules from {}", rules.size(), file.getDescription());
    }

    private void mirror(List<PolicyRule> rules) {
        jdbc.update("DELETE FROM policy_rule");
        jdbc.batchUpdate("""
            INSERT INTO policy_rule (id, priority, roles, tools, when_condition, decision, row_scope, mask_profile, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            rules.stream().map(r -> new Object[] {
                r.id(), r.priority(), join(r.roles()), join(r.tools()), r.when().describe(),
                r.decision().name(), r.scope().name(), r.maskProfile(), r.reason()
            }).toList());
    }

    private static String join(java.util.Collection<?> values) {
        return values.stream().map(String::valueOf).sorted().collect(Collectors.joining(","));
    }
}
