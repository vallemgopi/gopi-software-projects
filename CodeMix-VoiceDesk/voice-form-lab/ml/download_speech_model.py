"""Explicit, version-pinned download; no audio leaves the machine."""
import argparse
import json
from pathlib import Path
from huggingface_hub import HfApi, snapshot_download

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--model', choices=['base','small'], default='base')
    parser.add_argument('--revision', help='Commit SHA; if omitted, resolve and record the current immutable SHA')
    parser.add_argument('--output', default=str(Path(__file__).resolve().parents[1]/'.models/whisper-base'))
    args=parser.parse_args()
    repo='Systran/faster-whisper-'+args.model
    revision=HfApi().model_info(repo,revision=args.revision or ('ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66' if args.model=='base' else 'main')).sha
    folder=Path(args.output)
    snapshot_download(repo,revision=revision,local_dir=str(folder),
                      allow_patterns=['config.json','model.bin','tokenizer.json','vocabulary.*','preprocessor_config.json'])
    metadata={'repository':repo,'revision':revision,'computeType':'int8','device':'cpu'}
    (folder/'lab-model.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'downloadedTo':str(folder),**metadata},indent=2))
