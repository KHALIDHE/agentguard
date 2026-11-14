package dev.agentguard.gateway.admin;

import dev.agentguard.gateway.approval.ApprovalException;
import dev.agentguard.gateway.approval.ApprovalRequest;
import dev.agentguard.gateway.approval.ApprovalService;
import dev.agentguard.gateway.approval.ApprovalStatus;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The approval queue, for the dashboard. ADMIN only (see SecurityConfig). */
@RestController
@RequestMapping("/admin/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalView> list(@RequestParam Optional<ApprovalStatus> status) {
        return approvals.list(status).stream().map(ApprovalView::of).toList();
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                                @RequestBody(required = false) DecisionBody body) {
        return ApprovalView.of(approvals.approve(id, jwt.getSubject(), body == null ? null : body.note()));
    }

    @PostMapping("/{id}/refuse")
    public ApprovalView refuse(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                               @RequestBody(required = false) DecisionBody body) {
        return ApprovalView.of(approvals.refuse(id, jwt.getSubject(), body == null ? null : body.note()));
    }

    @ExceptionHandler(ApprovalException.class)
    ProblemDetail approvalProblem(ApprovalException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SELF_APPROVAL -> HttpStatus.FORBIDDEN;
            case ALREADY_DECIDED -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    public record DecisionBody(@Size(max = 300) String note) {}

    public record ApprovalView(long id, String requester, String tool, String arguments, String ruleId, String reason,
                               ApprovalStatus status, Instant createdAt, Instant expiresAt, String decidedBy,
                               Instant decidedAt, String note, String result) {
        public static ApprovalView of(ApprovalRequest r) {
            return new ApprovalView(r.getId(), r.getRequesterName(), r.getTool(), r.getArgsJson(), r.getRuleId(),
                r.getReason(), r.getStatus(), r.getCreatedAt(), r.getExpiresAt(), r.getDecidedBy(), r.getDecidedAt(),
                r.getDecisionNote(), r.getResultJson());
        }
    }
}
