package org.yosef.karov.updates;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public final class UpdateSpecTest {
    private JSONObject valid() throws Exception {
        return new JSONObject()
                .put("applicationId", "org.yosef.karov")
                .put("versionCode", 4)
                .put("versionName", "1.1.1")
                .put("minSdk", 28)
                .put("bytes", 10240)
                .put("sha256", "a".repeat(64))
                .put("path", "karov-tv-1.1.1.apk");
    }

    @Test
    public void acceptsBoundedSamePackage() throws Exception {
        assertEquals(4, new UpdateSpec(valid(), "org.yosef.karov").versionCode);
    }

    @Test
    public void rejectsExternalOrTraversingPaths() throws Exception {
        for (String path :
                new String[] {
                    "https://bad.test/a.apk",
                    "../a.apk",
                    "karov-tv-1.1.1.apk?x=1",
                    "/karov-tv-1.1.1.apk"
                }) {
            JSONObject value = valid().put("path", path);
            assertThrows(Exception.class, () -> new UpdateSpec(value, "org.yosef.karov"));
        }
    }

    @Test
    public void rejectsDifferentPackageOversizeAndBadHash() throws Exception {
        for (JSONObject value :
                new JSONObject[] {
                    valid().put("applicationId", "elsewhere"),
                    valid().put("bytes", UpdateSpec.MAX_BYTES + 1),
                    valid().put("sha256", "no"),
                    valid().put("versionCode", -1)
                }) assertThrows(Exception.class, () -> new UpdateSpec(value, "org.yosef.karov"));
    }
}
