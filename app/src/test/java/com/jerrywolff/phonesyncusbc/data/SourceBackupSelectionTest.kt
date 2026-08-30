package com.jerrywolff.phonesyncusbc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceBackupSelectionTest {
    @Test
    fun `changing source selects only current peer entries`() {
        assertEquals(
            setOf(7L, 8L),
            mergeSourceBackupSelection(
                peerId = "new-peer",
                previousPeerId = "old-peer",
                currentIds = setOf(7L, 8L),
                knownIds = setOf(1L, 2L),
                selectedIds = setOf(1L),
            ),
        )
    }

    @Test
    fun `same source preserves subset and selects newly collected entries`() {
        assertEquals(
            setOf(1L, 3L),
            mergeSourceBackupSelection(
                peerId = "peer",
                previousPeerId = "peer",
                currentIds = setOf(1L, 2L, 3L),
                knownIds = setOf(1L, 2L),
                selectedIds = setOf(1L),
            ),
        )
    }
}