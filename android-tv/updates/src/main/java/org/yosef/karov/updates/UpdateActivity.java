package org.yosef.karov.updates;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class UpdateActivity extends Activity {
    private static final String INSTALL_REQUEST = "org.yosef.karov.updates.INSTALL_REQUEST";
    private static final String INSTALL_LAUNCH = "org.yosef.karov.updates.INSTALL_LAUNCH";
    private static final String AWAITING_CONSENT = "awaitingConsent";
    private static final String PENDING_INSTALL = "pendingInstall";
    private static final ConcurrentHashMap<String, InstallLaunch> launches =
            new ConcurrentHashMap<>();
    private static final ScheduledExecutorService launchDeadlines =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "karov-update-launch-deadline");
                worker.setDaemon(true);
                return worker;
            });
    private UpdateEngine engine;
    private TextView status;
    private Button install, check, permission;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean awaitingConsent, pendingInstall, resumed;
    private String pendingLaunch;
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    renderStatus();
                    main.postDelayed(this, 750);
                }
            };

    public static void openForInstall(Context context) {
        openForInstall(context, opened -> {});
    }

    public static void openForInstall(Context context, Consumer<Boolean> opened) {
        InstallLaunch launch = new InstallLaunch(context.getApplicationContext(), opened);
        launches.put(launch.id, launch);
        launch.deadline =
                launchDeadlines.schedule(() -> launch.resolve(false), 2500, TimeUnit.MILLISECONDS);
        try {
            context.startActivity(
                    new Intent(context, UpdateActivity.class)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(INSTALL_REQUEST, true)
                            .putExtra(INSTALL_LAUNCH, launch.id));
        } catch (RuntimeException ignored) {
            launch.resolve(false);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = UpdateEngine.get(this);
        if (state != null) {
            awaitingConsent = state.getBoolean(AWAITING_CONSENT, false);
            pendingInstall = state.getBoolean(PENDING_INSTALL, false);
            pendingLaunch = state.getString(INSTALL_LAUNCH);
        }
        consumeInstallIntent(getIntent(), state == null);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff111216);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(28), dp(32), dp(28), dp(30));
        scroll.addView(content);
        label(content, "עדכוני קרוב", 29);
        status = label(content, "", 18);
        Switch automatic = new Switch(this);
        automatic.setText(engine.television() ? "בדיקה והורדה אוטומטיות" : "עדכון אוטומטי");
        automatic.setTextColor(Color.WHITE);
        automatic.setTextSize(18);
        automatic.setChecked(engine.automatic());
        automatic.setPadding(0, dp(18), 0, dp(18));
        automatic.setOnCheckedChangeListener((button, enabled) -> engine.automatic(enabled));
        content.addView(automatic);
        label(
                content,
                engine.television()
                        ? "הבדיקה וההורדה מתבצעות מדי כמה שעות. התקנה אינה מתחילה ברקע: בוחרים"
                                + " להתקין ומאשרים בחלון Android בטלוויזיה. העדכון שומר את כל"
                                + " הזיווגים."
                        : "הבדיקה מתבצעת מדי כמה שעות. בטלפון ההורדה האוטומטית ממתינה ל־Wi-Fi."
                                + " העדכון שומר את כל הזיווגים וממתין לסיום עזרה פעילה.",
                15);
        permission =
                button(
                        content,
                        "אישור חד־פעמי לעדכונים",
                        () -> {
                            try {
                                startActivity(
                                        new Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:" + getPackageName())));
                            } catch (Exception error) {
                                Toast.makeText(
                                                this,
                                                "פתחו בהגדרות Android את התקנת האפליקציות ואפשרו את"
                                                        + " קרוב",
                                                Toast.LENGTH_LONG)
                                        .show();
                            }
                        });
        check = button(content, "בדיקת גרסה", () -> engine.request(false, false, null));
        install = button(content, "התקנת העדכון", this::requestInstall);
        label(
                content,
                engine.television()
                        ? "ההתקנה מתבצעת דרך המתקין הרשמי של Android. גם כששליטה נוספת זמינה, עדכון"
                                + " הסטרימר ממתין לאישור בחלון המערכת. אפשר לבטל בלי להסיר את הגרסה"
                                + " הקיימת."
                        : "Android חדש מאפשר בדרך כלל עדכון עצמי ללא אישור נוסף. ב־Android ישן או"
                                + " בקושחה שמגבילה התקנה ייתכן אישור מערכת אחד.",
                14);
        label(
                content,
                "אם Google Play Protect מבקש סריקה, משלימים אותה במסך המערכת. אין צורך להסיר את"
                        + " האפליקציה או לזווג מחדש.",
                14);
        button(content, "חזרה", this::finish);
        setContentView(scroll);
        // מסך העדכון נגיש גם בטלפון עם ניווט מחוות, בלי כפתורים מתחת לפסי המערכת.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeInstallIntent(intent, true);
        startPendingInstall();
    }

    private void consumeInstallIntent(Intent intent, boolean accept) {
        if (intent == null) return;
        boolean requested = intent.getBooleanExtra(INSTALL_REQUEST, false);
        String launch = intent.getStringExtra(INSTALL_LAUNCH);
        intent.removeExtra(INSTALL_REQUEST);
        intent.removeExtra(INSTALL_LAUNCH);
        if (accept && requested) {
            pendingInstall = true;
            awaitingConsent = true;
            pendingLaunch = launch;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(AWAITING_CONSENT, awaitingConsent);
        state.putBoolean(PENDING_INSTALL, pendingInstall);
        state.putString(INSTALL_LAUNCH, pendingLaunch);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        main.post(refresh);
        startPendingInstall();
    }

    @Override
    public void onPause() {
        resumed = false;
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void renderStatus() {
        JSONObject info = engine.info();
        String text =
                "גרסה מותקנת: " + info.optString("version") + "\n\n" + info.optString("message");
        if (!info.optString("notes").isEmpty()) text += "\n\n" + info.optString("notes");
        // אין לשלוח שוב אירועי נגישות כשהמצב לא השתנה, במיוחד בסטרימרים ישנים.
        if (!text.contentEquals(status.getText())) status.setText(text);
        boolean working =
                info.optBoolean("working") || info.optString("state").equals("installing");
        check.setEnabled(!working);
        install.setEnabled(
                !working
                        && !info.optString("latest").isEmpty()
                        && !info.optString("state").equals("current"));
        check.setAlpha(check.isEnabled() ? 1 : 0.4f);
        install.setAlpha(install.isEnabled() ? 1 : 0.4f);
        // הרשאת ההתקנה הראשונית אינה מחליפה את אישור העדכון בחלון Android.
        permission.setVisibility(engine.permission() ? View.GONE : View.VISIBLE);
        startPendingInstall();
        if (resumed && awaitingConsent && engine.confirmation() != null) continueConsent();
    }

    private void requestInstall() {
        // לחיצה ידנית היא אישור חדש של המשתמש ואינה תלויה בבקשת פתיחה מרחוק שפג תוקפה.
        pendingLaunch = null;
        pendingInstall = true;
        awaitingConsent = true;
        startPendingInstall();
    }

    private void startPendingInstall() {
        if (!resumed || !pendingInstall) return;
        if (pendingLaunch != null) {
            InstallLaunch launch = launches.get(pendingLaunch);
            pendingLaunch = null;
            if (launch == null || !launch.resolve(true)) {
                // מסך שנפתח באיחור אינו רשאי להתקין אחרי שהמבקש כבר קיבל כישלון.
                pendingInstall = false;
                awaitingConsent = false;
                return;
            }
        }
        if (engine.confirmation() != null) {
            pendingInstall = false;
            continueConsent();
            return;
        }
        JSONObject info = engine.info();
        if (info.optBoolean("working")) return;
        // בקשה מפורשת שהגיעה בזמן הורדה ממתינה לסיומה. סשן שכבר נשלח אינו משוגר שוב.
        pendingInstall = false;
        if (info.optString("state").equals("installing")) return;
        if (!engine.request(true, false, null)) pendingInstall = true;
    }

    private void continueConsent() {
        if (!resumed) return;
        Intent confirmation = engine.takeConfirmation();
        if (confirmation == null) return;
        // צורכים לפני פתיחת החלון ושומרים את הדגל בסיבוב מסך. חזרה מביטול אינה פותחת אותו שוב.
        awaitingConsent = false;
        pendingInstall = false;
        try {
            startActivity(confirmation);
        } catch (Exception error) {
            Toast.makeText(
                            this,
                            "האישור כבר אינו זמין. לחצו שוב על התקנת העדכון",
                            Toast.LENGTH_LONG)
                    .show();
        }
    }

    private TextView label(LinearLayout content, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        view.setPadding(0, dp(8), 0, dp(8));
        content.addView(view);
        return view;
    }

    private Button button(LinearLayout content, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, shape(0xffa64325));
        background.addState(new int[] {android.R.attr.state_pressed}, shape(0xffa64325));
        background.addState(new int[] {}, shape(0xff292b34));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.setMargins(0, dp(6), 0, dp(6));
        content.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class InstallLaunch {
        final String id = UUID.randomUUID().toString();
        final long expiresAt = SystemClock.elapsedRealtime() + 2500;
        final AtomicBoolean resolved = new AtomicBoolean();
        final Context context;
        final Consumer<Boolean> opened;
        volatile ScheduledFuture<?> deadline;

        InstallLaunch(Context context, Consumer<Boolean> opened) {
            this.context = context;
            this.opened = opened;
        }

        boolean resolve(boolean openedInForeground) {
            // השעון נבדק גם במסך עצמו: עומס בלולאת הממשק אינו מאריך הרשאת התקנה מרחוק.
            boolean success = openedInForeground && SystemClock.elapsedRealtime() < expiresAt;
            if (!resolved.compareAndSet(false, true)) return false;
            launches.remove(id, this);
            ScheduledFuture<?> timer = deadline;
            if (timer != null) timer.cancel(false);
            try {
                opened.accept(success);
            } catch (RuntimeException ignored) {
                /* כשל במקבל התשובה אינו משגר בקשה נוספת. */
            }
            if (!success)
                new Handler(Looper.getMainLooper())
                        .post(() -> UpdateEngine.get(context).installationScreenUnavailable());
            return success;
        }
    }
}
