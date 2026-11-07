import { AccountError } from './accounts.mjs';

const bodyJson = async (req) => {
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || ''))
    throw new AccountError('JSON_REQUIRED', 415);
  if (Number(req.headers['content-length']) > 16384) throw new AccountError('BODY_LIMIT', 413);
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 16384) throw new AccountError('BODY_LIMIT', 413);
    chunks.push(chunk);
  }
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
    return body;
  } catch {
    throw new AccountError('INVALID_JSON');
  }
};

// נתיבי החשבון אינם משתפים מידע עם אתרים זרים, וסודות לעולם אינם מתקבלים מכתובת הבקשה.
export async function accountHttp(req, res, path, accounts, publicOrigin) {
  const reply = (status, value) => {
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    res.end(JSON.stringify(value));
  };
  try {
    const allowedOrigin = publicOrigin || `http://${req.headers.host}`;
    if (req.headers.origin && req.headers.origin !== allowedOrigin)
      throw new AccountError('FORBIDDEN', 403);
    if (new URL(req.url, 'http://local').search) throw new AccountError('INVALID_REQUEST');
    const method = req.method;
    const route = path.slice('/v1/account'.length);
    // במנהרה כמה מכשירים חולקים כתובת אחת. קריאות סנכרון אינן צורכות את מכסת ניסיונות הסיסמה.
    // הכתובת נלקחת מחיבור השרת בלבד; כותרות שמועברות בידי הלקוח אינן מקור מהימן לזהותו.
    const passwordRequest =
      method === 'POST' &&
      (['/login', '/register', '/password'].includes(route) ||
        /^\/admin\/users\/[a-f0-9]{32}\/reset$/.test(route));
    const bucket = passwordRequest ? 'auth' : method === 'GET' ? 'read' : 'write';
    accounts.rate(
      `ip:${bucket}:${req.socket.remoteAddress}`,
      passwordRequest ? 60 : method === 'GET' ? 1200 : 180,
    );
    const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '')?.[1];
    const body = ['POST', 'PUT', 'PATCH'].includes(method) ? await bodyJson(req) : {};
    let value;
    if (route === '/status' && method === 'GET') value = accounts.status();
    else if (route === '/register' && method === 'POST')
      value = await accounts.register(body, req.socket.remoteAddress);
    else if (route === '/login' && method === 'POST') value = await accounts.login(body);
    else if (route === '/me' && method === 'GET') {
      const { user } = accounts.auth(token, true);
      value = {
        user: {
          id: user.id,
          username: user.username,
          role: user.role,
          mustChangePassword: user.mustChangePassword,
          disabled: user.disabled,
        },
      };
    } else if (route === '/logout' && method === 'POST') value = accounts.logout(token);
    else if (route === '/password' && method === 'POST')
      value = await accounts.changePassword(token, body);
    else if (route === '/admin/users' && method === 'GET') value = accounts.listUsers(token);
    else if (/^\/admin\/users\/[a-f0-9]{32}\/reset$/.test(route) && method === 'POST')
      value = await accounts.reset(token, route.split('/')[3], body);
    else if (/^\/admin\/users\/[a-f0-9]{32}\/state$/.test(route) && method === 'POST')
      value = accounts.setState(token, route.split('/')[3], body);
    else if (route === '/devices' && method === 'GET') value = accounts.devices(token);
    else if (route === '/devices/prepare' && method === 'POST')
      value = accounts.prepare(token, body);
    else if (/^\/devices\/[a-f0-9]{32}$/.test(route) && method === 'DELETE')
      value = accounts.unlink(token, route.split('/')[2]);
    else throw new AccountError('NOT_FOUND', 404);
    reply(200, value);
  } catch (error) {
    reply(error instanceof AccountError ? error.status : 503, {
      error: error instanceof AccountError ? error.message : 'UNAVAILABLE',
    });
  }
}
