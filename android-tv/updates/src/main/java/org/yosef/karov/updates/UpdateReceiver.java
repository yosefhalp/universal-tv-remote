package org.yosef.karov.updates;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        UpdateEngine.get(context).installationResult(intent);
    }
}
