# Decisions

One entry per decision: context, choice, reason.

## D1. Oracle Database instead of PostgreSQL
- **Context:** the first draft used PostgreSQL. The project should show how the gateway behaves on an enterprise database with real per-schema grants and a built-in alternative for tamper-evident storage.
- **Choice:** Oracle Database Free 23ai in Docker (`gvenzl/oracle-free`), with Testcontainers `oracle-free` in tests.
- **Reason:** it lets us show real Oracle skills: identity columns, `FETCH FIRST`, `SELECT … FOR UPDATE`, grants per schema, and blockchain tables as a comparison point. The cost is a bigger image and slower startup than PostgreSQL.

## D2. Spring Boot 4.1.2 + Spring AI 2.0.2
- **Context:** Spring Boot 4.2 and Spring AI 2.1 are still release candidates (checked 2026-11-14).
- **Choice:** the latest GA versions, Boot 4.1.2 and Spring AI 2.0.2, with the MCP SDK version left to the Spring AI BOM.
- **Reason:** the build must be reproducible: the same commit has to build the same way next month. RC versions can change their APIs.

## D3. Spring MVC + virtual threads, not WebFlux
- **Context:** the gateway mostly waits on I/O (upstream MCP calls, database).
- **Choice:** blocking Spring MVC with Java 21 virtual threads enabled.
- **Reason:** we get almost the same concurrency as reactive code, while the code stays simple and easy to read and explain. The rejected alternative was WebFlux, which is harder to debug and to explain line by line.

## D4. Pipeline as Chain of Responsibility, rule conditions as Specification
- **Context:** a tool call goes through about ten independent checks.
- **Choice:** one `GatewayFilter` class per check, run in order, and each can stop the chain.
- **Reason:** each check can be tested alone, and adding a check doesn't touch the others. The rejected alternative was one service with nested `if` statements.

## D5. The gateway executes approved calls itself
- **Context:** an approval must not be reusable for a different action.
- **Choice:** store the exact call with its hash, and on approval the gateway runs it once.
- **Reason:** the agent never gets a reusable "approval token", so there's nothing to replay or tamper with.
