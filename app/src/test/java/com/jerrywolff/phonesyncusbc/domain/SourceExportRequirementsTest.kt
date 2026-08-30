package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceExportRequirementsTest {
    @Test
    fun `reports portable exports not exposed by source`() {
        assertEquals(
            listOf(
                ConsentCategory.CHAT_EXPORTS,
                ConsentCategory.CALL_LOGS,
                ConsentCategory.EMAIL_EXPORTS,
                ConsentCategory.CONTACTS,
                ConsentCategory.CALENDAR,
                ConsentCategory.VOICEMAIL_EXPORTS,
                ConsentCategory.NOTIFICATION_EXPORTS,
                ConsentCategory.PASSWORD_EXPORTS,
            ),
            SourceExportRequirements.missingFrom(setOf(ConsentCategory.SMS_EXPORTS)),
        )
    }

    @Test
    fun `reports ready when all required exports are visible`() {
        assertEquals(
            emptyList<ConsentCategory>(),
            SourceExportRequirements.missingFrom(SourceExportRequirements.categories.toSet()),
        )
    }
}