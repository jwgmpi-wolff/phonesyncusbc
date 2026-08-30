package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionInstallPolicyTest {
    @Test
    fun `android source without companion requires install`() {
        val plan = CompanionInstallPolicy.plan(
            sourcePlatform = SourcePlatform.ANDROID,
            appPackage = null,
            isHotspotConnected = true,
        )

        assertTrue(plan.requiresInstall)
        assertEquals("PhoneSync Companion", plan.requiredPackageLabel)
        assertTrue(plan.instructions.contains("Install the compatible PhoneSync Companion"))
    }

    @Test
    fun `ios source requires a signed companion on the actual iPhone`() {
        val plan = CompanionInstallPolicy.plan(
            sourcePlatform = SourcePlatform.IOS,
            appPackage = null,
            isHotspotConnected = true,
        )

        assertTrue(plan.requiresInstall)
        assertTrue(plan.requiresActualDevice)
        assertTrue(plan.requiresSignedCompanion)
        assertEquals("PhoneSync Companion", plan.requiredPackageLabel)
        assertTrue(plan.instructions.contains("actual iPhone"))
        assertTrue(plan.instructions.contains("Local Network"))
    }

    @Test
    fun `same phone sync app on android is already compatible`() {
        val plan = CompanionInstallPolicy.plan(
            sourcePlatform = SourcePlatform.ANDROID,
            appPackage = "com.jerrywolff.phonesyncusbc",
            isHotspotConnected = true,
        )

        assertFalse(plan.requiresInstall)
        assertEquals("Compatible companion ready", plan.status)
    }

    @Test
    fun `already compatible source does not require install`() {
        val plan = CompanionInstallPolicy.plan(
            sourcePlatform = SourcePlatform.ANDROID,
            appPackage = "com.jerrywolff.phonesyncusbc.companion",
            isHotspotConnected = true,
        )

        assertFalse(plan.requiresInstall)
        assertEquals("Compatible companion ready", plan.status)
    }
}
