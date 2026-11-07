package org.yosef.karov.updates;

import android.app.Activity;
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

public final class UpdateActivity extends Activity {
    private UpdateEngine engine;
    private TextView status;
    private Button install, check, permission;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean awaitingConsent;
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    renderStatus();
                    main.postDelayed(this, 750);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = UpdateEngine.get(this);
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
        automatic.setText("עדכון אוטומטי");
        automatic.setTextColor(Color.WHITE);
        automatic.setTextSize(18);
        automatic.setChecked(engine.automatic());
        automatic.setPadding(0, dp(18), 0, dp(18));
        automatic.setOnCheckedChangeListener((button, enabled) -> engine.automatic(enabled));
        content.addView(automatic);
        label(
                content,
                "הבדיקה מתבצעת מדי כמה שעות. בטלפון ההורדה האוטומטית ממתינה ל־Wi-Fi. העדכון שומר את"
                        + " כל הזיווגים וממתין לסיום עזרה פעילה.",
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
        install =
                button(
                        content,
                        "התקנת העדכון",
                        () -> {
                            awaitingConsent = true;
                            if (engine.confirmation() != null) continueConsent();
                            else engine.request(true, false, null);
                        });
        label(
                content,
                "Android חדש מאפשר בדרך כלל עדכון עצמי ללא אישור נוסף. ב־Android ישן או בקושחה"
                    + " שמגבילה התקנה ייתכן אישור מערכת אחד. בסטרימר, חיבור מקומי שכבר אושר יכול"
                    + " להתקין ללא האישור החוזר.",
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
    public void onResume() {
        super.onResume();
        main.post(refresh);
    }

    @Override
    public void onPause() {
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
        // גם כש-ADB זמין כדאי לאפשר את המתקין הרגיל, כדי שתהיה חלופה אם החיבור המקומי ייפול.
        permission.setVisibility(engine.permission() ? View.GONE : View.VISIBLE);
        if (awaitingConsent && engine.confirmation() != null) continueConsent();
    }

    private void continueConsent() {
        awaitingConsent = false;
        try {
            startActivity(engine.confirmation());
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
}
