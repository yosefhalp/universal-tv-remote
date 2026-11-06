const { test } = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');

global.TvSession = require('../anywhere/shared/session');
const Session = global.TvSession;
const Agent = require('../anywhere/agent/core');
const { startRelay } = require('../anywhere/relay/server');

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (k) => (values.has(k) ? values.get(k) : null),
    setItem: (k, v) => values.set(k, String(v)),
    removeItem: (k) => values.delete(k),
  };
}

function fakeAdapter() {
  const log = [];
  return {
    log,
    info: () => ({ brand: 'fake', model: 'Fake', capabilities: ['apps'], buttons: ['HOME'] }),
    button(name, done) { log.push(name); done(null); },
    listApps(done) { done(null, [{ id: 'a', title: 'שלום' }]); },
    capture(done) { done(new Error('no picture')); },
  };
}

// A phone, written from the protocol: it shares only session.js with the TV.
function phone(relay, id, password) {
  return new Promise((resolve, reject) => {
    const key = Session.deriveKey(password, id);
    const nonce = Session.newNonce();
    const ws = new WebSocket(`${relay}/client/${id}`);
    let cipher;
    const pending = new Map();
    let next = 1;
    const api = {
      ws,
      call: (m, ...a) => new Promise((done) => {
        const callId = next;
        next += 1;
        pending.set(callId, done);
        ws.send(JSON.stringify({ t: 'd', b: cipher.seal({ id: callId, m, a }) }));
      }),
    };
    ws.on('open', () => ws.send(JSON.stringify({ t: 'hello', n: nonce })));
    ws.on('error', reject);
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      api.seen = msg;
      if (msg.relay) reject(Object.assign(new Error(msg.relay), { code: msg.relay }));
      else if (msg.t === 'denied' || msg.t === 'blocked') reject(Object.assign(new Error(msg.t), { code: msg.t }));
      else if (msg.t === 'challenge') {
        cipher = Session.sessionCipher(key, nonce, msg.n, true);
        ws.send(JSON.stringify({ t: 'auth', b: cipher.seal({ proof: 'phone' }) }));
      } else if (msg.t === 'ok') {
        const proof = cipher.open(msg.b);
        assert.equal(proof.proof, 'tv');
        api.info = proof.info;
        resolve(api);
      } else if (msg.t === 'd') {
        const reply = cipher.open(msg.b);
        pending.get(reply.id)(reply);
      }
    });
  });
}

async function setup(now) {
  const relay = await startRelay({ port: 0, host: '127.0.0.1' });
  const url = `ws://127.0.0.1:${relay.port}`;
  const states = [];
  const adapter = fakeAdapter();
  const agent = new Agent({
    relay: url, adapter, storage: memoryStorage(), WebSocketClass: WebSocket, onChange: (s) => states.push(s), now,
  });
  agent.start();
  for (let i = 0; i < 100 && agent.status !== 'online'; i += 1) await delay(20);
  return { relay, url, agent, adapter, states, done: async () => {
    agent.stop();
    await relay.close();
  } };
}

test('weekly codes are stable within a week, change the next week and have the promised format', () => {
  const secret = Session.secretBytes(Session.randomSecret());
  const monday = new Date('2026-11-02T00:00:00Z');
  const sunday = new Date('2026-11-08T23:59:59Z');
  const next = new Date('2026-11-09T00:00:00Z');
  assert.equal(Session.weekNumber(monday), Session.weekNumber(sunday));
  assert.equal(Session.weekNumber(next), Session.weekNumber(monday) + 1);
  assert.equal(Session.nextChange(monday).toISOString(), next.toISOString());
  const a = Session.weeklyCodes(secret, 100, 0);
  assert.deepEqual(a, Session.weeklyCodes(secret, 100, 0));
  assert.notEqual(a.id, Session.weeklyCodes(secret, 101, 0).id);
  assert.notEqual(a.password, Session.weeklyCodes(secret, 101, 0).password);
  assert.notEqual(a.password, Session.weeklyCodes(secret, 100, 1).password);
  assert.match(a.id, /^\d{9}$/);
  assert.match(a.password, /^[2-9A-HJKMNP-Z]{10}$/);
});

test('a phone with the right codes controls the TV, and the relay sees no content', async () => {
  const { url, agent, adapter, states, done } = await setup();
  const { id, password } = agent.codes;
  const device = await phone(url, id, `${password.slice(0, 5).toLowerCase()} ${password.slice(5)}`);
  assert.equal(device.info.model, 'Fake');
  assert.equal((await device.call('button', 'HOME')).ok, true);
  assert.deepEqual(adapter.log, ['HOME']);
  assert.equal((await device.call('listApps')).v[0].title, 'שלום');
  assert.equal((await device.call('capture')).e, 'no picture');
  assert.equal(states.at(-1).peer, true);

  const refused = await device.call('__proto__');
  assert.equal(refused.ok, false);
  const notOffered = await device.call('powerOff');
  assert.equal(notOffered.ok, false);

  device.ws.close();
  for (let i = 0; i < 100 && agent.peer; i += 1) await delay(20);
  assert.equal(states.at(-1).peer, false);
  await done();
});

test('a wrong password is denied and repeated guessing gets blocked', async () => {
  const { url, agent, done } = await setup();
  const { id } = agent.codes;
  for (let i = 0; i < 5; i += 1) await assert.rejects(phone(url, id, 'WRONGWRONG'), { code: 'denied' });
  await assert.rejects(phone(url, id, agent.codes.password), { code: 'blocked' });
  await done();
});

test('an unknown ID and a second phone are refused', async () => {
  const { url, agent, done } = await setup();
  await assert.rejects(phone(url, '000000000', 'AAAAAAAAAA'), { code: 'no-tv' });
  const first = await phone(url, agent.codes.id, agent.codes.password);
  await assert.rejects(phone(url, agent.codes.id, agent.codes.password), { code: 'busy' });
  first.ws.close();
  await done();
});

test('a fake TV using the same ID cannot take over or fool the phone', async () => {
  const { url, agent, done } = await setup();
  const fake = new WebSocket(`${url}/agent/${agent.codes.id}?token=stolen`);
  const code = await new Promise((resolve) => fake.on('close', resolve));
  assert.equal(code, 4001);
  const device = await phone(url, agent.codes.id, agent.codes.password);
  assert.equal(device.info.model, 'Fake');
  device.ws.close();
  await done();
});

test('a tampered or replayed message is rejected', () => {
  const key = Session.deriveKey('ABCDEFGHJK', '123456789');
  const a = Session.newNonce();
  const b = Session.newNonce();
  const phoneSide = Session.sessionCipher(key, a, b, true);
  const tvSide = Session.sessionCipher(key, a, b, false);
  const first = phoneSide.seal({ m: 'x' });
  assert.deepEqual(tvSide.open(first), { m: 'x' });
  assert.equal(tvSide.open(first), null, 'replay');
  const second = phoneSide.seal({ m: 'y' });
  const raw = Buffer.from(second, 'base64');
  raw[raw.length - 1] ^= 1;
  assert.equal(tvSide.open(raw.toString('base64')), null, 'tamper');
  assert.deepEqual(tvSide.open(second), { m: 'y' });
  const wrong = Session.sessionCipher(Session.deriveKey('WRONGWRONG', '123456789'), a, b, false);
  assert.equal(wrong.open(Session.sessionCipher(key, a, b, true).seal({ m: 'z' })), null, 'wrong key');
});

test('codes change by themselves when the week changes, and old ones stop working', async () => {
  let time = new Date('2026-11-08T23:59:00Z');
  const { url, agent, done } = await setup(() => time);
  const old = { ...agent.codes };
  time = new Date('2026-11-09T00:01:00Z');
  agent._checkWeek();
  assert.notEqual(agent.codes.id, old.id);
  for (let i = 0; i < 200 && (agent.status !== 'online' || !agent.ws || !agent.ws.url.includes(agent.codes.id)); i += 1) await delay(20);
  await assert.rejects(phone(url, old.id, old.password), { code: 'no-tv' });
  const device = await phone(url, agent.codes.id, agent.codes.password);
  device.ws.close();
  await done();
});
