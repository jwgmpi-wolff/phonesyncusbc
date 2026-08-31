package com.jerrywolff.phonesyncusbc.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbSharedStorageClientTest {
    @Test
    fun `metadata listing parses paths with tabs and newlines in one pass`() {
        val files = parseAdbSharedStorageListing(
            "42\t1725000000.125000000\t/storage/emulated/0/Download/a\tb\nfile.txt\u0000" +
                "9\t1725000001.000000000\t/data/user/0/private.db\u0000",
        )

        assertEquals(
            listOf(
                AdbSharedFile(
                    path = "/storage/emulated/0/Download/a\tb\nfile.txt",
                    size = 42,
                    modifiedAtEpochMillis = 1_725_000_000_125,
                ),
            ),
            files,
        )
    }

    @Test
    fun `file listing keeps unique primary shared-storage paths only`() {
        val paths = parseAdbSharedStoragePaths(
            "/storage/emulated/0/DCIM/photo.jpg\u0000" +
                "/data/user/0/private.db\u0000" +
                "/storage/emulated/0/Download/report.pdf\u0000" +
                "/storage/emulated/0/Download/ spaced name .txt\u0000" +
                "/storage/emulated/0/DCIM/photo.jpg\u0000",
        )

        assertEquals(
            listOf(
                "/storage/emulated/0/DCIM/photo.jpg",
                "/storage/emulated/0/Download/report.pdf",
                "/storage/emulated/0/Download/ spaced name .txt",
            ),
            paths,
        )
    }

    @Test
    fun `epoch parser converts fractions and clamps unsupported extremes`() {
        assertEquals(1_725_000_000_125L, parseAdbEpochMillis("1725000000.125"))
        assertEquals(0L, parseAdbEpochMillis("-1"))
        assertEquals(Long.MAX_VALUE, parseAdbEpochMillis("999999999999999999999"))
        assertNull(parseAdbEpochMillis("not-a-time"))
    }
}