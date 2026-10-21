"""Real model inference on silence in each configured language. No accuracy claim."""
import os
import sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'ml'))
os.environ.setdefault('WHISPER_MODEL_PATH',str(root/'.models/whisper-base'))
from speech import WhisperProvider, whisper_model
from fastapi import HTTPException

model, metadata=whisper_model()
audio=(root/'ml/data/synthetic-silence.wav').read_bytes()
for language in ['ta','te','kn','ml']:
    try:
        result=WhisperProvider().transcribe(audio,None,language)
    except HTTPException as error:
        assert error.status_code==422 and 'No speech detected' in error.detail
        print(f'PASS: {language} real CPU model loaded, silence rejected')
    else:
        raise AssertionError(f'{language}: recognizer hallucinated speech on silence')
print('Runtime smoke only. Speech accuracy and code-mixing quality remain unmeasured.')
