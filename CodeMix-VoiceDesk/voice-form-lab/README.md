# Code-Mixed Voice Form Reliability Lab

Before uploading to GitHub, read [SECURITY.md](SECURITY.md) and the [security review and remaining advisories](docs/SECURITY_REVIEW.md). Generate a source-only archive with `python scripts/prepare_github.py --zip`; keep `.env` and database/model storage private. Previously exposed credentials require rotation.

Telephone intake integration is included, with consent, language selection, recorded complaints, retry processing, and a private staff queue. **No live telephone number is connected.** See [phone activation and limits](docs/PHONE_INTAKE.md) and [phone verification](docs/PHONE_VERIFICATION.md). Default prompts are English; localized recordings require configuration.

Direct intake: people can use [/complaint](http://localhost:5173/complaint) without a staff login to write, record, or upload a complaint for human review. See [public intake and hosting limits](docs/PUBLIC_INTAKE.md). The current address is local to this computer; internet hosting is not configured.

Docker deployment is verified: all four services run locally and Java 21 runtime checks passed. See [public-intake verification](docs/PUBLIC_INTAKE_VERIFICATION.md) for the latest tests and [initial Docker verification](docs/DOCKER_VERIFICATION.md) for the earlier baseline. Speech-accuracy and internet-hosting limits remain documented.

A local reliability and evaluation application for Tamil, Telugu, Kannada, Malayalam, English, and Romanized Hindi/English issue descriptions. Users capture text or a short WAV, inspect extracted fields and evidence, and save human corrections separately from immutable predictions. This application does not submit cases to any institution. See [South Indian language setup and measured limits](docs/SOUTH_INDIAN_SETUP.md) for real local speech setup, current coverage, and testing details.

## Architecture

React + TypeScript + Vite → Spring Boot 3.5 / Java 21 → PostgreSQL 17 (Flyway migrations). The Java API calls a private FastAPI service for transcription, CPU text classification, rule extraction, and evaluation. Controllers, validated DTOs, services, repositories, domain entities, and centralized error handling are separate layers.

The small character TF-IDF + logistic regression category classifier trains deterministically on 96 **synthetic** examples at service startup. Location/date/identifier extraction uses explicit patterns; summary is the first sentence. Text/sample modes need no pretrained downloads, API keys, or GPU. Real speech uses a separate, explicit local model download and CPU int8 inference. Confidence scores are **uncalibrated** probabilities or heuristic scores, not accuracy guarantees. Fields below 0.8 or missing are labeled **Needs review**. All fields still require reviewer confirmation.

## Quick start with Docker

Install Docker Engine/Desktop with Compose, then in this project folder:

```sh
cp .env.example .env
# Edit .env: change DB_PASSWORD and DEMO_PASSWORD (12+ characters).
docker compose up --build -d --wait
```

Open http://localhost:5173. Username: `reviewer`; password: the `DEMO_PASSWORD` you configured. The public example value is `local-demo-change-me`, for local demonstration only. No real credentials are bundled. Changing the demo password and restarting the API updates its BCrypt hash in the database. Changing PostgreSQL's initial password after creating its volume requires an explicit database password change; changing .env alone does not change an existing database password.

Create a case → **Use synthetic sample transcript** → **Extract & review** → inspect evidence → edit fields → confirm every field → **Save reviewed case**. In Evaluation, run the corpus and export JSON. Dashboard values come from the API; no fabricated dashboard statistics are seeded.

For sample audio mode, upload `ml/data/synthetic-silence.wav`, choose **Sample transcript**, and enter/use a sample transcript. This WAV contains generated silence. **Sample mode does not recognize audio**; it returns the supplied transcript and unavailable speech confidence. This is labeled in both API metadata and UI.

```sh
docker compose logs -f api ml
docker compose down
```

`down` preserves database data. Delete a case through the UI only after its confirmation dialog. Explicitly removing the database volume deletes all stored cases.

## Native development and checks

Prerequisites: Java 21+, Maven 3.9+, Python 3.11/3.12, Node 24 LTS (24.15.0 or newer), PostgreSQL 17. Java compiles with release 21. The frontend proxies `/api` to port 8080; Compose uses nginx for the same route.

```sh
python -m venv .venv
# Activate .venv (Windows: .venv\Scripts\Activate.ps1)
pip install -r ml/requirements.lock
cd ml
uvicorn app:app --host 127.0.0.1 --port 8000 --no-access-log
```

Create a PostgreSQL database `voicelab`. Export `DB_USER`, `DB_PASSWORD`, `DEMO_USER`, `DEMO_PASSWORD`, and optionally `DATABASE_URL` (`jdbc:postgresql://localhost:5432/voicelab`) into the API process environment. Native Java does not automatically read `.env`.

```sh
mvn -f api/pom.xml spring-boot:run
cd web
npm ci
npm run dev
```

In separate terminals:

```sh
mvn -B -f api/pom.xml verify
cd ml
pytest -q
python evaluate.py --dataset data/evaluation-v2.json --output computed-report.json
cd ../web
npm test
npm run build
npx playwright install chromium
# Start the real services first. Set DEMO_PASSWORD if changed from the example.
npm run e2e
```

`python scripts/smoke.py` from the project root exercises the real running HTTP services, including sample WAV upload, immutable predictions, correction export, stale saves, and deletion.

Java integration tests use Testcontainers with real PostgreSQL and skip when Docker is unavailable; unit tests still run. The Playwright workflow uses real API calls: create, correct, review, evaluate, and delete. GitHub Actions runs Java, ML, frontend, and a Compose browser smoke test. When making this folder its own repository, `.github/workflows/checks.yml` is ready at its root.

## API and security

Swagger: http://localhost:8080/swagger-ui/index.html. OpenAPI JSON: `/v3/api-docs`. ML docs are `/docs` on the internal ML service; for native development http://localhost:8000/docs.

All `/api` endpoints require HTTP Basic credentials. The UI keeps the Authorization value only in memory, sends explicit headers, omits browser credentials, and does not persist passwords. Spring uses stateless authentication, BCrypt hashes, no session cookies, and a JSON 401 without a browser Basic challenge. CSRF is disabled for this header-authenticated API. Compose binds published ports to loopback. Use HTTPS, rate limits, account provisioning, and an appropriate production identity provider before network deployment. CORS allows one configured origin, never a wildcard.

| Method | Route | Behavior |
| --- | --- | --- |
| GET | `/api/me` | Validate login |
| POST / GET | `/api/cases` | Create draft from `{transcript}` / list owned cases, `?search=` supported |
| GET / DELETE | `/api/cases/{id}` | Read / delete owned case |
| PUT | `/api/cases/{id}` | Save correction revision (same body as review) |
| POST | `/api/cases/{id}/review` | Save `{transcript, fields, version}` after review |
| POST | `/api/cases/audio` | Multipart `file`, `provider=whisper|sample|vosk`, `language=auto|ta|te|kn|ml|en|hi`, `sampleText` for sample mode |
| POST | `/api/evaluations` | Run bundled labeled dataset, persist timestamped report |
| GET | `/api/evaluations/latest` | Latest user's computed report, or `{}` |
| GET | `/api/metrics` | Case counts, daily capture trend, latest corrections by field |
| GET | `/api/exports/corrections` | Original predictions + all reviewer label revisions |
| GET | `/api/health` | Authenticated API + ML health |
| GET | `/actuator/health` | Public API/database health |

```sh
curl -u "reviewer:$DEMO_PASSWORD" http://localhost:8080/api/cases \
  -H 'Content-Type: application/json' \
  -d '{"transcript":"Paani nahi aa raha at Demo Colony."}'
curl -u "reviewer:$DEMO_PASSWORD" -X POST http://localhost:8080/api/evaluations
curl -u "reviewer:$DEMO_PASSWORD" http://localhost:8080/api/exports/corrections -o labels.json
```

Review `fields` must contain exactly `category`, `summary`, `location`, `datetime`, `identifier`. Blank values explicitly confirm missing information. Category is blank or water/electricity/roads/sanitation. Use the latest `version`; stale saves receive 409. Case IDs are scoped to their creator. Drafts are persisted as `NEEDS_REVIEW`; saving a review changes status to `REVIEWED` without changing original text or predictions. Each revision records corrected transcript, values, reviewer, and time. Minimal create/review/delete audit events retain no transcript/audio and survive case deletion.

Uploads: maximum 5 MiB and 60 seconds, WAV extension + RIFF/WAVE magic + decoder validation, uncompressed PCM only. Java and ML both check input; nginx caps requests. Filenames never determine storage paths. By default audio exists only in memory during inference and is not stored. `RETAIN_AUDIO=true` explicitly stores original WAV bytes in PostgreSQL; deleting the case removes them. No secrets, transcript contents, filenames, or raw audio are logged. Never reuse real identifiers in demonstrations.

## Evaluation method

All bundled examples, labels, locations, identifiers, and audio are synthetic. `generate_corpus.py` reproduces the original 32 training and 12 evaluation examples. `ml/generate_south_corpus.py` adds 64 training and 32 evaluation fixtures for native/Romanized Tamil, Telugu, Kannada, and Malayalam with English cues. The default combined corpus is `evaluation-v2.json` (44 examples), with per-language reports. The translations are generated and not native-speaker validated. Training and test sentences are separate but share vocabulary and templates: this is functional regression data, not evidence of deployment generalization.

`ml/computed-report.json` is generated by executing the evaluation, not a performance claim. Each run records dataset version/hash, model version, seed 42, and counts. Dependency versions are pinned in `ml/requirements.lock` and `web/package-lock.json`.

* WER = total word edit distance / total reference words, after NFC normalization and case folding; Unicode letters, numbers, and combining marks stay within words. Punctuation is ignored. Empty-reference corpora yield null WER. The bundled observed transcripts simulate recognition errors; no actual ASR accuracy is measured by this fixture.
* Exact match is case sensitive per field, including null/null matches. Non-missing reference accuracy is also reported to expose inflated scores from missing values. Summary reference uses the first sentence; exact match is a strict string metric, not semantic quality.
* Category macro precision/recall/F1 average the four supported issue classes; an abstention (`missing`) contributes a false negative for its true class. Confusion matrices include missing as a fifth row/column. Per-example errors include language and field. No confidence calibration or speed/impact claim is made.
* The keyword baseline looks for English category words. Model and baseline share deterministic extraction rules for the other fields; the comparison isolates the category classifier, not an entire generative extraction system.

### Import consented, de-identified data

Use the exact schema shown in `ml/data/eval.json`: top-level `version`, `synthetic`, and nonempty `examples`; each example has unique `id`, `synthetic`, `language`, `reference_transcript`, `observed_transcript`, and all five `fields` (string or null). Category is one of the four supported labels or null. Remove names/identifiers or replace consistently with consented pseudonyms. Record consent/provenance outside this application; imports do not assert consent. Do not mix training and test data.

```sh
cd ml
python evaluate.py --dataset /path/to/deidentified.json --output imported-report.json
```

The CLI validates the schema and duplicate IDs. The authenticated web API deliberately runs only the bundled, versioned dataset; external imports are offline. Correction export is a richer revision format: select the intended reviewer revision, de-identify both transcripts and spans, then map fields to this evaluation schema. It must not be fed back into the holdout set as training data.

## Optional speech recognition

The `SpeechProvider` interface includes real local faster-whisper CPU int8 recognition, sample transcripts, and optional Vosk. For the recommended multilingual provider, follow [South Indian speech setup](docs/SOUTH_INDIAN_SETUP.md): install `ml/requirements-speech.lock`, run the explicit model downloader, and set `WHISPER_MODEL_PATH`. The UI provides Tamil/Telugu/Kannada/Malayalam/English/Hindi or auto detection. Recognition uses transcription, not translation; code mixing and accents remain unvalidated. Vosk still requires a separate `vosk` installation and `VOSK_MODEL_PATH`; it requires mono 16-bit 16 kHz PCM WAV and remains untested. No model weights are committed. The synthetic corpus's simulated WER is not real ASR accuracy.

## Novel contribution and limits

The contribution is this project's transparent synthetic corpus, versioned evaluation method, reproducible comparison, preserved correction labels, and inspectable error analysis. This is **not** a claim that no similar tool exists or that the idea is globally unique. The small template-driven corpus cannot establish population-level accuracy, fairness, robustness to accents, or user impact. Four South Indian languages now have generated native/Romanized text fixtures, not validated spoken benchmarks. Hindi script, Tulu, Kodava, Konkani, and other regional languages are not represented in the corpus. Dates are extracted strings, not inferred relative dates; ambiguous or absent facts require review. Evidence spans refer to original model input even when a human changes the transcript. Rule scores are heuristic. Models can make confident errors, so every case requires human review.

This is a local research build: list/export operations are unpaginated, there is no organizational role model, and data retention/consent management beyond opt-in audio storage is not automated. No external submissions are implemented.

## Screenshots

Placeholder: add approved screenshots of Dashboard, field review, and evaluation after running locally. Only synthetic data should appear. A browser capture may be supplied under `docs/` after verification.

Verified browser captures using synthetic data: [dashboard](docs/dashboard.png), [field review](docs/review.png), [evaluation](docs/evaluation.png), and [mobile review](docs/mobile-review.png).

## Troubleshooting

* API fails startup: verify DB is ready, DB credentials are correct, and demo password has at least 12 characters. Flyway owns schema creation; Hibernate only validates.
* 503 during extraction: inspect ML health and `ML_URL`; the ML service needs its locked Python dependencies.
* 413/415/422 upload error: use a real PCM WAV, under 5 MiB/60 seconds. Sample mode requires an explicit transcript; optional Vosk requires installation and a model.
* 401: check the local environment password and sign in again after restart. No credentials are stored in localStorage.
* Browser API errors: use the Vite/nginx proxy, or configure the exact `CORS_ORIGIN`.
* Testcontainers skipped: start Docker and rerun Maven. Native tests alone do not verify Compose.

See `VERIFICATION.md` for the exact checks executed in the implementation environment and any remaining acceptance limitations.

Setup references: [Spring Boot Java compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc compatibility](https://springdoc.org/), [Vite requirements](https://vite.dev/guide/), [FastAPI containers](https://fastapi.tiangolo.com/deployment/docker/), [scikit-learn logistic regression](https://scikit-learn.org/stable/modules/generated/sklearn.linear_model.LogisticRegression.html).
