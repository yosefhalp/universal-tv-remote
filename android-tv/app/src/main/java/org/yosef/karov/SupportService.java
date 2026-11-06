package org.yosef.karov;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;

public final class SupportService extends Service {
    public static volatile SupportService instance;
    public static volatile String status = "העזרה מושהית";
    private RemoteLink link;
    private volatile boolean destroyed;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock availabilityLock;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, SupportService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        "support", "עזרה משפחתית", NotificationManager.IMPORTANCE_LOW));
        startForeground(10, notification("מכין חיבור לעזרה", false));
        try {
            Vault vault = Vault.open(this);
            if (!vault.enabled() || vault.get("server").isEmpty()) {
                stopSelf();
                return;
            }
            keepAvailable();
            DeviceActions actions = new DeviceActions(this);
            LocalAdb.get(this).start();
            link =
                    new RemoteLink(
                            vault,
                            actions,
                            (text, active) -> {
                                if (destroyed) return;
                                status = text;
                                manager.notify(10, notification(text, active));
                                sendBroadcast(
                                        new Intent(getPackageName() + ".STATE")
                                                .setPackage(getPackageName()));
                            });
            link.start();
            CaptureConsentActivity.request(this, true);
            connectivity = getSystemService(ConnectivityManager.class);
            networkCallback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            if (link != null) link.networkAvailable();
                        }
                    };
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception error) {
            status = "לא ניתן לקרוא את ההגדרות. פתחו את קרוב";
            stopSelf();
        }
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private void keepAvailable() {
        // הסטרימר מחובר לחשמל ונועד לקבל עזרה בכל שעה, גם כשהמסך כבוי.
        // הנעילה מחזיקה רק את המעבד למשך שירות הזמינות; השהיית העזרה משחררת אותה.
        // אין נעילת מסך ואין שינוי של מצב החיסכון או של הרשאות המערכת.
        availabilityLock =
                getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "karov:family-availability");
        availabilityLock.setReferenceCounted(false);
        availabilityLock.acquire();
    }

    private Notification notification(String text, boolean active) {
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        1,
                        new Intent(this, SupportService.class).setAction("pause"),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "support")
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(active ? "קרוב — עזרה פעילה" : "קרוב — זמין למשפחה")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "השהיית עזרה", stop).build())
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "pause".equals(intent.getAction())) {
            try {
                Vault.open(this).enable(false);
            } catch (Exception ignored) {
            }
            stopService(new Intent(this, ScreenService.class));
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    public void disconnectController() {
        if (link != null) link.disconnectController();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (link != null) link.stop();
        try {
            LocalAdb.get(this).stop();
        } catch (Exception ignored) {
        }
        try {
            if (connectivity != null && networkCallback != null)
                connectivity.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException notRegistered) {
            // ייתכן שהשירות נסגר אחרי כישלון ברישום המאזין לרשת.
        }
        if (availabilityLock != null && availabilityLock.isHeld()) availabilityLock.release();
        instance = null;
        status = "העזרה מושהית";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
