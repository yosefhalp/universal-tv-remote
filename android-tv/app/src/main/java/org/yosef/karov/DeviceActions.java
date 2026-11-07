package org.yosef.karov;

import android.content.*;
import android.content.pm.*;
import android.media.AudioManager;
import android.os.*;
import android.view.KeyEvent;

import org.json.*;

import java.util.*;
import java.util.function.BooleanSupplier;

public final class DeviceActions {
    public interface Result {
        void done(Object value, String error);
    }

    private final Context context;
    private final PointerActions pointer;
    private final Handler main = new Handler(Looper.getMainLooper());

    public DeviceActions(Context context) {
        this.context = context.getApplicationContext();
        pointer = new PointerActions(this.context);
    }

    public JSONObject info() throws Exception {
        LocalAdb adb = LocalAdb.get(context);
        String captureMode =
                ScreenService.available()
                        ? "projection"
                        : Build.VERSION.SDK_INT >= 30 && TvAccessibilityService.instance != null
                                ? "accessibility"
                                : adb.ready() ? "adb" : "permission";
        return new JSONObject()
                .put("name", Vault.open(context).get("name"))
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("localAdb", adb.ready())
                .put("pointer", TvAccessibilityService.instance != null || adb.ready())
                .put("remoteStatus", adb.status())
                .put("accessibility", TvAccessibilityService.instance != null)
                .put("captureMode", captureMode)
                .put("permanentCaptureConsent", CaptureConsentActivity.permanentConsent(context));
    }

    public void active(boolean active) {
        main.post(() -> {
            if (!active) pointer.disconnected();
            WakeActivity.session(context, active);
            if (TvAccessibilityService.instance != null)
                TvAccessibilityService.instance.showActive(active);
        });
    }

    public void execute(String method, JSONObject args, BooleanSupplier current, Result callback) {
        main.post(() -> {
            if (!current.getAsBoolean()) {
                callback.done(null, "DISCONNECTED");
                return;
            }
            try {
                TvAccessibilityService service = TvAccessibilityService.instance;
                LocalAdb remote = LocalAdb.get(context);
                switch (method) {
                    case "info" -> callback.done(info(), null);
                    case "pointer" -> pointer.execute(args, current, callback);
                    case "updateInfo" ->
                            callback.done(
                                    org.yosef.karov.updates.UpdateEngine.get(context).info(), null);
                    case "updateCheck", "updateInstall" ->
                            callback.done(
                                    org.yosef.karov.updates.UpdateEngine.get(context)
                                            .request(method.equals("updateInstall"), false, null),
                                    null);
                    case "apps" -> callback.done(apps(), null);
                    case "capture" -> {
                        if (MainActivity.visible) {
                            callback.done(null, "SECURE_CONTENT");
                            return;
                        }
                        if (ScreenService.available()) ScreenService.capture(callback);
                        else if (service != null && Build.VERSION.SDK_INT >= 30)
                            service.capture(callback);
                        else if (remote.ready()) remote.capture(current, callback);
                        else ScreenService.capture(callback);
                    }
                    case "key" -> {
                        if (service == null && !remote.ready()) {
                            callback.done(null, "NO_ACCESSIBILITY");
                            return;
                        }
                        String key = args.getString("key");
                        if (remote.ready() && !key.equals("SETTINGS"))
                            remote.key(key, current, callback);
                        else callback.done(key(key, service), null);
                    }
                    case "text" -> {
                        if (service == null) {
                            callback.done(null, "NO_ACCESSIBILITY");
                            return;
                        }
                        String text = args.getString("text");
                        if (text.length() > 1000) throw new Exception("BAD_INPUT");
                        if (service.text(text)) callback.done(true, null);
                        else callback.done(null, "NO_TEXT_FIELD");
                    }
                    case "adbEnable" -> {
                        remote.enable(true);
                        callback.done(true, null);
                    }
                    case "adbDisable" -> {
                        remote.enable(false);
                        callback.done(true, null);
                    }
                    case "launch" -> {
                        if (service == null) {
                            callback.done(null, "NO_ACCESSIBILITY");
                            return;
                        }
                        String pkg = args.getString("package");
                        boolean found = false;
                        JSONArray apps = apps();
                        for (int i = 0; i < apps.length(); i++)
                            if (pkg.equals(apps.getJSONObject(i).getString("id"))) found = true;
                        if (!found) throw new Exception("UNSUPPORTED");
                        PackageManager pm = context.getPackageManager();
                        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
                        if (intent == null) throw new Exception("UNSUPPORTED");
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        callback.done(true, null);
                    }
                    case "requestCapture" -> {
                        if (service == null) {
                            callback.done(null, "NO_ACCESSIBILITY");
                            return;
                        }
                        CaptureConsentActivity.request(context, false);
                        callback.done(true, null);
                    }
                    default -> callback.done(null, "UNSUPPORTED");
                }
            } catch (Exception error) {
                callback.done(
                        null,
                        java.util.Set.of("UNSUPPORTED", "BAD_POINTER")
                                        .contains(String.valueOf(error.getMessage()))
                                ? error.getMessage()
                                : "FAILED");
            }
        });
    }

    private boolean key(String key, TvAccessibilityService service) throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        switch (key) {
            case "SETTINGS" -> {
                if (!SystemSettings.open(context, false)) throw new Exception("UNSUPPORTED");
                return true;
            }
            case "VOLUME_UP", "VOLUME_DOWN", "MUTE" -> {
                audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        key.equals("MUTE")
                                ? AudioManager.ADJUST_TOGGLE_MUTE
                                : key.equals("VOLUME_UP")
                                        ? AudioManager.ADJUST_RAISE
                                        : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
                return true;
            }
            case "PLAY_PAUSE", "REWIND", "FAST_FORWARD" -> {
                int code =
                        key.equals("PLAY_PAUSE")
                                ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                : key.equals("REWIND")
                                        ? KeyEvent.KEYCODE_MEDIA_REWIND
                                        : KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                return true;
            }
            default -> {
                if (!service.key(key)) throw new Exception("UNSUPPORTED");
                return true;
            }
        }
    }

    private JSONArray apps() throws Exception {
        PackageManager manager = context.getPackageManager();
        Map<String, String> list = new TreeMap<>();
        for (String category :
                new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info :
                    manager.queryIntentActivities(
                            new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (!info.activityInfo.packageName.equals(context.getPackageName()))
                    list.put(info.activityInfo.packageName, info.loadLabel(manager).toString());
            }
        }
        JSONArray result = new JSONArray();
        for (Map.Entry<String, String> entry : list.entrySet())
            result.put(new JSONObject().put("id", entry.getKey()).put("name", entry.getValue()));
        return result;
    }
}
