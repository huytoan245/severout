import {ApiError,unb64,b64} from './google.js';
const utf8=new TextEncoder();
export function approvalEnvelope(value){
  if(value==null)return null;
  if(!value||typeof value!=='object'||Object.keys(value).sort().join(',')!=='payload,signature'||typeof value.payload!=='string'||typeof value.signature!=='string'||value.payload.length>8192||value.signature.length>1024)throw new ApiError('invalid_operator_approval',400);
  return {payload:value.payload,signature:value.signature};
}
function standardBytes(value){
  if(!/^[A-Za-z0-9+/]+={0,2}$/.test(value))throw new ApiError('invalid_operator_approval',400);
  try{return Uint8Array.from(atob(value),c=>c.charCodeAt(0));}catch{throw new ApiError('invalid_operator_approval',400);}
}
export async function operatorKey(env){
  const raw=env.PARENT_RECOVERY_OPERATOR_PUBLIC_KEY;
  if(typeof raw!=='string'||raw.length>1000)throw new ApiError('operator_approval_not_configured',503);
  try{const key=await crypto.subtle.importKey('spki',standardBytes(raw),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);if((key.algorithm.modulusLength||0)<2048)throw Error();return key;}catch{throw new ApiError('operator_approval_not_configured',503);}
}
export async function recoveryCapability(env){
  if(!env.PARENT_RECOVERY_OPERATOR_PUBLIC_KEY)return {parentRecoveryGuard:0};
  try{await operatorKey(env);return {parentRecoveryGuard:1,parentRecoveryOperatorKeyHash:b64(await crypto.subtle.digest('SHA-256',utf8.encode(env.PARENT_RECOVERY_OPERATOR_PUBLIC_KEY)))}}catch{return {parentRecoveryGuard:0};}
}
export async function verifyApproval(env,envelope,guard,now){
  if(!envelope||!guard)throw new ApiError('operator_approval_required',403);
  const raw=standardBytes(envelope.payload);
  let verified=false;try{verified=await crypto.subtle.verify('RSASSA-PKCS1-v1_5',await operatorKey(env),standardBytes(envelope.signature),raw);}catch(e){if(e instanceof ApiError)throw e;}
  if(!verified)throw new ApiError('operator_approval_invalid',403);
  let p;try{p=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(raw));}catch{throw new ApiError('invalid_operator_approval',400);}
  if(p.scope!=='PARENT_ONLY_REBIND'||p.bindingMatched!==true||!Number.isSafeInteger(p.issuedAt)||!Number.isSafeInteger(p.expiresAt)||p.issuedAt>now||p.expiresAt<=now||p.expiresAt-p.issuedAt>3600000||
     p.revision!==guard.revision||p.epoch!==guard.epoch||p.generation!==guard.generation||p.operationId!==guard.operationId||
     !/^[A-Za-z0-9_-]{43}$/.test(p.materialHash||'')||!/^[A-Za-z0-9_-]{43}$/.test(p.reportHash||'')||!/^[A-Za-z0-9_-]{43}$/.test(p.oldParentUidHash||''))throw new ApiError('operator_approval_scope_invalid',403);
  return p;
}
