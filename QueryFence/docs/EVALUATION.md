# Evaluation and regression methodology

## Primary versioned fixture

`evaluation/benchmark-v2.json` has dataset version **retail-questions-2.0** and 84 labeled questions: 52 expected plans, 8 clarification cases, 6 unsupported questions, 12 adversarial questions and 6 role-policy rejections. `evaluation/adversarial-v1.json` versions the adversarial subset independently. `generate-v2.mjs` constructs these fixtures without calling planner code. A semantic change to labels/questions/boundary fixtures should receive a new dataset version and reviewed baseline.

`EvaluationRunner` calls the configured planner, question/clarification logic, Bean Validation, role policy, SQL compiler and independent SQL gate. It does not run analytics, EXPLAIN or assume a PostgreSQL cost. This isolates plan/policy regressions from database execution. Real database and HTTP workflow tests cover execution, estimation, ownership, audit and approval separately.

The runner records dataset/catalog/application versions, planner name, configured model/tag, declared model version, measurement timestamp, sample counts, per-question expected/observed status and latency. Rules mode is labeled `Rules-based (no model)` / `rules-2.0.0`. Ollama uses its configured tag and optional owner-declared `OLLAMA_MODEL_VERSION`; unverified means no known immutable weights version. QueryFence does not verify a declared digest against loaded weights. Do not treat two mutable tags as proof of identical models.

## Metrics and denominators

| Metric | Calculation |
| --- | --- |
| Plan-field accuracy | All six fields match exactly / 52 supported questions; explanation excluded |
| Clarification rate | Correctly returned clarification / 8 questions labeled clarification |
| Unsupported rejection | Explicit unsupported rejection / 6 unsupported questions |
| Unsafe-question rejection | Explicit question-policy/unsupported rejection / 12 adversarial questions |
| Unsafe-plan rejection | Validator/compiler/gate rejects / 6 independently supplied invalid typed JSON fixtures |
| Role-policy rejection | Role policy rejects / 6 role-labeled questions |
| SQL validation success | Accepted supported plans that compile and pass gate / 52 supported questions |
| Unsafe-SQL rejection | Independent grammar rejects / 6 altered SQL structures |
| Latency | Mean, median and nearest-rank p95 of sequential question analysis + provider + validation/policy/compiler/gate, milliseconds |

Clarification rate is recall on the clarification subset, not the fraction of all questions receiving clarification. Per-question outcomes expose unexpected clarification on supported questions. Unsafe-plan fixtures include unknown metric, injected filter, excessive/zero limits, oversized explanation and unknown SQL field. SQL fixtures include additional statements, unknown table/column, writes and unsupported/comment/order structures. The Java tests add finer-grained parameter, expression and concurrency cases.

Provider/network/parsing errors are recorded as provider errors, **not counted as successful semantic/unsafe rejections**. This prevents an offline model from earning rejection credit. Inspect provider error counts, supported-plan accuracy and individual outcomes together. The runner does not assert a target accuracy; it calculates what current code produced. A test substitutes a deliberately broken provider and verifies its score falls, guarding against baked-in results.

## Reproduce and save a baseline

Start the application, open Evaluation & regression, choose Run benchmark, wait for completion, then Save this measured run as baseline. Run again to compare. The UI compares only matching dataset, planner, model and declared model version. It labels percentages, latency, sample count and provenance. Baseline selection accepts an owned completed run ID only; callers cannot upload arbitrary scores through the API.

REST:

1. POST `/api/v2/evaluations` with `evaluation:run` → 202 and ID.
2. Poll GET `/api/v2/evaluations` with `evaluation:read` until COMPLETED or FAILED.
3. POST `/api/v2/evaluations/{id}/baseline` to select the measured baseline.
4. Run again and compare the reports. A unique database index bounds active baseline selection per identity/model/version/dataset.

One bounded background evaluation job runs at a time. Local model inference can take several minutes; no fixed latency promise is made. Startup marks interrupted jobs failed, never invents a completed report. A job's model calls may warm/cold-load differently; no warm-up, repetitions or statistical confidence intervals are claimed.

`node scripts/smoke-v2.mjs` exercises the local-demo HTTP path and, in rules mode, runs two real evaluations, selects a database baseline, writes ignored `evaluation/results-v2-demo.json`, and creates `evaluation/baseline-demo-retail-v2.json` only if absent. The versioned snapshot is from an actual run and is review evidence, not a hard-coded score in the app. It does not automatically populate a fresh database's dashboard. The dashboard uses persisted completed runs; create/save a new local baseline through the UI.

The original `evaluation/benchmark.json` / `BenchmarkTest` has 62 legacy planner cases and remains available with `mvn -f api/pom.xml -Dtest=BenchmarkTest test`. It predates clarification and is not the primary v2 policy benchmark. Do not mix its denominators/scores with v2.

## Limits

This is a small templated, intentionally grammar-aligned synthetic fixture. High rules-mode scores do not establish broad natural-language understanding, production security, robustness to arbitrary prompts, statistical model superiority, runtime improvements or business impact. No Ollama accuracy score is claimed unless that model was actually evaluated. Boundary fixtures are finite regression samples, not a completeness proof. Catalog, role, parser, model, compiler and fixture changes can affect comparability; report those changes rather than presenting a single context-free percentage.

Current executed checks, measured sample counts, saved snapshot provenance and unavailable checks are recorded in [VERIFICATION.md](VERIFICATION.md). Keep new measurements separate from unrun expectations and test-only mock scores.
