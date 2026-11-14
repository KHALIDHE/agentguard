# AgentGuard

A security gateway between AI agents and company tools. Every tool call an agent makes goes through AgentGuard. The gateway allows it, denies it, masks the data, or holds it for a human to approve, and it records every decision in a tamper-evident log.

Core principle: **never trust the model.** Permissions are enforced in code, outside the model.

Stack: Java 21, Spring Boot 4.1, Spring AI 2.0 (MCP), Oracle Database Free 23ai, React.

Status: design phase. See [docs/DESIGN.md](docs/DESIGN.md).
