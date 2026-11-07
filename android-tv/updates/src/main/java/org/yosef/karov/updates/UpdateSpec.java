package org.yosef.karov.updates;

import org.json.JSONObject;

// נתוני השרת הם מידע לא מהימן. גם לאחר בדיקתם נדרשת חתימת APK זהה לאפליקציה המותקנת.
public final class UpdateSpec {
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    public final String applicationId, versionName, path, sha256, notes;
    public final long versionCode, bytes;
    public final int minSdk;

    public UpdateSpec(JSONObject value, String expectedPackage) throws Exception {
        applicationId = value.getString("applicationId");
        versionName = value.getString("versionName");
        versionCode = value.getLong("versionCode");
        bytes = value.getLong("bytes");
        minSdk = value.getInt("minSdk");
        path = value.getString("path");
        sha256 = value.getString("sha256");
        notes = value.optString("notes", "");
        if (!applicationId.equals(expectedPackage)
                || !versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || versionCode < 1
                || versionCode > Integer.MAX_VALUE
                || bytes < 1024
                || bytes > MAX_BYTES
                || minSdk < 28
                || minSdk > 100
                || !path.matches("karov-(tv|phone)-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")
                || !sha256.matches("[a-f0-9]{64}")
                || notes.length() > 1000) throw new Exception("UPDATE_METADATA");
    }

    public JSONObject json() throws Exception {
        return new JSONObject()
                .put("applicationId", applicationId)
                .put("versionName", versionName)
                .put("versionCode", versionCode)
                .put("bytes", bytes)
                .put("minSdk", minSdk)
                .put("path", path)
                .put("sha256", sha256)
                .put("notes", notes);
    }
}
