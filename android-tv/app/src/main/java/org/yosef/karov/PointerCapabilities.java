package org.yosef.karov;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

public final class PointerCapabilities {
    private PointerCapabilities() {}

    public static boolean gesturesSupported(Context context) {
        return policy(
                Build.VERSION.SDK_INT,
                displayTouchscreen(context),
                context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FAKETOUCH));
    }

    public static int displayTouchscreen(Context context) {
        try {
            // זה אותו מסך ברירת מחדל שמשמש למדידת התמונה ב-PointerActions.geometry.
            WindowManager manager = context.getSystemService(WindowManager.class);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            if (display == null) return Configuration.TOUCHSCREEN_UNDEFINED;
            // אין לשמור את ההקשר: תצורת המסך עשויה להשתנות כשמחברים או מנתקים התקן קלט.
            return context.createDisplayContext(display)
                    .getResources()
                    .getConfiguration()
                    .touchscreen;
        } catch (RuntimeException ignored) {
            // תצורה שלא ניתן לקרוא אינה הוכחה למסך מגע. המדיניות של כל גרסה קובעת איך להמשיך.
            return Configuration.TOUCHSCREEN_UNDEFINED;
        }
    }

    static boolean policy(int sdk, int touchscreen, boolean fakeTouch) {
        // ב-AOSP של Android 9 ו-10 אין שער חומרת מגע ב-sendGesture. נשמר המסלול הקיים שעבד בהן.
        if (sdk < 30) return true;
        // Android 11 בודק Configuration של מסך היעד, ולא את רשימת תכונות החומרה של המכשיר.
        if (sdk == 30) return touchscreen == Configuration.TOUCHSCREEN_FINGER;
        // החל מ-Android 12 השער ב-AOSP מבוסס על FEATURE_FAKETOUCH, גם כשאין מסך מגע פיזי.
        return fakeTouch;
    }
}
