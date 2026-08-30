package com.jerrywolff.phonesyncusbc

import com.jerrywolff.phonesyncusbc.data.SyncStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompletionPolicyTest {
    @Test
    fun `verified imports without a USB session may be handed off`() {
        assertTrue(permitsCloudHandoff(null))
    }

    @Test
    fun `only completed USB sessions may be handed off`() {
        assertTrue(permitsCloudHandoff(SyncStatus.COMPLETED))
        assertFalse(permitsCloudHandoff(SyncStatus.PARTIAL))
        assertFalse(permitsCloudHandoff(SyncStatus.FAILED))
        assertFalse(permitsCloudHandoff(SyncStatus.RUNNING))
    }
}