package org.yosef.karov.phone;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.View;
import android.view.Window;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yosef.karov.Crypto;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class PhoneFiles {
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int RPC_TIMEOUT = 25000;
    private static final int MIN_CHUNK_GAP_MS = 22;
    private final MainActivity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, JSONObject> checked = new LinkedHashMap<>();
    private Dialog dialog;
    private LinearLayout body;
    private String pickerTarget, pickerDestination;
    private Uri pickedUri;
    private long reconnectDeadline;
    private long screen;
    private boolean destroyed;
    private volatile Upload upload;
    private TextView progressText;
    private ProgressBar progress;
    private Button cancelButton;

    private static final class Upload {
        final RemoteClient connection;
        volatile String transfer;
        volatile boolean cancelled;
        boolean finishing, cancelSent;
        long lastProgress, lastChunk;

        Upload(RemoteClient connection) {
            this.connection = connection;
        }
    }

    private record Chunk(long received, CompletableFuture<Object> reply) {}

    PhoneFiles(MainActivity host) {
        this.host = host;
    }

    void save(Bundle state) {
        state.putString("files.picker.target", pickerTarget);
        state.putString("files.picker.destination", pickerDestination);
        if (pickedUri != null) state.putString("files.picker.uri", pickedUri.toString());
    }

    void restore(Bundle state) {
        if (state == null) return;
        pickerTarget = state.getString("files.picker.target");
        pickerDestination = state.getString("files.picker.destination");
        String uri = state.getString("files.picker.uri");
        if (uri != null) {
            pickedUri = Uri.parse(uri);
            reconnectDeadline = SystemClock.elapsedRealtime() + 25000;
        }
    }

    void destroy() {
        destroyed = true;
        cancelUpload();
        main.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss();
        worker.shutdownNow();
    }

    void disconnected() {
        Upload active = upload;
        if (active != null) {
            synchronized (active) {
                active.cancelled = true;
            }
        }
        if (dialog != null && pickedUri == null && pickerTarget == null && active == null)
            dialog.dismiss();
    }

    boolean transferring() {
        return upload != null;
    }

    private String size(long bytes) {
        return bytes < 0 ? "לא ידוע" : Formatter.formatFileSize(host, bytes);
    }

    private JSONObject arg(String key, Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (Exception error) {
            throw new IllegalArgumentException(error);
        }
    }

    private String errorMessage(Throwable error) {
        return switch (MainActivity.reason(error)) {
            case "FILE_STORAGE_UNAVAILABLE" ->
                    "יעד האחסון אינו זמין. בדקו את הכונן או בחרו יעד אחר.";
            case "FILE_NO_SPACE" -> "אין מספיק מקום פנוי ביעד.";
            case "FILE_BUSY" -> "מתבצעת פעולה אחרת בקבצים. המתינו לסיומה.";
            case "FILE_INVALID" -> "הקובץ, השם או יעד האחסון אינם תקינים.";
            case "FILE_CHANGED" -> "הקובץ השתנה. רעננו את הרשימה לפני פעולה נוספת.";
            case "FILE_INCOMPLETE" -> "הקובץ התקבל חלקית וההעברה לא הושלמה.";
            case "FILE_WRITE_FAILED" -> "לא ניתן לשמור את הקובץ ביעד שנבחר.";
            case "FILE_READ_FAILED" -> "לא ניתן לקרוא את הקובץ שנבחר.";
            case "FILE_PERMISSION" -> "הרשאת הגישה לקובץ או לתיקייה חסרה.";
            case "FILE_NO_PICKER" -> "בחירת תיקייה אינה זמינה בסטרימר הזה.";
            case "FILE_NO_PHONE_PICKER" -> "בחירת מסמך אינה זמינה במכשיר הזה.";
            case "FILE_NO_VIEWER" -> "אין בסטרימר יישום מתאים לפתיחת הקובץ.";
            case "FILE_TOO_LARGE" -> "אפשר להעביר קבצים בגודל של עד 8 GiB.";
            case "FILE_UNKNOWN_SIZE" -> "ספק הקובץ לא מסר גודל. בחרו עותק מקומי של הקובץ.";
            case "FILE_CANCELLED" -> "העברת הקובץ בוטלה.";
            case "DISCONNECTED", "NETWORK", "CONNECT_TIMEOUT", "REQUEST_TIMEOUT", "TIMEOUT" ->
                    "החיבור נותק או התעכב. בדקו את הרשימה לפני ניסיון נוסף.";
            case "UNSUPPORTED" -> "יש לעדכן את קרוב בסטרימר כדי להשתמש בקבצים.";
            default -> "לא ניתן להשלים את פעולת הקבצים.";
        };
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(host);
        value.setOrientation(LinearLayout.VERTICAL);
        value.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return value;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = host.dp(margin);
        parent.addView(view, params);
    }

    private void label(LinearLayout parent, String value, int size, int color, int margin) {
        add(parent, host.text(value, size, color), margin);
    }

    private void button(String label, boolean primary, Runnable action) {
        add(body, host.button(label, primary, action), 10);
    }

    private void page(String title, Runnable back) {
        screen++;
        if (dialog == null) {
            Dialog opened = new Dialog(host);
            opened.requestWindowFeature(Window.FEATURE_NO_TITLE);
            opened.setOnCancelListener(d -> cancelUpload());
            opened.setOnDismissListener(d -> {
                if (dialog == opened) {
                    dialog = null;
                    screen++;
                }
            });
            dialog = opened;
            opened.show();
            Window window = opened.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(
                        Math.min(
                                host.getResources().getDisplayMetrics().widthPixels - host.dp(24),
                                host.dp(560)),
                        -2);
            }
        }
        LinearLayout shell = column();
        shell.setPadding(host.dp(18), host.dp(12), host.dp(18), host.dp(16));
        shell.setBackground(host.shape(MainActivity.BG, 24));
        LinearLayout header = host.row();
        TextView heading = host.text(title, 20, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close =
                host.icon(
                        back == null ? "close" : "back",
                        back == null ? "סגירה" : "חזרה",
                        () -> {
                            if (back != null) back.run();
                            else if (dialog != null) dialog.dismiss();
                        });
        header.addView(close, new LinearLayout.LayoutParams(host.dp(48), host.dp(48)));
        shell.addView(header);
        ScrollView scroll =
                new ScrollView(host) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int available =
                                Math.max(
                                        host.dp(100),
                                        (int)
                                                (host.getResources()
                                                                .getDisplayMetrics()
                                                                .heightPixels
                                                        * .62));
                        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                            available = Math.min(available, MeasureSpec.getSize(height));
                        super.onMeasure(
                                width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
                    }
                };
        body = column();
        scroll.addView(body);
        add(shell, scroll, 6);
        dialog.setContentView(shell);
        dialog.setCancelable(upload == null);
        dialog.setCanceledOnTouchOutside(false);
        close.setVisibility(upload == null ? View.VISIBLE : View.GONE);
    }

    void open() {
        if (upload != null) {
            uploadPage(upload, "העברת קובץ");
            return;
        }
        RemoteClient active = host.fileConnection();
        if (active == null || !host.supportsFiles()) {
            page("קבצים ופינוי מקום", null);
            label(
                    body,
                    active == null
                            ? "מתחברים לסטרימר לפני ניהול הקבצים."
                            : "יש לעדכן את קרוב בסטרימר כדי להשתמש בקבצים.",
                    15,
                    MainActivity.MUTED,
                    4);
            return;
        }
        page("קבצים ופינוי מקום", null);
        label(body, "טוענים יעדי אחסון…", 15, MainActivity.MUTED, 4);
        request(active, "fileDestinations", new JSONObject(), value -> destinations(active, value));
    }

    private interface Reply {
        void received(JSONObject value);
    }

    private void request(RemoteClient active, String method, JSONObject args, Reply reply) {
        long displayed = screen;
        active.call(method, args, RPC_TIMEOUT)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (destroyed || dialog == null || displayed != screen) return;
                            if (host.fileConnection() != active) {
                                failure(new Exception("DISCONNECTED"), this::open);
                            } else if (error != null || !(value instanceof JSONObject)) {
                                failure(
                                        error == null ? new Exception("FILE_INVALID") : error,
                                        this::open);
                            } else reply.received((JSONObject) value);
                        }));
    }

    private void command(
            RemoteClient active, String method, JSONObject args, String message, Runnable back) {
        page("פעולה בסטרימר", back);
        label(body, "שולחים בקשה לטלוויזיה…", 15, MainActivity.MUTED, 4);
        long displayed = screen;
        active.call(method, args, RPC_TIMEOUT)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (destroyed || dialog == null || displayed != screen) return;
                            if (host.fileConnection() != active)
                                failure(new Exception("DISCONNECTED"), back);
                            else if (error != null || !Boolean.TRUE.equals(value))
                                failure(
                                        error == null ? new Exception("FILE_INVALID") : error,
                                        back);
                            else {
                                page("הבקשה נשלחה", back);
                                label(body, message, 15, MainActivity.MUTED, 4);
                                button("חזרה לקבצים", true, back);
                            }
                        }));
    }

    private void failure(Throwable error, Runnable back) {
        page("פעולת קבצים לא הושלמה", back);
        label(body, errorMessage(error), 15, MainActivity.MUTED, 4);
        button("חזרה לקבצים", true, back);
    }

    private void destinations(RemoteClient active, JSONObject value) {
        page("קבצים ופינוי מקום", null);
        label(body, "בחרו אחסון לשליחה או לניהול קבצים.", 15, MainActivity.MUTED, 4);
        JSONArray items = value.optJSONArray("destinations");
        if (items == null || items.length() == 0)
            label(body, "אין כרגע יעד אחסון זמין.", 16, Color.WHITE, 10);
        else
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                LinearLayout card = column();
                card.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
                card.setBackground(host.shape(MainActivity.PANEL, 16));
                label(card, item.optString("name"), 17, Color.WHITE, 0);
                String kind = item.optString("kind").equals("usb") ? "כונן USB" : "אחסון פנימי";
                label(
                        card,
                        kind
                                + " · פנוי "
                                + size(item.optLong("freeBytes", -1))
                                + " מתוך "
                                + size(item.optLong("totalBytes", -1)),
                        14,
                        MainActivity.MUTED,
                        4);
                String detail = item.optString("detail");
                if (!detail.isEmpty()) label(card, detail, 13, MainActivity.MUTED, 4);
                if (item.optBoolean("needsAccess"))
                    label(
                            card,
                            "בחרו תיקייה בטלוויזיה ואשרו גישה ליעד.",
                            14,
                            MainActivity.MUTED,
                            4);
                else if (!item.optBoolean("writable"))
                    label(card, "היעד אינו זמין לכתיבה", 14, MainActivity.MUTED, 4);
                LinearLayout actions = host.row();
                Button send = host.button("בחירת קובץ", true, () -> choose(active, item));
                send.setEnabled(item.optBoolean("writable"));
                send.setAlpha(send.isEnabled() ? 1 : .45f);
                host.cell(actions, send);
                host.cell(
                        actions,
                        host.button("קבצים ופינוי", false, () -> list(active, item, 0, true)));
                add(card, actions, 8);
                add(body, card, 10);
            }
        if (value.optBoolean("folderPicker"))
            button(
                    "בחירת תיקייה בטלוויזיה",
                    false,
                    () ->
                            command(
                                    active,
                                    "fileChooseFolder",
                                    new JSONObject(),
                                    "בחרו את התיקייה במסך הטלוויזיה ואשרו גישה. לאחר מכן רעננו את"
                                            + " היעדים.",
                                    this::open));
        if (!value.optBoolean("folderPicker"))
            label(body, "בורר תיקיות אינו זמין בסטרימר הזה.", 14, MainActivity.MUTED, 10);
        button(
                "הגדרות אחסון בטלוויזיה",
                false,
                () ->
                        command(
                                active,
                                "fileStorageSettings",
                                new JSONObject(),
                                "הגדרות האחסון נפתחו בטלוויזיה.",
                                this::open));
        button("רענון יעדים", false, this::open);
    }

    private void list(RemoteClient active, JSONObject destination, int offset, boolean reset) {
        if (reset) checked.clear();
        page(destination.optString("name", "קבצים"), this::open);
        label(body, "טוענים את הקבצים…", 15, MainActivity.MUTED, 4);
        try {
            JSONObject args = arg("destination", destination.getString("id")).put("offset", offset);
            request(
                    active,
                    "fileList",
                    args,
                    result -> showList(active, destination, offset, result));
        } catch (Exception error) {
            failure(error, this::open);
        }
    }

    private void showList(
            RemoteClient active, JSONObject destination, int offset, JSONObject result) {
        page(destination.optString("name", "קבצים"), this::open);
        if (result.optBoolean("truncated"))
            label(body, "הרשימה חלקית כי התיקייה מכילה קבצים רבים.", 14, MainActivity.MUTED, 4);
        JSONArray items = result.optJSONArray("files");
        if (items == null || items.length() == 0) {
            label(body, "אין קבצים להצגה בתיקייה הזאת.", 15, MainActivity.MUTED, 4);
            return;
        }
        label(
                body,
                "הקבצים הגדולים מופיעים ראשונים. אפשר לסמן עד 20 קבצים למחיקה.",
                14,
                MainActivity.MUTED,
                4);
        Button delete =
                host.button(
                        "מחיקת קבצים שסומנו",
                        true,
                        () -> confirmDelete(active, destination, offset));
        delete.setEnabled(!checked.isEmpty());
        delete.setAlpha(delete.isEnabled() ? 1 : .45f);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            LinearLayout card = column();
            card.setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8));
            card.setBackground(host.shape(MainActivity.PANEL, 16));
            String name = item.optString("name");
            if (item.optBoolean("canDelete")) {
                CheckBox mark = new CheckBox(host);
                mark.setText(name);
                mark.setTextColor(Color.WHITE);
                mark.setButtonTintList(ColorStateList.valueOf(MainActivity.ACCENT));
                mark.setChecked(checked.containsKey(id));
                mark.setOnCheckedChangeListener((view, on) -> {
                    if (on && checked.size() >= 20 && !checked.containsKey(id)) {
                        mark.setChecked(false);
                        Toast.makeText(host, "אפשר לסמן עד 20 קבצים", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (on) checked.put(id, item);
                    else checked.remove(id);
                    delete.setEnabled(!checked.isEmpty());
                    delete.setAlpha(delete.isEnabled() ? 1 : .45f);
                });
                add(card, mark, 0);
            } else label(card, name, 16, Color.WHITE, 0);
            label(card, size(item.optLong("bytes", -1)), 14, MainActivity.MUTED, 2);
            if (item.optBoolean("canOpen"))
                add(
                        card,
                        host.button(
                                "פתיחה בטלוויזיה",
                                false,
                                () -> {
                                    try {
                                        command(
                                                active,
                                                "fileOpen",
                                                arg("destination", destination.getString("id"))
                                                        .put("id", id),
                                                "הקובץ נשלח לפתיחה בנגן או במציג בטלוויזיה.",
                                                () -> list(active, destination, offset, false));
                                    } catch (Exception error) {
                                        failure(error, this::open);
                                    }
                                }),
                        5);
            add(body, card, 8);
        }
        add(body, delete, 12);
        if (result.optBoolean("hasMore"))
            button(
                    "קבצים נוספים",
                    false,
                    () ->
                            list(
                                    active,
                                    destination,
                                    result.optInt("offset", offset) + items.length(),
                                    false));
        if (offset > 0) button("לתחילת הרשימה", false, () -> list(active, destination, 0, false));
    }

    private void confirmDelete(RemoteClient active, JSONObject destination, int offset) {
        if (checked.isEmpty() || host.fileConnection() != active) return;
        JSONArray ids = new JSONArray(checked.keySet());
        Runnable back = () -> list(active, destination, offset, false);
        page("מחיקת קבצים", back);
        label(
                body,
                "למחוק לצמיתות "
                        + ids.length()
                        + " קבצים מהיעד "
                        + destination.optString("name")
                        + "?",
                16,
                Color.WHITE,
                4);
        for (JSONObject item : checked.values())
            label(
                    body,
                    item.optString("name") + " · " + size(item.optLong("bytes", -1)),
                    14,
                    MainActivity.MUTED,
                    5);
        button(
                "מחיקה לצמיתות",
                true,
                () -> {
                    if (host.fileConnection() != active) {
                        failure(new Exception("DISCONNECTED"), this::open);
                        return;
                    }
                    try {
                        JSONObject args =
                                arg("destination", destination.getString("id")).put("ids", ids);
                        page("מוחקים קבצים", null);
                        label(body, "ממתינים לתשובה מהסטרימר…", 15, MainActivity.MUTED, 4);
                        request(
                                active,
                                "fileDelete",
                                args,
                                result -> {
                                    checked.clear();
                                    Runnable refreshed = () -> list(active, destination, 0, true);
                                    page("תוצאות פינוי מקום", refreshed);
                                    label(
                                            body,
                                            "נמחקו "
                                                    + result.optInt("removed")
                                                    + " · לא נמחקו "
                                                    + result.optInt("failed")
                                                    + " · פונו "
                                                    + size(result.optLong("freedBytes", -1)),
                                            16,
                                            Color.WHITE,
                                            4);
                                    button("רענון הרשימה", true, refreshed);
                                });
                    } catch (Exception error) {
                        failure(error, this::open);
                    }
                });
        button("ביטול", false, back);
    }

    private void choose(RemoteClient active, JSONObject destination) {
        if (host.fileConnection() != active) return;
        pickerTarget = host.fileTarget();
        pickerDestination = destination.optString("id");
        if (dialog != null) dialog.dismiss();
        Intent pick =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            host.pickFile(pick);
        } catch (android.content.ActivityNotFoundException unavailable) {
            try {
                // גם בורר חלופי מעניק גישה למסמך שנבחר בלבד.
                host.pickFile(new Intent(pick).setAction(Intent.ACTION_GET_CONTENT));
            } catch (RuntimeException error) {
                clearPicker();
                failure(new Exception("FILE_NO_PHONE_PICKER"), this::open);
            }
        } catch (RuntimeException error) {
            clearPicker();
            failure(new Exception("FILE_NO_PHONE_PICKER"), this::open);
        }
    }

    private void clearPicker() {
        pickerTarget = null;
        pickerDestination = null;
        pickedUri = null;
        main.removeCallbacks(pickerTimeout);
    }

    private final Runnable pickerTimeout = this::pickerExpired;

    private void pickerExpired() {
        if (pickedUri != null && host.filesResumed()) {
            clearPicker();
            failure(new Exception("DISCONNECTED"), this::open);
        }
    }

    void picked(Intent result) {
        if (pickerTarget == null || destroyed) return;
        Uri uri = result == null ? null : result.getData();
        if (uri == null) {
            clearPicker();
            if (host.fileConnection() != null) open();
            return;
        }
        if (!"content".equals(uri.getScheme())) {
            clearPicker();
            failure(new Exception("FILE_PERMISSION"), this::open);
            return;
        }
        pickedUri = uri;
        reconnectDeadline = SystemClock.elapsedRealtime() + 25000;
        resume();
    }

    void resume() {
        if (pickedUri == null || !host.filesResumed()) return;
        if (pickerTarget == null || !pickerTarget.equals(host.fileTarget())) {
            clearPicker();
            failure(new Exception("DISCONNECTED"), this::open);
            return;
        }
        RemoteClient active = host.fileConnection();
        if (active == null) {
            main.removeCallbacks(pickerTimeout);
            main.postDelayed(
                    pickerTimeout, Math.max(0, reconnectDeadline - SystemClock.elapsedRealtime()));
            return;
        }
        String destination = pickerDestination;
        Uri uri = pickedUri;
        clearPicker();
        if (!host.supportsFiles()) {
            failure(new Exception("UNSUPPORTED"), this::open);
            return;
        }
        page("הכנת העברה", this::open);
        label(body, "בודקים שהיעד עדיין זמין…", 15, MainActivity.MUTED, 4);
        request(
                active,
                "fileDestinations",
                new JSONObject(),
                result -> {
                    JSONArray targets = result.optJSONArray("destinations");
                    if (targets != null)
                        for (int i = 0; i < targets.length(); i++) {
                            JSONObject target = targets.optJSONObject(i);
                            if (target != null
                                    && target.optBoolean("writable")
                                    && destination.equals(target.optString("id"))) {
                                startUpload(active, target, uri);
                                return;
                            }
                        }
                    failure(new Exception("FILE_STORAGE_UNAVAILABLE"), this::open);
                });
    }

    private void startUpload(RemoteClient active, JSONObject destination, Uri uri) {
        if (upload != null || host.fileConnection() != active) return;
        Upload task = new Upload(active);
        upload = task;
        uploadPage(task, "הכנת הקובץ");
        worker.execute(() -> sendFile(task, destination, uri));
    }

    private void uploadPage(Upload task, String title) {
        page(title, null);
        progressText = host.text("מכינים העברה לסטרימר…", 15, MainActivity.MUTED);
        progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, progressText, 5);
        progress = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(MainActivity.ACCENT));
        add(body, progress, 12);
        cancelButton = host.button("ביטול העברה", false, this::cancelUpload);
        synchronized (task) {
            cancelButton.setEnabled(!task.finishing && !task.cancelled);
        }
        add(body, cancelButton, 10);
    }

    private CompletableFuture<Object> dispatch(Upload task, String method, JSONObject args)
            throws Exception {
        boolean chunk = method.equals("fileChunk");
        if (chunk) {
            // שומרים מרווח גם בחיבור מהיר כדי להישאר מתחת למגבלת הקצב של המתווך.
            long remaining = task.lastChunk + MIN_CHUNK_GAP_MS - SystemClock.elapsedRealtime();
            if (remaining > 0) Thread.sleep(remaining);
        }
        synchronized (task) {
            if (task.cancelled || Thread.currentThread().isInterrupted())
                throw new Exception("FILE_CANCELLED");
            if (chunk) task.lastChunk = SystemClock.elapsedRealtime();
            return task.connection.call(method, args, RPC_TIMEOUT);
        }
    }

    private JSONObject response(CompletableFuture<Object> pending) throws Exception {
        Object result = pending.get(RPC_TIMEOUT + 2000, TimeUnit.MILLISECONDS);
        if (!(result instanceof JSONObject value)) throw new Exception("FILE_INVALID");
        return value;
    }

    private JSONObject call(Upload task, String method, JSONObject args) throws Exception {
        return response(dispatch(task, method, args));
    }

    private void confirmChunk(Upload task, Chunk chunk, long total) throws Exception {
        if (response(chunk.reply()).getLong("received") != chunk.received())
            throw new Exception("FILE_INCOMPLETE");
        publishProgress(task, chunk.received(), total);
    }

    private void requestCancel(Upload task) {
        synchronized (task) {
            if (task.transfer == null || task.cancelSent) return;
            task.cancelSent = true;
            task.connection.call("fileCancel", arg("transfer", task.transfer), RPC_TIMEOUT);
        }
    }

    private void cancelUpload() {
        Upload active = upload;
        if (active == null) return;
        synchronized (active) {
            if (active.finishing || active.cancelled) return;
            active.cancelled = true;
            requestCancel(active);
        }
        if (cancelButton != null) cancelButton.setEnabled(false);
        if (progressText != null) progressText.setText("מבטלים את ההעברה…");
    }

    private void sendFile(Upload task, JSONObject destination, Uri uri) {
        JSONObject finished = null;
        Exception failure = null;
        ArrayDeque<Chunk> pending = new ArrayDeque<>();
        try {
            String name;
            long bytes;
            try (Cursor cursor =
                    host.getContentResolver()
                            .query(
                                    uri,
                                    new String[] {
                                        OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
                                    },
                                    null,
                                    null,
                                    null)) {
                if (cursor == null || !cursor.moveToFirst())
                    throw new Exception("FILE_READ_FAILED");
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn < 0 || cursor.isNull(nameColumn))
                    throw new Exception("FILE_READ_FAILED");
                name = cursor.getString(nameColumn);
                bytes =
                        sizeColumn < 0 || cursor.isNull(sizeColumn)
                                ? -1
                                : cursor.getLong(sizeColumn);
            }
            if (bytes < 0) throw new Exception("FILE_UNKNOWN_SIZE");
            if (bytes > MAX_BYTES) throw new Exception("FILE_TOO_LARGE");
            long free = destination.optLong("freeBytes", -1);
            if (free >= 0 && bytes > free) throw new Exception("FILE_NO_SPACE");
            String mime = host.getContentResolver().getType(uri);
            try (InputStream input = host.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("FILE_READ_FAILED");
                JSONObject started =
                        call(
                                task,
                                "fileBegin",
                                new JSONObject()
                                        .put("destination", destination.getString("id"))
                                        .put("name", name)
                                        .put("bytes", bytes)
                                        .put(
                                                "mime",
                                                mime == null ? "application/octet-stream" : mime));
                task.transfer = started.getString("transfer");
                int chunk = started.getInt("chunkBytes");
                if (chunk <= 0 || chunk > 98304) throw new Exception("FILE_INVALID");
                int window = Math.max(1, Math.min(4, started.optInt("window", 1)));
                byte[] buffer = new byte[chunk];
                long offset = 0;
                publishProgress(task, 0, bytes);
                // שולחים לפי סדר ההיסטים ומאשרים לפי אותו סדר, עד ארבע מנות בו זמנית.
                while (offset < bytes) {
                    if (task.cancelled) throw new Exception("FILE_CANCELLED");
                    if (pending.size() == window) confirmChunk(task, pending.removeFirst(), bytes);
                    int wanted = (int) Math.min(buffer.length, bytes - offset);
                    int read = 0;
                    while (read < wanted) {
                        if (task.cancelled) throw new Exception("FILE_CANCELLED");
                        int count = input.read(buffer, read, wanted - read);
                        if (count <= 0) throw new Exception("FILE_CHANGED");
                        read += count;
                    }
                    CompletableFuture<Object> sent =
                            dispatch(
                                    task,
                                    "fileChunk",
                                    arg("transfer", task.transfer)
                                            .put("offset", offset)
                                            .put(
                                                    "data",
                                                    Crypto.b64(
                                                            read == buffer.length
                                                                    ? buffer
                                                                    : Arrays.copyOf(
                                                                            buffer, read))));
                    offset += read;
                    pending.addLast(new Chunk(offset, sent));
                }
                while (!pending.isEmpty()) confirmChunk(task, pending.removeFirst(), bytes);
                if (task.cancelled) throw new Exception("FILE_CANCELLED");
                if (input.read() != -1) throw new Exception("FILE_CHANGED");
            }
            synchronized (task) {
                if (task.cancelled) throw new Exception("FILE_CANCELLED");
                task.finishing = true;
            }
            main.post(() -> {
                if (upload == task && cancelButton != null) {
                    cancelButton.setEnabled(false);
                    progressText.setText("משלימים את השמירה בסטרימר…");
                }
            });
            finished = call(task, "fileFinish", arg("transfer", task.transfer));
            if (finished.getLong("bytes") != bytes || finished.optString("name").isEmpty())
                throw new Exception("FILE_INCOMPLETE");
        } catch (SecurityException error) {
            failure = new Exception("FILE_PERMISSION");
        } catch (java.io.IOException error) {
            failure = new Exception("FILE_READ_FAILED");
        } catch (Exception error) {
            failure = error;
        } finally {
            if (failure != null) {
                requestCancel(task);
                // הביטול נשלח אחרי המנות שכבר שוגרו. הלקוח מסיים את ההמתנה להן בתשובה או בפסק זמן.
                pending.clear();
            }
        }
        JSONObject result = finished;
        Exception error = failure;
        main.post(() -> {
            if (upload != task) return;
            upload = null;
            if (destroyed || !host.filesResumed()) {
                if (dialog != null) dialog.dismiss();
                return;
            }
            if (error != null) {
                failure(error, this::open);
            } else {
                page("הקובץ נשמר", this::open);
                label(
                        body,
                        result.optString("name") + " · " + size(result.optLong("bytes")),
                        16,
                        Color.WHITE,
                        4);
                button("חזרה לקבצים", true, this::open);
            }
        });
    }

    private void publishProgress(Upload task, long received, long total) {
        long now = SystemClock.elapsedRealtime();
        if (received != total && received != 0 && now - task.lastProgress < 120) return;
        task.lastProgress = now;
        main.post(() -> {
            if (upload != task || task.cancelled || dialog == null) return;
            progress.setIndeterminate(false);
            progress.setProgress(total == 0 ? 1000 : (int) ((double) received / total * 1000));
            progressText.setText(size(received) + " מתוך " + size(total));
        });
    }
}
