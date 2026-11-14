package dev.agentguard.gateway.admin;

import dev.agentguard.gateway.audit.AuditLog;
import dev.agentguard.gateway.audit.AuditRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit search and chain verification for the dashboard. ADMIN only. */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditRecord> search(@RequestParam(required = false) String user,
                                    @RequestParam(required = false) String tool,
                                    @RequestParam(required = false) String decision,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(defaultValue = "100") int limit) {
        return audit.search(user, tool, decision, from, to, limit);
    }

    @GetMapping("/verify")
    public AuditLog.Verification verify() {
        return audit.verify();
    }
}
