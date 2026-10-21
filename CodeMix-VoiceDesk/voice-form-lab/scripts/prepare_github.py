"""Scan a source-only distribution and optionally build a ZIP; never print secrets.

This does not inspect Git history or prove the absence of personal data.
Run from any directory: python scripts/prepare_github.py [--zip].
"""
from pathlib import Path
import argparse
import os
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.idea', '.tools', '.models', '.venv', 'node_modules',
            'target', 'dist', '__pycache__', '.pytest_cache', 'test-results',
            'playwright-report', 'uploads', 'recordings', 'exports', 'pgdata'}
PRIVATE_SUFFIXES = {'.pem', '.key', '.p12', '.pfx', '.jks', '.keystore',
                    '.log', '.dump', '.backup', '.tsbuildinfo', '.iml', '.db', '.sqlite', '.sqlite3'}
PATTERNS = [
    r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    r'AKIA[A-Z0-9]{16}', r'gh[pousr]_[A-Za-z0-9]{30,}',
    r'github_pat_[A-Za-z0-9_]{40,}', r'sk-proj-[A-Za-z0-9_-]{40,}',
    r'(?<![A-Za-z0-9])(?:AC|SK)[a-fA-F0-9]{32}(?![A-Za-z0-9])',
    r'(?i)(?:auth[_-]?token|api[_-]?secret)\s*[=:]\s*["\']?[a-f0-9]{32}',
]

def environment(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip().strip('"\'')
    return values

def candidates():
    for current, directories, files in os.walk(ROOT, followlinks=False):
        directories[:] = sorted(d for d in directories if d not in EXCLUDED
                                and not Path(current, d).is_symlink())
        for name in sorted(files):
            path = Path(current, name)
            if path.is_symlink() or name == 'github-release.zip':
                continue
            if name.startswith('.env') and name != '.env.example':
                continue
            if path.suffix.lower() in PRIVATE_SUFFIXES or name.endswith('.sql.gz'):
                continue
            yield path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--zip', action='store_true')
    args = parser.parse_args()
    demo = environment(ROOT / '.env.example')
    private = environment(ROOT / '.env')
    sensitive = [value.encode() for key, value in private.items()
                 if len(value) >= 8 and value != demo.get(key)
                 and any(s in key for s in ('PASSWORD', 'TOKEN', 'SECRET', 'SID', 'NUMBER'))]
    paths = list(candidates())
    problems = []
    for path in paths:
        data = path.read_bytes()
        text = data.decode('utf-8', errors='ignore')
        if any(re.search(pattern, text) for pattern in PATTERNS):
            problems.append((path.relative_to(ROOT), 'possible credential pattern'))
        if any(value in data for value in sensitive):
            problems.append((path.relative_to(ROOT), 'private environment value copied into source'))
    if problems:
        for path, reason in problems:
            print(f'BLOCKED: {path}: {reason}')
        return 1
    print(f'PASS: {len(paths)} distributable files scanned; no matched credential patterns or copied private environment values.')
    print('Excluded environment files, installed tools/models, database storage, build outputs, logs and private keys.')
    print('Git history and arbitrary personal data require separate review; this is a heuristic check.')
    if args.zip:
        output = ROOT / 'github-release.zip'
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in paths:
                archive.write(path, path.relative_to(ROOT).as_posix())
        print(f'Created source-only archive: {output.name} ({output.stat().st_size} bytes).')
    return 0

if __name__ == '__main__':
    sys.exit(main())
