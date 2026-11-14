-- A copy of the loaded policy, for the dashboard's policy viewer and so every
-- audit record can point to the rule that decided. policies.yml stays the
-- source of truth; this table is rewritten at each startup.

CREATE TABLE policy_rule (
    id            VARCHAR2(80)  PRIMARY KEY,
    priority      NUMBER(6)     NOT NULL,
    roles         VARCHAR2(200) NOT NULL,
    tools         VARCHAR2(400) NOT NULL,
    when_condition VARCHAR2(1000) NOT NULL,
    decision      VARCHAR2(20)  NOT NULL,
    row_scope     VARCHAR2(30)  NOT NULL,
    mask_profile  VARCHAR2(40),
    reason        VARCHAR2(300) NOT NULL,
    loaded_at     TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_policy_rule_decision CHECK (decision IN ('ALLOW', 'DENY', 'MASK', 'REQUIRE_APPROVAL'))
);
