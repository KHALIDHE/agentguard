package dev.agentguard.gateway.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private long requesterId;

    @Column(name = "requester_name", nullable = false, updatable = false, length = 60)
    private String requesterName;

    @Column(nullable = false, updatable = false, length = 60)
    private String tool;

    @Lob
    @Column(name = "args_json", nullable = false, updatable = false)
    private String argsJson;

    @Column(name = "action_hash", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String actionHash;

    @Column(name = "rule_id", nullable = false, updatable = false, length = 80)
    private String ruleId;

    @Column(nullable = false, updatable = false, length = 300)
    private String reason;

    @Column(name = "mask_profile", updatable = false, length = 40)
    private String maskProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ApprovalStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 300)
    private String decisionNote;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Lob
    @Column(name = "result_json")
    private String resultJson;

    protected ApprovalRequest() {
        // for JPA
    }

    ApprovalRequest(long requesterId, String requesterName, String tool, String argsJson, String actionHash,
                    String ruleId, String reason, String maskProfile, Instant now, Instant expiresAt) {
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.tool = tool;
        this.argsJson = argsJson;
        this.actionHash = actionHash;
        this.ruleId = ruleId;
        this.reason = reason;
        this.maskProfile = maskProfile;
        this.status = ApprovalStatus.PENDING;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    void markExecuted(String result, Instant at) {
        this.status = ApprovalStatus.EXECUTED;
        this.resultJson = result;
        this.executedAt = at;
    }

    void markFailed(String why, Instant at) {
        this.status = ApprovalStatus.FAILED;
        this.resultJson = why;
        this.executedAt = at;
    }

    public Long getId() { return id; }
    public long getRequesterId() { return requesterId; }
    public String getRequesterName() { return requesterName; }
    public String getTool() { return tool; }
    public String getArgsJson() { return argsJson; }
    public String getActionHash() { return actionHash; }
    public String getRuleId() { return ruleId; }
    public String getReason() { return reason; }
    public String getMaskProfile() { return maskProfile; }
    public ApprovalStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getExecutedAt() { return executedAt; }
    public String getResultJson() { return resultJson; }
}
