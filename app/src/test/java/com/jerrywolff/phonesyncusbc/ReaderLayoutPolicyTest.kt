package com.jerrywolff.phonesyncusbc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLayoutPolicyTest {
    @Test
    fun `auto uses three panes only at tablet width`() {
        assertFalse(shouldUseTabletReaderLayout(599, ReaderLayoutPreference.AUTO))
        assertTrue(shouldUseTabletReaderLayout(600, ReaderLayoutPreference.AUTO))
    }

    @Test
    fun `mobile override always uses one column`() {
        assertFalse(shouldUseTabletReaderLayout(840, ReaderLayoutPreference.MOBILE))
    }

    @Test
    fun `tablet option cannot force three panes onto narrow phones`() {
        assertFalse(shouldUseTabletReaderLayout(411, ReaderLayoutPreference.TABLET))
        assertTrue(shouldUseTabletReaderLayout(700, ReaderLayoutPreference.TABLET))
    }
}