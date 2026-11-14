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

## D6. The identity travels in the MCP transport context, not a thread-local
- **Context:** MCP tool handlers do not run on the HTTP request thread, so Spring Security's `SecurityContextHolder` is empty inside them.
- **Choice:** a custom `WebMvcStreamableServerTransportProvider` with a context extractor. While the request is being handled, after the JWT filter, it copies the verified `UserIdentity` into the `McpTransportContext`, and the handler reads it from the exchange.
- **Reason:** the identity is passed explicitly with each call. Nothing depends on which thread runs the handler, and a call without an identity is refused instead of running as nobody.

## D7. A service token between the gateway and the tools
- **Context:** if an agent could reach the hospital tools directly, the gateway would be optional.
- **Choice:** the tools service refuses any MCP request without `Authorization: Bearer <HOSPITAL_SERVICE_TOKEN>`, compared in constant time, and refuses everything when the token is not configured. Only the gateway holds the token.
- **Reason:** the gateway becomes the only door. In production this would be mTLS or a private network as well; the shared token keeps the demo simple and testable.

## D8. Phase 2 republishes the upstream schemas, minus scope parameters
- **Context:** the gateway must offer the same tools as the upstream server.
- **Choice:** at startup the gateway lists the upstream tools, removes every `scope*` parameter, sets `additionalProperties: false`, and publishes the result. Arguments not in the published schema are refused.
- **Reason:** agents can never set the row-scope parameters themselves. This still trusts the upstream for the rest of the schema; phase 4 pins strict schemas inside the gateway (types, ranges, lengths), so a compromised upstream cannot widen what agents may send.

