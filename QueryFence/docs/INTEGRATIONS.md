# Integration contract and platform compatibility

QueryFence exposes supported interfaces for owner-configured hosts. It does not discover or connect itself to every platform. Closed platforms require their own supported API, plugin/connector, MCP support or embedded-script capability, appropriate permissions and configuration by the platform owner.

## REST contract

Use `/api/v2`. Live OpenAPI at `/v3/api-docs` documents request DTOs, clarification/review union, typed plan fields, costs, approval, execution/results and safe errors. `docs/openapi-v2.json` is an exported snapshot; regenerate it after contract changes. v1 analytics aliases share the v2 authorization/policy path. A former `confirmed:true` execution without approval is intentionally rejected.

An integration credential has an owner-assigned stable subject, fixed role, scopes, origins, expiry and revocation state. Different credentials with the **same subject and role** intentionally share plan ownership; use distinct subjects for unrelated hosts. Never place a credential directly in a natural-language question, plan field or URL. Required scopes are listed in README. Expired/revoked/malformed credentials yield 401; scopes/role/origins yield 403. Not-owned plans yield 404 rather than revealing their contents. Body size/rate limits and correlation IDs apply to all data operations.

Flow: discover schema → create plan → resolve offered clarification if needed → review → optionally edit and review the new ID → explicit digest approval → consume its approval token once → retrieve results. BLOCKED plans cannot be approved. Editing, expiry, changed compilation and consumption invalidate earlier approvals. Result reads/summaries do not execute another analytics query.

Errors are JSON with `timestamp`, `code`, `message`, `requestId`; `X-Request-ID` is also returned. Do not blindly retry execution after ambiguous transport failure. Retrieve the existing plan/results first, then decide whether a fresh user-reviewed plan is needed. The result can have been stored even if the caller lost the response. Never manufacture a new approval token or use raw SQL as a recovery mechanism.

The local demo proxy is an explicitly trusted loopback convenience. External integrations must call the direct API with scoped bearer credentials. Before exposing a service outside the local machine, disable/remove the demo bypass and supply owner-managed TLS, user authentication and authorization. This repository does not certify that deployment work.

## Owner credential lifecycle

`scripts/credentials.mjs` talks to the local database owner CLI over Docker, not a remotely exposed credential-admin endpoint. It generates 256 random bits, stores only SHA-256 in the credentials table and writes the raw token once to a private ignored file. It validates role, scopes, exact HTTP(S) origins, name/subject and expiry (up to 720 hours). Prefer short expiry for examples. Revoke by ID; subsequent requests recheck the database. Protect the `.secrets` directory and do not commit/copy token files into frontend assets or container build contexts.

For a client/reviewer pair, issue different files with the same subject and role. The MCP client needs planning/execution/results scopes; the trusted reviewer additionally has `query:review`. Neither file belongs in model prompts. The backend approval protocol cannot independently attest physical human attention; obtaining consent is a trusted-host responsibility.

## MCP protocol surface

The separate Node/TypeScript adapter implements the tools subset of the [official 2026-07-28 stdio specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio), [versioning rules](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning) and [tool messages](https://modelcontextprotocol.io/specification/2026-07-28/server/tools). Modern requests put required protocol version and client capabilities in `params._meta`; responses include `resultType: "complete"`. `server/discover` advertises supported versions and tool capability. Unknown versions return -32022 with the supported list, invalid params -32602, unknown method -32601, invalid request -32600 and malformed JSON -32700. Application/tool failures use `isError:true`. Tools are deterministically ordered and do not advertise dynamic subscriptions.

Legacy clients explicitly initialize revision 2025-11-25. No support for other revisions, HTTP/SSE, resources, prompts, sampling, elicitation, tasks or general MCP certification is claimed. Advertised capabilities remain limited to implemented tools. Frames are newline-delimited UTF-8 JSON, capped at 64 KiB. Cancellation aborts the corresponding HTTP request and suppresses its result. Stdout contains JSON-RPC only; safe configuration errors go to stderr.

Tools: `get_schema`, `create_query_plan`, `clarify_query_plan`, `get_query_plan`, `execute_approved_plan`, `get_query_results`. Tool argument schemas reject extra fields such as SQL. IDs must be UUIDs. The execute tool accepts an existing approval token and true confirmation; it cannot create approvals and no approval tool is exposed. Every data action goes back through the authenticated Java API. Tool annotations are documentation, not authorization.

Protocol tests spawn the real stdio child process against an HTTP test server and cover discovery, metadata/version errors, legacy initialization, schema validation, no approval endpoint, replay rejection, cancellation and credential redaction. The opt-in live test exercises the real Java/PostgreSQL service with separately scoped client/reviewer credentials.

## Embedded component

Build `integrations/` and load the generated widget module. The host calls `configure` with API URL, light/dark theme, expected role, allowed host origins and `getAccessToken`. It supplies/refreshed short-lived credentials at runtime. No permanent token attribute, localStorage key or browser bundle secret is used. The backend enforces the real role and scope; the widget's expected role is only an additional consistency check.

The widget renders question, clarification, plan/SQL/parameters, shared catalog meaning, estimate, explicit approval and returned data. Dynamic text is placed with DOM `textContent`, not executable HTML. Main-studio editing/evaluation is not duplicated inside the small widget; the TypeScript client's methods allow a host to implement those REST flows when needed.

`examples/widget-host.html` and its external module form a working local host after building, issuing a credential for http://localhost:8085 and configuring global CORS. `node integrations/scripts/serve-example.mjs` serves only the built integration directory on loopback. The example password field accepts an owner-issued short-lived token into memory; a real host should obtain one from its own authenticated backend. That backend endpoint/user session is platform-specific and is not supplied automatically by QueryFence.

CSP must allow the widget module/stylesheet sources and API `connect-src`; use response headers with appropriate `frame-ancestors` in a real host. The example's local CSP permits only its own scripts/styles and the two documented loopback API ports. Configure API global origins **and** credential origins with exact scheme/host/port. A non-browser caller can omit/spoof Origin, so bearer authentication remains essential. An XSS-compromised host can access its own runtime tokens; CORS/Shadow DOM do not solve that threat.

## Other hosts

Java and TypeScript examples execute the full schema/plan/human-confirmation/results flow without paid services. The generic webhook JSON is a template for platforms that can make HTTP requests; its owner must bind a runtime secret and handle clarification/review. Do not wire a natural-language webhook directly to approval/execution without human consent. No external webhook is sent by QueryFence itself.

Supported protocol/interface behavior is tested locally. Actual compatibility with a named commercial platform requires testing that platform's supported extension mechanism, permissions and configuration; the presence of a REST URL alone is not such a test.
