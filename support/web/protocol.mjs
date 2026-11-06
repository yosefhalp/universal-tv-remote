// הפרוטוקול משותף לדפדפן ולבדיקות. מפתחות נפרדים לכל כיוון ומספר סידורי מונעים שימוש חוזר בהודעות.
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
export const VERSION = 1;
export const MAX_PACKET = 1500000;
export const validId = (value) => typeof value === 'string' && /^[a-f0-9]{32}$/.test(value);
export const randomId = () =>
  [...crypto.getRandomValues(new Uint8Array(16))]
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('');
export function base64(bytes) {
  let value = '';
  for (let i = 0; i < bytes.length; i += 8192)
    value += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(value).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}
export function unbase64(value, expected) {
  if (typeof value !== 'string' || !/^[\w-]+$/.test(value) || value.length > MAX_PACKET)
    throw new Error('BAD_ENCODING');
  const bytes = Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), (c) =>
    c.charCodeAt(0),
  );
  if (expected && bytes.length !== expected) throw new Error('BAD_LENGTH');
  if (base64(bytes) !== value) throw new Error('BAD_ENCODING');
  return bytes;
}
export const randomSecret = () => base64(crypto.getRandomValues(new Uint8Array(32)));
export const importSecret = (value) =>
  crypto.subtle.importKey('raw', unbase64(value, 32), 'HKDF', false, ['deriveKey']);
export const encodePairing = (value) => base64(encoder.encode(JSON.stringify(value)));
export function decodePairing(value) {
  if (typeof value !== 'string' || value.length > 4096) throw new Error('BAD_PAIRING');
  const data = JSON.parse(decoder.decode(unbase64(value)));
  if (
    data.v !== VERSION ||
    !validId(data.device) ||
    typeof data.name !== 'string' ||
    data.name.length > 80
  )
    throw new Error('BAD_PAIRING');
  unbase64(data.secret, 32);
  if (!Number.isSafeInteger(data.expires) || data.expires <= Date.now())
    throw new Error('PAIR_EXPIRED');
  return data;
}
export async function sessionKeys(secret, device, controller, clientNonce, agentNonce) {
  if (!validId(device) || !(controller === 'pair' || validId(controller)))
    throw new Error('BAD_ID');
  const a = unbase64(clientNonce, 32),
    b = unbase64(agentNonce, 32);
  const salt = new Uint8Array(64);
  salt.set(a);
  salt.set(b, 32);
  const transcript = `TVSUPPORT/1|${device}|${controller}|${clientNonce}|${agentNonce}`;
  const key = typeof secret === 'string' ? await importSecret(secret) : secret;
  const derive = (purpose, algorithm, uses) =>
    crypto.subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt, info: encoder.encode(`${transcript}|${purpose}`) },
      key,
      algorithm,
      false,
      uses,
    );
  const proof = await derive('proof', { name: 'HMAC', hash: 'SHA-256', length: 256 }, [
    'sign',
    'verify',
  ]);
  const client = await derive('client', { name: 'AES-GCM', length: 256 }, ['encrypt', 'decrypt']);
  const agent = await derive('agent', { name: 'AES-GCM', length: 256 }, ['encrypt', 'decrypt']);
  return {
    transcript,
    client,
    agent,
    proof: async (role) =>
      base64(
        new Uint8Array(
          await crypto.subtle.sign('HMAC', proof, encoder.encode(`${transcript}|${role}`)),
        ),
      ),
    verify: async (role, signature) => {
      try {
        return await crypto.subtle.verify(
          'HMAC',
          proof,
          unbase64(signature, 32),
          encoder.encode(`${transcript}|${role}`),
        );
      } catch {
        return false;
      }
    },
  };
}
export class Cipher {
  constructor(keys, isClient) {
    this.keys = keys;
    this.tx = isClient ? 'client' : 'agent';
    this.rx = isClient ? 'agent' : 'client';
    this.sent = 0;
    this.received = 0;
    this.sendQueue = Promise.resolve();
    this.receiveQueue = Promise.resolve();
  }
  params(direction, sequence) {
    const iv = new Uint8Array(12);
    new DataView(iv.buffer).setBigUint64(4, BigInt(sequence));
    return {
      name: 'AES-GCM',
      iv,
      additionalData: encoder.encode(`${this.keys.transcript}|${direction}|${sequence}`),
      tagLength: 128,
    };
  }
  seal(object) {
    const run = this.sendQueue.then(async () => {
      if (!Number.isSafeInteger(this.sent)) throw new Error('COUNTER_EXHAUSTED');
      const s = this.sent++;
      const data = encoder.encode(JSON.stringify(object));
      if (data.length > 1000000) throw new Error('MESSAGE_TOO_LARGE');
      const encrypted = await crypto.subtle.encrypt(
        this.params(this.tx, s),
        this.keys[this.tx],
        data,
      );
      return { t: 'data', s, b: base64(new Uint8Array(encrypted)) };
    });
    this.sendQueue = run;
    return run;
  }
  open(packet) {
    const run = this.receiveQueue.then(async () => {
      if (packet?.t !== 'data' || !Number.isSafeInteger(packet.s) || packet.s !== this.received)
        throw new Error('REPLAY_OR_ORDER');
      const decoded = await crypto.subtle.decrypt(
        this.params(this.rx, packet.s),
        this.keys[this.rx],
        unbase64(packet.b),
      );
      const object = JSON.parse(decoder.decode(decoded));
      this.received++;
      return object;
    });
    this.receiveQueue = run;
    return run;
  }
}
