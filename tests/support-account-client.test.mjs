import test from 'node:test';
import assert from 'node:assert/strict';
import { AccountClient } from '../support/web/account.mjs';
import { Connection } from '../support/web/connection.mjs';

const user = { id: '1'.repeat(32), username: 'משפחה', role: 'user', mustChangePassword: false };
const session = { token: 'test-token', expiresAt: Date.now() + 60000, user };
function memoryVault() {
  return {
    session: null,
    cache: [],
    generation: null,
    counter: 0,
    async load() {
      return structuredClone({
        session: this.session,
        devices: this.cache,
        generation: this.generation,
      });
    },
    async current(generation) {
      return generation === this.generation;
    },
    async save(value, expected, replace = false) {
      if (expected !== this.generation) return false;
      if (replace) {
        this.generation = `generation-${++this.counter}`;
        this.cache = [];
      }
      this.session = structuredClone(value);
      return this.generation;
    },
    async saveDevices(value, user, expected) {
      if (expected !== this.generation) return false;
      this.cache = value;
      return true;
    },
    async clear(expected) {
      if (expected !== this.generation) return false;
      this.session = null;
      this.cache = [];
      this.generation = `generation-${++this.counter}`;
      return this.generation;
    },
  };
}
const response = (value, status = 200) => ({ ok: status < 400, status, json: async () => value });

test('account token travels only in authorization and account keys remain nonextractable', async () => {
  const vault = memoryVault(),
    calls = [];
  const client = new AccountClient({
    vault,
    fetcher: async (url, options) => {
      calls.push({ url, options });
      return response(
        url.endsWith('/login')
          ? session
          : {
              devices: [
                {
                  device: '2'.repeat(32),
                  controller: '3'.repeat(32),
                  name: 'בית',
                  secret: 'A'.repeat(43),
                  linkedAt: 1,
                },
              ],
            },
      );
    },
  });
  await client.login({ username: 'משפחה', password: 'long-password' });
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.equal(calls[1].options.headers.Authorization, 'Bearer test-token');
  assert.ok(
    calls.every((call) => !call.url.includes(session.token) && call.options.cache === 'no-store'),
  );
  assert.equal(client.devices[0].key.extractable, false);
  assert.equal(client.devices[0].secret, undefined);
  await assert.rejects(crypto.subtle.exportKey('raw', client.devices[0].key));
});

test('network failures preserve account cache but unauthorized response clears it', async () => {
  const vault = memoryVault();
  vault.session = session;
  vault.cache = [{ device: 'cached' }];
  const client = new AccountClient({
    vault,
    fetcher: async () => {
      throw new Error('offline');
    },
  });
  await assert.rejects(client.init(), /NETWORK/);
  assert.equal(client.devices.length, 1);
  assert.equal(client.token, session.token);
  client.fetcher = async () => response({ error: 'ACCOUNT_DENIED' }, 401);
  await assert.rejects(client.refresh(), /ACCOUNT_DENIED/);
  assert.equal(client.token, undefined);
  assert.deepEqual(client.devices, []);
  assert.equal(vault.session, null);
});

test('forced password change never requests devices and replacing account clears prior cache', async () => {
  const vault = memoryVault(),
    calls = [];
  vault.cache = [{ device: 'prior-account' }];
  const client = new AccountClient({
    vault,
    fetcher: async (url) => {
      calls.push(url);
      return response({ ...session, user: { ...user, mustChangePassword: true } });
    },
  });
  await client.login({ username: 'user', password: 'password' });
  assert.deepEqual(calls, ['/v1/account/login']);
  assert.deepEqual(client.devices, []);
  assert.deepEqual(vault.cache, []);
});

test('stale synchronization response cannot repopulate a logged out account', async () => {
  let release;
  const client = new AccountClient({
    vault: memoryVault(),
    fetcher: () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  });
  client.session = session;
  const pending = client.sync();
  await client.clear();
  release(response({ devices: [] }));
  await assert.rejects(pending, /STALE_ACCOUNT/);
  assert.equal(client.token, undefined);
  assert.deepEqual(client.devices, []);
});

test('account websocket hello includes token without exposing it in URL and denial stops connection', async () => {
  let socket;
  class Socket {
    constructor(url) {
      this.url = url;
      this.readyState = 1;
      socket = this;
      queueMicrotask(() => this.onopen());
    }
    send(value) {
      this.hello = JSON.parse(value);
      queueMicrotask(() => this.onmessage({ data: JSON.stringify({ t: 'account-denied' }) }));
    }
    close() {
      this.readyState = 3;
    }
  }
  const connection = new Connection({
    url: 'wss://example.test',
    device: '2'.repeat(32),
    controller: '3'.repeat(32),
    secret: 'A'.repeat(43),
    accountToken: session.token,
    WebSocketClass: Socket,
  });
  await assert.rejects(connection.connect(), /ACCOUNT_DENIED/);
  assert.equal(socket.url, `wss://example.test/v1/client/${'2'.repeat(32)}`);
  assert.equal(socket.hello.accountToken, session.token);
  assert.equal(connection.ready, false);
});

for (const lateStatus of [401, 200]) {
  test(`shared vault keeps account Y when account X receives late ${lateStatus} refresh`, async () => {
    const vault = memoryVault();
    await vault.save(session, null, true);
    let release;
    const old = new AccountClient({
      vault,
      fetcher: () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    });
    const nextSession = {
      ...session,
      token: 'account-Y',
      user: { ...user, id: '9'.repeat(32), username: 'second@example.test' },
    };
    const other = new AccountClient({
      vault,
      fetcher: async (url) =>
        response(
          url.endsWith('/login')
            ? nextSession
            : url.endsWith('/devices')
              ? { devices: [] }
              : { ok: true },
        ),
    });
    await old.reload();
    await other.reload();
    const pending = old.refresh();
    await other.logout();
    await other.login({ username: nextSession.user.username, password: 'password-Y' });
    const expectedGeneration = vault.generation;
    release(response(lateStatus === 401 ? { error: 'UNAUTHORIZED' } : { user }, lateStatus));
    await assert.rejects(pending, /STALE_ACCOUNT/);
    assert.equal(vault.session.token, nextSession.token);
    assert.equal(vault.generation, expectedGeneration);
    assert.equal(old.token, nextSession.token);
    assert.equal(other.token, nextSession.token);
    assert.deepEqual(old.devices, []);
  });
}

test('cross-tab notification reloads identity and signals old cloud connection teardown', async () => {
  const vault = memoryVault();
  const listeners = new Set();
  vault.subscribe = (listener) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  };
  await vault.save(session, null, true);
  let teardowns = 0,
    activeToken = session.token;
  const client = new AccountClient({
    vault,
    onChange: () => {
      if (activeToken && activeToken !== client.token) {
        teardowns++;
        activeToken = null;
      }
    },
  });
  await client.reload();
  await vault.clear(vault.generation);
  for (const listener of listeners) listener();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(client.token, undefined);
  assert.equal(teardowns, 1);
});
