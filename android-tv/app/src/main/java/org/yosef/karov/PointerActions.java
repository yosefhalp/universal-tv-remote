package org.yosef.karov;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class PointerActions {
    private final Context context;
    private boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long operation;
    private final PointerOperation operations = new PointerOperation();
    private DeviceActions.Result pending;
    private Runnable deadline;
    private Runnable focusedClick;
    private TwoPanelTarget focusedTarget;

    public PointerActions(Context context) {
        this.context = context;
    }

    private long begin(DeviceActions.Result callback, int duration) {
        busy = true;
        pending = callback;
        long id = operations.begin(SystemClock.elapsedRealtime(), duration + 2500L);
        operation = id;
        // קושחה עלולה לא להחזיר תשובת ביטול. אין להשאיר את המצב תפוס אחרי מחווה קצובה.
        deadline = () -> finish(id, null, "POINTER_CANCELLED");
        main.postDelayed(deadline, duration + 2500L);
        return id;
    }

    private void finish(long id, Object value, String error) {
        if (!operations.finish(id)) return;
        main.removeCallbacks(deadline);
        if (focusedClick != null) main.removeCallbacks(focusedClick);
        focusedClick = null;
        if (focusedTarget != null) focusedTarget.close();
        focusedTarget = null;
        busy = false;
        DeviceActions.Result reply = pending;
        pending = null;
        reply.done(value, error);
    }

    public void disconnected() {
        if (busy) finish(operation, null, "DISCONNECTED");
        operations.invalidate();
    }

    public static JSONObject geometry(Context context, JSONObject frame) throws Exception {
        Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return frame.put("displayWidth", size.x)
                .put("displayHeight", size.y)
                .put("rotation", display.getRotation());
    }

    public void execute(JSONObject args, BooleanSupplier current, DeviceActions.Result callback)
            throws Exception {
        PointerSpec spec = new PointerSpec(args);
        JSONObject geometry = geometry(context, new JSONObject());
        if (!spec.matches(
                geometry.getInt("displayWidth"),
                geometry.getInt("displayHeight"),
                geometry.getInt("rotation"))) {
            callback.done(null, "STALE_GEOMETRY");
            return;
        }
        if (MainActivity.visible) {
            callback.done(null, "SECURE_CONTENT");
            return;
        }
        if (busy) {
            callback.done(null, "POINTER_BUSY");
            return;
        }
        if (!current.getAsBoolean()) {
            callback.done(null, "DISCONNECTED");
            return;
        }
        TvAccessibilityService service = TvAccessibilityService.instance;
        // התיקון נדרש גם כש-ADB זמין: מגע ישיר אינו מסנכרן את preview של TwoPanel.
        if (service != null
                && spec.kind.equals("tap")
                && twoPanelTap(
                        service::getRootInActiveWindow,
                        () -> TvAccessibilityService.instance == service,
                        spec,
                        current,
                        callback,
                        TwoPanelTarget.SETTINGS)) return;
        if (service != null && spec.kind.equals("scroll") && scrollNode(service, spec)) {
            callback.done(true, null);
            return;
        }
        LocalAdb adb = LocalAdb.get(context);
        boolean gestures = service != null && PointerCapabilities.gesturesSupported(context);
        if (!gestures && !adb.ready()) {
            if (service == null) callback.done(null, "NO_POINTER");
            else if (spec.kind.equals("tap")) {
                // בטלוויזיה ללא מסך מגע מפעילים את הרכיב שמתחת לסמן, בלי לשלוח מחווה שנדחית מראש.
                boolean clicked = clickNode(service, spec, current);
                callback.done(clicked ? true : null, clicked ? null : "POINTER_TARGET_UNAVAILABLE");
            } else callback.done(null, "POINTER_REQUIRES_ADB");
            return;
        }
        if (gestures) {
            Path path = new Path();
            float[][] points = spec.path();
            path.moveTo(points[0][0] * (spec.width - 1), points[0][1] * (spec.height - 1));
            for (int i = 1; i < points.length; i++)
                path.lineTo(points[i][0] * (spec.width - 1), points[i][1] * (spec.height - 1));
            GestureDescription gesture =
                    new GestureDescription.Builder()
                            .addStroke(
                                    new GestureDescription.StrokeDescription(
                                            path, 0, spec.duration))
                            .build();
            long id = begin(callback, spec.duration);
            // שולחים מחווה שלמה וקצובה רק אחרי שחרור האצבע. ניתוק באמצע ציור בצד הלקוח אינו משאיר
            // לחיצה
            // בטלוויזיה.
            boolean accepted;
            try {
                accepted =
                        service.dispatchGesture(
                                gesture,
                                new AccessibilityService.GestureResultCallback() {
                                    @Override
                                    public void onCompleted(GestureDescription ignored) {
                                        finish(id, true, null);
                                    }

                                    @Override
                                    public void onCancelled(GestureDescription ignored) {
                                        // ביטול בידי המשתמש או Android אינו סיבה לשדר את הפעולה שוב
                                        // דרך מסלול אחר.
                                        finish(id, null, "POINTER_CANCELLED");
                                    }
                                },
                                main);
            } catch (RuntimeException error) {
                // חריגה של שירות הנגישות אינה משאירה את הבקר תפוס ואינה משדרת את המחווה פעמיים.
                android.util.Log.w("KarovPointer", "שליחת המחווה נכשלה", error);
                finish(id, null, "POINTER_CANCELLED");
                return;
            }
            if (accepted) return;
            main.removeCallbacks(deadline);
            operations.finish(id);
            busy = false;
            pending = null;
        }
        if (adb.ready()) {
            long id = begin(callback, spec.duration);
            adb.pointer(
                    spec,
                    () -> queuedCurrent(id, spec, current),
                    (result, error) ->
                            new Handler(Looper.getMainLooper())
                                    .post(() -> {
                                        finish(id, result, error);
                                    }));
        } else callback.done(null, "NO_POINTER");
    }

    // פרמטר החבילה נגיש רק לבדיקת fixture פנימית; מסלול RPC תמיד שולח את חבילת Settings הקבועה.
    boolean twoPanelTap(
            Supplier<AccessibilityNodeInfo> roots,
            BooleanSupplier serviceCurrent,
            PointerSpec spec,
            BooleanSupplier current,
            DeviceActions.Result callback,
            String packageName) {
        AccessibilityNodeInfo root = roots.get();
        TwoPanelTarget.Selection selection;
        try {
            selection =
                    TwoPanelTarget.select(
                            root,
                            Math.round(spec.points[0][0] * (spec.width - 1)),
                            Math.round(spec.points[0][1] * (spec.height - 1)),
                            packageName);
        } finally {
            if (root != null) root.recycle();
        }
        try (selection) {
            if (!selection.recognized) return false;
            if (spec.duration >= 500 || selection.target == null) {
                callback.done(null, "POINTER_TARGET_UNAVAILABLE");
                return true;
            }
            long id = begin(callback, spec.duration);
            focusedTarget = selection.target;
            selection.target = null;
            try {
                String error = operationFailure(id, spec, current);
                if (error != null) {
                    finish(id, null, error);
                    return true;
                }
                AccessibilityNodeInfo beforeFocus = roots.get();
                boolean valid;
                try {
                    valid =
                            serviceCurrent.getAsBoolean()
                                    && focusedTarget.current(beforeFocus, false);
                } finally {
                    if (beforeFocus != null) beforeFocus.recycle();
                }
                if (!valid
                        || (!focusedTarget.row.isFocused()
                                && !focusedTarget.row.performAction(
                                        AccessibilityNodeInfo.ACTION_FOCUS))) {
                    finish(id, null, "POINTER_TARGET_UNAVAILABLE");
                    return true;
                }
                focusedClick =
                        () -> {
                            String failure = operationFailure(id, spec, current);
                            if (failure != null) {
                                finish(id, null, failure);
                                return;
                            }
                            AccessibilityNodeInfo active = null;
                            try {
                                if (!serviceCurrent.getAsBoolean() || focusedTarget == null) {
                                    finish(id, null, "POINTER_CANCELLED");
                                    return;
                                }
                                active = roots.get();
                                if (!focusedTarget.current(active, true)) {
                                    finish(id, null, "POINTER_TARGET_UNAVAILABLE");
                                    return;
                                }
                                failure = operationFailure(id, spec, current);
                                if (failure != null) {
                                    finish(id, null, failure);
                                    return;
                                }
                                // פעולה יחידה על אותו node לאחר רענון. אין לחיצה בקואורדינטה ישנה
                                // ואין fallback.
                                boolean clicked =
                                        focusedTarget.row.performAction(
                                                AccessibilityNodeInfo.ACTION_CLICK);
                                finish(
                                        id,
                                        clicked ? true : null,
                                        clicked ? null : "POINTER_TARGET_UNAVAILABLE");
                            } catch (RuntimeException failureException) {
                                finish(id, null, "POINTER_TARGET_UNAVAILABLE");
                            } finally {
                                if (active != null) active.recycle();
                            }
                        };
                main.postDelayed(focusedClick, 350);
            } catch (RuntimeException error) {
                finish(id, null, "POINTER_TARGET_UNAVAILABLE");
            }
            return true;
        }
    }

    private boolean clickNode(
            TvAccessibilityService service, PointerSpec spec, BooleanSupplier current) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        AccessibilityNodeInfo chosen = null;
        long area = Long.MAX_VALUE;
        int visited = 0;
        boolean longClick = spec.duration >= 500;
        int x = Math.round(spec.points[0][0] * (spec.width - 1));
        int y = Math.round(spec.points[0][1] * (spec.height - 1));
        try {
            while (!nodes.isEmpty() && visited++ < 512) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (node.isVisibleToUser()
                            && node.isEnabled()
                            && (longClick ? node.isLongClickable() : node.isClickable())
                            && bounds.contains(x, y)) {
                        long size = (long) bounds.width() * bounds.height();
                        // הרכיב הקטן ביותר שמתחת לסמן עדיף על מכולה לחיצה שמקיפה את כל השורה.
                        if (size <= area) {
                            if (chosen != null) chosen.recycle();
                            chosen = AccessibilityNodeInfo.obtain(node);
                            area = size;
                        }
                    }
                    for (int i = 0; i < node.getChildCount() && nodes.size() < 512; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                } finally {
                    node.recycle();
                }
            }
            // ניסיון יחיד בלבד: כישלון או ביטול אינם סיבה ללחוץ שוב על רכיב אחר.
            return chosen != null
                    && current.getAsBoolean()
                    && !MainActivity.visible
                    && chosen.performAction(
                            longClick
                                    ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                                    : AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            if (chosen != null) chosen.recycle();
            for (AccessibilityNodeInfo remaining : nodes) remaining.recycle();
        }
    }

    private boolean queuedCurrent(long id, PointerSpec spec, BooleanSupplier current) {
        // הבדיקה רצה בתור העבודה של ADB, ממש לפני שליחת הפקודה. תוקף נמדד בשעון מונוטוני
        // כדי שגם עיכוב בלולאת הממשק לא יאפשר למחווה שפג תוקפה להתחיל מאוחר יותר.
        String error = operationFailure(id, spec, current);
        if (error != null) {
            String failure = error;
            main.post(() -> finish(id, null, failure));
            return false;
        }
        // ניתוק או ביטול בזמן קריאת ממדי המסך מבטלים גם את הפקודה הממתינה.
        return operations.canStart(id, SystemClock.elapsedRealtime())
                && current.getAsBoolean()
                && !MainActivity.visible;
    }

    private String operationFailure(long id, PointerSpec spec, BooleanSupplier current) {
        String error = null;
        if (!operations.canStart(id, SystemClock.elapsedRealtime())) error = "POINTER_CANCELLED";
        else if (!current.getAsBoolean()) error = "DISCONNECTED";
        else if (MainActivity.visible) error = "SECURE_CONTENT";
        else {
            try {
                JSONObject screen = geometry(context, new JSONObject());
                if (!spec.matches(
                        screen.getInt("displayWidth"),
                        screen.getInt("displayHeight"),
                        screen.getInt("rotation"))) error = "STALE_GEOMETRY";
            } catch (Exception ignored) {
                // אם אי אפשר לאמת את המסך הנוכחי, אסור להפעיל קואורדינטות מתמונה ישנה.
                error = "STALE_GEOMETRY";
            }
        }
        if (error == null && !operations.canStart(id, SystemClock.elapsedRealtime()))
            error = "POINTER_CANCELLED";
        if (error == null && !current.getAsBoolean()) error = "DISCONNECTED";
        if (error == null && MainActivity.visible) error = "SECURE_CONTENT";
        return error;
    }

    private boolean scrollNode(TvAccessibilityService service, PointerSpec spec) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        AccessibilityNodeInfo chosen = null;
        long area = Long.MAX_VALUE;
        int count = 0;
        while (!nodes.isEmpty() && count++ < 512) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser()
                    && node.isEnabled()
                    && node.isScrollable()
                    && bounds.contains(
                            (int) (spec.points[0][0] * (spec.width - 1)),
                            (int) (spec.points[0][1] * (spec.height - 1)))) {
                long size = (long) bounds.width() * bounds.height();
                if (size < area) {
                    if (chosen != null) chosen.recycle();
                    chosen = AccessibilityNodeInfo.obtain(node);
                    area = size;
                }
            }
            for (int i = 0; i < node.getChildCount() && nodes.size() < 512; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.add(child);
            }
            node.recycle();
        }
        for (AccessibilityNodeInfo remaining : nodes) remaining.recycle();
        if (chosen == null) return false;
        boolean horizontal = Math.abs(spec.dx) > Math.abs(spec.dy);
        AccessibilityNodeInfo.AccessibilityAction direction =
                horizontal
                        ? spec.dx > 0
                                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                        : spec.dy > 0
                                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
        boolean result = chosen.performAction(direction.getId());
        if (!result && !horizontal)
            result =
                    chosen.performAction(
                            spec.dy > 0
                                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        chosen.recycle();
        return result;
    }
}
