package org.yosef.karov.updates;

import android.content.pm.PackageInstaller;

// מדיניות ההתקנה נפרדת מהורדה ואימות: הסטרימר תמיד ממתין לבחירה מפורשת ולאישור Android.
final class UpdatePolicy {
    private UpdatePolicy() {}

    static boolean television(String applicationId) {
        return "org.yosef.karov".equals(applicationId)
                || "org.yosef.karov.debug".equals(applicationId);
    }

    static boolean automaticInstall(boolean television, boolean automatic) {
        return !television && automatic;
    }

    static boolean mayInstall(boolean television, boolean requested, boolean background) {
        return requested && !(television && background);
    }

    static boolean localInstall(boolean television) {
        return !television;
    }

    static int userAction(int sdk, boolean television) {
        if (sdk < 31) return PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED;
        return television
                ? PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                : PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED;
    }
}
