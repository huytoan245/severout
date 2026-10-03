from pathlib import Path
import base64,zipfile,io,re,subprocess,sys,shutil
r=Path(__file__).resolve().parent.parent; workflow=(r/'.github/workflows/build-family-location-v229.yml').read_text(encoding='utf-8')
b64=''.join(p.read_text() for p in sorted((r/'apk-build').glob('part*.txt')))
zipfile.ZipFile(io.BytesIO(base64.b64decode(b64))).extractall(r/'appsrc')
for module in ['parent-app','child-app']:
 p=r/'appsrc'/module/'build.gradle.kts'; s=p.read_text(encoding='utf-8').replace('compileSdk = 37','compileSdk = 36').replace('compose-bom:2026.08.00','compose-bom:2026.06.00'); s='\n'.join(line for line in s.splitlines() if 'id("org.jetbrains.kotlin.android")' not in line and 'kotlinOptions { jvmTarget = "17" }' not in line)+'\n'; p.write_text(s,encoding='utf-8')
import os
os.chdir(r)
block=workflow.split("python3 - <<'PY'\n",1)[1].split('\n          PY',1)[0]; exec('\n'.join(line[10:] for line in block.splitlines()).replace('.read_text()', '.read_text(encoding="utf-8")').replace('.write_text(s)', '.write_text(s, encoding="utf-8")'))
for src,dst in re.findall(r'^          cp (ci-patches/\S+) (appsrc/\S+)',workflow,re.M): shutil.copyfile(src,dst)
p=Path('appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt'); s=p.read_text(encoding='utf-8');
if 'import android.app.PendingIntent\n' not in s:s=s.replace('import android.app.NotificationManager\n','import android.app.NotificationManager\nimport android.app.PendingIntent\n');p.write_text(s,encoding='utf-8')
for patch in re.findall(r'^          python3 (ci-patches/\S+)',workflow,re.M): subprocess.run([sys.executable,patch],check=True)
for name,body in re.findall(r'cat > "appsrc/\$module/src/main/res/drawable/([^"]+)" <<\'EOF\'\n(.*?)\n          EOF',workflow,re.S):
 for m in ['parent-app','child-app']:
  p=Path('appsrc')/m/'src/main/res/drawable'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('\n'.join(l[10:] for l in body.splitlines())+'\n',encoding='utf-8')
body=workflow.split("cat >> appsrc/core/build.gradle.kts <<'EOF'\n",1)[1].split('\n          EOF',1)[0]
with Path('appsrc/core/build.gradle.kts').open('a',encoding='utf-8') as f:f.write('\n'.join(l[10:] for l in body.splitlines())+'\n')
print('Reconstructed exact v229 workflow baseline')

if '--baseline-only' not in sys.argv: subprocess.run([sys.executable, '-X', 'utf8', 'ci-patches/v2210_reliability_patch.py'],check=True)
