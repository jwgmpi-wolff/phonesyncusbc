package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySelectionPlanTest {
    @Test
    fun `mixed selection continues with valid source entries and actionable exclusions`() {
        val valid = entry(1, "peer-a", "/DCIM/photo.jpg", "fingerprint-a")
        val collector = entry(2, "peer-a", "/Download/Phone Sync/Selected folder/local.jpg", "fingerprint-b")
        val otherPeer = entry(3, "peer-b", "/DCIM/other.jpg", "fingerprint-c")

        val plan = planExternalRecoveryEntries(listOf(valid, collector, otherPeer), "peer-a")

        assertEquals(listOf(valid), plan.eligibleEntries)
        assertEquals(2, plan.excludedItems)
        assertTrue(plan.issues.any { it.reason == RecoveryIssueReason.COLLECTOR_ORIGIN })
        assertTrue(plan.issues.any { it.reason == RecoveryIssueReason.DIFFERENT_SOURCE_PEER })
        assertTrue(plan.issues.all { it.remediation.isNotBlank() })
    }

    @Test
    fun `failed acquisition is retryable`() {
        val failed = entry(1, "peer-a", "/Downloads/chat-export.zip", "fingerprint-a")
            .copy(status = TransferStatus.FAILED, destination = null)

        val issue = planExternalRecoveryEntries(listOf(failed), "peer-a").issues.single()

        assertEquals(RecoveryIssueReason.TRANSFER_NOT_COMPLETED, issue.reason)
        assertTrue(issue.retryable)
    }

    private fun entry(id: Long, peerId: String, sourceItem: String, fingerprint: String) = AuditEntry(
        id = id,
        transferredAtEpochMillis = 1,
        category = ConsentCategory.DOCUMENTS,
        sourceItem = sourceItem,
        destination = "content://recovered/$id",
        bytesTransferred = 1,
        status = TransferStatus.COMPLETED,
        error = null,
        sourceSize = 1,
        sourceModifiedAtEpochMillis = 1,
        contentSha256 = "hash-$id",
        peerId = peerId,
        sourceFingerprint = fingerprint,
    )
}