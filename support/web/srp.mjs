import { base64, unbase64, validId } from './protocol.mjs';

// SRP-6a, קבוצה RFC 5054 בגודל 3072 סיביות ו-SHA-256.
// סדר ההוכחות והריפוד תואם ל-SRP6Client/SRP6Util של Bouncy Castle 1.86.
// כל צד מוכיח ידיעת הקוד בלי לשדר אותו; המפתח הקבוע נוצר באקראי רק לאחר הזיווג.
const N = BigInt(
  '0x' +
    'FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08' +
    '8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B' +
    '302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9' +
    'A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6' +
    '49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8' +
    'FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D' +
    '670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C' +
    '180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718' +
    '3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D' +
    '04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7D' +
    'B3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D226' +
    '1AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C' +
    'BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFC' +
    'E0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF',
);
const g = 5n;
const encoder = new TextEncoder();
const hex = (bytes) => Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
const integer = (bytes) => BigInt('0x' + hex(bytes));
function pad(value) {
  const text = value.toString(16).padStart(768, '0');
  return Uint8Array.from(text.match(/../g), (x) => parseInt(x, 16));
}
async function hash(...arrays) {
  const input = new Uint8Array(arrays.reduce((sum, a) => sum + a.length, 0));
  let offset = 0;
  for (const a of arrays) {
    input.set(a, offset);
    offset += a.length;
  }
  return new Uint8Array(await crypto.subtle.digest('SHA-256', input));
}
function power(base, exponent) {
  // BigInt אינו מבטיח זמן קבוע. הסוואת המעריך באקראי שומרת את התוצאה ומצמצמת דליפת תזמון.
  exponent += integer(crypto.getRandomValues(new Uint8Array(32))) * (N - 1n);
  let result = 1n;
  for (; exponent > 0n; exponent >>= 1n) {
    if (exponent & 1n) result = (result * base) % N;
    base = (base * base) % N;
  }
  return result;
}
export function normalizeCode(value) {
  const code = String(value).replace(/[\s-]/g, '');
  if (!/^[0-9]{8}$/.test(code)) throw new Error('BAD_CODE');
  return code;
}
export class SrpClient {
  // הזרקת ערך פרטי משמשת רק לווקטור הבדיקה המשותף ל-Java ולדפדפן.
  constructor(privateValue) {
    let a;
    do {
      a = integer(privateValue || crypto.getRandomValues(new Uint8Array(384)));
    } while (!privateValue && (a < 1n << 255n || a >= N));
    if (a < 1n << 255n || a >= N) throw new Error('BAD_PRIVATE');
    this.a = a;
    this.A = power(g, a);
    this.publicValue = this.A.toString(16);
  }

  async respond(device, input, salt, publicValue) {
    if (this.used || !validId(device)) throw new Error('BAD_HANDSHAKE');
    this.used = true;
    const code = normalizeCode(input);
    if (typeof publicValue !== 'string' || !/^[0-9a-f]{1,768}$/.test(publicValue))
      throw new Error('BAD_PUBLIC');
    const B = BigInt('0x' + publicValue);
    if (B <= 0n || B >= N) throw new Error('BAD_PUBLIC');
    const k = integer(await hash(pad(N), pad(g)));
    const u = integer(await hash(pad(this.A), pad(B)));
    if (!u) throw new Error('BAD_PUBLIC');
    const x = integer(
      await hash(unbase64(salt, 16), await hash(encoder.encode(`karov:${device}:${code}`))),
    );
    const base = (((B - k * power(g, x)) % N) + N) % N;
    if (!base) throw new Error('BAD_PUBLIC');
    const S = power(base, this.a + u * x);
    this.a = 0n;
    const proof = await hash(pad(this.A), pad(B), pad(S));
    return {
      proof: hex(proof),
      serverProof: base64(await hash(pad(this.A), pad(integer(proof)), pad(S))),
      key: base64(await hash(pad(S))),
    };
  }
}
