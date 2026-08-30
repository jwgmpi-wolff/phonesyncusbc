package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtifactIndexerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: ArtifactIndexDatabase
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        database = ArtifactIndexDatabase(context, TEST_DATABASE)
        testDirectory = File(context.cacheDir, "artifact-index-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
        testDirectory.deleteRecursively()
    }

    @Test
    fun sourceRebuildIsIndependentOfPreviousRecordOwnershipAndRepeatable() {
        database.replaceSourceArtifacts(SOURCE_ID, SOURCE_NAME) { writer ->
            writer.replaceArtifact(metadata(1, "Exports/A/messages.json")) { records ->
                records.insert("messages.json", record = record("shared"))
                ArtifactParseOutcome(ArtifactParseStatus.PARSED)
            }
            writer.replaceArtifact(metadata(2, "Exports/B/messages.json")) { records ->
                records.insert("messages.json", record = record("shared"))
                ArtifactParseOutcome(ArtifactParseStatus.PARSED)
            }
        }

        fun rebuildChangedSource() {
            database.replaceSourceArtifacts(SOURCE_ID, SOURCE_NAME) { writer ->
                writer.replaceArtifact(metadata(2, "Exports/B/messages.json")) { records ->
                    records.insert("messages.json", record = record("shared"))
                    ArtifactParseOutcome(ArtifactParseStatus.PARSED)
                }
                writer.replaceArtifact(metadata(1, "Exports/A/messages.json")) { records ->
                    records.insert("messages.json", record = record("changed"))
                    ArtifactParseOutcome(ArtifactParseStatus.PARSED)
                }
            }
        }

        rebuildChangedSource()
        val firstStats = database.stats()
        val firstSummaries = database.queryRecords(sourceId = SOURCE_ID, limit = 10).map { it.summary }.sorted()
        rebuildChangedSource()
        val secondStats = database.stats()

        assertEquals(2, firstStats.artifactCount)
        assertEquals(2, firstStats.recordCount)
        assertEquals(listOf("changed", "shared"), firstSummaries)
        assertEquals(firstStats, secondStats)
    }

    @Test
    fun singleArtifactReplacementRetainsSiblingArtifacts() {
        database.replaceArtifact(metadata(1, "Exports/A/messages.json"))
        database.replaceArtifact(metadata(2, "Exports/B/messages.json"))
        database.replaceArtifact(metadata(1, "Exports/A/messages.json"))

        assertEquals(2, database.stats().artifactCount)
    }

    @Test
    fun smsZipIndexesEveryNonSensitiveEntryAndRemainsIdempotent() {
        val zip = File(testDirectory, "sms-backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { archive ->
            archive.writeEntry("messages/messages.json", "[{\"sender\":\"Ada\",\"body\":\"From ZIP\"}]")
            archive.writeEntry("messages/sms.xml", "<smses><sms body=\"XML message\" /></smses>")
            archive.writeEntry("attachments/photo.jpg", "image-bytes")
            archive.writeEntry("attachments/raw.bin", "opaque-attachment")
            archive.writeEntry("databases/mmssms.db", "sqlite-bytes")
            archive.writeEntry("credentials/credentials.json", "{\"password\":\"excluded\"}")
        }
        val entry = auditEntry(zip)
        val collectorEntry = entry.copy(
            id = 11,
            sourceItem = "/Download/Phone Sync/This Android/sms_exports/collector.zip",
            peerId = SOURCE_ID,
            sourceFingerprint = "$SOURCE_ID|collector.zip|${zip.length()}|${zip.lastModified()}",
        )
        val otherPeerEntry = entry.copy(
            id = 12,
            peerId = "usb-other-peer",
            sourceFingerprint = "usb-other-peer|sms-backup.zip|${zip.length()}|${zip.lastModified()}",
        )
        val indexer = ArtifactIndexer(context, database)

        val first = indexer.rebuild(listOf(otherPeerEntry, entry, collectorEntry), SOURCE_ID, SOURCE_NAME)
        val firstStats = database.stats()
        val firstRows = database.queryRecords(sourceId = SOURCE_ID, limit = 20)
        val second = indexer.rebuild(listOf(otherPeerEntry, entry, collectorEntry), SOURCE_ID, SOURCE_NAME)
        val secondStats = database.stats()

        assertEquals(5, first.recordsIndexed)
        assertEquals(1, first.indexedArtifacts)
        assertEquals(5, firstStats.recordCount)
        assertEquals(first.recordsIndexed, second.recordsIndexed)
        assertEquals(firstStats, secondStats)
        assertTrue(firstRows.all { it.category == ConsentCategory.SMS_EXPORTS })
        assertFalse(firstRows.any { "credentials" in it.jsonSource.lowercase() })
        assertTrue(firstRows.any { it.jsonSource == "attachments/photo.jpg" && it.recordKind == ParsedRecordKind.MEDIA })
        assertTrue(firstRows.any { it.jsonSource == "databases/mmssms.db" && it.recordKind == ParsedRecordKind.APPLICATION })
        assertTrue(firstRows.any { it.jsonSource == "attachments/raw.bin" && it.recordType == "Archive item" })
        assertEquals(5, database.queryRecords(sourceId = SOURCE_ID, focus = ArtifactFocus.SMS, limit = 20).size)
        assertEquals(1, database.queryRecords(sourceId = SOURCE_ID, focus = ArtifactFocus.IMAGES, limit = 20).size)
    }

    @Test
    fun looseImagesAndVoicemailsBecomeBrowseableRecords() {
        val image = File(testDirectory, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val voicemail = File(testDirectory, "voicemail-message.amr").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val entries = listOf(
            auditEntry(image).copy(
                id = 20,
                category = ConsentCategory.PHOTOS_AND_VIDEOS,
                sourceItem = "/DCIM/Camera/photo.jpg",
                sourceFingerprint = "$SOURCE_ID|photo.jpg|${image.length()}|${image.lastModified()}",
            ),
            auditEntry(voicemail).copy(
                id = 21,
                category = ConsentCategory.VOICEMAIL_EXPORTS,
                sourceItem = "/Exports/Voicemail/voicemail-message.amr",
                sourceFingerprint = "$SOURCE_ID|voicemail-message.amr|${voicemail.length()}|${voicemail.lastModified()}",
            ),
        )

        val result = ArtifactIndexer(context, database).rebuild(entries, SOURCE_ID, SOURCE_NAME)
        val rows = database.queryRecords(sourceId = SOURCE_ID, limit = 10)
        val images = database.queryRecords(sourceId = SOURCE_ID, focus = ArtifactFocus.IMAGES, limit = 10)
        val voicemails = database.queryRecords(sourceId = SOURCE_ID, focus = ArtifactFocus.VOICEMAILS, limit = 10)

        assertEquals(2, result.recordsIndexed)
        assertTrue(rows.any { it.recordKind == ParsedRecordKind.MEDIA && it.title == "photo.jpg" })
        assertTrue(rows.any { it.category == ConsentCategory.VOICEMAIL_EXPORTS && it.recordType == "Voicemail" })
        assertEquals(listOf("photo.jpg"), images.map { it.title })
        assertEquals(listOf("voicemail-message.amr"), voicemails.map { it.title })
        assertEquals(
            listOf("photo.jpg"),
            database.queryRecords(sourceId = SOURCE_ID, recordIds = setOf(images.single().id), limit = 10).map { it.title },
        )
    }

    @Test
    fun uploadZipQuarantinesCollectorAndMixedPeerEntries() {
        val externalFile = File(testDirectory, "external.txt").apply { writeText("external") }
        val valid = auditEntry(externalFile).copy(
            id = 30,
            category = ConsentCategory.DOCUMENTS,
            sourceItem = "/Documents/external.txt",
            sourceFingerprint = "external-fingerprint",
        )
        val collector = valid.copy(
            id = 31,
            sourceItem = "/Download/Phone Sync/Selected folder/external.txt",
            sourceFingerprint = "collector-fingerprint",
        )
        val otherPeer = valid.copy(
            id = 32,
            peerId = "other-peer",
            sourceFingerprint = "other-fingerprint",
        )
        val manager = DataExportManager(context)

        val collectorResult = manager.createUploadArchive(listOf(valid, collector), SOURCE_ID)
        val mixedPeerResult = manager.createUploadArchive(listOf(valid, otherPeer), SOURCE_ID)
        assertNotNull(collectorResult.uri)
        assertNotNull(mixedPeerResult.uri)
        assertEquals(1, collectorResult.archivedItems)
        assertEquals(1, collectorResult.excludedItems)
        assertEquals(1, mixedPeerResult.archivedItems)
        assertEquals(1, mixedPeerResult.excludedItems)
        val collectorManifest = readZipText(collectorResult.uri!!, "backup-manifest.json")
        val mixedPeerManifest = readZipText(mixedPeerResult.uri!!, "backup-manifest.json")
        assertFalse(collectorManifest.contains("Selected folder"))
        assertFalse(mixedPeerManifest.contains("other-peer"))
        context.contentResolver.delete(collectorResult.uri!!, null, null)
        context.contentResolver.delete(mixedPeerResult.uri!!, null, null)

        val validResult = manager.createUploadArchive(listOf(valid), SOURCE_ID)
        assertNotNull(validResult.uri)
        assertEquals(sha256(validResult.uri!!), validResult.archiveSha256)
        val manifest = readZipText(validResult.uri!!, "backup-manifest.json")
        assertTrue(manifest.contains("\"externalPeerId\": \"$SOURCE_ID\""))
        assertTrue(manifest.contains("\"peerId\": \"$SOURCE_ID\""))
        context.contentResolver.delete(validResult.uri!!, null, null)
    }

    @Test
    fun documentTreeExportReopensAndVerifiesDestinationBytes() {
        val source = File(testDirectory, "verified-package.zip").apply {
            writeBytes(ByteArray(8_193) { index -> (index % 251).toByte() })
        }
        val destination = File(testDirectory, "destination").apply { mkdirs() }
        val sourceHash = sha256(source)
        val entry = auditEntry(source).copy(
            id = 41,
            category = ConsentCategory.DOCUMENTS,
            sourceItem = "/RecoverByBackup package/verified-package.zip",
            sourceSize = source.length(),
            bytesTransferred = source.length(),
            contentSha256 = sourceHash,
            sourceFingerprint = "verified-package:$sourceHash",
        )

        val result = DataExportManager(context).export(
            entries = listOf(entry),
            destinationTree = Uri.fromFile(destination),
            expectedPeerId = SOURCE_ID,
            folderNamePrefix = "RecoverByBackup Test",
        )

        assertEquals(1, result.exportedItems)
        assertEquals(0, result.failedItems)
        val copied = destination.walkTopDown().single { it.isFile }
        assertEquals(source.length(), copied.length())
        assertEquals(sourceHash, sha256(copied))
    }

    private fun auditEntry(zip: File): AuditEntry = AuditEntry(
        id = 10,
        transferredAtEpochMillis = zip.lastModified(),
        category = ConsentCategory.SMS_EXPORTS,
        sourceItem = "/Backups/sms_exports/Family/sms-backup.zip",
        destination = Uri.fromFile(zip).toString(),
        bytesTransferred = zip.length(),
        status = TransferStatus.COMPLETED,
        error = null,
        contentSha256 = sha256(zip),
        peerId = SOURCE_ID,
        sourceFingerprint = "$SOURCE_ID|sms-backup.zip|${zip.length()}|${zip.lastModified()}",
    )

    private fun metadata(transferId: Long, path: String) = ArtifactIndexMetadata(
        transferId = transferId,
        sourceId = SOURCE_ID,
        sourceName = SOURCE_NAME,
        category = ConsentCategory.SMS_EXPORTS,
        sourcePath = path,
        destinationUri = "content://external/$transferId",
        mimeType = "application/json",
        bytes = 100,
        sha256 = "hash-$transferId",
        folderMetadata = deriveFolderMetadata(path, ConsentCategory.SMS_EXPORTS),
    )

    private fun record(value: String) = FlattenedJsonRecord(
        recordIndex = 0,
        recordType = "message",
        recordKind = ParsedRecordKind.MESSAGE,
        title = value,
        summary = value,
        timestamp = null,
        fields = listOf(FlattenedJsonField("body", "body", FlattenedValueType.STRING, value)),
    )

    private fun ZipOutputStream.writeEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun sha256(uri: Uri): String = context.contentResolver.openInputStream(uri).use { input ->
        MessageDigest.getInstance("SHA-256").digest(checkNotNull(input).readBytes()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun readZipText(uri: Uri, entryName: String): String {
        var text: String? = null
        context.contentResolver.openInputStream(uri).use { input ->
            ZipInputStream(checkNotNull(input)).use { archive ->
                while (text == null) {
                    val entry = archive.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == entryName) {
                        text = archive.reader().readText()
                    }
                    archive.closeEntry()
                }
            }
        }
        return checkNotNull(text) { "$entryName is missing" }
    }

    private companion object {
        const val TEST_DATABASE = "artifact_index_instrumented_test.sqlite"
        const val SOURCE_ID = "usb-peer-test"
        const val SOURCE_NAME = "Test phone"
    }
}