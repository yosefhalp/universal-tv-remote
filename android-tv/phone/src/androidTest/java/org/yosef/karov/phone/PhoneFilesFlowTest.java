package org.yosef.karov.phone;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public final class PhoneFilesFlowTest {
    @Rule
    public ActivityTestRule<MainActivity> activity =
            new ActivityTestRule<>(MainActivity.class, false, false);

    private final ArrayList<String> calls = new ArrayList<>();
    private final ArrayList<CompletableFuture<Object>> captures = new ArrayList<>();
    private RemoteClient fixture;
    private MainActivity phone;

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2)
                result.put((String) values[i], values[i + 1]);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        return result;
    }

    private void set(String name, Object value) throws Exception {
        var field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(phone, value);
    }

    private Object invoke(String name, Class<?> type, Object value) throws Exception {
        var method =
                type == null
                        ? MainActivity.class.getDeclaredMethod(name)
                        : MainActivity.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return type == null ? method.invoke(phone) : method.invoke(phone, value);
    }

    private interface Step {
        void run() throws Exception;
    }

    private void ui(Step step) {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> {
                    try {
                        step.run();
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    private void withFixture(Step test) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("הבדיקה פועלת רק בחבילת מעבדה", target.getPackageName().endsWith(".debug"));
        SharedPreferences prefs =
                target.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String saved = prefs.getString("sealed", null);
        assertTrue(prefs.edit().remove("sealed").commit());
        fixture =
                new RemoteClient("", "", "", null, reason -> {}) {
                    @Override
                    CompletableFuture<Object> call(String method, JSONObject args, int timeout) {
                        calls.add(method);
                        if (method.equals("capture")) {
                            CompletableFuture<Object> reply = new CompletableFuture<>();
                            captures.add(reply);
                            return reply;
                        }
                        return CompletableFuture.completedFuture(
                                switch (method) {
                                    case "fileDestinations" ->
                                            object(
                                                    "destinations",
                                                    new JSONArray()
                                                            .put(
                                                                    object(
                                                                            "id",
                                                                            "disk",
                                                                            "name",
                                                                            "אחסון בדיקה",
                                                                            "kind",
                                                                            "internal",
                                                                            "writable",
                                                                            true,
                                                                            "freeBytes",
                                                                            1048576,
                                                                            "totalBytes",
                                                                            2097152)),
                                                    "folderPicker",
                                                    false);
                                    case "fileList" ->
                                            object(
                                                    "files",
                                                    new JSONArray()
                                                            .put(
                                                                    object(
                                                                            "id",
                                                                            "file",
                                                                            "name",
                                                                            "בדיקה.txt",
                                                                            "bytes",
                                                                            1024,
                                                                            "canDelete",
                                                                            true)),
                                                    "offset",
                                                    0,
                                                    "hasMore",
                                                    false);
                                    case "fileDelete" -> {
                                        assertEquals("disk", args.optString("destination"));
                                        assertEquals("file", args.optJSONArray("ids").optString(0));
                                        yield object("removed", 1, "failed", 0, "freedBytes", 1024);
                                    }
                                    default -> true;
                                });
                    }
                };
        try {
            phone = activity.launchActivity(new Intent());
            ui(() -> {
                set(
                        "selected",
                        object("device", "fake", "controller", "fake", "name", "סטרימר בדיקה"));
                set("connection", fixture);
                set("connected", true);
                set("filesSupported", true);
                invoke("render", null, null);
            });
            test.run();
        } finally {
            if (phone != null) activity.finishActivity();
            SharedPreferences.Editor editor = prefs.edit();
            if (saved == null) editor.remove("sealed");
            else editor.putString("sealed", saved);
            assertTrue(editor.commit());
        }
    }

    @Test
    public void fileDeleteRequiresConfirmationAndDisplaysResult() throws Exception {
        withFixture(() -> {
            ui(() -> invoke("options", null, null));
            onView(withText("קבצים ופינוי מקום")).inRoot(isDialog()).perform(click());
            onView(withText("אחסון בדיקה")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("קבצים ופינוי")).inRoot(isDialog()).perform(click());
            onView(withText("בדיקה.txt")).inRoot(isDialog()).perform(click());
            onView(withText("מחיקת קבצים שסומנו")).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withText("מחיקה לצמיתות")).inRoot(isDialog()).check(matches(isDisplayed()));
            ui(() -> assertFalse(calls.contains("fileDelete")));
            onView(withText("מחיקה לצמיתות")).inRoot(isDialog()).perform(click());
            onView(withText("תוצאות פינוי מקום")).inRoot(isDialog()).check(matches(isDisplayed()));
            ui(() -> assertEquals(1, calls.stream().filter("fileDelete"::equals).count()));
        });
    }

    @Test
    public void keyDoesNotWaitForCaptureAndAckRequestsOneFreshCapture() throws Exception {
        withFixture(() -> {
            ui(() -> {
                invoke("capture", RemoteClient.class, fixture);
                assertEquals(1, captures.size());
                invoke("key", String.class, "HOME");
                assertEquals(java.util.List.of("capture", "key"), calls);
            });
            ui(() -> {
                assertEquals(1, captures.size());
                captures.get(0).complete(new JSONObject());
            });
            ui(() -> {
                assertEquals(java.util.List.of("capture", "key", "capture"), calls);
                assertEquals(2, captures.size());
            });
        });
    }
}
