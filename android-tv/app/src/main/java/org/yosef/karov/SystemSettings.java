package org.yosef.karov;

import android.content.*;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.widget.Toast;

public final class SystemSettings {
    public static boolean open(Context context, boolean accessibility) {
        // פתיחת השורש גם אם בפעם הקודמת נשאר מסך משנה פתוח בתוך משימת ההגדרות.
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        Intent intent =
                new Intent(
                                accessibility
                                        ? Settings.ACTION_ACCESSIBILITY_SETTINGS
                                        : Settings.ACTION_SETTINGS)
                        .addFlags(flags);
        ResolveInfo target = context.getPackageManager().resolveActivity(intent, 0);
        // בחלק מתמונות Android TV הקיצור מצביע על רכיב ריק. במקרה כזה פותחים את תפריט ההגדרות
        // האמיתי.
        if (target == null || target.activityInfo.packageName.endsWith("frameworkpackagestubs")) {
            intent = new Intent(Settings.ACTION_SETTINGS).addFlags(flags);
            target = context.getPackageManager().resolveActivity(intent, 0);
            if (target == null || target.activityInfo.packageName.endsWith("frameworkpackagestubs"))
                intent.setComponent(
                        new ComponentName(
                                "com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            if (accessibility)
                Toast.makeText(context, "בחרו העדפות מכשיר ← נגישות ← קרוב", Toast.LENGTH_LONG)
                        .show();
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(context, "פתחו הגדרות דרך השלט ובחרו נגישות ← קרוב", Toast.LENGTH_LONG)
                    .show();
            return false;
        }
    }

    private SystemSettings() {}
}
