package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

public final class PairingVaultTest {
    @Test
    public void rotationAndConsumptionRejectPreviousQrSession() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(
                BuildConfig.DEBUG
                        && (android.os.Build.HARDWARE.equals("ranchu")
                                || android.os.Build.HARDWARE.equals("goldfish")));
        Vault vault = Vault.open(context);
        // הבדיקה פועלת רק בכספת של יישום הפיתוח באמולטור, ולא נוגעת בזיווגי גרסת המסירה.
        vault.revokeAll();
        vault.pairing();
        String old = vault.secretFor("pair");
        vault.pairing();
        String current = vault.secretFor("pair"), id = Crypto.id(), secret = Crypto.secret();
        assertThrows(Exception.class, () -> vault.enroll(old, id, secret, "ישן"));
        vault.enroll(current, id, secret, "בדיקה");
        assertEquals(secret, vault.secretFor(id));
        assertTrue(vault.secretFor("pair").isEmpty());
        assertThrows(
                Exception.class, () -> vault.enroll(current, Crypto.id(), Crypto.secret(), "חוזר"));
        vault.revokeAll();
    }
}
