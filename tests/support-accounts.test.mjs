import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdtemp, readFile, rm, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import WebSocket from 'ws';
import { startServer } from '../support/server/server.mjs';
import { passwordVerifier } from '../support/server/enrollment.mjs';

const randomId = () => randomBytes(16).toString('hex');
const randomSecret = () => randomBytes(32).toString('base64url');
const installationPassword = 'התקנה לבדיקה בלבד 81729';
const userPassword = 'בדיקה-לחשבון-81729';
const inboxes = new WeakMap();
async function socket(base, device, agentToken) {
  const ws = new WebSocket(
    `${base.replace('http', 'ws')}/v1/${agentToken ? 'agent' : 'client'}/${device}`,
    agentToken
      ? {
          headers: {
            Authorization: `Bearer ${agentToken}`,
            'X-Karov-Enrollment': `pw1:${Buffer.from(installationPassword).toString('base64url')}`,
          },
        }
      : {},
  );
  const inbox = { queue: [], waiters: [] };
  inboxes.set(ws, inbox);
  ws.on('message', (raw) => {
    const value = JSON.parse(raw.toString());
    const waiter = inbox.waiters.shift();
    if (waiter) waiter(value);
    else inbox.queue.push(value);
  });
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  return ws;
}
function next(ws) {
  const inbox = inboxes.get(ws);
  if (inbox.queue.length) return Promise.resolve(inbox.queue.shift());
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('MESSAGE_TIMEOUT')), 3000);
    inbox.waiters.push((value) => {
      clearTimeout(timer);
      resolve(value);
    });
  });
}
const send = (ws, value) => ws.send(JSON.stringify(value));
async function closed(ws) {
  if (ws.readyState === WebSocket.CLOSED) return;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('CLOSE_TIMEOUT')), 3000);
    ws.once('close', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}
async function request(
  base,
  route,
  { token, body, method = body ? 'POST' : 'GET', headers = {} } = {},
) {
  const response = await fetch(`${base}/v1/account${route}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  return { status: response.status, data: await response.json() };
}
async function fixture(t, extra = {}) {
  const folder = await mkdtemp(join(tmpdir(), 'karov-account-'));
  const options = {
    port: 0,
    dataFile: join(folder, 'devices.json'),
    enrollmentPasswordVerifier: await passwordVerifier(installationPassword),
    ...extra,
  };
  let service = await startServer(options);
  const result = {
    folder,
    options,
    service,
    base: `http://127.0.0.1:${service.port}`,
    restart: async () => {
      await service.close();
      service = await startServer(options);
      result.service = service;
      result.base = `http://127.0.0.1:${service.port}`;
    },
  };
  t.after(async () => {
    await service.close();
    await rm(folder, { force: true, recursive: true });
  });
  return result;
}
async function register(base, name, bootstrap = false) {
  const result = await request(base, '/register', {
    body: {
      username: name,
      password: userPassword,
      ...(bootstrap ? { installationPassword } : {}),
    },
  });
  assert.equal(result.status, 200);
  return result.data;
}
async function link(base, account, device, agent) {
  const prepared = await request(base, '/devices/prepare', {
    token: account.token,
    body: { device, name: 'טלוויזיה בסלון' },
  });
  assert.equal(prepared.status, 200);
  const pending = prepared.data;
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.deepEqual(await next(agent), {
    t: 'account-link-result',
    ticket: pending.ticket,
    ok: true,
  });
  return pending;
}

test('יצירת מנהל דורשת הוכחת התקנה; שם מנורמל, מירוצים והגנות HTTP נאכפים', async (t) => {
  const f = await fixture(t),
    base = f.base;
  assert.deepEqual((await request(base, '/status')).data, { setupRequired: true });
  assert.equal(
    (await request(base, '/register', { body: { username: 'admin', password: userPassword } }))
      .status,
    403,
  );
  assert.equal(
    (
      await request(base, '/register', {
        body: { username: 'admin', password: userPassword, installationPassword, role: 'admin' },
      })
    ).status,
    400,
  );
  const races = await Promise.all([
    request(base, '/register', {
      body: { username: ' ＡＤＭＩＮ ', password: userPassword, installationPassword },
    }),
    request(base, '/register', {
      body: { username: 'admin', password: userPassword, installationPassword },
    }),
  ]);
  assert.deepEqual(races.map((entry) => entry.status).sort(), [200, 409]);
  const admin = races.find((entry) => entry.status === 200).data;
  assert.equal(admin.user.username, 'admin');
  assert.equal(admin.user.role, 'admin');
  const user = await register(base, 'משתמש');
  assert.equal(user.user.role, 'user');
  assert.equal((await request(base, '/admin/users', { token: user.token })).status, 403);
  assert.equal(
    (await request(base, '/status', { headers: { Origin: 'https://foreign.example' } })).status,
    403,
  );
  assert.equal((await request(base, '/me?token=forbidden', { token: admin.token })).status, 400);
  assert.equal(
    (await request(base, '/login', { body: { username: 'missing', password: userPassword } })).data
      .error,
    'INVALID_CREDENTIALS',
  );
  assert.equal(
    (await request(base, '/login', { body: { username: 'admin', password: 'wrong-password' } }))
      .data.error,
    'INVALID_CREDENTIALS',
  );
  const oversized = await request(base, '/login', { body: { username: 'x'.repeat(17000) } });
  assert.equal(oversized.status, 413);
  const notJson = await fetch(`${base}/v1/account/login`, { method: 'POST', body: '{}' });
  assert.equal(notJson.status, 415);
});

test('ההרשאות נשמרות בהצפנה, מקושרות רק באישור סוכן, ומבודדות בין חשבונות', async (t) => {
  const f = await fixture(t);
  const admin = await register(f.base, 'admin', true),
    other = await register(f.base, 'other');
  const device = randomId(),
    agentToken = randomSecret();
  let agent = await socket(f.base, device, agentToken);
  const pending = (
    await request(f.base, '/devices/prepare', {
      token: admin.token,
      body: { device, name: 'סלון' },
    })
  ).data;
  const again = (
    await request(f.base, '/devices/prepare', {
      token: admin.token,
      body: { device, name: 'סלון חדש' },
    })
  ).data;
  assert.equal(again.controller, pending.controller);
  assert.equal(again.secret, pending.secret);
  assert.notEqual(again.ticket, pending.ticket);
  assert.deepEqual((await request(f.base, '/devices', { token: admin.token })).data, {
    devices: [],
  });
  let client = await socket(f.base, device);
  assert.equal((await next(agent)).t, 'peer-joined');
  send(client, { t: 'account-linked', ticket: again.ticket, controller: again.controller });
  send(client, { t: 'account-link-result', ticket: again.ticket, ok: true });
  send(client, { t: 'marker' });
  assert.equal((await next(agent)).t, 'marker');
  assert.deepEqual((await request(f.base, '/devices', { token: admin.token })).data.devices, []);
  client.close();
  await closed(client);
  assert.equal((await next(agent)).t, 'peer-left');
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
  send(agent, { t: 'account-linked', ticket: again.ticket, controller: again.controller });
  assert.equal((await next(agent)).ok, true);
  send(agent, { t: 'account-linked', ticket: again.ticket, controller: again.controller });
  assert.equal((await next(agent)).ok, true);
  const record = (await request(f.base, '/devices', { token: admin.token })).data.devices[0];
  assert.equal(record.secret, again.secret);
  assert.deepEqual((await request(f.base, '/devices', { token: other.token })).data.devices, []);
  for (const badToken of [undefined, other.token]) {
    client = await socket(f.base, device);
    await next(agent);
    send(client, {
      t: 'hello',
      controller: record.controller,
      accountToken: badToken,
      nonce: randomSecret(),
      padding: 'x'.repeat(4096),
    });
    assert.equal((await next(client)).t, 'account-denied');
    await closed(client);
    await next(agent);
  }
  client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: record.controller,
    accountToken: admin.token,
    nonce: randomSecret(),
  });
  const hello = await next(agent);
  assert.equal(hello.t, 'hello');
  assert.equal(Object.hasOwn(hello, 'accountToken'), false);
  const raw = await readFile(join(f.folder, 'accounts.json'), 'utf8');
  for (const value of [admin.token, again.secret, userPassword, 'סלון חדש', 'scrypt'])
    assert.ok(!raw.includes(value));
  await f.restart();
  assert.equal(
    (await request(f.base, '/devices', { token: admin.token })).data.devices[0].secret,
    record.secret,
  );
  agent = await socket(f.base, device, agentToken);
  client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: record.controller,
    accountToken: admin.token,
    nonce: randomSecret(),
  });
  await next(agent);
  assert.equal(
    (await request(f.base, `/devices/${device}`, { token: admin.token, method: 'DELETE' })).status,
    200,
  );
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  client = await socket(f.base, device);
  await next(agent);
  send(client, { t: 'hello', controller: record.controller, nonce: randomSecret() });
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  client = await socket(f.base, device);
  await next(agent);
  send(client, { t: 'hello', controller: randomId(), nonce: randomSecret() });
  assert.equal((await next(agent)).t, 'hello');
});

test('איפוס וחסימה מבטלים חיבורים וסשנים; החלפה כפויה אינה נותנת גישה למכשירים', async (t) => {
  const f = await fixture(t),
    admin = await register(f.base, 'admin', true),
    user = await register(f.base, 'user');
  const device = randomId(),
    agent = await socket(f.base, device, randomSecret());
  const pending = await link(f.base, user, device, agent);
  let client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: pending.controller,
    accountToken: user.token,
    nonce: randomSecret(),
  });
  await next(agent);
  const nextPassword = 'סיסמה-חדשה-אחרי-איפוס';
  assert.equal(
    (
      await request(f.base, `/admin/users/${user.user.id}/reset`, {
        token: admin.token,
        body: { password: nextPassword },
      })
    ).status,
    200,
  );
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  assert.equal((await request(f.base, '/me', { token: user.token })).status, 401);
  assert.equal(
    (await request(f.base, '/login', { body: { username: 'user', password: userPassword } }))
      .status,
    401,
  );
  const forced = (
    await request(f.base, '/login', { body: { username: 'user', password: nextPassword } })
  ).data;
  assert.equal(forced.user.mustChangePassword, true);
  assert.equal((await request(f.base, '/me', { token: forced.token })).status, 200);
  assert.equal((await request(f.base, '/devices', { token: forced.token })).status, 403);
  assert.equal(
    (
      await request(f.base, '/devices/prepare', {
        token: forced.token,
        body: { device, name: 'סלון' },
      })
    ).status,
    403,
  );
  const changed = (
    await request(f.base, '/password', {
      token: forced.token,
      body: { currentPassword: nextPassword, newPassword: userPassword },
    })
  ).data;
  assert.equal(changed.user.mustChangePassword, false);
  assert.equal((await request(f.base, '/me', { token: forced.token })).status, 401);
  assert.equal(
    (await request(f.base, '/devices', { token: changed.token })).data.devices.length,
    1,
  );
  client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: pending.controller,
    accountToken: changed.token,
    nonce: randomSecret(),
  });
  await next(agent);
  await request(f.base, `/admin/users/${user.user.id}/state`, {
    token: admin.token,
    body: { disabled: true },
  });
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  assert.equal((await request(f.base, '/me', { token: changed.token })).status, 401);
  send(agent, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
  assert.equal(
    (await request(f.base, '/login', { body: { username: 'user', password: userPassword } })).data
      .error,
    'INVALID_CREDENTIALS',
  );
  assert.equal(
    (
      await request(f.base, `/admin/users/${admin.user.id}/state`, {
        token: admin.token,
        body: { disabled: true },
      })
    ).status,
    403,
  );
  assert.equal(
    (
      await request(f.base, `/admin/users/${admin.user.id}/reset`, {
        token: admin.token,
        body: { password: nextPassword },
      })
    ).status,
    403,
  );
  const users = (await request(f.base, '/admin/users', { token: admin.token })).data.users;
  assert.equal(users.find((entry) => entry.id === user.user.id).deviceCount, 1);
  assert.equal(
    users.some((entry) => Object.hasOwn(entry, 'password')),
    false,
  );
});

test('יציאה ופקיעת סשן סוגרות חיבור והרשאת סוכן אחר אינה מאשרת ticket', async (t) => {
  let now = Date.now();
  const f = await fixture(t, { accountNow: () => now }),
    admin = await register(f.base, 'admin', true);
  const device = randomId(),
    agent = await socket(f.base, device, randomSecret());
  const otherDevice = randomId(),
    otherAgent = await socket(f.base, otherDevice, randomSecret());
  const pending = (
    await request(f.base, '/devices/prepare', {
      token: admin.token,
      body: { device, name: 'סלון' },
    })
  ).data;
  send(otherAgent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(otherAgent)).ok, false);
  now += 120001;
  send(agent, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
  const fresh = await link(f.base, admin, device, agent);
  assert.equal(fresh.controller, pending.controller);
  let client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: fresh.controller,
    accountToken: admin.token,
    nonce: randomSecret(),
  });
  await next(agent);
  assert.equal((await request(f.base, '/logout', { token: admin.token, body: {} })).status, 200);
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  const session = (
    await request(f.base, '/login', { body: { username: 'admin', password: userPassword } })
  ).data;
  client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: fresh.controller,
    accountToken: session.token,
    nonce: randomSecret(),
  });
  await next(agent);
  now = session.expiresAt + 1;
  send(client, { t: 'data', b: 'opaque' });
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  assert.equal((await request(f.base, '/me', { token: session.token })).status, 401);
});

test('קובץ חשבון קיים ללא מפתח או עם מפתח שגוי נכשל סגור', async () => {
  const folder = await mkdtemp(join(tmpdir(), 'karov-account-key-'));
  try {
    const options = {
      port: 0,
      dataFile: join(folder, 'devices.json'),
      accountDataFile: join(folder, 'private-accounts.json'),
      enrollmentPasswordVerifier: await passwordVerifier(installationPassword),
    };
    const service = await startServer(options);
    await register(`http://127.0.0.1:${service.port}`, 'admin', true);
    await service.close();
    const key = await readFile(`${options.accountDataFile}.key`);
    assert.equal(key.length, 32);
    await unlink(`${options.accountDataFile}.key`);
    await assert.rejects(startServer(options), /ACCOUNT_KEY_MISSING/);
    await writeFile(`${options.accountDataFile}.key`, randomBytes(32));
    await assert.rejects(startServer(options), /ACCOUNT_DATA_INVALID/);
  } finally {
    await rm(folder, { recursive: true, force: true });
  }
});

test('הרשמה מקבילה יוצרת מנהל יחיד, סשנים מוגבלים וניסיונות כניסה נחסמים', async (t) => {
  const f = await fixture(t);
  const registrations = await Promise.all(
    ['first', 'second'].map((username) =>
      request(f.base, '/register', {
        body: { username, password: userPassword, installationPassword },
      }),
    ),
  );
  assert.ok(registrations.every((entry) => entry.status === 200));
  assert.equal(registrations.filter((entry) => entry.data.user.role === 'admin').length, 1);
  const admin = registrations.find((entry) => entry.data.user.role === 'admin').data;
  const device = randomId(),
    agent = await socket(f.base, device, randomSecret());
  const pending = await link(f.base, admin, device, agent);
  const client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: pending.controller,
    accountToken: admin.token,
    nonce: randomSecret(),
  });
  await next(agent);
  const sessions = [admin];
  for (let index = 0; index < 16; index++) {
    const login = await request(f.base, '/login', {
      body: { username: admin.user.username, password: userPassword },
    });
    assert.equal(login.status, 200);
    sessions.push(login.data);
  }
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  assert.equal((await request(f.base, '/me', { token: sessions[0].token })).status, 401);
  assert.equal((await request(f.base, '/me', { token: sessions[1].token })).status, 200);
  let result;
  for (let index = 0; index < 31; index++) {
    result = await request(f.base, '/login', {
      body: { username: 'nonexistent', password: userPassword },
    });
  }
  assert.equal(result.status, 429);
});

test('סנכרונים רבים אינם צורכים מכסת כניסה ומכסת האימות אינה נעקפת בכותרת כתובת', async (t) => {
  const f = await fixture(t),
    admin = await register(f.base, 'admin', true);
  for (let index = 0; index < 200; index++) {
    assert.equal((await request(f.base, '/me', { token: admin.token })).status, 200);
  }
  const login = await request(f.base, '/login', {
    body: { username: 'admin', password: userPassword },
  });
  assert.equal(login.status, 200);
  for (let index = 0; index < 59; index++) {
    const result = await request(f.base, '/login', {
      body: { username: `missing-${index}`, password: 'short' },
      headers: {
        'X-Forwarded-For': `203.0.113.${index + 1}`,
        'CF-Connecting-IP': `198.51.100.${index + 1}`,
      },
    });
    // ההרשמה והכניסה המוצלחת צרכו שתיים מתוך שישים בקשות האימות המותרות.
    assert.equal(result.status, index < 58 ? 401 : 429);
  }
  assert.equal((await request(f.base, '/me', { token: admin.token })).status, 200);
  assert.equal((await request(f.base, '/devices', { token: admin.token })).status, 200);
});

test('בדיקת קישור מקדימה מחזירה הרשאה שמורה רק לסוכן המתאים ואינה מפעילה אותה', async (t) => {
  const f = await fixture(t),
    admin = await register(f.base, 'admin', true),
    user = await register(f.base, 'owner');
  const device = randomId(),
    agent = await socket(f.base, device, randomSecret());
  const otherAgent = await socket(f.base, randomId(), randomSecret());
  const pending = (
    await request(f.base, '/devices/prepare', {
      token: user.token,
      body: { device, name: 'סלון לבדיקה' },
    })
  ).data;
  let client = await socket(f.base, device);
  await next(agent);
  send(client, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  send(client, {
    t: 'account-link-checked',
    ticket: pending.ticket,
    ok: true,
    controller: pending.controller,
    secret: randomSecret(),
    padding: 'x'.repeat(4096),
  });
  send(client, { t: 'account-link-result', ticket: pending.ticket, ok: true });
  send(client, { t: 'marker-client' });
  assert.equal((await next(agent)).t, 'marker-client');
  send(agent, {
    t: 'account-link-checked',
    ticket: pending.ticket,
    ok: true,
    secret: randomSecret(),
    padding: 'x'.repeat(4096),
  });
  send(agent, { t: 'account-link-result', ticket: pending.ticket, ok: true });
  send(agent, { t: 'marker-agent' });
  assert.equal((await next(client)).t, 'marker-agent');
  for (const check of [
    { ticket: randomSecret(), controller: pending.controller },
    { ticket: pending.ticket, controller: randomId() },
    { ticket: randomSecret(), controller: randomId() },
  ]) {
    send(agent, { t: 'account-link-check', ...check });
    const denied = await next(agent);
    assert.equal(denied.t, 'account-link-checked');
    assert.equal(denied.ok, false);
    assert.equal(Object.hasOwn(denied, 'secret'), false);
  }
  send(otherAgent, {
    t: 'account-link-check',
    ticket: pending.ticket,
    controller: pending.controller,
  });
  const wrongDevice = await next(otherAgent);
  assert.equal(wrongDevice.ok, false);
  assert.equal(Object.hasOwn(wrongDevice, 'secret'), false);
  send(agent, {
    t: 'account-link-check',
    ticket: pending.ticket,
    controller: pending.controller,
    secret: randomSecret(),
    name: 'שם מזויף',
  });
  assert.deepEqual(await next(agent), {
    t: 'account-link-checked',
    ticket: pending.ticket,
    ok: true,
    controller: pending.controller,
    secret: pending.secret,
    name: pending.name,
  });
  assert.deepEqual((await request(f.base, '/devices', { token: user.token })).data.devices, []);
  send(client, {
    t: 'hello',
    controller: pending.controller,
    accountToken: user.token,
    nonce: randomSecret(),
  });
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, true);
  assert.equal(
    (await request(f.base, '/devices', { token: user.token })).data.devices[0].controller,
    pending.controller,
  );
  client = await socket(f.base, device);
  await next(agent);
  send(client, {
    t: 'hello',
    controller: pending.controller,
    accountToken: user.token,
    nonce: randomSecret(),
  });
  assert.equal((await next(agent)).t, 'hello');
  await request(f.base, `/admin/users/${user.user.id}/reset`, {
    token: admin.token,
    body: { password: 'סיסמה-אחרת-לאחר-איפוס' },
  });
  assert.equal((await next(client)).t, 'account-denied');
  await closed(client);
  await next(agent);
  send(agent, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
});

test('מזהי דוא״ל מנורמלים בהרשמה ובכניסה ושמות משתמש קודמים נשארים תקינים', async (t) => {
  const f = await fixture(t);
  const email = await register(f.base, ' ＯＷＮＥＲ+Home@Example.COM ', true);
  assert.equal(email.user.username, 'owner+home@example.com');
  assert.equal(email.user.role, 'admin');
  const login = await request(f.base, '/login', {
    body: { username: ' OWNER+HOME@EXAMPLE.COM ', password: userPassword },
  });
  assert.equal(login.status, 200);
  assert.equal(login.data.user.id, email.user.id);
  assert.equal(
    (
      await request(f.base, '/register', {
        body: { username: 'owner+home@example.com', password: userPassword },
      })
    ).status,
    409,
  );
  const legacy = await register(f.base, 'שם_משתמש-ישן');
  assert.equal(
    (
      await request(f.base, '/login', {
        body: { username: legacy.user.username, password: userPassword },
      })
    ).status,
    200,
  );
  for (const username of [
    'a@b',
    'a@@example.com',
    '.owner@example.com',
    'owner.@example.com',
    'ow..ner@example.com',
    'ow ner@example.com',
    'owner@exam ple.com',
    'owner@-example.com',
    'owner@example-.com',
    'owner@example..com',
    'owner@example.c',
    'owner@example.com\u0000',
    'owner\n@example.com',
    `${'a'.repeat(65)}@example.com`,
    `owner@${'a'.repeat(64)}.com`,
    `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}.com`,
  ]) {
    const invalid = await request(f.base, '/register', {
      body: { username, password: userPassword },
    });
    assert.equal(invalid.status, 400);
    assert.equal(invalid.data.error, 'INVALID_USERNAME');
  }
});

test('אישור שנבדק מראש שורד פקיעה, הכנה חוזרת ואתחול ומחזיר אישור חוזר לאחר אובדן תשובה', async (t) => {
  let now = Date.now();
  const f = await fixture(t, { accountNow: () => now }),
    admin = await register(f.base, 'admin', true);
  const device = randomId(),
    agentToken = randomSecret();
  let agent = await socket(f.base, device, agentToken);
  const pending = (
    await request(f.base, '/devices/prepare', {
      token: admin.token,
      body: { device, name: 'בדיקת התאוששות' },
    })
  ).data;
  send(agent, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, true);
  now += 120001;
  send(agent, { t: 'account-link-check', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, false);
  const renewed = (
    await request(f.base, '/devices/prepare', {
      token: admin.token,
      body: { device, name: 'הכנה חוזרת' },
    })
  ).data;
  assert.equal(renewed.controller, pending.controller);
  assert.equal(renewed.secret, pending.secret);
  assert.notEqual(renewed.ticket, pending.ticket);
  await f.restart();
  agent = await socket(f.base, device, agentToken);
  assert.deepEqual((await request(f.base, '/devices', { token: admin.token })).data.devices, []);
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.equal((await next(agent)).ok, true);
  assert.equal(
    (await request(f.base, '/devices', { token: admin.token })).data.devices[0].controller,
    pending.controller,
  );
  // הכנת מכשיר אחר מפעילה ניקוי מצבים ישנים, בלי למחוק הוכחה של הרשאה שכבר הופעלה.
  now += 120001;
  const otherDevice = randomId();
  await socket(f.base, otherDevice, randomSecret());
  await request(f.base, '/devices/prepare', {
    token: admin.token,
    body: { device: otherDevice, name: 'מכשיר נוסף' },
  });
  await f.restart();
  agent = await socket(f.base, device, agentToken);
  send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
  assert.deepEqual(await next(agent), {
    t: 'account-link-result',
    ticket: pending.ticket,
    ok: true,
  });
});

test('איפוס, חסימה והסרה מבטלים הוכחות אישור ישנות ושומרים בקר חוסם עקיפת חשבון', async (t) => {
  let now = Date.now();
  const f = await fixture(t, { accountNow: () => now }),
    admin = await register(f.base, 'admin', true);
  for (const action of ['reset', 'disable', 'unlink']) {
    let user = await register(f.base, `user-${action}`);
    const device = randomId(),
      agent = await socket(f.base, device, randomSecret());
    const pending = (
      await request(f.base, '/devices/prepare', {
        token: user.token,
        body: { device, name: 'הרשאה ממתינה' },
      })
    ).data;
    send(agent, {
      t: 'account-link-check',
      ticket: pending.ticket,
      controller: pending.controller,
    });
    assert.equal((await next(agent)).ok, true);
    if (action === 'reset') {
      const resetPassword = 'סיסמה-אחרי-איפוס-להתאוששות';
      await request(f.base, `/admin/users/${user.user.id}/reset`, {
        token: admin.token,
        body: { password: resetPassword },
      });
      user = (
        await request(f.base, '/login', {
          body: { username: user.user.username, password: resetPassword },
        })
      ).data;
      user = (
        await request(f.base, '/password', {
          token: user.token,
          body: { currentPassword: resetPassword, newPassword: userPassword },
        })
      ).data;
    } else if (action === 'disable') {
      await request(f.base, `/admin/users/${user.user.id}/state`, {
        token: admin.token,
        body: { disabled: true },
      });
      await request(f.base, `/admin/users/${user.user.id}/state`, {
        token: admin.token,
        body: { disabled: false },
      });
      user = (
        await request(f.base, '/login', {
          body: { username: user.user.username, password: userPassword },
        })
      ).data;
    } else await request(f.base, `/devices/${device}`, { token: user.token, method: 'DELETE' });
    now += 120001;
    send(agent, { t: 'account-linked', ticket: pending.ticket, controller: pending.controller });
    const denied = await next(agent);
    assert.equal(denied.ok, false);
    assert.equal(denied.error, 'INVALID_TICKET');
    assert.deepEqual((await request(f.base, '/devices', { token: user.token })).data.devices, []);
    const client = await socket(f.base, device);
    await next(agent);
    send(client, {
      t: 'hello',
      controller: pending.controller,
      accountToken: user.token,
      nonce: randomSecret(),
    });
    assert.equal((await next(client)).t, 'account-denied');
    await closed(client);
    await next(agent);
  }
});

test('הוכחות אישור מוגבלות בלי למחוק כרטיס שכבר אושר ונדרש לתור ההתאוששות', async (t) => {
  let now = Date.now();
  const f = await fixture(t, { accountNow: () => now }),
    admin = await register(f.base, 'admin', true);
  const device = randomId(),
    agent = await socket(f.base, device, randomSecret());
  let first;
  for (let index = 0; index < 17; index++) {
    const pending = (
      await request(f.base, '/devices/prepare', {
        token: admin.token,
        body: { device, name: 'הכנה חוזרת' },
      })
    ).data;
    first ||= pending;
    send(agent, {
      t: 'account-link-check',
      ticket: pending.ticket,
      controller: pending.controller,
    });
    const checked = await next(agent);
    assert.equal(checked.ok, index < 16);
    if (index === 16) assert.equal(checked.error, 'LIMIT');
  }
  now += 120001;
  send(agent, { t: 'account-linked', ticket: first.ticket, controller: first.controller });
  assert.equal((await next(agent)).ok, true);
});
