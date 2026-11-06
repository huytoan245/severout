import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign} from 'node:crypto';
import {harness,installation,NOW} from './enrollment-fixture.mjs';
import {digest,proofMessage} from '../src/enrollment.js';
import {b64} from '../src/google.js';
import {recoveryCapability} from '../src/recovery-approval.js';
const operator=generateKeyPairSync('rsa',{modulusLength:2048});
function envelope(payload,key=operator.privateKey){const bytes=Buffer.from(JSON.stringify(payload));return {payload:bytes.toString('base64'),signature:sign('RSA-SHA256',bytes,key).toString('base64')};}
async function setup(){
 const h=harness(),old=await installation('old-parent','parent'),child=await installation('child','child');await h.enroll(old);await h.enroll(child);
 h.env.PARENT_RECOVERY_OPERATOR_PUBLIC_KEY=operator.publicKey.export({format:'der',type:'spki'}).toString('base64');
 h.documents.get('families/family-01').updateTime='2026-11-06T08:00:00.123456Z';
 const fresh=await installation('new-parent','parent',old.material),guard={revision:'2026-11-06T08:00:00.123456Z',epoch:1,generation:0,operationId:crypto.randomUUID()};
 const consent={scope:'PARENT_ONLY_REBIND',bindingMatched:true,issuedAt:NOW-1000,expiresAt:NOW+60000,...guard,reportHash:await digest('reviewed-report'),materialHash:await digest(old.material),oldParentUidHash:await digest(old.uid)};
 return {h,old,child,fresh,guard,consent};
}
const challenge=(x,approval,guard=x.guard)=>x.h.call(x.fresh,'challenge',{familyId:'family-01',deviceId:'child-01',role:'parent',purpose:'rebind'},guard,approval);
async function proof(x,approval){
 const c=await challenge(x,approval);assert.equal(c.status,200);
 const raw=JSON.stringify({familyId:'family-01',deviceId:'child-01',version:'2.3.2',deviceRecoveryMaterial:x.fresh.material});
 return {nonce:c.body.nonce,publicKey:x.fresh.publicKey,payload:raw,signature:b64(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},x.fresh.pair.privateKey,new TextEncoder().encode(proofMessage(x.fresh.uid,'parent',c.body.nonce,'rebind',await digest(raw)))))};
}
test('server requires independent operator signature even with known device material/fresh Auth/key',async()=>{
 const x=await setup();assert.equal((await challenge(x,null)).body.error,'operator_approval_required');
 assert.equal((await challenge(x,null,null)).body.error,'operator_approval_required');
 assert.equal((await x.h.api.readFamily()).parentUid,x.old.uid);
});
test('reviewed signed permit authorizes exact Parent snapshot once, Child remains unchanged',async()=>{
 const x=await setup(),a=envelope(x.consent),p=await proof(x,a);const child=(await x.h.api.readFamily()).childKey;
 assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard,a)).status,200);x.h.restart();
 assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard,a)).status,200);
 const f=await x.h.api.readFamily();assert.equal(f.epoch,2);assert.equal(f.childKey,child);
 assert.equal((await x.h.call(x.child,'state')).status,200);
});
test('forged signature, different operator and changed signed payload are rejected server-side',async()=>{
 const x=await setup();const other=generateKeyPairSync('rsa',{modulusLength:2048});
 const tampered=envelope(x.consent);tampered.payload=Buffer.from(JSON.stringify({...x.consent,epoch:99})).toString('base64');
 for(const a of [envelope(x.consent,other.privateKey),tampered,{payload:'bm90LWpzb24=',signature:'YmFk'}])assert.equal((await challenge(x,a)).status,403);
});
test('expired/future/wrong role or snapshot consent cannot issue a nonce',async()=>{
 const x=await setup();for(const changes of [{expiresAt:NOW},{issuedAt:NOW+1},{scope:'CHILD_REBIND'},{revision:'2026-11-06T08:00:00.999999Z'},{oldParentUidHash:await digest('other-parent')}]){
  assert.equal((await challenge(x,envelope({...x.consent,...changes}))).status,403);
 }
});
test('approval is bound to nonce and exact same-device material at POST',async()=>{
 const x=await setup(),a=envelope(x.consent),p=await proof(x,a);
 const changed=envelope({...x.consent,reportHash:await digest('other-report')});
 assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard,changed)).body.error,'operator_approval_changed');
 const y=await setup(),bad=envelope({...y.consent,materialHash:await digest('wrong-material')}),q=await proof(y,bad);
 assert.equal((await y.h.call(y.fresh,'rebindParent',q,y.guard,bad)).body.error,'operator_approval_scope_invalid');
});
test('health advertises only a valid configured operator key and its public digest',async()=>{
 assert.equal((await recoveryCapability({})).parentRecoveryGuard,0);
 assert.equal((await recoveryCapability({PARENT_RECOVERY_OPERATOR_PUBLIC_KEY:'invalid'})).parentRecoveryGuard,0);
 const x=await setup(),c=await recoveryCapability(x.h.env);assert.equal(c.parentRecoveryGuard,1);assert.equal(c.parentRecoveryOperatorKeyHash,await digest(x.h.env.PARENT_RECOVERY_OPERATOR_PUBLIC_KEY));
});
test('guarded Parent commit writes only Parent/epoch/retirement fields, never Child metadata',async()=>{
 const x=await setup(),a=envelope(x.consent),p=await proof(x,a),call=x.h.api.call.bind(x.h.api);let captured;
 x.h.api.call=async(url,options={})=>{if(url.endsWith('/documents:commit'))captured=JSON.parse(options.body).writes;return call(url,options);};
 assert.equal((await x.h.call(x.fresh,'rebindParent',p,x.guard,a)).status,200);
 assert.deepEqual(captured[0].updateMask.fieldPaths,['epoch','parentUid','parentKey','parentRebindGeneration','parentReboundAt','parentRebindProofHash','retiredUidHashes','retiredKeyHashes']);
 assert.equal(Object.keys(captured[0].update.fields).some(k=>k.startsWith('child')),false);
 assert.equal(captured.length,2);assert.equal(captured[1].currentDocument.exists,false);
});
test('shared old Parent/Child key cannot be retired by Parent recovery',async()=>{
 const x=await setup();const f=x.h.documents.get('families/family-01');f.fields.childKey=structuredClone(f.fields.parentKey);
 assert.equal((await challenge(x,envelope(x.consent))).body.error,'role_key_conflict');
 assert.equal((await x.h.api.readFamily()).parentUid,x.old.uid);
});
