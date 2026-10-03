const { test } = require("node:test");
const assert = require("node:assert/strict");
const { createWakeHandler, WAKE_TTL_MS } = require("./wake-handler");
const NOW = 1_800_000_000_000;
function fixture(initial = {}, send = async () => "message-1") {
  let state = { refreshRequestedAt: NOW - 1000, fcmToken: "token-A", ...initial };
  let clock = NOW;
  let tail = Promise.resolve();
  const sends = [];
  const ref = { get: async () => ({ data: () => ({ ...state }) }) };
  const db = { doc: path => { assert.equal(path, "devices/child-01"); return ref; }, runTransaction: body => {
    const result = tail.then(async () => {
      const updates = [];
      const value = await body({ get: ref.get, set: (r, patch) => { assert.equal(r, ref); updates.push(patch); } });
      updates.forEach(patch => { state = { ...state, ...patch }; });
      return value;
    });
    tail = result.catch(() => {}); return result;
  } };
  const handler = createWakeHandler({ db, messaging: { send: async m => { sends.push(m); return send(m, () => state, p => { state = { ...state, ...p }; }); } }, now: () => clock });
  const event = (before = {}, after = state) => ({ data: { before: { data: () => before }, after: { data: () => ({ ...after }) } } });
  return { handler, event, sends, state: () => state, patch: p => { state = { ...state, ...p }; }, advance: ms => { clock += ms; } };
}
test("fresh request sends high priority with remaining TTL and records dispatch", async () => {
  const f = fixture(); await f.handler(f.event());
  assert.equal(f.sends.length, 1); assert.equal(f.sends[0].android.priority, "high");
  assert.equal(f.sends[0].android.ttl, WAKE_TTL_MS - 1000); assert.equal(f.state().wakeDispatchResult, "sent");
});
test("repeated and unordered delivery use current request and dedupe", async () => {
  const f = fixture(); await f.handler(f.event()); await f.handler(f.event());
  assert.equal(f.sends.length, 1);
  f.patch({ refreshRequestedAt: NOW + 100 }); await f.handler(f.event({}, { refreshRequestedAt: NOW - 2000 }));
  assert.equal(f.sends.at(-1).data.requestId, String(NOW + 100));
});
test("concurrent delivery cannot send while another owner holds lease", async () => {
  let release; const wait = new Promise(r => { release = r; });
  const f = fixture({}, async () => { await wait; return "message"; });
  const first = f.handler(f.event()); await new Promise(r => setImmediate(r));
  await assert.rejects(f.handler(f.event()), /lease busy/); release(); await first;
  await f.handler(f.event()); assert.equal(f.sends.length, 1);
});
test("expired requests and far-future timestamps never send", async () => {
  for (const refreshRequestedAt of [NOW - WAKE_TTL_MS, NOW + 61_000]) {
    const f = fixture({ refreshRequestedAt }); await f.handler(f.event());
    assert.equal(f.sends.length, 0); assert.equal(f.state().wakeDispatchResult, "expired");
  }
});
test("missing token then late token publication dispatches pending request", async () => {
  const f = fixture({ fcmToken: null }); await f.handler(f.event());
  const before = { ...f.state() }; f.patch({ fcmToken: "late-token" });
  await f.handler(f.event(before)); assert.equal(f.sends.length, 1);
});
test("rotation after sent re-dispatches same pending request to new token", async () => {
  const f = fixture(); await f.handler(f.event()); const before = { ...f.state() };
  f.patch({ fcmToken: "token-B" }); await f.handler(f.event(before));
  assert.deepEqual(f.sends.map(m => m.token), ["token-A", "token-B"]);
});
test("delayed invalid-token error never clears newly rotated token", async () => {
  const f = fixture({}, async (_, get, patch) => { patch({ fcmToken: "token-B" }); throw Object.assign(new Error("invalid"), { code: "messaging/registration-token-not-registered" }); });
  await f.handler(f.event()); assert.equal(f.state().fcmToken, "token-B");
});
test("invalid current token is cleared and transient failure is retried", async () => {
  const f = fixture({}, async () => { throw Object.assign(new Error("invalid"), { code: "messaging/invalid-registration-token" }); });
  await f.handler(f.event()); assert.equal(f.state().fcmToken, null);
  let count = 0;
  const g = fixture({}, async () => { if (!count++) throw Object.assign(new Error("temporary"), { code: "messaging/server-unavailable" }); return "ok"; });
  await assert.rejects(g.handler(g.event())); await g.handler(g.event()); assert.equal(g.state().wakeDispatchResult, "sent");
});
test("owner crash lease expiry permits retry; unexpired lease retries", async () => {
  const f = fixture({ wakeLeaseFor: NOW - 1000, wakeLeaseUntil: NOW + 10_000, wakeLeaseOwner: "dead" });
  await assert.rejects(f.handler(f.event()), /lease busy/); f.advance(10_001);
  await f.handler(f.event()); assert.equal(f.sends.length, 1);
});
test("completion/failure suppress dispatch and diagnostics do not retrigger", async () => {
  for (const field of ["refreshCompletedFor", "refreshFailedFor"]) {
    const f = fixture({ [field]: NOW - 1000 }); await f.handler(f.event()); assert.equal(f.sends.length, 0);
  }
  const f = fixture(); const before = { ...f.state() }; f.patch({ heartbeatAt: NOW }); await f.handler(f.event(before)); assert.equal(f.sends.length, 0);
});
