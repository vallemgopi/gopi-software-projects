"""Lazy, local-only CPU speech inference. Model download is a separate explicit step."""
import io
import json
import os
from pathlib import Path
from functools import lru_cache
from threading import Lock
from fastapi import HTTPException
from languages import LANGUAGES

inference_lock = Lock()

@lru_cache(maxsize=1)
def whisper_model():
    folder = Path(os.environ.get('WHISPER_MODEL_PATH', '/models/whisper-base'))
    if not (folder/'model.bin').is_file() or not (folder/'lab-model.json').is_file():
        raise HTTPException(503, 'Local speech model missing. Run ml/download_speech_model.py and configure WHISPER_MODEL_PATH.')
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        raise HTTPException(503, 'Install ml/requirements-speech.lock to enable local speech recognition')
    metadata = json.loads((folder/'lab-model.json').read_text(encoding='utf-8'))
    return WhisperModel(str(folder), device='cpu', compute_type='int8', cpu_threads=4,
                        local_files_only=True, num_workers=1), metadata

class WhisperProvider:
    def transcribe(self, audio: bytes, sample_text: str | None, language='auto'):
        if language not in LANGUAGES:
            raise HTTPException(422, 'Unsupported speech language')
        model, metadata = whisper_model()
        # Serialize CPU inference to bound concurrency and memory; no prompts/translations.
        with inference_lock:
            segments, info = model.transcribe(io.BytesIO(audio), language=None if language=='auto' else language,
                task='transcribe', beam_size=3, temperature=0, word_timestamps=True,
                vad_filter=True, condition_on_previous_text=False)
            segments = list(segments)
        text=' '.join(s.text.strip() for s in segments).strip()
        if not text:
            raise HTTPException(422, 'No speech detected; upload a spoken clip, not the silent sample fixture.')
        import faster_whisper
        return {'text':text,'provider':'faster-whisper-cpu-int8','confidence':None,'synthetic':None,'inputProvenance':'not declared',
            'requestedLanguage':language,'detectedLanguage':info.language,
            'languageProbability':info.language_probability if language=='auto' else None,
            'model':metadata,'libraryVersion':faster_whisper.__version__,
            'segments':[{'text':s.text.strip(),'start':s.start,'end':s.end,
                         'words':[{'text':w.word,'start':w.start,'end':w.end,'score':w.probability} for w in s.words or []]} for s in segments],
            'warning':'Real local recognition. Word scores are uncalibrated; verify names, dates, and English code mixing. Accuracy is not yet measured for these languages.'}
