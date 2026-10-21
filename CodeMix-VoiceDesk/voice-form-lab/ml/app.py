import io
import json
import os
import re
import wave
import hashlib
import logging
import unicodedata
from languages import LANGUAGES, language_hints, vocabulary
from speech import WhisperProvider
from pathlib import Path
from typing import Protocol
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from pydantic import BaseModel, Field
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.metrics import precision_recall_fscore_support, confusion_matrix

DATA = Path(__file__).parent / 'data'
TRAIN = json.loads((DATA / 'train.json').read_text(encoding='utf-8'))
TRAIN['examples'] += json.loads((DATA / 'south-train.json').read_text(encoding='utf-8'))['examples']
TRAIN['version'] = 'synthetic-train-v2-south'
EVAL = json.loads((DATA / 'evaluation-v2.json').read_text(encoding='utf-8'))
MODEL_VERSION = 'char-tfidf-logreg-v2-south'
FIELDS = ['category', 'summary', 'location', 'datetime', 'identifier']
LABELS = ['electricity', 'roads', 'sanitation', 'water']
model = make_pipeline(TfidfVectorizer(analyzer='char_wb', ngram_range=(2, 5)), LogisticRegression(C=8, random_state=42, max_iter=500))
model.fit([e['text'] for e in TRAIN['examples']], [e['category'] for e in TRAIN['examples']])
app = FastAPI(title='Voice Form Lab ML', version='1.0.0')
logging.basicConfig(level=logging.INFO, format='%(asctime)s level=%(levelname)s service=ml %(message)s')
log = logging.getLogger('lab.ml')


class TextInput(BaseModel):
    text: str = Field(min_length=1, max_length=10000)


class SpeechProvider(Protocol):
    def transcribe(self, audio: bytes, sample_text: str | None, language: str = 'auto') -> dict: ...


class SampleProvider:
    def transcribe(self, audio, sample_text, language='auto'):
        if not sample_text or not sample_text.strip():
            raise HTTPException(422, 'Sample mode requires an explicit transcript; audio is not recognized.')
        return {'text': sample_text, 'provider': 'sample-transcript-v1', 'confidence': None,
                'synthetic': True, 'requestedLanguage': language, 'warning': 'DEMO: supplied transcript, not speech recognition'}


class VoskProvider:
    def transcribe(self, audio, sample_text, language='auto'):
        try:
            import vosk
            speech_model = vosk.Model(os.environ['VOSK_MODEL_PATH'])
        except (ImportError, KeyError):
            raise HTTPException(503, 'Install optional vosk and set VOSK_MODEL_PATH first')
        with wave.open(io.BytesIO(audio)) as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 16000:
                raise HTTPException(422, 'Vosk requires mono 16-bit 16 kHz PCM WAV')
            recognizer = vosk.KaldiRecognizer(speech_model, 16000)
            recognizer.SetWords(True)
            recognizer.AcceptWaveform(wav.readframes(wav.getnframes()))
            result = json.loads(recognizer.FinalResult())
        conf = [x['conf'] for x in result.get('result', [])]
        return {'text': result.get('text', ''), 'provider': 'vosk-local',
                'confidence': sum(conf) / len(conf) if conf else None, 'synthetic': False,
                'warning': 'Recognizer confidence is not calibrated correctness'}


def field(value=None, confidence=0., span=None):
    return {'value': value, 'confidence': round(float(confidence), 4), 'evidence': span,
            'needsReview': value is None or confidence < 0.8}


def matched(text, pattern):
    m = re.search(pattern, text, re.I)
    return field(m.group(1).strip(), .85, {'text': m.group(1), 'start': m.start(1), 'end': m.end(1)}) if m else field()


def extract(text, baseline=False):
    if baseline:
        category = next((c for c in LABELS if re.search(r'\b' + c + r'\b', text, re.I)), None)
        confidence = .55 if category else 0.
    else:
        probs = model.predict_proba([text])[0]
        index = int(probs.argmax())
        category, confidence = str(model.classes_[index]), float(probs[index])
        known = any(token in text.lower() for token in ['water', 'paani', 'jal', 'tap', 'power', 'bijli', 'electric', 'light', 'road', 'sadak', 'gaddha', 'pothole', 'pavement', 'garbage', 'kachra', 'drain', 'naali', 'waste', 'rubbish', 'sewage'] + vocabulary())
        if not known:
            category, confidence = None, 0.
    first = text.split('.')[0].strip()
    fields = {
        'category': field(category, confidence, {'text': text, 'start': 0, 'end': len(text)} if category else None),
        'summary': field(first, .75, {'text': first, 'start': text.find(first), 'end': text.find(first) + len(first)}),
        'location': matched(text, r'\b(?:at|near)\s+([^.,;:\n]+?)(?=\s+(?:since|on)\b|[.,;]|$)'),
        'datetime': matched(text, r'\b(\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2})?)\b'),
        'identifier': matched(text, r'(?:\b(?:name|id)|பெயர்|పేరు|ಹೆಸರು|പേര്)\s*:\s*([^.,;:\n]+?)(?=[.,;]|$)')}
    explicit_location=matched(text,r'(?:\blocation|இடம்|స్థలం|ಸ್ಥಳ|സ്ഥലം)\s*:\s*([^.,;:\n]+?)(?=[.,;]|$)')
    if explicit_location['value'] is not None:
        fields['location']=explicit_location
    return {'transcript': text, 'fields': fields, 'provider': 'keyword-baseline-v1' if baseline else MODEL_VERSION,
            'trainingVersion': TRAIN['version'],
            'languageHints': language_hints(text),
            'languageHintNote': 'Script and lexical candidate hints; Romanized language identification is ambiguous.',
            'confidenceNote': 'Uncalibrated classifier probabilities and rule scores; human review required.'}


def words(s):
    # Keep Indic vowel signs/combining marks inside words. Do not split graphemes.
    s=unicodedata.normalize('NFC',s).casefold()
    return ''.join(c if unicodedata.category(c)[0] in 'LMN' or c=='_' else ' ' for c in s).split()


def edit_distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        new = [i]
        for j, y in enumerate(b, 1):
            new.append(min(new[-1] + 1, row[j] + 1, row[j-1] + (x != y)))
        row = new
    return row[-1]


def evaluate(dataset=EVAL, include_breakdown=True):
    reports = {}
    for name in ['model', 'baseline']:
        errors, truth, pred = [], [], []
        correct = dict.fromkeys(FIELDS, 0)
        present, present_correct = dict.fromkeys(FIELDS, 0), dict.fromkeys(FIELDS, 0)
        for e in dataset['examples']:
            out = extract(e['observed_transcript'], name == 'baseline')
            truth.append(e['fields']['category'] or 'missing')
            pred.append(out['fields']['category']['value'] or 'missing')
            for f in FIELDS:
                expected, actual = e['fields'].get(f), out['fields'][f]['value']
                ok = expected == actual
                correct[f] += ok
                if expected is not None:
                    present[f] += 1
                    present_correct[f] += ok
                if not ok:
                    errors.append({'id': e['id'], 'language': e['language'], 'field': f, 'expected': expected, 'predicted': actual})
        p, r, f1, _ = precision_recall_fscore_support(truth, pred, labels=LABELS, average='macro', zero_division=0)
        reports[name] = {'perFieldAccuracy': {f: correct[f] / len(truth) for f in FIELDS},
                         'nonMissingAccuracy': {f: present_correct[f] / present[f] if present[f] else None for f in FIELDS},
                         'categoryMacroPrecision': p, 'categoryMacroRecall': r, 'categoryMacroF1': f1,
                         'confusionLabels': LABELS + ['missing'],
                         'confusionMatrix': confusion_matrix(truth, pred, labels=LABELS + ['missing']).tolist(), 'errors': errors}
    distances = sum(edit_distance(words(e['reference_transcript']), words(e['observed_transcript'])) for e in dataset['examples'])
    count = sum(len(words(e['reference_transcript'])) for e in dataset['examples'])
    result = {'datasetVersion': dataset['version'], 'datasetSha256': hashlib.sha256(json.dumps(dataset, sort_keys=True).encode()).hexdigest(),
            'modelVersion': MODEL_VERSION, 'seed': 42, 'synthetic': dataset.get('synthetic', False),
            'examples': len(dataset['examples']), 'wer': distances / count if count else None,
            'wordErrors': distances, 'referenceWords': count,
            'tokenization':'NFC Unicode letter/mark/number words, case folded v2',
            'werSource': 'Bundled simulated recognition transcripts, not measured speech recognition', 'reports': reports}
    if include_breakdown:
        result['byLanguage']={lang:evaluate({**dataset,'examples':[e for e in dataset['examples'] if e['language']==lang]},False)
                              for lang in sorted({e['language'] for e in dataset['examples']})}
    return result


@app.get('/health')
def health():
    from pathlib import Path
    folder=Path(os.environ.get('WHISPER_MODEL_PATH','/models/whisper-base'))
    return {'status': 'ok', 'model': MODEL_VERSION, 'speechLanguages':LANGUAGES,
            'speechModelDownloaded':(folder/'model.bin').is_file(),
            'speechAccuracyStatus':'Not measured on consented South Indian speech',
            'speechProvider':'faster-whisper-cpu-int8'}


@app.post('/extract')
def extraction(body: TextInput):
    if not body.text.strip():
        raise HTTPException(422, 'Transcript must not be blank')
    result = extract(body.text)
    log.info('event=extraction_completed model=%s', MODEL_VERSION)
    return result


@app.post('/transcribe')
async def transcribe(file: UploadFile = File(...), provider: str = Form('sample'), sampleText: str | None = Form(None, max_length=10000), language: str = Form('auto')):
    audio = await file.read(5 * 1024 * 1024 + 1)
    if len(audio) > 5 * 1024 * 1024:
        raise HTTPException(413, 'Maximum audio size is 5 MiB')
    if not (file.filename or '').lower().endswith('.wav') or audio[:4] != b'RIFF' or audio[8:12] != b'WAVE':
        raise HTTPException(415, 'Only PCM WAV files accepted')
    try:
        with wave.open(io.BytesIO(audio)) as wav:
            if wav.getcomptype() != 'NONE' or wav.getnframes() / wav.getframerate() > 60:
                raise HTTPException(422, 'Only uncompressed WAV clips up to 60 seconds')
    except (wave.Error, EOFError, ZeroDivisionError, ValueError):
        raise HTTPException(422, 'Invalid WAV')
    if language not in LANGUAGES:
        raise HTTPException(422, 'Unsupported speech language; choose Tamil, Telugu, Kannada, Malayalam, English, Hindi, or auto')
    if provider not in ('sample', 'vosk', 'whisper'):
        raise HTTPException(422, 'Unknown speech provider')
    recognizer = {'sample':SampleProvider,'vosk':VoskProvider,'whisper':WhisperProvider}[provider]()
    # Run CPU inference outside the asynchronous event loop so health stays responsive.
    from starlette.concurrency import run_in_threadpool
    result = await run_in_threadpool(recognizer.transcribe, audio, sampleText, language)
    if not result['text'].strip():
        raise HTTPException(422, 'No speech recognized')
    return {**extract(result['text']), 'speech': result}


@app.post('/evaluate')
def run_evaluation():
    result = evaluate()
    log.info('event=evaluation_completed dataset=%s examples=%s', result['datasetVersion'], result['examples'])
    return result
