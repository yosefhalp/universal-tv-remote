package org.yosef.karov;

import org.json.JSONArray;
import org.json.JSONObject;

// המסלול מנורמל לתמונה עצמה, בלי השוליים השחורים. אין כאן פקודת shell או פיקסלים חופשיים.
public final class PointerSpec {
    public final String kind;
    public final int width, height, rotation, duration;
    public final float[][] points;
    public final float dx, dy;

    public PointerSpec(JSONObject value) throws Exception {
        kind = value.getString("kind");
        width = integer(value, "displayWidth", 1, 16384);
        height = integer(value, "displayHeight", 1, 16384);
        rotation = integer(value, "rotation", 0, 3);
        duration = integer(value, "duration", 40, 1500);
        if (!java.util.Set.of("tap", "drag", "scroll").contains(kind))
            throw new Exception("BAD_POINTER");
        JSONArray path = value.getJSONArray("points");
        if (path.length() < 1 || path.length() > 64 || !kind.equals("drag") && path.length() != 1)
            throw new Exception("BAD_POINTER");
        points = new float[path.length()][2];
        for (int i = 0; i < points.length; i++) {
            JSONArray point = path.getJSONArray(i);
            if (point.length() != 2) throw new Exception("BAD_POINTER");
            points[i][0] = number(point.getDouble(0), 0, 1);
            points[i][1] = number(point.getDouble(1), 0, 1);
        }
        dx = number(value.optDouble("dx", 0), -1, 1);
        dy = number(value.optDouble("dy", 0), -1, 1);
        if (kind.equals("scroll") && Math.abs(dx) + Math.abs(dy) < 0.001)
            throw new Exception("BAD_POINTER");
    }

    private static int integer(JSONObject value, String key, int min, int max) throws Exception {
        double number = value.getDouble(key);
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
            throw new Exception("BAD_POINTER");
        return (int) number;
    }

    private static float number(double value, float min, float max) throws Exception {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new Exception("BAD_POINTER");
        return (float) value;
    }

    public boolean matches(int w, int h, int r) {
        return width == w && height == h && rotation == r;
    }

    public float[][] path() {
        if (!kind.equals("scroll")) return points;
        // גלגלת חיובית גוללת את התוכן מטה; האצבע המדומה נעה בכיוון ההפוך.
        float x = points[0][0], y = points[0][1];
        return new float[][] {
            {x, y}, {Math.max(0, Math.min(1, x - dx * .4f)), Math.max(0, Math.min(1, y - dy * .4f))}
        };
    }
}
