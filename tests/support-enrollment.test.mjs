import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  passwordVerifier,
  EnrollmentPassword,
  decodeEnrollment,
} from '../support/server/enrollment.mjs';
import { startServer } from '../support/server/server.mjs';
import { randomId, randomSecret } from '../support/web/protocol.mjs';
import WebSocket from 'ws';

const connect = (base, device, token, password) =>
  new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}/v1/agent/${device}`, {
      headers: { Authorization: `Bearer ${token}`, 'X-Karov-Enrollment': password },
    });
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
test('סיסמה נשמרת עם מלח ייחודי, תומכת בעברית ומגבילה ניסיונות', async () => {
  const password = 'סיסמת בדיקה שאינה סוד';
  const verifier = await passwordVerifier(password);
  assert.notEqual(verifier, await passwordVerifier(password));
  let now = 0;
  const gate = new EnrollmentPassword(verifier, () => now);
  const encoded = 'pw1:' + Buffer.from(password).toString('base64url');
  assert.equal(decodeEnrollment(encoded), password);
  assert.equal(decodeEnrollment('pw1:%%%'), '');
  assert.equal(await gate.check(decodeEnrollment(encoded), 'test'), 'OK');
  for (let i = 0; i < 5; i++) assert.equal(await gate.check('wrong-password', 'test'), 'DENIED');
  assert.equal(await gate.check(password, 'test'), 'LIMIT');
  now = 600001;
  assert.equal(await gate.check(password, 'test'), 'OK');
});
test('סיסמה גוברת על המפתח הישן ומכשיר רשום חוזר גם בעת חסימת רישום חדש', async (t) => {
  const password = 'demo-password-only';
  const oldKey = randomSecret(),
    device = randomId(),
    token = randomSecret();
  const service = await startServer({
    port: 0,
    dataFile: null,
    registrationKey: oldKey,
    enrollmentPasswordVerifier: await passwordVerifier(password),
  });
  t.after(() => service.close());
  const base = `ws://127.0.0.1:${service.port}`;
  await assert.rejects(connect(base, device, token, oldKey), /403/);
  const agent = await connect(
    base,
    device,
    token,
    'pw1:' + Buffer.from(password).toString('base64url'),
  );
  agent.close();
  for (let i = 0; i < 5; i++)
    await assert.rejects(connect(base, randomId(), randomSecret(), 'wrong-password'), /403/);
  await assert.rejects(connect(base, randomId(), randomSecret(), password), /429/);
  const returning = await connect(base, device, token, '');
  assert.equal(returning.readyState, WebSocket.OPEN);
  await assert.rejects(connect(base, device, randomSecret(), password), /403/);
});
test('רישום מקביל אינו מחליף טוקן של אותו מכשיר', async (t) => {
  const password = 'demo-concurrent-only',
    device = randomId();
  const service = await startServer({
    port: 0,
    dataFile: null,
    enrollmentPasswordVerifier: await passwordVerifier(password),
  });
  t.after(() => service.close());
  const base = `ws://127.0.0.1:${service.port}`;
  const result = await Promise.allSettled([
    connect(base, device, randomSecret(), password),
    connect(base, device, randomSecret(), password),
  ]);
  assert.equal(result.filter((item) => item.status === 'fulfilled').length, 1);
  assert.match(result.find((item) => item.status === 'rejected').reason.message, /403/);
});
