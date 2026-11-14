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
- **Reason:** agents can never set the row-scope parameters themselves. This still trusted the upstream for the rest of the schema; D10 replaced it.

## D9. Policies as data: first match wins, deny by default
- **Context:** permissions must be readable by a non-developer, testable as a table, and impossible to forget.
- **Choice:** rules live in `policies.yml` (role, tool, optional condition, decision, scope, mask profile, reason). The engine evaluates them by priority; the first match decides; no match means DENY with the rule id `default-deny`. Conditions are small Specification objects (`ArgumentIn`, `ArgumentGreaterThan`, `AllOf`, `AnyOf`, `Not`) built from the YAML. The parser refuses unknown keys, roles, tools, decisions and mask profiles, so a typo stops the gateway at startup.
- **Reason:** first-match is how firewalls work and is easy to reason about (one rule decides, and the audit can name it). The rejected alternative, combining all matching rules ("most restrictive wins"), is harder to explain and to test row by row. `PolicyMatrixTest` checks all 50 role and tool pairs, so any policy edit shows up as a diff in that table.

## D10. The gateway owns the tool schemas
- **Context:** D8 republished upstream schemas. A compromised or careless upstream could then add a parameter, and agents would be invited to use it.
- **Choice:** `ToolCatalog` defines every tool's parameters with types, ranges, allowed values and lengths. That schema is what agents see and what is validated. Upstream tools missing from the catalogue stay hidden.
- **Reason:** what an agent may send is decided by the gateway, not by the system it protects. Validation also runs before the policy, because policy conditions read arguments.

## D11. Masking uses allow-lists where it can, and fails closed
- **Context:** results come back as JSON rows from the upstream.
- **Choice:** a mask profile either keeps only listed fields (reception) or removes and partly hides listed ones (nurse). If a result is not JSON, or a part is not text, the whole result is withheld. `structuredContent` is dropped from masked results.
- **Reason:** an allow-list hides any column added upstream later. Withholding on doubt is the only safe failure: returning the raw result would bypass the policy.

## D12. run_query is parsed, restricted and limited
- **Context:** free SQL is the most dangerous tool an agent can hold.
- **Choice:** JSqlParser must find exactly one SELECT. Tables, columns and functions come from allow-lists that exclude names, identifiers, contact data, diagnoses and salaries. Comments, `SELECT *`, `FOR UPDATE`, `INTO` and database links are refused. The accepted query is wrapped in `SELECT * FROM (...) FETCH FIRST n ROWS ONLY`.
- **Reason:** text filters are bypassed by case, comments and spacing; a parse tree is not. The tool also stays behind ADMIN-only policy, and the upstream runs it in an Oracle read-only transaction, so three independent walls must fail before data leaks or changes.
- **Known limit:** the column check is by name, across the tables a query uses. Two allowed tables sharing a column name cannot be told apart, which is acceptable here because the allow-lists hold no sensitive columns.

## D13. Approval as a compare-and-set UPDATE, execution by the gateway
- **Context:** an approval must be single-use, bound to one exact action, and safe when two approvers act at once.
- **Choice:** the scoped call is stored with a SHA-256 of (requester, tool, canonical arguments). Deciding is one `UPDATE ... WHERE status = 'PENDING' AND expires_at > now AND requester_name <> approver`; whoever changes the row wins, and everyone else gets 409. After approval the gateway executes the stored call once, re-checking the hash first. A CHECK constraint also forbids `decided_by = requester_name`. Pending requests expire after `agentguard.approvals.timeout` (10 minutes by default) and count as refused.
- **Reason:** the agent never holds anything it could replay with different arguments. The rejected alternative, a one-time approval token returned to the agent, would move the trust back to the client. A single conditional UPDATE is atomic in Oracle without holding a lock between a read and a write.

