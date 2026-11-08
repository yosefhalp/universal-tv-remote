package org.yosef.karov;

import static org.junit.Assert.*;

import org.junit.Test;

public final class StoragePolicyTest {
    @Test
    public void adoptedVolumeIsDetectedWithoutLegacyExternalFlag() {
        assertEquals("external", StoragePolicy.classify(false, true, false, "/unknown/base.apk"));
        assertEquals(
                "external",
                StoragePolicy.classify(false, false, false, "/mnt/expand/volume/app/base.apk"));
    }

    @Test
    public void externalEvidenceWinsWhenFirmwareReportsInternalUuid() {
        assertEquals("external", StoragePolicy.classify(true, true, true, "/data/app/base.apk"));
        assertEquals(
                "external",
                StoragePolicy.classify(false, true, true, "/mnt/expand/volume/app/base.apk"));
    }

    @Test
    public void missingUuidDoesNotCrashOrInventExternalStorage() {
        assertEquals("internal", StoragePolicy.classify(false, false, false, "/data/app/base.apk"));
        assertEquals("unknown", StoragePolicy.classify(false, false, false, null));
        assertEquals("internal", StoragePolicy.classify(false, true, true, null));
        assertTrue(StoragePolicy.localAdbSetupAllowed("internal"));
        assertTrue(StoragePolicy.localAdbSetupAllowed("unknown"));
        assertFalse(StoragePolicy.localAdbSetupAllowed("external"));
    }
}
