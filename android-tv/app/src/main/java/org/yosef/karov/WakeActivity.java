package org.yosef.karov;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;

// חלון קצר שמבקש יקיצה רק בתחילת עזרה מאומתת, ואז מחזיר את התוכן שהיה פתוח.
public final class WakeActivity extends Activity {
    private static boolean authorizedSession;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable close = this::finishAndRemoveTask;

    public static void session(Context context, boolean active) {
        authorizedSession = active;
        PowerManager power = context.getSystemService(PowerManager.class);
        if (!active || power == null || power.isInteractive()) return;
        try {
            if (Build.VERSION.SDK_INT < 33) {
                // בחלק מגרסאות TV ישנות חלון שקוף אינו מעיר את המסך. מנגנון היקיצה
                // הוותיק מוגבל כאן לגרסאות שבהן הוא נתמך, ופוקע מעצמו אחרי שנייה.
                PowerManager.WakeLock wake =
                        power.newWakeLock(
                                PowerManager.SCREEN_DIM_WAKE_LOCK
                                        | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                "karov:family-wake");
                wake.acquire(1000);
                return;
            }
            context.startActivity(
                    new Intent(context, WakeActivity.class)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException unavailable) {
            // קושחה שחוסמת פתיחת מסך ברקע עדיין מאפשרת את יתר פעולות העזרה.
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (!authorizedSession || !Vault.open(this).enabled()) {
                finishAndRemoveTask();
                return;
            }
        } catch (Exception unavailable) {
            finishAndRemoveTask();
            return;
        }
        // ה-API הרגיל מעיר את התצוגה; הוא אינו מבטל נעילת מכשיר או הגנת PIN.
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().setWindowAnimations(0);
        setContentView(new View(this));
    }

    @Override
    public void onResume() {
        super.onResume();
        // מאפשרים לחלון להצטייר כדי ש-Android תשלים את היקיצה לפני סגירתו.
        main.postDelayed(close, 400);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(close);
        super.onDestroy();
    }
}
