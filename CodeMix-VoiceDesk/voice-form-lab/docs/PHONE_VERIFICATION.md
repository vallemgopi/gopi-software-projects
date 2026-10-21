# Phone integration verification — 2026-10-21

Executed from the project folder on Windows. Local tools were used where unavailable on PATH.

| Command | Result |
| --- | --- |
| `.tools/apache-maven-3.9.11/bin/mvn.cmd -B -q -f api/pom.xml test` | 20 passed, zero failures/errors/skips; PostgreSQL Testcontainers |
| `docker compose up --build -d --wait --wait-timeout 300` | Exit 0; database, ML, API, web healthy; Flyway V3 applied |
| `npm.cmd test` (web, bundled Node 24 on PATH) | 2 passed |
| `.venv/Scripts/python.exe -m pytest ml -q` | 17 passed |
| `npm.cmd run build` (web) | TypeScript and Vite production build passed |
| `npm.cmd run e2e` (web, `BASE_URL=http://localhost:5173`) | 12 passed, including phone desk, public intake, recording, review, evaluation and four native-script workflows |
| `.venv/Scripts/python.exe scripts/smoke.py` | Passed authentication, CRUD, immutable predictions, review, export, sample audio, evaluation, metrics and OpenAPI checks |
| `.venv/Scripts/python.exe scripts/public_smoke.py` | Passed consent, private receipt, ownership and cleanup checks |
| `.venv/Scripts/python.exe scripts/check_artifacts.py` | 109 text artifacts checked at execution; no known secret patterns, local .env ignored, corpus labeled synthetic. Heuristic only. |

Initial browser testing caught a JSON/XML content negotiation regression introduced by the SDK's XML dependency. Explicit JSON content type fixed it; a real local HTTP transport regression test and the full browser rerun passed. An initial retry test had a Mockito restubbing error; corrected before the successful full Java run. Build output includes nonfatal deprecated JVM, duplicate test JSON classpath, npm configuration and terminal-color warnings.

Phone integration tests cover signed synthetic callbacks, all seven menu options, decline without recording, forged requests, durable queue retry/recovery, immutable intake identity, callback replay after case deletion, owner-only access and provider cleanup. Provider recording downloads and ML calls are mocked in these phone tests; they make no live provider requests. Existing browser/API sample-mode flows use the real running local services.

No Twilio credentials, live number, public HTTPS origin, real telephone call, provider media retrieval/deletion, or localized prompt recordings were available for live verification. Phone intake remains disabled. The local app runs at http://localhost:5173; staff can inspect **Phone intake**. Activation steps and remaining live checks are in [PHONE_INTAKE.md](PHONE_INTAKE.md).
