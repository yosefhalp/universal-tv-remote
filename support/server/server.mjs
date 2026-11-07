import http from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import {
  createReadStream,
  readFileSync,
  writeFileSync,
  mkdirSync,
  renameSync,
  existsSync,
  statSync,
  openSync,
  fsyncSync,
  closeSync,
} from 'node:fs';
import { dirname, resolve, extname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import { PairingDirectory } from './pairing-directory.mjs';
import { EnrollmentPassword, decodeEnrollment } from './enrollment.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../web');
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.apk': 'application/vnd.android.package-archive',
  '.webmanifest': 'application/manifest+json',
  '.json': 'application/json; charset=utf-8',
};
const digest = (token) => createHash('sha256').update(token).digest('hex');
const equal = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a),
    right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
};

export async function startServer({
  port = 8788,
  host = '127.0.0.1',
  dataFile = '.local/relay/devices.json',
  publicOrigin = '',
  registrationKey = '',
  registrationKeyHash = '',
  enrollmentPasswordVerifier = '',
  maxDevices = 32,
  maxConnections = 128,
} = {}) {
  // נשמרת רק טביעת מפתח החיבור. הסודות המפענחים את השליטה ואת התמונה אינם מגיעים לשרת.
  const devices =
    dataFile && existsSync(dataFile) ? JSON.parse(readFileSync(dataFile, 'utf8')) : {};
  if (
    !devices ||
    Array.isArray(devices) ||
    typeof devices !== 'object' ||
    Object.entries(devices).some(
      ([id, hash]) =>
        !/^[a-f0-9]{32}$/.test(id) || typeof hash !== 'string' || !/^[a-f0-9]{64}$/.test(hash),
    )
  )
    throw new Error('INVALID_REGISTRY');
  if (registrationKeyHash && !/^[a-f0-9]{64}$/.test(registrationKeyHash))
    throw new Error('INVALID_ENROLLMENT_HASH');
  // בשרת קבוע שומרים גיבוב בלבד; מפתח ההתקנה עצמו נשאר אצל המתקין.
  const enrollmentHash = registrationKeyHash || (registrationKey ? digest(registrationKey) : '');
  const password = enrollmentPasswordVerifier
    ? new EnrollmentPassword(enrollmentPasswordVerifier)
    : null;
  const rooms = new Map(),
    rates = new Map();
  const pairings = new PairingDirectory();
  const persist = () => {
    if (!dataFile) return;
    mkdirSync(dirname(resolve(dataFile)), { recursive: true });
    writeFileSync(`${dataFile}.tmp`, JSON.stringify(devices), { mode: 0o600, flush: true });
    renameSync(`${dataFile}.tmp`, dataFile);
    // בשרת Linux משלימים גם את כתיבת רישום התיקייה לפני אישור רישום המכשיר.
    if (process.platform !== 'win32') {
      const directory = openSync(dirname(resolve(dataFile)), 'r');
      try {
        fsyncSync(directory);
      } finally {
        closeSync(directory);
      }
    }
  };
  const server = http.createServer((req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    res.setHeader(
      'Content-Security-Policy',
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self' ws://localhost:* ws://127.0.0.1:*; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    );
    let path;
    try {
      path = new URL(req.url, 'http://local').pathname;
    } catch {
      res.writeHead(400);
      res.end();
      return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) {
      res.writeHead(405);
      res.end();
      return;
    }
    if (path === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, protocol: 1 }));
      return;
    }
    if (/^\/v1\/pair\/[0-9]{2}$/.test(path)) {
      const key = `pair:${req.socket.remoteAddress}`;
      const now = Date.now();
      let rate = rates.get(key);
      if (!rate || now - rate.start > 60000) rate = { start: now, count: 0 };
      rates.set(key, rate);
      const limited = ++rate.count > 60;
      const entry = limited ? null : pairings.lookup(path.slice(-2));
      res.writeHead(limited ? 429 : entry ? 200 : 404, {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      });
      res.end(
        req.method === 'HEAD'
          ? undefined
          : JSON.stringify(entry || { error: limited ? 'LIMIT' : 'EXPIRED' }),
      );
      return;
    }
    const name = path === '/' ? 'index.html' : path.slice(1);
    if (name !== basename(name) || !/^[a-z0-9.-]+$/.test(name)) {
      res.writeHead(404);
      res.end();
      return;
    }
    const file = resolve(ROOT, name);
    if (!existsSync(file) || !statSync(file).isFile() || !TYPES[extname(name)]) {
      res.writeHead(404);
      res.end();
      return;
    }
    // מטמון השלד מנוהל ב-Service Worker; אין לתת ל-CDN לשמור גרסת קוד קודמת אחרי עדכון.
    res.setHeader('Cache-Control', 'no-store');
    res.writeHead(200, {
      'Content-Type': TYPES[extname(name)],
      'Content-Length': statSync(file).size,
    });
    if (req.method === 'HEAD') res.end();
    else
      createReadStream(file)
        .on('error', () => res.destroy())
        .pipe(res);
  });
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: 1500000,
    perMessageDeflate: false,
  });
  const send = (ws, value) => {
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
  };
  const close = (ws, code = 1000) => {
    if (ws && ws.readyState < WebSocket.CLOSING) ws.close(code);
  };
  server.on('upgrade', async (req, socket, head) => {
    // גם חיבור שנסגר במהלך גיבוב הסיסמה מטופל בלי שגיאת socket לא נתפסת.
    socket.on('error', () => {});
    const reject = (status) => {
      socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    };
    let url;
    try {
      url = new URL(req.url, 'http://local');
    } catch {
      reject('400 Bad Request');
      return;
    }
    const match = /^\/v1\/(agent|client)\/([a-f0-9]{32})$/.exec(url.pathname);
    if (!match || url.search) {
      reject('404 Not Found');
      return;
    }
    if (wss.clients.size >= maxConnections) {
      reject('503 Service Unavailable');
      return;
    }
    const address = req.socket.remoteAddress;
    const now = Date.now();
    const rate = rates.get(address) || { start: now, count: 0 };
    if (now - rate.start > 60000) {
      rate.start = now;
      rate.count = 0;
    }
    rates.set(address, rate);
    if (++rate.count > 90) {
      reject('429 Too Many Requests');
      return;
    }
    const [, role, id] = match;
    const origin = req.headers.origin;
    const allowedOrigin = publicOrigin || `http://${req.headers.host}`;
    if (role === 'client' && origin && origin !== allowedOrigin) {
      reject('403 Forbidden');
      return;
    }
    if (role === 'agent') {
      const token = req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
      if (!token) {
        reject('401 Unauthorized');
        return;
      }
      const hash = digest(token);
      if (Object.hasOwn(devices, id) && !equal(devices[id], hash)) {
        reject('403 Forbidden');
        return;
      }
      if (!Object.hasOwn(devices, id)) {
        const enrollment = decodeEnrollment(req.headers['x-karov-enrollment']);
        let accepted;
        try {
          accepted = password
            ? await password.check(enrollment, address)
            : enrollmentHash && equal(digest(enrollment), enrollmentHash)
              ? 'OK'
              : 'DENIED';
        } catch {
          reject('503 Service Unavailable');
          return;
        }
        if (socket.destroyed) return;
        if (accepted !== 'OK') {
          reject(accepted === 'LIMIT' ? '429 Too Many Requests' : '403 Forbidden');
          return;
        }
        // גיבוב אסינכרוני אינו רשאי לדרוס זהות שנרשמה בינתיים בחיבור אחר.
        if (Object.hasOwn(devices, id) && !equal(devices[id], hash)) {
          reject('403 Forbidden');
          return;
        }
        if (Object.keys(devices).length >= maxDevices) {
          reject('503 Service Unavailable');
          return;
        }
        devices[id] = hash;
        try {
          persist();
        } catch {
          delete devices[id];
          reject('503 Service Unavailable');
          return;
        }
      }
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      let room = rooms.get(id);
      if (!room && role === 'client') {
        send(ws, { t: 'offline' });
        close(ws, 4003);
        return;
      }
      if (!room) {
        room = { agent: null, client: null };
        rooms.set(id, room);
      }
      ws.alive = true;
      ws.on('pong', () => {
        ws.alive = true;
      });
      ws.on('error', () => {});
      if (role === 'agent') {
        pairings.clear(room.agent);
        close(room.agent, 4002);
        // החלפת הסוכן מבטלת את הסשן כדי שלא יועברו פקודות עם מפתחות מהחיבור הקודם.
        send(room.client, { t: 'offline' });
        close(room.client, 4003);
        room.client = null;
        room.agent = ws;
      } else {
        if (!room.agent || room.agent.readyState !== WebSocket.OPEN) {
          send(ws, { t: 'offline' });
          close(ws, 4003);
          return;
        }
        if (room.client) {
          send(ws, { t: 'busy' });
          close(ws, 4004);
          return;
        }
        room.client = ws;
        send(room.agent, { t: 'peer-joined' });
      }
      let burstStart = Date.now(),
        messages = 0;
      ws.on('message', (raw, binary) => {
        if (Date.now() - burstStart >= 1000) {
          burstStart = Date.now();
          messages = 0;
        }
        if (++messages > 80 || binary) {
          close(ws, 4008);
          return;
        }
        if (room[role] !== ws) return;
        const other = role === 'agent' ? room.client : room.agent;
        const value = raw.toString();
        if (raw.length < 2048) {
          let control;
          try {
            control = JSON.parse(value);
          } catch {
            /* הודעות אחרות נבדקות אצל הסטרימר. */
          }
          if (control?.t === 'pair-register' || control?.t === 'pair-clear') {
            if (role !== 'agent') return;
            if (control.t === 'pair-clear') pairings.clear(ws);
            else {
              try {
                const prefix = pairings.register(ws, id, control);
                send(ws, { t: 'pair-code', request: control.request, prefix });
              } catch {
                send(ws, { t: 'pair-code', request: control.request, error: 'UNAVAILABLE' });
              }
            }
            return;
          }
          if (control?.t === 'pair-code') return;
        }
        if (value === '{"t":"ping"}') {
          send(ws, { t: 'pong' });
          return;
        }
        if (role === 'agent' && value === '{"t":"kick"}') {
          close(room.client, 4005);
          return;
        }
        if (other?.readyState === WebSocket.OPEN) {
          if (other.bufferedAmount > 2000000) {
            close(other, 4009);
            return;
          }
          other.send(value);
        }
      });
      ws.on('close', () => {
        if (room[role] !== ws) return;
        room[role] = null;
        if (role === 'agent') {
          pairings.clear(ws);
          send(room.client, { t: 'offline' });
          close(room.client, 4003);
        } else send(room.agent, { t: 'peer-left' });
        if (!room.agent && !room.client) rooms.delete(id);
      });
    });
  });
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.alive) {
        ws.terminate();
        continue;
      }
      ws.alive = false;
      ws.ping();
    }
    for (const [ip, rate] of rates) if (Date.now() - rate.start > 60000) rates.delete(ip);
    pairings.prune();
  }, 20000);
  heartbeat.unref();
  await new Promise((done, fail) => {
    server.once('error', fail);
    server.listen(port, host, done);
  });
  return {
    port: server.address().port,
    rooms,
    close: async () => {
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      await new Promise((done) => wss.close(done));
      await new Promise((done) => server.close(done));
    },
  };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const service = await startServer({
    port: Number(process.env.PORT || 8788),
    host: process.env.HOST || '127.0.0.1',
    dataFile: process.env.DATA_FILE || '.local/relay/devices.json',
    publicOrigin: process.env.PUBLIC_ORIGIN || '',
    registrationKey: process.env.ENROLLMENT_KEY || '',
    registrationKeyHash: process.env.ENROLLMENT_KEY_HASH || '',
    enrollmentPasswordVerifier: process.env.ENROLLMENT_PASSWORD_VERIFIER || '',
  });
  console.log(`TV Support listening on port ${service.port}`);
  let stopping = false;
  for (const event of ['SIGINT', 'SIGTERM'])
    process.on(event, async () => {
      if (stopping) return;
      stopping = true;
      await service.close();
    });
}
