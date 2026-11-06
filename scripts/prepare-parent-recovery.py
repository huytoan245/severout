"""Collect only the separately tested Parent Recovery APK. Never promote the normal pair."""
from pathlib import Path
import argparse,json,hashlib,shutil,xml.etree.ElementTree as ET,subprocess,zipfile
p=argparse.ArgumentParser()
for name in ['build-root','evidence-root','output-directory','operator-public-key']:p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args();repo=Path(__file__).resolve().parent.parent;build=a.build_root;e=a.evidence_root;out=a.output_directory
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
log=(e/'android-build.log').read_text(encoding='utf-8-sig');assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
assert 'ALL CORE TESTS PASSED' in log and 'SCENARIO TEST PASSED' in log
assert 'fail 0' in (e/'worker-tests.log').read_text(encoding='utf-8-sig')
inputs={}
for file in (repo/'appsrc').rglob('*'):
 relative=file.relative_to(repo/'appsrc')
 if not file.is_file() or any(x in {'build','.gradle','.kotlin','docs'} for x in relative.parts) or file.name in {'local.properties','README.md'}:continue
 assert (build/relative).is_file() and sha(file)==sha(build/relative),f'Untested input: {relative}'
 inputs[relative.as_posix()]=sha(file)
tests={};lint={}
for module in ['enrollment','parent-app']:
 suites=[ET.parse(f).getroot() for f in (build/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
 assert suites and all(int(s.get('failures','0'))==0 and int(s.get('errors','0'))==0 for s in suites)
 tests[module]=sum(int(s.get('tests')) for s in suites)
 issues=ET.parse(build/module/'build/reports/lint-results-release.xml').getroot().findall('issue');assert not any(i.get('severity') in ['Error','Fatal'] for i in issues)
 lint[module]={'errors':0,'warnings':sum(i.get('severity')=='Warning' for i in issues)}
assert tests['enrollment']>=36
config=(build/'parent-app/build/generated/source/buildConfig/release/com/family/parent/BuildConfig.java').read_text()
pub=a.operator_public_key.read_text(encoding='utf-8-sig').strip()
assert f'RECOVERY_OPERATOR_KEY = "{pub}";' in config and 'BOOTSTRAP_TOKEN = "";' in config
manifests=list((build/'parent-app/build/intermediates/merged_manifests/release').rglob('AndroidManifest.xml'));assert len(manifests)==1
m=ET.parse(manifests[0]).getroot();ns='{http://schemas.android.com/apk/res/android}'
assert m.get('package')=='com.family.parent' and m.get(ns+'versionName')=='2.3.3' and m.get(ns+'versionCode')=='37'
flags={v.get(ns+'name'):v.get(ns+'value') for v in m.findall('application/meta-data')}
assert flags['family.parent_recovery']=='true' and flags['family.update_in_place']=='true'
launchers=[x.get(ns+'name') for x in m.findall('application/activity') if any(c.get(ns+'name')=='android.intent.category.LAUNCHER' for c in x.findall('intent-filter/category'))]
assert len(launchers)==1 and launchers[0] in ['.RecoveryActivity','com.family.parent.RecoveryActivity']
out.mkdir(parents=True,exist_ok=True);assert not list(out.glob('*Installable.apk'))
name='Family-Parent-v2.3.3-Recovery-unsigned.apk';source=build/'parent-app/build/outputs/apk/release/parent-app-release-unsigned.apk'
with zipfile.ZipFile(source) as archive:
 assert archive.testzip() is None
 assert any(pub.encode() in archive.read(n) for n in archive.namelist() if n.endswith('.dex')),'Actual APK recipient key mismatch'
 assert not any(n.lower().endswith(('.jks','.keystore','.key')) or 'bootstrap.private' in n.lower() or 'service-account' in n.lower() for n in archive.namelist())
shutil.copy2(source,out/name)
gate={'status':'PASS','ReleaseMode':'PARENT_RECOVERY','VersionName':'2.3.3','VersionCode':37,'inputHashes':inputs,'operatorPublicKeyHash':hashlib.sha256(pub.encode()).hexdigest(),'apkSha256':sha(out/name),'tests':tests,'lint':lint,'productionApprovalIssued':False,'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()}
(out/'PARENT-RECOVERY-GATE.json').write_text(json.dumps(gate,indent=2)+'\n',encoding='utf-8')
for file in ['ANDROID-BUILD-TOOLS.json','android-build.log','worker-tests.log']:
 shutil.copy2(e/file,out/file)
(out/'RECOVERY-UNSIGNED-SHA256SUMS.txt').write_text(gate['apkSha256']+'  '+name+'\n',encoding='utf-8')
print(json.dumps({'mode':gate['ReleaseMode'],'tests':tests,'lint':lint,'unsignedSha256':gate['apkSha256'],'productionApply':'LOCKED - separate approval and backend guard required'}))
