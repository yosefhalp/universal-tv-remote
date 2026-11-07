import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PairingDirectory } from '../support/server/pairing-directory.mjs';
import { SrpClient, normalizeCode } from '../support/web/srp.mjs';
import { base64 } from '../support/web/protocol.mjs';

test('קוד מוצג בקבוצות ומתקבל גם בהדבקה עם רווחים ואפס מוביל', () => {
  assert.equal(normalizeCode('01-234-567'), '01234567');
  assert.equal(normalizeCode('01 234 567'), '01234567');
  for (const input of ['123', '123456789', '12abc345678', '１２３４５６７８'])
    assert.throws(() => normalizeCode(input), /BAD_CODE/);
});

test('המיפוי מבודד בעלים, פותר התנגשויות ופג אוטומטית בלי לשמור סוד', () => {
  const directory = new PairingDirectory();
  const request = { request: 'a'.repeat(32), prefix: '12', expires: 2000, name: 'טלוויזיה' };
  assert.equal(directory.register('owner-a', 'a'.repeat(32), request, 1000), '12');
  const second = directory.register('owner-b', 'b'.repeat(32), request, 1000);
  assert.notEqual(second, '12');
  assert.deepEqual(directory.lookup('12', 1000), {
    device: 'a'.repeat(32),
    name: 'טלוויזיה',
    expires: 2000,
  });
  directory.clear('owner-a');
  assert.equal(directory.lookup('12', 1000), null);
  assert.ok(directory.lookup(second, 1000));
  assert.equal(directory.lookup(second, 2000), null);
  assert.throws(() => directory.register('a', 'a', { ...request, expires: 900000 }, 1000));
});

test('זיווג Web תואם וקטור שנוצר בלקוח ובשרת Bouncy Castle העצמאיים', async () => {
  const vector = JSON.parse(
    readFileSync(new URL('../android-tv/app/src/test/resources/srp-vector.json', import.meta.url)),
  );
  const client = new SrpClient(Buffer.from(vector.a, 'hex'));
  assert.equal(client.publicValue, vector.A);
  const result = await client.respond(
    vector.device,
    vector.code,
    base64(Buffer.from(vector.salt, 'hex')),
    vector.B,
  );
  assert.equal(result.proof, vector.M1);
  assert.equal(result.serverProof, base64(Buffer.from(vector.M2, 'hex')));
  assert.equal(result.key, base64(Buffer.from(vector.K, 'hex')));
  await assert.rejects(
    client.respond(vector.device, vector.code, vector.salt, vector.B),
    /BAD_HANDSHAKE/,
  );
  const wrong = await new SrpClient(Buffer.from(vector.a, 'hex')).respond(
    vector.device,
    '12345679',
    base64(Buffer.from(vector.salt, 'hex')),
    vector.B,
  );
  assert.notEqual(wrong.proof, result.proof);
  for (const value of ['0', '-1', 'f'.repeat(769), 'f'.repeat(768)])
    await assert.rejects(
      new SrpClient().respond(
        vector.device,
        vector.code,
        base64(Buffer.from(vector.salt, 'hex')),
        value,
      ),
      /BAD_PUBLIC/,
    );
});
