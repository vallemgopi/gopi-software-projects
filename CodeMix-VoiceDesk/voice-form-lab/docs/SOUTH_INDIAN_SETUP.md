# South Indian language phase

The current phase adds **Tamil (ta), Telugu (te), Kannada (kn), Malayalam (ml)** with English code mixing. English and Romanized Hindi remain available. These four are not a claim of covering every language spoken in South India. Tulu, Kodava, Konkani, dialect coverage, and native-speaker acceptance need separate model/data work.

## Use it locally

The existing local preview is http://localhost:5173. Sign in with the configured demo credentials, open New case, choose a speech/sample language, then:

* Text: enter a transcript or click the language-specific synthetic sample, then extract and review.
* Real voice: choose **Local Whisper CPU**, upload a spoken PCM WAV (under 5 MiB and 60 seconds), and extract. Select the dominant local language for short/code-mixed clips; auto detection is also available. This requests transcription, never translation. Code mixing can still be normalized, omitted, or misrecognized by the model.
* Demo: explicitly select **Sample transcript** to use supplied text. The bundled silent WAV cannot test real recognition; real recognition rejects it as no speech.

All fields still require human confirmation. Missing values are not inferred. Native-script and Romanized language hints are heuristic candidates, not verified language identification. Romanized words can overlap across languages. Original predictions and each correction revision remain separate.

## Reproduce speech setup

From the project root, using the project's virtual environment:

```powershell
.\.venv\Scripts\python.exe -m pip install -r ml/requirements-speech.lock
.\.venv\Scripts\python.exe ml/download_speech_model.py --model base
$env:WHISPER_MODEL_PATH=(Join-Path (Get-Location) '.models\whisper-base')
cd ml
..\.venv\Scripts\python.exe -m uvicorn app:app --host 127.0.0.1 --port 8000 --no-access-log
```

The downloader pins base revision `ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66`, records a manifest, and downloads public model files. No API key is required, and no audio is sent to Hugging Face. API inference loads local files only and cannot silently download a model. Model weights are 145,217,532 bytes in this verified download; tokenizer/config files add overhead. They are ignored by Git under `.models/`. The provider caches one CPU int8 model and serializes inference to bound CPU concurrency. First initialization can take time; proxies allow up to five minutes. No latency guarantee is made.

For a larger model, `--model small --output .models/whisper-small` resolves and records its revision; point `WHISPER_MODEL_PATH` at it. Larger models need more disk/RAM/CPU and separate testing. This phase tested base only.

Compose installs the speech dependencies and mounts `./.models` read-only at `/models`. Run the explicit downloader on the host before using real speech in Compose. Sample/text modes continue without a model download. Docker startup, container inference, Java 21 runtime, and the real browser workflows have now passed; see [Docker verification](DOCKER_VERIFICATION.md).

## Extraction and evaluation limits

The classifier now trains on 96 synthetic text examples: 32 existing and 64 South Indian lexical templates. The combined evaluation has 44 examples: 12 existing and 32 new (four categories in native script and Romanized text for each of four languages). Run:

```powershell
.\.venv\Scripts\python.exe ml/generate_south_corpus.py
.\.venv\Scripts\python.exe ml/evaluate.py --output ml/computed-report.json
```

These are generated fixtures, **not reviewed translations or an independent natural-language benchmark**. Train and evaluation sentences differ, but intentionally share vocabulary/templates. They are useful for reproducible functional regression checks and transparent error examples, not population performance claims. The evaluation page now exposes per-language/script results. Earlier reports retain their model/dataset versions.

Location extraction supports English `at`/`near` and explicit `location:` / `இடம்:` / `స్థలం:` / `ಸ್ಥಳ:` / `സ്ഥലം:` labels. Names/identifiers require explicit `name:` / `id:` / `பெயர்:` / `పేరు:` / `ಹೆಸರು:` / `പേര്:` labels. Free-form local grammar is not fully parsed; missing fields must be completed by a reviewer. Dates remain literal ISO strings. Evidence offsets refer to the original transcript; Unicode combining marks are preserved in values and WER tokenization.

The bundled WER is still simulated transcript error, not actual multilingual ASR WER. Adding generated zero-error fixtures changes its denominator; a lower value does not imply improved speech recognition. The real runtime was tested on silence in all four language settings and on one Windows-generated synthetic English utterance through the audio API. It has **not** been accuracy-tested on spoken Tamil, Telugu, Kannada, Malayalam, regional accents, or English code mixing. Consented, de-identified, reference-labeled recordings and native-speaker review are needed before making those claims.

## Hosting

No hosting account is needed yet. Keep the application local while gathering consented evaluation recordings and measuring CPU/RAM use. This phase does not deploy publicly or create a paid account. Hosting can be selected after resource and access requirements are known.

Sources: [faster-whisper CPU int8 usage](https://github.com/SYSTRAN/faster-whisper), [converted base model and license](https://huggingface.co/Systran/faster-whisper-base), [Whisper language tokens](https://github.com/openai/whisper/blob/main/whisper/tokenizer.py).
