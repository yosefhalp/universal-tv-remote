package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.res.Configuration;

import org.junit.Test;

public final class PointerCapabilitiesTest {
    @Test
    public void androidNineAndTenPreserveExistingGesturePathWithoutTouchFeatures() {
        for (int sdk : new int[] {28, 29}) {
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_NOTOUCH, false));
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_UNDEFINED, false));
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_FINGER, false));
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_NOTOUCH, true));
        }
    }

    @Test
    public void androidElevenRequiresFingerConfigurationEvenWhenFakeTouchIsDeclared() {
        assertFalse(PointerCapabilities.policy(30, Configuration.TOUCHSCREEN_NOTOUCH, false));
        assertFalse(PointerCapabilities.policy(30, Configuration.TOUCHSCREEN_NOTOUCH, true));
        assertFalse(PointerCapabilities.policy(30, Configuration.TOUCHSCREEN_UNDEFINED, true));
        assertTrue(PointerCapabilities.policy(30, Configuration.TOUCHSCREEN_FINGER, false));
        assertTrue(PointerCapabilities.policy(30, Configuration.TOUCHSCREEN_FINGER, true));
    }

    @Test
    public void androidTwelveAndNewerUseFakeTouchInsteadOfDisplayFingerConfiguration() {
        for (int sdk : new int[] {31, 32, 33, 34, 35, 36}) {
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_NOTOUCH, true));
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_UNDEFINED, true));
            assertTrue(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_FINGER, true));
            assertFalse(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_FINGER, false));
            assertFalse(PointerCapabilities.policy(sdk, Configuration.TOUCHSCREEN_NOTOUCH, false));
        }
    }
}
