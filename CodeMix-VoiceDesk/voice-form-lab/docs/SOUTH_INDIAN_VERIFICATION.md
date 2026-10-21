# South Indian phase verification — 2026-10-21

Update: the later [Docker verification](DOCKER_VERIFICATION.md) resolves the Docker/Testcontainers/Java-21-runtime gaps recorded below. Speech-accuracy limits still apply.

This supplements the historical initial `VERIFICATION.md`; the default model/corpus are now v2-south. No original case predictions or correction history were migrated or overwritten.

Executed checks:

| Command (project root unless specified) | Observed result |
| --- | --- |
| `.venv\Scripts\python.exe ml/generate_south_corpus.py` | Generated versioned synthetic South Indian train/eval and combined corpus. |
| `.venv\Scripts\python.exe -m pip install -r ml/requirements-speech.txt` | Installed faster-whisper 1.2.1 and compatible CPU runtime; exact environment captured in `ml/requirements-speech.lock`. |
| `.venv\Scripts\python.exe ml/download_speech_model.py --model base` | Downloaded public base revision `ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66`; weights 145,217,532 bytes. Model remains ignored under `.models/`. |
| `.venv\Scripts\python.exe -m pytest -q` (in `ml`, executable selected as `..\.venv\Scripts\python.exe`) | 17 tests passed, covering the original cases plus four scripts, combining marks, per-language reports, native field labels, missing model/language validation, and a mocked provider contract. |
| `.venv\Scripts\python.exe ml/evaluate.py --output ml/computed-report.json` | Computed default v2 report: 44 synthetic examples, 96 synthetic training examples; one simulated edit / 551 reference words. Category macro F1: model 0.8392857143, baseline 0.0416666667. These describe template fixtures, not speech quality. |
| `.tools\apache-maven-3.9.11\bin\mvn.cmd -B -q -f api/pom.xml clean verify` | Exit 0; two Java unit tests passed, one Testcontainers integration test skipped because Docker remains unavailable. |
| `npm.cmd test` and `npm.cmd run build` (in `web`, Node 24.22.0) | Vitest test passed; TypeScript and production bundle passed. |
| `.venv\Scripts\python.exe scripts/speech_smoke.py` | Actual local model loaded with CPU int8; silence rejected for ta/te/kn/ml. This tests runtime behavior, not spoken recognition accuracy. |
| Windows `System.Speech.Synthesis.SpeechSynthesizer` generated `.tools/synthetic-english.wav`; real `/api/cases/audio` request with `provider=whisper`, `language=en` | HTTP 201. Synthetic reference: `Water is leaking at Demo Lane.`; recognized: `Water is leaking at demo lane.`. Returned local model revision and word scores; case then deleted. No real personal recording used. This one synthetic utterance is a smoke check, not a speech benchmark. |
| `.venv\Scripts\python.exe scripts/smoke.py` | Full real HTTP flow passed with the updated corpus and API. |
| `BASE_URL=http://localhost:5173 npm.cmd run e2e -- --workers=1` (PowerShell environment assignment, in `web`) | Seven real browser workflows passed: original three plus native Tamil, Telugu, Kannada, Malayalam sample capture/review/delete. Cleanup targets each created case ID. |
| Python `yaml.safe_load` of `compose.yml` | YAML parsed, all four services present. This does not verify Docker image builds, startup, networking, or health checks. |

The initial mobile test timed out on an exact nested-label lookup after adding provider options. It was changed to an accessible combobox-role selector; all seven browser tests then passed.

Docker check: `Get-Command docker` returned nothing; `C:\Program Files\Docker\Docker\resources\bin\docker.exe` was absent; `wsl --list --quiet` reported WSL is not installed. Therefore the user's reported Docker setup is not available to this session, and Compose/Testcontainers cannot yet be verified. No system installation or public deployment was performed.

Important limits: no spoken Tamil/Telugu/Kannada/Malayalam or accent/code-mixing accuracy was measured; synthetic text translations are not native-speaker validated; Tulu/Kodava/Konkani are not implemented. Native-script field extraction covers explicit labels and English location cues, not unrestricted local grammar. Use human review and obtain consented, de-identified audio before a real speech benchmark. Hosting remains local pending resource measurements.
