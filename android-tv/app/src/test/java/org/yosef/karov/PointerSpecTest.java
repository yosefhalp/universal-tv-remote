package org.yosef.karov;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public final class PointerSpecTest {
    private JSONObject valid() throws Exception {
        return new JSONObject()
                .put("kind", "tap")
                .put("points", new JSONArray("[[0.5,0.5]]"))
                .put("duration", 60)
                .put("displayWidth", 1920)
                .put("displayHeight", 1080)
                .put("rotation", 0);
    }

    @Test
    public void checksRealDisplayAndRotation() throws Exception {
        PointerSpec spec = new PointerSpec(valid());
        assertTrue(spec.matches(1920, 1080, 0));
        assertFalse(spec.matches(1080, 1920, 1));
        assertFalse(spec.matches(1920, 1080, 2));
    }

    @Test
    public void rejectsUnboundedInput() throws Exception {
        assertThrows(Exception.class, () -> new PointerSpec(valid().put("duration", 10000)));
        assertThrows(Exception.class, () -> new PointerSpec(valid().put("displayWidth", 1.5)));
        assertThrows(
                Exception.class,
                () -> new PointerSpec(valid().put("points", new JSONArray("[[-0.01,0.5]]"))));
        assertThrows(
                Exception.class,
                () -> new PointerSpec(valid().put("points", new JSONArray("[[0.5,1.1]]"))));
        assertThrows(Exception.class, () -> new PointerSpec(valid().put("kind", "shell")));
        JSONArray many = new JSONArray();
        for (int i = 0; i < 65; i++) many.put(new JSONArray("[0.5,0.5]"));
        assertThrows(
                Exception.class,
                () -> new PointerSpec(valid().put("kind", "drag").put("points", many)));
    }

    @Test
    public void scrollPathStaysInsideAndMovesOppositeContent() throws Exception {
        PointerSpec spec =
                new PointerSpec(
                        valid().put("kind", "scroll")
                                .put("dy", 1)
                                .put("points", new JSONArray("[[0.99,0.01]]")));
        float[][] path = spec.path();
        assertEquals(.99f, path[0][0], .0001f);
        assertEquals(.01f, path[0][1], .0001f);
        assertTrue(path[1][1] < path[0][1]);
        assertTrue(path[1][1] >= 0);
        assertThrows(
                Exception.class, () -> new PointerSpec(valid().put("kind", "scroll").put("dy", 0)));
    }
}
