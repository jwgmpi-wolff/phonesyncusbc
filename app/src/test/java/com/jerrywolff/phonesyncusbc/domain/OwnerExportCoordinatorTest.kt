package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerExportCoordinatorTest {
    @Test
    fun `trigger creates messaging service actions when chat exports are missing`() {
        val workflow = OwnerExportCoordinator.trigger(
            sourcePlatform = SourcePlatform.IOS,
            visibleCategories = setOf(
                ConsentCategory.SMS_EXPORTS,
                ConsentCategory.CALL_LOGS,
                ConsentCategory.EMAIL_EXPORTS,
            ),
        )

        assertTrue(workflow.requiresOwnerAction)
        assertTrue(workflow.actions.any { it.service == "WhatsApp" })
        assertTrue(workflow.actions.any { it.service == "Microsoft Teams" })
        assertTrue(workflow.actions.any { it.service == "Zoom Workplace" })
        assertTrue(workflow.actions.any { it.service == "Cisco Webex" })
        assertTrue(workflow.actions.all { "external source device" in it.ownerSteps })
    }

    @Test
    fun `trigger omits actions for categories already visible over USB`() {
        val workflow = OwnerExportCoordinator.trigger(
            sourcePlatform = SourcePlatform.ANDROID,
            visibleCategories = SourceExportRequirements.categories.toSet(),
        )

        assertFalse(workflow.requiresOwnerAction)
        assertTrue(workflow.actions.isEmpty())
    }

    @Test
    fun `trigger includes generic owner export categories`() {
        val workflow = OwnerExportCoordinator.trigger(SourcePlatform.ANDROID, emptySet())

        assertTrue(workflow.actions.any { it.category == ConsentCategory.SMS_EXPORTS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.CALL_LOGS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.EMAIL_EXPORTS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.CONTACTS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.CALENDAR })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.VOICEMAIL_EXPORTS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.NOTIFICATION_EXPORTS })
        assertTrue(workflow.actions.any { it.category == ConsentCategory.PASSWORD_EXPORTS })
        assertTrue(workflow.actions.any { it.service == "Other messaging or meeting app" })
    }

    @Test
    fun `iPhone SMS remediation requires an Apple local backup import`() {
        val workflow = OwnerExportCoordinator.trigger(SourcePlatform.IOS, emptySet())
        val smsAction = workflow.actions.single { it.category == ConsentCategory.SMS_EXPORTS }

        assertTrue(smsAction.service.contains("Apple Messages"))
        assertTrue(smsAction.ownerSteps.contains("Apple Devices"))
        assertTrue(smsAction.ownerSteps.contains("Finder"))
        assertTrue(smsAction.expectedArtifacts.contains("Manifest.db"))
        assertTrue(smsAction.expectedArtifacts.contains("sms.db"))
    }
}