package com.jerrywolff.phonesyncusbc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTargetTypeTest {
    @Test
    fun `OneDrive target dispatches to OneDrive instead of local storage`() {
        assertEquals(
            ProviderBackupTarget("OneDrive", "com.microsoft.skydrive"),
            BackupTargetType.ONEDRIVE.providerTarget(),
        )
        assertEquals("Push to OneDrive", BackupTargetType.ONEDRIVE.primaryActionLabel())
    }

    @Test
    fun `Google Drive target dispatches to Google Drive`() {
        assertEquals(
            ProviderBackupTarget("Google Drive", "com.google.android.apps.docs"),
            BackupTargetType.GOOGLE_DRIVE.providerTarget(),
        )
    }

    @Test
    fun `phone and folder targets remain direct local copies`() {
        assertNull(BackupTargetType.PHONE_DOWNLOADS.providerTarget())
        assertNull(BackupTargetType.DOCUMENT_TREE.providerTarget())
    }
}