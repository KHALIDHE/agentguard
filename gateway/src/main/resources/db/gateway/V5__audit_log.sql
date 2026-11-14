-- Append-only, hash-chained audit log.
--
-- Every row stores the hash of the previous row. Changing or deleting any
-- old row breaks the chain from that point, and the verification command
-- reports where. AUDIT_HEAD holds the latest sequence number and hash; it is
-- locked while a row is added, so two parallel inserts cannot fork the chain.

CREATE TABLE audit_event (
    seq          NUMBER(19)     PRIMARY KEY,
    event_time   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    event_type   VARCHAR2(20)   NOT NULL,
    request_id   VARCHAR2(64),
    username     VARCHAR2(60)   NOT NULL,
    role         VARCHAR2(20),
    tool         VARCHAR2(60),
    arguments    VARCHAR2(4000),
    decision     VARCHAR2(20)   NOT NULL,
    rule_id      VARCHAR2(80),
    reason       VARCHAR2(500),
    latency_ms   NUMBER(10),
    flags        VARCHAR2(200),
    approval_id  NUMBER(19),
    prev_hash    CHAR(64)       NOT NULL,
    hash         CHAR(64)       NOT NULL
);

CREATE INDEX ix_audit_event_time ON audit_event (event_time);
CREATE INDEX ix_audit_event_user ON audit_event (username, event_time);
CREATE INDEX ix_audit_event_decision ON audit_event (decision, event_time);

CREATE TABLE audit_head (
    id         NUMBER(1)  PRIMARY KEY,
    last_seq   NUMBER(19) NOT NULL,
    last_hash  CHAR(64)   NOT NULL,
    CONSTRAINT ck_audit_head_single_row CHECK (id = 1)
);

INSERT INTO audit_head (id, last_seq, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- First wall: the application cannot update or delete audit rows by mistake.
-- Second wall: anyone who disables this trigger to edit a row is caught by
-- the hash chain.
CREATE OR REPLACE TRIGGER audit_event_append_only
BEFORE UPDATE OR DELETE ON audit_event
BEGIN
    RAISE_APPLICATION_ERROR(-20001, 'audit_event is append-only');
END;
/
