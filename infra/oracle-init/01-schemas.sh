#!/usr/bin/env bash
# Runs once, on the first start of the Oracle container (gvenzl images source
# every *.sh in /container-entrypoint-initdb.d after the database is created).
# Two schemas, two users: a bug in one service cannot read the other's tables.
set -euo pipefail

sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER finance IDENTIFIED BY "${FINANCE_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO finance;

CREATE USER agentguard IDENTIFIED BY "${AGENTGUARD_DB_PASSWORD}" QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW, CREATE TRIGGER TO agentguard;
SQL
