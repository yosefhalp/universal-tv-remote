import { randomInt } from 'node:crypto';

// הספרות הראשונות רק מאתרות את הסטרימר. שש הספרות הסודיות ומפתחות הזיווג אינם מגיעים לכאן.
export class PairingDirectory {
  entries = new Map();

  clear(owner) {
    for (const [prefix, entry] of this.entries)
      if (entry.owner === owner) this.entries.delete(prefix);
  }

  prune(now = Date.now()) {
    for (const [prefix, entry] of this.entries)
      if (entry.expires <= now) this.entries.delete(prefix);
  }

  register(owner, device, request, now = Date.now()) {
    this.prune(now);
    if (
      !/^[a-f0-9]{32}$/.test(request.request) ||
      !Number.isSafeInteger(request.expires) ||
      request.expires <= now ||
      request.expires > now + 600000
    )
      throw new Error('INVALID');
    this.clear(owner);
    let prefix = request.prefix;
    if (!/^[0-9]{2}$/.test(prefix) || this.entries.has(prefix)) {
      const available = Array.from({ length: 100 }, (_, i) => String(i).padStart(2, '0')).filter(
        (key) => !this.entries.has(key),
      );
      if (!available.length) throw new Error('FULL');
      prefix = available[randomInt(available.length)];
    }
    this.entries.set(prefix, {
      owner,
      device,
      expires: request.expires,
      name: typeof request.name === 'string' ? request.name.slice(0, 80) : 'הטלוויזיה',
    });
    return prefix;
  }

  lookup(prefix, now = Date.now()) {
    this.prune(now);
    const entry = this.entries.get(prefix);
    return entry ? { device: entry.device, name: entry.name, expires: entry.expires } : null;
  }
}
