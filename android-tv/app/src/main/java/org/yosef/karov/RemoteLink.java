package org.yosef.karov;

import okhttp3.*;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RemoteLink {
    public interface Listener {
        void state(String status, boolean active);
    }

    private final Vault vault;
    private final DeviceActions actions;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient client =
            new OkHttpClient.Builder()
                    .pingInterval(15, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build();
    private WebSocket socket;
    private Crypto.Session cipher;
    private PairingWindow pairing;
    private SrpExchange srp;
    private String pairingVersion = "", clientNonce = "", serverNonce = "";
    private volatile String pairingLabel = "מכינים קוד קצר…";
    private String controller;
    private boolean authenticated = false;
    private boolean codeAttempt = false;
    private volatile boolean stopped = false;
    private boolean credentialsRejected;
    private volatile long epoch = 0;
    private long blockedUntil = 0;
    private int failures = 0, retry = 0, inFlight = 0;
    private ScheduledFuture<?> reconnect, authenticationTimeout, pairingExpiry;
    private ScheduledFuture<?> accountRetry;
    private final java.util.Map<String, AccountGrant> accountGrants = new java.util.HashMap<>();

    private static final class AccountGrant {
        final Crypto.Session session;
        final long request;
        final boolean pairing;
        final String controller, secret, authorizer, pairVersion;
        boolean installed;
        ScheduledFuture<?> timeout;

        AccountGrant(
                Crypto.Session session,
                long request,
                boolean pairing,
                String controller,
                String secret,
                String authorizer,
                String pairVersion) {
            this.session = session;
            this.request = request;
            this.pairing = pairing;
            this.controller = controller;
            this.secret = secret;
            this.authorizer = authorizer;
            this.pairVersion = pairVersion;
        }
    }

    public RemoteLink(Vault vault, DeviceActions actions, Listener listener) {
        this.vault = vault;
        this.actions = actions;
        this.listener = listener;
    }

    public void start() {
        worker.execute(this::connect);
    }

    public String pairingLabel() {
        return pairingLabel;
    }

    public void beginPairing() {
        dispatch(() -> {
            try {
                if ("pair".equals(controller) || srp != null) {
                    simple("kick");
                    endSession();
                }
                clearPairing();
                pairing =
                        new PairingWindow(
                                vault.secretFor("pair"), Long.parseLong(vault.get("pairExpires")));
                pairing.request = Crypto.id();
                advertisePairing();
                pairingExpiry =
                        worker.schedule(
                                this::clearPairing,
                                Math.max(1, pairing.expires - System.currentTimeMillis()),
                                TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                clearPairing();
            }
        });
    }

    private void clearPairing() {
        pairing = null;
        pairingLabel = "הקוד אינו פעיל. לזיווג נוסף פתחו קוד חדש";
        if (pairingExpiry != null) pairingExpiry.cancel(false);
        simple("pair-clear");
    }

    private void advertisePairing() {
        if (pairing == null) return;
        try {
            if (!pairing.valid(vault.secretFor("pair"), System.currentTimeMillis())) {
                clearPairing();
                return;
            }
            if (!pairing.available()) return;
            pairing.registered = false;
            pairingLabel = "מכינים קוד קצר… אפשר כבר לסרוק את ה-QR";
            send(
                    new JSONObject()
                            .put("t", "pair-register")
                            .put("request", pairing.request)
                            .put("prefix", pairing.prefix)
                            .put("expires", pairing.expires)
                            .put("name", vault.get("name")));
            PairingWindow current = pairing;
            worker.schedule(
                    () -> {
                        if (pairing == current && !current.registered)
                            pairingLabel = "אין קוד קצר כרגע. אפשר לסרוק QR או לפתוח זיווג מחדש";
                    },
                    8,
                    TimeUnit.SECONDS);
        } catch (Exception error) {
            clearPairing();
        }
    }

    private boolean authorized() throws Exception {
        String current = vault.secretFor(controller);
        return !current.isEmpty() && (!"pair".equals(controller) || current.equals(pairingVersion));
    }

    public void networkAvailable() {
        if (!worker.isShutdown())
            worker.execute(() -> {
                if (!stopped && socket == null) {
                    retry = 0;
                    connect();
                }
            });
    }

    private void connect() {
        if (stopped
                || credentialsRejected
                || !vault.enabled()
                || vault.get("server").isEmpty()
                || socket != null) return;
        if (reconnect != null) reconnect.cancel(false);
        listener.state("מתחבר לשרת…", false);
        String url =
                vault.get("server").replaceFirst("^http", "ws")
                        + "/v1/agent/"
                        + vault.get("device");
        Request request =
                new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + vault.get("token"))
                        .header(
                                "X-Karov-Enrollment",
                                "pw1:"
                                        + java.util.Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(
                                                        vault.get("enrollment")
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8)))
                        .build();
        socket =
                client.newWebSocket(
                        request,
                        new WebSocketListener() {
                            @Override
                            public void onOpen(WebSocket ws, Response response) {
                                dispatch(() -> {
                                    if (ws != socket) {
                                        ws.cancel();
                                        return;
                                    }
                                    retry = 0;
                                    listener.state("מוכן לעזרה מרחוק", false);
                                    advertisePairing();
                                    replayAccountLinks();
                                    if (accountRetry != null) accountRetry.cancel(false);
                                    accountRetry =
                                            worker.scheduleAtFixedRate(
                                                    RemoteLink.this::replayAccountLinks,
                                                    15,
                                                    15,
                                                    TimeUnit.SECONDS);
                                });
                            }

                            @Override
                            public void onMessage(WebSocket ws, String text) {
                                dispatch(() -> {
                                    if (ws == socket) receive(text);
                                });
                            }

                            @Override
                            public void onFailure(
                                    WebSocket ws, Throwable error, Response response) {
                                dispatch(() -> {
                                    if (ws != socket) return;
                                    if (response != null
                                            && (response.code() == 401 || response.code() == 403)) {
                                        // שגיאת סיסמה דורשת תיקון בהכנה; אין לנחש אותה מחדש
                                        // אוטומטית ולצבור חסימה.
                                        credentialsRejected = true;
                                        socket = null;
                                        endSession();
                                        listener.state(
                                                "פרטי ההתקנה לא התקבלו. פתחו הגדרות וחיבור ובדקו את"
                                                        + " הסיסמה",
                                                false);
                                    } else {
                                        lost(ws);
                                        if (response != null && response.code() == 429) {
                                            if (reconnect != null) reconnect.cancel(false);
                                            reconnect =
                                                    worker.schedule(
                                                            RemoteLink.this::connect,
                                                            10,
                                                            TimeUnit.MINUTES);
                                            listener.state(
                                                    "בוצעו ניסיונות רבים. ננסה שוב בעוד עשר דקות",
                                                    false);
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onClosed(WebSocket ws, int code, String reason) {
                                dispatch(() -> lost(ws));
                            }

                            @Override
                            public void onClosing(WebSocket ws, int code, String reason) {
                                ws.close(code, null);
                                dispatch(() -> lost(ws));
                            }
                        });
    }

    private void dispatch(Runnable action) {
        if (!worker.isShutdown())
            try {
                worker.execute(action);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
            }
    }

    private void lost(WebSocket ws) {
        if (ws != socket) return;
        if (accountRetry != null) accountRetry.cancel(false);
        socket = null;
        if (pairing != null) {
            pairing.registered = false;
            pairingLabel = "ממתינים לחזרת חיבור האינטרנט…";
        }
        endSession();
        listener.state("אין חיבור. ננסה שוב אוטומטית", false);
        if (!stopped)
            reconnect =
                    worker.schedule(
                            this::connect,
                            Math.min(30000, 1000L << Math.min(retry++, 5)),
                            TimeUnit.MILLISECONDS);
    }

    private void send(JSONObject value) {
        if (socket != null && !socket.send(value.toString())) {
            WebSocket previous = socket;
            previous.cancel();
            lost(previous);
        }
    }

    private void simple(String type) {
        try {
            send(new JSONObject().put("t", type));
        } catch (Exception ignored) {
        }
    }

    private void endSession() {
        for (AccountGrant grant : accountGrants.values())
            if (grant.timeout != null) grant.timeout.cancel(false);
        accountGrants.clear();
        epoch++;
        cipher = null;
        srp = null;
        pairingVersion = clientNonce = serverNonce = "";
        controller = null;
        authenticated = false;
        codeAttempt = false;
        inFlight = 0;
        if (authenticationTimeout != null) authenticationTimeout.cancel(false);
        actions.active(false);
        listener.state(socket == null ? "אין חיבור לשרת" : "מוכן לעזרה מרחוק", false);
    }

    private void reject() {
        // לקוד הקצר יש מכסה נפרדת של חמישה ניסיונות. טעות בו אינה חוסמת מכשיר שכבר מורשה.
        if (!codeAttempt) failures++;
        if (!codeAttempt && failures >= 5)
            blockedUntil =
                    android.os.SystemClock.elapsedRealtime()
                            + Math.min(300000, 30000L << Math.min(failures - 5, 3));
        simple("denied");
        simple("kick");
        endSession();
    }

    private void receive(String text) {
        if (text.length() > 200000) {
            reject();
            return;
        }
        try {
            JSONObject value = new JSONObject(text);
            String type = value.optString("t");
            if (type.equals("pong")) return;
            if (type.equals("account-link-checked")) {
                String ticket = value.optString("ticket");
                AccountGrant grant = accountGrants.get(ticket);
                if (grant == null || grant.installed || cipher != grant.session) return;
                try {
                    if (!value.optBoolean("ok")
                            || !grant.controller.equals(value.optString("controller"))
                            || !java.security.MessageDigest.isEqual(
                                    Crypto.bytes(grant.secret),
                                    Crypto.bytes(value.optString("secret"))))
                        throw new Exception("ACCOUNT_LINK_FAILED");
                    // שומרים הרשאה רק אחרי שהשרת אימת את הכרטיס ואת המפתח ששייך לחשבון.
                    // כך בקר חשבון אינו יכול ליצור הרשאת גישה מקומית שתעקוף חסימה או איפוס.
                    if (grant.pairing) {
                        vault.enroll(
                                grant.pairVersion,
                                grant.controller,
                                grant.secret,
                                value.optString("name", "חשבון משפחתי"),
                                ticket);
                        clearPairing();
                    } else
                        vault.grantAccount(
                                grant.authorizer,
                                grant.controller,
                                grant.secret,
                                value.optString("name", "חשבון משפחתי"),
                                ticket);
                    grant.installed = true;
                    send(
                            new JSONObject()
                                    .put("t", "account-linked")
                                    .put("ticket", ticket)
                                    .put("controller", grant.controller));
                } catch (Exception error) {
                    accountGrants.remove(ticket);
                    if (grant.timeout != null) grant.timeout.cancel(false);
                    reply(grant.session, grant.request, null, accountError(error));
                }
                return;
            }
            if (type.equals("account-link-result")) {
                String ticket = value.optString("ticket");
                // תשובה חוזרת לאחר אתחול מסיימת גם אישור שאין לו כבר בקשת לקוח חיה.
                if (value.optBoolean("ok") || "INVALID_TICKET".equals(value.optString("error"))) {
                    try {
                        vault.accountLinked(ticket);
                    } catch (Exception ignored) {
                    }
                }
                AccountGrant grant = accountGrants.get(ticket);
                if (grant == null || !grant.installed) return;
                accountGrants.remove(ticket);
                if (grant.timeout != null) grant.timeout.cancel(false);
                if (cipher != grant.session) return;
                boolean ok = value.optBoolean("ok");
                reply(
                        grant.session,
                        grant.request,
                        ok ? true : null,
                        ok ? null : "ACCOUNT_LINK_FAILED");
                if (grant.pairing) endPairingSession(grant.session);
                return;
            }
            if (type.equals("pair-code")) {
                if (pairing == null
                        || pairing.registered
                        || !pairing.request.equals(value.optString("request"))) return;
                if (!pairing.valid(vault.secretFor("pair"), System.currentTimeMillis())) {
                    clearPairing();
                    return;
                }
                String prefix = value.optString("prefix");
                if (!prefix.matches("[0-9]{2}")) {
                    pairingLabel = "הקוד הקצר אינו זמין כרגע. אפשר לסרוק QR";
                    return;
                }
                pairing.prefix = prefix;
                pairing.registered = true;
                String code = pairing.code();
                pairingLabel =
                        code.substring(0, 2) + "-" + code.substring(2, 5) + "-" + code.substring(5);
                return;
            }
            if (type.equals("peer-left")) {
                endSession();
                return;
            }
            if (type.equals("peer-joined")) {
                endSession();
                authenticationTimeout =
                        worker.schedule(
                                () -> {
                                    simple("kick");
                                    endSession();
                                },
                                15,
                                TimeUnit.SECONDS);
                return;
            }
            if (type.equals("code-hello")) {
                codeAttempt = true;
                if (cipher != null
                        || srp != null
                        || authenticated
                        || value.optInt("v") != 1
                        || android.os.SystemClock.elapsedRealtime() < blockedUntil
                        || pairing == null
                        || !pairing.attempt(
                                vault.secretFor("pair"),
                                value.optString("prefix"),
                                System.currentTimeMillis())) {
                    reject();
                    return;
                }
                // ניסיון נספר לפני החישוב, גם אם הלקוח נעלם או שולח ערך פגום.
                if (!pairing.available()) {
                    simple("pair-clear");
                    pairingLabel = "נוצלו ניסיונות הקוד. סרקו QR או פתחו קוד חדש";
                }
                controller = "pair";
                pairingVersion = pairing.version;
                clientNonce = value.getString("nonce");
                Crypto.decode(clientNonce, 32);
                serverNonce = Crypto.secret();
                srp =
                        new SrpExchange(
                                "karov:" + vault.get("device"),
                                pairing.code(),
                                value.getString("A"));
                send(
                        new JSONObject()
                                .put("t", "code-challenge")
                                .put("salt", Crypto.b64(srp.salt))
                                .put("B", srp.publicValue)
                                .put("nonce", serverNonce));
                return;
            }
            if (type.equals("code-proof") && srp != null && cipher == null && !authenticated) {
                if (!authorized()) {
                    reject();
                    return;
                }
                byte[][] result = srp.finish(value.getString("proof"));
                srp = null;
                cipher =
                        new Crypto.Session(
                                Crypto.b64(result[1]),
                                vault.get("device"),
                                "pair",
                                clientNonce,
                                serverNonce);
                authenticated = true;
                failures = 0;
                if (authenticationTimeout != null) authenticationTimeout.cancel(false);
                send(new JSONObject().put("t", "code-proof").put("proof", Crypto.b64(result[0])));
                send(
                        cipher.seal(
                                new JSONObject().put("type", "ready").put("info", actions.info())));
                return;
            }
            if (type.equals("hello")) {
                if (cipher != null || srp != null || authenticated || value.optInt("v") != 1) {
                    reject();
                    return;
                }
                if (android.os.SystemClock.elapsedRealtime() < blockedUntil) {
                    simple("blocked");
                    simple("kick");
                    return;
                }
                String requested = value.getString("controller");
                if (!(requested.equals("pair") || Crypto.validId(requested))) {
                    reject();
                    return;
                }
                String secret = vault.secretFor(requested);
                if (secret.isEmpty()) {
                    reject();
                    return;
                }
                String nonce = Crypto.secret();
                cipher =
                        new Crypto.Session(
                                secret,
                                vault.get("device"),
                                requested,
                                value.getString("nonce"),
                                nonce);
                controller = requested;
                if (controller.equals("pair")) pairingVersion = secret;
                send(
                        new JSONObject()
                                .put("t", "challenge")
                                .put("nonce", nonce)
                                .put("proof", cipher.proof("agent")));
                return;
            }
            if (type.equals("auth") && cipher != null && !authenticated) {
                if (!authorized() || !cipher.verify("client", value.getString("proof"))) {
                    reject();
                    return;
                }
                authenticated = true;
                failures = 0;
                if (authenticationTimeout != null) authenticationTimeout.cancel(false);
                if (controller.equals("pair"))
                    send(
                            cipher.seal(
                                    new JSONObject()
                                            .put("type", "ready")
                                            .put("info", actions.info())));
                else {
                    actions.active(true);
                    listener.state("בן משפחה מחובר ועוזר עכשיו", true);
                    // מצרפים תמונה לתשובת האימות כדי לחסוך סבב אינטרנט שלם בכניסה למסך.
                    Crypto.Session session = cipher;
                    long current = epoch;
                    java.util.concurrent.atomic.AtomicBoolean delivered =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    worker.schedule(
                            () -> ready(session, null, delivered), 1500, TimeUnit.MILLISECONDS);
                    actions.execute(
                            "capture",
                            new JSONObject(),
                            () -> !stopped && current == epoch,
                            (frame, error) ->
                                    dispatch(() ->
                                            ready(
                                                    session,
                                                    error == null ? frame : null,
                                                    delivered)));
                }
                return;
            }
            if (!type.equals("data") || !authenticated || cipher == null || !authorized()) {
                reject();
                return;
            }
            JSONObject request = cipher.open(value);
            long id = request.getLong("id");
            String method = request.getString("method");
            JSONObject args = request.optJSONObject("args");
            if (args == null) args = new JSONObject();
            if (id <= 0 || id > 9007199254740991L || ++inFlight > 16) {
                reject();
                return;
            }
            final Crypto.Session session = cipher;
            final long current = epoch;
            if (controller.equals("pair")) {
                if (!method.equals("enroll")) {
                    reply(session, id, null, "PAIR_ONLY");
                    return;
                }
                String accountTicket = args.optString("accountTicket");
                if (!accountTicket.isEmpty()) {
                    try {
                        checkAccount(accountTicket, args, session, id, true);
                    } catch (Exception error) {
                        reply(session, id, null, accountError(error));
                    }
                } else {
                    vault.enroll(
                            pairingVersion,
                            args.getString("controller"),
                            args.getString("secret"),
                            args.optString("name", "מכשיר משפחתי"));
                    clearPairing();
                    reply(session, id, true, null);
                    endPairingSession(session);
                }
                return;
            }
            if (method.equals("grantAccount")) {
                try {
                    checkAccount(args.getString("ticket"), args, session, id, false);
                } catch (Exception error) {
                    reply(session, id, null, accountError(error));
                }
                return;
            }
            if (method.equals("controllers")) {
                reply(session, id, vault.controllers(), null);
                return;
            }
            if (method.equals("revoke")) {
                String target = args.getString("controller");
                if (!Crypto.validId(target)) {
                    reply(session, id, null, "BAD_ID");
                    return;
                }
                vault.revoke(target);
                reply(session, id, true, null);
                if (target.equals(controller)) {
                    // ה-ACK והניתוק נשמרים בסדר של אותו socket. ביטול ההרשאה נכנס לתוקף מיד.
                    simple("kick");
                    endSession();
                }
                return;
            }
            final JSONObject parameters = args;
            actions.execute(
                    method,
                    parameters,
                    () -> !stopped && epoch == current,
                    (result, error) ->
                            dispatch(() -> {
                                if (cipher == session && epoch == current)
                                    reply(session, id, result, error);
                            }));
        } catch (Exception error) {
            reject();
        }
    }

    private void endPairingSession(Crypto.Session session) {
        worker.schedule(
                () -> {
                    if (cipher == session) {
                        simple("kick");
                        endSession();
                    }
                },
                1000,
                TimeUnit.MILLISECONDS);
    }

    private static String accountError(Exception error) {
        return java.util.Set.of(
                                "CONTROLLER_LIMIT",
                                "CONTROLLER_CONFLICT",
                                "DENIED",
                                "STORAGE_FAILED",
                                "ACCOUNT_LINK_BUSY",
                                "ACCOUNT_LINK_FAILED",
                                "PAIR_EXPIRED")
                        .contains(String.valueOf(error.getMessage()))
                ? error.getMessage()
                : "BAD_ACCOUNT_LINK";
    }

    private void replayAccountLinks() {
        if (socket == null || stopped) return;
        try {
            org.json.JSONArray pending = vault.accountLinks();
            for (int i = 0; i < pending.length(); i++)
                send(pending.getJSONObject(i).put("t", "account-linked"));
        } catch (Exception ignored) {
            // כשל זמני בכתיבה או בשליחה יטופל במחזור הבא; לא מוחקים אישור שלא התקבל.
        }
    }

    private void checkAccount(
            String ticket, JSONObject args, Crypto.Session session, long request, boolean pairing)
            throws Exception {
        Crypto.decode(ticket, 32);
        String target = args.getString("controller"), secret = args.getString("secret");
        if (!Crypto.validId(target)) throw new Exception("BAD_ID");
        Crypto.decode(secret, 32);
        if (accountGrants.containsKey(ticket)) throw new Exception("ACCOUNT_LINK_BUSY");
        AccountGrant grant =
                new AccountGrant(
                        session, request, pairing, target, secret, controller, pairingVersion);
        accountGrants.put(ticket, grant);
        // אישור הקישור מגיע רק מהשרת דרך חיבור הסטרימר. אובדן תשובה אינו מוחק הרשאה שאולי כבר
        // סונכרנה.
        grant.timeout =
                worker.schedule(
                        () -> {
                            if (accountGrants.remove(ticket) != grant || cipher != session) return;
                            reply(session, request, null, "REQUEST_TIMEOUT");
                            if (pairing) endPairingSession(session);
                        },
                        8,
                        TimeUnit.SECONDS);
        send(
                new JSONObject()
                        .put("t", "account-link-check")
                        .put("ticket", ticket)
                        .put("controller", target));
    }

    private void ready(
            Crypto.Session session,
            Object frame,
            java.util.concurrent.atomic.AtomicBoolean delivered) {
        if (cipher != session || delivered.getAndSet(true)) return;
        try {
            JSONObject info = actions.info();
            if (frame != null) info.put("firstFrame", frame);
            send(session.seal(new JSONObject().put("type", "ready").put("info", info)));
        } catch (Exception error) {
            simple("kick");
            endSession();
        }
    }

    private void reply(Crypto.Session session, long id, Object result, String error) {
        inFlight = Math.max(0, inFlight - 1);
        if (cipher != session) return;
        try {
            JSONObject response = new JSONObject().put("id", id).put("ok", error == null);
            if (error == null) response.put("value", result);
            else response.put("error", error);
            send(session.seal(response));
        } catch (Exception ignored) {
            simple("kick");
            endSession();
        }
    }

    public void disconnectController() {
        dispatch(() -> {
            try {
                if (pairing != null
                        && !pairing.valid(vault.secretFor("pair"), System.currentTimeMillis()))
                    clearPairing();
            } catch (Exception ignored) {
                clearPairing();
            }
            simple("kick");
            endSession();
        });
    }

    public void stop() {
        dispatch(() -> {
            stopped = true;
            if (reconnect != null) reconnect.cancel(false);
            clearPairing();
            endSession();
            actions.close();
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            worker.shutdown();
        });
    }
}
