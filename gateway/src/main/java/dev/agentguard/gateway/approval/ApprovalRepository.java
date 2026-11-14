package dev.agentguard.gateway.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRepository extends JpaRepository<ApprovalRequest, Long> {

    List<ApprovalRequest> findByStatusOrderByCreatedAtAsc(ApprovalStatus status);

    List<ApprovalRequest> findTop100ByOrderByCreatedAtDesc();

    Optional<ApprovalRequest> findByIdAndRequesterId(long id, long requesterId);

    /**
     * Moves a request out of PENDING in one UPDATE. Only one caller can win:
     * the WHERE clause re-checks status and expiry inside the database, so two
     * approvers clicking at the same time cannot both succeed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ApprovalRequest a
           SET a.status = :to, a.decidedBy = :by, a.decidedAt = :now, a.decisionNote = :note
         WHERE a.id = :id AND a.status = dev.agentguard.gateway.approval.ApprovalStatus.PENDING
           AND a.expiresAt > :now AND a.requesterName <> :by""")
    int decide(@Param("id") long id, @Param("to") ApprovalStatus to, @Param("by") String by,
               @Param("note") String note, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE ApprovalRequest a SET a.status = dev.agentguard.gateway.approval.ApprovalStatus.EXPIRED
         WHERE a.status = dev.agentguard.gateway.approval.ApprovalStatus.PENDING AND a.expiresAt <= :now""")
    int expirePending(@Param("now") Instant now);
}
