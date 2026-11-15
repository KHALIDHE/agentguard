#!/usr/bin/env bash
# Run once on the server. Creates the FINANCE and AGENTGUARD database users in the Oracle container
# that already runs for QlinkR Order. Passwords come from deploy/.env.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
sudo docker exec -i deploy-oracle-1 sqlplus -s "sys/${ORACLE_SYS_PASSWORD}@//localhost:1521/FREEPDB1 as sysdba" <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
CREATE USER finance IDENTIFIED BY "${FINANCE_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO finance;
CREATE USER agentguard IDENTIFIED BY "${AGENTGUARD_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER TO agentguard;
SQL
echo "Database users created"
