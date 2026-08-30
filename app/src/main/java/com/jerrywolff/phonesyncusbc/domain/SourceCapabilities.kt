package com.jerrywolff.phonesyncusbc.domain

enum class AccessMode {
    MTP,
    PTP,
    MTP_PTP,
    PORTABLE_EXPORT,
    SAF,
    LOCAL_ANDROID_PROVIDER,
}

data class CategoryCapability(
    val category: ConsentCategory,
    val accessMode: AccessMode,
    val description: String,
)

enum class ProtectedSurface {
    SMS_DATABASE,
    CHAT_APPLICATION_DATABASE,
    EMAIL_APPLICATION_DATABASE,
    OTHER_APP_PRIVATE_STORAGE,
    LIVE_NOTIFICATION_CONTENT,
    ACCESSIBILITY_SCRAPING,
}

data class ProtectedSurfacePolicy(
    val surface: ProtectedSurface,
    val supported: Boolean,
    val alternative: String?,
)

data class SourceCapabilities(
    val physicalConnection: PhysicalConnection,
    val connectionSupported: Boolean,
    val connectionMessage: String,
    val categories: List<CategoryCapability>,
    val protectedSurfaces: List<ProtectedSurfacePolicy>,
) {
    val supportedCategories: Set<ConsentCategory> = categories.mapTo(linkedSetOf()) { it.category }
}

object SourceCapabilityPolicy {
    fun forSource(source: DetectedSource): SourceCapabilities {
        val mediaMode = when (source.platform) {
            SourcePlatform.IOS -> AccessMode.PTP
            SourcePlatform.ANDROID, SourcePlatform.WINDOWS_PHONE -> AccessMode.MTP
            SourcePlatform.UNKNOWN -> AccessMode.MTP_PTP
        }
        val documentMode = when (source.platform) {
            SourcePlatform.ANDROID, SourcePlatform.WINDOWS_PHONE -> AccessMode.MTP
            SourcePlatform.IOS -> AccessMode.PORTABLE_EXPORT
            SourcePlatform.UNKNOWN -> AccessMode.MTP_PTP
        }

        return SourceCapabilities(
            physicalConnection = source.connection,
            connectionSupported = source.connection != PhysicalConnection.FIREWIRE,
            connectionMessage = when (source.connection) {
                PhysicalConnection.FIREWIRE -> "FireWire is not exposed through Android USB host APIs. Use a USB or USB-C data connection."
                PhysicalConnection.USB_C -> "USB-C data connection detected"
                PhysicalConnection.USB_A -> "USB-A data connection detected"
                PhysicalConnection.USB -> "USB data connection detected"
                PhysicalConnection.UNKNOWN -> "USB data connection type is not reported by Android"
            },
            categories = listOf(
                CategoryCapability(
                    ConsentCategory.PHOTOS_AND_VIDEOS,
                    mediaMode,
                    if (mediaMode == AccessMode.PTP) {
                        "Photos and videos exposed by iPhone over PTP"
                    } else {
                        "Photos and videos exposed by the source phone"
                    },
                ),
                CategoryCapability(
                    ConsentCategory.DOCUMENTS,
                    documentMode,
                    if (documentMode == AccessMode.PORTABLE_EXPORT) {
                        "Documents explicitly exported from iOS apps"
                    } else {
                        "Documents exposed by the source phone"
                    },
                ),
                CategoryCapability(
                    ConsentCategory.APPLICATION_DATA,
                    documentMode,
                    "Application data files exposed by the source through normal USB access",
                ),
                CategoryCapability(
                    ConsentCategory.CONFIGURATION,
                    documentMode,
                    "Configuration and settings files exposed by the source",
                ),
                CategoryCapability(
                    ConsentCategory.LOGS,
                    documentMode,
                    "Logs, diagnostics, and crash reports exposed by the source",
                ),
                CategoryCapability(
                    ConsentCategory.SYSTEM_INFORMATION,
                    documentMode,
                    "System-information and device-report files exposed by the source",
                ),
                CategoryCapability(
                    ConsentCategory.CONTACTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Contacts exposed by the USB source as vCard exports",
                ),
                CategoryCapability(
                    ConsentCategory.CALL_LOGS,
                    AccessMode.PORTABLE_EXPORT,
                    "Call history exposed by the USB source as an export file",
                ),
                CategoryCapability(
                    ConsentCategory.CALENDAR,
                    AccessMode.PORTABLE_EXPORT,
                    "Calendar events exposed by the USB source as an export file",
                ),
                CategoryCapability(
                    ConsentCategory.SMS_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "SMS/MMS exposed by the USB source as a Phone Sync or app export",
                ),
                CategoryCapability(
                    ConsentCategory.CHAT_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Owner-created WhatsApp, Teams, Zoom, Webex, Slack, Signal, Telegram, or other chat exports and transcripts",
                ),
                CategoryCapability(
                    ConsentCategory.EMAIL_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "User-created mail exports such as .eml or .mbox files",
                ),
                CategoryCapability(
                    ConsentCategory.PASSWORD_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Owner-created encrypted password-manager or provider-supported passkey backup exports; " +
                        "hardware-backed passkey private keys are not USB-exportable",
                ),
                CategoryCapability(
                    ConsentCategory.VOICEMAIL_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Voicemail audio or visual-voicemail exports exposed by the source",
                ),
                CategoryCapability(
                    ConsentCategory.NOTIFICATION_EXPORTS,
                    AccessMode.PORTABLE_EXPORT,
                    "Notifications exposed by the USB source as a Phone Sync or app export",
                ),
            ),
            protectedSurfaces = protectedSurfacePolicies,
        )
    }

    private val DetectedSource.connection: PhysicalConnection
        get() = physicalConnection

    private val protectedSurfacePolicies = listOf(
        ProtectedSurfacePolicy(
            ProtectedSurface.SMS_DATABASE,
            supported = false,
            alternative = "Create an export on the external source and place it in USB-visible storage.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.CHAT_APPLICATION_DATABASE,
            supported = false,
            alternative = "Use the chat application's own export feature.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.EMAIL_APPLICATION_DATABASE,
            supported = false,
            alternative = "Export messages from the mail provider or app.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.OTHER_APP_PRIVATE_STORAGE,
            supported = false,
            alternative = "Use that application's supported export or backup flow.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.LIVE_NOTIFICATION_CONTENT,
            supported = false,
            alternative = "Export notifications on the external source into USB-visible storage.",
        ),
        ProtectedSurfacePolicy(
            ProtectedSurface.ACCESSIBILITY_SCRAPING,
            supported = false,
            alternative = null,
        ),
    )
}