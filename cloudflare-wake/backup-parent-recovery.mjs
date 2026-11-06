// Operator workstation only. No Firestore/Auth mutation endpoints exist here.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const args=process.argv.slice(2),get=key=>args[args.indexOf(key)+1];
async function run(){
  if(args.length!==4 || !args.includes('--credential-file') || !args.includes('--output'))throw Error();
  const output=get('--output'),sa=JSON.parse(fs.readFileSync(get('--credential-file'),'utf8').replace(/^\uFEFF/,''));
  if(sa.type!=='service_account'||sa.project_id!=='family-location-884e5'||!fs.statSync(output).isDirectory())throw Error();
  const save=(name,value)=>fs.writeFileSync(path.join(output,name),JSON.stringify(value,null,2)+'\n',{flag:'wx'});
  async function request(url,init={}){
    const u=new URL(url);if(u.protocol!=='https:'||!['oauth2.googleapis.com','firestore.googleapis.com','identitytoolkit.googleapis.com','family-location-wake.huytoan0979928450.workers.dev'].includes(u.hostname))throw Error();
    const r=await fetch(url,{...init,redirect:'error',signal:AbortSignal.timeout(30000)});if(!r.ok)throw Error();return r.json();
  }
  const now=Math.floor(Date.now()/1000),b64=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned=b64({alg:'RS256',typ:'JWT'})+'.'+b64({iss:sa.client_email,scope:'https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
  const signature=crypto.sign('RSA-SHA256',Buffer.from(unsigned),sa.private_key).toString('base64url');
  const oauth=await request('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:unsigned+'.'+signature})});
  const headers={Authorization:'Bearer '+oauth.access_token,'Content-Type':'application/json'};
  const base='https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents';
  const [family,device,health]=await Promise.all([request(base+'/families/family-01',{headers}),request(base+'/devices/child-01',{headers}),request('https://family-location-wake.huytoan0979928450.workers.dev/health')]);
  const fields=family.fields,f=k=>fields[k]?.stringValue??(fields[k]?.integerValue===undefined?fields[k]?.booleanValue:Number(fields[k].integerValue));
  if(f('schemaVersion')!==232||f('familyId')!=='family-01'||f('childDeviceId')!=='child-01'||!f('parentUid')||!f('childUid')||!f('parentKey')||!f('childKey'))throw Error();
  save('family.private.json',family);save('device.private.json',device);
  const auth=await request('https://identitytoolkit.googleapis.com/v1/projects/family-location-884e5/accounts:lookup',{method:'POST',headers,body:JSON.stringify({localId:[f('parentUid')]})});
  const user=auth.users?.find(u=>u.localId===f('parentUid'));
  let token='',page=0;
  do{const audit=await request(base+'/familyRebind?pageSize=100'+(token?'&pageToken='+encodeURIComponent(token):''),{headers});save(`rebind-audit-${++page}.private.json`,audit);token=audit.nextPageToken||'';if(page>1000)throw Error();}while(token);
  const summary={checkedAt:new Date().toISOString(),readOnly:true,health,schema:f('schemaVersion'),familyRevision:family.updateTime,epoch:f('epoch'),parentRebindGeneration:f('parentRebindGeneration')||0,parentSlotPresent:true,childSlotPresent:true,parentBindingPresent:/^[A-Za-z0-9_-]{43}$/.test(f('parentDeviceBindingHash')||''),parentAuthAccountExists:!!user,parentAuthDisabled:user?user.disabled===true:null,auditPages:page,productionMutation:false};
  save('READONLY-SUMMARY.json',summary);console.log(JSON.stringify(summary));
}
run().catch(()=>{console.error('Read-only backup failed; sensitive diagnostics suppressed. No mapping/Auth/data mutations performed.');process.exitCode=1;});
