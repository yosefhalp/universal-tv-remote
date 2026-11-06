package org.yosef.karov;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.Toast;

// חלון מעבר ללא מסך הגדרות: בקשת המערכת נסגרת בחזרה לתוכן שהיה פתוח בטלוויזיה.
public final class CaptureConsentActivity extends Activity {
    private static boolean requesting;

    public static boolean permanentConsent(Context context) {
        if (Build.VERSION.SDK_INT != 28) return false;
        try {
            // קריאה בלבד של ההרשאה שהמערכת שמרה בעקבות "לא להציג שוב" ב-Android 9.
            // אין כאן שינוי AppOps, מתן הרשאות, שימוש ב-root או עקיפה של חלון האישור.
            return context.getSystemService(AppOpsManager.class)
                            .checkOpNoThrow(
                                    "android:project_media",
                                    Process.myUid(),
                                    context.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static void request(Context context, boolean automatic) {
        if (ScreenService.available() || requesting) return;
        try {
            Vault vault = Vault.open(context);
            if (!vault.enabled()) return;
            if (automatic
                    && (!"true".equals(vault.get("restoreCapture")) || !permanentConsent(context)))
                return;
            requesting = true;
            context.startActivity(
                    new Intent(context, CaptureConsentActivity.class)
                            .putExtra("automatic", automatic)
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception unavailable) {
            requesting = false;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (!Vault.open(this).enabled()
                    || ScreenService.available()
                    || (getIntent().getBooleanExtra("automatic", false)
                            && !permanentConsent(this))) {
                finish();
                return;
            }
            if (state == null) {
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                Intent capture =
                        Build.VERSION.SDK_INT >= 34
                                ? manager.createScreenCaptureIntent(
                                        MediaProjectionConfig.createConfigForDefaultDisplay())
                                : manager.createScreenCaptureIntent();
                startActivityForResult(capture, 7);
            }
        } catch (Exception unavailable) {
            finish();
        }
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 7 && result == RESULT_OK && data != null) {
            try {
                Vault vault = Vault.open(this);
                if (vault.enabled()) {
                    vault.put("restoreCapture", String.valueOf(permanentConsent(this)));
                    startForegroundService(
                            new Intent(this, ScreenService.class).putExtra("captureData", data));
                }
            } catch (Exception unavailable) {
                Toast.makeText(this, "לא ניתן להתחיל את שיתוף המסך", Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    @Override
    public void onDestroy() {
        requesting = false;
        super.onDestroy();
    }
}
