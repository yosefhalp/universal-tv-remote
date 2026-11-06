import { test } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { startServer } from '../support/server/server.mjs';
import { Connection } from '../support/web/connection.mjs';
import { Cipher, randomId, randomSecret, sessionKeys } from '../support/web/protocol.mjs';

// סוכן בדיקה מוצפן מפעיל את אותם מסרים, דרך שרת אמיתי ושקעי TCP אמיתיים.
async function fixture(t) {
  const registrationKey = randomSecret(),
    device = randomId(),
    token = randomSecret(),
    controller = randomId(),
    secret = randomSecret();
  const server = await startServer({ port: 0, dataFile: null, registrationKey });
  const url = `ws://127.0.0.1:${server.port}`;
  const agent = new WebSocket(`${url}/v1/agent/${device}`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Karov-Enrollment': registrationKey },
  });
  await new Promise((ok, no) => {
    agent.once('open', ok);
    agent.once('error', no);
  });
  let cipher,
    keys,
    chain = Promise.resolve(),
    revoked = false;
  const calls = [];
  agent.on('message', (raw) => {
    chain = chain
      .then(async () => {
        const packet = JSON.parse(raw.toString());
        if (packet.t === 'peer-joined' || packet.t === 'peer-left') {
          cipher = null;
          keys = null;
          return;
        }
        if (packet.t === 'hello') {
          if (revoked || packet.controller !== controller) {
            agent.send('{"t":"denied"}');
            return;
          }
          const nonce = randomSecret();
          keys = await sessionKeys(secret, device, controller, packet.nonce, nonce);
          cipher = new Cipher(keys, false);
          agent.send(JSON.stringify({ t: 'challenge', nonce, proof: await keys.proof('agent') }));
        } else if (packet.t === 'auth') {
          if (!(await keys.verify('client', packet.proof))) {
            agent.send('{"t":"denied"}');
            return;
          }
          agent.send(JSON.stringify(await cipher.seal({ type: 'ready', info: { sdk: 34 } })));
        } else if (packet.t === 'data') {
          const request = await cipher.open(packet);
          calls.push(request.method);
          if (request.method === 'wait') return;
          if (request.method === 'revoke') revoked = true;
          agent.send(
            JSON.stringify(await cipher.seal({ id: request.id, ok: true, value: request.args })),
          );
        }
      })
      .catch(() => agent.close());
  });
  const clients = [];
  function client(overrides = {}) {
    const client = new Connection({
      url,
      device,
      controller,
      secret,
      WebSocketClass: WebSocket,
      ...overrides,
    });
    clients.push(client);
    return client;
  }
  t.after(async () => {
    clients.forEach((c) => c.close());
    agent.terminate();
    await server.close();
  });
  return { client, calls, agent };
}

test('חיבור מוצפן משלים אימות, מחזיר תשובות מקבילות וחוסם מכשיר שני', async (t) => {
  const f = await fixture(t),
    c = f.client();
  assert.equal((await c.connect()).sdk, 34);
  const values = await Promise.all(
    Array.from({ length: 8 }, (_, n) => c.call('echo', { n, text: 'שלום' })),
  );
  assert.deepEqual(
    values.map((v) => v.n),
    [0, 1, 2, 3, 4, 5, 6, 7],
  );
  await assert.rejects(f.client().connect(), /BUSY/);
});
test('סוד שגוי וביטול הרשאה אינם מאפשרים שליטה', async (t) => {
  const f = await fixture(t);
  await assert.rejects(f.client({ secret: randomSecret() }).connect(), /BAD_IDENTITY/);
  // ממתינים לאירוע הסגירה האמיתי לפני הקמת סשן חדש.
  await new Promise((r) => setTimeout(r, 30));
  const c = f.client();
  await c.connect();
  await c.call('revoke');
  c.close();
  await new Promise((r) => setTimeout(r, 30));
  await assert.rejects(f.client().connect(), /DENIED/);
  assert.deepEqual(f.calls, ['revoke']);
});
test('ניתוק דוחה בקשות תלויות ואינו משדר לחיצות מחדש', async (t) => {
  const f = await fixture(t),
    c = f.client();
  await c.connect();
  const waiting = c.call('wait');
  const rejected = assert.rejects(waiting, /DISCONNECTED|OFFLINE/);
  await new Promise((r) => setTimeout(r, 20));
  f.agent.terminate();
  await rejected;
  assert.equal(c.pending.size, 0);
  assert.deepEqual(f.calls, ['wait']);
});
test('בקשה שלא נענית פוקעת וסגירה בזמן חיבור אינה משאירה Promise תלוי', async (t) => {
  const f = await fixture(t),
    c = f.client();
  await c.connect();
  await assert.rejects(c.call('wait', {}, 30), /REQUEST_TIMEOUT/);
  assert.equal(c.pending.size, 0);
  assert.equal(c.ready, false);
  assert.deepEqual(f.calls, ['wait']);
  c.close();
  const next = f.client();
  const connecting = next.connect();
  next.close('CANCELLED');
  await assert.rejects(connecting, /CANCELLED/);
});
