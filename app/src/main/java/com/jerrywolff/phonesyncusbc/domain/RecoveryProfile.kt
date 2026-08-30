package com.jerrywolff.phonesyncusbc.domain

enum class RecoveryDeviceType(val label: String) {
    WINDOWS_PC("Windows PC"),
    ANDROID("Android"),
    IPHONE_IPAD("iPhone/iPad"),
    NETWORK_IPHONE("iPhone on Wi‑Fi"),
    NETWORK_DEVICE("Device on Wi‑Fi/Bluetooth"),
    CAMERA_IOT("Camera/IoT"),
    ;

    companion object {
        fun defaultFor(source: DetectedSource?): RecoveryDeviceType = when (source?.platform) {
            SourcePlatform.ANDROID -> ANDROID
            SourcePlatform.IOS -> IPHONE_IPAD
            SourcePlatform.WINDOWS_PHONE -> WINDOWS_PC
            SourcePlatform.UNKNOWN, null -> CAMERA_IOT
        }
    }
}

enum class RecoveryPurpose(val label: String) {
    DATA_RECOVERY("Data recovery"),
    FORENSIC_ACQUISITION("Read-only logical forensic acquisition of an owned device"),
    FILE_RECOVERY("File recovery"),
    ARTIFACT_EXTRACTION("Artifact extraction"),
    BACKUP_RESTORATION("Backup restoration input recovery"),
    STORAGE_ANALYSIS("USB-visible storage analysis"),
}

data class RecoveryProfile(
    val deviceType: RecoveryDeviceType,
    val recoverableTargets: List<String>,
    val passwordTarget: String,
    val purposes: List<RecoveryPurpose> = RecoveryPurpose.entries,
)

const val LOGICAL_ACQUISITION_LIMIT =
    "This is an owner-authorized, read-only logical MTP/PTP acquisition. It does not create a raw-disk image, " +
        "recover deleted blocks, decrypt protected data, or bypass device, account, password, or encryption controls."

object RecoveryProfiles {
    fun forDevice(deviceType: RecoveryDeviceType): RecoveryProfile = when (deviceType) {
        RecoveryDeviceType.WINDOWS_PC -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "User profiles and Desktop, Documents, and Downloads",
                "Browser data, PST/OST mail files, and OneDrive cache files",
                "Event logs, registry hives, application databases, and configuration files",
                "Complete owner-approved Windows backup/archive ZIPs and individual exports",
            ),
            passwordTarget = "Password vaults, browser credential stores, and provider-supported encrypted passkey backups",
        )
        RecoveryDeviceType.ANDROID -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "Internal and SD-card photos, videos, documents, and application data",
                "SMS/MMS, contacts, call history, and chat backups exported to shared storage",
                "Device logs, configuration files, diagnostics, and system-information exports",
                "Complete owner-approved Android backup/archive ZIPs and individual app exports",
            ),
            passwordTarget = "Password-manager vaults and provider-supported encrypted password/passkey exports",
        )
        RecoveryDeviceType.IPHONE_IPAD -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "Photos, videos, app documents, and locally available iCloud-synchronized files",
                "Complete owner-approved Apple local backups, including raw Messages/SMS databases and attachments",
                "Configuration, diagnostics, and application data exported by iOS or its apps",
                "Complete owner-approved iPhone app/provider archives and individual exports",
            ),
            passwordTarget = "Encrypted password-manager vaults, keychain backups, and provider-supported passkey backups",
        )
        RecoveryDeviceType.NETWORK_IPHONE -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "Messages, contacts, notes, and call history exposed by the companion app over local Wi‑Fi",
                "JSON payloads served by a running iPhone companion app on the same network",
                "Owner-authorized exports from the companion app after device-to-device discovery and validation",
                "Recovered data staged for review before any export or backup destination selection",
            ),
            passwordTarget = "Companion-app credential exports, protected archives, and locally stored passkeys the source app exposes over Wi‑Fi",
        )
        RecoveryDeviceType.NETWORK_DEVICE -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "Files and owner-approved exports exposed by a compatible device over Wi‑Fi",
                "Files shared through a device companion app or standard local transfer protocol",
                "Bluetooth file transfers when the source device supports an accessible file profile",
                "Recovered data staged for review before export or backup destination selection",
            ),
            passwordTarget = "Credential exports and protected archives explicitly exposed by the source device",
        )
        RecoveryDeviceType.CAMERA_IOT -> RecoveryProfile(
            deviceType,
            recoverableTargets = listOf(
                "SD-card contents, photos, recorded footage, and documents",
                "Configuration files, logs, diagnostics, and device settings backups",
                "Application databases and system-information reports exposed by the device",
                "Complete owner-approved Camera/IoT backup/archive ZIPs and individual exports",
            ),
            passwordTarget = "Credential backups and password-protected configuration archives",
        )
    }
}
