package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OwnerApprovedArchiveImporterInstrumentedTest {
    private lateinit var context: Context
    private lateinit var auditLog: AuditLog
    private lateinit var testDirectory: File
    private val usedPeerIds = linkedSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        auditLog = AuditLog(context)
        usedPeerIds.clear()
        usedPeerIds += SOURCE_ID
        auditLog.clear(SOURCE_ID)
        testDirectory = File(context.cacheDir, "owner-archive-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        usedPeerIds.forEach { peerId ->
            auditLog.completedTransfers(peerId).forEach { entry ->
                entry.destination?.let(Uri::parse)?.let { context.contentResolver.delete(it, null, null) }
            }
            auditLog.clear(peerId)
        }
        auditLog.close()
        context.deleteDatabase(INDEX_DATABASE)
        testDirectory.deleteRecursively()
    }

    @Test
    fun preservesPackageAndRecoversEveryOwnerApprovedEntry() {
        val archive = File(testDirectory, "android-owner-export.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("Documents/"))
            output.closeEntry()
            output.writeEntry("Documents/readme.txt", "document-data")
            output.writeEntry("Downloads/WhatsApp Chat with Family.txt", "WhatsApp recovered conversation")
            output.writeEntry("password_exports/vault.kdbx", "opaque-password-vault")
            output.writeEntry("Phone Sync/This Android/legacy.txt", "explicit-owner-approved-legacy-data")
            output.writeEntry("Documents/empty.txt", "")
        }

        val result = OwnerApprovedArchiveImporter(context, auditLog).importSource(
            sourceUri = Uri.fromFile(archive),
            peerId = SOURCE_ID,
            sourceName = "Test Android",
            deviceType = RecoveryDeviceType.ANDROID,
        )

        assertTrue(result.sourcePreserved)
        assertEquals(6, result.declaredItems)
        assertEquals(5, result.recoveredItems)
        assertEquals(1, result.directoryItems)
        assertEquals(0, result.failedItems)
        assertTrue(result.issues.isEmpty())
        assertTrue(ConsentCategory.CHAT_EXPORTS in result.categoriesRecovered)
        assertTrue(ConsentCategory.PASSWORD_EXPORTS in result.categoriesRecovered)

        val entries = auditLog.completedExternalTransfers(SOURCE_ID)
        assertEquals(7, entries.size)
        assertTrue(entries.any { it.category == ConsentCategory.APPLICATION_DATA })
        assertTrue(entries.any { it.category == ConsentCategory.CHAT_EXPORTS })
        assertTrue(entries.any { it.category == ConsentCategory.PASSWORD_EXPORTS })
        assertTrue(entries.any { it.sourceItem.endsWith("owner-approved-source-inventory.jsonl") })
        assertTrue(entries.all { !isCollectorOwnedSourceItem(it.sourceItem) })

        val inventoryEntry = entries.single { it.sourceItem.endsWith("owner-approved-source-inventory.jsonl") }
        val inventory = context.contentResolver.openInputStream(Uri.parse(inventoryEntry.destination)).use { input ->
            checkNotNull(input).reader(Charsets.UTF_8).readText()
        }
        val inventoryRows = inventory.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        assertTrue(inventoryRows.any { it.optString("originalPath") == "Phone Sync/This Android/legacy.txt" })
        assertTrue(inventory, inventory.contains("COPIED_OPAQUE_NO_DECRYPTION"))
        assertTrue(inventory, inventory.contains("DIRECTORY_ACCOUNTED"))

        val database = ArtifactIndexDatabase(context, INDEX_DATABASE)
        try {
            val indexed = ArtifactIndexer(context, database).rebuild(entries, SOURCE_ID, "Test Android")
            assertTrue(indexed.skippedSensitiveArtifacts >= 1)
            assertTrue(
                database.queryRecords(
                    sourceId = SOURCE_ID,
                    focus = ArtifactFocus.MESSAGES,
                    search = "WhatsApp",
                    limit = 20,
                ).isNotEmpty(),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unsafeEntryRemainsPreservedAndGetsExplicitRemediation() {
        val archive = File(testDirectory, "camera-owner-export.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.writeEntry("../unsafe.txt", "unsafe-but-preserved-in-original")
            output.writeEntry("DCIM/good.jpg", "image-data")
        }

        val result = OwnerApprovedArchiveImporter(context, auditLog).importSource(
            sourceUri = Uri.fromFile(archive),
            peerId = SOURCE_ID,
            sourceName = "Test Camera",
            deviceType = RecoveryDeviceType.CAMERA_IOT,
        )

        assertTrue(result.sourcePreserved)
        assertEquals("error=${result.error}; issues=${result.issues}", 0, result.declaredItems)
        assertEquals(0, result.recoveredItems)
        assertEquals(1, result.failedItems)
        assertTrue(result.issues.any { it.remediation.contains("unsafe.txt") })
        val entries = auditLog.completedExternalTransfers(SOURCE_ID)
        assertTrue(entries.any { it.category == ConsentCategory.APPLICATION_DATA })
        assertTrue(entries.any { it.sourceItem.endsWith("owner-approved-source-inventory.jsonl") })
        assertFalse(entries.any { it.category == ConsentCategory.PHOTOS_AND_VIDEOS })
        assertFalse(entries.any { it.sourceItem.contains("unsafe.txt") })
    }

    @Test
    fun importsIndividualOwnerExportForEveryRecoveryProfile() {
        RecoveryDeviceType.entries.forEach { deviceType ->
            val peerId = "$SOURCE_ID-${deviceType.name.lowercase()}"
            usedPeerIds += peerId
            auditLog.clear(peerId)
            val export = File(testDirectory, "${deviceType.name.lowercase()}-notes.txt").apply {
                writeText("owner-approved ${deviceType.label} export")
            }

            val result = OwnerApprovedArchiveImporter(context, auditLog).importSource(
                sourceUri = Uri.fromFile(export),
                peerId = peerId,
                sourceName = "Test ${deviceType.label}",
                deviceType = deviceType,
            )

            assertTrue(deviceType.name, result.sourcePreserved)
            assertEquals(deviceType.name, 1, result.declaredItems)
            assertEquals(deviceType.name, 1, result.recoveredItems)
            assertEquals(deviceType.name, 0, result.failedItems)
            assertTrue(deviceType.name, result.issues.isEmpty())
            assertEquals(deviceType.name, 2, auditLog.completedExternalTransfers(peerId).size)
        }
    }

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        const val SOURCE_ID = "owner-approved-universal-test-peer"
        const val INDEX_DATABASE = "owner-approved-import-index-test.sqlite"
    }
}