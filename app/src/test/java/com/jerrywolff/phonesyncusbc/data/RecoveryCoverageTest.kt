package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCoverageTest {
    @Test
    fun `raw iOS database and attachments do not falsely satisfy message coverage`() {
        val entries = listOf(
            entry("/Owner-approved iPhone backup/Library/SMS/sms.db"),
            entry("/Owner-approved iPhone backup/Library/SMS/Attachments/ios-message-attachments.zip"),
        )

        assertFalse(ConsentCategory.SMS_EXPORTS in recoveredCoverageCategories(entries))
    }

    @Test
    fun `searchable iOS message export satisfies message coverage`() {
        val entries = listOf(
            entry("/Owner-approved iPhone backup/Library/SMS/sms.db"),
            entry("/Owner-approved iPhone backup/Library/SMS/ios-messages.jsonl"),
        )

        assertTrue(ConsentCategory.SMS_EXPORTS in recoveredCoverageCategories(entries))
    }

    @Test
    fun `owner-created SMS export satisfies message coverage`() {
        assertTrue(
            ConsentCategory.SMS_EXPORTS in recoveredCoverageCategories(
                listOf(entry("/Downloads/sms-backup.zip")),
            ),
        )
    }

    private fun entry(sourceItem: String) = AuditEntry(
        id = sourceItem.hashCode().toLong(),
        transferredAtEpochMillis = 1,
        category = ConsentCategory.SMS_EXPORTS,
        sourceItem = sourceItem,
        destination = "content://recovered/${sourceItem.hashCode()}",
        bytesTransferred = 1,
        status = TransferStatus.COMPLETED,
        error = null,
        peerId = "external-peer",
        sourceFingerprint = "fingerprint-${sourceItem.hashCode()}",
    )
}