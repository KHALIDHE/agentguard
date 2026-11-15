-- The humans an agent can act for. Every MCP call to the gateway carries a
-- token issued to one of these users.

CREATE TABLE app_user (
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username           VARCHAR2(60)  NOT NULL,
    password_hash      VARCHAR2(100) NOT NULL,
    role               VARCHAR2(20)  NOT NULL,
    department         VARCHAR2(16),
    finance_employee_id  NUMBER,
    active             BOOLEAN       DEFAULT TRUE NOT NULL,
    created_at         TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('FINANCE_MANAGER', 'ACCOUNTANT', 'CLERK', 'HR', 'ADMIN')),
    -- Finance roles always work in a department; the policy engine relies on it.
    CONSTRAINT ck_app_user_department CHECK (
        role IN ('HR', 'ADMIN') OR department IS NOT NULL)
);
