import { ApiError, b64, unb64 } from './google.js';
const utf8 = new TextEncoder();
export const canonicalHash = s => typeof s === 'string' && /^[A-Za-z0-9_-]{43}$/.test(s) && b64(unb64(s)) === s;
export function constantTimeEqual(a, b) {
  if (!canonicalHash(a) || !canonicalHash(b)) return false;
  const x = unb64(a), y = unb64(b); let different = 0;
  for (let i = 0; i < 32; i++) different |= x[i] ^ y[i];
  return different === 0;
}
export async function bindingHash(pepper, role, material) {
  if (!canonicalHash(pepper)) throw new ApiError('binding_not_configured');
  if (!['parent','child'].includes(role) || !canonicalHash(material)) throw new ApiError('invalid_recovery_material', 400);
  const key = await crypto.subtle.importKey('raw', unb64(pepper), {name:'HMAC',hash:'SHA-256'}, false, ['sign']);
  // Server domain separation also protects against equal client inputs across roles.
  return b64(await crypto.subtle.sign('HMAC', key, utf8.encode(`FL232-BIND\nfamily-01\n${role}\n${material}`)));
}
