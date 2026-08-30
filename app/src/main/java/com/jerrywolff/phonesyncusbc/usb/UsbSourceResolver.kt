package com.jerrywolff.phonesyncusbc.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.domain.DetectedSource
import com.jerrywolff.phonesyncusbc.domain.SourcePlatformDetector
import com.jerrywolff.phonesyncusbc.domain.UsbDescriptor
import com.jerrywolff.phonesyncusbc.domain.UsbTransport
import com.jerrywolff.phonesyncusbc.domain.PhysicalConnection

data class PeerIdentity(
    val peerId: String,
    val profileId: String,
    val serialAvailable: Boolean,
)

data class IdentityReadProgress(
    val stage: IdentityReadStage,
    val completedSteps: Int,
    val totalSteps: Int = TOTAL_IDENTITY_READ_STEPS,
)

enum class IdentityReadStage {
    CHECKING_PERMISSION,
    READING_USB_SERIAL,
    READING_USB_DESCRIPTOR,
    CREATING_IDENTITY,
    COMPLETE,
}

data class AttachedSource(
    val device: UsbDevice,
    val detected: DetectedSource,
    val permissionGranted: Boolean,
)

class MtpSession internal constructor(
    val device: MtpDevice,
    private val connection: UsbDeviceConnection,
) : AutoCloseable {
    override fun close() {
        device.close()
        connection.close()
    }
}

class UsbSourceResolver(context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)

    fun attachedSources(): List<AttachedSource> {
        return usbManager.deviceList.values
            .filter(::isPhoneCandidate)
            .map(::describe)
            .sortedBy { it.detected.displayName.lowercase() }
    }

    fun describe(device: UsbDevice): AttachedSource {
        val descriptor = UsbDescriptor(
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            transports = transportsFor(device),
            // Android reports the USB protocol, not the connector's physical shape.
            physicalConnection = PhysicalConnection.USB,
        )
        return AttachedSource(
            device = device,
            detected = SourcePlatformDetector.detect(descriptor),
            permissionGranted = usbManager.hasPermission(device),
        )
    }

    fun resolveIdentity(
        source: AttachedSource,
        onProgress: (IdentityReadProgress) -> Unit = {},
    ): PeerIdentity {
        onProgress(IdentityReadProgress(IdentityReadStage.CHECKING_PERMISSION, 0))
        check(usbManager.hasPermission(source.device)) { "USB permission is required" }
        onProgress(IdentityReadProgress(IdentityReadStage.READING_USB_SERIAL, 1))
        val usbSerial = runCatching { source.device.serialNumber }.getOrNull()
        onProgress(IdentityReadProgress(IdentityReadStage.READING_USB_DESCRIPTOR, 2))
        val mtpSerial = if (usbSerial.isNullOrBlank()) {
            runCatching {
                openMtp(source.device)?.use { session -> session.device.deviceInfo?.serialNumber }
            }.getOrNull()
        } else {
            null
        }
        val stableSerial = usbSerial?.takeIf(String::isNotBlank)
            ?: mtpSerial?.takeIf(String::isNotBlank)
        val platform = source.detected.platform.name
        val serialMaterial = stableSerial ?: stableUsbIdentityMaterial(
            platform = platform,
            vendorId = source.device.vendorId,
            productId = source.device.productId,
            manufacturerName = runCatching { source.device.manufacturerName }.getOrNull(),
            productName = runCatching { source.device.productName }.getOrNull(),
            transportSignatures = (0 until source.device.interfaceCount).map { index ->
                source.device.getInterface(index).let { usbInterface ->
                    "${usbInterface.interfaceClass}:${usbInterface.interfaceSubclass}:${usbInterface.interfaceProtocol}"
                }
            },
        )
        onProgress(IdentityReadProgress(IdentityReadStage.CREATING_IDENTITY, 3))
        val identity = PeerIdentity(
            peerId = DeviceIdentity.peerId(
                platform = platform,
                vendorId = source.device.vendorId,
                productId = source.device.productId,
                serialNumber = serialMaterial,
            ),
            profileId = DeviceIdentity.profileId(
                platform = platform,
                vendorId = source.device.vendorId,
                productId = source.device.productId,
            ),
            serialAvailable = stableSerial != null,
        )
        onProgress(IdentityReadProgress(IdentityReadStage.COMPLETE, TOTAL_IDENTITY_READ_STEPS))
        return identity
    }

    fun openMtp(device: UsbDevice): MtpSession? {
        val connection = usbManager.openDevice(device) ?: return null
        val mtpDevice = MtpDevice(device)
        if (!mtpDevice.open(connection)) {
            connection.close()
            return null
        }
        return MtpSession(mtpDevice, connection)
    }

    private fun isPhoneCandidate(device: UsbDevice): Boolean {
        val identity = listOfNotNull(
            runCatching { device.manufacturerName }.getOrNull(),
            runCatching { device.productName }.getOrNull(),
        ).joinToString(" ").lowercase()

        val knownVendor = device.vendorId in KNOWN_PHONE_VENDOR_IDS
        val hasAndroidIdentity = identity.contains("android") || identity.contains("pixel") ||
            identity.contains("galaxy") || identity.contains("tablet") || identity.contains("phone")
        val hasMediaInterface = (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                usbInterface.interfaceClass == 0xFF ||
                usbInterface.interfaceClass == 0xEF
        }
        return knownVendor || hasAndroidIdentity || hasMediaInterface
    }

    private fun transportsFor(device: UsbDevice): Set<UsbTransport> {
        val hasMediaInterface = (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                usbInterface.interfaceClass == 0xFF ||
                usbInterface.interfaceClass == 0xEF
        }
        if (!hasMediaInterface) return emptySet()
        return if (device.vendorId == APPLE_VENDOR_ID) {
            setOf(UsbTransport.PTP)
        } else {
            setOf(UsbTransport.MTP)
        }
    }

    private companion object {
        const val APPLE_VENDOR_ID = 0x05AC
        val KNOWN_PHONE_VENDOR_IDS = setOf(
            APPLE_VENDOR_ID,
            0x18D1,
            0x045E,
            0x0421,
            0x04E8,
            0x0BB4,
            0x12D1,
            0x22B8,
            0x2717,
            0x2A70,
        )
    }
}

internal fun stableUsbIdentityMaterial(
    platform: String,
    vendorId: Int,
    productId: Int,
    manufacturerName: String?,
    productName: String?,
    transportSignatures: List<String>,
): String {
    return listOf(
        "descriptor",
        platform,
        vendorId.toString(),
        productId.toString(),
        manufacturerName.orEmpty().trim().lowercase(),
        productName.orEmpty().trim().lowercase(),
        transportSignatures.sorted().joinToString(","),
    ).joinToString(":")
}

private const val TOTAL_IDENTITY_READ_STEPS = 4