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
    private volatile long epoch = 0;
    private long blockedUntil = 0;
    private int failures = 0, retry = 0, inFlight = 0;
    private ScheduledFuture<?> reconnect, authenticationTimeout, pairingExpiry;

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
        if (stopped || !vault.enabled() || vault.get("server").isEmpty() || socket != null) return;
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
                        .header("X-Karov-Enrollment", vault.get("enrollment"))
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
                                dispatch(() -> lost(ws));
                            }

                            @Override
                            public void onClosed(WebSocket ws, int code, String reason) {
                                dispatch(() -> lost(ws));
                            }

                            @Override
                            public void onClosing(WebSocket ws, int code, String reason) {
                                ws.close(code, null);
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
                vault.enroll(
                        pairingVersion,
                        args.getString("controller"),
                        args.getString("secret"),
                        args.optString("name", "מכשיר משפחתי"));
                reply(session, id, true, null);
                clearPairing();
                worker.schedule(
                        () -> {
                            if (cipher == session) {
                                simple("kick");
                                endSession();
                            }
                        },
                        1000,
                        TimeUnit.MILLISECONDS);
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
                if (target.equals(controller))
                    worker.schedule(
                            () -> {
                                if (cipher == session) {
                                    simple("kick");
                                    endSession();
                                }
                            },
                            300,
                            TimeUnit.MILLISECONDS);
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
