"""Collect a tested UPDATE_IN_PLACE pair; no enrollment secrets or signing input."""
from pathlib import Path
import argparse,hashlib,json,re,shutil,subprocess,xml.etree.ElementTree as ET,zipfile

repo=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--build-root',type=Path,required=True);p.add_argument('--evidence-root',type=Path,required=True);p.add_argument('--output-directory',type=Path,required=True)
a=p.parse_args();build=a.build_root.resolve();evidence=a.evidence_root.resolve();out=a.output_directory.resolve()
sha=lambda x:hashlib.sha256(x.read_bytes()).hexdigest()
def log(name):return (evidence/name).read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in log('android-build.log') and 'BUILD FAILED' not in log('android-build.log')
assert 'ALL CORE TESTS PASSED' in log('android-build.log') and 'SCENARIO TEST PASSED' in log('android-build.log')
worker=int(re.search(r'pass (\d+)',log('worker-tests.log'))[1]);assert worker>=88 and re.search(r'fail 0\b',log('worker-tests.log'))
rules=int(re.search(r'PASS: (\d+) actual Firestore',log('rules-emulator.log'))[1]);assert rules>=96
signing=int(re.search(r'PASS: (\d+) signing regressions',log('signing-regression.log'))[1]);assert signing>=102
assert 'PASS: actual workerd/SQLite:' in log('runtime-smoke.log')
inputs={}
for source in (repo/'appsrc').rglob('*'):
    relative=source.relative_to(repo/'appsrc')
    if not source.is_file() or any(x in {'build','.gradle','.kotlin','docs'} for x in relative.parts) or source.name in {'local.properties','README.md'}:continue
    assert (build/relative).is_file() and sha(source)==sha(build/relative),f'Untested Android input: {relative}'
    inputs[relative.as_posix()]=sha(source)
tests={};lint={}
for module in ('enrollment','child-app','parent-app'):
    suites=[ET.parse(f).getroot() for f in (build/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    assert suites and all(int(s.get('failures','0'))==0 and int(s.get('errors','0'))==0 for s in suites)
    tests[module]=sum(int(s.get('tests','0')) for s in suites)
    issues=ET.parse(build/module/'build/reports/lint-results-release.xml').getroot().findall('issue')
    assert not any(i.get('severity') in ('Error','Fatal') for i in issues)
    lint[module]={'errors':0,'warnings':sum(i.get('severity')=='Warning' for i in issues)}
assert tests['enrollment']>=15 and tests['child-app']>=40 and tests['parent-app']>=4
metadata=json.loads((evidence/'ANDROID-BUILD-TOOLS.json').read_text(encoding='utf-8-sig'))
assert metadata['modules']=={':parent-app':'36.0.0',':child-app':'36.0.0'}
assert all(sha(repo/'appsrc'/name)==value for name,value in metadata['gradleFileHashes'].items())
out.mkdir(parents=True,exist_ok=True)
assert not list(out.glob('*Installable.apk')),'Signed outputs already present; do not overwrite'
hashes={};sums=[]
for app,module in [('Parent','parent-app'),('Child','child-app')]:
    config=(build/module/'build/generated/source/buildConfig/release'/('com/family/'+app.lower())/'BuildConfig.java').read_text()
    assert re.search(r'BOOTSTRAP_TOKEN\s*=\s*"";',config),'Update APK must contain no initial capability'
    manifests=list((build/module/'build/intermediates/merged_manifests/release').rglob('AndroidManifest.xml'))
    assert len(manifests)==1
    manifest=ET.parse(manifests[0]).getroot();ns='{http://schemas.android.com/apk/res/android}'
    assert manifest.attrib['package']=='com.family.'+app.lower() and manifest.attrib[ns+'versionCode']=='36' and manifest.attrib[ns+'versionName']=='2.3.2'
    flags=[m.get(ns+'value') for m in manifest.findall('application/meta-data') if m.get(ns+'name')=='family.update_in_place'];assert flags==['true']
    apk=build/module/f'build/outputs/apk/release/{module}-release-unsigned.apk'
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        assert not any(n.lower().endswith(('.jks','.keystore','.pem','.key')) or 'bootstrap.private' in n.lower() or 'service-account' in n.lower() for n in z.namelist())
    name=f'Family-{app}-v2.3.2-unsigned.apk';shutil.copy2(apk,out/name);hashes[app]=sha(out/name);sums.append(hashes[app]+'  '+name)
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
gate={'status':'PASS','VersionName':'2.3.2','VersionCode':36,'ReleaseMode':'UPDATE_IN_PLACE','commit':commit,'inputHashes':inputs,'workerTests':worker,'rulesAssertions':rules,'signingRegressions':signing,'tests':tests,'lint':lint,'physical':'READY FOR DEVICE TEST','productionDeployment':'NOT PERFORMED'}
(out/'AUTOMATED-GATE.json').write_text(json.dumps(gate,indent=2)+'\n',encoding='utf-8')
(out/'UPDATE-IN-PLACE.json').write_text(json.dumps({'mode':'UPDATE_IN_PLACE','versionName':'2.3.2','versionCode':36,'bootstrapIncluded':False,'existingEnrollmentRequired':True,'apks':hashes,'commit':commit},indent=2)+'\n',encoding='utf-8')
(out/'SHA256SUMS.txt').write_text('\n'.join(sums)+'\n',encoding='utf-8')
for name in ['ANDROID-BUILD-TOOLS.json','android-build.log','worker-tests.log','rules-emulator.log','signing-regression.log','runtime-smoke.log']:
    if (evidence/name).resolve()!=(out/name).resolve():shutil.copy2(evidence/name,out/name)
print(json.dumps({'mode':'UPDATE_IN_PLACE','tests':tests,'worker':worker,'rules':rules,'signingMockRegressions':signing,'apks':hashes,'state':'READY FOR DEVICE TEST'},indent=2))
