"""Small reproducible hygiene check for distributable source, excluding runtime caches."""
from pathlib import Path
import re
import json

root = Path(__file__).resolve().parents[1]
ignored = {'.git', '.tools', '.models', '.venv', 'node_modules', 'target', 'dist', '__pycache__', '.pytest_cache', 'test-results', 'playwright-report'}
patterns = [r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', r'AKIA[A-Z0-9]{16}', r'ghp_[A-Za-z0-9]{36}', r'sk-proj-[A-Za-z0-9_-]{40,}']
checked = 0
for p in root.rglob('*'):
    if any(part in ignored for part in p.relative_to(root).parts) or not p.is_file():
        continue
    if p.name == '.env':
        assert '.env' in (root/'.gitignore').read_text().splitlines(), 'Local .env must be ignored'
        continue  # Local Compose configuration is allowed; never scan or print its values.
    if p.suffix in {'.png', '.wav', '.zip'}:
        continue
    text = p.read_text(encoding='utf-8-sig')
    assert not any(re.search(pattern, text) for pattern in patterns), f'Possible secret in {p}'
    checked += 1
for name in ['train', 'eval']:
    data = json.loads((root/f'ml/data/{name}.json').read_text())
    assert data['synthetic'] is True
    if name == 'eval':
        assert all(e['synthetic'] for e in data['examples'])
assert 'Novel contribution and limits' in (root/'README.md').read_text()
print(f'PASS: {checked} text artifacts checked; no known secret patterns; local .env excluded by ignore rule; corpus marked synthetic. This heuristic scan is not a complete secret/PII detector.')
