package org.yosef.karov;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public final class TvAccessibilityService extends AccessibilityService {
    public static volatile TvAccessibilityService instance;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long CAPTURE_INTERVAL = 350;
    private long lastCapture;
    private boolean capturing;
    private TextView badge;

    @Override
    public void onServiceConnected() {
        instance = this;
        try {
            if (Vault.open(this).enabled()) SupportService.start(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        showActive(false);
        instance = null;
        images.shutdown();
        super.onDestroy();
    }

    public void showActive(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> showActive(active));
            return;
        }
        WindowManager wm = getSystemService(WindowManager.class);
        if (badge != null) {
            try {
                wm.removeView(badge);
            } catch (Exception ignored) {
            }
            badge = null;
        }
        if (!active) return;
        badge = new TextView(this);
        badge.setText("קרוב • בן משפחה עוזר עכשיו");
        badge.setTextColor(0xff111216);
        badge.setTextSize(14);
        badge.setPadding(18, 7, 18, 7);
        badge.setBackgroundColor(0xeeff784e);
        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 16;
        params.y = 12;
        try {
            wm.addView(badge, params);
        } catch (Exception ignored) {
            badge = null;
        }
    }

    public boolean key(String key) {
        if (key.equals("HOME")) return performGlobalAction(GLOBAL_ACTION_HOME);
        if (key.equals("BACK")) return performGlobalAction(GLOBAL_ACTION_BACK);
        if (Build.VERSION.SDK_INT >= 33) {
            int action =
                    switch (key) {
                        case "UP" -> GLOBAL_ACTION_DPAD_UP;
                        case "DOWN" -> GLOBAL_ACTION_DPAD_DOWN;
                        case "LEFT" -> GLOBAL_ACTION_DPAD_LEFT;
                        case "RIGHT" -> GLOBAL_ACTION_DPAD_RIGHT;
                        case "OK" -> GLOBAL_ACTION_DPAD_CENTER;
                        default -> -1;
                    };
            if (action != -1 && performGlobalAction(action)) return true;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focus == null) focus = root;
        if (key.equals("OK")) {
            for (int i = 0; focus != null && i < 6; i++, focus = focus.getParent())
                if (focus.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            return false;
        }
        int direction =
                switch (key) {
                    case "UP" -> View.FOCUS_UP;
                    case "DOWN" -> View.FOCUS_DOWN;
                    case "LEFT" -> View.FOCUS_LEFT;
                    case "RIGHT" -> View.FOCUS_RIGHT;
                    default -> 0;
                };
        if (direction == 0) return false;
        AccessibilityNodeInfo next = focus.focusSearch(direction);
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    public boolean text(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.isEditable()) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public void capture(BooleanSupplier current, DeviceActions.Result callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.done(null, "CAPTURE_PERMISSION");
            return;
        }
        if (capturing) {
            callback.done(null, "CAPTURE_BUSY");
            return;
        }
        capturing = true;
        // בקשה אחת מחכה לחלון הצילום הבא במקום להחזיר ״עסוק״ ולחכות לסבב רשת נוסף.
        long wait = Math.max(0, CAPTURE_INTERVAL - (SystemClock.elapsedRealtime() - lastCapture));
        if (wait == 0) takeFrame(current, callback);
        else main.postDelayed(() -> takeFrame(current, callback), wait);
    }

    @android.annotation.TargetApi(30)
    private void takeFrame(BooleanSupplier current, DeviceActions.Result callback) {
        if (!current.getAsBoolean() || instance != this) {
            completeCapture(callback, null, "SECURE_CONTENT");
            return;
        }
        lastCapture = SystemClock.elapsedRealtime();
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                images.execute(() -> {
                                    Bitmap hardware = null, software = null;
                                    try {
                                        hardware =
                                                Bitmap.wrapHardwareBuffer(
                                                        result.getHardwareBuffer(),
                                                        result.getColorSpace());
                                        if (hardware == null) throw new Exception("CAPTURE_FAILED");
                                        software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                        JSONObject jpeg =
                                                frame(software, TvAccessibilityService.this);
                                        main.post(() -> {
                                            boolean available =
                                                    instance == TvAccessibilityService.this
                                                            && current.getAsBoolean();
                                            completeCapture(
                                                    callback,
                                                    available ? jpeg : null,
                                                    available ? null : "SECURE_CONTENT");
                                        });
                                    } catch (Exception error) {
                                        main.post(() ->
                                                completeCapture(callback, null, "CAPTURE_FAILED"));
                                    } finally {
                                        if (software != null) software.recycle();
                                        if (hardware != null) hardware.recycle();
                                        result.getHardwareBuffer().close();
                                    }
                                });
                            } catch (java.util.concurrent.RejectedExecutionException error) {
                                result.getHardwareBuffer().close();
                                completeCapture(callback, null, "NO_ACCESSIBILITY");
                            }
                        }

                        @Override
                        public void onFailure(int code) {
                            completeCapture(
                                    callback,
                                    null,
                                    code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                            ? "CAPTURE_BUSY"
                                            : code == 6 ? "SECURE_CONTENT" : "CAPTURE_FAILED");
                        }
                    });
        } catch (RuntimeException error) {
            completeCapture(callback, null, "CAPTURE_FAILED");
        }
    }

    private void completeCapture(DeviceActions.Result callback, JSONObject frame, String error) {
        capturing = false;
        callback.done(frame, error);
    }

    public static JSONObject frame(Bitmap bitmap, android.content.Context context)
            throws Exception {
        int width = Math.min(1280, bitmap.getWidth()),
                height = Math.max(1, bitmap.getHeight() * width / bitmap.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, out);
            return PointerActions.geometry(
                    context,
                    new JSONObject()
                            .put(
                                    "jpeg",
                                    android.util.Base64.encodeToString(
                                            out.toByteArray(), android.util.Base64.NO_WRAP))
                            .put("width", width)
                            .put("height", height));
        } finally {
            if (scaled != bitmap) scaled.recycle();
        }
    }
}
