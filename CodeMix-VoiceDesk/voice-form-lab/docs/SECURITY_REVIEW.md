# GitHub publication review — 2026-10-21

Status: source prepared for review and publication; no GitHub upload performed. This review does not guarantee zero vulnerabilities or certify public deployment. The Twilio token previously pasted into chat must be rotated by its owner; rotation was not verified.

## Files and data

Expanded Git exclusions at both workspace and application level for environment variants/backups, private-key formats, tools, downloaded models, databases, logs and generated outputs. Each Docker build context now excludes environment files and common private-key formats. Existing local credentials and database contents were preserved.

`scripts/prepare_github.py` scans distributable files for credential patterns and non-demo private environment values copied into source. It builds a source-only ZIP with `.env.example` and synthetic corpus, excluding actual `.env`, runtime model downloads, synthetic audio test fixture, private storage and local tool caches. Two temporary-fixture tests verify exclusions and reject a copied secret without printing its value. These checks cannot recognize all personal data or unknown credential formats.

No `.git` repository was found in the project/workspace and Git is not installed on PATH or in the checked standard installation locations. Existing remote history and staging therefore were not inspected. If uploading to an existing repository, review its history separately. Nothing was uploaded or committed by this review.

## Dependency audit

| Area | Result |
| --- | --- |
| Frontend `npm audit --json` | 0 known advisories at execution |
| Core Python `pip-audit` | 30 pinned packages; 0 known advisories |
| Optional speech Python `pip-audit` | 46 pinned packages; 0 known advisories |
| Java Maven coordinates queried through OSV | After fixes: 149 packages; 1 package with 2 remaining advisories |

Patched Jackson to 2.21.8, Tomcat to 10.1.61, PostgreSQL JDBC to 42.7.14, Commons Lang to 3.21.0, Commons Compress to 1.28.0, HttpClient to 5.6.5, HttpCore to 5.4.4, and Log4j BOM to 2.26.1. Versions were checked against Maven Central and exercised by Java integration tests. Earlier audit findings affected 11 packages; the remaining findings are Spring MVC 6.2.19:

* [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8): SSE view-fragment stream corruption. This application uses JSON REST controllers and does not implement SSE view fragments.
* [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg): XsltView path limitation. This application has no XsltView, XSLT view resolver, or catch-all view-rendering controller.

These dependency findings remain in the scanner; they were not suppressed. No patched 6.2 release was available in Maven Central at review time. A future compatible patch or a separately tested Spring Boot 4 / Spring 7 migration is required to remove them. Their documented triggering features are absent in the current code, but that is not a general proof of non-exploitability. Recheck before adding view rendering/SSE or deploying publicly.

OSV and pip audit use public package names/versions; application secrets and complaints were not submitted. Database advisory coverage is time-sensitive; zero reported findings is not proof of zero vulnerabilities. Container operating-system packages and GitHub account configuration were not audited.

## API/browser review and changes

Staff endpoints require authentication and case queries are scoped to the current owner. Public intake returns a receipt without cases/predictions, validates consent and uploads, and has concurrency/rate backstops. Telephone callbacks validate signatures and account identifiers, ignore supplied media URLs, and enforce consent/idempotency. Audio retention is disabled by default. JPA/JDBC queries use parameters; generic error responses avoid logging request bodies/secrets.

Added explicit foreign browser Origin rejection for mutations, alongside configured CORS and explicit in-memory Authorization headers. Added CSP, clickjacking prevention, nosniff and referrer/permissions headers. Blob media remains allowed for local recording preview. Staff password guessing still needs perimeter throttling before internet deployment. HTTP Basic is appropriate only over HTTPS outside loopback testing.

Workflow token permissions are read-only; added source-distribution checks to CI and weekly Dependabot configuration. Existing GitHub account secret scanning/push protection could not be verified without a target repository.

## Commands and validation

* `.tools/apache-maven-3.9.11/bin/mvn.cmd -B -q -f api/pom.xml test dependency:tree "-DoutputType=json" "-DoutputFile=../.tools/java-dependencies.json"`: 22 Java tests passed, including PostgreSQL Testcontainers and browser-origin checks. Host JVM was Java 25; Docker application uses Java 21.
* `.venv/Scripts/python.exe -m unittest discover -s scripts -p test_prepare_github.py`: 2 passed.
* `.venv/Scripts/python.exe -m pytest ml -q`: 17 passed.
* `npm.cmd test` in web: 2 passed.
* `npm.cmd audit --json` in web; isolated `pip-audit` against both Python lock files; `.venv/Scripts/python.exe scripts/audit_java.py`: results above. Java audit intentionally returns failure while unsuppressed advisories remain.
* `docker compose up --build -d --wait --wait-timeout 300`: rebuild validates Java 21 and frontend production bundles; final state checked after patched dependencies.

An initial Maven dependency export needed quoted PowerShell arguments. An initial CSP browser run blocked the test's fetch of a locally generated blob; permitting blob connections supports recording-preview verification. Final browser/API verification results are recorded below after rerunning.

Final verification: all four Docker services healthy, `npm.cmd run e2e` passed all 12 workflows, `scripts/smoke.py` and `scripts/public_smoke.py` passed against the patched stack. Additional live HTTP checks confirmed 401 for anonymous private endpoints, 403 for a foreign-Origin mutation, and CSP/nosniff headers on the UI. Total automated tests: 22 Java, 17 ML, 2 frontend, 12 browser, 2 release-scanner tests. Final source-only archive contents were checked to exclude private/runtime files. The archive contains source and synthetic documentation screenshots, not `.env` or database records.
