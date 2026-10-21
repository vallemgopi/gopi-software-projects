"""Check exported Maven coordinates against OSV. Sends package names/versions only.

First run Maven dependency:tree -DoutputType=json
-DoutputFile=../.tools/java-dependencies.json. No keys required.
"""
from pathlib import Path
import json
import urllib.request

root = Path(__file__).resolve().parents[1]
tree = json.loads((root / '.tools/java-dependencies.json').read_text())
packages = {}
def visit(node):
    key = (node['groupId'] + ':' + node['artifactId'], node['version'])
    packages[key] = node.get('scope', '')
    for child in node.get('children', []):
        visit(child)
for child in tree.get('children', []):
    visit(child)
coordinates = sorted(packages)
results = []
for start in range(0, len(coordinates), 100):
    batch = coordinates[start:start + 100]
    body = {'queries': [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
                        for name, version in batch]}
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                     data=json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        matches = json.load(response)['results']
    for coordinate, match in zip(batch, matches):
        if match.get('vulns'):
            name, version = coordinate
            results.append({'name': name, 'version': version, 'scope': packages[coordinate],
                            'advisories': [v['id'] for v in match['vulns']]})
report = {'checked_packages': len(coordinates), 'affected_packages': results}
(root / '.tools/java-audit.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
raise SystemExit(1 if results else 0)
