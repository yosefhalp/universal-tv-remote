import { scrypt, randomBytes, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const derive = promisify(scrypt);
const options = { N: 16384, r: 8, p: 1, maxmem: 32 * 1024 * 1024 };
const validPassword = (value) =>
  typeof value === 'string' && Buffer.byteLength(value) >= 8 && Buffer.byteLength(value) <= 256;

// הסיסמה עצמה אינה נשמרת בשרת. מלח אקראי וגיבוב איטי מקשים על ניחוש לאחר דליפת הגדרות.
export async function passwordVerifier(password) {
  if (!validPassword(password)) throw new Error('INVALID_PASSWORD');
  const salt = randomBytes(16);
  const key = await derive(password, salt, 32, options);
  return `scrypt-v1:${salt.toString('base64url')}:${key.toString('base64url')}`;
}

export function decodeEnrollment(header) {
  if (typeof header !== 'string' || header.length > 350) return '';
  if (!header.startsWith('pw1:')) return header;
  const encoded = header.slice(4);
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) return '';
  const bytes = Buffer.from(encoded, 'base64url');
  const value = bytes.toString('utf8');
  return bytes.toString('base64url') === encoded && Buffer.from(value).equals(bytes) ? value : '';
}

export class EnrollmentPassword {
  constructor(verifier, now = Date.now) {
    const match = /^scrypt-v1:([A-Za-z0-9_-]{22}):([A-Za-z0-9_-]{43})$/.exec(verifier);
    if (!match) throw new Error('INVALID_PASSWORD_VERIFIER');
    this.salt = Buffer.from(match[1], 'base64url');
    this.expected = Buffer.from(match[2], 'base64url');
    this.now = now;
    this.attempts = new Map();
    this.busy = 0;
  }

  async check(password, address) {
    const now = this.now();
    for (const [key, value] of this.attempts)
      if (now - value.start >= 600000) this.attempts.delete(key);
    const rate = this.attempts.get(address) || { start: now, count: 0 };
    if (
      this.busy >= 2 ||
      rate.count >= 5 ||
      (this.attempts.size >= 1024 && !this.attempts.has(address))
    )
      return 'LIMIT';
    rate.count++;
    this.attempts.set(address, rate);
    if (!validPassword(password)) return 'DENIED';
    this.busy++;
    try {
      const actual = await derive(password, this.salt, 32, options);
      if (!timingSafeEqual(actual, this.expected)) return 'DENIED';
      // רישום מוצלח אינו צובר חסימה עבור התקנות נוספות מאותה כתובת מנהרה.
      this.attempts.delete(address);
      return 'OK';
    } finally {
      this.busy--;
    }
  }
}
