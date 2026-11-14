# Demo script

Each step is marked with the phase from which it works.

| # | Step | Works from |
|---|---|---|
| 1 | As a doctor, ask the agent about one of your patients. It answers normally. | phase 8 |
| 2 | As a nurse, ask for staff salaries. Denied, with a clear reason. | phase 3 |
| 3 | As a nurse, ask for a patient of another department. Nothing returned. | phase 4 |
| 4 | Ask the agent to summarise document 19 ("Quarterly report draft"), which hides an instruction to export all patients. The export does not happen. | phase 7 |
| 5 | As a receptionist, ask to cancel all appointments for tomorrow. It waits in the approval queue; refuse it in the dashboard. | phase 5 (API), phase 9 (dashboard) |
| 6 | Open the audit log. Run the chain verification, change one record by hand, run it again and watch it fail. | phase 6 |
| 7 | Show the evaluation report. | phase 10 |

## Available now (phase 6)

1. Start Oracle, the hospital tools and the gateway (see the README).
2. Log in as `nurse.salma` and connect an MCP client to `http://localhost:8080/mcp` with the token.
3. `tools/list` shows the 10 hospital tools. None of them has a `scopeDepartment` or `scopeDoctorId` parameter.
4. Call `get_staff_directory` with `{"department": "CARDIO"}`: the data comes back through the gateway.
5. Call `search_patients` with `{"scopeDepartment": "NEURO"}`: refused, the agent cannot set the scope itself.
6. Connect without a token, or with a token signed by another key: refused before any tool runs.
7. Call `http://localhost:8081/mcp` directly: 401, only the gateway holds the service token.
8. As `nurse.salma`, call `get_staff_salaries`: refused with `Denied by policy salaries-everyone-else: Salary data is restricted to HR.`
9. As `hr.imane`, call `get_staff_salaries`: the salaries come back.
10. As `reception.omar`, call `cancel_appointments` for tomorrow: the agent gets `PENDING_APPROVAL: request N ...`. Nothing is cancelled yet. `GET /admin/approvals?status=PENDING` as `admin.youssef` shows it; `POST /admin/approvals/N/refuse` refuses it, and `get_approval_status {"approvalId": N}` now says `REFUSED`.
11. As `admin.youssef`, `GET /admin/policies` lists the 14 rules in evaluation order.
12. As `nurse.salma` (CARDIO), call `get_patient_record` for a NEURO patient: `{"found": false}`. The department filter is in the SQL.
13. Same nurse, a CARDIO patient: no `ADDRESS`, no `DIAGNOSIS`, `NATIONAL_ID` shown as `******42`. As `dr.amrani`, the same patient comes back in full.
14. As `admin.youssef`, `run_query` with `SELECT salary_mad FROM staff`: `SQL refused: column SALARY_MAD is not allowed`. `DELETE FROM patient`: `only SELECT is allowed`.
15. As `admin.youssef`, `GET /admin/audit?limit=20`: every call above is there, with its rule and reason.
16. `GET /admin/audit/verify`: `chain intact`. Then, in SQL as the gateway's database user:
    `ALTER TRIGGER audit_event_append_only DISABLE; UPDATE audit_event SET reason = 'nothing happened' WHERE seq = 3; COMMIT;`
    and `GET /admin/audit/verify` again: `row 3 was modified after it was written`.
