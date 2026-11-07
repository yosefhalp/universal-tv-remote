package org.yosef.karov;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;

import org.json.JSONObject;

import java.nio.ByteBuffer;

public final class ScreenService extends Service {
    private static volatile ScreenService instance;
    private volatile JSONObject latest;
    private volatile long latestAt;
    private volatile MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread images;
    private boolean captureScheduled;

    public static boolean available() {
        return instance != null && instance.projection != null;
    }

    public static void capture(DeviceActions.Result callback) {
        ScreenService service = instance;
        if (service == null || service.projection == null) {
            callback.done(null, "CAPTURE_PERMISSION");
            return;
        }
        // מסך סטטי אינו מפיק בהכרח תמונות נוספות. התמונה נשארת תקפה כל עוד השיתוף פעיל.
        if (service.latest == null) {
            callback.done(null, "CAPTURE_BUSY");
            return;
        }
        callback.done(service.latest, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_NOT_STICKY;
        if (intent == null || !intent.hasExtra("captureData")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(
                        "capture", "שיתוף מסך", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        20,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE);
        Notification notification =
                new Notification.Builder(this, "capture")
                        .setSmallIcon(R.drawable.icon)
                        .setContentTitle("קרוב — שיתוף המסך פעיל")
                        .setContentText("אפשר להפסיק את השיתוף מתוך האפליקציה")
                        .setContentIntent(open)
                        .setOngoing(true)
                        .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(20, notification);
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent data = intent.getParcelableExtra("captureData");
            projection = manager.getMediaProjection(Activity.RESULT_OK, data);
            if (projection == null) throw new Exception("NO_PROJECTION");
            images = new HandlerThread("karov-capture");
            images.start();
            Handler handler = new Handler(images.getLooper());
            projection.registerCallback(
                    new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            latest = null;
                            projection = null;
                            stopSelf();
                        }
                    },
                    handler);
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = Math.min(1280, metrics.widthPixels),
                    height = Math.max(1, metrics.heightPixels * width / metrics.widthPixels);
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(
                    source -> {
                        if (captureScheduled) return;
                        captureScheduled = true;
                        // דוחים את הקריאה עצמה במקום לזרוק תמונה מוקדמת; כך גם שינוי אחרון במסך
                        // סטטי לא הולך לאיבוד.
                        handler.postDelayed(
                                () -> {
                                    captureScheduled = false;
                                    readFrame(source, width, height);
                                },
                                Math.max(0, 400 - (SystemClock.elapsedRealtime() - latestAt)));
                    },
                    handler);
            display =
                    projection.createVirtualDisplay(
                            "קרוב",
                            width,
                            height,
                            metrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            reader.getSurface(),
                            new VirtualDisplay.Callback() {
                                @Override
                                public void onStopped() {
                                    latest = null;
                                    stopSelf();
                                }
                            },
                            handler);
            instance = this;
        } catch (Exception error) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void readFrame(ImageReader source, int width, int height) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || projection == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride(),
                    rowPadding = plane.getRowStride() - pixelStride * width;
            Bitmap padded =
                    Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            Bitmap cropped = null;
            try {
                padded.copyPixelsFromBuffer(buffer);
                cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
                JSONObject frame = TvAccessibilityService.frame(cropped, this);
                if (projection != null) {
                    latest = frame;
                    latestAt = SystemClock.elapsedRealtime();
                }
            } finally {
                if (cropped != null && cropped != padded) cropped.recycle();
                padded.recycle();
            }
        } catch (Exception unavailable) {
            latest = null;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        latest = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (images != null) images.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
