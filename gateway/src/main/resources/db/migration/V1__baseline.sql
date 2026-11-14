-- AgentGuard schema baseline. Policy, approval and audit tables arrive in
-- the phases that implement them. This table proves Flyway ran.

CREATE TABLE app_meta (
    meta_key    VARCHAR2(64)  NOT NULL,
    meta_value  VARCHAR2(255) NOT NULL,
    updated_at  TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_app_meta PRIMARY KEY (meta_key)
);

INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema', 'agentguard');
