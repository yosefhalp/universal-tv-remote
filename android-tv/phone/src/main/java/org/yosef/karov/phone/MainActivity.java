package org.yosef.karov.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import okhttp3.*;

import org.json.*;
import org.yosef.karov.Crypto;

import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    private static final int BG = Color.rgb(20, 21, 25),
            PANEL = Color.rgb(31, 33, 39),
            LINE = Color.rgb(56, 59, 68),
            MUTED = Color.rgb(178, 182, 192),
            ACCENT = Color.rgb(255, 120, 78);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private PhoneVault vault;
    private JSONObject selected;
    private RemoteClient connection;
    private volatile RemoteClient pairingConnection;
    private LinearLayout content;
    private TextView status, screenMessage;
    private ImageView screen, fullScreen;
    private Button pause;
    private Dialog fullDialog;
    private Bitmap frame;
    private volatile boolean resumed, destroyed;
    private boolean connected, paused, pairBusy;
    private int reconnects;
    private volatile int pairEpoch;
    private long lastFrame;
    private JSONArray apps = new JSONArray();
    private volatile Call lookup;
    private final ActivityResultLauncher<Intent> scanner =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            try {
                                Pairing pairing =
                                        Pairing.fromQr(
                                                result.getData().getStringExtra("qr"),
                                                System.currentTimeMillis());
                                new AlertDialog.Builder(this)
                                        .setTitle("חיבור " + pairing.name)
                                        .setMessage(
                                                "לשמור בטלפון הזה הרשאה לצפות במסך ולשלוט"
                                                        + " בטלוויזיה?")
                                        .setPositiveButton(
                                                "חיבור ושמירה", (d, w) -> pair(pairing, null))
                                        .setNegativeButton("ביטול", null)
                                        .show();
                            } catch (Exception error) {
                                toast(explain(error));
                            }
                        }
                    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            vault = new PhoneVault(this);
            selected = vault.get(vault.last());
            render();
        } catch (Exception error) {
            TextView failure =
                    text(
                            "לא ניתן לקרוא את הזיווגים השמורים. אין למחוק את נתוני האפליקציה לפני"
                                    + " בדיקה.",
                            20,
                            Color.WHITE);
            failure.setPadding(dp(24), dp(60), dp(24), dp(30));
            setContentView(failure);
        }
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (selected != null) home();
                                else finish();
                            }
                        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (selected != null && connection == null) connect();
        main.post(staleFrame);
    }

    @Override
    protected void onPause() {
        resumed = false;
        pairEpoch++;
        pairBusy = false;
        if (lookup != null) lookup.cancel();
        if (pairingConnection != null) pairingConnection.close();
        stopConnection();
        if (fullDialog != null) fullDialog.dismiss();
        clearFrame("העזרה מושהית כשהאפליקציה ברקע");
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        tasks.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (fullDialog != null) fullDialog.dismiss();
        render();
        setConnected(connected);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private android.graphics.drawable.Drawable touchSurface(int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x30ffffff),
                shape(color, radius),
                shape(Color.WHITE, radius));
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(7), dp(12), dp(7));
        button.setTextColor(primary ? BG : Color.WHITE);
        button.setBackground(touchSurface(primary ? ACCENT : PANEL, 14));
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return button;
    }

    private void add(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        content.addView(view, params);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return row;
    }

    private void cell(LinearLayout row, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        row.addView(view, params);
    }

    private void control(Button button) {
        controls.add(button);
        button.setEnabled(connected);
        button.setAlpha(connected ? 1 : 0.4f);
    }

    private void render() {
        if (vault == null || destroyed) return;
        controls.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout bar = row();
        bar.setPadding(dp(22), dp(13), dp(22), dp(13));
        TextView brand = text("קרוב", 28, Color.WHITE);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button more =
                button(
                        "אפשרויות",
                        false,
                        () -> {
                            options();
                        });
        bar.addView(more);
        root.addView(bar);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), dp(30));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (view, insets) -> {
                    Insets safe =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout()
                                            | WindowInsetsCompat.Type.ime());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                });
        if (selected == null) renderHome();
        else renderRemote();
    }

    private void renderHome() {
        add(text("המסכים שלי", 32, Color.WHITE), 4);
        add(text("נכנסים לטלוויזיה. עוזרים מכל מקום.", 16, MUTED), 8);
        try {
            JSONArray devices = vault.devices();
            if (devices.length() == 0) {
                LinearLayout empty = new LinearLayout(this);
                empty.setOrientation(LinearLayout.VERTICAL);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(20), dp(38), dp(20), dp(38));
                empty.setBackground(shape(PANEL, 24));
                TextView tv = text("▣", 60, ACCENT);
                tv.setGravity(Gravity.CENTER);
                empty.addView(tv);
                TextView title = text("נוסיף את הטלוויזיה הראשונה", 22, Color.WHITE);
                title.setGravity(Gravity.CENTER);
                empty.addView(title);
                TextView hint =
                        text(
                                "פותחים קרוב בסטרימר ובוחרים זיווג.\n"
                                        + "סורקים QR או מקלידים קוד קצר כאן.",
                                15,
                                MUTED);
                hint.setGravity(Gravity.CENTER);
                hint.setPadding(0, dp(18), 0, 0);
                empty.addView(hint);
                add(empty, 30);
            }
            for (int i = 0; i < devices.length(); i++) {
                JSONObject item = devices.getJSONObject(i);
                String id = item.getString("device"), name = item.getString("name");
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(20), dp(20), dp(20), dp(20));
                card.setBackground(shape(PANEL, 20));
                card.addView(text(name, 21, Color.WHITE));
                TextView note = text("מכשיר שמור · אין צורך בזיווג נוסף", 13, MUTED);
                note.setPadding(0, dp(5), 0, dp(16));
                card.addView(note);
                LinearLayout actions = row();
                cell(actions, button("התחברות", true, () -> select(id)));
                cell(actions, button("הסרה", false, () -> forget(id, name)));
                card.addView(actions);
                add(card, 22);
            }
        } catch (Exception error) {
            toast(explain(error));
        }
        add(
                button(
                        "סריקת QR מהטלוויזיה",
                        true,
                        () -> {
                            if (!pairBusy) scanner.launch(new Intent(this, QrActivity.class));
                        }),
                28);
        add(button("חיבור בקוד קצר", false, this::codeDialog), 12);
        status =
                text(
                        pairBusy
                                ? "מזווגים בבטחה…"
                                : "החיבור נשמר בטלפון שלך. אפשר לבטל גישה בטלוויזיה.",
                        13,
                        MUTED);
        add(status, 22);
    }

    private void renderRemote() {
        add(text(selected.optString("name", "הטלוויזיה"), 27, Color.WHITE), 3);
        status = text(connected ? "● מחוברים לטלוויזיה" : "מתחברים לטלוויזיה…", 13, MUTED);
        add(status, 7);
        FrameLayout picture = new FrameLayout(this);
        picture.setBackground(shape(Color.BLACK, 18));
        picture.setClipToOutline(true);
        screen = new ImageView(this);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setContentDescription("מסך הסטרימר בזמן העזרה");
        picture.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        screenMessage = text("ממתינים לתמונה…", 15, MUTED);
        screenMessage.setGravity(Gravity.CENTER);
        screenMessage.setPadding(dp(16), dp(10), dp(16), dp(10));
        picture.addView(screenMessage, new FrameLayout.LayoutParams(-1, -1));
        int width = getResources().getDisplayMetrics().widthPixels - dp(44);
        LinearLayout.LayoutParams pictureSize =
                new LinearLayout.LayoutParams(
                        -1, Math.min(dp(300), Math.max(dp(174), width * 9 / 16)));
        pictureSize.topMargin = dp(22);
        content.addView(picture, pictureSize);
        picture.setOnClickListener(v -> fullscreen());
        if (frame != null && !paused) {
            screen.setImageBitmap(frame);
            screenMessage.setVisibility(View.GONE);
        }
        LinearLayout imageActions = row();
        pause =
                button(
                        paused ? "חידוש תמונה" : "השהיית תמונה",
                        false,
                        () -> {
                            paused = !paused;
                            pause.setText(paused ? "חידוש תמונה" : "השהיית תמונה");
                            clearFrame(paused ? "התמונה מושהית" : "מחדשים תמונה…");
                            if (!paused) capture(connection);
                        });
        control(pause);
        cell(imageActions, pause);
        Button expand = button("מסך מלא", false, this::fullscreen);
        control(expand);
        cell(imageActions, expand);
        add(imageActions, 12);
        LinearLayout navigation = row();
        for (String[] key :
                new String[][] {{"בית", "HOME"}, {"חזרה", "BACK"}, {"הגדרות", "SETTINGS"}}) {
            Button b = button(key[0], false, () -> key(key[1]));
            control(b);
            cell(navigation, b);
        }
        add(navigation, 23);
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        pad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pad.setPadding(dp(20), dp(16), dp(20), dp(16));
        pad.setBackground(shape(PANEL, 100));
        LinearLayout.LayoutParams padSize = new LinearLayout.LayoutParams(dp(256), dp(256));
        padSize.gravity = Gravity.CENTER;
        padSize.topMargin = dp(22);
        content.addView(pad, padSize);
        dpadRow(pad, new String[][] {{"", ""}, {"▲", "UP"}, {"", ""}});
        dpadRow(pad, new String[][] {{"◀", "LEFT"}, {"אישור", "OK"}, {"▶", "RIGHT"}});
        dpadRow(pad, new String[][] {{"", ""}, {"▼", "DOWN"}, {"", ""}});
        LinearLayout sound = row();
        for (String[] key :
                new String[][] {
                    {"קול +", "VOLUME_UP"}, {"השתקה", "MUTE"}, {"קול −", "VOLUME_DOWN"}
                }) {
            Button b = button(key[0], false, () -> key(key[1]));
            control(b);
            cell(sound, b);
        }
        add(sound, 22);
        LinearLayout media = row();
        for (String[] key :
                new String[][] {
                    {"קדימה", "FAST_FORWARD"}, {"ניגון / השהיה", "PLAY_PAUSE"}, {"אחורה", "REWIND"}
                }) {
            Button b = button(key[0], false, () -> key(key[1]));
            b.setTextSize(13);
            control(b);
            cell(media, b);
        }
        add(media, 12);
        LinearLayout tools = row();
        Button keyboard = button("כתיבה בטלוויזיה", false, this::keyboard);
        control(keyboard);
        cell(tools, keyboard);
        Button applications = button("אפליקציות", false, this::applications);
        control(applications);
        cell(tools, applications);
        add(tools, 24);
        add(button("סיום עזרה", false, this::home), 20);
        add(text("התמונה מיועדת לעזרה בניווט. תוכן מוגן עשוי להיות מוסתר.", 12, MUTED), 20);
    }

    private void dpadRow(LinearLayout pad, String[][] items) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pad.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        for (String[] item : items) {
            if (item[1].isEmpty()) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
                continue;
            }
            Button b = button(item[0], item[1].equals("OK"), () -> key(item[1]));
            b.setTextSize(item[1].equals("OK") ? 15 : 23);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setContentDescription(
                    switch (item[1]) {
                        case "UP" -> "למעלה";
                        case "DOWN" -> "למטה";
                        case "LEFT" -> "שמאלה";
                        case "RIGHT" -> "ימינה";
                        default -> "אישור";
                    });
            if (!item[1].equals("OK")) b.setBackground(touchSurface(PANEL, 40));
            control(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
            p.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, p);
        }
    }

    private void select(String id) {
        if (pairBusy) return;
        try {
            selected = vault.get(id);
            vault.last(id);
            paused = false;
            reconnects = 0;
            stopConnection();
            render();
            if (resumed) connect();
        } catch (Exception error) {
            toast(explain(error));
        }
    }

    private void home() {
        stopConnection();
        selected = null;
        paused = false;
        frame = null;
        apps = new JSONArray();
        try {
            vault.last("");
        } catch (Exception error) {
            toast(explain(error));
        }
        render();
    }

    private void stopConnection() {
        RemoteClient previous = connection;
        connection = null;
        connected = false;
        if (previous != null) previous.close();
        setConnected(false);
        clearFrame("החיבור הופסק");
    }

    private void setConnected(boolean value) {
        connected = value;
        org.yosef.karov.updates.UpdateEngine.get(this).busy(value);
        for (View view : controls) {
            view.setEnabled(value);
            view.setAlpha(value ? 1 : 0.4f);
        }
    }

    private void connect() {
        if (!resumed || selected == null || connection != null || destroyed) return;
        clearFrame("מתחברים לסטרימר…");
        status.setText("מתחברים…");
        setConnected(false);
        JSONObject target = selected;
        RemoteClient[] reference = new RemoteClient[1];
        RemoteClient active =
                new RemoteClient(
                        target.optString("device"),
                        target.optString("controller"),
                        target.optString("secret"),
                        null,
                        reason ->
                                main.post(() -> {
                                    if (resumed
                                            && connection == reference[0]
                                            && !"CLOSED".equals(reason))
                                        lost(reference[0], new Exception(reason));
                                }));
        reference[0] = active;
        connection = active;
        active.connect()
                .whenComplete((info, error) ->
                        main.post(() -> {
                            if (!resumed || connection != active || selected != target) return;
                            if (error != null) {
                                lost(active, error);
                                return;
                            }
                            reconnects = 0;
                            setConnected(true);
                            status.setText("● מחוברים לטלוויזיה");
                            status.setTextColor(Color.rgb(133, 209, 175));
                            if (target.optBoolean("pending"))
                                try {
                                    target.put("pending", false);
                                    vault.put(target);
                                } catch (Exception failure) {
                                    toast(explain(failure));
                                }
                            JSONObject initial = info.optJSONObject("firstFrame");
                            if (!paused && initial != null) display(initial, active);
                            capture(active);
                            active.call("apps", new JSONObject(), 10000)
                                    .whenComplete((value, failure) ->
                                            main.post(() -> {
                                                if (connection == active
                                                        && value instanceof JSONArray)
                                                    apps = (JSONArray) value;
                                            }));
                            heartbeat(active);
                        }));
    }

    private void heartbeat(RemoteClient active) {
        main.postDelayed(
                () -> {
                    if (!resumed || connection != active) return;
                    active.call("info", new JSONObject(), 5000)
                            .whenComplete((value, error) ->
                                    main.post(() -> {
                                        if (connection != active || !resumed) return;
                                        if (error != null) lost(active, error);
                                        else heartbeat(active);
                                    }));
                },
                5000);
    }

    private void lost(RemoteClient active, Throwable error) {
        if (connection != active) return;
        stopConnection();
        String reason = reason(error);
        status.setText(explain(error));
        status.setTextColor(MUTED);
        boolean retry =
                !Arrays.asList("DENIED", "EXPIRED", "BLOCKED", "BAD_IDENTITY").contains(reason)
                        && (!reason.equals("BUSY") || reconnects < 3);
        if (retry && resumed && selected != null) {
            int delay = Math.min(15000, 1000 << Math.min(reconnects++, 4));
            JSONObject expected = selected;
            main.postDelayed(
                    () -> {
                        if (resumed && selected == expected && connection == null) connect();
                    },
                    delay);
        }
    }

    private void capture(RemoteClient active) {
        if (active == null || connection != active || !resumed || paused || !connected) return;
        active.call("capture", new JSONObject(), 5000)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (connection != active || !resumed || paused) return;
                            if (error != null) {
                                String reason = reason(error);
                                if (Arrays.asList("NETWORK", "DISCONNECTED", "REQUEST_TIMEOUT")
                                        .contains(reason)) {
                                    lost(active, error);
                                    return;
                                }
                                if (!reason.equals("CAPTURE_BUSY")
                                        || SystemClock.elapsedRealtime() - lastFrame > 3000)
                                    clearFrame(explain(error));
                            } else if (value instanceof JSONObject)
                                display((JSONObject) value, active);
                            main.postDelayed(() -> capture(active), 500);
                        }));
    }

    private void display(JSONObject value, RemoteClient active) {
        try {
            String jpeg = value.getString("jpeg");
            if (jpeg.length() > 1200000 || !jpeg.matches("[A-Za-z0-9+/=]+"))
                throw new Exception("BAD_FRAME");
            byte[] bytes = android.util.Base64.decode(jpeg, android.util.Base64.DEFAULT);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0
                    || bounds.outHeight <= 0
                    || bounds.outWidth > 1280
                    || bounds.outHeight > 1280
                    || (long) bounds.outWidth * bounds.outHeight > 1600000)
                throw new Exception("BAD_FRAME");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new Exception("BAD_FRAME");
            if (connection != active || !resumed || paused) return;
            frame = bitmap;
            lastFrame = SystemClock.elapsedRealtime();
            screen.setImageBitmap(bitmap);
            screenMessage.setVisibility(View.GONE);
            if (fullScreen != null) fullScreen.setImageBitmap(bitmap);
        } catch (Exception error) {
            clearFrame("לא ניתן להציג את התמונה כרגע");
        }
    }

    private void clearFrame(String message) {
        frame = null;
        lastFrame = 0;
        if (screen != null) screen.setImageDrawable(null);
        if (fullScreen != null) fullScreen.setImageDrawable(null);
        if (screenMessage != null) {
            screenMessage.setText(message);
            screenMessage.setVisibility(View.VISIBLE);
        }
    }

    private final Runnable staleFrame =
            new Runnable() {
                @Override
                public void run() {
                    if (lastFrame > 0 && SystemClock.elapsedRealtime() - lastFrame > 4000)
                        clearFrame("ממתינים לתמונה מעודכנת…");
                    if (resumed) main.postDelayed(this, 1000);
                }
            };

    private void key(String key) {
        action("key", object("key", key));
    }

    private JSONObject object(String key, Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private void action(String method, JSONObject args) {
        RemoteClient active = connection;
        if (active == null || !connected) return;
        active.call(method, args, 10000)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (connection != active || !resumed) return;
                            if (error != null) {
                                toast(explain(error));
                                if (Arrays.asList("NETWORK", "DISCONNECTED", "REQUEST_TIMEOUT")
                                        .contains(reason(error))) lost(active, error);
                            }
                        }));
    }

    private void codeDialog() {
        if (pairBusy) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(16), dp(24), 0);
        TextView help =
                text("מקלידים את שמונה הספרות שמופיעות במסך הזיווג של קרוב בטלוויזיה.", 16, MUTED);
        layout.addView(help);
        EditText input = new EditText(this);
        input.setHint("00-000-000");
        input.setContentDescription("קוד זיווג");
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(27);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
        layout.addView(input);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("חיבור בקוד קצר")
                        .setView(layout)
                        .setPositiveButton("חיבור ושמירה", null)
                        .setNegativeButton("ביטול", null)
                        .create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        try {
                            String code = Pairing.normalizeCode(input.getText().toString());
                            dialog.dismiss();
                            hideKeyboard();
                            pair(null, code);
                        } catch (Exception error) {
                            input.setError("נדרשות שמונה ספרות");
                        }
                    });
        });
        dialog.show();
    }

    private void pair(Pairing supplied, String code) {
        if (pairBusy) return;
        pairBusy = true;
        int attempt = ++pairEpoch;
        render();
        tasks.execute(() -> {
            RemoteClient temporary = null;
            JSONObject record = null;
            boolean enrollmentSent = false;
            try {
                Pairing pairing = supplied;
                if (pairing == null) {
                    OkHttpClient http =
                            new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
                    try {
                        lookup =
                                http.newCall(
                                        new Request.Builder()
                                                .url(
                                                        Pairing.SERVER
                                                                + "/v1/pair/"
                                                                + code.substring(0, 2))
                                                .build());
                        try (Response response = lookup.execute()) {
                            if (!response.isSuccessful())
                                throw new Exception(
                                        response.code() == 429 ? "CODE_LIMIT" : "CODE_NOT_FOUND");
                            if (response.body() == null || response.body().contentLength() > 4096)
                                throw new Exception("BAD_PAIRING");
                            java.io.InputStream stream = response.body().byteStream();
                            byte[] bytes = new byte[4097];
                            int length = 0, count;
                            while (length < bytes.length
                                    && (count = stream.read(bytes, length, bytes.length - length))
                                            != -1) length += count;
                            if (length > 4096) throw new Exception("BAD_PAIRING");
                            pairing =
                                    Pairing.fromCode(
                                            new JSONObject(
                                                    new String(
                                                            bytes,
                                                            0,
                                                            length,
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)),
                                            code,
                                            System.currentTimeMillis());
                        }
                    } finally {
                        lookup = null;
                        http.connectionPool().evictAll();
                        http.dispatcher().executorService().shutdown();
                    }
                }
                if (destroyed || !resumed || pairEpoch != attempt) throw new Exception("CANCELLED");
                JSONObject existing = vault.get(pairing.device);
                if (existing != null) {
                    String id = pairing.device;
                    main.post(() -> {
                        if (!destroyed && resumed && pairEpoch == attempt) {
                            pairBusy = false;
                            select(id);
                        }
                    });
                    return;
                }
                temporary =
                        new RemoteClient(
                                pairing.device, "pair", pairing.secret, pairing.code, reason -> {});
                pairingConnection = temporary;
                temporary.connect().get(15, TimeUnit.SECONDS);
                if (!resumed || pairEpoch != attempt) throw new Exception("CANCELLED");
                String id = Crypto.id(), secret = Crypto.secret();
                record =
                        new JSONObject()
                                .put("device", pairing.device)
                                .put("controller", id)
                                .put("secret", secret)
                                .put("name", pairing.name)
                                .put("pending", true);
                // כותבים לכספת לפני השליחה: אובדן תשובת הרשמה לא יגרום לאובדן המפתח היחיד.
                vault.put(record);
                enrollmentSent = true;
                temporary
                        .call(
                                "enroll",
                                new JSONObject()
                                        .put("controller", id)
                                        .put("secret", secret)
                                        .put("name", "אפליקציית קרוב בטלפון"),
                                10000)
                        .get(12, TimeUnit.SECONDS);
                record.put("pending", false);
                vault.put(record);
                String device = pairing.device;
                main.post(() -> {
                    if (!destroyed && resumed && pairEpoch == attempt) {
                        pairBusy = false;
                        select(device);
                    }
                });
            } catch (Exception error) {
                if (record != null
                        && (!enrollmentSent
                                || !Arrays.asList(
                                                "DISCONNECTED",
                                                "NETWORK",
                                                "REQUEST_TIMEOUT",
                                                "CANCELLED",
                                                "TIMEOUT")
                                        .contains(reason(error)))) {
                    try {
                        vault.remove(record.optString("device"));
                    } catch (Exception ignored) {
                    }
                }
                main.post(() -> {
                    if (!destroyed && resumed && pairEpoch == attempt) {
                        pairBusy = false;
                        render();
                        toast(explain(error));
                    }
                });
            } finally {
                if (temporary != null) temporary.close();
                pairingConnection = null;
                main.post(() -> {
                    if (!destroyed && pairEpoch == attempt) {
                        pairBusy = false;
                        if (selected == null && resumed) render();
                    }
                });
            }
        });
    }

    private void keyboard() {
        EditText input = new EditText(this);
        input.setHint("מה לחפש בטלוויזיה?");
        input.setTextDirection(View.TEXT_DIRECTION_RTL);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1000)});
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(22), dp(12), dp(22), 0);
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("כתיבה בטלוויזיה")
                .setMessage("פותחים קודם שדה חיפוש בטלוויזיה.")
                .setView(layout)
                .setPositiveButton(
                        "שליחת טקסט",
                        (d, w) -> {
                            if (input.length() > 0)
                                action("text", object("text", input.getText().toString()));
                            hideKeyboard();
                        })
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(content.getWindowToken(), 0);
    }

    private void applications() {
        if (apps.length() == 0) {
            toast("רשימת האפליקציות עדיין אינה זמינה");
            return;
        }
        String[] names = new String[Math.min(100, apps.length())];
        for (int i = 0; i < names.length; i++)
            names[i] = apps.optJSONObject(i).optString("name", "אפליקציה");
        new AlertDialog.Builder(this)
                .setTitle("האפליקציות בטלוויזיה")
                .setItems(
                        names,
                        (d, index) ->
                                action(
                                        "launch",
                                        object(
                                                "package",
                                                apps.optJSONObject(index).optString("id"))))
                .setNegativeButton("סגירה", null)
                .show();
    }

    private void fullscreen() {
        if (frame == null) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        fullScreen = new ImageView(this);
        fullScreen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullScreen.setImageBitmap(frame);
        box.addView(fullScreen, new FrameLayout.LayoutParams(-1, -1));
        Button close = button("סגירה", false, dialog::dismiss);
        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(100), dp(48), Gravity.TOP | Gravity.END);
        p.setMargins(dp(16), dp(24), dp(16), dp(16));
        box.addView(close, p);
        dialog.setContentView(box);
        dialog.setOnDismissListener(d -> {
            fullScreen = null;
            fullDialog = null;
        });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        fullDialog = dialog;
        ViewCompat.setOnApplyWindowInsetsListener(
                box,
                (view, insets) -> {
                    Insets safe =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                    return insets;
                });
    }

    private void options() {
        if (selected == null) {
            new AlertDialog.Builder(this)
                    .setTitle("אפשרויות")
                    .setItems(
                            new String[] {"עדכוני האפליקציה בטלפון", "מדריך התקנה"},
                            (d, which) -> {
                                if (which == 0) phoneUpdates();
                                else guide();
                            })
                    .show();
            return;
        }
        String[] items = {
            "החלפת טלוויזיה",
            "מכשירים מורשים",
            "בקשת שיתוף בטלוויזיה",
            "חיבור שליטה נוספת",
            "ניתוק שליטה נוספת",
            "מדריך התקנה",
            "עדכוני האפליקציה בטלפון",
            "עדכוני קרוב בטלוויזיה"
        };
        new AlertDialog.Builder(this)
                .setTitle("אפשרויות")
                .setItems(
                        items,
                        (d, which) -> {
                            switch (which) {
                                case 0 -> home();
                                case 1 -> controllers();
                                case 2 -> action("requestCapture", new JSONObject());
                                case 3 ->
                                        new AlertDialog.Builder(this)
                                                .setTitle("שליטה נוספת")
                                                .setMessage(
                                                        "האפשרות דורשת הכנה חד־פעמית של ניפוי"
                                                                + " שגיאות ברשת בסטרימר ואישור על"
                                                                + " הטלוויזיה. להפעיל את החיבור"
                                                                + " המקומי?")
                                                .setPositiveButton(
                                                        "הפעלה",
                                                        (a, b) ->
                                                                action(
                                                                        "adbEnable",
                                                                        new JSONObject()))
                                                .setNegativeButton("ביטול", null)
                                                .show();
                                case 4 -> action("adbDisable", new JSONObject());
                                case 5 -> guide();
                                case 6 -> phoneUpdates();
                                case 7 -> tvUpdates();
                            }
                        })
                .show();
    }

    private void phoneUpdates() {
        startActivity(new Intent(this, org.yosef.karov.updates.UpdateActivity.class));
    }

    private void tvUpdates() {
        TextView message = text("בודקים את מצב הסטרימר…", 16, Color.WHITE);
        message.setPadding(dp(24), dp(20), dp(24), dp(20));
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("עדכוני קרוב בטלוויזיה")
                        .setView(message)
                        .setPositiveButton("בדיקה והורדה", null)
                        .setNeutralButton("התקנת עדכון", null)
                        .setNegativeButton("סגירה", null)
                        .create();
        Runnable poll =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!resumed || !dialog.isShowing()) return;
                        RemoteClient active = connection;
                        if (active == null || !connected) {
                            message.setText("ממתינים לחיבור מחדש לסטרימר…");
                            main.postDelayed(this, 2000);
                            return;
                        }
                        active.call("updateInfo", new JSONObject(), 10000)
                                .whenComplete((value, error) ->
                                        main.post(() -> {
                                            if (!resumed || !dialog.isShowing()) return;
                                            if (connection == active
                                                    && error == null
                                                    && value instanceof JSONObject info) {
                                                message.setText(
                                                        "גרסה מותקנת: "
                                                                + info.optString("version")
                                                                + "\n\n"
                                                                + info.optString("message")
                                                                + "\n\n"
                                                                + "ההתקנה תנתק לרגע את העזרה."
                                                                + " הזיווג יישמר והחיבור יתחדש"
                                                                + " אוטומטית.");
                                                boolean idle =
                                                        !info.optBoolean("working")
                                                                && !info.optString("state")
                                                                        .equals("installing");
                                                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                                        .setEnabled(idle);
                                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                                                        .setEnabled(
                                                                idle
                                                                        && !info.optString("latest")
                                                                                .isEmpty()
                                                                        && !info.optString("state")
                                                                                .equals("current"));
                                            } else
                                                message.setText(
                                                        "מצב העדכון אינו זמין. גרסאות ישנות דורשות"
                                                                + " התקנה ראשונה של APK הכולל"
                                                                + " עדכונים.");
                                            main.postDelayed(this, 2000);
                                        }));
                    }
                };
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> action("updateCheck", new JSONObject()));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> action("updateInstall", new JSONObject()));
            poll.run();
        });
        dialog.setOnDismissListener(d -> main.removeCallbacks(poll));
        dialog.show();
    }

    private void controllers() {
        RemoteClient active = connection;
        if (active == null) return;
        active.call("controllers", new JSONObject(), 10000)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (!resumed || connection != active) return;
                            if (error != null || !(value instanceof JSONArray)) {
                                toast(explain(error));
                                return;
                            }
                            JSONArray list = (JSONArray) value;
                            String[] names = new String[list.length()];
                            for (int i = 0; i < names.length; i++)
                                names[i] = list.optJSONObject(i).optString("name");
                            new AlertDialog.Builder(this)
                                    .setTitle("ביטול הרשאה למכשיר")
                                    .setItems(
                                            names,
                                            (d, index) -> {
                                                String id =
                                                        list.optJSONObject(index).optString("id");
                                                new AlertDialog.Builder(this)
                                                        .setTitle("לבטל גישה?")
                                                        .setMessage(names[index])
                                                        .setPositiveButton(
                                                                "ביטול גישה",
                                                                (a, b) -> {
                                                                    active.call(
                                                                                    "revoke",
                                                                                    object(
                                                                                            "controller",
                                                                                            id),
                                                                                    10000)
                                                                            .whenComplete(
                                                                                    (result,
                                                                                            failure) ->
                                                                                            main
                                                                                                    .post(
                                                                                                            () -> {
                                                                                                                if (connection
                                                                                                                                != active
                                                                                                                        || !resumed)
                                                                                                                    return;
                                                                                                                if (failure
                                                                                                                        != null)
                                                                                                                    toast(
                                                                                                                            explain(
                                                                                                                                    failure));
                                                                                                                else if (selected
                                                                                                                                != null
                                                                                                                        && id
                                                                                                                                .equals(
                                                                                                                                        selected
                                                                                                                                                .optString(
                                                                                                                                                        "controller"))) {
                                                                                                                    try {
                                                                                                                        vault
                                                                                                                                .remove(
                                                                                                                                        selected
                                                                                                                                                .optString(
                                                                                                                                                        "device"));
                                                                                                                    } catch (
                                                                                                                            Exception
                                                                                                                                    ignored) {
                                                                                                                    }
                                                                                                                    home();
                                                                                                                } else
                                                                                                                    toast(
                                                                                                                            "ההרשאה"
                                                                                                                                + " בוטלה");
                                                                                                            }));
                                                                })
                                                        .setNegativeButton("חזרה", null)
                                                        .show();
                                            })
                                    .setNegativeButton("סגירה", null)
                                    .show();
                        }));
    }

    private void forget(String id, String name) {
        new AlertDialog.Builder(this)
                .setTitle("להסיר את " + name + "?")
                .setMessage(
                        "הזיווג יוסר מהטלפון הזה. לביטול ההרשאה בטלוויזיה עצמה משתמשים במכשירים"
                                + " מורשים.")
                .setPositiveButton(
                        "הסרה מהטלפון",
                        (d, w) -> {
                            try {
                                vault.remove(id);
                                render();
                            } catch (Exception error) {
                                toast(explain(error));
                            }
                        })
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void guide() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Pairing.SERVER + "/setup.html")));
    }

    private void toast(String message) {
        if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String reason(Throwable error) {
        if (error == null) return "FAILED";
        while (error.getCause() != null) error = error.getCause();
        return error instanceof TimeoutException
                ? "TIMEOUT"
                : error.getMessage() == null ? "FAILED" : error.getMessage();
    }

    private static String explain(Throwable error) {
        return switch (reason(error)) {
            case "OFFLINE" -> "הסטרימר אינו מחובר. מנסים שוב אוטומטית…";
            case "BUSY" -> "מכשיר אחר עוזר כרגע. מסיימים שם את העזרה ומנסים שוב.";
            case "DENIED", "EXPIRED" -> "הזיווג אינו תקף. פותחים זיווג חדש בטלוויזיה.";
            case "BAD_CODE" -> "מזינים את שמונה הספרות שמופיעות בטלוויזיה.";
            case "CODE_DENIED" ->
                    "הקוד לא התקבל. בדקו את הספרות. אחרי חמישה ניסיונות פותחים קוד חדש או סורקים"
                            + " QR.";
            case "CODE_NOT_FOUND" -> "הקוד אינו זמין. בדקו את הספרות ופתחו זיווג חדש בטלוויזיה.";
            case "CODE_LIMIT", "BLOCKED" -> "בוצעו יותר מדי ניסיונות. המתינו מעט ונסו שוב.";
            case "BAD_PAIRING", "BAD_IDENTITY", "BAD_HANDSHAKE" ->
                    "לא ניתן לאמת את הזיווג. סורקים שוב את ה-QR של קרוב בטלוויזיה.";
            case "NO_ACCESSIBILITY" -> "יש להשלים את הרשאת הנגישות של קרוב בסטרימר.";
            case "CAPTURE_PERMISSION" ->
                    "נדרש אישור שיתוף מסך בטלוויזיה. בקשת שיתוף נמצאת באפשרויות.";
            case "CAPTURE_BUSY" -> "ממתינים לתמונה מעודכנת…";
            case "SECURE_CONTENT" -> "התוכן מוגן מצילום. אפשר להמשיך להשתמש בשלט.";
            case "NO_TEXT_FIELD" -> "פותחים קודם שדה חיפוש בטלוויזיה.";
            case "UNSUPPORTED" -> "הפעולה אינה זמינה במסך או בדגם הזה.";
            case "STORAGE_UNAVAILABLE" -> "לא ניתן לשמור את הזיווג בטלפון.";
            case "CANCELLED" -> "הזיווג הופסק כשהאפליקציה עברה לרקע. אפשר לנסות שוב.";
            case "NETWORK", "DISCONNECTED", "REQUEST_TIMEOUT", "CONNECT_TIMEOUT", "TIMEOUT" ->
                    "החיבור מתעכב. בודקים את האינטרנט ומנסים להתחבר מחדש…";
            default -> "לא ניתן לבצע את הפעולה כרגע. נסו שוב.";
        };
    }
}
