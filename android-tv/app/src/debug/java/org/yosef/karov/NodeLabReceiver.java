package org.yosef.karov;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;

// כלי שחזור באמולטור debug בלבד: פותח קטגוריית הגדרות קיימת, בלי לשנות שום ערך מערכת.
public final class NodeLabReceiver extends BroadcastReceiver {
    private static final String SETTINGS = "com.android.tv.settings";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.DEBUG
                || !(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"))
                || intent == null
                || !"tap-unfocused".equals(intent.getStringExtra("operation"))) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"ok\":false,\"error\":\"LAB_UNSUPPORTED\"}");
            return;
        }
        PendingResult pending = goAsync();
        Handler main = new Handler(Looper.getMainLooper());
        Context app = context.getApplicationContext();
        main.post(() -> {
            AccessibilityNodeInfo root = null, accounts = null, target = null;
            try {
                TvAccessibilityService service = TvAccessibilityService.instance;
                root = settingsRoot(service);
                // שני שמות קבועים של קטגוריות ריקות/מידע בלבד; אין קלט של כתובת או פעולה מהשולח.
                accounts = row(root, "Accounts & sign-in");
                target = row(root, "Remotes & accessories");
                if (accounts == null || target == null)
                    throw new Exception("LAB_NOT_SETTINGS_ROOT");
                if (!accounts.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        || !accounts.refresh()
                        || !accounts.isFocused()) throw new Exception("LAB_FOCUS_FAILED");
                JSONObject before = new JSONObject().put("focusedAccounts", true);
                main.postDelayed(() -> tap(app, pending, service, before), 350);
            } catch (Exception error) {
                finish(pending, new JSONObject(), error.getMessage());
            } finally {
                if (root != null) root.recycle();
                if (accounts != null) accounts.recycle();
                if (target != null) target.recycle();
            }
        });
    }

    private static void tap(
            Context context,
            PendingResult pending,
            TvAccessibilityService service,
            JSONObject result) {
        AccessibilityNodeInfo root = null, target = null;
        try {
            root = settingsRoot(service);
            target = row(root, "Remotes & accessories");
            if (target == null) throw new Exception("LAB_TARGET_MISSING");
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            result.put("target", details(target)).put("targetFocused", target.isFocused());
            if (target.isFocused()) throw new Exception("LAB_TARGET_ALREADY_FOCUSED");
            JSONObject args = PointerActions.geometry(context, new JSONObject());
            int x = Math.round(bounds.exactCenterX()), y = Math.round(bounds.exactCenterY());
            result.put("chosen", chosen(root, x, y));
            args.put("kind", "tap")
                    .put("duration", 200)
                    .put(
                            "points",
                            new JSONArray()
                                    .put(
                                            new JSONArray()
                                                    .put(x / (args.getInt("displayWidth") - 1.0))
                                                    .put(
                                                            y
                                                                    / (args.getInt("displayHeight")
                                                                            - 1.0))));
            // הקריאה עצמה עוברת בקוד המוצר הקיים; האיסוף הנוסף מחזיר רק מזהים ודגלים, ללא טקסט
            // חשבון.
            new PointerActions(context)
                    .execute(
                            args,
                            () -> TvAccessibilityService.instance == service,
                            (value, error) -> finish(pending, result, error));
        } catch (Exception error) {
            finish(pending, result, error.getMessage());
        } finally {
            if (root != null) root.recycle();
            if (target != null) target.recycle();
        }
    }

    private static AccessibilityNodeInfo settingsRoot(TvAccessibilityService service)
            throws Exception {
        if (service == null) throw new Exception("LAB_NO_ACCESSIBILITY");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) throw new Exception("LAB_NO_ROOT");
        if (!SETTINGS.contentEquals(root.getPackageName())) {
            root.recycle();
            throw new Exception("LAB_WRONG_PACKAGE");
        }
        return root;
    }

    private static AccessibilityNodeInfo row(AccessibilityNodeInfo root, String title) {
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(title);
        try {
            for (AccessibilityNodeInfo match : matches) {
                if (!title.contentEquals(match.getText())) continue;
                AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(match);
                for (int depth = 0; node != null && depth < 8; depth++) {
                    if (node.isVisibleToUser() && node.isEnabled() && node.isClickable())
                        return node;
                    AccessibilityNodeInfo parent = node.getParent();
                    node.recycle();
                    node = parent;
                }
                if (node != null) node.recycle();
            }
            return null;
        } finally {
            for (AccessibilityNodeInfo match : matches) match.recycle();
        }
    }

    private static JSONObject chosen(AccessibilityNodeInfo root, int x, int y) throws Exception {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        JSONObject chosen = new JSONObject();
        long area = Long.MAX_VALUE;
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited++ < 512) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    long size = (long) bounds.width() * bounds.height();
                    if (node.isVisibleToUser()
                            && node.isEnabled()
                            && node.isClickable()
                            && bounds.contains(x, y)
                            && size <= area) {
                        chosen = details(node);
                        area = size;
                    }
                    for (int index = 0;
                            index < node.getChildCount() && queue.size() < 512;
                            index++) {
                        AccessibilityNodeInfo child = node.getChild(index);
                        if (child != null) queue.add(child);
                    }
                } finally {
                    node.recycle();
                }
            }
        } finally {
            for (AccessibilityNodeInfo node : queue) node.recycle();
        }
        return chosen;
    }

    private static JSONObject details(AccessibilityNodeInfo node) throws Exception {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return new JSONObject()
                .put("class", node.getClassName())
                .put("id", node.getViewIdResourceName())
                .put("bounds", bounds.toShortString())
                .put("focused", node.isFocused())
                .put("clickable", node.isClickable());
    }

    private static void finish(PendingResult pending, JSONObject result, String error) {
        try {
            result.put("ok", error == null);
            if (error != null)
                result.put("error", error.matches("[A-Z_]{1,64}") ? error : "LAB_FAILED");
            pending.setResultCode(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
            pending.setResultData(result.toString());
        } catch (Exception ignored) {
            pending.setResultCode(Activity.RESULT_CANCELED);
        } finally {
            pending.finish();
        }
    }
}
