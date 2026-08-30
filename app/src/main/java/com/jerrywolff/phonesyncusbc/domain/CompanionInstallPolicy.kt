package com.jerrywolff.phonesyncusbc.domain

data class CompanionInstallPlan(
    val requiresInstall: Boolean,
    val requiresActualDevice: Boolean = false,
    val requiresSignedCompanion: Boolean = false,
    val status: String,
    val requiredPackageLabel: String,
    val instructions: String,
)

object CompanionInstallPolicy {
    private fun isCompatibleCompanionPackage(appPackage: String?): Boolean {
        if (appPackage == null) return false
        val normalized = appPackage.lowercase()
        return normalized.contains("companion") || normalized.contains("phonesync")
    }

    fun plan(
        sourcePlatform: SourcePlatform,
        appPackage: String?,
        isHotspotConnected: Boolean,
    ): CompanionInstallPlan {
        val requiredPackageLabel = "PhoneSync Companion"

        if (isCompatibleCompanionPackage(appPackage)) {
            return CompanionInstallPlan(
                requiresInstall = false,
                requiresActualDevice = false,
                requiresSignedCompanion = false,
                status = "Compatible companion ready",
                requiredPackageLabel = requiredPackageLabel,
                instructions = "Compatible companion already installed on the source device. Continue the direct transfer over the active hotspot or Wi‑Fi network.",
            )
        }

        if (!isHotspotConnected) {
            return CompanionInstallPlan(
                requiresInstall = false,
                requiresActualDevice = false,
                requiresSignedCompanion = false,
                status = "Waiting for hotspot or Wi‑Fi",
                requiredPackageLabel = requiredPackageLabel,
                instructions = "Connect both devices to the same hotspot or Wi‑Fi network before starting a companion-based transfer.",
            )
        }

        return when (sourcePlatform) {
            SourcePlatform.IOS -> CompanionInstallPlan(
                requiresInstall = true,
                requiresActualDevice = true,
                requiresSignedCompanion = true,
                status = "Companion install required on the actual iPhone",
                requiredPackageLabel = requiredPackageLabel,
                instructions = "Build and sign the PhoneSync Companion for the actual iPhone, install it on that device, enable Local Network access, and keep the companion running before Android can discover and recover data.",
            )

            SourcePlatform.ANDROID -> CompanionInstallPlan(
                requiresInstall = true,
                requiresActualDevice = false,
                requiresSignedCompanion = false,
                status = "Companion install required",
                requiredPackageLabel = requiredPackageLabel,
                instructions = "Install the compatible PhoneSync Companion on the source Android device before transfer. Keep both devices on the same hotspot and start the companion before discovery.",
            )

            SourcePlatform.WINDOWS_PHONE, SourcePlatform.UNKNOWN -> CompanionInstallPlan(
                requiresInstall = false,
                requiresActualDevice = false,
                requiresSignedCompanion = false,
                status = "Direct local transfer available",
                requiredPackageLabel = requiredPackageLabel,
                instructions = "This source can use the standard local transfer path without an additional companion app.",
            )
        }
    }
}
