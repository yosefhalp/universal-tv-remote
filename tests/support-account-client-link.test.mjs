import test from 'node:test';
import assert from 'node:assert/strict';
import { accountUI, accountDeviceSummary, linkAccountTelevision } from '../support/web/account-ui.mjs';

function fixture() {
  let time = 10000, connects = 0, grants = 0, reads = 0, prepares = 0;
  const device = { device: 'a'.repeat(32), controller: 'b'.repeat(32), name: 'סלון', key: { extractable: false } };
  const record = { ...device, controller: 'c'.repeat(32), account: true };
  const active = { ready: true, async call(method, args, timeout) {
    assert.equal(method, 'grantAccount'); assert.equal(args.controller, record.controller);
    assert.equal(args.ticket, 'ticket'); assert.ok(timeout >= 12000); grants++;
  } };
  let state = { device, connection: active, supported: true, generation: 1 };
  const client = {
    token: 'session-X', generation: 'X', user: { mustChangePassword: false }, devices: [],
    async prepare() { prepares++; return { linked: false, controller: record.controller, secret: 'new-account-secret', ticket: 'ticket', expiresAt: time + 120000 }; },
    async sync() { reads++; this.devices = [record]; },
  };
  return {
    client, device, record, active,
    options: { client, device, getActive: () => state, connect: async () => { connects++; state = { device, connection: active, supported: true, generation: 2 }; }, now: () => time, sleep: async (ms) => { time += ms; } },
    setState(value) { state = value; }, advance(ms) { time += ms; },
    stats: () => ({ time, connects, grants, reads, prepares }),
  };
}

test('explicit local selection connects once, grants account access and preserves legacy record', async () => {
  const f = fixture(), before = structuredClone(f.device);
  f.setState({ device: null, connection: null });
  assert.deepEqual(await linkAccountTelevision(f.options), f.record);
  assert.equal(f.stats().connects, 1); assert.equal(f.stats().grants, 1);
  assert.deepEqual(f.device, before);
});

test('active legacy connection is reused and success waits for durable server list after RPC timeout', async () => {
  const f = fixture();
  f.active.call = async () => { throw new Error('REQUEST_TIMEOUT'); };
  let reads = 0;
  f.client.sync = async () => { if (++reads === 12) f.client.devices = [f.record]; };
  assert.equal((await linkAccountTelevision(f.options)).controller, f.record.controller);
  assert.equal(f.stats().connects, 0); assert.equal(reads, 12);
  assert.ok(f.stats().time >= 25000);
});

test('empty durable list reaches bounded pending result instead of false success', async () => {
  const f = fixture(); let reads = 0;
  f.client.sync = async () => { reads++; };
  await assert.rejects(linkAccountTelevision(f.options), /ACCOUNT_LINK_PENDING/);
  assert.equal(reads, 20); assert.equal(f.stats().time, 40000);
  assert.match(accountDeviceSummary([]), /אין עדיין טלוויזיות/);
  assert.doesNotMatch(accountDeviceSummary([]), /הפעולה הושלמה|סונכרנה/);
});

test('account replacement or changed selected connection prevents grant', async () => {
  for (const changeAccount of [true, false]) {
    const f = fixture(), prepare = f.client.prepare;
    f.client.prepare = async () => {
      const result = await prepare();
      if (changeAccount) f.client.generation = 'Y';
      else f.setState({ device: f.device, connection: { ready: true }, generation: 2, supported: true });
      return result;
    };
    await assert.rejects(linkAccountTelevision(f.options), changeAccount ? /STALE_ACCOUNT/ : /ACCOUNT_SELECTION_CHANGED/);
    assert.equal(f.stats().grants, 0);
  }
});

test('expired pending is prepared again and never submitted after expiry', async () => {
  const f = fixture(), prepare = f.client.prepare; let attempts = 0;
  f.client.prepare = async () => ++attempts === 1 ? { linked: false, expiresAt: 1 } : prepare();
  await linkAccountTelevision(f.options);
  assert.equal(attempts, 2); assert.equal(f.stats().grants, 1);
  const expired = fixture(); expired.client.prepare = async () => ({ linked: false, expiresAt: 1 });
  await assert.rejects(linkAccountTelevision(expired.options), /ACCOUNT_LINK_EXPIRED/);
  assert.equal(expired.stats().grants, 0);
});

test('linked prepare result still waits for matching controller in durable account list', async () => {
  const f = fixture();
  f.client.prepare = async () => ({ linked: true, record: f.record });
  f.client.sync = async () => { f.client.devices = [{ ...f.record, controller: 'wrong-controller' }]; };
  await assert.rejects(linkAccountTelevision(f.options), /ACCOUNT_LINK_PENDING/);
  assert.equal(f.stats().grants, 0);
});

test('a transient refresh failure retries but changed account during durable polling cannot succeed', async () => {
  const f = fixture(); let reads = 0;
  f.client.sync = async () => { if (++reads === 1) throw new Error('NETWORK'); f.client.devices = [f.record]; };
  assert.equal((await linkAccountTelevision(f.options)).controller, f.record.controller);
  assert.equal(reads, 2);
  const changed = fixture();
  changed.client.sync = async () => { changed.client.devices = [changed.record]; changed.client.generation = 'Y'; };
  await assert.rejects(linkAccountTelevision(changed.options), /STALE_ACCOUNT/);
  const stale = fixture();
  stale.client.devices = [stale.record];
  stale.client.sync = async () => { throw new Error('NETWORK'); };
  await assert.rejects(linkAccountTelevision(stale.options), /ACCOUNT_LINK_PENDING/);
});

test('account UI separates local linking from read-only refresh and explains empty results', async () => {
  class Element {
    constructor() { this.children = []; this.hidden = false; }
    replaceChildren(...items) { this.children = items; }
    append(...items) { this.children.push(...items); }
    addEventListener() {}
    querySelectorAll() { return []; }
  }
  const previous = globalThis.document, elements = new Map();
  globalThis.document = {
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); },
    createElement() { return new Element(); },
  };
  try {
    let refreshes = 0, grants = 0;
    const client = { token: 'X', generation: 'X', user: { username: 'משפחה', role: 'user' }, devices: [], async refresh() { refreshes++; } };
    accountUI(client, { localDevices: async () => [], onLink: async () => { grants++; }, onSync: async () => {} });
    await elements.get('account-refresh').onclick();
    assert.equal(refreshes, 1); assert.equal(grants, 0);
    assert.match(elements.get('account-message').textContent, /אין עדיין טלוויזיות/);
    await elements.get('account-sync').onclick();
    assert.equal(refreshes, 1); assert.equal(grants, 0);
    assert.equal(elements.get('account-local-picker').hidden, false);
    assert.equal(elements.get('account-local-empty').hidden, false);
    assert.match(elements.get('account-message').textContent, /אין טלוויזיה מקומית/);
  } finally { globalThis.document = previous; }
});
