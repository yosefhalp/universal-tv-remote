package org.yosef.karov;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// כלי מעבדה באמולטור debug בלבד: אין קלט חופשי, גישה לסודות או שינוי הרשאות והגדרות.
public final class PointerLabReceiver extends BroadcastReceiver {
    private static final Set<String> OPERATIONS =
            Set.of(
                    "status",
                    "baseline",
                    "tap",
                    "scroll",
                    "drag",
                    "missing",
                    "stale",
                    "disconnected",
                    "home",
                    "back");
    private static final Set<String> ERRORS =
            Set.of(
                    "NO_POINTER",
                    "NO_ACCESSIBILITY",
                    "POINTER_TARGET_UNAVAILABLE",
                    "POINTER_REQUIRES_ADB",
                    "POINTER_CANCELLED",
                    "POINTER_BUSY",
                    "STALE_GEOMETRY",
                    "DISCONNECTED",
                    "SECURE_CONTENT",
                    "ADB_UNAVAILABLE",
                    "BAD_POINTER",
                    "UNSUPPORTED",
                    "FAILED");
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "karov-pointer-lab-deadline");
                worker.setDaemon(true);
                return worker;
            });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG
                || !(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"))) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"ok\":false,\"error\":\"LAB_UNSUPPORTED\"}");
            return;
        }
        String operation = intent == null ? null : intent.getStringExtra("operation");
        if (operation == null || !OPERATIONS.contains(operation)) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"ok\":false,\"error\":\"LAB_OPERATION\"}");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"ok\":false,\"error\":\"LAB_BUSY\"}");
            return;
        }
        Reply reply = new Reply(context.getApplicationContext(), operation, goAsync());
        reply.main.post(() -> {
            if (reply.done.get()) return;
            try {
                run(reply);
            } catch (LabFailure error) {
                reply.fail(error.code);
            } catch (Exception ignored) {
                reply.fail("LAB_EXCEPTION");
            }
        });
    }

    private static void run(Reply reply) throws Exception {
        if (reply.operation.equals("status")) {
            reply.finish(diagnostics(reply, new JSONObject().put("ok", true)));
            return;
        }
        if (reply.operation.equals("home") || reply.operation.equals("back")) {
            new DeviceActions(reply.context)
                    .execute(
                            "key",
                            new JSONObject()
                                    .put("key", reply.operation.equals("home") ? "HOME" : "BACK"),
                            () -> !reply.done.get(),
                            (value, error) -> finishAction(reply, value, error));
            return;
        }
        TvAccessibilityService service = TvAccessibilityService.instance;
        Probe probe = probe(reply.context, service);
        if (reply.operation.equals("baseline")) {
            baseline(reply, service, probe);
            return;
        }
        String kind =
                reply.operation.equals("scroll")
                        ? "scroll"
                        : reply.operation.equals("drag") ? "drag" : "tap";
        Rect target =
                reply.operation.equals("scroll")
                        ? probe.scroll
                        : reply.operation.equals("drag")
                                ? probe.slider
                                : reply.operation.equals("missing") ? probe.label : probe.target;
        JSONObject args = PointerActions.geometry(reply.context, new JSONObject());
        double width = args.getInt("displayWidth") - 1.;
        double height = args.getInt("displayHeight") - 1.;
        if (width <= 0 || height <= 0) throw new LabFailure("LAB_GEOMETRY");
        JSONArray points = new JSONArray();
        if (kind.equals("drag")) {
            points.put(
                    point(
                            (target.left + target.width() * .25) / width,
                            target.exactCenterY() / height));
            points.put(
                    point(
                            (target.left + target.width() * .75) / width,
                            target.exactCenterY() / height));
        } else points.put(point(target.exactCenterX() / width, target.exactCenterY() / height));
        args.put("kind", kind).put("duration", 200).put("points", points);
        if (kind.equals("scroll")) args.put("dy", .65);
        if (reply.operation.equals("stale"))
            args.put("displayWidth", args.getInt("displayWidth") + 1);
        PointerActions actions = new PointerActions(reply.context);
        // המצב נבדק שוב לפני פעולה ממתינה. גם באמולטור אין להזריק נקודה לאפליקציה אחרת.
        actions.execute(
                args,
                () ->
                        !reply.done.get()
                                && !reply.operation.equals("disconnected")
                                && probeActive(reply.context, service),
                (value, error) -> finishAction(reply, value, error));
    }

    private static JSONArray point(double x, double y) throws Exception {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
            throw new LabFailure("LAB_GEOMETRY");
        return new JSONArray().put(x).put(y);
    }

    private static void baseline(Reply reply, TvAccessibilityService service, Probe probe)
            throws Exception {
        if (reply.done.get()) return;
        if (!probeActive(reply.context, service)) throw new LabFailure("LAB_NOT_PROBE");
        Path path = new Path();
        path.moveTo(probe.target.exactCenterX(), probe.target.exactCenterY());
        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                        .build();
        AtomicBoolean accepted = new AtomicBoolean(), reported = new AtomicBoolean();
        if (reply.done.get()) return;
        boolean sent =
                service.dispatchGesture(
                        gesture,
                        new AccessibilityService.GestureResultCallback() {
                            @Override
                            public void onCompleted(GestureDescription ignored) {
                                baselineResult(reply, accepted.get(), false, true, reported);
                            }

                            @Override
                            public void onCancelled(GestureDescription ignored) {
                                baselineResult(reply, accepted.get(), true, false, reported);
                            }
                        },
                        reply.main);
        accepted.set(sent);
        if (!sent) baselineResult(reply, false, false, false, reported);
    }

    private static void baselineResult(
            Reply reply,
            boolean accepted,
            boolean cancelled,
            boolean completed,
            AtomicBoolean reported) {
        if (reply.done.get() || !reported.compareAndSet(false, true)) return;
        // ממתינים רק לעדכון המונה; אין שידור חוזר אחרי ביטול או תוצאה לא ודאית.
        reply.main.postDelayed(
                () -> {
                    if (reply.done.get()) return;
                    try {
                        JSONObject result =
                                new JSONObject()
                                        .put("ok", true)
                                        .put("accepted", accepted)
                                        .put("cancelled", cancelled)
                                        .put("completed", completed);
                        reply.finish(diagnostics(reply, result));
                    } catch (Exception ignored) {
                        reply.fail("LAB_DIAGNOSTICS");
                    }
                },
                350);
    }

    private static void finishAction(Reply reply, Object value, String error) {
        if (reply.done.get() || !reply.callback.compareAndSet(false, true)) return;
        reply.main.postDelayed(
                () -> {
                    if (reply.done.get()) return;
                    try {
                        String failure =
                                error == null
                                        ? null
                                        : ERRORS.contains(error) ? error : "LAB_ACTION_FAILED";
                        JSONObject result =
                                new JSONObject()
                                        .put("ok", failure == null)
                                        .put(
                                                "value",
                                                Boolean.TRUE.equals(value) ? true : JSONObject.NULL)
                                        .put("error", failure == null ? JSONObject.NULL : failure);
                        reply.finish(diagnostics(reply, result));
                    } catch (Exception ignored) {
                        reply.fail("LAB_DIAGNOSTICS");
                    }
                },
                350);
    }

    private static JSONObject diagnostics(Reply reply, JSONObject result) throws Exception {
        JSONObject info = new DeviceActions(reply.context).info();
        result.put("operation", reply.operation)
                .put("package", reply.context.getPackageName())
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT);
        for (String key :
                new String[] {
                    "accessibility",
                    "localAdb",
                    "pointer",
                    "pointerDrag",
                    "pointerMode",
                    "displayTouchscreen"
                }) result.put(key, info.get(key));
        TvAccessibilityService service = TvAccessibilityService.instance;
        AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveWindow();
        if (root != null) {
            try {
                result.put("windowPackage", identifier(root.getPackageName()));
                result.put("windowClass", identifier(root.getClassName()));
            } finally {
                root.recycle();
            }
        }
        try {
            Probe probe = probe(reply.context, service);
            result.put("probe", true)
                    .put("clicks", probe.clicks)
                    .put("scroll", probe.scrollText)
                    .put("slider", probe.sliderText);
        } catch (LabFailure ignored) {
            result.put("probe", false);
        }
        return result;
    }

    private static String identifier(CharSequence value) {
        String text = value == null ? "" : value.toString();
        return text.matches("[A-Za-z0-9_.$]{1,180}") ? text : "";
    }

    private static boolean probeActive(Context context, TvAccessibilityService service) {
        if (service != TvAccessibilityService.instance) return false;
        try {
            probe(context, service);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Probe {
        Rect target, slider, scroll, label;
        String clicks, scrollText, sliderText;
    }

    private static Probe probe(Context context, TvAccessibilityService service) throws LabFailure {
        if (service == null) throw new LabFailure("LAB_NO_ACCESSIBILITY");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) throw new LabFailure("LAB_NOT_PROBE");
        if (!context.getPackageName()
                .contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle();
            throw new LabFailure("LAB_NOT_PROBE");
        }
        Probe probe = new Probe();
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        int count = 0;
        try {
            while (!nodes.isEmpty() && count++ < 512) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    if (node.isVisibleToUser() && node.isEnabled()) {
                        String description =
                                node.getContentDescription() == null
                                        ? ""
                                        : node.getContentDescription().toString();
                        String text = node.getText() == null ? "" : node.getText().toString();
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty()) {
                            if (description.equals("יעד לחיצה") && node.isClickable())
                                probe.target = bounds;
                            if (description.equals("יעד גרירה")) probe.slider = bounds;
                            if (description.equals("יעד גלילה") && node.isScrollable())
                                probe.scroll = bounds;
                            // רק שלושת מוני המעבדה מועתקים. טקסט חופשי מהחלון לעולם אינו מוחזר.
                            if (text.matches("לחיצות: [0-9]{1,9}")) probe.clicks = text;
                            if (text.matches("גלילה: [0-9]{1,9}")) probe.scrollText = text;
                            if (text.matches("ערך גרירה: [0-9]{1,3}")) {
                                probe.sliderText = text;
                                if (!node.isClickable() && !node.isLongClickable())
                                    probe.label = bounds;
                            }
                        }
                    }
                    for (int index = 0;
                            index < node.getChildCount() && nodes.size() < 512;
                            index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) nodes.add(child);
                    }
                } finally {
                    node.recycle();
                }
            }
        } finally {
            for (AccessibilityNodeInfo remaining : nodes) remaining.recycle();
        }
        if (probe.target == null
                || probe.slider == null
                || probe.scroll == null
                || probe.label == null
                || probe.clicks == null
                || probe.scrollText == null
                || probe.sliderText == null) throw new LabFailure("LAB_NOT_PROBE");
        return probe;
    }

    private static final class LabFailure extends Exception {
        final String code;

        LabFailure(String code) {
            this.code = code;
        }
    }

    private static final class Reply {
        final Context context;
        final String operation;
        final PendingResult result;
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean done = new AtomicBoolean(), callback = new AtomicBoolean();
        final ScheduledFuture<?> deadline;

        Reply(Context context, String operation, PendingResult result) {
            this.context = context;
            this.operation = operation;
            this.result = result;
            // שעון נפרד מסיים את השידור גם אם קריאת מערכת חוסמת זמנית את לולאת הממשק.
            deadline = DEADLINES.schedule(() -> fail("LAB_TIMEOUT"), 6500, TimeUnit.MILLISECONDS);
        }

        void fail(String code) {
            finish(
                    "{\"ok\":false,\"error\":\"" + code + "\",\"operation\":\"" + operation + "\"}",
                    false);
        }

        void finish(JSONObject value) {
            finish(value.toString(), value.optBoolean("ok"));
        }

        private void finish(String value, boolean ok) {
            if (!done.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            try {
                result.setResultCode(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
                result.setResultData(value);
                result.finish();
            } finally {
                BUSY.set(false);
            }
        }
    }
}
