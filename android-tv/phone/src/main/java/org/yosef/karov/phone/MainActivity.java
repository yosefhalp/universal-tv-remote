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
    private static final int BG = Color.rgb(13, 16, 22),
            PANEL = Color.rgb(25, 30, 40),
            LINE = Color.rgb(56, 59, 68),
            MUTED = Color.rgb(178, 182, 192),
            ACCENT = Color.rgb(108, 169, 255);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService tasks = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private PhoneVault vault;
    private AccountClient accounts;
    private volatile boolean accountBusy;
    private boolean accountSyncSupported;
    private JSONObject selected;
    private RemoteClient connection;
    private volatile RemoteClient pairingConnection;
    private LinearLayout content;
    private TextView status, screenMessage;
    private PointerSurface screen, fullScreen;
    private JSONObject frameMetadata;
    private boolean pointerSupported, pointerBusy, relativeMode, dragArmed;
    private ImageButton directModeButton, padModeButton, dragButton;
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
            accounts = new AccountClient(vault);
            relativeMode = getPreferences(MODE_PRIVATE).getBoolean("pointerRelative", false);
            selected = vault.get(vault.last());
            if (selected == null) selected = vault.accountDevice(vault.last());
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
        refreshAccount(false);
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
        if (accounts != null) accounts.close();
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

    private void control(View button) {
        controls.add(button);
        button.setEnabled(connected);
        button.setAlpha(connected ? 1 : 0.4f);
    }

    private void render() {
        if (vault == null || destroyed) return;
        if (selected != null) {
            renderCompact();
            return;
        }
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
    }

    private void renderHome() {
        add(text("המסכים שלי", 32, Color.WHITE), 4);
        add(text("נכנסים לטלוויזיה. עוזרים מכל מקום.", 16, MUTED), 8);
        add(button("החשבון שלי · כניסה וסנכרון", false, this::accountDialog), 16);
        try {
            JSONArray devices = vault.devices();
            JSONArray cloud = vault.accountDevices();
            for (int i = 0; i < cloud.length(); i++) {
                JSONObject item = cloud.getJSONObject(i);
                if (vault.get(item.getString("device")) == null) devices.put(item);
            }
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

    private ImageButton icon(String name, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(new RemoteIcon(name, Color.WHITE));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int inset = Math.max(dp(4), (Math.min(r - l, b - t) - dp(24)) / 2);
            if (v.getPaddingLeft() != inset) v.setPadding(inset, inset, inset, inset);
        });
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setBackground(touchSurface(PANEL, 14));
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return button;
    }

    private void iconCell(LinearLayout row, View button, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
    }

    private void renderCompact() {
        controls.clear();
        boolean landscape =
                getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
        int unit =
                landscape ? (getResources().getConfiguration().screenHeightDp < 360 ? 36 : 40) : 48;
        int headerHeight = landscape ? 36 : 44;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout header = row();
        header.setPadding(dp(12), dp(4), dp(12), 0);
        ImageButton exit = icon("close", "סיום עזרה", this::home);
        exit.setBackground(touchSurface(BG, 24));
        header.addView(exit, new LinearLayout.LayoutParams(dp(44), dp(headerHeight)));
        TextView title =
                text(selected.optString("name", "הטלוויזיה"), landscape ? 15 : 18, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status =
                text(
                        connected ? "● מחוברים לטלוויזיה" : "מתחברים לטלוויזיה…",
                        landscape ? 10 : 12,
                        connected ? Color.rgb(133, 209, 175) : MUTED);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (landscape) {
            LinearLayout caption = new LinearLayout(this);
            caption.setOrientation(LinearLayout.VERTICAL);
            caption.addView(title, new LinearLayout.LayoutParams(-1, dp(21)));
            caption.addView(status, new LinearLayout.LayoutParams(-1, dp(15)));
            header.addView(caption, new LinearLayout.LayoutParams(0, dp(headerHeight), 1));
        } else header.addView(title, new LinearLayout.LayoutParams(0, dp(headerHeight), 1));
        ImageButton more = icon("more", "אפשרויות", this::options);
        more.setBackground(touchSurface(BG, 24));
        header.addView(more, new LinearLayout.LayoutParams(dp(44), dp(headerHeight)));
        root.addView(header);
        if (!landscape) root.addView(status, new LinearLayout.LayoutParams(-1, dp(22)));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        body.setPadding(dp(12), dp(6), dp(12), dp(10));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout picture = new FrameLayout(this);
        picture.setBackground(shape(Color.BLACK, 18));
        picture.setClipToOutline(true);
        PointerSurface previousScreen = screen;
        screen = new PointerSurface(this, this::pointerRequest);
        if (frame != null) screen.copyPositionFrom(previousScreen);
        picture.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        screenMessage = text("ממתינים לתמונה…", 14, MUTED);
        screenMessage.setGravity(Gravity.CENTER);
        screenMessage.setPadding(dp(12), 0, dp(12), 0);
        picture.addView(screenMessage, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams pictureSize =
                landscape
                        ? new LinearLayout.LayoutParams(0, -1, 1)
                        : new LinearLayout.LayoutParams(-1, dp(180));
        LinearLayout viewer = new LinearLayout(this);
        viewer.setOrientation(LinearLayout.VERTICAL);
        viewer.addView(
                picture,
                landscape
                        ? new LinearLayout.LayoutParams(-1, 0, 1)
                        : new LinearLayout.LayoutParams(-1, -1));
        body.addView(viewer, pictureSize);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        body.addView(
                panel,
                landscape
                        ? new LinearLayout.LayoutParams(dp(290), -1)
                        : new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout modes = row();
        modes.setPadding(0, dp(6), 0, dp(4));
        directModeButton = icon("touch", "מגע ישיר", () -> pointerMode(false));
        padModeButton = icon("pad", "משטח מגע", () -> pointerMode(true));
        dragButton =
                icon(
                        "drag",
                        "גרירה במשטח המגע",
                        () -> {
                            dragArmed = !dragArmed;
                            refreshPointerMode();
                        });
        for (View v :
                new View[] {
                    directModeButton,
                    padModeButton,
                    icon("click", "לחיצה במקום הסמן", () -> screen.clickPointer()),
                    dragButton,
                    icon("expand", "מסך מלא", this::fullscreen)
                }) {
            control(v);
            iconCell(modes, v, unit);
        }
        panel.addView(modes);
        LinearLayout middle = row();
        middle.setGravity(Gravity.CENTER);
        middle.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout sound = new LinearLayout(this);
        sound.setOrientation(LinearLayout.VERTICAL);
        sound.setGravity(Gravity.CENTER);
        sound.setBackground(shape(PANEL, 25));
        for (String[] a :
                new String[][] {
                    {"plus", "הגברת קול", "VOLUME_UP"},
                    {"mute", "השתקה", "MUTE"},
                    {"minus", "הנמכת קול", "VOLUME_DOWN"}
                }) {
            ImageButton v = icon(a[0], a[1], () -> key(a[2]));
            v.setBackground(touchSurface(Color.TRANSPARENT, 24));
            control(v);
            sound.addView(v, new LinearLayout.LayoutParams(dp(unit), dp(unit)));
        }
        middle.addView(sound, new LinearLayout.LayoutParams(dp(unit), -2));
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(shape(PANEL, 120));
        ArrayList<View> padCells = new ArrayList<>();
        String[][] names = {{"", "up", ""}, {"left", "ok", "right"}, {"", "down", ""}};
        String[][] labels = {{"", "למעלה", ""}, {"שמאלה", "אישור", "ימינה"}, {"", "למטה", ""}};
        String[][] keys = {{"", "UP", ""}, {"LEFT", "OK", "RIGHT"}, {"", "DOWN", ""}};
        for (int r = 0; r < 3; r++) {
            LinearLayout line = row();
            line.setGravity(Gravity.CENTER);
            line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            for (int col = 0; col < 3; col++) {
                String key = keys[r][col];
                View v =
                        key.isEmpty()
                                ? new View(this)
                                : icon(names[r][col], labels[r][col], () -> key(key));
                if (!key.isEmpty()) {
                    control(v);
                    v.setBackground(
                            touchSurface(
                                    key.equals("OK") ? 0xff326ae6 : Color.TRANSPARENT,
                                    key.equals("OK") ? 24 : 30));
                }
                padCells.add(v);
                line.addView(v, new LinearLayout.LayoutParams(dp(unit), dp(unit)));
            }
            pad.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout.LayoutParams padSize =
                new LinearLayout.LayoutParams(dp(unit * 3), dp(unit * 3));
        padSize.setMargins(dp(12), 0, dp(12), 0);
        middle.addView(pad, padSize);
        LinearLayout wheel = new LinearLayout(this);
        wheel.setOrientation(LinearLayout.VERTICAL);
        wheel.setGravity(Gravity.CENTER);
        wheel.setBackground(shape(PANEL, 25));
        ImageButton up = icon("scrollUp", "גלילה למעלה", () -> screen.scrollPointer(0, -.65f));
        ImageButton down = icon("scrollDown", "גלילה למטה", () -> screen.scrollPointer(0, .65f));
        for (ImageButton v : new ImageButton[] {up, down}) {
            v.setBackground(touchSurface(Color.TRANSPARENT, 24));
            control(v);
            wheel.addView(v, new LinearLayout.LayoutParams(dp(unit), dp(unit)));
        }
        middle.addView(wheel, new LinearLayout.LayoutParams(dp(unit), -2));
        panel.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));
        // כפתורי הניגון נשארים גלויים גם כאשר האפליקציה בטלוויזיה מסתירה את התמונה.
        LinearLayout playback = row();
        playback.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (String[] item :
                new String[][] {
                    {"rewind", "הרצה לאחור", "REWIND"},
                    {"playPause", "ניגון או השהיה", "PLAY_PAUSE"},
                    {"forward", "הרצה קדימה", "FAST_FORWARD"}
                }) {
            ImageButton button = icon(item[0], item[1], () -> key(item[2]));
            control(button);
            iconCell(playback, button, unit);
        }
        if (landscape) viewer.addView(playback);
        else panel.addView(playback);
        LinearLayout navigation = row();
        navigation.setPadding(0, dp(4), 0, 0);
        ImageButton[] nav = {
            icon("back", "חזרה", () -> key("BACK")),
            icon("home", "בית", () -> key("HOME")),
            icon("apps", "אפליקציות", this::applications),
            icon("keyboard", "כתיבה בטלוויזיה", this::keyboard)
        };
        for (ImageButton v : nav) {
            control(v);
            iconCell(navigation, v, unit);
        }
        panel.addView(navigation);
        // התמונה שומרת על יחס הטלוויזיה. שטח נוסף מגדיל את השלט והריווח במקום ליצור שוליים שחורים
        // ענקיים.
        body.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!landscape) {
                int wanted =
                        Math.max(
                                dp(80),
                                Math.min((r - l - dp(24)) * 9 / 16, b - t - dp(284 + unit)));
                if (pictureSize.height != wanted) {
                    pictureSize.height = wanted;
                    viewer.setLayoutParams(pictureSize);
                }
            }
        });
        middle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int cell =
                    Math.max(
                            dp(30),
                            Math.min(
                                    dp(68),
                                    Math.min(
                                            (r - l - dp(unit * 2 + 24)) / 3,
                                            (b - t - dp(12)) / 3)));
            if (padSize.width != cell * 3) {
                padSize.width = padSize.height = cell * 3;
                pad.setLayoutParams(padSize);
                for (View child : padCells) {
                    child.setLayoutParams(new LinearLayout.LayoutParams(cell, cell));
                    if (child instanceof ImageButton)
                        child.setPadding(
                                (cell - dp(24)) / 2,
                                (cell - dp(24)) / 2,
                                (cell - dp(24)) / 2,
                                (cell - dp(24)) / 2);
                }
            }
        });
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
        if (frame != null && !paused) {
            screen.setImageBitmap(frame);
            if (frameMetadata != null) screen.geometry(frameMetadata);
            screenMessage.setVisibility(View.GONE);
        }
        setConnected(connected);
        refreshPointerMode();
    }

    private void pointerMode(boolean relative) {
        relativeMode = relative;
        dragArmed = false;
        getPreferences(MODE_PRIVATE).edit().putBoolean("pointerRelative", relative).apply();
        refreshPointerMode();
        toast(
                relative
                        ? "משטח מגע: הזזה באצבע, נגיעה ללחיצה ושתי אצבעות לגלילה"
                        : "מגע ישיר: לחיצה וגרירה על התמונה; שתי אצבעות לגלילה");
    }

    private void refreshPointerMode() {
        if (screen != null) {
            screen.mode(relativeMode);
            screen.drag(dragArmed);
            screen.allowed(connected && pointerSupported && !paused);
        }
        if (fullScreen != null) {
            fullScreen.mode(relativeMode);
            fullScreen.drag(dragArmed);
            fullScreen.allowed(connected && pointerSupported && !paused);
        }
        if (directModeButton != null) {
            directModeButton.setSelected(!relativeMode);
            directModeButton.setBackground(touchSurface(!relativeMode ? 0xff203d69 : PANEL, 14));
        }
        if (padModeButton != null) {
            padModeButton.setSelected(relativeMode);
            padModeButton.setBackground(touchSurface(relativeMode ? 0xff203d69 : PANEL, 14));
        }
        if (dragButton != null) {
            dragButton.setSelected(dragArmed);
            dragButton.setBackground(touchSurface(dragArmed ? 0xff203d69 : PANEL, 14));
            dragButton.setEnabled(connected && pointerSupported && relativeMode);
            dragButton.setAlpha(dragButton.isEnabled() ? 1 : .35f);
        }
    }

    private void pointerRequest(JSONObject args) {
        RemoteClient active = connection;
        if (active == null
                || !connected
                || paused
                || !pointerSupported
                || pointerBusy
                || lastFrame == 0
                || SystemClock.elapsedRealtime() - lastFrame > 4000) return;
        pointerBusy = true;
        active.call("pointer", args, 5000)
                .whenComplete((value, error) ->
                        main.post(() -> {
                            if (connection != active) return;
                            pointerBusy = false;
                            if (!resumed) return;
                            if (error != null) toast(explain(error));
                        }));
    }

    private void select(String id) {
        if (pairBusy) return;
        try {
            selected = vault.get(id);
            if (selected == null) selected = vault.accountDevice(id);
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
        pointerSupported = false;
        pointerBusy = false;
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
                        target.optBoolean("account") ? accountToken() : null,
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
                            pointerSupported = info.optBoolean("pointer");
                            accountSyncSupported = info.optBoolean("accountSync");
                            setConnected(true);
                            refreshPointerMode();
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
        if (reason.equals("ACCOUNT_DENIED")) {
            home();
            refreshAccount(true);
            return;
        }
        status.setText(explain(error));
        status.setTextColor(MUTED);
        boolean retry =
                !Arrays.asList("DENIED", "EXPIRED", "BLOCKED", "BAD_IDENTITY", "ACCOUNT_DENIED")
                                .contains(reason)
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
            frameMetadata = value;
            screen.geometry(value);
            screen.allowed(connected && pointerSupported && !paused);
            screen.setImageBitmap(bitmap);
            screenMessage.setVisibility(View.GONE);
            if (fullScreen != null) {
                fullScreen.geometry(value);
                fullScreen.allowed(connected && pointerSupported && !paused);
                fullScreen.setImageBitmap(bitmap);
            }
        } catch (Exception error) {
            clearFrame("לא ניתן להציג את התמונה כרגע");
        }
    }

    private void clearFrame(String message) {
        // כשאין תמונה, חוזרים לשלט כדי שכפתורי הניגון לא יוסתרו מאחורי מסך מלא שחור.
        if (fullDialog != null) fullDialog.dismiss();
        frame = null;
        frameMetadata = null;
        lastFrame = 0;
        if (screen != null) screen.allowed(false);
        if (fullScreen != null) fullScreen.allowed(false);
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
        if (pairBusy || accountBusy) return;
        pairBusy = true;
        int attempt = ++pairEpoch;
        render();
        tasks.execute(() -> {
            RemoteClient temporary = null;
            JSONObject record = null;
            boolean enrollmentSent = false;
            boolean accountPair = false;
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
                JSONObject prepared = null;
                accountPair = accountToken() != null;
                if (accountPair) {
                    prepared = accounts.prepare(pairing.device, pairing.name);
                    if (prepared.optBoolean("linked")) {
                        accounts.refresh();
                        String device = pairing.device;
                        main.post(() -> {
                            if (!destroyed && resumed && pairEpoch == attempt) {
                                pairBusy = false;
                                select(device);
                            }
                        });
                        return;
                    }
                }
                temporary =
                        new RemoteClient(
                                pairing.device, "pair", pairing.secret, pairing.code, reason -> {});
                pairingConnection = temporary;
                JSONObject pairingInfo = temporary.connect().get(15, TimeUnit.SECONDS);
                if (accountPair && !pairingInfo.optBoolean("accountSync"))
                    throw new Exception("ACCOUNT_UPDATE_REQUIRED");
                if (!resumed || pairEpoch != attempt) throw new Exception("CANCELLED");
                String id = accountPair ? prepared.getString("controller") : Crypto.id();
                String secret = accountPair ? prepared.getString("secret") : Crypto.secret();
                record =
                        new JSONObject()
                                .put("device", pairing.device)
                                .put("controller", id)
                                .put("secret", secret)
                                .put("name", pairing.name)
                                .put("pending", true);
                // זיווג מקומי נשמר לפני שליחה. הרשאת חשבון כבר נשמרה בהכנה בשרת ונשלפת בסנכרון.
                if (!accountPair) vault.put(record);
                JSONObject enrollment =
                        new JSONObject()
                                .put("controller", id)
                                .put("secret", secret)
                                .put("name", accountPair ? "חשבון קרוב" : "אפליקציית קרוב בטלפון");
                if (accountPair) enrollment.put("accountTicket", prepared.getString("ticket"));
                enrollmentSent = true;
                Exception enrollmentFailure = null;
                try {
                    temporary.call("enroll", enrollment, 12000).get(14, TimeUnit.SECONDS);
                } catch (Exception error) {
                    enrollmentFailure = error;
                }
                if (accountPair) {
                    accounts.refresh();
                    if (vault.accountDevice(pairing.device) == null)
                        throw enrollmentFailure == null
                                ? new Exception("ACCOUNT_LINK_FAILED")
                                : enrollmentFailure;
                } else {
                    if (enrollmentFailure != null) throw enrollmentFailure;
                    record.put("pending", false);
                    vault.put(record);
                }
                temporary.close();
                temporary = null;
                String device = pairing.device;
                main.post(() -> {
                    if (!destroyed && resumed && pairEpoch == attempt) {
                        pairBusy = false;
                        select(device);
                    }
                });
            } catch (Exception error) {
                if (record != null
                        && !accountPair
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
        fullScreen = new PointerSurface(this, this::pointerRequest);
        fullScreen.copyPositionFrom(screen);
        if (frameMetadata != null) fullScreen.geometry(frameMetadata);
        fullScreen.mode(relativeMode);
        fullScreen.drag(dragArmed);
        fullScreen.allowed(connected && pointerSupported && !paused);
        fullScreen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullScreen.setImageBitmap(frame);
        FrameLayout.LayoutParams imageArea = new FrameLayout.LayoutParams(-1, -1);
        imageArea.bottomMargin = dp(64);
        box.addView(fullScreen, imageArea);
        // Android עלול להחזיר תמונה שחורה בלי שגיאה; הניגון נשאר נגיש גם במסך מלא.
        LinearLayout playback = row();
        playback.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ArrayList<View> playbackControls = new ArrayList<>();
        for (String[] item :
                new String[][] {
                    {"rewind", "הרצה לאחור", "REWIND"},
                    {"playPause", "ניגון או השהיה", "PLAY_PAUSE"},
                    {"forward", "הרצה קדימה", "FAST_FORWARD"}
                }) {
            ImageButton button = icon(item[0], item[1], () -> key(item[2]));
            control(button);
            playbackControls.add(button);
            iconCell(playback, button, 48);
        }
        FrameLayout.LayoutParams playbackArea =
                new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        playbackArea.setMargins(dp(16), 0, dp(16), dp(4));
        box.addView(playback, playbackArea);
        ImageButton close = icon("close", "סגירת מסך מלא", dialog::dismiss);
        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        p.setMargins(dp(16), dp(24), dp(16), dp(16));
        box.addView(close, p);
        dialog.setContentView(box);
        dialog.setOnDismissListener(d -> {
            controls.removeAll(playbackControls);
            if (screen != null) screen.copyPositionFrom(fullScreen);
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

    private interface AccountWork {
        void run() throws Exception;
    }

    private String accountToken() {
        try {
            return accounts.token();
        } catch (Exception error) {
            return null;
        }
    }

    // כל עבודת הרשת בתור הרקע; כישלון רשת משאיר את המטמון המוצפן זמין.
    private void accountWork(AccountWork work, Runnable success) {
        if (accountBusy || pairBusy || destroyed) return;
        accountBusy = true;
        tasks.execute(() -> {
            Exception failure = null;
            try {
                work.run();
            } catch (Exception error) {
                failure = error;
            }
            Exception result = failure;
            // מעבר לרקע מסיר callbacks של המסך, ולכן מצב העבודה משתחרר גם בלי callback לתצוגה.
            accountBusy = false;
            main.post(() -> {
                if (destroyed) return;
                reconcileAccount();
                if (result != null) toast(explain(result));
                else if (resumed) success.run();
            });
        });
    }

    private void reconcileAccount() {
        try {
            JSONObject session = vault.accountSession();
            if (selected != null && selected.optBoolean("account")) {
                JSONObject current = vault.accountDevice(selected.optString("device"));
                if (session == null
                        || current == null
                        || !current.optString("controller")
                                .equals(selected.optString("controller"))) home();
            }
            if (selected == null && resumed) render();
        } catch (Exception error) {
            toast(explain(error));
        }
    }

    private void refreshAccount(boolean show) {
        if (accounts == null || accountToken() == null || accountBusy) return;
        accountWork(
                () -> accounts.refresh(),
                () -> {
                    if (show) accountDialog();
                    else {
                        try {
                            JSONObject session = vault.accountSession();
                            if (session != null
                                    && session.getJSONObject("user")
                                            .optBoolean("mustChangePassword")) passwordDialog(true);
                        } catch (Exception error) {
                            toast(explain(error));
                        }
                    }
                });
    }

    private LinearLayout accountForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        return form;
    }

    private EditText accountField(LinearLayout form, String hint, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (password
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_NORMAL));
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(password ? 128 : 32)});
        form.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));
        return field;
    }

    private void accountDialog() {
        if (accountBusy || pairBusy) {
            toast("ממתינים לסיום הפעולה…");
            return;
        }
        try {
            JSONObject session = vault.accountSession();
            if (session == null) {
                new AlertDialog.Builder(this)
                        .setTitle("החשבון שלי")
                        .setMessage("הטלוויזיות בחשבון זמינות בכל טלפון וטאבלט לאחר הכניסה.")
                        .setPositiveButton("כניסה", (d, w) -> credentialsDialog(false, false))
                        .setNeutralButton(
                                "הרשמה",
                                (d, w) -> {
                                    final boolean[] setup = {false};
                                    accountWork(
                                            () ->
                                                    setup[0] =
                                                            accounts.request(
                                                                            "GET", "/status", null,
                                                                            false)
                                                                    .getBoolean("setupRequired"),
                                            () -> credentialsDialog(true, setup[0]));
                                })
                        .setNegativeButton("סגירה", null)
                        .show();
                return;
            }
            JSONObject user = session.getJSONObject("user");
            if (user.optBoolean("mustChangePassword")) {
                passwordDialog(true);
                return;
            }
            boolean admin = user.optString("role").equals("admin");
            ArrayList<String> items =
                    new ArrayList<>(
                            Arrays.asList("סנכרון טלוויזיות", "הטלוויזיות בחשבון", "החלפת סיסמה"));
            if (admin) items.add("ניהול משתמשים");
            items.add("יציאה מהחשבון");
            new AlertDialog.Builder(this)
                    .setTitle("החשבון של " + user.optString("username"))
                    .setItems(
                            items.toArray(new String[0]),
                            (d, which) -> {
                                String action = items.get(which);
                                if (action.equals("סנכרון טלוויזיות")) refreshAccount(true);
                                else if (action.equals("הטלוויזיות בחשבון")) accountTelevisions();
                                else if (action.equals("החלפת סיסמה")) passwordDialog(false);
                                else if (action.equals("ניהול משתמשים")) adminUsers();
                                else
                                    accountWork(
                                            () -> accounts.logout(),
                                            () -> {
                                                reconcileAccount();
                                                accountDialog();
                                            });
                            })
                    .setNegativeButton("סגירה", null)
                    .show();
        } catch (Exception error) {
            toast(explain(error));
        }
    }

    private void credentialsDialog(boolean register, boolean setup) {
        LinearLayout form = accountForm();
        EditText username = accountField(form, "דוא״ל או שם משתמש", false);
        username.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        username.setTextDirection(View.TEXT_DIRECTION_LTR);
        EditText password = accountField(form, "סיסמה · לפחות 8 תווים", true);
        EditText installation =
                setup ? accountField(form, "סיסמת ההתקנה ליצירת מנהל ראשון", true) : null;
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(setup ? "יצירת מנהל ראשון" : register ? "הרשמה" : "כניסה")
                        .setView(form)
                        .setPositiveButton(register ? "הרשמה" : "כניסה", null)
                        .setNegativeButton("ביטול", null)
                        .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            if (accountBusy) return;
                            JSONObject input = object("username", username.getText().toString());
                            try {
                                input.put("password", password.getText().toString());
                                if (installation != null)
                                    input.put(
                                            "installationPassword",
                                            installation.getText().toString());
                            } catch (Exception error) {
                                toast(explain(error));
                                return;
                            }
                            accountWork(
                                    () -> accounts.authenticate(register, input),
                                    () -> {
                                        dialog.dismiss();
                                        accountDialog();
                                    });
                        }));
        dialog.show();
    }

    private void accountTelevisions() {
        try {
            JSONArray devices = vault.accountDevices();
            String[] names = new String[devices.length()];
            for (int i = 0; i < names.length; i++)
                names[i] = devices.getJSONObject(i).optString("name");
            new AlertDialog.Builder(this)
                    .setTitle("הטלוויזיות בחשבון")
                    .setItems(
                            names,
                            (d, which) -> {
                                JSONObject device = devices.optJSONObject(which);
                                new AlertDialog.Builder(this)
                                        .setTitle(device.optString("name"))
                                        .setPositiveButton(
                                                "התחברות",
                                                (a, b) -> {
                                                    try {
                                                        stopConnection();
                                                        selected =
                                                                vault.accountDevice(
                                                                        device.getString("device"));
                                                        vault.last(device.getString("device"));
                                                        paused = false;
                                                        reconnects = 0;
                                                        render();
                                                        if (resumed) connect();
                                                    } catch (Exception error) {
                                                        toast(explain(error));
                                                    }
                                                })
                                        .setNeutralButton(
                                                "הסרה מהחשבון",
                                                (a, b) ->
                                                        new AlertDialog.Builder(this)
                                                                .setTitle(
                                                                        "להסיר מכל המכשירים"
                                                                            + " שבחשבון?")
                                                                .setMessage(
                                                                        "זיווגים מקומיים נשארים"
                                                                            + " שמורים בטלפונים"
                                                                            + " שבהם נוצרו.")
                                                                .setPositiveButton(
                                                                        "הסרה",
                                                                        (c, e) ->
                                                                                accountWork(
                                                                                        () -> {
                                                                                            accounts
                                                                                                    .request(
                                                                                                            "DELETE",
                                                                                                            "/devices/"
                                                                                                                    + device
                                                                                                                            .getString(
                                                                                                                                    "device"),
                                                                                                            null,
                                                                                                            true);
                                                                                            accounts
                                                                                                    .refresh();
                                                                                        },
                                                                                        this
                                                                                                ::accountTelevisions))
                                                                .setNegativeButton("ביטול", null)
                                                                .show())
                                        .setNegativeButton("סגירה", null)
                                        .show();
                            })
                    .setNegativeButton("סגירה", null)
                    .show();
        } catch (Exception error) {
            toast(explain(error));
        }
    }

    private void passwordDialog(boolean forced) {
        LinearLayout form = accountForm();
        if (forced) form.addView(text("יש לבחור סיסמה אישית לפני הצגת הטלוויזיות.", 16, MUTED));
        EditText current = accountField(form, "הסיסמה הנוכחית", true);
        EditText next = accountField(form, "סיסמה חדשה · לפחות 8 תווים", true);
        EditText confirmation = accountField(form, "אימות הסיסמה החדשה", true);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("החלפת סיסמה")
                        .setView(form)
                        .setPositiveButton("שמירת סיסמה", null)
                        .setNegativeButton("סגירה", null)
                        .setNeutralButton(
                                "יציאה מהחשבון",
                                (d, w) -> accountWork(() -> accounts.logout(), this::accountDialog))
                        .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            if (!next.getText()
                                    .toString()
                                    .equals(confirmation.getText().toString())) {
                                confirmation.setError("הסיסמאות אינן זהות");
                                return;
                            }
                            String oldPassword = current.getText().toString(),
                                    newPassword = next.getText().toString();
                            accountWork(
                                    () -> accounts.password(oldPassword, newPassword),
                                    () -> {
                                        dialog.dismiss();
                                        accountDialog();
                                    });
                        }));
        dialog.show();
    }

    private void adminUsers() {
        final JSONArray[] users = {null};
        accountWork(
                () ->
                        users[0] =
                                accounts.request("GET", "/admin/users", null, true)
                                        .getJSONArray("users"),
                () -> {
                    String[] names = new String[users[0].length()];
                    for (int i = 0; i < names.length; i++) {
                        JSONObject user = users[0].optJSONObject(i);
                        names[i] =
                                user.optString("username")
                                        + (user.optBoolean("disabled") ? " · חסום" : "")
                                        + (user.optString("role").equals("admin") ? " · מנהל" : "");
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("ניהול משתמשים")
                            .setItems(
                                    names,
                                    (d, which) -> {
                                        JSONObject user = users[0].optJSONObject(which);
                                        try {
                                            if (user.optString("id")
                                                    .equals(
                                                            vault.accountSession()
                                                                    .getJSONObject("user")
                                                                    .optString("id"))) {
                                                passwordDialog(false);
                                                return;
                                            }
                                        } catch (Exception error) {
                                            toast(explain(error));
                                            return;
                                        }
                                        new AlertDialog.Builder(this)
                                                .setTitle(user.optString("username"))
                                                .setItems(
                                                        new String[] {
                                                            "איפוס סיסמה",
                                                            user.optBoolean("disabled")
                                                                    ? "הפעלת המשתמש"
                                                                    : "חסימת המשתמש"
                                                        },
                                                        (a, action) -> {
                                                            if (action == 0) resetUser(user);
                                                            else if (user.optString("role")
                                                                    .equals("admin"))
                                                                toast("לא ניתן לחסום מנהל");
                                                            else
                                                                new AlertDialog.Builder(this)
                                                                        .setTitle(
                                                                                "לעדכן את מצב"
                                                                                    + " המשתמש?")
                                                                        .setMessage(
                                                                                user.optString(
                                                                                        "username"))
                                                                        .setPositiveButton(
                                                                                "אישור",
                                                                                (b, c) ->
                                                                                        accountWork(
                                                                                                () ->
                                                                                                        accounts
                                                                                                                .request(
                                                                                                                        "POST",
                                                                                                                        "/admin/users/"
                                                                                                                                + user
                                                                                                                                        .getString(
                                                                                                                                                "id")
                                                                                                                                + "/state",
                                                                                                                        object(
                                                                                                                                "disabled",
                                                                                                                                !user
                                                                                                                                        .optBoolean(
                                                                                                                                                "disabled")),
                                                                                                                        true),
                                                                                                this
                                                                                                        ::adminUsers))
                                                                        .setNegativeButton(
                                                                                "ביטול", null)
                                                                        .show();
                                                        })
                                                .setNegativeButton("סגירה", null)
                                                .show();
                                    })
                            .setNegativeButton("סגירה", null)
                            .show();
                });
    }

    private void resetUser(JSONObject user) {
        LinearLayout form = accountForm();
        EditText password = accountField(form, "סיסמה זמנית · לפחות 8 תווים", true);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("איפוס סיסמת " + user.optString("username"))
                        .setMessage("המשתמש ינותק ויחויב להחליף את הסיסמה בכניסה הבאה.")
                        .setView(form)
                        .setPositiveButton("איפוס", null)
                        .setNegativeButton("ביטול", null)
                        .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            String temporary = password.getText().toString();
                            accountWork(
                                    () ->
                                            accounts.request(
                                                    "POST",
                                                    "/admin/users/"
                                                            + user.getString("id")
                                                            + "/reset",
                                                    object("password", temporary),
                                                    true),
                                    () -> {
                                        dialog.dismiss();
                                        adminUsers();
                                    });
                        }));
        dialog.show();
    }

    private void syncTelevision() {
        if (selected == null || !connected || connection == null) {
            toast("מתחברים קודם לטלוויזיה");
            return;
        }
        if (accountToken() == null) {
            accountDialog();
            return;
        }
        if (selected.optBoolean("account")) {
            refreshAccount(true);
            return;
        }
        if (!accountSyncSupported) {
            toast("יש לעדכן את קרוב בסטרימר כדי לסנכרן לחשבון. הזיווג המקומי נשמר.");
            return;
        }
        JSONObject target = selected;
        RemoteClient active = connection;
        new AlertDialog.Builder(this)
                .setTitle("סנכרון הטלוויזיה לחשבון")
                .setMessage("הטלוויזיה תהיה זמינה בכל טלפון וטאבלט המחוברים לחשבון שלך.")
                .setPositiveButton(
                        "סנכרון",
                        (d, w) ->
                                accountWork(
                                        () -> {
                                            JSONObject prepared =
                                                    accounts.prepare(
                                                            target.getString("device"),
                                                            target.getString("name"));
                                            if (!prepared.optBoolean("linked")) {
                                                Exception grantFailure = null;
                                                try {
                                                    active.call(
                                                                    "grantAccount",
                                                                    new JSONObject()
                                                                            .put(
                                                                                    "ticket",
                                                                                    prepared
                                                                                            .getString(
                                                                                                    "ticket"))
                                                                            .put(
                                                                                    "controller",
                                                                                    prepared
                                                                                            .getString(
                                                                                                    "controller"))
                                                                            .put(
                                                                                    "secret",
                                                                                    prepared
                                                                                            .getString(
                                                                                                    "secret"))
                                                                            .put(
                                                                                    "name",
                                                                                    "חשבון קרוב"),
                                                                    12000)
                                                            .get(14, TimeUnit.SECONDS);
                                                } catch (Exception error) {
                                                    grantFailure = error;
                                                }
                                                accounts.refresh();
                                                if (vault.accountDevice(target.getString("device"))
                                                        == null)
                                                    throw grantFailure == null
                                                            ? new Exception("ACCOUNT_LINK_FAILED")
                                                            : grantFailure;
                                            } else accounts.refresh();
                                        },
                                        () -> toast("הטלוויזיה מסונכרנת לחשבון")))
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void options() {
        if (selected == null) {
            new AlertDialog.Builder(this)
                    .setTitle("אפשרויות")
                    .setItems(
                            new String[] {"החשבון שלי", "עדכוני האפליקציה בטלפון", "מדריך התקנה"},
                            (d, which) -> {
                                if (which == 0) accountDialog();
                                else if (which == 1) phoneUpdates();
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
            "עדכוני קרוב בטלוויזיה",
            "הגדרות הטלוויזיה",
            "הרצה לאחור",
            "הרצה קדימה",
            paused ? "חידוש תמונה" : "השהיית תמונה",
            "החשבון שלי",
            "סנכרון הטלוויזיה לחשבון"
        };
        new AlertDialog.Builder(this)
                .setTitle("אפשרויות")
                .setItems(
                        items,
                        (d, which) -> {
                            switch (which) {
                                case 12 -> accountDialog();
                                case 13 -> syncTelevision();
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
                                case 8 -> key("SETTINGS");
                                case 9 -> key("REWIND");
                                case 10 -> key("FAST_FORWARD");
                                case 11 -> {
                                    paused = !paused;
                                    clearFrame(paused ? "התמונה מושהית" : "מחדשים תמונה…");
                                    if (!paused) capture(connection);
                                }
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
        try {
            if (vault.get(id) == null && vault.accountDevice(id) != null) {
                new AlertDialog.Builder(this)
                        .setTitle("להסיר מהחשבון?")
                        .setMessage(name + " יוסר מכל הטלפונים והטאבלטים המחוברים לחשבון.")
                        .setPositiveButton(
                                "הסרה",
                                (d, w) ->
                                        accountWork(
                                                () -> {
                                                    accounts.request(
                                                            "DELETE", "/devices/" + id, null, true);
                                                    accounts.refresh();
                                                },
                                                () -> {
                                                    if (selected != null
                                                            && id.equals(
                                                                    selected.optString("device")))
                                                        home();
                                                    else render();
                                                }))
                        .setNegativeButton("ביטול", null)
                        .show();
                return;
            }
        } catch (Exception error) {
            toast(explain(error));
            return;
        }
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
            case "ACCOUNT_UNAUTHORIZED", "ACCOUNT_DENIED", "UNAUTHORIZED", "INVALID_CREDENTIALS" ->
                    "הכניסה לחשבון אינה תקפה. יש להיכנס מחדש.";
            case "ACCOUNT_UPDATE_REQUIRED" -> "יש לעדכן את קרוב בסטרימר לפני חיבור לחשבון.";
            case "ACCOUNT_LINK_FAILED" -> "הטלוויזיה טרם נוספה לחשבון. נסו לסנכרן שוב.";
            case "MUST_CHANGE_PASSWORD", "PASSWORD_CHANGE_REQUIRED" ->
                    "יש להחליף את הסיסמה לפני שימוש בחשבון.";
            case "USERNAME_TAKEN", "USERNAME_EXISTS" -> "שם המשתמש כבר קיים. בחרו שם אחר.";
            case "BAD_USERNAME", "INVALID_USERNAME" ->
                    "יש להזין כתובת דוא״ל תקינה או שם משתמש באורך 3 עד 32 תווים.";
            case "BAD_PASSWORD", "INVALID_PASSWORD" -> "הסיסמה צריכה להכיל 8 עד 128 תווים.";
            case "INSTALLATION_REQUIRED",
                    "INSTALLATION_PASSWORD_REQUIRED",
                    "BOOTSTRAP_DENIED",
                    "INVALID_INSTALLATION_PASSWORD" ->
                    "יש להזין את סיסמת ההתקנה כדי ליצור מנהל ראשון.";
            case "LIMIT", "RATE_LIMITED", "TOO_MANY_REQUESTS" ->
                    "בוצעו יותר מדי ניסיונות. המתינו מעט ונסו שוב.";
            case "POINTER_BUSY" -> "המחווה הקודמת מסתיימת. נסו שוב בעוד רגע.";
            case "POINTER_CANCELLED" -> "Android הפסיק את המחווה. אפשר לנסות שוב.";
            case "STALE_GEOMETRY" -> "המסך שינה כיוון או גודל. המתינו לתמונה החדשה.";
            case "NO_POINTER" -> "יש להשלים נגישות או שליטה נוספת בסטרימר.";
            case "BAD_POINTER" -> "לא ניתן למפות את המיקום למסך הטלוויזיה.";
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
            case "SECURE_CONTENT" -> "התוכן מוגן מצילום. כפתורי השלט והניגון זמינים.";
            case "CAPTURE_FAILED" -> "התמונה אינה זמינה כרגע. כפתורי השלט והניגון זמינים.";
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
