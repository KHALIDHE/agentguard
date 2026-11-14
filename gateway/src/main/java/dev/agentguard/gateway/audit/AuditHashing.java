package dev.agentguard.gateway.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * hash = SHA-256(previous hash + canonical form of this row).
 *
 * The canonical form lists every stored field in a fixed order, separated by
 * a control character that cannot appear in normal text, so two different
 * rows can never produce the same input.
 */
public final class AuditHashing {

    public static final String GENESIS = "0".repeat(64);
    private static final String SEPARATOR = "\u001f";

    private AuditHashing() {
    }

    public static String hash(String prevHash, long seq, Instant time, AuditEntry e) {
        String canonical = Stream.of(
                String.valueOf(seq), time.toString(), e.eventType(), e.requestId(), e.username(), e.role(),
                e.tool(), e.arguments(), e.decision(), e.ruleId(), e.reason(),
                e.latencyMs() == null ? null : String.valueOf(e.latencyMs()), e.flags(),
                e.approvalId() == null ? null : String.valueOf(e.approvalId()))
            .map(v -> Objects.toString(v, ""))
            .collect(Collectors.joining(SEPARATOR));
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prevHash.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
            sha.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
