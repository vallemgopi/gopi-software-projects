# Docker verification — 2026-10-21

Later feature update: direct anonymous complaint intake, microphone recording, and its Docker checks are recorded in [public-intake verification](PUBLIC_INTAKE_VERIFICATION.md). Counts below describe the initial Docker acceptance run.

Docker is now installed, running, and verified on this computer. This supersedes the earlier Docker-unavailable notes in historical verification records.

## Results

* Docker Desktop 4.96.0; Linux Engine 29.9.1; Compose v5.6.0 detected.
* `docker compose up --build -d --wait --wait-timeout 300` completed with exit 0. All three application images built successfully, PostgreSQL started, Flyway migrated the new persistent database, and the four services started.
* `docker compose ps`: API, database, and ML running/healthy; web running. The web service has no separate health check; its actual HTTP response and browser workflows passed.
* `docker compose exec -T api java -version`: Temurin **21.0.12.1**. The application is now verified on a Java 21 runtime, not only release-21 compilation under the host JDK.
* `http://localhost:5173` returned HTTP 200 from nginx/1.31.7, confirming the production web image is serving the app. `/actuator/health` returned `UP`; authenticated `/api/health` showed the downloaded speech model available.

## Tests actually run

| Command | Result |
| --- | --- |
| `.\.tools\apache-maven-3.9.11\bin\mvn.cmd -B -q -f api/pom.xml test` | **3 passed, 0 skipped**, exit 0. This includes the previously skipped Testcontainers integration test against real PostgreSQL 17.11 in Docker. It verified authentication, migrations/schema validation, case creation, preserved predictions, review corrections, stale-update rejection, export, and deletion. |
| `..\.venv\Scripts\python.exe -m pytest -q` in `ml` | **17 passed**, exit 0. |
| `npm.cmd test` in `web`, Node 24.22.0 | **1 passed**, exit 0. |
| `.\.venv\Scripts\python.exe scripts/smoke.py` against the Compose API | Passed full real HTTP workflow, including sample WAV upload, no audio retention, correction export, persisted evaluation, metrics, and OpenAPI. |
| `$env:BASE_URL='http://localhost:5173'; npm.cmd run e2e -- --workers=1` in `web` | **7 passed**, exit 0, against production nginx + containerized Java/ML/PostgreSQL. Covers desktop case creation/review/evaluation/report export/delete confirmation, login error, mobile sample audio, and native Tamil/Telugu/Kannada/Malayalam capture/review/delete. |
| Python script piped into `docker compose run --rm --no-deps -T ml python -` | Actual Linux ML image loaded the mounted base model with CPU int8 and rejected silence for ta/te/kn/ml. Temporary test container removed automatically. |
| Real multipart `POST /api/cases/audio`, `provider=whisper`, `language=en`, with existing `.tools/synthetic-english.wav` | HTTP 201 from the Compose deployment. Recognized `Water is leaking at demo lane.`; local provider confirmed; the test-created case was deleted. Audio was generated, not a personal recording. |
| `python scripts/check_artifacts.py` | Passed. Local `.env` is ignored; source secret-pattern scan and synthetic corpus checks passed. The checker was corrected to allow the documented ignored local `.env` without reading or printing its contents. |

Total: **28 automated tests passed, none skipped**, plus successful Docker build/startup, HTTP/speech smoke checks, and production web/health checks. The host and Docker use separate databases; no previous native database was deleted or migrated into Docker. Services remain running for local use.

## Local use and lifecycle

Open http://localhost:5173 on this computer. The local `.env` was created from `.env.example`; its public demo credentials remain `reviewer` / `local-demo-change-me`. This is a local demonstration configuration, not a public deployment. No real credentials were committed.

From the project root:

```powershell
docker compose up -d --wait
docker compose ps
docker compose logs -f api ml
docker compose down
```

`down` preserves the named database volume. Do not remove volumes unless you intend to delete Docker case history. Model files stay in ignored `.models/` and are mounted read-only.

## Remaining limits

Container deployment and the Testcontainers gap are resolved. Spoken Tamil/Telugu/Kannada/Malayalam accuracy, accent coverage, and English code-mixing accuracy remain unmeasured. Four-language browser tests use synthetic text/sample mode; real speech checks cover silence rejection and one generated English utterance. No public hosting was created, and no population-level performance claims follow from these tests.
