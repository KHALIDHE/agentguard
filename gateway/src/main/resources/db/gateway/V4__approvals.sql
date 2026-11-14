-- Risky calls (bulk writes, exports) wait here until a second person decides.
-- The row stores the exact call, already scoped, plus a hash of it: the
-- gateway executes this stored call, never a new one sent by the agent.

CREATE TABLE approval_request (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requester_id      NUMBER        NOT NULL,
    requester_name    VARCHAR2(60)  NOT NULL,
    tool              VARCHAR2(60)  NOT NULL,
    args_json         CLOB          NOT NULL,
    action_hash       CHAR(64)      NOT NULL,
    rule_id           VARCHAR2(80)  NOT NULL,
    reason            VARCHAR2(300) NOT NULL,
    mask_profile      VARCHAR2(40),
    status            VARCHAR2(12)  NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    decided_by        VARCHAR2(60),
    decided_at        TIMESTAMP(6) WITH TIME ZONE,
    decision_note     VARCHAR2(300),
    executed_at       TIMESTAMP(6) WITH TIME ZONE,
    result_json       CLOB,
    CONSTRAINT fk_approval_requester FOREIGN KEY (requester_id) REFERENCES app_user (id),
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REFUSED', 'EXPIRED', 'EXECUTED', 'FAILED')),
    -- Nobody approves their own request.
    CONSTRAINT ck_approval_four_eyes CHECK (decided_by IS NULL OR decided_by <> requester_name)
);

CREATE INDEX ix_approval_status ON approval_request (status, expires_at);
