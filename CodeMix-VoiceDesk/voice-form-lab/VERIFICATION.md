# Verification record

Docker is now available and its deployment/integration checks passed. See [current Docker verification](docs/DOCKER_VERIFICATION.md). The initial unavailability notes below are historical.

Historical initial-build checks follow below. The current South Indian language phase uses a new model/corpus; see [latest verification](docs/SOUTH_INDIAN_VERIFICATION.md) and [setup/coverage](docs/SOUTH_INDIAN_SETUP.md). Original numeric results below refer to v1 only.

Executed on 2026-10-21 on Windows. The pre-existing `src/Main.java` and IntelliJ files were left intact; all project work is in `voice-form-lab/`.

## Environment

* Java 25.0.1 was installed. Maven compiled the application with **Java release 21**; Docker targets Temurin 21. Java 21 runtime execution itself was not available locally.
* Maven 3.9.11 was downloaded into ignored `.tools/` because Maven was absent from PATH.
* Python 3.11, isolated `.venv`; package versions are recorded in `ml/requirements.lock`.
* Node 24.22.0 LTS was downloaded into ignored `.tools/`. The original Node 24.13.0 was below the installed jsdom engine requirement, so final checks used 24.22.0.
* Temporary PostgreSQL 17.6 binaries from the Zonky Maven artifact were extracted under ignored `.tools/` and bound to `127.0.0.1:55432`. This isolated acceptance database uses trust authentication on loopback only; it is not the Compose configuration. Compose requires the configured database password and does not publish a database port.
* Docker was not installed or on PATH. Its usual Program Files executable location was also absent.

## Commands and final results

Commands below were run from the project root unless noted. PowerShell executable prefixes were used to select the isolated runtimes; these are the actual final check commands, not claims about unexecuted CI.

| Command | Result |
| --- | --- |
| `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -B -q -f api/pom.xml clean verify` | Exit 0. Java API packaged; **2 JUnit/Mockito unit tests passed**. **1 Testcontainers integration test skipped** because Docker is unavailable. |
| `.\.venv\Scripts\python.exe -m pytest -q` (in `ml`) | Exit 0, **6 tests passed**: missing-field abstention, evidence offsets, deterministic evaluation/denominators, invalid input, explicit sample mode, audio size/duration limits. One dependency deprecation warning about Starlette's httpx TestClient integration. |
| `.\.venv\Scripts\python.exe ml/evaluate.py --dataset ml/data/eval.json --output ml/computed-report.json` | Exit 0. Validated import schema and generated the bundled report. |
| `npm.cmd ci` (in `web`, Node 24.22.0 on PATH) | Exit 0. Locked dependencies installed; npm reported 0 known vulnerabilities at execution time. |
| `npm.cmd test` (in `web`) | Exit 0, **1 React Testing Library/Vitest test passed**. |
| `npm.cmd run build` (in `web`) | Exit 0. TypeScript check and Vite production bundle succeeded. |
| `npx.cmd playwright install chromium` (in `web`) | Exit 0. Browser dependencies installed. |
| `$env:BASE_URL='http://localhost:5173'; npm.cmd run e2e -- --workers=1` (in `web`) | Exit 0, **3 real browser tests passed**: desktop text → correction → evaluation → report download → canceled/confirmed delete; accessible authentication failure; mobile sample-WAV → labeled demo output → review → delete. No API mocks. Cleanup targets each test's own case ID. |
| `.\.venv\Scripts\python.exe scripts/smoke.py` | Exit 0. Real HTTP authentication, validation, creation, immutable predictions, correction revisions, stale update 409, export, deletion/404, sample WAV, audio not retained, computed/stored evaluation, metrics, and OpenAPI all passed. |
| `python scripts/check_artifacts.py` | Exit 0. No `.env` in distributable files; no known secret patterns found; corpus explicitly synthetic. This is a heuristic scan, not a guarantee of detecting every possible secret or personal datum. No real personal data or credentials were intentionally added; example passwords are public local fixtures. No Git commit was created. |
| `docker compose up --build -d --wait` | **Could not execute**: PowerShell reported `docker` is not recognized. Compose images, container networking, health checks, and the Docker-backed integration test remain unverified here. |

## Native end-to-end startup used

Temporary database initialization and launch:

```powershell
.tools/postgres/bin/initdb.exe -D .tools/pgdata -U lab -A trust --encoding=UTF8 --locale=C
.tools/postgres/bin/pg_ctl.exe -D .tools/pgdata -l .tools/postgres.log -o '-p 55432 -h 127.0.0.1' -w start
```

The minimized PostgreSQL binary archive did not include `createdb.exe`; that command failed, so the temporary acceptance run used the existing `postgres` database instead. The normal documented database remains `voicelab`.

```powershell
# ML, separate terminal
cd ml
..\.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8000 --no-access-log

# API, from project root, separate terminal
$env:DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/postgres'
$env:DB_USER='lab'
$env:DB_PASSWORD='local-test-only'
$env:DEMO_PASSWORD='local-demo-change-me'
java -jar api/target/voice-form-api-1.0.0.jar

# UI, separate terminal with Node 24.22.0 selected
cd web
npm.cmd run dev
```

Flyway migrated a real empty PostgreSQL schema. Hibernate validated it successfully. Native application preview: http://localhost:5173, username `reviewer`, public test password `local-demo-change-me`. This preview uses the temporary database, not Compose.

## Computed synthetic results

See `ml/computed-report.json` for full precision/recall, exact matches, per-example errors, hash, and confusion matrices.

* Holdout: **12 synthetic examples**, separate from **32 synthetic training examples**.
* Simulated WER: **1 / 92 = 0.0108695652**. This measures the bundled simulated transcript substitution, not an actual speech recognizer.
* Model category macro F1: **0.830952381**; keyword baseline macro F1: **0.125**.
* Model exact match: category **10/12**, summary **11/12**, location **11/12**, datetime **12/12**, identifier **12/12**. Missing-value agreement is included; the report separately exposes non-missing-reference accuracy.

No timing, population accuracy, fairness, or user-impact claim follows from these fixtures. Optional Vosk recognition was not installed or tested. The bundled audio contains generated silence, not a real speaker.

## Failures found and resolved during verification

Initial frontend compilation found a generic return-type error and missing Vite CSS declarations; both were fixed and the final build passed. An initial browser launch ran before Chromium finished installing and received EBUSY; installation completed and later runs passed. Rebuilding a running JAR and reinstalling native npm modules while Vite was running caused Windows file-lock failures; services were stopped before the clean rebuild/install, and final checks passed. A browser assertion initially selected both the loading and success announcements; its locator was narrowed to the success text, and the final suite passed. These failed attempts are not counted as successful verification.

## Remaining acceptance limits

1. Run `docker compose up --build -d --wait`, `mvn -f api/pom.xml verify` with Docker available, and the browser workflow against Compose to validate the supplied deployment and Testcontainers path. Docker is the external blocker; the same application workflow passed against native real services.
2. Optional Vosk model installation, code-mixed speech accuracy, and a Java 21 runtime execution remain untested. The core sample/text modes require neither Vosk nor a paid service.
3. GitHub Actions is supplied but was not run on GitHub in this environment. Screenshots under `docs/` were generated by successful native browser tests and inspected for desktop/mobile layout.
