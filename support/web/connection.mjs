import { Cipher, sessionKeys, randomSecret, validId, MAX_PACKET } from './protocol.mjs';

export class Connection {
  constructor({ url, device, controller, secret, WebSocketClass = WebSocket, onClose = () => {} }) {
    if (!validId(device) || !(controller === 'pair' || validId(controller)))
      throw new Error('BAD_ID');
    this.options = { url, device, controller, secret, WebSocketClass, onClose };
    this.pending = new Map();
    this.nextId = 1;
    this.generation = 0;
  }
  async connect() {
    this.close('REPLACED');
    const generation = ++this.generation;
    const { url, device, controller, secret, WebSocketClass } = this.options;
    const ws = (this.ws = new WebSocketClass(`${url}/v1/client/${device}`));
    this.incoming = Promise.resolve();
    const nonce = randomSecret();
    return new Promise((resolve, reject) => {
      let authenticated = false,
        keys;
      const fail = (reason) => {
        if (generation !== this.generation) return;
        reject(new Error(reason));
        this.close(reason);
      };
      this.cancelConnect = reject;
      const timer = setTimeout(() => fail('CONNECT_TIMEOUT'), 12000);
      this.connectTimer = timer;
      ws.onopen = () => ws.send(JSON.stringify({ t: 'hello', v: 1, controller, nonce }));
      ws.onerror = () => fail('NETWORK');
      ws.onclose = () => {
        if (this.ws !== ws) return;
        if (!authenticated) reject(new Error('DISCONNECTED'));
        this.close('DISCONNECTED');
      };
      ws.onmessage = ({ data }) => {
        this.incoming = this.incoming
          .then(async () => {
            if (generation !== this.generation || this.ws !== ws) return;
            if (typeof data !== 'string' || data.length > MAX_PACKET)
              throw new Error('BAD_MESSAGE');
            const message = JSON.parse(data);
            if (message.t === 'pong') return;
            if (['offline', 'busy', 'denied', 'expired', 'blocked'].includes(message.t))
              throw new Error(message.t.toUpperCase());
            if (message.t === 'challenge' && !authenticated && !keys) {
              keys = await sessionKeys(secret, device, controller, nonce, message.nonce);
              if (!(await keys.verify('agent', message.proof))) throw new Error('BAD_IDENTITY');
              if (generation !== this.generation) return;
              this.cipher = new Cipher(keys, true);
              ws.send(JSON.stringify({ t: 'auth', proof: await keys.proof('client') }));
            } else if (message.t === 'data' && this.cipher) {
              const value = await this.cipher.open(message);
              if (generation !== this.generation) return;
              if (!authenticated) {
                if (value.type !== 'ready') throw new Error('BAD_HANDSHAKE');
                authenticated = this.ready = true;
                this.cancelConnect = null;
                clearTimeout(timer);
                // בודקים גם את הסטרימר דרך הערוץ המוצפן; תשובת השרת לבדה אינה מוכיחה שהטלוויזיה זמינה.
                if (controller !== 'pair')
                  this.pingTimer = setInterval(() => {
                    if (generation === this.generation && this.ready)
                      this.call('info', {}, 5000).catch(() => {});
                  }, 15000);
                resolve(value.info);
              } else {
                const pending = this.pending.get(value.id);
                if (!pending) return;
                this.pending.delete(value.id);
                clearTimeout(pending.timer);
                if (value.ok) pending.resolve(value.value);
                else
                  pending.reject(
                    new Error(typeof value.error === 'string' ? value.error : 'FAILED'),
                  );
              }
            } else throw new Error('BAD_MESSAGE');
          })
          .catch((error) => {
            if (generation === this.generation) fail(error.message);
          });
      };
    });
  }
  async call(method, args = {}, timeout = 10000) {
    if (!this.ready || this.ws?.readyState !== 1) throw new Error('DISCONNECTED');
    if (this.pending.size >= 16) throw new Error('TOO_MANY_REQUESTS');
    const id = this.nextId++,
      generation = this.generation,
      ws = this.ws;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('REQUEST_TIMEOUT'));
        // אין להמשיך להציג חיבור פעיל או לשמור פקודות בתור אחרי שהסטרימר חדל לענות.
        if (generation === this.generation) this.close('REQUEST_TIMEOUT');
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.cipher
        .seal({ id, method, args })
        .then((packet) => {
          if (generation === this.generation && ws.readyState === 1)
            ws.send(JSON.stringify(packet));
        })
        .catch((error) => {
          this.close(error.message);
        });
    });
  }
  close(reason = 'CLOSED') {
    this.generation++;
    this.ready = false;
    clearTimeout(this.connectTimer);
    clearInterval(this.pingTimer);
    this.cancelConnect?.(new Error(reason));
    this.cancelConnect = null;
    const ws = this.ws;
    this.ws = null;
    this.cipher = null;
    if (ws) {
      ws.onclose = null;
      ws.onerror = () => {};
      ws.onmessage = null;
      ws.onopen = null;
      ws.close();
    }
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error(reason));
    }
    this.pending.clear();
    if (ws) this.options.onClose(reason);
  }
}
