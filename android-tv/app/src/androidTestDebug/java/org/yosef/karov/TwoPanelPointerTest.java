package org.yosef.karov;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// כל הבדיקות רצות על מסך debug סינתטי בלבד באמולטור, עם קוד בחירת ה-node של המוצר.
public final class TwoPanelPointerTest {
    private Context context;
    private UiAutomation automation;

    @Before
    public void fixture() throws Exception {
        assertTrue(
                BuildConfig.DEBUG
                        && (Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        automation =
                InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        context.startActivity(
                new Intent(context, TwoPanelProbeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            try {
                if (root != null
                        && context.getPackageName().contentEquals(root.getPackageName())
                        && hasContainer(root)) return;
            } finally {
                if (root != null) root.recycle();
            }
        }
        fail("מסך הבדיקה לא הופיע");
    }

    @Test
    public void focusesMovesRefreshesAndClicksExactRowOnce() throws Exception {
        Rect before = systemBounds();
        Outcome outcome = tap(200, null, null, null);
        assertNull(outcome.error);
        assertEquals(Boolean.TRUE, outcome.value);
        assertEquals(1, TwoPanelProbeActivity.focusEvents);
        assertEquals(1, TwoPanelProbeActivity.clicks);
        assertEquals(0, TwoPanelProbeActivity.wrongClicks);
        assertTrue(systemBounds().top < before.top);
    }

    @Test
    public void clientDisconnectDuringFocusWaitNeverClicks() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        Outcome outcome = tap(200, current, () -> current.set(false), null);
        assertEquals("DISCONNECTED", outcome.error);
        Thread.sleep(500);
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    @Test
    public void explicitCancellationRemovesQueuedClick() throws Exception {
        Outcome outcome = tap(200, null, null, "cancel");
        assertEquals("DISCONNECTED", outcome.error);
        Thread.sleep(500);
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    @Test
    public void recycledRowWithDifferentTitleNeverClicks() throws Exception {
        Outcome outcome = tap(200, null, () -> TwoPanelProbeActivity.instance.replaceTitle(), null);
        assertEquals("POINTER_TARGET_UNAVAILABLE", outcome.error);
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    @Test
    public void changedActiveWindowNeverClicks() throws Exception {
        Outcome outcome =
                tap(
                        200,
                        null,
                        () ->
                                context.startActivity(
                                        new Intent(context, PointerProbeActivity.class)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
                        null);
        assertEquals("POINTER_TARGET_UNAVAILABLE", outcome.error);
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    @Test
    public void longTapIsRejectedWithoutFocusOrFallback() throws Exception {
        Outcome outcome = tap(700, null, null, null);
        assertEquals("POINTER_TARGET_UNAVAILABLE", outcome.error);
        assertEquals(0, TwoPanelProbeActivity.focusEvents);
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    @Test
    public void actualPackageGuardRejectsSyntheticApp() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        try (TwoPanelTarget.Selection selection =
                TwoPanelTarget.select(root, 400, 800, TwoPanelTarget.SETTINGS)) {
            assertFalse(selection.recognized);
            assertNull(selection.target);
        } finally {
            if (root != null) root.recycle();
        }
    }

    @Test
    public void completeOnePanelTreeKeepsExistingPath() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> TwoPanelProbeActivity.instance.onePanel());
        Thread.sleep(150);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        try (TwoPanelTarget.Selection selection =
                TwoPanelTarget.select(root, 400, 800, context.getPackageName())) {
            assertFalse(selection.recognized);
            assertNull(selection.target);
        } finally {
            if (root != null) root.recycle();
        }
    }

    @Test
    public void truncatedTreeWithoutContainerFailsClosed() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> TwoPanelProbeActivity.instance.truncatedTree());
        Thread.sleep(200);
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        try (TwoPanelTarget.Selection selection =
                TwoPanelTarget.select(root, 400, 800, context.getPackageName())) {
            assertTrue("עץ חלקי לא יכול להוכיח שזהו OnePanel", selection.recognized);
            assertNull(selection.target);
        } finally {
            if (root != null) root.recycle();
        }
        assertEquals(0, TwoPanelProbeActivity.clicks);
    }

    private Rect systemBounds() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        assertNotNull(root);
        List<AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText("System");
        try {
            assertEquals(1, labels.size());
            AccessibilityNodeInfo row = labels.get(0).getParent();
            try {
                Rect bounds = new Rect();
                row.getBoundsInScreen(bounds);
                return bounds;
            } finally {
                row.recycle();
            }
        } finally {
            for (AccessibilityNodeInfo node : labels) node.recycle();
            root.recycle();
        }
    }

    private Outcome tap(int duration, AtomicBoolean state, Runnable change, String cancel)
            throws Exception {
        Rect bounds = systemBounds();
        JSONObject args =
                PointerActions.geometry(context, new JSONObject())
                        .put("kind", "tap")
                        .put("duration", duration);
        args.put(
                "points",
                new JSONArray()
                        .put(
                                new JSONArray()
                                        .put(bounds.centerX() / (args.getInt("displayWidth") - 1.0))
                                        .put(
                                                bounds.centerY()
                                                        / (args.getInt("displayHeight") - 1.0))));
        PointerSpec spec = new PointerSpec(args);
        CountDownLatch done = new CountDownLatch(1);
        Outcome outcome = new Outcome();
        AtomicBoolean current = state == null ? new AtomicBoolean(true) : state;
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> {
                    PointerActions actions = new PointerActions(context);
                    assertTrue(
                            actions.twoPanelTap(
                                    automation::getRootInActiveWindow,
                                    () -> true,
                                    spec,
                                    current::get,
                                    (value, error) -> {
                                        outcome.value = value;
                                        outcome.error = error;
                                        done.countDown();
                                    },
                                    context.getPackageName()));
                    if (change != null)
                        new Handler(Looper.getMainLooper()).postDelayed(change, 150);
                    if (cancel != null)
                        new Handler(Looper.getMainLooper()).postDelayed(actions::disconnected, 150);
                });
        assertTrue("לפעולה חייבת להיות תשובה קצובה", done.await(5, TimeUnit.SECONDS));
        return outcome;
    }

    private static final class Outcome {
        Object value;
        String error;
    }

    private boolean hasContainer(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(
                        context.getPackageName() + ":id/two_panel_fragment_container");
        try {
            return nodes.size() == 1;
        } finally {
            for (AccessibilityNodeInfo node : nodes) node.recycle();
        }
    }
}
