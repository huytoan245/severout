import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { generateKeyPairSync, sign as rsaSign } from 'node:crypto';
import { b64 } from '../src/google.js';
import { digest, proofMessage } from '../src/enrollment.js';
const bundled = spawnSync(process.execPath, ['node_modules/wrangler/bin/wrangler.js', 'deploy', '--dry-run', '--outdir', 'dist'], { encoding: 'utf8', env: { ...process.env, WRANGLER_SEND_METRICS: 'false' } });
assert.equal(bundled.status, 0, 'Fresh Worker dry-run must succeed before runtime test');
const persist = mkdtempSync(join(tmpdir(), 'family-wake-runtime-test-'));
// Ephemeral TEST service-account key only; every outbound URL is intercepted.
const { privateKey, publicKey: rsaPublic } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const sa = { project_id: 'family-location-884e5', client_email: 'fixture@family-location-884e5.iam.gserviceaccount.com', private_key: privateKey.export({type:'pkcs8',format:'pem'}) };
let family = null, device = null, revision = 0, fcmSends = 0;
const testJwt = (uid, overrides = {}) => {
  const sec=Math.floor(Date.now()/1000);
  const h=b64(new TextEncoder().encode(JSON.stringify({alg:'RS256',kid:'runtime-fixture'})));
  const p=b64(new TextEncoder().encode(JSON.stringify({aud:'family-location-884e5',iss:'https://securetoken.google.com/family-location-884e5',sub:uid,iat:sec,auth_time:sec,exp:sec+3600,...overrides})));
  return h+'.'+p+'.'+b64(rsaSign('RSA-SHA256',Buffer.from(h+'.'+p),privateKey));
};
const id = Date.now();
const outbound = async request => {
  const url = new URL(request.url);
  if (url.hostname === 'www.googleapis.com') return Response.json({keys:[{...rsaPublic.export({format:'jwk'}),kid:'runtime-fixture',alg:'RS256'}]});
  if (url.hostname === 'fcm.googleapis.com') {
    const b=await request.json();assert.equal(b.message.token,'runtime-child-token');assert.equal(b.message.android.priority,'HIGH');assert.equal(b.message.data.deviceId,'child-01');fcmSends++;return Response.json({name:'projects/family-location-884e5/messages/runtime-fixture'});
  }
  if (url.hostname === 'oauth2.googleapis.com') return Response.json({access_token:'fixture-oauth',expires_in:3600});
  if (url.hostname !== 'firestore.googleapis.com') throw new Error('Unexpected outbound network attempt blocked');
  if (url.pathname.endsWith('/documents:commit')) {
    const writes=(await request.json()).writes;
    for(const w of writes) {const previous=w.update.name.endsWith('/families/family-01')?family:device;if(w.currentDocument.updateTime && previous?.updateTime!==w.currentDocument.updateTime) return new Response('',{status:409});}
    for(const w of writes) {const isFamily=w.update.name.endsWith('/families/family-01');const previous=isFamily?family:device;const updated={fields:{...previous?.fields,...w.update.fields},updateTime:String(++revision)};if(isFamily) family=updated;else device=updated;}
    return Response.json({});
  }
  const isFamily = url.pathname.endsWith('/families/family-01');
  if (!isFamily && !url.pathname.endsWith('/devices/child-01')) throw new Error('Unexpected Firestore target');
  const previous = isFamily ? family : device;
  if (request.method === 'PATCH') {
    const condition = url.searchParams.get('currentDocument.updateTime');
    if ((condition && previous?.updateTime !== condition) || (url.searchParams.get('currentDocument.exists') === 'false' && previous)) return new Response('',{status:409});
    const updated = {fields:{...previous?.fields,...(await request.json()).fields},updateTime:String(++revision)};
    if (isFamily) family=updated; else device=updated;
    return Response.json(updated);
  }
  return previous ? Response.json(previous) : new Response('',{status:404});
};
const options = convertV4MiniflareOptions({ scriptPath: resolve('dist/worker.js'), modules: true, compatibilityDate: '2026-10-03',
  durableObjects: { WAKE_STATE: { className: 'WakeCoordinator', useSQLite: true }, FAMILY_REGISTRY: {className:'FamilyRegistry',useSQLite:true} }, resourcePersistencePath: persist,
  outboundService: outbound,
  bindings: { FIREBASE_PROJECT_ID: 'family-location-884e5', ENROLLMENT_ENABLED:'true', GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify(sa) } });
const req = () => new Request('https://internal/v1/wake', { method: 'POST', body: JSON.stringify({command:{deviceId:'child-01',requestId:String(id),requestedAt:id},binding:{parentUid:'test-parent',epoch:1}}) });
let mf = new Miniflare(options);
const publicCall=async (uid,path,body,overrides={})=>mf.dispatchFetch('https://local'+path,{method:body==null?'GET':'POST',headers:{'content-type':'application/json',Authorization:'Bearer '+testJwt(uid,overrides)},...(body==null?{}:{body:JSON.stringify(body)})});
const call = async (stub, uid, operation, body) => stub.fetch(new Request('https://registry.internal',{method:'POST',body:JSON.stringify({uid,operation,body})}));
try {
  const health = await mf.dispatchFetch('https://local/health'); assert.equal((await health.json()).version, '2.3.1');
  const publicDenied = await mf.dispatchFetch('https://local/v1/wake', {method:'POST',headers:{'content-type':'application/json'},body:'{}'}); assert.equal(publicDenied.status,401);
  let registryNs = await mf.getDurableObjectNamespace('FAMILY_REGISTRY'), registry = registryNs.get(registryNs.idFromName('family-01'));
  const pair = await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
  const publicKey = b64(await crypto.subtle.exportKey('spki',pair.publicKey));
  const c = await publicCall('test-parent','/v1/challenge',{familyId:'family-01',deviceId:'child-01',role:'parent',purpose:'register'}); assert.equal(c.status,200, await c.clone().text());
  const nonce = (await c.json()).nonce;
  // Restart real workerd after durable challenge, before registration response.
  await mf.dispose(); mf = new Miniflare(options);
  registryNs = await mf.getDurableObjectNamespace('FAMILY_REGISTRY'); registry=registryNs.get(registryNs.idFromName('family-01'));
  const payload=JSON.stringify({familyId:'family-01',deviceId:'child-01',version:'2.3.1'});
  const signature=b64(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},pair.privateKey,new TextEncoder().encode(proofMessage('test-parent','parent',nonce,'register',await digest(payload)))));
  const proof={nonce,signature,publicKey,payload};
  assert.equal((await publicCall('test-parent','/v1/register/parent',proof)).status,200);
  assert.equal((await call(registry,'test-parent','registerParent',proof)).status,200);
  const childKeys=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
  const childPublic=b64(await crypto.subtle.exportKey('spki',childKeys.publicKey));
  const childProof=async(purpose,values)=>{
    const response=await publicCall('test-child','/v1/challenge',{familyId:'family-01',deviceId:'child-01',role:'child',purpose});assert.equal(response.status,200);
    const nonce=(await response.json()).nonce,payload=JSON.stringify(values);
    return {nonce,publicKey:childPublic,payload,signature:b64(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},childKeys.privateKey,new TextEncoder().encode(proofMessage('test-child','child',nonce,purpose,await digest(payload)))))};
  };
  assert.equal((await publicCall('test-child','/v1/register/child',await childProof('register',{familyId:'family-01',deviceId:'child-01',version:'2.3.1'}))).status,200);
  assert.equal((await publicCall('test-parent','/v1/family')).status,200);
  assert.equal((await publicCall('outsider','/v1/family')).status,403);
  assert.equal((await publicCall('test-parent','/v1/family',null,{aud:'wrong-project'})).status,401);
  assert.equal((await publicCall('test-child','/v1/challenge',{familyId:'other',deviceId:'child-01',role:'child',purpose:'register'})).status,400);
  device={updateTime:'fixture-device',fields:{refreshRequestedAt:{integerValue:String(id)},refreshExpiresAt:{integerValue:String(id+900000)},refreshRequestedBy:{stringValue:'test-parent'}}};
  assert.equal((await publicCall('test-child','/v1/token',await childProof('token',{familyId:'family-01',deviceId:'child-01',token:'runtime-child-token',generation:1}))).status,200);
  let ns=await mf.getDurableObjectNamespace('WAKE_STATE'),stub=ns.get(ns.idFromName('child-01'));
  assert.equal((await stub.fetch(req())).status,200);
  for(let i=1;i<12;i++) assert.notEqual((await stub.fetch(req())).status,429);
  assert.equal(fcmSends,1);
  await mf.dispose(); mf=new Miniflare(options);
  ns=await mf.getDurableObjectNamespace('WAKE_STATE');stub=ns.get(ns.idFromName('child-01'));
  assert.equal((await stub.fetch(req())).status,429);
  registryNs=await mf.getDurableObjectNamespace('FAMILY_REGISTRY');registry=registryNs.get(registryNs.idFromName('family-01'));
  assert.equal((await call(registry,'test-parent','registerParent',proof)).status,200);
  console.log('PASS: actual workerd/SQLite: public Firebase JWT verification, Parent/Child enrollment, token CAS and HIGH FCM dispatch; persisted nonce/enrollment/rate after restart; unauthorized UID/project denied. All Google traffic intercepted; no production deployment.');
} finally { await mf.dispose(); }
