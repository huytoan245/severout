import { ApiError, FirebaseVerifier } from './google.js';
import { validateRequest } from './coordinator.js';
export { WakeCoordinator } from './coordinator.js';
const verifier = new FirebaseVerifier();
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return Response.json({ service: 'family-location-wake', version: '2.2.11', configured: Boolean(env.PARENT_UID && env.CHILD_UID && env.GOOGLE_SERVICE_ACCOUNT_JSON) }, { headers: { 'Cache-Control': 'no-store' } });
    if (url.pathname !== '/v1/wake') return Response.json({ error: 'not_found' }, { status: 404 });
    if (request.method !== 'POST') return Response.json({ error: 'method_not_allowed' }, { status: 405, headers: { Allow: 'POST' } });
    try {
      if (env.FIREBASE_PROJECT_ID !== 'family-location-884e5' || !env.PARENT_UID || !env.CHILD_UID || env.PARENT_UID === env.CHILD_UID || !env.GOOGLE_SERVICE_ACCOUNT_JSON) throw new ApiError('server_not_configured');
      if (!request.headers.get('content-type')?.startsWith('application/json')) throw new ApiError('invalid_content_type', 415);
      if (Number(request.headers.get('content-length')) > 1024) throw new ApiError('body_too_large', 413);
      if (!request.body) throw new ApiError('invalid_request', 400);
      const reader = request.body.getReader(); let size = 0; const chunks = [];
      while (true) { const part = await reader.read(); if (part.done) break; size += part.value.byteLength; if (size > 1024) { await reader.cancel(); throw new ApiError('body_too_large', 413); } chunks.push(part.value); }
      const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
      const text = new TextDecoder().decode(bytes);
      let body; try { body = JSON.parse(text); } catch { throw new ApiError('invalid_request', 400); }
      validateRequest(body);
      const match = /^Bearer ([A-Za-z0-9_.-]+)$/.exec(request.headers.get('authorization') || '');
      if (!match) throw new ApiError('unauthorized', 401);
      await verifier.verify(match[1], env.PARENT_UID);
      const stub = env.WAKE_STATE.get(env.WAKE_STATE.idFromName('child-01'));
      return await stub.fetch(new Request('https://wake.internal/v1/wake', { method: 'POST', body: JSON.stringify(body) }));
    } catch (e) { return Response.json({ error: e instanceof ApiError ? e.code : 'backend_unavailable' }, { status: e instanceof ApiError ? e.status : 503, headers: { 'Cache-Control': 'no-store' } }); }
  }
};
