package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMetadataTest {
    @Test
    fun `uses meaningful parent folder as collection label`() {
        val labels = deriveFolderMetadata(
            "/Download/Phone Sync/SMS Exports/Family/messages.json",
            ConsentCategory.SMS_EXPORTS,
        )

        assertEquals("Family", labels.folderLabel)
        assertEquals("Family", labels.collectionLabel)
        assertEquals("Family", labels.recordLabel)
    }

    @Test
    fun `uses zip entry folder as the record collection label`() {
        val labels = deriveFolderMetadata(
            "/Downloads/backup.zip",
            ConsentCategory.CHAT_EXPORTS,
            "conversations/Project Team/messages.json",
        )

        assertEquals("Project Team", labels.folderLabel)
        assertEquals("Project Team", labels.collectionLabel)
        assertEquals("Project Team", labels.recordLabel)
    }

    @Test
    fun `falls back to category when folders are generic`() {
        val labels = deriveFolderMetadata(
            "/Downloads/Phone Sync/Exports/sms_exports/messages.json",
            ConsentCategory.SMS_EXPORTS,
        )

        assertEquals("Sms Exports", labels.collectionLabel)
    }
}