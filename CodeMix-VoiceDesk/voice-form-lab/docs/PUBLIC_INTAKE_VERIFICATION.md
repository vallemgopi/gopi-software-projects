# Public intake verification — 2026-10-21

Implemented and deployed locally to the running Docker stack. Original `src/Main.java` and the user's `.env` credentials were not changed. Existing cases and correction revisions were preserved; Flyway V2 added source/consent/language metadata with existing rows defaulted to `STAFF`.

## Commands actually run

| Command | Result |
| --- | --- |
| `.tools\apache-maven-3.9.11\bin\mvn.cmd -B -q -f api/pom.xml test` | **6 tests passed, no skips**: three real PostgreSQL Testcontainers integration tests, two case-service tests, one public-rate/concurrency test. Covers consent, honeypot, private receipts, anonymous read/delete denial, staff-only queue access, enforced real audio provider, original corrections, and stale saves. |
| `npm.cmd test` in `web`, Node 24.22.0 | **2 tests passed**: confidence indicator and WAV byte structure/sample clipping. |
| `npm.cmd run build` in `web` | TypeScript and Vite production build passed. |
| `docker compose up --build -d --wait --wait-timeout 300` | Build/startup passed. API, database, ML healthy; nginx web running. Java API built with Java 21. Existing named volume retained and migrated. |
| `BASE_URL=http://localhost:5173 npm.cmd run e2e -- --workers=1` (PowerShell environment assignment, `web`) | **11 browser tests passed** against production Docker. Seven existing workflows plus anonymous mobile submission → receipt → staff review/delete; invalid/silent public upload; microphone denial; real browser fake-device recording → 16 kHz PCM WAV preview. No API mocks. The permission-error test intentionally simulates browser rejection. |
| `.venv\Scripts\python.exe scripts/smoke.py` | Existing authenticated HTTP workflow passed. |
| HTTPX `/v3/api-docs` and route-access assertions | Public OpenAPI operations have empty authentication requirements; config is anonymous, case listing is protected. |
| `.venv\Scripts\python.exe scripts/public_smoke.py --synthetic-audio .tools/synthetic-english.wav` | Public text and real audio receipts passed; no model output leaked; staff saw PUBLIC/NEEDS_REVIEW, language and consent timestamp; unauthenticated read/delete returned 401; test-created cases deleted. Synthetic English recognized as `Water is leaking at demo lane.`. |
| `python scripts/check_artifacts.py` | Passed source/known-secret-pattern and synthetic-corpus checks; ignored local `.env` values not read or printed. |

**19 automated tests passed in this change**, plus HTTP/audio smoke and build checks. The ML service was unchanged; its earlier 17-test result remains in the historical language/Docker records and is not counted as a new run here. The browser recording test uses synthetic browser audio and verifies conversion/preview, not Tamil/Telugu/Kannada/Malayalam spoken accuracy. The successful public speech request uses generated English audio, not personal data.

Screenshots inspected: [mobile form](public-complaint.png), [receipt](public-receipt.png). Receipt references in screenshots are synthetic test-case references, and those cases were deleted.

## Live local links

* Public, no staff login: http://localhost:5173/complaint
* Staff review portal: http://localhost:5173

Anonymous intake can be disabled with `PUBLIC_INTAKE_ENABLED=false` and API recreation. The public form is write-only and returns a receipt; no public complaint/status listing was added.

## What is not deployed/tested

No internet hosting, domain, HTTPS ingress, public tunnel, paid service, or phone outside this computer was configured. The user has not supplied hosting access or a domain; localhost is not shareable with other phones. A reachable HTTPS origin is required for that next deployment step. Real Safari/Android microphone behavior, South Indian speech accuracy, distributed abuse protection, and GitHub Actions execution remain outside this local verification. See [public intake setup](PUBLIC_INTAKE.md).
