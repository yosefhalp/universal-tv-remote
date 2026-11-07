package org.yosef.karov;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import javax.security.auth.x500.X500Principal;

// חלופה לגרסאות ישנות: חיבור ADB לכתובת המקומית בלבד, לאחר אישור מפורש של מפתח האפליקציה בטלוויזיה.
public final class LocalAdb {
    private static LocalAdb instance;

    public static synchronized LocalAdb get(Context context) throws Exception {
        if (instance == null)
            instance = new LocalAdb(context.getApplicationContext(), Vault.open(context));
        return instance;
    }

    private final Vault vault;
    private final Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile Socket socket;
    private volatile boolean ready, running;
    private volatile long generation;
    private volatile String status = "שליטה נוספת אינה מופעלת";
    private int retries, streamId;
    private ScheduledFuture<?> retry, heartbeat;
    private static final String ALIAS = "karov-adb-identity";
    static final int CNXN = 0x4e584e43,
            AUTH = 0x48545541,
            OPEN = 0x4e45504f,
            OKAY = 0x59414b4f,
            WRTE = 0x45545257,
            CLSE = 0x45534c43;

    private LocalAdb(Context context, Vault vault) {
        this.context = context;
        this.vault = vault;
    }

    public boolean ready() {
        return ready;
    }

    public String status() {
        return status;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        if ("true".equals(vault.get("adbEnabled"))) worker.execute(this::connect);
    }

    public synchronized void stop() {
        running = false;
        generation++;
        ready = false;
        if (retry != null) retry.cancel(false);
        if (heartbeat != null) heartbeat.cancel(false);
        close(socket);
        socket = null;
    }

    public void enable(boolean enabled) throws Exception {
        vault.put("adbEnabled", String.valueOf(enabled));
        stop();
        if (enabled) start();
        else status = "שליטה נוספת אינה מופעלת";
    }

    private static void close(Socket socket) {
        if (socket != null)
            try {
                socket.close();
            } catch (IOException ignored) {
            }
    }

    private void connect() {
        if (!running || !"true".equals(vault.get("adbEnabled")) || socket != null) return;
        final long current = generation;
        Socket active = new Socket();
        socket = active;
        try {
            status = "מחבר שליטה נוספת…";
            active.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            active.setSoTimeout(120000);
            KeyStore store = identity();
            PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
            RSAPublicKey publicKey = (RSAPublicKey) store.getCertificate(ALIAS).getPublicKey();
            write(active, CNXN, 0x01000000, 262144, Crypto.bytes("host::features=shell_v2\0"));
            int attempts = 0;
            while (running && current == generation) {
                Packet packet = read(active);
                if (packet.command == CNXN) {
                    ready = true;
                    retries = 0;
                    status = "שליטה נוספת מחוברת";
                    active.setSoTimeout(10000);
                    watchConnection(active, current);
                    return;
                }
                if (packet.command != AUTH
                        || packet.arg0 != 1
                        || packet.data.length != 20
                        || attempts > 2) throw new IOException("ADB_AUTH");
                if (attempts++ == 0) {
                    // ADB מוסר גיבוב SHA-1 מוכן; מוסיפים DigestInfo וחותמים בלי לגבב אותו פעם
                    // שנייה.
                    byte[] prefix =
                            new byte[] {
                                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
                                0x05, 0x00, 0x04, 0x14
                            };
                    Signature signature = Signature.getInstance("NONEwithRSA");
                    signature.initSign(key);
                    signature.update(prefix);
                    signature.update(packet.data);
                    write(active, AUTH, 2, 0, signature.sign());
                } else {
                    status = "אשרו בטלוויזיה את בקשת ניפוי השגיאות וסמנו אישור קבוע";
                    String encoded =
                            Base64.getEncoder().encodeToString(publicKey(publicKey))
                                    + " karov@family\0";
                    write(active, AUTH, 3, 0, Crypto.bytes(encoded));
                }
            }
        } catch (Exception error) {
            if (current == generation) {
                status = "אין חיבור לניפוי שגיאות ברשת. בדקו את הגדרות הסטרימר";
                logFailure(error);
            }
        } finally {
            if (!ready || current != generation) {
                close(active);
                if (socket == active) socket = null;
                if (running && current == generation)
                    retry =
                            worker.schedule(
                                    this::connect,
                                    Math.min(30000, 2000L << Math.min(retries++, 4)),
                                    TimeUnit.MILLISECONDS);
            }
        }
    }

    private void watchConnection(Socket active, long current) {
        if (heartbeat != null) heartbeat.cancel(false);
        // בדיקה שקטה מגלה אובדן חיבור גם כשהמשפחה אינה לוחצת על השלט; אין בה שינוי במסך.
        heartbeat =
                worker.schedule(
                        () -> {
                            if (!running || !ready || socket != active || generation != current)
                                return;
                            try {
                                execute(active, "echo karov", 1024);
                                watchConnection(active, current);
                            } catch (Exception error) {
                                logFailure(error);
                                disconnected(active, current);
                            }
                        },
                        15,
                        TimeUnit.SECONDS);
    }

    private void disconnected(Socket active, long current) {
        close(active);
        if (current != generation || socket != active) return;
        ready = false;
        socket = null;
        status = "החיבור המקומי נותק; מנסה שוב";
        if (heartbeat != null) heartbeat.cancel(false);
        if (running) retry = worker.schedule(this::connect, 2000, TimeUnit.MILLISECONDS);
    }

    private void command(
            String command,
            int limit,
            BooleanSupplier sessionCurrent,
            DeviceActions.Result callback,
            boolean screenshot) {
        final Socket active = socket;
        final long current = generation;
        worker.execute(() -> {
            // פקודה שהמתינה בתור אינה מבוצעת אם בן המשפחה כבר התנתק; אין שידור חוזר אוטומטי.
            if (!sessionCurrent.getAsBoolean()) {
                callback.done(null, "DISCONNECTED");
                return;
            }
            if (!running || !ready || active == null || active != socket || current != generation) {
                callback.done(null, "ADB_UNAVAILABLE");
                return;
            }
            try {
                byte[] result = execute(active, command, limit);
                if (current != generation) {
                    callback.done(null, "DISCONNECTED");
                    return;
                }
                if (screenshot) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(result, 0, result.length);
                    if (bitmap == null) throw new IOException("CAPTURE_FAILED");
                    try {
                        callback.done(TvAccessibilityService.frame(bitmap, context), null);
                    } finally {
                        bitmap.recycle();
                    }
                } else callback.done(true, null);
            } catch (Exception error) {
                logFailure(error);
                disconnected(active, current);
                callback.done(null, "ADB_UNAVAILABLE");
            }
        });
    }

    public void key(String key, BooleanSupplier sessionCurrent, DeviceActions.Result callback) {
        int code =
                switch (key) {
                    case "HOME" -> 3;
                    case "BACK" -> 4;
                    case "UP" -> 19;
                    case "DOWN" -> 20;
                    case "LEFT" -> 21;
                    case "RIGHT" -> 22;
                    case "OK" -> 23;
                    case "VOLUME_UP" -> 24;
                    case "VOLUME_DOWN" -> 25;
                    case "MUTE" -> 164;
                    case "PLAY_PAUSE" -> 85;
                    case "REWIND" -> 89;
                    case "FAST_FORWARD" -> 90;
                    default -> -1;
                };
        if (code < 0) {
            callback.done(null, "UNSUPPORTED");
            return;
        }
        command("input keyevent " + code, 16384, sessionCurrent, callback, false);
    }

    public void capture(BooleanSupplier sessionCurrent, DeviceActions.Result callback) {
        command("screencap -p", 12582912, sessionCurrent, callback, true);
    }

    public void pointer(PointerSpec spec, BooleanSupplier current, DeviceActions.Result callback) {
        float[][] path = spec.path();
        int x = Math.round(path[0][0] * (spec.width - 1));
        int y = Math.round(path[0][1] * (spec.height - 1));
        String input = "input touchscreen ";
        if (spec.kind.equals("tap") && spec.duration < 500) input += "tap " + x + " " + y;
        else {
            float[] end = path[path.length - 1];
            input +=
                    "swipe "
                            + x
                            + " "
                            + y
                            + " "
                            + Math.round(end[0] * (spec.width - 1))
                            + " "
                            + Math.round(end[1] * (spec.height - 1))
                            + " "
                            + spec.duration;
        }
        // רק מספרים שנבדקו ופקודה קבועה; חלופת ADB מבצעת גרירה ישרה בין נקודות הקצה.
        command(input, 16384, current, callback, false);
    }

    private byte[] execute(Socket active, String command, int limit) throws Exception {
        return execute(active, command, limit, ++streamId);
    }

    static byte[] execute(Socket active, String command, int limit, int local) throws Exception {
        StreamState stream = new StreamState(local);
        write(active, OPEN, local, 0, Crypto.bytes("exec:" + command + "\0"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int count = 0; count < 8192; count++) {
            Packet packet = read(active);
            stream.accept(packet);
            if (packet.command == WRTE) {
                if (output.size() + packet.data.length > limit) throw new IOException("ADB_LIMIT");
                output.write(packet.data);
                write(active, OKAY, local, stream.peer, new byte[0]);
            } else if (packet.command == CLSE) {
                // הסוכן כבר סגר את הזרם. אין תשובת סגירה נוספת שיכולה להיכשל אחרי ביצוע הפקודה.
                return output.toByteArray();
            } else if (packet.command != OKAY) throw new IOException("ADB_MESSAGE");
        }
        throw new IOException("ADB_LIMIT");
    }

    static final class StreamState {
        private final int local;
        private boolean opened;
        int peer;

        StreamState(int local) {
            this.local = local;
        }

        void accept(Packet packet) throws IOException {
            if (packet.arg1 != local) throw new IOException("ADB_STREAM");
            if (!opened) {
                // סגירה לפני אישור הפתיחה היא סירוב לפתוח, ולא הצלחה ללא פלט.
                if (packet.command == CLSE) throw new IOException("ADB_OPEN");
                if (packet.command != OKAY) throw new IOException("ADB_MESSAGE");
                if (packet.arg0 == 0) throw new IOException("ADB_STREAM");
                peer = packet.arg0;
                opened = true;
                return;
            }
            // גרסאות ADB ישנות עשויות לסגור עם מזהה שולח אפס, רק אחרי שאושר אותו זרם באותו חיבור.
            if (packet.command == CLSE && packet.arg0 == 0) return;
            if (packet.arg0 != peer) throw new IOException("ADB_STREAM");
        }
    }

    static String failureDiagnostic(Exception error) {
        String message = error.getMessage();
        boolean known =
                message != null
                        && Set.of(
                                        "ADB_OPEN",
                                        "ADB_AUTH",
                                        "ADB_STREAM",
                                        "ADB_MESSAGE",
                                        "ADB_LIMIT",
                                        "ADB_HEADER",
                                        "ADB_CHECKSUM",
                                        "ADB_UNAVAILABLE",
                                        "CAPTURE_FAILED",
                                        "UPDATE_INSTALL",
                                        "UPDATE_SIZE")
                                .contains(message);
        return error.getClass().getSimpleName() + ":" + (known ? message : "IO_FAILURE");
    }

    private static void logFailure(Exception error) {
        // נשמרים רק סוג החריגה וקוד קבוע. אין רישום פקודה, תוכן זרם, מפתח או הודעת מערכת חופשית.
        if (BuildConfig.DEBUG)
            android.util.Log.w("KarovAdb", "זרם ADB לא הושלם: " + failureDiagnostic(error));
    }

    // מסלול מוגבל לעדכון קרוב עצמה בלבד. אין קבלת פקודת shell, כתובת או קובץ מהשלט הרחוק.
    public void installUpdate(File apk) throws Exception {
        if (!apk.getCanonicalFile()
                .equals(new File(context.getFilesDir(), "karov-update.apk").getCanonicalFile()))
            throw new IOException("UPDATE_PATH");
        org.yosef.karov.updates.UpdateSpec spec =
                new org.yosef.karov.updates.UpdateSpec(
                        new org.json.JSONObject(
                                context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE)
                                        .getString("spec", "{}")),
                        context.getPackageName());
        org.yosef.karov.updates.UpdateEngine.verify(context, apk, spec);
        worker.submit(
                        () -> {
                            Socket active = socket;
                            long current = generation;
                            if (!running || !ready || active == null)
                                throw new IOException("ADB_UNAVAILABLE");
                            try (InputStream input = new FileInputStream(apk)) {
                                active.setSoTimeout(120000);
                                int local = ++streamId;
                                StreamState stream = new StreamState(local);
                                long sent = 0;
                                boolean awaitingAck = false;
                                write(
                                        active,
                                        OPEN,
                                        local,
                                        0,
                                        Crypto.bytes(
                                                "exec:cmd package install -r -S "
                                                        + apk.length()
                                                        + "\0"));
                                ByteArrayOutputStream output = new ByteArrayOutputStream();
                                for (int count = 0; count < 20000; count++) {
                                    Packet packet = read(active);
                                    stream.accept(packet);
                                    if (packet.command == OKAY) {
                                        awaitingAck = false;
                                    } else if (packet.command == WRTE) {
                                        if (output.size() + packet.data.length > 16384)
                                            throw new IOException("ADB_LIMIT");
                                        output.write(packet.data);
                                        write(active, OKAY, local, stream.peer, new byte[0]);
                                    } else if (packet.command == CLSE) {
                                        if (sent != apk.length()
                                                || !output.toString("UTF-8")
                                                        .trim()
                                                        .equals("Success"))
                                            throw new IOException("UPDATE_INSTALL");
                                        return null;
                                    } else throw new IOException("ADB_MESSAGE");
                                    if (!awaitingAck && sent < apk.length()) {
                                        byte[] buffer =
                                                new byte[(int) Math.min(4096, apk.length() - sent)];
                                        int read = input.read(buffer);
                                        if (read <= 0) throw new IOException("UPDATE_SIZE");
                                        write(
                                                active,
                                                WRTE,
                                                local,
                                                stream.peer,
                                                read == buffer.length
                                                        ? buffer
                                                        : Arrays.copyOf(buffer, read));
                                        sent += read;
                                        awaitingAck = true;
                                    }
                                }
                                throw new IOException("ADB_LIMIT");
                            } catch (Exception error) {
                                logFailure(error);
                                disconnected(active, current);
                                throw error;
                            } finally {
                                if (!active.isClosed()) active.setSoTimeout(10000);
                            }
                        })
                .get(150, TimeUnit.SECONDS);
    }

    private static KeyStore identity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(
                    new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setKeySize(2048)
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                            .setCertificateSubject(new X500Principal("CN=karov-adb"))
                            .setCertificateSerialNumber(BigInteger.ONE)
                            .setCertificateNotBefore(new Date(1577836800000L))
                            .setCertificateNotAfter(new Date(2208988800000L))
                            .build());
            generator.generateKeyPair();
        }
        return store;
    }

    static byte[] publicKey(RSAPublicKey key) {
        BigInteger modulus = key.getModulus(), word = BigInteger.ONE.shiftLeft(32);
        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64).putInt(modulus.modInverse(word).negate().intValue());
        buffer.put(little(modulus, 256))
                .put(little(BigInteger.ONE.shiftLeft(4096).mod(modulus), 256))
                .putInt(key.getPublicExponent().intValue());
        return buffer.array();
    }

    private static byte[] little(BigInteger value, int size) {
        byte[] big = value.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    static final class Packet {
        final int command, arg0, arg1;
        final byte[] data;

        Packet(int c, int a, int b, byte[] d) {
            command = c;
            arg0 = a;
            arg1 = b;
            data = d;
        }
    }

    private static byte[] exact(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        int n = 0;
        while (n < length) {
            int got = in.read(bytes, n, length - n);
            if (got < 0) throw new EOFException();
            n += got;
        }
        return bytes;
    }

    static Packet read(InputStream in) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(exact(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt(),
                a = header.getInt(),
                b = header.getInt(),
                length = header.getInt(),
                checksum = header.getInt(),
                magic = header.getInt();
        if (magic != (command ^ -1) || length < 0 || length > 1048576)
            throw new IOException("ADB_HEADER");
        byte[] body = exact(in, length);
        int sum = 0;
        for (byte item : body) sum += item & 255;
        if (sum != checksum) throw new IOException("ADB_CHECKSUM");
        return new Packet(command, a, b, body);
    }

    private static Packet read(Socket socket) throws IOException {
        return read(socket.getInputStream());
    }

    static byte[] packet(int command, int a, int b, byte[] data) {
        int sum = 0;
        for (byte item : data) sum += item & 255;
        return ByteBuffer.allocate(24 + data.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command)
                .putInt(a)
                .putInt(b)
                .putInt(data.length)
                .putInt(sum)
                .putInt(command ^ -1)
                .put(data)
                .array();
    }

    private static void write(Socket socket, int command, int a, int b, byte[] data)
            throws IOException {
        socket.getOutputStream().write(packet(command, a, b, data));
        socket.getOutputStream().flush();
    }
}
