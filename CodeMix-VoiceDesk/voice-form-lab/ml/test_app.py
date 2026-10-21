import io
import wave
from fastapi.testclient import TestClient
from app import app, extract, evaluate, edit_distance
client = TestClient(app)

def test_missing_not_invented():
    out = extract('Please help me')
    assert out['fields']['location']['value'] is None
    assert out['fields']['identifier']['needsReview']
    assert out['fields']['category']['value'] is None

def test_evidence_offsets():
    text = 'Paani nahi at Demo Lane on 2026-01-15. ID: SYN-01.'
    for f in extract(text)['fields'].values():
        if f['evidence']:
            s = f['evidence']
            assert text[s['start']:s['end']] == s['text']

def test_evaluation_reproducible_and_denominators():
    a = evaluate()
    assert a == evaluate()
    assert a['examples'] == 44 and a['wordErrors'] == 1
    assert a['reports']['model']['perFieldAccuracy']['location'] == 43 / 44
    assert len(a['reports']['model']['confusionMatrix']) == 5
    assert edit_distance(['a', 'b'], ['b']) == 1

def test_invalid_audio_and_blank_text():
    assert client.post('/extract', json={'text': ' '}).status_code == 422
    assert client.post('/transcribe', files={'file': ('x.wav', b'bad', 'audio/wav')}).status_code == 415

def test_sample_mode_explicit():
    b = io.BytesIO()
    with wave.open(b, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b'\0\0' * 160)
    response = client.post('/transcribe', files={'file': ('x.wav', b.getvalue(), 'audio/wav')}, data={'sampleText': 'Water leak at Demo Lane.'})
    assert response.status_code == 200
    assert response.json()['speech']['confidence'] is None
    assert response.json()['speech']['provider'] == 'sample-transcript-v1'
    missing = client.post('/transcribe', files={'file': ('x.wav', b.getvalue(), 'audio/wav')})
    assert missing.status_code == 422

def test_audio_duration_and_size_limits():
    b = io.BytesIO()
    with wave.open(b, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b'\0\0' * 16000 * 61)
    assert client.post('/transcribe', files={'file': ('long.wav', b.getvalue(), 'audio/wav')}, data={'sampleText': 'water'}).status_code == 422
    assert client.post('/transcribe', files={'file': ('big.wav', b'x' * (5 * 1024 * 1024 + 1), 'audio/wav')}).status_code == 413
