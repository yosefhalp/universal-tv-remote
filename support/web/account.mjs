import { importSecret, validId, randomId } from './protocol.mjs';

// החשבון נשמר במסד נפרד מהזיווגים המקומיים. הטוקן מוצפן ומפתחות השליטה אינם ניתנים לייצוא.
function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('karov-account', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('vault');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error('STORAGE_UNAVAILABLE'));
  });
}
async function access(mode, operation) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('vault', mode);
    const request = operation(tx.objectStore('vault'));
    tx.oncomplete = () => {
      db.close();
      resolve(request?.result);
    };
    tx.onerror = tx.onabort = () => {
      db.close();
      reject(new Error('STORAGE_UNAVAILABLE'));
    };
  });
}
export class AccountVault {
  constructor() {
    this.listeners = new Set();
    if (typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel('karov-account-changes');
      this.channel.onmessage = () => this.listeners.forEach((listener) => listener());
    }
  }
  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  async current(generation) {
    return ((await access('readonly', (s) => s.get('state')))?.generation ?? null) === generation;
  }
  async commit(expected, change) {
    const db = await database();
    const result = await new Promise((resolve, reject) => {
      const tx = db.transaction('vault', 'readwrite'),
        store = tx.objectStore('vault');
      let accepted = false;
      const read = store.get('state');
      // הקריאה, בדיקת הדור והכתיבה מתבצעות באותה טרנזקציה גם כשחלון אחר מחליף חשבון.
      read.onsuccess = () => {
        if ((read.result?.generation ?? null) !== expected) return;
        const value = change(read.result || {});
        store.put(value, 'state');
        store.delete('session');
        store.delete('devices');
        accepted = true;
      };
      tx.oncomplete = () => {
        db.close();
        resolve(accepted);
      };
      tx.onerror = tx.onabort = () => {
        db.close();
        reject(new Error('STORAGE_UNAVAILABLE'));
      };
    });
    if (result) this.channel?.postMessage({ changed: true });
    return result;
  }
  async key() {
    const existing = await access('readonly', (s) => s.get('key'));
    if (existing) return existing;
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
      'encrypt',
      'decrypt',
    ]);
    // add אינו דורס מפתח שנוצר בו־זמנית בחלון אחר.
    try {
      await access('readwrite', (s) => s.add(key, 'key'));
      return key;
    } catch {
      return access('readonly', (s) => s.get('key'));
    }
  }
  async load() {
    for (;;) {
      const state = await access('readonly', (s) => s.get('state'));
      const generation = state?.generation ?? null;
      let session = null;
      if (state?.session) {
        try {
          const plain = await crypto.subtle.decrypt(
            { name: 'AES-GCM', iv: state.session.iv },
            await this.key(),
            state.session.data,
          );
          session = JSON.parse(new TextDecoder().decode(plain));
        } catch {
          await this.clear(generation);
          continue;
        }
      }
      if (!(await this.current(generation))) continue;
      return {
        generation,
        session,
        devices: session && state?.owner === session.user.id ? state.devices || [] : [],
      };
    }
  }
  async save(session, expected, replace = false) {
    const plain = new TextEncoder().encode(JSON.stringify(session));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await this.key(), plain);
    const generation = replace ? randomId() : expected;
    return (await this.commit(expected, (state) => ({
      ...state,
      generation,
      session: { iv, data },
      owner: session.user.id,
      ...(replace ? { devices: [] } : {}),
    })))
      ? generation
      : false;
  }
  saveDevices(devices, user, generation) {
    return this.commit(generation, (state) => ({ ...state, owner: user, devices }));
  }
  async clear(expected) {
    const generation = randomId();
    return (await this.commit(expected, () => ({ generation, session: null, devices: [] })))
      ? generation
      : false;
  }
}

export class AccountClient {
  constructor({
    fetcher = (...args) => fetch(...args),
    vault = new AccountVault(),
    onChange = () => {},
  } = {}) {
    this.fetcher = fetcher;
    this.vault = vault;
    this.onChange = onChange;
    this.session = null;
    this.devices = [];
    this.epoch = 0;
    this.generation = null;
    this.reloadId = 0;
    this.unsubscribe = this.vault.subscribe?.(() => this.reload().catch(() => {}));
    if (typeof window !== 'undefined')
      window.addEventListener('focus', () => this.reload().catch(() => {}));
  }
  get token() {
    return this.session?.token;
  }
  get user() {
    return this.session?.user;
  }
  async init() {
    await this.reload();
    if (this.session && this.session.expiresAt <= Date.now()) await this.clear();
    if (this.session) await this.refresh();
  }
  async reload() {
    const reloadId = ++this.reloadId;
    const snapshot = await this.vault.load();
    if (reloadId !== this.reloadId) return;
    if (snapshot.generation !== this.generation) this.epoch++;
    this.generation = snapshot.generation;
    this.session = snapshot.session;
    this.devices = snapshot.devices;
    this.onChange();
  }
  async clear(expected = this.generation) {
    const generation = await this.vault.clear(expected);
    if (generation === false) {
      await this.reload();
      return false;
    }
    this.reloadId++;
    this.epoch++;
    this.generation = generation;
    this.session = null;
    this.devices = [];
    this.onChange();
    return true;
  }
  async request(path, body, method = body === undefined ? 'GET' : 'POST', authenticated = true) {
    const token = this.token,
      generation = this.generation;
    let response;
    try {
      response = await this.fetcher(`/v1/account${path}`, {
        method,
        cache: 'no-store',
        credentials: 'same-origin',
        signal: AbortSignal.timeout(12000),
        headers: {
          'Content-Type': 'application/json',
          ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch {
      throw new Error('NETWORK');
    }
    const result = await response.json().catch(() => ({}));
    if (
      authenticated &&
      (generation !== this.generation || !(await this.vault.current(generation)))
    ) {
      await this.reload();
      throw new Error('STALE_ACCOUNT');
    }
    if (!response.ok) {
      if (response.status === 401 && authenticated && token === this.token)
        await this.clear(generation);
      throw new Error(result.error || (response.status === 401 ? 'ACCOUNT_DENIED' : 'FAILED'));
    }
    return result;
  }
  status() {
    return this.request('/status', undefined, 'GET', false);
  }
  async authenticate(path, body) {
    const expected = this.generation;
    const result = await this.request(path, body, 'POST', path === '/password');
    const generation = await this.vault.save(result, expected, true);
    if (generation === false) {
      await this.reload();
      throw new Error('STALE_ACCOUNT');
    }
    this.reloadId++;
    this.epoch++;
    this.generation = generation;
    this.session = result;
    this.devices = [];
    this.onChange();
    if (!this.user.mustChangePassword) await this.sync();
    return result;
  }
  login(body) {
    return this.authenticate('/login', body);
  }
  register(body) {
    return this.authenticate('/register', body);
  }
  password(body) {
    return this.authenticate('/password', body);
  }
  async refresh() {
    if (!this.token) return;
    const epoch = this.epoch,
      generation = this.generation;
    const { user } = await this.request('/me');
    if (epoch !== this.epoch || !this.session) return;
    const updated = { ...this.session, user };
    if ((await this.vault.save(updated, generation)) === false) {
      await this.reload();
      return;
    }
    if (epoch !== this.epoch) return;
    this.session = updated;
    this.onChange();
    if (user.mustChangePassword) {
      this.devices = [];
      await this.vault.saveDevices([], user.id, generation);
    } else await this.sync();
  }
  async sync() {
    if (!this.token || this.user.mustChangePassword) return;
    const epoch = this.epoch,
      generation = this.generation;
    const result = await this.request('/devices');
    const records = await Promise.all(
      result.devices.map(async (record) => {
        if (!validId(record.device) || !validId(record.controller)) throw new Error('BAD_ID');
        return {
          device: record.device,
          controller: record.controller,
          name: String(record.name).slice(0, 80),
          linkedAt: record.linkedAt,
          key: await importSecret(record.secret),
          account: true,
        };
      }),
    );
    if (epoch !== this.epoch || !this.token) return;
    if (!(await this.vault.saveDevices(records, this.user.id, generation))) {
      await this.reload();
      return;
    }
    if (epoch !== this.epoch || !this.token) return;
    this.devices = records;
    this.onChange();
  }
  async logout() {
    const generation = this.generation;
    try {
      if (this.token) await this.request('/logout', {});
    } finally {
      await this.clear(generation);
    }
  }
  prepare(device, name) {
    return this.request('/devices/prepare', { device, name });
  }
  async unlink(device) {
    await this.request(`/devices/${device}`, undefined, 'DELETE');
    await this.sync();
  }
  users() {
    return this.request('/admin/users');
  }
  reset(id, password) {
    return this.request(`/admin/users/${id}/reset`, { password });
  }
  state(id, disabled) {
    return this.request(`/admin/users/${id}/state`, { disabled });
  }
}
