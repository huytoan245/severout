"""Collect a locally tested unsigned pair. Never reads a keystore/password."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

repo = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--build-root', type=Path, required=True)
parser.add_argument('--evidence-root', type=Path, required=True)
args = parser.parse_args()
build, evidence = args.build_root.resolve(), args.evidence_root.resolve()
out = repo / 'out/v231'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
logs = {key: (evidence / name).read_text(encoding='utf-8-sig') for key, name in {
    'build': 'build-v231.log', 'worker': 'worker-tests-v231.log',
    'runtime': 'runtime-v231.log', 'rules': 'rules-v231.log',
    'signing': 'signing-regression-v231.log'}.items()}
assert 'BUILD SUCCESSFUL' in logs['build'] and 'BUILD FAILED' not in logs['build']
assert 'ALL CORE TESTS PASSED' in logs['build'] and 'SCENARIO TEST PASSED' in logs['build']
assert re.search(r'pass 45\b', logs['worker']) and re.search(r'fail 0\b', logs['worker'])
assert 'PASS: actual workerd/SQLite:' in logs['runtime']
assert 'PASS: 51 actual Firestore emulator' in logs['rules']
assert 'PASS: 69 signing regressions.' in logs['signing']
inputs = {}
for path in (repo / 'appsrc').rglob('*'):
    relative = path.relative_to(repo / 'appsrc')
    if not path.is_file() or any(part in {'build', '.gradle', '.kotlin', 'docs'} for part in relative.parts) or path.name in {'local.properties', 'README.md'}:
        continue
    assert path.suffix not in {'.jks', '.keystore', '.pem', '.key'}
    assert (build / relative).is_file() and sha(path) == sha(build / relative), f'Untested/mismatched Android input: {relative}'
    inputs[relative.as_posix()] = sha(path)
tests, lint = {}, {}
for module in ['child-app', 'enrollment', 'parent-app']:
    suites = [ET.parse(p).getroot() for p in (build/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    assert all(int(s.attrib['failures']) == 0 and int(s.attrib['errors']) == 0 for s in suites)
    tests[module] = sum(int(s.attrib['tests']) for s in suites)
    issues = ET.parse(build/module/'build/reports/lint-results-release.xml').getroot().findall('issue')
    assert not any(i.get('severity') in {'Error', 'Fatal'} for i in issues)
    lint[module] = {'errors': 0, 'warnings': sum(i.get('severity') == 'Warning' for i in issues)}
assert tests['child-app'] == 26 and tests['enrollment'] == 7
out.mkdir(parents=True, exist_ok=True)
assert not list(out.glob('*Installable.apk')), 'Existing signed artifacts require separate review; do not overwrite'
sums = []
for app, module in [('Parent', 'parent-app'), ('Child', 'child-app')]:
    source = build/module/f'build/outputs/apk/release/{module}-release-unsigned.apk'
    with zipfile.ZipFile(source) as z:
        assert z.testzip() is None and 'AndroidManifest.xml' in z.namelist()
    name = f'Family-{app}-v2.3.1-unsigned.apk'
    shutil.copy2(source, out/name)
    assert sha(source) == sha(out/name)
    sums.append(sha(out/name) + '  ' + name)
metadata = json.loads((evidence/'v231-ANDROID-BUILD-TOOLS.json').read_text(encoding='utf-8-sig'))
assert metadata['modules'] == {':child-app': '36.0.0', ':parent-app': '36.0.0'}
assert all(sha(repo/'appsrc'/path) == value for path, value in metadata['gradleFileHashes'].items())
shutil.copy2(evidence/'v231-ANDROID-BUILD-TOOLS.json', out/'ANDROID-BUILD-TOOLS.json')
for name in ['build-v231.log', 'worker-tests-v231.log', 'runtime-v231.log', 'rules-v231.log', 'signing-regression-v231.log']:
    shutil.copy2(evidence/name, out/name)
gate = {'status': 'PASS', 'VersionName': '2.3.1', 'VersionCode': 35,
        'inputHashes': inputs, 'tests': tests, 'lint': lint, 'workerTests': 45,
        'rulesAssertions': 51, 'signingRegressions': 69, 'workerdSQLite': 'PASS (mock Google only)',
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
        'signing': 'PENDING LOCAL PASSWORD', 'physicalDevice': 'NOT VERIFIED', 'production': 'STOPPED FOR REVIEW'}
(out/'AUTOMATED-GATE.json').write_text(json.dumps(gate, indent=2) + '\n', encoding='utf-8')
(out/'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
print('PASS: tested input hashes and both unsigned APK archives. Local secure signing remains pending.')
print('\n'.join(sums))
