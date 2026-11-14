package dev.agentguard.gateway.audit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Command-line verification:
 *
 *   java -jar gateway.jar --verify-audit
 *
 * Prints the result and exits with 0 if the chain is intact, 1 if not, so a
 * cron job or CI step can alert on it.
 */
@Component
public class VerifyAuditCommand implements ApplicationRunner {

    private final AuditLog audit;
    private final ApplicationContext context;

    public VerifyAuditCommand(AuditLog audit, ApplicationContext context) {
        this.audit = audit;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("verify-audit")) {
            return;
        }
        AuditLog.Verification result = audit.verify();
        System.out.println((result.intact() ? "AUDIT OK: " : "AUDIT BROKEN: ") + result.message()
            + " (" + result.rowsChecked() + " rows checked"
            + (result.firstBadSeq() == null ? "" : ", first bad row " + result.firstBadSeq()) + ")");
        int code = result.intact() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
