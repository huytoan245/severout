import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { ROOT, fields } from './bootstrap-plan.mjs';
export const hash = v => createHash('sha256').update(v).digest('base64url');
export function validatePermanent(config) {
  if (Object.keys(config || {}).sort().join() !== 'bootstrapMode,deviceId,familyId,roles,versionCode,versionName' || config.familyId !== 'family-01' || config.deviceId !== 'child-01' || config.versionName !== '2.3.2' || config.versionCode !== 36 || config.bootstrapMode !== 'UNTIL_CONSUMED_OR_REVOKED' || Object.keys(config.roles || {}).sort().join() !== 'child,parent') throw Error('Invalid v232 permanent bootstrap scope');
  for (const r of ['parent','child']) {
    const e=config.roles[r];
    if (Object.keys(e || {}).join() !== 'sha256' || !/^[A-Za-z0-9_-]{43}$/.test(e.sha256) || Buffer.from(e.sha256,'base64url').toString('base64url') !== e.sha256) throw Error('Hash-only canonical role metadata required; initial expiry forbidden');
  }
  if(config.roles.parent.sha256===config.roles.child.sha256) throw Error('Distinct role hashes required');
}
export function initialPlan(config) {
  validatePermanent(config);
  const f={familyId:'family-01',childDeviceId:'child-01',epoch:1,locked:false,schemaVersion:232,bootstrapMode:config.bootstrapMode};
  for(const r of ['parent','child']) Object.assign(f,{[r+'BootstrapHash']:config.roles[r].sha256,[r+'BootstrapConsumed']:false,[r+'BootstrapRevoked']:false,[r+'RebindGeneration']:0});
  return {reviewOnly:true,requiresSeparateOperatorApproval:true,preservesLocationAndEvents:true,commit:{writes:[{update:{name:ROOT+'/families/family-01',fields:fields(f)},currentDocument:{exists:false}}]}};
}
export function migrationPlan(snapshot, config) {
  validatePermanent(config);
  const f=snapshot.family;
  if(!f) return initialPlan(config);
  if(f.familyId!=='family-01' || f.childDeviceId!=='child-01' || !f.updateTime || !Number.isSafeInteger(f.epoch) || f.epoch<1 || f.epoch>=Number.MAX_SAFE_INTEGER || ![undefined,231].includes(f.schemaVersion)) throw Error('CONFLICT FOUND: invalid baseline/CAS');
  for(const r of ['parent','child']) if(f[r+'Uid'] || f[r+'Key'] || f[r+'RegisteredAt'] || f[r+'ClaimProofHash'] || f[r+'DeviceBindingHash'] || f[r+'BootstrapConsumed']!==false) throw Error('CONFLICT FOUND: enrollment or ambiguous claim state');
  const retired=new Set((f.retiredBootstrapHashes || '').split(',').filter(Boolean));
  for(const r of ['parent','child']) { if(!f[r+'BootstrapHash']) throw Error('CONFLICT FOUND: missing old bootstrap'); retired.add(f[r+'BootstrapHash']); }
  for(const r of ['parent','child']) if(retired.has(config.roles[r].sha256)) throw Error('Fresh v232 capabilities required');
  const next={...f,epoch:f.epoch+1,schemaVersion:232,bootstrapMode:config.bootstrapMode,retiredBootstrapHashes:[...retired].join(',')};
  for(const r of ['parent','child']) { delete next[r+'BootstrapExpiresAt']; Object.assign(next,{[r+'BootstrapHash']:config.roles[r].sha256,[r+'BootstrapConsumed']:false,[r+'BootstrapRevoked']:false,[r+'RebindGeneration']:0}); }
  return {reviewOnly:true,requiresSeparateOperatorApproval:true,conflictCheck:'NO CONFLICT',preservesLocationAndEvents:true,commit:{writes:[{update:{name:ROOT+'/families/family-01',fields:fields(next)},currentDocument:{updateTime:f.updateTime}}]}};
}
export function recoveryPlan(f, device, role, capability, now=Date.now()) {
  if(!['parent','child'].includes(role) || f.schemaVersion!==232 || f.familyId!=='family-01' || f.childDeviceId!=='child-01' || !f.updateTime || !Number.isSafeInteger(f.epoch) || f.epoch<1 || f.epoch>=Number.MAX_SAFE_INTEGER || Object.keys(capability || {}).sort().join()!=='expiresAt,sha256' || !/^[A-Za-z0-9_-]{43}$/.test(capability.sha256) || Buffer.from(capability.sha256,'base64url').toString('base64url')!==capability.sha256 || !Number.isSafeInteger(capability.expiresAt) || capability.expiresAt<=now || capability.expiresAt>now+86400000) throw Error('Invalid offline recovery snapshot/capability');
  const retiredCaps=new Set((f.retiredBootstrapHashes || '').split(',').filter(Boolean));
  for(const r of ['parent','child']) if(f[r+'BootstrapHash']) retiredCaps.add(f[r+'BootstrapHash']);
  if(retiredCaps.has(capability.sha256)) throw Error('Recovery requires new capability');
  const next={...f,epoch:f.epoch+1,locked:false,retiredBootstrapHashes:[...new Set([...(f.retiredBootstrapHashes || '').split(',').filter(Boolean),f[role+'BootstrapHash']].filter(Boolean))].join(',')};
  for(const [suffix,list] of [['Uid','retiredUidHashes'],['Key','retiredKeyHashes'],['DeviceBindingHash','retiredBindingHashes']]) {
    const values=new Set((f[list] || '').split(',').filter(Boolean));
    if(f[role+suffix]) values.add(suffix==='DeviceBindingHash'?f[role+suffix]:hash(f[role+suffix])); next[list]=[...values].join(','); delete next[role+suffix];
  }
  for(const suffix of ['RegisteredAt','ReboundAt','RebindProofHash','ClaimProofHash','BootstrapConsumedAt']) delete next[role+suffix];
  const generation=f[role+'RebindGeneration'] || 0;
  if(!Number.isSafeInteger(generation) || generation<0 || generation>=Number.MAX_SAFE_INTEGER) throw Error('Invalid generation');
  Object.assign(next,{[role+'BootstrapHash']:capability.sha256,[role+'BootstrapMode']:'RECOVERY_EXPIRING',[role+'BootstrapExpiresAt']:capability.expiresAt,[role+'BootstrapConsumed']:false,[role+'BootstrapRevoked']:false,[role+'RebindGeneration']:generation+1});
  const audit={role,oldEpoch:f.epoch,newEpoch:next.epoch,time:now,oldUidHash:f[role+'Uid']?hash(f[role+'Uid']):'',oldBindingHash:f[role+'DeviceBindingHash'] || ''};
  const writes=[{update:{name:ROOT+'/families/family-01',fields:fields(next)},currentDocument:{updateTime:f.updateTime}},{update:{name:ROOT+'/familyRecovery/'+next.epoch+'-'+role,fields:fields(audit)},currentDocument:{exists:false}}];
  if(role==='child') {
    if(!device.updateTime || !Number.isSafeInteger(device.fcmTokenGeneration || 0) || (device.fcmTokenGeneration || 0)>=Number.MAX_SAFE_INTEGER) throw Error('Child token CAS revision required');
    const values={fcmToken:'',fcmTokenOwnerUid:'',fcmTokenGeneration:(device.fcmTokenGeneration || 0)+1,fcmTokenUpdatedAt:now};
    writes.push({update:{name:ROOT+'/devices/child-01',fields:fields(values)},updateMask:{fieldPaths:Object.keys(values)},currentDocument:{updateTime:device.updateTime}});
  }
  return {reviewOnly:true,requiresSeparateOperatorApproval:true,preservesLocationAndEvents:true,commit:{writes}};
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  const a=process.argv.slice(2);
  if(a.length!==6 || a[0]!=='--snapshot' || a[2]!=='--hashes' || a[4]!=='--output') throw Error('Usage: --snapshot protected-snapshot.json --hashes hash-only-manifest.json --output review-plan.json; no apply option');
  const read=p=>JSON.parse(readFileSync(p,'utf8').replace(/^\uFEFF/,''));
  writeFileSync(a[5],JSON.stringify(migrationPlan(read(a[1]),read(a[3])),null,2)+'\n',{flag:'wx'});
  console.log('NO CONFLICT: offline CAS migration proposal only; no production writes.');
}
