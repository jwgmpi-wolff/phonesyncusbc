package com.jerrywolff.phonesyncusbc.domain

enum class SourcePlatform {
    ANDROID,
    IOS,
    WINDOWS_PHONE,
    UNKNOWN,
}

enum class SourceFamily {
    GOOGLE_PHONE,
    ANDROID_PHONE,
    IPHONE,
    WINDOWS_PHONE,
    UNKNOWN,
}

enum class UsbTransport {
    MTP,
    PTP,
    UNKNOWN,
}

enum class PhysicalConnection {
    USB,
    USB_A,
    USB_C,
    FIREWIRE,
    UNKNOWN,
}

data class UsbDescriptor(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val transports: Set<UsbTransport> = emptySet(),
    val physicalConnection: PhysicalConnection = PhysicalConnection.UNKNOWN,
)

data class DetectedSource(
    val platform: SourcePlatform,
    val family: SourceFamily,
    val displayName: String,
    val physicalConnection: PhysicalConnection = PhysicalConnection.UNKNOWN,
)

object SourcePlatformDetector {
    private const val APPLE_VENDOR_ID = 0x05AC
    private const val GOOGLE_VENDOR_ID = 0x18D1
    private const val MICROSOFT_VENDOR_ID = 0x045E
    private const val NOKIA_VENDOR_ID = 0x0421

    fun detect(descriptor: UsbDescriptor): DetectedSource {
        val identity = listOfNotNull(descriptor.manufacturerName, descriptor.productName)
            .joinToString(" ")
            .lowercase()

        return when {
            descriptor.vendorId == APPLE_VENDOR_ID || "iphone" in identity -> DetectedSource(
                platform = SourcePlatform.IOS,
                family = SourceFamily.IPHONE,
                displayName = descriptor.productName ?: "iPhone",
                physicalConnection = descriptor.physicalConnection,
            )

            descriptor.vendorId == GOOGLE_VENDOR_ID || "pixel" in identity -> DetectedSource(
                platform = SourcePlatform.ANDROID,
                family = SourceFamily.GOOGLE_PHONE,
                displayName = descriptor.productName ?: "Google phone",
                physicalConnection = descriptor.physicalConnection,
            )

            isWindowsPhone(descriptor.vendorId, identity) -> DetectedSource(
                platform = SourcePlatform.WINDOWS_PHONE,
                family = SourceFamily.WINDOWS_PHONE,
                displayName = descriptor.productName ?: "Windows Phone",
                physicalConnection = descriptor.physicalConnection,
            )

            "android" in identity || descriptor.vendorId in ANDROID_VENDOR_IDS -> DetectedSource(
                platform = SourcePlatform.ANDROID,
                family = SourceFamily.ANDROID_PHONE,
                displayName = descriptor.productName ?: "Android phone",
                physicalConnection = descriptor.physicalConnection,
            )

            else -> DetectedSource(
                platform = SourcePlatform.UNKNOWN,
                family = SourceFamily.UNKNOWN,
                displayName = descriptor.productName ?: "USB device",
                physicalConnection = descriptor.physicalConnection,
            )
        }
    }

    private fun isWindowsPhone(vendorId: Int, identity: String): Boolean {
        val hasPhoneIdentity = listOf("lumia", "windows phone", "windows mobile").any(identity::contains)
        return hasPhoneIdentity ||
            (vendorId == MICROSOFT_VENDOR_ID && "phone" in identity) ||
            (vendorId == NOKIA_VENDOR_ID && "lumia" in identity)
    }

    private val ANDROID_VENDOR_IDS = setOf(
        GOOGLE_VENDOR_ID,
        0x04E8, // Samsung
        0x0BB4, // HTC
        0x12D1, // Huawei
        0x22B8, // Motorola
        0x2717, // Xiaomi
        0x2A70, // OnePlus
    )
}