// Runs on the TV: keeps a connection to the relay, shows the weekly codes, and carries out what an authenticated phone asks.
// Runs on old TV browsers, so it stays ES5. An adapter does the platform-specific part.
(function (root) {
  var Session = root.TvSession;
  var METHODS = ['button', 'move', 'click', 'scroll', 'insertText', 'deleteCharacters', 'sendEnter', 'listApps', 'launchApp',
    'listInputs', 'switchInput', 'setVolume', 'setMute', 'powerOff', 'screenOff', 'screenOn', 'capture', 'getState'];
  var RECONNECT_MAX_MS = 60000;
  var PING_MS = 25000;
  var FREE_TRIES = 5;
  var AUTH_TIMEOUT_MS = 15000;

  function Agent(options) {
    this.relay = options.relay.replace(/\/$/, '');
    this.adapter = options.adapter;
    this.storage = options.storage;
    this.onChange = options.onChange;
    this.WebSocketClass = options.WebSocketClass || root.WebSocket;
    this.now = options.now || function () { return new Date(); };
    this.ws = null;
    this.status = 'starting';
    this.peer = false;
    this.failures = 0;
    this.blockedUntil = 0;
    this.reconnectMs = 2000;
    this.secret = this.storage.getItem('agentSecret');
    if (!this.secret) {
      this.secret = Session.randomSecret();
      this.storage.setItem('agentSecret', this.secret);
    }
    this.token = this.storage.getItem('agentToken');
    if (!this.token) {
      this.token = Session.newNonce().replace(/[^0-9A-Za-z]/g, '');
      this.storage.setItem('agentToken', this.token);
    }
  }

  Agent.prototype.start = function () {
    if (this.running) return;
    this.running = true;
    var self = this;
    this._refreshCodes();
    this._connect();
    this.weekTimer = setInterval(function () { self._checkWeek(); }, 60000);
  };

  // עצירה מפורשת מונעת חיבור מחדש ומשחררת את כל הטיימרים גם כשהרשת כבר נותקה.
  Agent.prototype.stop = function () {
    this.running = false;
    clearInterval(this.weekTimer);
    clearInterval(this.pingTimer);
    clearTimeout(this.reconnectTimer);
    this._endPeer();
    var ws = this.ws;
    this.ws = null;
    if (ws) { ws.onclose = null; ws.onmessage = null; ws.onopen = null; ws.close(); }
    this._setStatus('offline');
  };

  Agent.prototype._rotation = function () {
    return Number(this.storage.getItem('agentRotation') || 0);
  };

  Agent.prototype._refreshCodes = function () {
    this.week = Session.weekNumber(this.now());
    this.codes = Session.weeklyCodes(Session.secretBytes(this.secret), this.week, this._rotation());
    this.key = Session.deriveKey(this.codes.password, this.codes.id);
    this.failures = 0;
    this.blockedUntil = 0;
    this._notify();
  };

  Agent.prototype._checkWeek = function () {
    if (Session.weekNumber(this.now()) !== this.week) this._rollCodes();
  };

  Agent.prototype._rollCodes = function () {
    this._refreshCodes();
    if (this.ws) this.ws.close();
  };

  // For when the codes reached someone they shouldn't have.
  Agent.prototype.newCodes = function () {
    this.storage.setItem('agentRotation', String(this._rotation() + 1));
    this._rollCodes();
  };

  Agent.prototype._notify = function () {
    this.onChange({
      id: this.codes.id,
      password: this.codes.password,
      validUntil: Session.nextChange(this.now()),
      status: this.status,
      peer: this.peer,
    });
  };

  Agent.prototype._setStatus = function (status) {
    this.status = status;
    this._notify();
  };

  Agent.prototype._connect = function () {
    if (!this.running) return;
    var self = this;
    var ws;
    this._setStatus('connecting');
    try {
      ws = new this.WebSocketClass(this.relay + '/agent/' + this.codes.id + '?token=' + this.token);
    } catch (err) {
      this._scheduleReconnect();
      return;
    }
    this.ws = ws;
    ws.onopen = function () {
      if (!self.running || self.ws !== ws) { ws.close(); return; }
      self.reconnectMs = 2000;
      self._setStatus('online');
      self.pingTimer = setInterval(function () { if (ws.readyState === 1) ws.send('ping'); }, PING_MS);
    };
    ws.onmessage = function (event) { if (self.running && self.ws === ws) self._onMessage(event.data); };
    ws.onclose = function () {
      if (self.ws !== ws) return;
      clearInterval(self.pingTimer);
      self.ws = null;
      self._endPeer();
      self._scheduleReconnect();
    };
    ws.onerror = function () {};
  };

  Agent.prototype._scheduleReconnect = function () {
    if (!this.running) return;
    var self = this;
    this._setStatus('offline');
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(function () { self._connect(); }, this.reconnectMs);
    this.reconnectMs = Math.min(this.reconnectMs * 2, RECONNECT_MAX_MS);
  };

  Agent.prototype._send = function (object) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(object));
  };

  Agent.prototype._kick = function () {
    clearTimeout(this.authTimer);
    if (this.ws && this.ws.readyState === 1) this.ws.send('kick');
  };

  Agent.prototype._endPeer = function () {
    clearTimeout(this.authTimer);
    this.cipher = null;
    this.pendingAuth = null;
    if (this.peer) {
      this.peer = false;
      this._notify();
    }
  };

  // Each wrong password doubles the wait, which stops guessing through the TV.
  Agent.prototype._onFailure = function () {
    this.failures += 1;
    if (this.failures >= FREE_TRIES) {
      this.blockedUntil = this.now().getTime() + 30000 * Math.pow(2, Math.min(this.failures - FREE_TRIES, 8));
    }
    this.pendingAuth = null;
    this._send({ t: 'denied' });
    this._kick();
  };

  Agent.prototype._onMessage = function (raw) {
    if (raw === 'pong') return;
    var msg;
    try {
      msg = JSON.parse(raw);
    } catch (err) {
      return;
    }
    if (msg.relay === 'peer-left') {
      this._endPeer();
    } else if (msg.t === 'hello') {
      this._onHello(msg);
    } else if (msg.t === 'auth' && this.pendingAuth) {
      this._onAuth(msg);
    } else if (msg.t === 'd' && this.cipher) {
      var request = this.cipher.open(msg.b);
      if (request) this._onRequest(request);
    }
  };

  Agent.prototype._onHello = function (msg) {
    this._endPeer();
    if (this.now().getTime() < this.blockedUntil) {
      this._send({ t: 'blocked' });
      this._kick();
      return;
    }
    var nonce = Session.newNonce();
    try {
      this.pendingAuth = Session.sessionCipher(this.key, msg.n, nonce, false);
    } catch (err) {
      return;
    }
    var self = this;
    this.authTimer = setTimeout(function () {
      if (self.pendingAuth) {
        self.pendingAuth = null;
        self._kick();
      }
    }, AUTH_TIMEOUT_MS);
    this._send({ t: 'challenge', n: nonce });
  };

  Agent.prototype._onAuth = function (msg) {
    var cipher = this.pendingAuth;
    var proof = cipher.open(msg.b);
    if (!proof || proof.proof !== 'phone') {
      this._onFailure();
      return;
    }
    clearTimeout(this.authTimer);
    this.pendingAuth = null;
    this.cipher = cipher;
    this.failures = 0;
    this.peer = true;
    this._notify();
    this._send({ t: 'ok', b: cipher.seal({ proof: 'tv', info: this.adapter.info() }) });
  };

  Agent.prototype._reply = function (id, ok, value) {
    if (!this.cipher) return;
    this._send({ t: 'd', b: this.cipher.seal(ok ? { id: id, ok: true, v: value } : { id: id, ok: false, e: String(value) }) });
  };

  Agent.prototype._onRequest = function (request) {
    var self = this;
    var cipher = this.cipher;
    var id = request.id;
    var done = function (err, value) {
      if (self.cipher !== cipher) return;
      if (id === undefined) return;
      if (err) self._reply(id, false, err.message || err);
      else self._reply(id, true, value);
    };
    if (METHODS.indexOf(request.m) < 0 || typeof this.adapter[request.m] !== 'function') {
      done(new Error('Unsupported'));
      return;
    }
    try {
      this.adapter[request.m].apply(this.adapter, (request.a || []).concat(done));
    } catch (err) {
      done(err);
    }
  };

  root.TvAgent = Agent;
  if (typeof module === 'object' && module.exports) module.exports = Agent;
}(typeof window !== 'undefined' ? window : global));
