"""Exercise running real services, including immutable predictions and sample WAV upload."""
import os
from pathlib import Path
import httpx

base = os.environ.get('API_URL', 'http://localhost:8080')
auth = (os.environ.get('DEMO_USER', 'reviewer'), os.environ.get('DEMO_PASSWORD', 'local-demo-change-me'))
root = Path(__file__).resolve().parents[1]
with httpx.Client(base_url=base, auth=auth, timeout=130, trust_env=False) as client:
    assert httpx.get(base + '/api/cases', trust_env=False).status_code == 401
    assert client.get('/api/health').json()['ml']['status'] == 'ok'
    assert client.post('/api/cases', json={'transcript': ' '}).status_code == 400
    created = client.post('/api/cases', json={'transcript': 'Water leak at Demo Lane.'})
    assert created.status_code == 201, created.text
    c = created.json()
    fields = {k: v['value'] or '' for k, v in c['prediction']['fields'].items()}
    fields['location'] = 'Corrected Demo Lane'
    body = {'transcript': 'Water leak at Corrected Demo Lane.', 'fields': fields, 'version': c['version']}
    reviewed = client.post(f"/api/cases/{c['id']}/review", json=body)
    assert reviewed.status_code == 200, reviewed.text
    r = reviewed.json()
    assert r['prediction']['fields']['location']['value'] == 'Demo Lane'
    assert r['corrections'][-1]['fields']['location'] == 'Corrected Demo Lane'
    assert client.put(f"/api/cases/{c['id']}", json=body).status_code == 409
    assert client.get('/api/exports/corrections').json()['cases']
    assert client.delete(f"/api/cases/{c['id']}").status_code == 204
    assert client.get(f"/api/cases/{c['id']}").status_code == 404
    audio = (root/'ml/data/synthetic-silence.wav').read_bytes()
    response = client.post('/api/cases/audio', files={'file': ('synthetic-silence.wav', audio, 'audio/wav')}, data={'provider': 'sample', 'sampleText': 'Paani nahi at Demo Colony.'})
    assert response.status_code == 201, response.text
    sample = response.json()
    assert sample['prediction']['speech']['provider'] == 'sample-transcript-v1'
    assert not sample['audioRetained']
    assert client.delete('/api/cases/' + sample['id']).status_code == 204
    assert client.post('/api/cases/audio', files={'file': ('bad.wav', b'bad', 'audio/wav')}).status_code == 415
    evaluation = client.post('/api/evaluations')
    assert evaluation.status_code == 200, evaluation.text
    report = evaluation.json()['report']
    assert report['examples'] == 44 and report['wordErrors'] == 1
    assert client.get('/api/evaluations/latest').json()['report'] == report
    assert client.get('/api/metrics').status_code == 200
    assert client.get('/v3/api-docs').status_code == 200
    print('PASS: auth, validation, create, immutable predictions, review, stale update, export, delete, sample audio, no audio retention, evaluation, metrics, OpenAPI')
