package org.yosef.karov.updates;

import android.app.Application;
import android.os.Handler;

public final class UpdateApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UpdateEngine.get(this).schedule();
        // נותנים לשירות השליטה זמן לשחזר חיבור לפני החלטה על התקנה אוטומטית.
        new Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> UpdateEngine.get(this).background(null), 15000);
    }
}
