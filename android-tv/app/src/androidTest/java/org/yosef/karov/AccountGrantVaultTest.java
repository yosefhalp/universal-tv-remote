package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

public final class AccountGrantVaultTest {
    @Test
    public void onlyExistingControllerCanGrantAndRetriesAreIdempotent() throws Exception {
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context original = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preference = "account-grant-test-" + System.nanoTime();
        Context isolated =
                new ContextWrapper(original) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return original.getSharedPreferences(preference, mode);
                    }
                };
        try {
            // כספת בדיקה מבודדת לחלוטין; אין מחיקה או החלפה של הזיווגים האמיתיים באמולטור.
            var constructor = Vault.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Vault vault = constructor.newInstance(isolated);
            String parent = Crypto.id(),
                    parentSecret = Crypto.secret(),
                    child = Crypto.id(),
                    childSecret = Crypto.secret();
            vault.pairing();
            vault.enroll(vault.secretFor("pair"), parent, parentSecret, "בקר קיים");
            assertThrows(
                    Exception.class,
                    () -> vault.grantAccount(Crypto.id(), child, childSecret, "חשבון"));
            assertEquals("", vault.secretFor(child));
            vault.grantAccount(parent, child, childSecret, "חשבון");
            vault.grantAccount(parent, child, childSecret, "חשבון");
            assertEquals(2, vault.controllers().length());
            assertEquals(childSecret, vault.secretFor(child));
            assertEquals(parentSecret, vault.secretFor(parent));
            assertThrows(
                    Exception.class,
                    () -> vault.grantAccount(parent, child, Crypto.secret(), "החלפת מפתח אסורה"));
            assertEquals(childSecret, vault.secretFor(child));
            vault.revoke(parent);
            assertThrows(
                    Exception.class,
                    () -> vault.grantAccount(parent, Crypto.id(), Crypto.secret(), "הרשאה שבוטלה"));
            Vault reopened = constructor.newInstance(isolated);
            assertEquals(childSecret, reopened.secretFor(child));
            String ticket = Crypto.secret();
            reopened.grantAccount(child, child, childSecret, "חשבון", ticket);
            Vault durable = constructor.newInstance(isolated);
            assertEquals(ticket, durable.accountLinks().getJSONObject(0).getString("ticket"));
            durable.accountLinked(Crypto.secret());
            assertEquals(1, durable.accountLinks().length());
            durable.accountLinked(ticket);
            assertEquals(0, constructor.newInstance(isolated).accountLinks().length());
            String paired = Crypto.id(), pairTicket = Crypto.secret();
            durable.pairing();
            durable.enroll(
                    durable.secretFor("pair"), paired, Crypto.secret(), "זיווג לחשבון", pairTicket);
            Vault afterPair = constructor.newInstance(isolated);
            assertEquals("", afterPair.secretFor("pair"));
            assertEquals(pairTicket, afterPair.accountLinks().getJSONObject(0).getString("ticket"));
            afterPair.revoke(paired);
            assertEquals(0, constructor.newInstance(isolated).accountLinks().length());
        } finally {
            original.deleteSharedPreferences(preference);
        }
    }
}
