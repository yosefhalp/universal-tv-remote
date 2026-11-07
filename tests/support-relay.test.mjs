import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import WebSocket from 'ws';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { startServer } from '../support/server/server.mjs';
import { randomId, randomSecret } from '../support/web/protocol.mjs';

function open(url, options) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, options);
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}
const message = (ws) =>
  new Promise((resolve) => ws.once('message', (value) => resolve(JSON.parse(value.toString()))));
test('מיפוי קוד נרשם רק מסוכן מאומת, אינו נשמר במטמון ונמחק בניתוק', async (t) => {
  const registrationKey = randomSecret(),
    device = randomId(),
    token = randomSecret();
  const server = await startServer({ port: 0, dataFile: null, registrationKey });
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.port}`;
  const agent = await open(`${base.replace('http', 'ws')}/v1/agent/${device}`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Karov-Enrollment': registrationKey },
  });
  const ack = message(agent);
  agent.send(
    JSON.stringify({
      t: 'pair-register',
      request: randomId(),
      expires: Date.now() + 30000,
      prefix: '12',
      name: 'בדיקה',
    }),
  );
  assert.equal((await ack).prefix, '12');
  const lookup = await fetch(`${base}/v1/pair/12`);
  assert.equal(lookup.headers.get('cache-control'), 'no-store');
  assert.equal((await lookup.json()).device, device);
  const joined = message(agent);
  const client = await open(`${base.replace('http', 'ws')}/v1/client/${device}`);
  await joined;
  const marker = message(agent);
  client.send(JSON.stringify({ t: 'pair-clear' }));
  client.send(
    JSON.stringify({
      t: 'pair-register',
      request: randomId(),
      expires: Date.now() + 30000,
      prefix: '99',
    }),
  );
  client.send(JSON.stringify({ t: 'marker' }));
  assert.equal((await marker).t, 'marker');
  assert.equal((await fetch(`${base}/v1/pair/12`)).status, 200);
  assert.equal((await fetch(`${base}/v1/pair/99`)).status, 404);
  const closed = new Promise((resolve) => client.once('close', resolve));
  agent.close();
  await closed;
  assert.equal((await fetch(`${base}/v1/pair/12`)).status, 404);
  assert.equal((await fetch(`${base}/v1/pair/12345678`)).status, 404);
});
test('השרת דורש מפתח התקנה, שומר זהות ומעביר מידע בין מכשירים בלבד', async (t) => {
  const folder = await mkdtemp(join(tmpdir(), 'karov-test-')),
    file = join(folder, 'devices.json');
  const registrationKey = randomSecret(),
    device = randomId(),
    token = randomSecret();
  let server = await startServer({
    port: 0,
    dataFile: file,
    registrationKeyHash: createHash('sha256').update(registrationKey).digest('hex'),
  });
  t.after(async () => {
    await server.close();
    await rm(folder, { recursive: true, force: true });
  });
  let base = `ws://127.0.0.1:${server.port}`;
  await assert.rejects(
    open(`${base}/v1/agent/${device}`, { headers: { Authorization: `Bearer ${token}` } }),
    /403/,
  );
  await assert.rejects(
    open(`${base}/v1/agent/${device}`, {
      headers: { Authorization: `Bearer ${token}`, 'X-Karov-Enrollment': 'wrong-key' },
    }),
    /403/,
  );
  const agent = await open(`${base}/v1/agent/${device}`, {
    headers: { Authorization: `Bearer ${token}`, 'X-Karov-Enrollment': registrationKey },
  });
  const joined = message(agent);
  const client = await open(`${base}/v1/client/${device}`);
  assert.equal((await joined).t, 'peer-joined');
  const delivery = message(agent);
  client.send(JSON.stringify({ t: 'data', s: 0, b: 'opaque' }));
  assert.equal((await delivery).b, 'opaque');
  const raw = await readFile(file, 'utf8');
  assert.ok(!raw.includes(token));
  assert.ok(!raw.includes(registrationKey));
  await assert.rejects(
    open(`${base}/v1/agent/${device}`, {
      headers: { Authorization: `Bearer ${randomSecret()}`, 'X-Karov-Enrollment': registrationKey },
    }),
    /403/,
  );
  await server.close();
  server = await startServer({ port: 0, dataFile: file });
  base = `ws://127.0.0.1:${server.port}`;
  const again = await open(`${base}/v1/agent/${device}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  again.close();
});
test('שרת מסרב למקור זר ולנתיב חריג ומספק כותרות הגנה', async (t) => {
  const server = await startServer({ port: 0, dataFile: null });
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.port}`;
  const response = await fetch(`${base}/health`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('x-frame-options'), 'DENY');
  await assert.rejects(
    open(`${base.replace('http', 'ws')}/v1/client/${randomId()}`, {
      origin: 'https://other.example',
    }),
    /403/,
  );
  await assert.rejects(
    open(`${base.replace('http', 'ws')}/v1/client/${randomId()}?token=secret`),
    /404/,
  );
  assert.equal((await fetch(`${base}/.env`)).status, 404);
  assert.equal((await fetch(`${base}/health`, { method: 'POST' })).status, 405);
});
