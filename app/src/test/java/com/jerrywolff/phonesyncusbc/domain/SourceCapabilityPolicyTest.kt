package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapabilityPolicyTest {
    @Test
    fun `Google phones use MTP for media and documents`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.ANDROID, SourceFamily.GOOGLE_PHONE, "Pixel 9"),
        )

        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `iPhone media uses PTP while documents require exports`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.IOS, SourceFamily.IPHONE, "iPhone"),
        )

        assertEquals(AccessMode.PTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `connected source personal data requires USB-visible exports`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.IOS, SourceFamily.IPHONE, "iPhone"),
        )

        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.SMS_EXPORTS))
        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.CONTACTS))
        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.CALL_LOGS))
        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.CALENDAR))
        assertEquals(
            AccessMode.PORTABLE_EXPORT,
            capabilities.modeFor(ConsentCategory.PASSWORD_EXPORTS),
        )
        assertEquals(
            AccessMode.PORTABLE_EXPORT,
            capabilities.modeFor(ConsentCategory.VOICEMAIL_EXPORTS),
        )
        assertEquals(
            AccessMode.PORTABLE_EXPORT,
            capabilities.modeFor(ConsentCategory.NOTIFICATION_EXPORTS),
        )
        assertFalse(ConsentCategory.CLOUD_ACCOUNTS in capabilities.supportedCategories)
    }

    @Test
    fun `Windows Phone uses MTP`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.WINDOWS_PHONE, SourceFamily.WINDOWS_PHONE, "Lumia"),
        )

        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `generic camera or IoT source uses logical MTP PTP recovery`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.UNKNOWN, SourceFamily.UNKNOWN, "Camera"),
        )

        assertEquals(AccessMode.MTP_PTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.MTP_PTP, capabilities.modeFor(ConsentCategory.CONFIGURATION))
    }

    @Test
    fun `FireWire is explicitly unsupported by Android USB host`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(
                SourcePlatform.ANDROID,
                SourceFamily.ANDROID_PHONE,
                "Legacy phone",
                PhysicalConnection.FIREWIRE,
            ),
        )

        assertFalse(capabilities.connectionSupported)
        assertTrue(capabilities.connectionMessage.contains("FireWire"))
    }

    @Test
    fun `private app data notification capture and accessibility scraping stay disabled`() {
        val policies = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.ANDROID, SourceFamily.ANDROID_PHONE, "Android phone"),
        ).protectedSurfaces

        assertTrue(policies.isNotEmpty())
        assertFalse(policies.first { it.surface == ProtectedSurface.LIVE_NOTIFICATION_CONTENT }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.SMS_DATABASE }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.CHAT_APPLICATION_DATABASE }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.EMAIL_APPLICATION_DATABASE }.supported)
        assertTrue(
            policies.first { it.surface == ProtectedSurface.ACCESSIBILITY_SCRAPING }
                .alternative
                .isNullOrBlank(),
        )
    }

    private fun SourceCapabilities.modeFor(category: ConsentCategory): AccessMode {
        return categories.first { it.category == category }.accessMode
    }
}