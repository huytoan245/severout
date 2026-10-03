import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
const persist = mkdtempSync(join(tmpdir(), 'family-wake-runtime-test-'));
const options = convertV4MiniflareOptions({ scriptPath: resolve('dist/worker.js'), modules: true, compatibilityDate: '2026-10-03',
  durableObjects: { WAKE_STATE: { className: 'WakeCoordinator', useSQLite: true } }, resourcePersistencePath: persist,
  bindings: { FIREBASE_PROJECT_ID: 'family-location-884e5', PARENT_UID: 'test-parent', CHILD_UID: 'test-child', GOOGLE_SERVICE_ACCOUNT_JSON: '{}' } });
const id = Date.now();
const req = () => new Request('https://internal/v1/wake', { method: 'POST', body: JSON.stringify({ deviceId: 'child-01', requestId: String(id), requestedAt: id }) });
let mf = new Miniflare(options);
try {
  const health = await mf.dispatchFetch('https://local/health'); assert.equal((await health.json()).version, '2.2.11');
  const publicDenied = await mf.dispatchFetch('https://local/v1/wake', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ deviceId: 'child-01', requestId: String(id), requestedAt: id }) }); assert.equal(publicDenied.status, 401);
  let ns = await mf.getDurableObjectNamespace('WAKE_STATE'); let stub = ns.get(ns.idFromName('child-01'));
  const accepted = await stub.fetch(req()); assert.equal(accepted.status, 200);
  for (let i = 1; i < 12; i++) assert.notEqual((await stub.fetch(req())).status, 429);
  await mf.dispose(); mf = new Miniflare(options);
  ns = await mf.getDurableObjectNamespace('WAKE_STATE'); stub = ns.get(ns.idFromName('child-01'));
  assert.equal((await stub.fetch(req())).status, 429);
  console.log('PASS: actual workerd runtime, SQLite DO binding, public auth denial, durable acceptance and rate state after runtime restart. Test identities only; no Google calls or production deployment.');
} finally { await mf.dispose(); }
