package org.yosef.karov.updates;

import static org.junit.Assert.*;

import android.content.pm.PackageInstaller;

import org.junit.Test;

public final class UpdatePolicyTest {
    @Test
    public void identifiesTelevisionAndPhoneIncludingDebugVariants() {
        assertTrue(UpdatePolicy.television("org.yosef.karov"));
        assertTrue(UpdatePolicy.television("org.yosef.karov.debug"));
        assertFalse(UpdatePolicy.television("org.yosef.karov.phone"));
        assertFalse(UpdatePolicy.television("org.yosef.karov.phone.debug"));
        assertFalse(UpdatePolicy.television("org.yosef.karov.phone.other"));
    }

    @Test
    public void televisionBackgroundCannotInstallEvenWithLegacyAutomaticPreferenceEnabled() {
        for (boolean automatic : new boolean[] {true, false})
            assertFalse(UpdatePolicy.automaticInstall(true, automatic));
        // גם קריאה ישירה מהשירות עם install=true אינה עוקפת את מדיניות הרקע.
        assertFalse(UpdatePolicy.mayInstall(true, true, true));
        assertFalse(UpdatePolicy.mayInstall(true, false, true));
        assertTrue(UpdatePolicy.mayInstall(true, true, false));
        assertFalse(UpdatePolicy.mayInstall(true, false, false));
        assertFalse(UpdatePolicy.localInstall(true));
    }

    @Test
    public void phoneKeepsAutomaticBackgroundAndManualInstallationPolicy() {
        assertTrue(UpdatePolicy.automaticInstall(false, true));
        assertFalse(UpdatePolicy.automaticInstall(false, false));
        assertTrue(UpdatePolicy.mayInstall(false, true, true));
        assertTrue(UpdatePolicy.mayInstall(false, true, false));
        assertFalse(UpdatePolicy.mayInstall(false, false, true));
        assertFalse(UpdatePolicy.mayInstall(false, false, false));
        assertTrue(UpdatePolicy.localInstall(false));
    }

    @Test
    public void televisionRequiresOfficialConfirmationOnModernAndroidAndOlderVersionsKeepDefault() {
        for (int sdk : new int[] {28, 29, 30}) {
            assertEquals(
                    PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
                    UpdatePolicy.userAction(sdk, true));
            assertEquals(
                    PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
                    UpdatePolicy.userAction(sdk, false));
        }
        for (int sdk : new int[] {31, 32, 33, 34, 35, 36}) {
            assertEquals(
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                    UpdatePolicy.userAction(sdk, true));
            assertEquals(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                    UpdatePolicy.userAction(sdk, false));
        }
    }
}
