import io
import wave
from types import SimpleNamespace
import pytest
from fastapi.testclient import TestClient
from app import app, extract, evaluate, words
from languages import SOUTH, south_datasets
from speech import WhisperProvider

def silence():
    b=io.BytesIO()
    with wave.open(b,'wb') as w:
        w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(b'\0\0'*16000)
    return b.getvalue()

@pytest.mark.parametrize('code', ['ta','te','kn','ml'])
def test_script_hints_evidence_and_combining_marks(code):
    phrase=SOUTH[code]['water'][2]
    text=phrase+' at '+phrase+'. ID: '+phrase+'.'
    out=extract(text)
    assert code in out['languageHints']
    assert out['fields']['location']['value']==phrase
    assert out['fields']['identifier']['value']==phrase
    for field in out['fields'].values():
        if field['evidence']:
            span=field['evidence']; assert text[span['start']:span['end']]==span['text']
    assert words(phrase)==phrase.split()

def test_language_breakdowns_and_distinct_sentence_sets():
    train, test=south_datasets()
    assert not {e['text'] for e in train['examples']} & {e['reference_transcript'] for e in test['examples']}
    result=evaluate()
    for code in SOUTH:
        assert result['byLanguage'][code+'-en']['examples']==4
        assert result['byLanguage'][code+'-Latn-en']['examples']==4

@pytest.mark.parametrize('location_label,name_label,value',[
    ('இடம்','பெயர்','செயற்கை'),('స్థలం','పేరు','నమూనా'),
    ('ಸ್ಥಳ','ಹೆಸರು','ಮಾದರಿ'),('സ്ഥലം','പേര്','മാതൃക')])
def test_explicit_native_labels(location_label,name_label,value):
    text=f'{location_label}: {value}; {name_label}: {value}.'
    out=extract(text)
    for key in ['location','identifier']:
        f=out['fields'][key]
        assert f['value']==value
        span=f['evidence'];assert text[span['start']:span['end']]==value

def test_invalid_language_and_missing_local_model(monkeypatch,tmp_path):
    from speech import whisper_model
    whisper_model.cache_clear()
    monkeypatch.setenv('WHISPER_MODEL_PATH',str(tmp_path))
    client=TestClient(app)
    for language,status in [('tulu',422),('ta',503)]:
        r=client.post('/transcribe',files={'file':('s.wav',silence(),'audio/wav')},data={'provider':'whisper','language':language})
        assert r.status_code==status

def test_whisper_adapter_forwards_language_preserves_transcript_and_scores(monkeypatch):
    import speech
    class Model:
        def transcribe(self,audio,**kwargs):
            assert kwargs['language']=='ta' and kwargs['task']=='transcribe'
            assert kwargs['condition_on_previous_text'] is False
            word=SimpleNamespace(word='தண்ணீர்',start=0.,end=1.,probability=.6)
            segment=SimpleNamespace(text='தண்ணீர் at Demo Lane',start=0.,end=1.,words=[word])
            return iter([segment]), SimpleNamespace(language='ta',language_probability=1.)
    monkeypatch.setattr(speech,'whisper_model',lambda:(Model(),{'repository':'test-only','revision':'test-only'}))
    # No real model is used by this unit test. Library metadata is supplied explicitly.
    import sys
    monkeypatch.setitem(sys.modules,'faster_whisper',SimpleNamespace(__version__='test-only'))
    result=WhisperProvider().transcribe(silence(),None,'ta')
    assert result['text']=='தண்ணீர் at Demo Lane'
    assert result['confidence'] is None and result['languageProbability'] is None
    assert result['segments'][0]['words'][0]['score']==.6
