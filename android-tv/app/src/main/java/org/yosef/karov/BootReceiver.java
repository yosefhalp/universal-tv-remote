package org.yosef.karov;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            Vault vault = Vault.open(context);
            if (vault.enabled() && !vault.get("server").isEmpty()) SupportService.start(context);
        } catch (Exception ignored) {
            /* אם המערכת אינה מאפשרת הפעלה ברקע, האפליקציה מציגה את המצב בפתיחה הבאה. */
        }
    }
}
