package dev.agentguard.gateway.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The human an agent is acting for, read from a token whose signature and
 * expiry were already checked. Every decision the gateway makes starts from
 * this record, never from anything the agent or the model says.
 */
public record UserIdentity(long userId, String username, Role role, String department, Long staffId) {

    static final String CLAIM_USER_ID = "uid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_DEPARTMENT = "dept";
    static final String CLAIM_STAFF_ID = "sid";

    public static UserIdentity from(Jwt jwt) {
        Number uid = jwt.getClaim(CLAIM_USER_ID);
        Number sid = jwt.getClaim(CLAIM_STAFF_ID);
        return new UserIdentity(
            uid.longValue(),
            jwt.getSubject(),
            Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
            jwt.getClaimAsString(CLAIM_DEPARTMENT),
            sid == null ? null : sid.longValue());
    }
}
