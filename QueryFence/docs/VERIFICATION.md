# Checks actually performed

## Current v2 extension verification — 2026-10-21

The existing application was extended in place. The original root IntelliJ files and database volume were preserved. Flyway added persistent workflow/audit/credential/evaluation tables and version metadata. The earlier in-memory lifecycle remains only as a test-scope regression fixture, not in the production JAR; active v1/v2 routes share the persistent policy boundary.

| Check actually run | Outcome |
| --- | --- |
| Maven `clean verify` | **37 tests passed, zero failures/errors/skips**; Java release 21; executable API 2.0.0 JAR built |
| Real PostgreSQL tests | Passed original read-only tests and nine HTTP workflow tests: approval/replay, expiry, clarification/edit invalidation, credentials/scopes/origins/body limit, control-role isolation, stale compilation, concurrent database execution claims and OpenAPI authorization contracts |
| Frontend tests | **15 passed** across original workflow/design regressions plus clarification, catalog, editing, blocked estimate, row-cited summary, role switch and measured baseline comparison |
| Frontend build | TypeScript/Vite passed on host and in Docker |
| Standalone integrations | TypeScript build and **9 tests passed with the live test enabled**: five MCP protocol tests, three widget tests and one real MCP/Java/PostgreSQL scoped-client/reviewer test |
| Compose | Configuration validated; all images built; API/PostgreSQL healthy; frontend served the extended application |
| `node scripts/smoke-v2.mjs` | Passed catalog/auth boundary, clarification, edited SQL, cost estimate, explicit approval, replay/role/adversarial rejection, stored summary/history/audit, generated OpenAPI and measured baseline workflow |
| Actual cost blocking | Temporarily configured cost budget 0.01; a real PostgreSQL EXPLAIN returned BLOCKED, approval was denied; original budget restored |
| Restart persistence | Stored results, row citations and EXECUTED history survived two API/container restarts |
| Java client example | Compiled and ran against the real API; explicit APPROVE reached execution and stored summary |
| TypeScript client example | Built and ran against the real API; explicit APPROVE reached execution and stored summary |
| Widget host/CORS | Served actual host/module/stylesheet assets; allowed authenticated origin and preflight passed; blocked origin rejected |
| Secret exposure checks | Generated database/proxy secrets were absent from built browser assets; private values remain in ignored environment/credential files |
| Database permissions | Live analytics login has only SELECT on orders; control has no retail access and audit has INSERT/SELECT only |

The example checks exposed Windows-specific piped-input issues (Node readline shutdown and a PowerShell UTF-8 BOM). Both were corrected, and both examples subsequently completed the real approved workflow. No secrets were printed to diagnose them.

The default integration suite deliberately **skips its one live test** unless both credential-file variables are supplied. The recorded nine-test run explicitly supplied them; it did not silently skip. Test-only mock scores are isolated in frontend fixtures and are not published measurements.

Commands used from `queryfence/` (IntelliJ Maven path is this machine's installation):

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/valle/IdeaProjects/QueryFence/queryfence/.tools/m2' -B -f api/pom.xml clean verify
cd web
npm.cmd test
npm.cmd run build
cd ../integrations
$env:QUERYFENCE_LIVE_CLIENT_FILE='C:/Users/valle/IdeaProjects/QueryFence/queryfence/.secrets/mcp-client.json'
$env:QUERYFENCE_LIVE_REVIEWER_FILE='C:/Users/valle/IdeaProjects/QueryFence/queryfence/.secrets/mcp-reviewer.json'
npm.cmd test
cd ..
docker compose config --quiet
docker compose up --build -d
node scripts/smoke-v2.mjs
```

This checkout uses frontend port **3000**, direct API **18080** and PostgreSQL **5432**. The unrelated service on 8080 remains untouched. The local widget-origin example was verified at 8085; it is optional and not part of Compose startup.

### Actual v2 measurement

`evaluation/baseline-demo-retail-v2.json` is the first saved measured snapshot, not a target. `evaluation/results-v2-demo.json` is an ignored latest-run report. One actual live run at **2026-10-21T15:27:39.565191032Z** recorded:

| Metric | Actual numerator / denominator |
| --- | --- |
| Exact six-field plan match | 52 / 52 supported questions |
| Correct clarification | 8 / 8 clarification questions |
| Unsupported rejection | 6 / 6 questions |
| Adversarial rejection | 12 / 12 questions |
| Role-policy rejection | 6 / 6 questions |
| Invalid-plan rejection | 6 / 6 typed boundary fixtures |
| SQL validation | 52 / 52 supported plans |
| Unsafe SQL rejection | 6 / 6 altered SQL fixtures |

Provenance: dataset `retail-questions-2.0`, catalog `retail-semantics-2.0`, planner `demo`, model **Rules-based (no model)**, model version **rules-2.0.0**. Mean planning/policy latency was **0.26866 ms**, median **0.31142 ms**, nearest-rank p95 **0.56104 ms**. Those are one machine/run's measurements, not query runtime or performance improvements. The fixture is intentionally grammar-aligned and synthetic; these results do not establish general natural-language accuracy, security certification or real-world business impact.

The live smoke runner saved a completed run as database baseline and ran another actual evaluation. The dashboard compares stored measured runs with matching planner/model/model-version/dataset labels. It never accepts uploaded scores. See [EVALUATION.md](EVALUATION.md).

### Unavailable / explicitly unclaimed

- **Real Ollama inference:** no installed/configured local Ollama executable/model was available. Provider mock HTTP tests passed. To run later, install/configure Ollama per README, set model version provenance, recreate API and use Run benchmark in the dashboard. No Ollama score is claimed.
- **Visual browser review:** this session exposed no browser. React DOM/widget tests, production builds and actual host/asset/API checks passed; screenshot/mobile visual inspection is not claimed. Open http://localhost:3000 and optionally the documented widget host to inspect visually.
- **Git commit audit:** the original workspace is not a Git repository. No commits were created. Private files/build outputs are ignored and known-secret/bundle scans were performed; no claim about nonexistent tracked history is made.
- **Named commercial platform compatibility:** no closed third-party platform was connected. REST/MCP/component contracts were tested locally; each real host still needs its own supported interface, permissions and owner configuration.

The sections below are historical verification from the original implementation and earlier design follow-ups; their smaller test counts refer to those earlier stages.

## Dashboard redesign follow-up

Portfolio navigation follow-up: Swagger moved from the main navigation into an expandable **Developer resources** section with an explanation of its purpose and same-origin proxy. The main navigation now links to **How it works**. All nine frontend tests and the production build passed after this change; the added test covers documentation discoverability and navigation separation.

The dashboard was redesigned with navy top navigation, an ivory workspace, orange actions, serif headline typography, a three-step workflow indicator, responsive cards and a dark SQL/guardrails treatment. External font requests were removed. A relative-result-bar bug was corrected: values 100, 25 and 0 now produce widths of 100%, 25% and 0%.

Follow-up verification: Maven `verify` passed all 16 tests with no skips, including real PostgreSQL integration and the 62-question benchmark. Frontend tests passed all eight cases, including new coverage for keyboard navigation, the accessible character count, clearing confirmation when the question changes, and proportional result bars. TypeScript/Vite and the Docker frontend production build passed. Browser visual inspection remains unavailable because this session exposes no browsers; responsive styling has not been visually certified.

Verified on 2026-10-21 in Windows PowerShell. Host: Java 25.0.1 compiling with Java release 21, Node 24.13.0, npm 11.6.2, Docker Engine 29.9.1. Docker API image uses Java 21. Maven was found in IntelliJ's bundled installation; it was not on PATH. Initial sandbox restrictions blocked Maven downloads, esbuild's parent-directory reads and Docker engine access; those checks were successfully rerun with the necessary tool permissions.

| Check | Actual outcome |
| --- | --- |
| Maven `verify` | Passed; 16 tests, zero failures/errors/skips; executable Spring Boot JAR built |
| Real PostgreSQL Testcontainers test | Passed on PostgreSQL 17.6; 1,000 deterministic rows, exact amount sum, region count, all metric/dimension combinations, and denied write/DDL queries after disabling the session read-only flag |
| Frontend `npm test` | Passed; five tests covering submission, review, explicit confirmation, displayed results, loading, planner failure, empty results and execution failure |
| Frontend `npm run build` | Passed TypeScript and Vite production build |
| `npm audit` during final dependency installation | Zero reported advisories after pinning Vite 6.4.5 and Vitest 4.1.11; this is not a full application security audit |
| `docker compose config --quiet` | Passed |
| `docker compose up --build -d` | API, web and PostgreSQL images built and services started; PostgreSQL and API healthy |
| Live `scripts/smoke.mjs` | Passed real plan API, false-confirmation rejection, real SELECT results, duplicate rejection, unknown request field rejection, history, frontend HTML, frontend API proxy and OpenAPI schema |
| Live database role inspection | Analytics has table SELECT only, no superuser/database-create/role-create flags; `default_transaction_read_only=on`, `statement_timeout=5s`, `search_path=analytics` |
| Structured request logging | Live ECS JSON contains requestId, method, status and elapsedMs; regression test prevents duplicate requestId fields and verifies MDC cleanup |
| Source credential scan | No real keys/private keys/password literals found; environment variable reference and explicitly ephemeral integration-test credential reviewed |

The existing service on host port 8080 was preserved. This checkout's generated, gitignored `.env` sets `API_PORT=18080`. Frontend: http://localhost:3000; direct API: http://localhost:18080/api/v1/health; same-origin Swagger: http://localhost:3000/swagger-ui/index.html. Default fresh setup remains port 8080 unless changed in `.env`.

Actual commands used (from `queryfence/`; Maven path may differ on another installation):

```powershell
& 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/valle/IdeaProjects/QueryFence/queryfence/.tools/m2' -B -f api/pom.xml verify
cd web
npm.cmd test
npm.cmd run build
cd ..
docker compose config --quiet
docker compose up --build -d
$env:API_URL='http://localhost:18080/api/v1'
node scripts/smoke.mjs
```

## Measured rules benchmark

The benchmark was executed through `BenchmarkTest` as part of Maven verification. A run on this machine at `2026-10-21T13:35:21.731818Z` recorded:

| Measure | Result |
| --- | --- |
| Exact plan fields | 52 / 52 supported questions |
| SQL compilation | 52 / 52 supported questions |
| Out-of-scope rejection | 6 / 6 questions |
| Ambiguous rejection | 4 / 4 questions |
| Unsafe/invalid plan rejection | 6 / 6 boundary fixtures |
| Rules planning + validation latency | Mean 0.6432 ms; median 0.2273 ms; nearest-rank p95 0.4095 ms |

These are local measurements on a small, intentionally grammar-aligned synthetic fixture. They do not establish general natural-language accuracy, model quality, SQL execution performance, production security or business impact. Mean can exceed p95 because of isolated cold-call outliers. Generated detailed reports live in gitignored `evaluation/results-demo.json`; reruns overwrite timing measurements. See [methodology and denominators](EVALUATION.md).

## Checks not performed

- **Live Ollama inference/benchmark:** no Ollama executable or configured local model was available in this environment. Mock HTTP tests exercised parsing, invalid JSON, unsupported fields and provider HTTP failures. To test a real model, follow README setup, then run `mvn -f api/pom.xml -Dtest=BenchmarkTest -Dbenchmark.planner=ollama -Dollama.model=qwen2.5:3b test` and use the browser workflow in Ollama mode. No Ollama accuracy score is claimed.
- **Visual browser inspection:** the computer-use browser inventory was empty and the in-app browser was unavailable. Automated React DOM tests, the production frontend build and live HTML/proxy checks passed. Open http://localhost:3000 in a desktop/mobile browser to inspect layout and keyboard behavior; no screenshot review is claimed.
- **Git commit audit:** the original workspace has no Git repository, so no commits or tracked-file claims were made. Project `.gitignore` excludes `.env`, build output, dependency caches and measured result files. No Git repository was initialized and no commits were created.

## Dashboard simplification — 2026-10-21

The question is the main entry point. Plan review appears after planning; SQL details, activity, semantic definitions, guardrails, evaluation, and developer documentation use expandable sections. Explicit approval, policy blocking, role switching, and real API results remain in the workflow. The responsive layout uses a single column, compact navigation, and teal controls.

Checks run after this UI change: 17 frontend tests passed; TypeScript/Vite production build passed; frontend Docker image rebuilt and deployed; live v2 API smoke passed (including the measured 84-question rules fixture and saved baseline); Compose configuration validation passed; HTTP verified the updated bundle on port 3000. Java source was unchanged, so Java tests were not rerun for this UI change. No browser rendering inspection was available.
