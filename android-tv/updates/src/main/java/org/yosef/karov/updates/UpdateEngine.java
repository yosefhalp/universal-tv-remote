package org.yosef.karov.updates;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.*;
import android.content.pm.*;
import android.os.Build;

import okhttp3.*;

import org.json.JSONObject;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateEngine {
    public static final String ORIGIN = "https://tv.yosefapk.top";
    private static final long PERIOD = 6 * 60 * 60 * 1000L;
    private static final int JOB = 71201;
    private static UpdateEngine instance;

    public static synchronized UpdateEngine get(Context context) {
        if (instance == null) instance = new UpdateEngine(context.getApplicationContext());
        return instance;
    }

    public interface LocalInstaller {
        boolean available();

        void install(File apk) throws Exception;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean();
    private final OkHttpClient http =
            new OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.MINUTES)
                    .build();
    private final PackageInfo installed;
    private volatile Call download;
    private volatile boolean busy;
    private volatile LocalInstaller localInstaller;
    private volatile Intent confirmation;

    private UpdateEngine(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE);
        try {
            installed =
                    context.getPackageManager()
                            .getPackageInfo(
                                    context.getPackageName(),
                                    PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        long expected = prefs.getLong("expected", 0);
        if (expected > 0 && installed.getLongVersionCode() >= expected) {
            prefs.edit()
                    .remove("session")
                    .remove("nonce")
                    .remove("expected")
                    .remove("spec")
                    .putString("state", "current")
                    .putString("message", "העדכון הושלם. הזיווגים נשמרו.")
                    .commit();
            new File(context.getFilesDir(), "karov-update.apk").delete();
        } else if (Set.of("checking", "downloading", "installing")
                .contains(prefs.getString("state", ""))) {
            state("ready", "הפעולה נעצרה. אפשר לבדוק ולהמשיך את העדכון.");
        }
    }

    public void localInstaller(LocalInstaller installer) {
        localInstaller = installer;
    }

    public boolean television() {
        return UpdatePolicy.television(context.getPackageName());
    }

    public void busy(boolean value) {
        boolean wasBusy = busy;
        busy = value;
        if (wasBusy && !value && "deferred".equals(prefs.getString("state", ""))) background(null);
    }

    public boolean automatic() {
        return prefs.getBoolean("automatic", true);
    }

    public void automatic(boolean value) {
        prefs.edit().putBoolean("automatic", value).apply();
    }

    public boolean permission() {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public Intent confirmation() {
        return confirmation;
    }

    public synchronized Intent takeConfirmation() {
        Intent pending = confirmation;
        confirmation = null;
        return pending;
    }

    public void installationScreenUnavailable() {
        // כשל פתיחת המסך אינו כשל באימות הקובץ או בהורדה, ולכן שומרים את מצב העבודה הקיים.
        if (working.get() || "installing".equals(prefs.getString("state", ""))) return;
        prefs.edit()
                .putString(
                        "message",
                        "פתחו בטלוויזיה את קרוב, עדכוני קרוב, והתקנת העדכון. Android לא אפשר לפתוח"
                            + " את המסך מרחוק.")
                .commit();
    }

    public void cancelDownload() {
        Call call = download;
        if (call != null) call.cancel();
    }

    public JSONObject info() {
        try {
            return new JSONObject()
                    .put("version", installed.versionName)
                    .put("versionCode", installed.getLongVersionCode())
                    .put("automatic", automatic())
                    .put("permission", permission())
                    .put("working", working.get())
                    .put("state", prefs.getString("state", "idle"))
                    .put(
                            "message",
                            prefs.getString("message", "עדכונים נבדקים אוטומטית מדי כמה שעות"))
                    .put("latest", prefs.getString("latest", ""))
                    .put("notes", prefs.getString("notes", ""))
                    .put("checkedAt", prefs.getLong("checkedAt", 0))
                    .put(
                            "localInstall",
                            UpdatePolicy.localInstall(television())
                                    && localInstaller != null
                                    && localInstaller.available())
                    .put("installMode", television() ? "system-confirmation" : "automatic");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public void schedule() {
        if (context.getPackageName().endsWith(".debug")) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB) == null)
            scheduler.schedule(
                    new JobInfo.Builder(JOB, new ComponentName(context, UpdateJob.class))
                            .setPersisted(true)
                            .setPeriodic(PERIOD)
                            .setRequiredNetworkType(
                                    !television()
                                            ? JobInfo.NETWORK_TYPE_UNMETERED
                                            : JobInfo.NETWORK_TYPE_ANY)
                            .build());
    }

    public void background(Runnable done) {
        String current = prefs.getString("state", "");
        boolean due = System.currentTimeMillis() - prefs.getLong("attemptAt", 0) >= PERIOD;
        if (!automatic()
                || context.getPackageName().endsWith(".debug")
                || !due && !current.equals("deferred")
                || current.equals("confirmation")
                || current.equals("installing")) {
            if (done != null) done.run();
            return;
        }
        // בדיקה המופעלת בפתיחת האפליקציה מכבדת גם היא את מגבלת Wi-Fi של הטלפון.
        if (!television()
                && context.getSystemService(android.net.ConnectivityManager.class)
                        .isActiveNetworkMetered()) {
            if (done != null) done.run();
            return;
        }
        request(UpdatePolicy.automaticInstall(television(), automatic()), true, done);
    }

    public boolean request(boolean install, boolean background, Runnable done) {
        boolean installRequested = UpdatePolicy.mayInstall(television(), install, background);
        if (!working.compareAndSet(false, true)) {
            if (done != null) done.run();
            return false;
        }
        worker.execute(() -> {
            try {
                if ("installing".equals(prefs.getString("state", ""))
                        && System.currentTimeMillis() - prefs.getLong("installAt", 0) < 180000)
                    return;
                prefs.edit().putLong("attemptAt", System.currentTimeMillis()).commit();
                state("checking", "בודקים אם קיימת גרסה חדשה…");
                UpdateSpec spec = fetchSpec();
                prefs.edit()
                        .putLong("checkedAt", System.currentTimeMillis())
                        .putString("latest", spec.versionName)
                        .putString("notes", spec.notes)
                        .putString("spec", spec.json().toString())
                        .commit();
                if (spec.versionCode <= installed.getLongVersionCode()) {
                    state("current", "מותקנת הגרסה העדכנית");
                    return;
                }
                if (spec.minSdk > Build.VERSION.SDK_INT) {
                    state("unsupported", "הגרסה החדשה דורשת Android חדש יותר");
                    return;
                }
                File apk = new File(context.getFilesDir(), "karov-update.apk");
                boolean verified = false;
                if (apk.isFile())
                    try {
                        verify(context, apk, spec);
                        verified = true;
                    } catch (Exception ignored) {
                        /* קובץ חלקי או ישן יורד מחדש. */
                    }
                if (!verified) download(spec, apk);
                verify(context, apk, spec);
                state(
                        "ready",
                        "גרסה "
                                + spec.versionName
                                + " מוכנה להתקנה"
                                + (television()
                                        ? ". ההתקנה תתחיל רק לאחר בחירה ואישור במסך הטלוויזיה."
                                        : ""));
                if (installRequested) {
                    if (background && (!automatic() || busy)) {
                        state("deferred", "העדכון מוכן ויותקן לאחר סיום העזרה");
                        return;
                    }
                    install(apk, spec, background);
                }
            } catch (Exception error) {
                state("error", "העדכון לא הושלם. השליטה ממשיכה לפעול; אפשר לנסות שוב מאוחר יותר.");
            } finally {
                download = null;
                working.set(false);
                if (done != null) done.run();
            }
        });
        return true;
    }

    private UpdateSpec fetchSpec() throws Exception {
        download =
                http.newCall(
                        new Request.Builder()
                                .url(ORIGIN + "/updates.json")
                                .header("Cache-Control", "no-cache")
                                .build());
        try (Response response = download.execute()) {
            if (response.code() != 200 || response.body() == null)
                throw new IOException("UPDATE_NETWORK");
            byte[] bytes = readBounded(response.body().byteStream(), 16384);
            JSONObject manifest =
                    new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (manifest.getInt("schema") != 1) throw new IOException("UPDATE_METADATA");
            String channel = television() ? "tv" : "phone";
            return new UpdateSpec(manifest.getJSONObject(channel), context.getPackageName());
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("UPDATE_SIZE");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void download(UpdateSpec spec, File target) throws Exception {
        state("downloading", "מורידים גרסה " + spec.versionName + "…");
        File partial = new File(context.getFilesDir(), "karov-update.part");
        download = http.newCall(new Request.Builder().url(ORIGIN + "/" + spec.path).build());
        try (Response response = download.execute()) {
            if (response.code() != 200 || response.body() == null)
                throw new IOException("UPDATE_NETWORK");
            if (response.body().contentLength() != -1
                    && response.body().contentLength() != spec.bytes)
                throw new IOException("UPDATE_SIZE");
            try (FileOutputStream output = new FileOutputStream(partial);
                    InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[32768];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > spec.bytes) throw new IOException("UPDATE_SIZE");
                    output.write(buffer, 0, count);
                }
                if (total != spec.bytes) throw new IOException("UPDATE_SIZE");
                output.getFD().sync();
            }
            verify(context, partial, spec);
            if (!partial.renameTo(target)) throw new IOException("UPDATE_STORAGE");
        } finally {
            partial.delete();
        }
    }

    public static void verify(Context context, File file, UpdateSpec spec) throws Exception {
        if (file.length() != spec.bytes || file.length() > UpdateSpec.MAX_BYTES)
            throw new IOException("UPDATE_SIZE");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!hex.toString().equals(spec.sha256)) throw new IOException("UPDATE_HASH");
        PackageManager pm = context.getPackageManager();
        // Android 9 אינו מחזיר SigningInfo עבור APK בארכיון בכל קושחה. מבקשים גם את השדה הישן,
        // ומשווים אותן תעודות בדיוק; אין קבלה של קובץ חסר חתימה או של חתימה שונה.
        int flags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
        PackageInfo own = pm.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (candidate == null
                || !candidate.packageName.equals(context.getPackageName())
                || !spec.applicationId.equals(context.getPackageName())
                || candidate.getLongVersionCode() != spec.versionCode
                || !spec.versionName.equals(candidate.versionName)
                || candidate.getLongVersionCode() <= own.getLongVersionCode()
                || signers(candidate).isEmpty()
                || !signers(candidate).equals(signers(own)))
            throw new IOException("UPDATE_IDENTITY");
    }

    private static Set<android.content.pm.Signature> signers(PackageInfo info) {
        android.content.pm.Signature[] values =
                info.signingInfo != null
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signatures;
        return values == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(values));
    }

    private void install(File apk, UpdateSpec spec, boolean background) throws Exception {
        // הגנה נוספת בנקודת ההתקנה: גם בקשת רקע מפורשת אינה רשאית להתקין בסטרימר.
        if (!UpdatePolicy.mayInstall(television(), true, background)) {
            state("ready", "העדכון מוכן. ההתקנה ממתינה לבחירה ולאישור במסך הטלוויזיה.");
            return;
        }
        // גם קובץ שכבר ירד נבדק שוב ממש לפני מסירתו למתקין המערכת.
        verify(context, apk, spec);
        if (background && (!automatic() || busy)) {
            state("deferred", "העדכון מוכן ויותקן לאחר סיום העזרה");
            return;
        }
        LocalInstaller local = localInstaller;
        if (UpdatePolicy.localInstall(television()) && local != null && local.available()) {
            prefs.edit()
                    .putLong("expected", spec.versionCode)
                    .putLong("installAt", System.currentTimeMillis())
                    .commit();
            state("installing", "מתקינים עדכון באמצעות החיבור המקומי המאושר…");
            try {
                local.install(apk);
                return;
            } catch (Exception error) {
                // זמינות ADB אינה מבטיחה שהקושחה תאפשר התקנה. ממשיכים למתקין Android
                // עם אותו APK שכבר אומת; הרשאות ואישורי מערכת ממשיכים לחול כרגיל.
                if (context.getPackageManager()
                                .getPackageInfo(context.getPackageName(), 0)
                                .getLongVersionCode()
                        >= spec.versionCode) {
                    state("current", "העדכון הושלם. הזיווגים נשמרו.");
                    return;
                }
                verify(context, apk, spec);
                if (background && (!automatic() || busy)) {
                    state("deferred", "העדכון מוכן ויותקן לאחר סיום העזרה");
                    return;
                }
            }
        }
        if (!permission()) {
            state("permission", "בהכנה הראשונה יש לאפשר לקרוב להתקין עדכונים");
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int old = prefs.getInt("session", -1);
        if (old >= 0)
            try {
                installer.abandonSession(old);
            } catch (Exception ignored) {
                /* סשן שהסתיים כבר אינו קיים. */
            }
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(spec.bytes);
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(
                    UpdatePolicy.userAction(Build.VERSION.SDK_INT, television()));
        int sessionId = installer.createSession(params);
        String nonce = UUID.randomUUID().toString();
        confirmation = null;
        prefs.edit()
                .putInt("session", sessionId)
                .putString("nonce", nonce)
                .putLong("expected", spec.versionCode)
                .putLong("installAt", System.currentTimeMillis())
                .commit();
        try (PackageInstaller.Session session = installer.openSession(sessionId);
                InputStream input = new FileInputStream(apk);
                OutputStream output = session.openWrite("base.apk", 0, spec.bytes)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            session.fsync(output);
            // הזרם חייב להיסגר לפני commit; אחרת Android מסרב להתקין.
        }
        state(
                "installing",
                television()
                        ? "מכינים את חלון ההתקנה של Android. יש לאשר את העדכון במסך הטלוויזיה."
                        : "מתקינים עדכון; החיבור יתחדש לאחר ההתקנה…");
        Intent result =
                new Intent(context, UpdateReceiver.class)
                        .setAction(context.getPackageName() + ".UPDATE_RESULT")
                        .putExtra("nonce", nonce);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, result, flags);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            if (background && (!automatic() || busy)) {
                session.abandon();
                prefs.edit().remove("session").remove("nonce").remove("expected").commit();
                state("deferred", "העדכון מוכן ויותקן לאחר סיום העזרה");
                return;
            }
            session.commit(callback.getIntentSender());
        }
    }

    public synchronized void installationResult(Intent intent) {
        if (!Objects.equals(prefs.getString("nonce", ""), intent.getStringExtra("nonce"))
                || prefs.getInt("session", -2)
                        != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)) return;
        int status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            state(
                    "confirmation",
                    "Android דורש אישור התקנה אחד במכשיר. פתחו את עדכוני קרוב להמשך.");
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            confirmation = null;
            state("current", "העדכון הושלם. הזיווגים נשמרו.");
        } else {
            confirmation = null;
            prefs.edit().remove("session").remove("nonce").commit();
            state(
                    "error",
                    status == PackageInstaller.STATUS_FAILURE_ABORTED
                            ? "העדכון בוטל. הגרסה הקיימת ממשיכה לפעול."
                            : "Android לא השלים את העדכון. אפשר לנסות שוב.");
        }
    }

    private void state(String state, String message) {
        prefs.edit().putString("state", state).putString("message", message).commit();
    }
}
