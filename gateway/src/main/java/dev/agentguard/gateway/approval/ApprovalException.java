package dev.agentguard.gateway.approval;

public class ApprovalException extends RuntimeException {

    public enum Kind { NOT_FOUND, SELF_APPROVAL, ALREADY_DECIDED }

    private final Kind kind;

    public ApprovalException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
