# Boundaries, permissions and local-demo limits

This is a single-instance portfolio demonstration using deterministic synthetic retail data. It is not a security certification, a production service, a general SQL sandbox or an instruction to connect to a real company database.

## Analytics boundary

All active v1/v2 HTTP routes use `WorkflowController` / `AnalyticsWorkflow`. Authentication and integration controls precede business logic. A planner can return only `QueryPlan` enums, optional allow-listed category/region, a 1–50 limit and a bounded explanation. Strict JSON parsing rejects unknown fields, numeric enum values, trailing values and scalar coercion. There is no raw-SQL input contract.

`BusinessCatalog` owns metric definitions and compiler expressions. The UI and Ollama prompt receive its definitions; `SqlCompiler` chooses fixed fragments and prepared parameters. `SqlSafetyGate` independently recognizes the entire finite SQL grammar, including the repeated grouping expression, fixed table, permitted aggregate expressions, exact filter shapes and parameter count/types. Comments, additional statements, writes, joins, arbitrary columns/functions and unexpected ordering are outside that grammar. Changing the compiler to support something new requires consciously extending the independent gate and tests.

The model can still misunderstand a supported question or suggest an inappropriate allowed plan. Prompt instructions and the heuristic adversarial vocabulary are not universal injection detection. The typed policy/compiler/gate/database path constrains what accepted output can do even when those heuristics miss a novel attack. The fixture tests are regression evidence, not proof against every adversarial prompt.

## Role policies and scopes

`RolePolicy` is checked during creation, clarification, editing, review/approval, execution and result retrieval. FINANCE cannot use RETURN_RATE or STATUS grouping. OPERATIONS cannot use REVENUE/MONTH and must specify HOME/GROCERY plus NORTH/SOUTH; missing filters never mean unrestricted access for that role. ANALYST allows the approved catalog. These are Java-enforced data scopes, not PostgreSQL row-level security policies.

The built-in studio intentionally simulates selectable local roles. Nginx/Vite keep a generated local proxy secret server-side; the secret is not a `VITE_` variable or included in browser bundles. Compose binds ports to loopback. Anyone who can use that local proxy can use its demo role simulation. This is a trusted local convenience, **not account authentication**. Do not expose the demo proxy to another network. Set `DEMO_ENABLED=false` and remove the demo proxy secret/header before any owner-managed non-local deployment.

External integrations authenticate with random 256-bit opaque bearer tokens. PostgreSQL stores only SHA-256 hashes. Credentials carry a stable owner-chosen subject, immutable role, scopes, exact browser origins, expiry and revocation state. Every request rechecks these values. An invalid bearer never falls back to local-demo authorization. An integration's `X-Demo-Role` header has no authority; a supplied `X-QueryFence-Role` must match its credential.

Two independently scoped credentials can intentionally share a subject and role for a client/reviewer pair. They share ownership of that application's plans. The owner must isolate unrelated applications with different subjects. The metadata store enforces plan identity **and role**, so a token for one application cannot retrieve or execute another application's plan. Scopes split planning, review, execution and result reads. MCP client credentials should exclude `query:review`; a separate trusted human review service holds that scope.

## Review and approval lifecycle

The local proxy bypass additionally restricts browser Origin to `DEMO_ORIGINS` (studio/Vite origins by default). An embedded origin allowed by global CORS still needs its own bearer credential; it does not inherit proxy authority. Requests without Origin remain a trusted loopback convenience, which is another reason to disable/remove the proxy bypass before external deployment.

Plans expire after ten minutes. The current plan, identity, role, version, SQL and parameters contribute to a review digest. Saving an edit supersedes the old ID and invalidates its approval. Approval requires `query:review`, `confirmed:true` and the current digest. Only the approval hash is stored. Execution requires `query:execute`, an existing unexpired approved plan, explicit confirmation and the matching single-use token. An atomic conditional UPDATE claims execution and clears the token before the SELECT runs. Concurrent/replayed claims are rejected.

The current compilation and catalog are checked again against the digest; stored plans cannot silently change query meaning across compiler/catalog changes. The cost is re-estimated before execution. A trusted host must obtain actual human consent before calling approve. The server cannot prove that a person read a screen, and a fully privileged dishonest client can falsely assert consent. Scope separation and the absence of an MCP approval tool reduce accidental agent-controlled approval; they do not attest human presence cryptographically.

This is not a distributed exactly-once execution guarantee. If the process dies after the read but before saving results, the approval remains consumed. Startup marks interrupted executions/evaluations failed and does not rerun analytics. New explicit planning/review is necessary. Recovery assumes one API instance; do not run multiple replicas with this startup routine.

## PostgreSQL accounts

| Account | Purpose and permissions |
| --- | --- |
| `analytics` | LOGIN, non-superuser, no database/role creation; schema USAGE + SELECT on `analytics.orders` only; read-only transactions by default; five-second statement timeout |
| `queryfence_control` | Workflow/credentials/evaluation SELECT/INSERT/UPDATE on named control tables; audit SELECT/INSERT only and sequence usage; cannot SELECT retail orders or DELETE audit events |
| Database owner | Flyway migrations and local owner CLI; privileged and never supplied to analytics query execution |

PUBLIC has no analytics schema rights, schema creation or temporary-table grants. JDBC queries use the analytics pool, read-only transactions and a query timeout. EXPLAIN uses the same analytics pool and never includes ANALYZE. Tests turn session read-only off and still verify denied INSERT, UPDATE, DELETE, DROP and CREATE operations. This demonstrates privilege enforcement independent of the JDBC flag.

Persistence writes are explicitly limited to application metadata, credentials, audit and measured evaluations through the separate control/owner paths. Natural-language analytics never authorizes retail writes, scheduled work or external actions. The local API process still receives owner migration credentials. A process compromise could therefore exceed the application query boundary. A real deployment should run migrations as a separate privileged job and remove owner credentials from runtime processes.

## HTTP/integration controls

Global origins and credential origins must both allow a browser caller. Wildcard origins are not configured. Allowed preflights advertise only the expected methods/headers. Server-to-server clients may omit Origin, so CORS is not a replacement for authentication. Requests have a 16 KiB bound enforced by Nginx and by a bounded servlet body read, including unknown content length. Identity and IP fixed-minute buckets return 429 with Retry-After and are bounded in memory. Limits are local to one API process, not a distributed rate limiter.

MCP frames are bounded to 64 KiB. All data tools call the same REST path; discovery/tool schemas alone do not grant data access. Stdio stdout is reserved for JSON-RPC; raw credentials are never emitted. The adapter implements the stated protocol/tools subset, not all optional MCP features. Protocol tests and a live scoped-credential test cover the supported wire behavior.

Widget access tokens are supplied at runtime in memory. The expected role and widget origin settings do not grant authority; the API independently checks both. A hostile/XSS-compromised host can access its own runtime credentials. Use owner-managed TLS, short-lived least-scope tokens, CSP, authentication and browser-origin policies. Never embed permanent tokens in HTML, JavaScript bundles, attributes, URLs or localStorage.

## Audit and privacy

Plans, edits/parent versions, declared planner/model versions, review digest, approval state, execution results/timing and integration decisions persist in PostgreSQL. Audit records include identity, role, safe action/outcome, plan ID, approval state, correlation ID and bounded metadata. They do not contain raw bearer/approval tokens or raw questions. Planner explanations are replaced with Java-generated descriptions before persistence, preventing an explanation from echoing a submitted secret into stored plan text. The local model/host/browser can have independent logs; users must not enter real secrets or personal data.

The control role cannot update/delete audit rows, but the database owner can. Audit is not cryptographically tamper-evident or immutable to administrators. The application returns at most 100 history/audit rows per request and bounds pending-plan admission; persistent records still consume disk. There is no automatic retention/deletion scheduler. Monitor local storage; an owner may explicitly reset the demo volume if its history is no longer needed. Never use this to retain real user/company data.

Errors are safe JSON with timestamps and correlation IDs. ECS request logging includes method/status/time/ID, not Authorization, approval tokens, request bodies or URLs containing user text. `.env`, `.secrets`, dependencies, build outputs and generated current result files are ignored. POSIX credential files use owner-only modes; Windows owners must also restrict directory ACLs. Committed test strings are ephemeral synthetic test credentials, not reusable service keys.

## Remaining deployment limitations

No real user accounts, SSO, tenant provisioning, distributed coordination, network TLS termination, production load testing, threat assessment or security certification is provided. Model version labels can be owner-declared/unverified, local models can fail or misinterpret, and PostgreSQL estimates do not guarantee runtime. Dependency pins and a clean npm audit are not an application security audit. [Integration documentation](INTEGRATIONS.md) describes the supported interfaces and required owner configuration.
