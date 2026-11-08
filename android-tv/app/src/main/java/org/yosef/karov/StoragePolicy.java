package org.yosef.karov;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.storage.StorageManager;

import java.util.UUID;

// אפליקציית עזרה צריכה להישאר זמינה גם כשכונן USB מתנתק או משנה את אופן החיבור שלו.
final class StoragePolicy {
    static final String INTERNAL = "internal", EXTERNAL = "external", UNKNOWN = "unknown";
    static final String SETUP_ERROR = "APP_STORAGE_EXTERNAL";

    private StoragePolicy() {}

    static String location(Context context) {
        try {
            ApplicationInfo app = context.getApplicationInfo();
            UUID volume = app.storageUuid;
            return classify(
                    (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0,
                    volume != null,
                    StorageManager.UUID_DEFAULT.equals(volume),
                    app.sourceDir);
        } catch (RuntimeException unavailable) {
            return UNKNOWN;
        }
    }

    static boolean localAdbSetupAllowed(String location) {
        // חוסמים רק התקנה חיצונית מזוהה; חוסר מידע אצל יצרן אינו משנה חיבור קיים.
        return !EXTERNAL.equals(location);
    }

    static String classify(
            boolean externalFlag, boolean knownVolume, boolean internalVolume, String apkPath) {
        // נתיב מאומץ ודגל חיצוני קודמים ל-UUID, כדי לזהות גם דיווח חסר או סותר של קושחה.
        if (externalFlag
                || apkPath != null
                        && (apkPath.startsWith("/mnt/expand/") || apkPath.startsWith("/mnt/asec/")))
            return EXTERNAL;
        if (knownVolume) return internalVolume ? INTERNAL : EXTERNAL;
        if (apkPath != null && apkPath.startsWith("/data/")) return INTERNAL;
        return UNKNOWN;
    }
}
