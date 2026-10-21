"""Public receipt/privacy check; optional generated audio tests actual recognition."""
import argparse
import os
from pathlib import Path
import httpx

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--synthetic-audio',help='Optional generated English PCM WAV, never a personal recording')
    args=parser.parse_args()
    base=os.environ.get('API_URL','http://localhost:8080')
    auth=(os.environ.get('DEMO_USER','reviewer'),os.environ.get('DEMO_PASSWORD','local-demo-change-me'))
    with httpx.Client(base_url=base,timeout=300,trust_env=False) as public, httpx.Client(base_url=base,auth=auth,timeout=300,trust_env=False) as staff:
        config=public.get('/api/public/config');config.raise_for_status();assert config.json()['enabled']
        assert public.post('/api/public/submissions',json={'transcript':'Synthetic public check','language':'en','consent':False}).status_code==400
        assert public.post('/api/public/submissions',json={'transcript':'Synthetic public check','language':'en','consent':True,'status':'REVIEWED'}).status_code==400
        payload={'transcript':'Synthetic water leak at Demo Public Lane.','language':'en','consent':True}
        result=public.post('/api/public/submissions',json=payload);result.raise_for_status()
        receipts=[result.json()]
        if args.synthetic_audio:
            result=public.post('/api/public/submissions/audio',files={'file':('synthetic-english.wav',Path(args.synthetic_audio).read_bytes(),'audio/wav')},data={'language':'en','consent':'true'})
            result.raise_for_status();receipts.append(result.json())
        for receipt in receipts:
            case_id=receipt['reference']
            try:
                assert receipt['status']=='AWAITING_REVIEW' and 'prediction' not in receipt and 'transcript' not in receipt
                assert public.get('/api/cases/'+case_id).status_code==401
                assert public.delete('/api/cases/'+case_id).status_code==401
                record=staff.get('/api/cases/'+case_id);record.raise_for_status();record=record.json()
                assert record['source']=='PUBLIC' and record['status']=='NEEDS_REVIEW'
                assert record['consentedAt'] and record['submittedLanguage']=='en'
                assert record['audioRetained']==config.json()['audioRetained'] if record['prediction'].get('speech') else not record['audioRetained']
                if record['prediction'].get('speech'):
                    assert record['prediction']['speech']['provider']=='faster-whisper-cpu-int8'
                    print('PASS public real speech receipt; synthetic transcript:',record['transcript'])
            finally:
                staff.delete('/api/cases/'+case_id).raise_for_status()
        print('PASS consent, field injection rejection, private receipts, staff-only access, public source, language, timestamps, cleanup')
