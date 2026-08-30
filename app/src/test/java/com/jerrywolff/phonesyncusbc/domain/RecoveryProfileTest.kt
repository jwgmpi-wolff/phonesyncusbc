package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryProfileTest {
    @Test
    fun `every device profile includes every recovery purpose and password artifacts`() {
        RecoveryDeviceType.entries.forEach { deviceType ->
            val profile = RecoveryProfiles.forDevice(deviceType)

            assertEquals(RecoveryPurpose.entries, profile.purposes)
            assertTrue(profile.recoverableTargets.isNotEmpty())
            assertTrue(profile.passwordTarget.contains("password", ignoreCase = true) ||
                profile.passwordTarget.contains("credential", ignoreCase = true))
        }
    }

    @Test
    fun `detected mobile platforms select matching recovery profiles`() {
        assertEquals(
            RecoveryDeviceType.ANDROID,
            RecoveryDeviceType.defaultFor(
                DetectedSource(SourcePlatform.ANDROID, SourceFamily.ANDROID_PHONE, "Android"),
            ),
        )
        assertEquals(
            RecoveryDeviceType.IPHONE_IPAD,
            RecoveryDeviceType.defaultFor(
                DetectedSource(SourcePlatform.IOS, SourceFamily.IPHONE, "iPhone"),
            ),
        )
    }

    @Test
    fun `network iphone recovery path is available for direct wifi transfer`() {
        val profile = RecoveryProfiles.forDevice(RecoveryDeviceType.NETWORK_IPHONE)

        assertEquals(RecoveryDeviceType.NETWORK_IPHONE, profile.deviceType)
        assertTrue(profile.recoverableTargets.isNotEmpty())
        assertTrue(profile.passwordTarget.contains("password", ignoreCase = true) ||
            profile.passwordTarget.contains("credential", ignoreCase = true))
    }

    @Test
    fun `logical acquisition limit excludes physical and access bypass claims`() {
        assertTrue(LOGICAL_ACQUISITION_LIMIT.contains("read-only", ignoreCase = true))
        assertTrue(LOGICAL_ACQUISITION_LIMIT.contains("raw-disk image", ignoreCase = true))
        assertTrue(LOGICAL_ACQUISITION_LIMIT.contains("decrypt", ignoreCase = true))
        assertTrue(LOGICAL_ACQUISITION_LIMIT.contains("bypass", ignoreCase = true))
    }
}
