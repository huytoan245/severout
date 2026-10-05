import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
export const ROOT = 'projects/family-location-884e5/databases/(default)/documents';
export const fields = values => Object.fromEntries(Object.entries(values).filter(([k]) => k !== 'updateTime').map(([k,v]) => [k, typeof v === 'number' ? {integerValue:String(v)} : typeof v === 'boolean' ? {booleanValue:v} : {stringValue:String(v)}]));
export function validateHashes(config, roles, now) {
  if (config?.familyId !== 'family-01' || config.deviceId !== 'child-01' || config.versionName !== '2.3.1' || config.versionCode !== 35 || Object.keys(config).sort().join() !== 'deviceId,familyId,roles,versionCode,versionName') throw new Error('Invalid public bootstrap hash configuration');
  if (!config.roles || Object.keys(config.roles).some(r => !['parent','child'].includes(r))) throw new Error('Invalid bootstrap roles');
  const hashes = [];
  for (const role of roles) {
    const value = config.roles[role];
    if (!value || Object.keys(value).sort().join() !== 'expiresAt,sha256' || !/^[A-Za-z0-9_-]{43}$/.test(value.sha256) || Buffer.from(value.sha256,'base64url').toString('base64url') !== value.sha256 || !Number.isSafeInteger(value.expiresAt) || value.expiresAt <= now || value.expiresAt > now + 604800000) throw new Error('Missing, invalid or expired role bootstrap hash');
    hashes.push(value.sha256);
  }
  if (new Set(hashes).size !== hashes.length) throw new Error('Role bootstrap hashes must be distinct');
}
// OFFLINE ONLY: initial provisioning cannot overwrite an existing family.
export function bootstrapPlan(config, now = Date.now()) {
  validateHashes(config, ['parent','child'], now);
  const family = {familyId:'family-01',childDeviceId:'child-01',epoch:1,locked:false};
  for (const role of ['parent','child']) Object.assign(family, { [role+'BootstrapHash']:config.roles[role].sha256, [role+'BootstrapExpiresAt']:config.roles[role].expiresAt, [role+'BootstrapConsumed']:false });
  return {reviewOnly:true,requiresSeparateOperatorApproval:true,commit:{writes:[{update:{name:ROOT+'/families/family-01',fields:fields(family)},currentDocument:{exists:false}}]}};
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args=process.argv.slice(2);
  if(args.length!==4 || args[0]!=='--hashes' || args[2]!=='--output') throw new Error('Usage: node bootstrap-plan.mjs --hashes public-hashes.json --output review-plan.json. No apply option.');
  const plan=bootstrapPlan(JSON.parse(readFileSync(args[1],'utf8').replace(/^\uFEFF/,'')));
  writeFileSync(args[3],JSON.stringify(plan,null,2)+'\n',{encoding:'utf8',flag:'wx'});
  console.log('Offline hash-only initial provisioning plan created. No cloud writes.');
}
