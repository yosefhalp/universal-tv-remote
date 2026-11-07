package org.yosef.karov.phone;

import okhttp3.*;

import org.json.JSONObject;
import org.yosef.karov.Crypto;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

// כל שינוי בסשן ובמונים רץ בתור יחיד. אין שידור חוזר של פקודה אחרי ניתוק.
final class RemoteClient {
    private final String device, controller, secret, code, accountToken;
    private final Consumer<String> onClosed;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final OkHttpClient http =
            new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(15, TimeUnit.SECONDS)
                    .build();
    private final CompletableFuture<JSONObject> connected = new CompletableFuture<>();
    private final Map<Long, CompletableFuture<Object>> pending = new HashMap<>();
    private WebSocket socket;
    private Crypto.Session cipher;
    private PhoneSrp srp;
    private String clientNonce, serverNonce;
    private boolean challengeSeen, ready;
    private volatile boolean closed;
    private long nextId = 1;
    private ScheduledFuture<?> deadline;

    RemoteClient(
            String device,
            String controller,
            String secret,
            String code,
            Consumer<String> onClosed) {
        this(device, controller, secret, code, null, onClosed);
    }

    RemoteClient(
            String device,
            String controller,
            String secret,
            String code,
            String accountToken,
            Consumer<String> onClosed) {
        this.device = device;
        this.controller = controller;
        this.secret = secret;
        this.code = code;
        this.accountToken = accountToken;
        this.onClosed = onClosed;
    }

    CompletableFuture<JSONObject> connect() {
        dispatch(() -> {
            try {
                if (!Crypto.validId(device)
                        || !(controller.equals("pair") || Crypto.validId(controller)))
                    throw new Exception("BAD_ID");
                clientNonce = Crypto.secret();
                if (code != null) srp = new PhoneSrp(device, code);
                deadline = worker.schedule(() -> finish("CONNECT_TIMEOUT"), 12, TimeUnit.SECONDS);
                socket =
                        http.newWebSocket(
                                new Request.Builder()
                                        .url(
                                                Pairing.SERVER.replaceFirst("^http", "ws")
                                                        + "/v1/client/"
                                                        + device)
                                        .build(),
                                new WebSocketListener() {
                                    @Override
                                    public void onOpen(WebSocket ws, Response response) {
                                        dispatch(() -> {
                                            try {
                                                JSONObject hello =
                                                        new JSONObject()
                                                                .put("v", 1)
                                                                .put("nonce", clientNonce);
                                                if (srp != null)
                                                    hello.put("t", "code-hello")
                                                            .put("prefix", code.substring(0, 2))
                                                            .put("A", srp.publicValue);
                                                else
                                                    hello.put("t", "hello")
                                                            .put("controller", controller);
                                                if (accountToken != null)
                                                    hello.put("accountToken", accountToken);
                                                send(hello);
                                            } catch (Exception error) {
                                                finish("BAD_HANDSHAKE");
                                            }
                                        });
                                    }

                                    @Override
                                    public void onMessage(WebSocket ws, String text) {
                                        dispatch(() -> receive(text));
                                    }

                                    @Override
                                    public void onMessage(WebSocket ws, okio.ByteString bytes) {
                                        dispatch(() -> finish("BAD_MESSAGE"));
                                    }

                                    @Override
                                    public void onFailure(
                                            WebSocket ws, Throwable error, Response response) {
                                        dispatch(() -> finish("NETWORK"));
                                    }

                                    @Override
                                    public void onClosed(WebSocket ws, int code, String reason) {
                                        dispatch(() -> finish("DISCONNECTED"));
                                    }

                                    @Override
                                    public void onClosing(WebSocket ws, int code, String reason) {
                                        ws.close(code, null);
                                    }
                                });
            } catch (Exception error) {
                finish(error.getMessage());
            }
        });
        return connected;
    }

    private void dispatch(Runnable action) {
        if (!closed)
            try {
                worker.execute(() -> {
                    if (!closed) action.run();
                });
            } catch (RejectedExecutionException ignored) {
            }
    }

    private void send(JSONObject value) throws Exception {
        if (closed || socket == null || !socket.send(value.toString()))
            throw new Exception("DISCONNECTED");
    }

    private void receive(String text) {
        try {
            if (text.length() > 1500000) throw new Exception("BAD_MESSAGE");
            JSONObject value = new JSONObject(text);
            String type = value.optString("t");
            if (type.equals("account-denied")) throw new Exception("ACCOUNT_DENIED");
            if (type.equals("pong")) return;
            if (java.util.Arrays.asList("offline", "busy", "denied", "expired", "blocked")
                    .contains(type))
                throw new Exception(
                        code != null && type.equals("denied")
                                ? "CODE_DENIED"
                                : type.toUpperCase(java.util.Locale.ROOT));
            if (type.equals("challenge") && code == null && !challengeSeen && !ready) {
                challengeSeen = true;
                cipher =
                        new Crypto.Session(
                                secret,
                                device,
                                controller,
                                clientNonce,
                                value.getString("nonce"),
                                true);
                if (!cipher.verify("agent", value.getString("proof")))
                    throw new Exception("BAD_IDENTITY");
                send(new JSONObject().put("t", "auth").put("proof", cipher.proof("client")));
                return;
            }
            if (type.equals("code-challenge") && srp != null && !challengeSeen && !ready) {
                challengeSeen = true;
                serverNonce = value.getString("nonce");
                Crypto.decode(serverNonce, 32);
                send(
                        new JSONObject()
                                .put("t", "code-proof")
                                .put(
                                        "proof",
                                        srp.challenge(
                                                value.getString("salt"), value.getString("B"))));
                return;
            }
            if (type.equals("code-proof")
                    && srp != null
                    && challengeSeen
                    && cipher == null
                    && !ready) {
                cipher =
                        new Crypto.Session(
                                srp.finish(value.getString("proof")),
                                device,
                                controller,
                                clientNonce,
                                serverNonce,
                                true);
                srp = null;
                return;
            }
            if (!type.equals("data") || cipher == null) throw new Exception("BAD_MESSAGE");
            JSONObject payload = cipher.open(value);
            if (!ready) {
                if (!payload.optString("type").equals("ready"))
                    throw new Exception("BAD_HANDSHAKE");
                ready = true;
                deadline.cancel(false);
                connected.complete(payload.getJSONObject("info"));
                return;
            }
            CompletableFuture<Object> reply = pending.remove(payload.getLong("id"));
            if (reply != null) {
                if (payload.optBoolean("ok")) reply.complete(payload.opt("value"));
                else
                    reply.completeExceptionally(
                            new Exception(payload.optString("error", "FAILED")));
            }
        } catch (Exception error) {
            finish(error.getMessage());
        }
    }

    CompletableFuture<Object> call(String method, JSONObject args, int timeout) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new Exception("DISCONNECTED"));
            return future;
        }
        try {
            worker.execute(() -> {
                if (closed || !ready || pending.size() >= 16) {
                    future.completeExceptionally(
                            new Exception(ready ? "TOO_MANY_REQUESTS" : "DISCONNECTED"));
                    return;
                }
                long id = nextId++;
                try {
                    pending.put(id, future);
                    send(
                            cipher.seal(
                                    new JSONObject()
                                            .put("id", id)
                                            .put("method", method)
                                            .put("args", args)));
                    ScheduledFuture<?> timer =
                            worker.schedule(
                                    () -> {
                                        if (pending.remove(id) != null) {
                                            future.completeExceptionally(
                                                    new Exception("REQUEST_TIMEOUT"));
                                            finish("REQUEST_TIMEOUT");
                                        }
                                    },
                                    timeout,
                                    TimeUnit.MILLISECONDS);
                    future.whenComplete((result, error) -> timer.cancel(false));
                } catch (Exception error) {
                    finish(error.getMessage());
                }
            });
        } catch (RejectedExecutionException stopped) {
            future.completeExceptionally(new Exception("DISCONNECTED"));
        }
        return future;
    }

    private void finish(String reason) {
        if (closed) return;
        closed = true;
        ready = false;
        cipher = null;
        srp = null;
        if (deadline != null) deadline.cancel(false);
        if (socket != null) socket.cancel();
        Exception failure = new Exception(reason == null ? "NETWORK" : reason);
        connected.completeExceptionally(failure);
        for (CompletableFuture<Object> future : pending.values())
            future.completeExceptionally(failure);
        pending.clear();
        // משימות שכבר נכנסו לתור משלימות את העתיד שלהן כשגיאה; אין להשאיר קריאה ממתינה.
        worker.shutdown();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        onClosed.accept(failure.getMessage());
    }

    void close() {
        dispatch(() -> finish("CLOSED"));
    }
}
