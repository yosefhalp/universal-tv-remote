import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  Cipher,
  sessionKeys,
  randomId,
  randomSecret,
  importSecret,
  encodePairing,
  decodePairing,
} from '../support/web/protocol.mjs';

test('הצפנה בין שני כיוונים, Unicode ואימות הדדי', async () => {
  const secret = randomSecret(),
    device = randomId(),
    controller = randomId(),
    a = randomSecret(),
    b = randomSecret();
  const first = await sessionKeys(secret, device, controller, a, b),
    second = await sessionKeys(await importSecret(secret), device, controller, a, b);
  assert.ok(await second.verify('agent', await first.proof('agent')));
  assert.equal(await second.verify('client', await first.proof('agent')), false);
  const client = new Cipher(first, true),
    agent = new Cipher(second, false);
  const payload = { method: 'text', args: { text: 'שלום סבתא 🎬' } };
  assert.deepEqual(await agent.open(await client.seal(payload)), payload);
  assert.deepEqual(await client.open(await agent.seal({ ok: true })), { ok: true });
});
test('סשנים, זהויות וכיוונים מופרדים והודעה חוזרת נדחית', async () => {
  const secret = randomSecret(),
    device = randomId(),
    controller = randomId(),
    a = randomSecret(),
    b = randomSecret();
  const keys = await sessionKeys(secret, device, controller, a, b);
  const sender = new Cipher(keys, true),
    receiver = new Cipher(keys, false);
  const packet = await sender.seal({ key: 'HOME' });
  await receiver.open(packet);
  await assert.rejects(receiver.open(packet), /REPLAY/);
  await assert.rejects(new Cipher(keys, true).open(packet));
  const other = await sessionKeys(secret, device, randomId(), a, b);
  await assert.rejects(new Cipher(other, false).open(packet));
  const fresh = await sessionKeys(secret, device, controller, randomSecret(), b);
  await assert.rejects(new Cipher(fresh, false).open(packet));
});
test('שינוי בתוכן או בסדר הודעות נדחה ושליחה מקבילית נשארת מסודרת', async () => {
  const keys = await sessionKeys(
    randomSecret(),
    randomId(),
    randomId(),
    randomSecret(),
    randomSecret(),
  );
  const sender = new Cipher(keys, true);
  const packets = await Promise.all(
    Array.from({ length: 20 }, (_, index) => sender.seal({ index })),
  );
  assert.deepEqual(
    packets.map((item) => item.s),
    Array.from({ length: 20 }, (_, index) => index),
  );
  const receiver = new Cipher(keys, false);
  assert.deepEqual(
    (await Promise.all(packets.map((packet) => receiver.open(packet)))).map((item) => item.index),
    packets.map((item) => item.s),
  );
  await assert.rejects(new Cipher(keys, false).open(packets[1]), /REPLAY/);
  const tampered = {
    ...packets[0],
    b: (packets[0].b[0] === 'A' ? 'B' : 'A') + packets[0].b.slice(1),
  };
  await assert.rejects(new Cipher(keys, false).open(tampered));
});
test('קישור זיווג פג תוקף או בעל שדות פגומים אינו מתקבל', () => {
  const pair = {
    v: 1,
    device: randomId(),
    name: 'הטלוויזיה',
    secret: randomSecret(),
    expires: Date.now() + 60000,
  };
  assert.deepEqual(decodePairing(encodePairing(pair)), pair);
  assert.throws(() => decodePairing(encodePairing({ ...pair, expires: 1 })), /PAIR_EXPIRED/);
  assert.throws(
    () => decodePairing(encodePairing({ ...pair, device: '../../etc' })),
    /BAD_PAIRING/,
  );
  assert.throws(() => decodePairing(encodePairing({ ...pair, secret: 'abc' })));
});
