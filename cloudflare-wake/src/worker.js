import { ApiError, FirebaseVerifier } from './google.js';
export { WakeCoordinator } from './coordinator.js';
export { FamilyRegistry } from './enrollment.js';
const verifier = new FirebaseVerifier();
const routes = { '/v1/challenge': 'challenge', '/v1/register/parent': 'registerParent', '/v1/register/child': 'registerChild', '/v1/rebind/parent':'rebindParent', '/v1/rebind/child':'rebindChild', '/v1/token': 'token', '/v1/wake': 'wake', '/v1/family': 'state', '/v1/diagnostics': 'diagnostics' };
export async function readBody(request) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) throw new ApiError('invalid_content_type', 415);
  if (Number(request.headers.get('content-length')) > 8192) throw new ApiError('body_too_large', 413);
  if (!request.body) throw new ApiError('invalid_request', 400);
  const reader = request.body.getReader(); let size = 0; const chunks = [];
  while (true) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > 8192) { await reader.cancel(); throw new ApiError('body_too_large', 413); } chunks.push(part.value); }
  const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); } catch { throw new ApiError('invalid_request', 400); }
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return Response.json({ service: 'family-location-wake', version: '2.3.2', configured: Boolean(env.GOOGLE_SERVICE_ACCOUNT_JSON && env.DEVICE_BINDING_PEPPER) }, { headers: { 'Cache-Control': 'no-store' } });
    const operation = routes[url.pathname];
    if (!operation || url.search) return Response.json({ error: 'not_found' }, { status: 404 });
    const method = ['state', 'diagnostics'].includes(operation) ? 'GET' : 'POST';
    if (request.method !== method) return Response.json({ error: 'method_not_allowed' }, { status: 405, headers: { Allow: method } });
    try {
      if (env.FIREBASE_PROJECT_ID !== 'family-location-884e5' || !env.GOOGLE_SERVICE_ACCOUNT_JSON) throw new ApiError('server_not_configured');
      const match = /^Bearer ([A-Za-z0-9_.-]+)$/.exec(request.headers.get('authorization') || '');
      if (!match) throw new ApiError('unauthorized', 401);
      const uid = await verifier.verify(match[1]);
      const body = method === 'POST' ? await readBody(request) : {};
      const stub = env.FAMILY_REGISTRY.get(env.FAMILY_REGISTRY.idFromName('family-01'));
      return await stub.fetch(new Request('https://registry.internal', { method: 'POST', body: JSON.stringify({ uid, operation, body }) }));
    } catch (e) { return Response.json({ error: e instanceof ApiError ? e.code : 'backend_unavailable' }, { status: e instanceof ApiError ? e.status : 503, headers: { 'Cache-Control': 'no-store' } }); }
  }
};
