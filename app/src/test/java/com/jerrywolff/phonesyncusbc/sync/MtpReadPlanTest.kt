package com.jerrywolff.phonesyncusbc.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MtpReadPlanTest {
    @Test
    fun `iOS standard partial read is tried before full object`() {
        assertEquals(
            listOf(MtpReadMode.PARTIAL_STANDARD, MtpReadMode.FULL_OBJECT),
            mtpReadPlan(
                supportsPartial64 = false,
                supportsPartialStandard = true,
            ),
        )
    }

    @Test
    fun `all compatible read modes retain deterministic order`() {
        assertEquals(
            listOf(
                MtpReadMode.PARTIAL_64,
                MtpReadMode.PARTIAL_STANDARD,
                MtpReadMode.FULL_OBJECT,
            ),
            mtpReadPlan(
                supportsPartial64 = true,
                supportsPartialStandard = true,
            ),
        )
    }

    @Test
    fun `each compatible read mode is retried in deterministic order`() {
        assertEquals(
            listOf(
                MtpReadAttempt(MtpReadMode.PARTIAL_64, 1, 2),
                MtpReadAttempt(MtpReadMode.PARTIAL_64, 2, 2),
                MtpReadAttempt(MtpReadMode.PARTIAL_STANDARD, 1, 2),
                MtpReadAttempt(MtpReadMode.PARTIAL_STANDARD, 2, 2),
                MtpReadAttempt(MtpReadMode.FULL_OBJECT, 1, 2),
                MtpReadAttempt(MtpReadMode.FULL_OBJECT, 2, 2),
            ),
            mtpReadAttempts(
                supportsPartial64 = true,
                supportsPartialStandard = true,
            ),
        )
    }

    @Test
    fun `discovers likely backup and export artifacts without claiming completeness`() {
        assertEquals(true, isBackupArtifactPath("/Downloads/RecoverByBackup-phone.zip"))
        assertEquals(true, isBackupArtifactPath("/Android/Backups/device.ab"))
        assertEquals(true, isBackupArtifactPath("/Apple/Manifest.db"))
        assertEquals(false, isBackupArtifactPath("/DCIM/Camera/photo.jpg"))
    }
}