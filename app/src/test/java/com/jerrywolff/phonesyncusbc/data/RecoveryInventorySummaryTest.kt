package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryInventorySummaryTest {
    @Test
    fun `summary statuses are mutually exclusive and password artifacts are accounted for`() {
        val items = listOf(
            item("new", RecoveryItemStatus.RECOVERED, 10, ConsentCategory.PASSWORD_EXPORTS),
            item("existing", RecoveryItemStatus.ALREADY_RECOVERED, 20, ConsentCategory.PASSWORD_EXPORTS),
            item("/Exports/Passkeys/passkeys.json", RecoveryItemStatus.RECOVERED, 5, ConsentCategory.PASSWORD_EXPORTS),
            item("unauthorized", RecoveryItemStatus.NOT_AUTHORIZED),
            item("not-recovered", RecoveryItemStatus.NOT_RECOVERED),
            item("failed", RecoveryItemStatus.FAILED),
        )

        val summary = summarizeRecoveryItems(items)

        assertEquals(6, summary.discoveredItems)
        assertEquals(2, summary.recoveredItems)
        assertEquals(1, summary.alreadyRecoveredItems)
        assertEquals(2, summary.notRecoveredItems)
        assertEquals(1, summary.failedItems)
        assertEquals(15, summary.recoveredBytes)
        assertEquals(35, summary.accountedBytes)
        assertEquals(3, summary.passwordArtifacts)
        assertEquals(3, summary.recoveredPasswordArtifacts)
        assertEquals(1, summary.passkeyRelatedArtifacts)
        assertEquals(1, summary.recoveredPasskeyRelatedArtifacts)
        assertEquals(
            summary.discoveredItems,
            summary.recoveredItems + summary.alreadyRecoveredItems +
                summary.notRecoveredItems + summary.failedItems,
        )
    }

    private fun item(
        sourcePath: String,
        status: RecoveryItemStatus,
        recoveredBytes: Long = 0,
        category: ConsentCategory = ConsentCategory.DOCUMENTS,
    ): RecoveryInventoryItem {
        return RecoveryInventoryItem(
            sourcePath = sourcePath,
            category = category,
            status = status,
            recoveredBytes = recoveredBytes,
        )
    }
}
