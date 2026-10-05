import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { validateHashes } from './bootstrap-plan.mjs';
const ROOT = 'projects/family-location-884e5/databases/(default)/documents';
const fields = values => Object.fromEntries(Object.entries(values).filter(([k]) => k !== 'updateTime').map(([k, v]) => [k, typeof v === 'number' ? { integerValue: String(v) } : typeof v === 'boolean' ? { booleanValue: v } : { stringValue: String(v) }]));

// OFFLINE ONLY: consumes an admin-read normalized snapshot, emits a proposed
// conditional commit. No network, credentials, SDK calls or apply option.
export function recoveryPlan(family, device, role, now = Date.now(), bootstrap) {
  if (!['parent', 'child', 'both'].includes(role) || family.familyId !== 'family-01' || family.childDeviceId !== 'child-01' || !family.updateTime || !Number.isSafeInteger(family.epoch) || family.epoch < 1) throw new Error('Invalid fixed-family admin snapshot or recovery role');
  const next = { ...family, epoch: family.epoch + 1, locked: false };
  const recovered = role === 'both' ? ['parent', 'child'] : [role];
  validateHashes(bootstrap, recovered, now);
  const retiredCaps = new Set((family.retiredBootstrapHashes || '').split(',').filter(Boolean));
  const knownCaps = new Set(retiredCaps);
  for (const name of ['parent','child']) if (family[name+'BootstrapHash']) knownCaps.add(family[name+'BootstrapHash']);
  const retired = new Set((family.retiredUidHashes || '').split(',').filter(Boolean));
  for (const name of recovered) {
    const fresh = bootstrap.roles[name];
    if (knownCaps.has(fresh.sha256)) throw new Error('Recovery requires a NEW capability; old hashes can never reopen a slot');
    if (family[name+'BootstrapHash']) retiredCaps.add(family[name+'BootstrapHash']);
    if (family[name + 'Uid']) retired.add(createHash('sha256').update(family[name + 'Uid']).digest('base64url'));
    delete next[name + 'Uid']; delete next[name + 'Key']; delete next[name + 'RegisteredAt'];
    delete next[name+'ClaimProofHash']; delete next[name+'BootstrapConsumedAt'];
    Object.assign(next,{[name+'BootstrapHash']:fresh.sha256,[name+'BootstrapExpiresAt']:fresh.expiresAt,[name+'BootstrapConsumed']:false});
  }
  next.retiredUidHashes = [...retired].join(',');
  next.retiredBootstrapHashes = [...retiredCaps].join(',');
  const audit = { familyId: 'family-01', childDeviceId: 'child-01', oldEpoch: family.epoch, newEpoch: next.epoch, recoveryRole: role, requestedAt: now, oldParentUid: family.parentUid || '', oldChildUid: family.childUid || '' };
  const writes = [
    { update: { name: `${ROOT}/familyRecovery/${family.epoch}-${now}`, fields: fields(audit) }, currentDocument: { exists: false } },
    { update: { name: `${ROOT}/families/family-01`, fields: fields(next) }, currentDocument: { updateTime: family.updateTime } }
  ];
  if (role !== 'parent') {
    if (!device.updateTime || !Number.isSafeInteger(device.fcmTokenGeneration || 0) || (device.fcmTokenGeneration || 0) >= Number.MAX_SAFE_INTEGER) throw new Error('Read current Child document revision before planning Child recovery');
    const values = { fcmToken: '', fcmTokenOwnerUid: '', fcmTokenGeneration: (device.fcmTokenGeneration || 0) + 1, fcmTokenUpdatedAt: now };
    writes.push({ update: { name: `${ROOT}/devices/child-01`, fields: fields(values) }, updateMask: { fieldPaths: Object.keys(values) }, currentDocument: { updateTime: device.updateTime } });
  }
  return { reviewOnly: true, requiresSeparateOperatorApproval: true, familyId: 'family-01', role, preservesLocationAndEvents: true, commit: { writes } };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2), get = key => args[args.indexOf(key) + 1];
  if (args.length !== 8 || !args.includes('--snapshot') || !args.includes('--role') || !args.includes('--hashes') || !args.includes('--output') || args.some(x => !['--snapshot', '--role', '--hashes', '--output', get('--snapshot'), get('--role'), get('--hashes'), get('--output')].includes(x))) throw new Error('Usage: node recovery-plan.mjs --snapshot public-admin-snapshot.json --role parent|child|both --hashes fresh-public-hashes.json --output review-plan.json. No apply/deploy option.');
  const snapshot = JSON.parse(readFileSync(get('--snapshot'), 'utf8'));
  const plan = recoveryPlan(snapshot.family, snapshot.device || {}, get('--role'), Date.now(), JSON.parse(readFileSync(get('--hashes'), 'utf8').replace(/^\uFEFF/,'')));
  writeFileSync(get('--output'), JSON.stringify(plan, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
  console.log('Offline conditional recovery plan created for review. No cloud writes or credential reads.');
}
