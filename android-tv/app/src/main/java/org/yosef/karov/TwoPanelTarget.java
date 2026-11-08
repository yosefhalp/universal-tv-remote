package org.yosef.karov;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// ב-TwoPanel לחיצה על שורה לא ממוקדת עלולה לפתוח את התצוגה המקדימה של השורה הקודמת.
// נשמרת זהות רכיב הנגישות, ולא הקואורדינטות שעשויות להשתנות בעקבות המיקוד.
final class TwoPanelTarget implements AutoCloseable {
    static final String SETTINGS = "com.android.tv.settings";
    final AccessibilityNodeInfo row;
    private final AccessibilityNodeInfo list, container;
    private final String packageName, title, className, viewId;
    private final int window;

    private TwoPanelTarget(
            AccessibilityNodeInfo row,
            AccessibilityNodeInfo list,
            AccessibilityNodeInfo container,
            String packageName,
            String title) {
        this.row = AccessibilityNodeInfo.obtain(row);
        this.list = AccessibilityNodeInfo.obtain(list);
        this.container = AccessibilityNodeInfo.obtain(container);
        this.packageName = packageName;
        this.title = title;
        this.className = String.valueOf(row.getClassName());
        this.viewId = row.getViewIdResourceName();
        this.window = row.getWindowId();
    }

    static final class Selection implements AutoCloseable {
        final boolean recognized;
        TwoPanelTarget target;

        Selection(boolean recognized, TwoPanelTarget target) {
            this.recognized = recognized;
            this.target = target;
        }

        @Override
        public void close() {
            if (target != null) target.close();
        }
    }

    static Selection select(AccessibilityNodeInfo root, int x, int y, String packageName) {
        if (root == null || !packageName.contentEquals(root.getPackageName()))
            return new Selection(false, null);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo container = null, chosen = null, chosenList = null;
        String chosenTitle = null;
        int containers = 0, candidates = 0;
        boolean truncated = false;
        try {
            while (!queue.isEmpty() && nodes.size() < 512) {
                AccessibilityNodeInfo node = queue.removeFirst();
                nodes.add(node);
                if (id(packageName, "two_panel_fragment_container")
                                .equals(node.getViewIdResourceName())
                        && node.isVisibleToUser()) {
                    container = node;
                    containers++;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    if (queue.size() + nodes.size() >= 512) {
                        truncated = true;
                        break;
                    }
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
            }
            truncated |= !queue.isEmpty();
            // עץ חלקי בחבילת Settings אינו מוכיח שזהו OnePanel; אין לאפשר נפילה למגע עמום.
            if (truncated) return new Selection(true, null);
            if (containers == 0) return new Selection(false, null);
            if (containers != 1) return new Selection(true, null);
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = bounds(node);
                if (!bounds.contains(x, y) || !rowValid(node, packageName, root.getWindowId()))
                    continue;
                AccessibilityNodeInfo parent = node.getParent();
                try {
                    if (parent == null
                            || !id(packageName, "list").equals(parent.getViewIdResourceName())
                            || !inside(parent, container)) continue;
                    String title = title(node);
                    if (title == null) continue;
                    candidates++;
                    chosen = node;
                    chosenTitle = title;
                    if (chosenList != null) chosenList.recycle();
                    chosenList = AccessibilityNodeInfo.obtain(parent);
                } finally {
                    if (parent != null) parent.recycle();
                }
            }
            return new Selection(
                    true,
                    candidates == 1
                            ? new TwoPanelTarget(
                                    chosen, chosenList, container, packageName, chosenTitle)
                            : null);
        } finally {
            if (chosenList != null) chosenList.recycle();
            for (AccessibilityNodeInfo node : nodes) node.recycle();
            for (AccessibilityNodeInfo node : queue) node.recycle();
        }
    }

    boolean current(AccessibilityNodeInfo root, boolean requireFocused) {
        if (root == null
                || root.getWindowId() != window
                || !packageName.contentEquals(root.getPackageName())
                || !row.refresh()
                || !list.refresh()
                || !container.refresh()
                || !rowValid(row, packageName, window)
                || (requireFocused && !row.isFocused())
                || !className.equals(String.valueOf(row.getClassName()))
                || !Objects.equals(viewId, row.getViewIdResourceName())
                || !title.equals(title(row))
                || !id(packageName, "list").equals(list.getViewIdResourceName())
                || !id(packageName, "two_panel_fragment_container")
                        .equals(container.getViewIdResourceName())
                || !container.isVisibleToUser()
                || !inside(list, container)) return false;
        AccessibilityNodeInfo parent = row.getParent(),
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        try {
            // התאמת מקור הנגישות מונעת מעבר לשורה חדשה ששוחזרה באותו מיקום ברשימה.
            return parent != null
                    && parent.equals(list)
                    && (!requireFocused || (focused != null && focused.equals(row)))
                    && inside(container, root);
        } finally {
            if (parent != null) parent.recycle();
            if (focused != null) focused.recycle();
        }
    }

    private static boolean rowValid(AccessibilityNodeInfo node, String packageName, int window) {
        return packageName.contentEquals(node.getPackageName())
                && node.getWindowId() == window
                && node.isVisibleToUser()
                && node.isEnabled()
                && node.isFocusable()
                && node.isClickable()
                && !bounds(node).isEmpty()
                && (node.isFocused() || hasAction(node, AccessibilityNodeInfo.ACTION_FOCUS))
                && hasAction(node, AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static boolean hasAction(AccessibilityNodeInfo node, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
            if (action.getId() == id) return true;
        return false;
    }

    private static String title(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> titles =
                row.findAccessibilityNodeInfosByViewId("android:id/title");
        try {
            if (titles.size() != 1) return null;
            CharSequence text = titles.get(0).getText();
            return text == null || text.length() == 0 ? null : text.toString();
        } finally {
            for (AccessibilityNodeInfo node : titles) node.recycle();
        }
    }

    private static boolean inside(AccessibilityNodeInfo child, AccessibilityNodeInfo ancestor) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(child);
        try {
            for (int depth = 0; node != null && depth < 24; depth++) {
                if (node.equals(ancestor)) return true;
                AccessibilityNodeInfo parent = node.getParent();
                node.recycle();
                node = parent;
            }
            return false;
        } finally {
            if (node != null) node.recycle();
        }
    }

    private static String id(String packageName, String name) {
        return packageName + ":id/" + name;
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds;
    }

    @Override
    public void close() {
        row.recycle();
        list.recycle();
        container.recycle();
    }
}
