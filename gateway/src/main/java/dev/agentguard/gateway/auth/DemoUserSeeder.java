package dev.agentguard.gateway.auth;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the demo users once, all with the password from DEMO_USER_PASSWORD.
 * Without that variable no user is created, so a real deployment never gets
 * accounts with a known password.
 *
 * Employee ids match the finance seed: the first three employee rows of each
 * department are its managers (ACCT 1-3, TREAS 8-10).
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    record Demo(String username, Role role, String department, Long employeeId) {}

    static final List<Demo> DEMO_USERS = List.of(
        new Demo("fm.amrani", Role.FINANCE_MANAGER, "ACCT", 1L),
        new Demo("fm.tazi", Role.FINANCE_MANAGER, "TREAS", 8L),
        new Demo("accountant.salma", Role.ACCOUNTANT, "ACCT", null),
        new Demo("accountant.hajar", Role.ACCOUNTANT, "TREAS", null),
        new Demo("clerk.omar", Role.CLERK, "ACCT", null),
        new Demo("hr.imane", Role.HR, null, null),
        new Demo("admin.youssef", Role.ADMIN, null, null),
        // A second administrator: approvals need someone other than the requester.
        new Demo("admin.nadia", Role.ADMIN, null, null));

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public DemoUserSeeder(AppUserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${agentguard.demo.user-password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.length() < 10) {
            log.info("DEMO_USER_PASSWORD not set (10+ characters): no demo users created");
            return;
        }
        String hash = passwordEncoder.encode(password);
        int created = 0;
        for (Demo d : DEMO_USERS) {
            if (!users.existsByUsername(d.username())) {
                users.save(new AppUser(d.username(), hash, d.role(), d.department(), d.employeeId()));
                created++;
            }
        }
        if (created > 0) {
            log.info("Created {} demo users", created);
        }
    }
}
