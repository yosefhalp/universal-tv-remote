import {
  randomBytes,
  createHash,
  createCipheriv,
  createDecipheriv,
  scrypt,
  timingSafeEqual,
} from 'node:crypto';
import { promisify } from 'node:util';
import {
  existsSync,
  readFileSync,
  writeFileSync,
  mkdirSync,
  renameSync,
  openSync,
  fsyncSync,
  closeSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';

const derive = promisify(scrypt);
const hash = (value) => createHash('sha256').update(value).digest('hex');
const id = () => randomBytes(16).toString('hex');
const secret = () => randomBytes(32).toString('base64url');
const ID = /^[a-f0-9]{32}$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const MAX_AGE = 30 * 86400000;
const equal = (a, b) =>
  typeof a === 'string' &&
  typeof b === 'string' &&
  a.length === b.length &&
  timingSafeEqual(Buffer.from(a), Buffer.from(b));
export class AccountError extends Error {
  constructor(code, status = 400) {
    super(code);
    this.status = status;
  }
}
const fail = (code, status) => {
  throw new AccountError(code, status);
};
// כתובת דוא״ל היא מזהה כניסה בלבד; בדיקת המבנה אינה מאמתת שהמשתמש מחזיק בתיבת הדואר.
const emailIdentifier = (name) => {
  if (name.length > 254) return false;
  const parts = name.split('@');
  if (parts.length !== 2) return false;
  const [local, domain] = parts;
  if (
    !local ||
    local.length > 64 ||
    !/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/.test(local) ||
    local.startsWith('.') ||
    local.endsWith('.') ||
    local.includes('..')
  )
    return false;
  const labels = domain.split('.');
  return (
    labels.length >= 2 &&
    labels.every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) &&
    /^[a-z]{2,63}$/.test(labels.at(-1))
  );
};
const username = (value) => {
  if (typeof value !== 'string') fail('INVALID_USERNAME');
  const name = value.normalize('NFKC').trim().toLowerCase();
  if (!/^[a-z\u05d0-\u05ea0-9._-]{3,32}$/.test(name) && !emailIdentifier(name))
    fail('INVALID_USERNAME');
  return name;
};
const passwordValid = (value) =>
  typeof value === 'string' &&
  [...value].length >= 8 &&
  [...value].length <= 128 &&
  Buffer.byteLength(value) <= 256;
const dto = (user) => ({
  id: user.id,
  username: user.username,
  role: user.role,
  mustChangePassword: user.mustChangePassword,
  disabled: user.disabled,
});

// כל המצב מוצפן, לרבות הרשאות השליטה. מפתח הקובץ נשאר בנפח הקבוע ובעל הרשאות מצומצמות.
export class AccountStore {
  constructor({
    dataFile = null,
    verifyInstallation = async () => 'DENIED',
    deviceExists = () => false,
    onRevoke = () => {},
    now = Date.now,
  } = {}) {
    this.dataFile = dataFile && resolve(dataFile);
    this.verifyInstallation = verifyInstallation;
    this.deviceExists = deviceExists;
    this.onRevoke = onRevoke;
    this.now = now;
    this.busy = 0;
    this.rates = new Map();
    this.state = { version: 1, users: [], sessions: [], bindings: {}, pending: [] };
    if (!this.dataFile) return;
    const keyFile = `${this.dataFile}.key`;
    mkdirSync(dirname(this.dataFile), { recursive: true });
    if (existsSync(this.dataFile) && !existsSync(keyFile)) throw new Error('ACCOUNT_KEY_MISSING');
    if (!existsSync(keyFile)) {
      try {
        writeFileSync(keyFile, randomBytes(32), { mode: 0o600, flag: 'wx', flush: true });
      } catch (error) {
        if (error.code !== 'EEXIST') throw error;
      }
    }
    this.key = readFileSync(keyFile);
    if (this.key.length !== 32) throw new Error('ACCOUNT_KEY_INVALID');
    if (existsSync(this.dataFile)) {
      try {
        const envelope = JSON.parse(readFileSync(this.dataFile, 'utf8'));
        if (envelope.version !== 1) throw new Error();
        const decipher = createDecipheriv(
          'aes-256-gcm',
          this.key,
          Buffer.from(envelope.iv, 'base64url'),
        );
        decipher.setAAD(Buffer.from('karov-accounts-v1'));
        decipher.setAuthTag(Buffer.from(envelope.tag, 'base64url'));
        this.state = JSON.parse(
          Buffer.concat([
            decipher.update(Buffer.from(envelope.data, 'base64url')),
            decipher.final(),
          ]).toString(),
        );
        if (
          this.state.version !== 1 ||
          !Array.isArray(this.state.users) ||
          !Array.isArray(this.state.sessions) ||
          !Array.isArray(this.state.pending) ||
          !this.state.bindings ||
          typeof this.state.bindings !== 'object' ||
          Array.isArray(this.state.bindings)
        )
          throw new Error();
      } catch {
        throw new Error('ACCOUNT_DATA_INVALID');
      }
    }
  }
  persist() {
    if (!this.dataFile) return;
    const iv = randomBytes(12),
      cipher = createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from('karov-accounts-v1'));
    const data = Buffer.concat([cipher.update(JSON.stringify(this.state)), cipher.final()]);
    writeFileSync(
      `${this.dataFile}.tmp`,
      JSON.stringify({
        version: 1,
        iv: iv.toString('base64url'),
        tag: cipher.getAuthTag().toString('base64url'),
        data: data.toString('base64url'),
      }),
      { mode: 0o600, flush: true },
    );
    renameSync(`${this.dataFile}.tmp`, this.dataFile);
    if (process.platform !== 'win32') {
      const directory = openSync(dirname(this.dataFile), 'r');
      try {
        fsyncSync(directory);
      } finally {
        closeSync(directory);
      }
    }
  }
  mutate(action) {
    const before = structuredClone(this.state);
    try {
      const result = action();
      this.persist();
      return result;
    } catch (error) {
      this.state = before;
      throw error;
    }
  }
  rate(key, limit = 30) {
    const now = this.now();
    for (const [entry, rate] of this.rates)
      if (now - rate.start >= 600000) this.rates.delete(entry);
    const rate = this.rates.get(key) || { start: now, count: 0 };
    if ((!this.rates.has(key) && this.rates.size >= 4096) || ++rate.count > limit)
      fail('LIMIT', 429);
    this.rates.set(key, rate);
  }
  async passwordHash(value, saved) {
    if (!passwordValid(value)) fail('INVALID_PASSWORD');
    if (this.busy >= 2) fail('LIMIT', 429);
    this.busy++;
    try {
      const salt = saved?.salt || randomBytes(16).toString('base64url');
      const result = (
        await derive(value, Buffer.from(salt, 'base64url'), 32, {
          N: 16384,
          r: 8,
          p: 1,
          maxmem: 32 * 1024 * 1024,
        })
      ).toString('base64url');
      return saved ? equal(result, saved.hash) : { salt, hash: result };
    } finally {
      this.busy--;
    }
  }
  auth(token, allowChange = false) {
    if (typeof token !== 'string' || !TOKEN.test(token)) fail('UNAUTHORIZED', 401);
    const session = this.state.sessions.find((entry) => entry.hash === hash(token));
    const user = session && this.state.users.find((entry) => entry.id === session.user);
    if (!user || user.disabled || session.expiresAt <= this.now()) fail('UNAUTHORIZED', 401);
    if (user.mustChangePassword && !allowChange) fail('PASSWORD_CHANGE_REQUIRED', 403);
    return { user, session };
  }
  session(user) {
    const token = secret(),
      expiresAt = this.now() + MAX_AGE;
    this.state.sessions = this.state.sessions.filter((entry) => entry.expiresAt > this.now());
    const sessions = this.state.sessions.filter((entry) => entry.user === user.id);
    while (sessions.length >= 16) {
      const old = sessions.shift();
      this.state.sessions = this.state.sessions.filter((entry) => entry !== old);
    }
    this.state.sessions.push({ hash: hash(token), user: user.id, expiresAt });
    return { token, expiresAt, user: dto(user) };
  }
  revoke(user) {
    this.state.sessions = this.state.sessions.filter((entry) => entry.user !== user);
  }
  invalidatePending(user) {
    this.state.pending = this.state.pending.filter((entry) => entry.user !== user);
  }
  status() {
    return { setupRequired: this.state.users.length === 0 };
  }
  async register(body, address) {
    const name = username(body.username);
    this.rate(`name:${name}`);
    if (Object.hasOwn(body, 'role')) fail('INVALID_ROLE');
    const bootstrap = this.state.users.length === 0;
    if (bootstrap) {
      const accepted = await this.verifyInstallation(body.installationPassword, address);
      if (accepted !== 'OK')
        fail(
          accepted === 'LIMIT' ? 'LIMIT' : 'INSTALLATION_REQUIRED',
          accepted === 'LIMIT' ? 429 : 403,
        );
    }
    const password = await this.passwordHash(body.password);
    const result = this.mutate(() => {
      // תוצאות הגיבוב חוזרות בסדר לא קבוע: בודקים שוב בעלות ושם בתוך הפעולה הסינכרונית.
      if (this.state.users.length === 0 && !bootstrap) fail('INSTALLATION_REQUIRED', 403);
      if (this.state.users.some((entry) => entry.username === name)) fail('USERNAME_TAKEN', 409);
      if (this.state.users.length >= 100) fail('LIMIT', 429);
      const user = {
        id: id(),
        username: name,
        password,
        role: this.state.users.length === 0 ? 'admin' : 'user',
        disabled: false,
        mustChangePassword: false,
        createdAt: this.now(),
      };
      this.state.users.push(user);
      return this.session(user);
    });
    this.onRevoke();
    return result;
  }
  async login(body) {
    let name;
    try {
      name = username(body.username);
    } catch {
      fail('INVALID_CREDENTIALS', 401);
    }
    this.rate(`name:${name}`);
    const user = this.state.users.find((entry) => entry.username === name);
    const expected = user?.password || {
      salt: 'AAAAAAAAAAAAAAAAAAAAAA',
      hash: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    };
    let valid = false;
    if (passwordValid(body.password)) valid = await this.passwordHash(body.password, expected);
    if (!valid || !user || user.disabled || user.password !== expected)
      fail('INVALID_CREDENTIALS', 401);
    const result = this.mutate(() => this.session(user));
    this.onRevoke();
    return result;
  }
  async changePassword(token, body) {
    const { user } = this.auth(token, true),
      expected = user.password;
    if (
      !passwordValid(body.currentPassword) ||
      !(await this.passwordHash(body.currentPassword, expected))
    )
      fail('INVALID_CREDENTIALS', 401);
    const password = await this.passwordHash(body.newPassword);
    this.auth(token, true);
    if (user.password !== expected) fail('UNAUTHORIZED', 401);
    const result = this.mutate(() => {
      user.password = password;
      user.mustChangePassword = false;
      this.revoke(user.id);
      this.invalidatePending(user.id);
      return this.session(user);
    });
    this.onRevoke();
    return result;
  }
  admin(token) {
    const auth = this.auth(token);
    if (auth.user.role !== 'admin') fail('FORBIDDEN', 403);
    return auth;
  }
  listUsers(token) {
    this.admin(token);
    return {
      users: this.state.users.map((user) => ({
        ...dto(user),
        createdAt: user.createdAt,
        deviceCount: Object.values(this.state.bindings).filter(
          (entry) => entry.user === user.id && entry.active,
        ).length,
      })),
    };
  }
  async reset(token, userId, body) {
    this.admin(token);
    const password = await this.passwordHash(body.password);
    const { user: actor } = this.admin(token);
    const user = this.state.users.find((entry) => entry.id === userId);
    if (!user) fail('NOT_FOUND', 404);
    if (user.id === actor.id) fail('FORBIDDEN', 403);
    this.mutate(() => {
      user.password = password;
      user.mustChangePassword = true;
      this.revoke(user.id);
      this.invalidatePending(user.id);
    });
    this.onRevoke();
    return { ok: true };
  }
  setState(token, userId, body) {
    const { user: actor } = this.admin(token);
    const user = this.state.users.find((entry) => entry.id === userId);
    if (!user) fail('NOT_FOUND', 404);
    if (user.id === actor.id || user.role === 'admin') fail('FORBIDDEN', 403);
    if (typeof body.disabled !== 'boolean') fail('INVALID_STATE');
    this.mutate(() => {
      user.disabled = body.disabled;
      if (user.disabled) {
        this.revoke(user.id);
        this.invalidatePending(user.id);
      }
    });
    this.onRevoke();
    return { ok: true };
  }
  logout(token) {
    const { session } = this.auth(token, true);
    this.mutate(() => {
      this.state.sessions = this.state.sessions.filter((entry) => entry !== session);
    });
    this.onRevoke();
    return { ok: true };
  }
  devices(token) {
    const { user } = this.auth(token);
    return {
      devices: Object.values(this.state.bindings)
        .filter((entry) => entry.user === user.id && entry.active)
        .map((entry) => ({ ...entry.record })),
    };
  }
  prepare(token, body) {
    const { user } = this.auth(token);
    if (!ID.test(body.device) || !this.deviceExists(body.device)) fail('DEVICE_NOT_FOUND', 404);
    if (typeof body.name !== 'string' || body.name.length > 80) fail('INVALID_NAME');
    const linked = Object.values(this.state.bindings).find(
      (entry) => entry.user === user.id && entry.device === body.device && entry.active,
    );
    if (linked) return { linked: true, record: { ...linked.record } };
    return this.mutate(() => {
      this.state.pending = this.state.pending.filter(
        (entry) => !entry.linked || entry.checkedTickets?.length || entry.expiresAt > this.now(),
      );
      let pending = this.state.pending.find(
        (entry) => entry.user === user.id && entry.device === body.device && !entry.linked,
      );
      const slots =
        Object.values(this.state.bindings).filter((entry) => entry.user === user.id && entry.active)
          .length +
        this.state.pending.filter((entry) => entry.user === user.id && !entry.linked).length;
      if (!pending && slots >= 32) fail('LIMIT', 429);
      if (!pending) {
        const controller = id();
        pending = { user: user.id, device: body.device, controller, secret: secret() };
        this.state.pending.push(pending);
        // גם הרשאה שטרם אושרה אינה יכולה לעקוף בעתיד את אימות החשבון כהרשאת עבר מקומית.
        this.state.bindings[controller] = { user: user.id, device: body.device, active: false };
      }
      const ticket = secret();
      Object.assign(pending, {
        ticketHash: hash(ticket),
        name: body.name,
        expiresAt: this.now() + 120000,
      });
      return {
        linked: false,
        ticket,
        device: pending.device,
        controller: pending.controller,
        secret: pending.secret,
        name: pending.name,
        expiresAt: pending.expiresAt,
      };
    });
  }
  pendingTicket(device, ticket, controller, confirmation = false) {
    if (
      typeof ticket !== 'string' ||
      !TOKEN.test(ticket) ||
      typeof controller !== 'string' ||
      !ID.test(controller) ||
      typeof device !== 'string' ||
      !ID.test(device)
    )
      fail('INVALID_TICKET');
    const ticketHash = hash(ticket);
    const pending = this.state.pending.find(
      (entry) =>
        entry.device === device &&
        entry.controller === controller &&
        ((entry.ticketHash === ticketHash && entry.expiresAt > this.now()) ||
          (confirmation && entry.checkedTickets?.includes(ticketHash))),
    );
    const user = pending && this.state.users.find((entry) => entry.id === pending.user);
    if (!pending || !user || user.disabled || user.mustChangePassword) fail('INVALID_TICKET', 403);
    if (pending.linked && !this.state.bindings[controller]?.active) fail('INVALID_TICKET', 403);
    return { pending, user };
  }
  // בדיקת ההכנה קודמת לשמירה בטלוויזיה. רק ההרשאה שהשרת יצר מוחזרת לסוכן המכשיר המתאים.
  // הבדיקה אינה מפעילה את ההרשאה: רק אישור השמירה הבא מקשר אותה לחשבון.
  // גיבוב הכרטיס נשמר לפני התשובה כדי שאישור סוכן שנדחה עקב ניתוק יוכל להשלים גם אחרי פקיעתו.
  validateTicket(device, ticket, controller) {
    const { pending } = this.pendingTicket(device, ticket, controller);
    const ticketHash = hash(ticket);
    if (!pending.checkedTickets?.includes(ticketHash))
      this.mutate(() => {
        if ((pending.checkedTickets?.length || 0) >= 16) fail('LIMIT', 429);
        pending.checkedTickets = [...(pending.checkedTickets || []), ticketHash];
      });
    return { controller: pending.controller, secret: pending.secret, name: pending.name };
  }
  confirm(device, ticket, controller) {
    const { pending, user } = this.pendingTicket(device, ticket, controller, true);
    if (pending.linked) {
      return { ok: true };
    }
    this.mutate(() => {
      const record = {
        device,
        controller,
        secret: pending.secret,
        name: pending.name,
        linkedAt: this.now(),
      };
      this.state.bindings[controller] = { user: user.id, device, active: true, record };
      pending.linked = true;
    });
    return { ok: true };
  }
  unlink(token, device) {
    const { user } = this.auth(token);
    this.mutate(() => {
      for (const binding of Object.values(this.state.bindings))
        if (binding.user === user.id && binding.device === device) {
          binding.active = false;
          delete binding.record;
        }
      this.state.pending = this.state.pending.filter(
        (entry) => entry.user !== user.id || entry.device !== device,
      );
    });
    this.onRevoke();
    return { ok: true };
  }
  authorizeController(token, device, controller) {
    const binding = this.state.bindings[controller];
    if (!binding) return null;
    const { user, session } = this.auth(token);
    if (!binding.active || binding.user !== user.id || binding.device !== device)
      fail('ACCOUNT_DENIED', 403);
    return { user: user.id, session: session.hash, device, controller };
  }
  validSocket(auth) {
    if (!auth) return true;
    const session = this.state.sessions.find((entry) => entry.hash === auth.session);
    const user = this.state.users.find((entry) => entry.id === auth.user);
    const binding = this.state.bindings[auth.controller];
    return !!(
      session &&
      session.expiresAt > this.now() &&
      user &&
      !user.disabled &&
      !user.mustChangePassword &&
      binding?.active &&
      binding.user === auth.user &&
      binding.device === auth.device
    );
  }
}
