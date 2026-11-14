package dev.agentguard.gateway.approval;

import dev.agentguard.gateway.audit.AuditEntry;
import dev.agentguard.gateway.audit.AuditLog;
import dev.agentguard.gateway.auth.UserIdentity;
import dev.agentguard.gateway.mcp.ResultMasking;
import dev.agentguard.gateway.mcp.UpstreamClient;
import dev.agentguard.gateway.policy.Decision;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds risky calls until a second person decides.
 *
 * The request stores the exact call (tool + scoped arguments) and a SHA-256
 * of it. On approval the gateway executes that stored call once. The agent
 * never receives anything it could replay, and a call changed after it was
 * stored is detected by the hash and refused.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApprovalRepository approvals;
    private final UpstreamClient upstream;
    private final TransactionTemplate tx;
    private final Duration timeout;
    private final Clock clock;
    private final AuditLog audit;

    public ApprovalService(ApprovalRepository approvals, UpstreamClient upstream, PlatformTransactionManager txManager,
                           @Value("${agentguard.approvals.timeout:PT10M}") Duration timeout, Clock clock,
                           AuditLog audit) {
        this.approvals = approvals;
        this.upstream = upstream;
        this.tx = new TransactionTemplate(txManager);
        this.timeout = timeout;
        this.clock = clock;
        this.audit = audit;
    }

    private void audit(ApprovalRequest r, String who, String decision, String reason) {
        audit.record(new AuditEntry(AuditEntry.APPROVAL, null, who, null, r.getTool(), r.getArgsJson(),
            decision, r.getRuleId(), reason, null, null, r.getId()));
    }

    public ApprovalRequest create(UserIdentity requester, String tool, Map<String, Object> scopedArgs, Decision decision) {
        String argsJson = canonicalJson(scopedArgs);
        Instant now = clock.instant();
        ApprovalRequest request = new ApprovalRequest(requester.userId(), requester.username(), tool, argsJson,
            actionHash(requester.userId(), tool, argsJson), decision.ruleId(), decision.reason(),
            decision.maskProfile(), now, now.plus(timeout));
        return tx.execute(status -> approvals.save(request));
    }

    public List<ApprovalRequest> list(Optional<ApprovalStatus> status) {
        return status.map(approvals::findByStatusOrderByCreatedAtAsc)
            .orElseGet(approvals::findTop100ByOrderByCreatedAtDesc);
    }

    public Optional<ApprovalRequest> findForRequester(long id, long requesterId) {
        return approvals.findByIdAndRequesterId(id, requesterId);
    }

    public ApprovalRequest refuse(long id, String approver, String note) {
        decideOrExplain(id, ApprovalStatus.REFUSED, approver, note);
        ApprovalRequest r = approvals.findById(id).orElseThrow();
        audit(r, approver, "REFUSED", note == null ? "Refused by " + approver : note);
        return r;
    }

    /** Approves, then executes the stored call. Returns the request in its final state. */
    public ApprovalRequest approve(long id, String approver, String note) {
        decideOrExplain(id, ApprovalStatus.APPROVED, approver, note);
        audit(approvals.findById(id).orElseThrow(), approver, "APPROVED", note == null ? "Approved by " + approver : note);
        ApprovalRequest done = execute(id);
        audit(done, "gateway", done.getStatus().name(),
            done.getStatus() == ApprovalStatus.EXECUTED ? "Executed the stored call" : truncate(done.getResultJson()));
        return done;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    private void decideOrExplain(long id, ApprovalStatus to, String approver, String note) {
        Instant now = clock.instant();
        Integer changed = tx.execute(s -> approvals.decide(id, to, approver, note, now));
        if (changed != null && changed == 1) {
            return;
        }
        ApprovalRequest request = approvals.findById(id)
            .orElseThrow(() -> new ApprovalException(ApprovalException.Kind.NOT_FOUND, "No approval request " + id));
        if (request.getRequesterName().equals(approver)) {
            throw new ApprovalException(ApprovalException.Kind.SELF_APPROVAL, "You cannot decide on your own request");
        }
        if (request.getStatus() == ApprovalStatus.PENDING && !request.getExpiresAt().isAfter(now)) {
            expirePending();
            throw new ApprovalException(ApprovalException.Kind.ALREADY_DECIDED, "Request " + id + " has expired");
        }
        throw new ApprovalException(ApprovalException.Kind.ALREADY_DECIDED,
            "Request " + id + " is already " + request.getStatus());
    }

    private ApprovalRequest execute(long id) {
        ApprovalRequest request = approvals.findById(id).orElseThrow();
        String expected = actionHash(request.getRequesterId(), request.getTool(), request.getArgsJson());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                request.getActionHash().getBytes(StandardCharsets.UTF_8))) {
            log.error("Approval {} refused at execution: the stored call no longer matches its hash", id);
            return finish(id, false, "The stored call was altered after it was requested; not executed");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = JSON.readValue(request.getArgsJson(), Map.class);
            McpSchema.CallToolResult result = upstream.call(request.getTool(), args);
            if (request.getMaskProfile() != null) {
                result = ResultMasking.mask(result, request.getMaskProfile());
            }
            String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
            return finish(id, !Boolean.TRUE.equals(result.isError()), text);
        } catch (RuntimeException e) {
            log.error("Approval {} failed during execution: {}", id, e.getMessage());
            return finish(id, false, "Execution failed: " + e.getMessage());
        }
    }

    private ApprovalRequest finish(long id, boolean ok, String text) {
        return tx.execute(s -> {
            ApprovalRequest r = approvals.findById(id).orElseThrow();
            if (ok) {
                r.markExecuted(text, clock.instant());
            } else {
                r.markFailed(text, clock.instant());
            }
            return approvals.save(r);
        });
    }

    /** No answer within the time limit means refused. */
    @Scheduled(fixedDelayString = "${agentguard.approvals.expiry-check:PT30S}")
    public int expirePending() {
        Instant now = clock.instant();
        List<ApprovalRequest> due = approvals.findByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING).stream()
            .filter(r -> !r.getExpiresAt().isAfter(now)).toList();
        Integer expired = tx.execute(s -> approvals.expirePending(now));
        due.forEach(r -> audit(r, "gateway", "EXPIRED", "No decision before " + r.getExpiresAt()));
        if (expired != null && expired > 0) {
            log.info("{} approval request(s) expired without a decision", expired);
        }
        return expired == null ? 0 : expired;
    }

    static String canonicalJson(Map<String, Object> args) {
        return JSON.writeValueAsString(new TreeMap<>(args));
    }

    static String actionHash(long requesterId, String tool, String canonicalArgs) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((requesterId + "\n" + tool + "\n" + canonicalArgs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
