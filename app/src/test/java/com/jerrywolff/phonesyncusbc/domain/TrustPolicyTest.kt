package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPolicyTest {
    private val context = TrustContext(
        peerDeviceId = "source-1",
        localDeviceId = "target-1",
        encryptionKeyProof = "key-1",
    )

    private val record = TrustRecord(
        peerDeviceId = context.peerDeviceId,
        localDeviceId = context.localDeviceId,
        encryptionKeyProof = context.encryptionKeyProof,
        authorizedCategories = setOf(ConsentCategory.PHOTOS_AND_VIDEOS),
    )

    @Test
    fun `allows an unchanged trusted relationship`() {
        assertTrue(TrustPolicy.evaluate(record, context) is TrustDecision.Approved)
    }

    @Test
    fun `requires approval after reinstall or first run`() {
        assertReason(null, ReapprovalReason.NO_TRUST_RECORD)
    }

    @Test
    fun `requires approval when trust is revoked`() {
        assertReason(record.copy(revokedAtEpochMillis = 1L), ReapprovalReason.TRUST_REVOKED)
    }

    @Test
    fun `requires approval when source identity changes`() {
        assertReason(record.copy(peerDeviceId = "source-2"), ReapprovalReason.SOURCE_DEVICE_IDENTITY_CHANGED)
    }

    @Test
    fun `requires approval when local identity changes`() {
        assertReason(record.copy(localDeviceId = "target-2"), ReapprovalReason.LOCAL_DEVICE_IDENTITY_CHANGED)
    }

    @Test
    fun `requires approval when encryption keys change`() {
        assertReason(record.copy(encryptionKeyProof = "key-2"), ReapprovalReason.ENCRYPTION_KEYS_CHANGED)
    }

    private fun assertReason(candidate: TrustRecord?, expected: ReapprovalReason) {
        val decision = TrustPolicy.evaluate(candidate, context) as TrustDecision.ReapprovalRequired
        assertEquals(expected, decision.reason)
    }
}