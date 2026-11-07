package org.yosef.karov;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PointerAdbTest {
    @Test
    public void approvedLocalConnectionInjectsBoundedPointerCommands() throws Exception {
        // מריצים רק באמולטור debug שכבר אושר דרך חלון Android, כאשר מסך הבדיקה פתוח.
        assertTrue(
                BuildConfig.DEBUG
                        && (android.os.Build.HARDWARE.equals("ranchu")
                                || android.os.Build.HARDWARE.equals("goldfish")));
        LocalAdb adb =
                LocalAdb.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
        adb.start();
        for (int i = 0; i < 50 && !adb.ready(); i++) Thread.sleep(200);
        assertTrue(adb.status(), adb.ready());
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.startActivity(
                new android.content.Intent()
                        .setClassName(
                                context.getPackageName(), "org.yosef.karov.PointerProbeActivity")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        var automation =
                InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(
                                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        boolean visible = false;
        for (int i = 0; i < 30 && !visible; i++) {
            Thread.sleep(200);
            var tree = automation.getRootInActiveWindow();
            visible = tree != null && !tree.findAccessibilityNodeInfosByText("לחיצות: 0").isEmpty();
        }
        assertTrue("מסך בדיקת המחוות חייב להופיע לפני הזרקת הקלט", visible);
        call(adb, "tap", new double[][] {{486 / 1920., 80 / 1080.}}, 0);
        call(
                adb,
                "drag",
                new double[][] {{486 / 1920., 250 / 1080.}, {800 / 1920., 250 / 1080.}},
                0);
        call(adb, "scroll", new double[][] {{1400 / 1920., 600 / 1080.}}, .65);
        Thread.sleep(800);
        var root = automation.getRootInActiveWindow();
        assertNotNull(root);
        assertEquals(1, root.findAccessibilityNodeInfosByText("לחיצות: 1").size());
        var dragged = root.findAccessibilityNodeInfosByText("ערך גרירה:");
        assertEquals(1, dragged.size());
        assertFalse(dragged.get(0).getText().toString().endsWith("50"));
        var scrolled = root.findAccessibilityNodeInfosByText("גלילה:");
        assertEquals(1, scrolled.size());
        assertFalse(scrolled.get(0).getText().toString().equals("גלילה: 0"));
    }

    private void call(LocalAdb adb, String kind, double[][] points, double dy) throws Exception {
        JSONArray path = new JSONArray();
        for (double[] p : points) path.put(new JSONArray().put(p[0]).put(p[1]));
        PointerSpec spec =
                new PointerSpec(
                        new JSONObject()
                                .put("displayWidth", 1920)
                                .put("displayHeight", 1080)
                                .put("rotation", 0)
                                .put("kind", kind)
                                .put("points", path)
                                .put("duration", 300)
                                .put("dy", dy));
        CountDownLatch done = new CountDownLatch(1);
        Object[] error = {null};
        adb.pointer(
                spec,
                () -> true,
                (value, failure) -> {
                    error[0] = failure;
                    done.countDown();
                });
        assertTrue(done.await(8, TimeUnit.SECONDS));
        assertNull(error[0]);
    }
}
