package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class IosBackupImporterInstrumentedTest {
    private lateinit var context: Context
    private lateinit var auditLog: AuditLog
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        auditLog = AuditLog(context)
        auditLog.clear(SOURCE_ID)
        testDirectory = File(context.cacheDir, "ios-backup-import-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        auditLog.completedTransfers(SOURCE_ID).forEach { entry ->
            entry.destination?.let(Uri::parse)?.let { context.contentResolver.delete(it, null, null) }
        }
        auditLog.clear(SOURCE_ID)
        auditLog.close()
        context.deleteDatabase(INDEX_DATABASE)
        testDirectory.deleteRecursively()
    }

    @Test
    fun importsEveryDeclaredFileAndPublishesMessagesAndAttachments() {
        val smsDatabase = createSmsDatabase()
        val attachment = File(testDirectory, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val manifest = createManifestDatabase(
            listOf(
                BackupFile(SMS_FILE_ID, "HomeDomain", "Library/SMS/sms.db", smsDatabase),
                BackupFile(
                    ATTACHMENT_FILE_ID,
                    "MediaDomain",
                    "Library/SMS/Attachments/aa/photo.jpg",
                    attachment,
                ),
            ),
        )
        val backupZip = createBackupZip(manifest, listOf(
            BackupFile(SMS_FILE_ID, "HomeDomain", "Library/SMS/sms.db", smsDatabase),
            BackupFile(
                ATTACHMENT_FILE_ID,
                "MediaDomain",
                "Library/SMS/Attachments/aa/photo.jpg",
                attachment,
            ),
        ))

        val fullImport = OwnerApprovedArchiveImporter(context, auditLog).importSource(
            Uri.fromFile(backupZip),
            SOURCE_ID,
            "Test iPhone",
            com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType.IPHONE_IPAD,
        )
        assertTrue(fullImport.sourcePreserved)
        assertEquals(3, fullImport.declaredItems)
        assertEquals(3, fullImport.recoveredItems)
        assertEquals(0, fullImport.failedItems)

        val result = IosBackupImporter(context, auditLog).importBackup(
            Uri.fromFile(backupZip),
            SOURCE_ID,
            "Test iPhone",
        )

        assertTrue(result.backupPreserved)
        assertEquals(2, result.declaredFiles)
        assertEquals(2, result.presentFiles)
        assertEquals(0, result.missingFiles)
        assertEquals(2, result.messagesExported)
        assertEquals(1, result.attachmentsExported)
        assertTrue(result.smsRequirementSatisfied)
        assertTrue(result.issues.isEmpty())

        val entries = auditLog.completedExternalTransfers(SOURCE_ID)
        assertTrue(entries.any { it.category == ConsentCategory.APPLICATION_DATA })
        assertTrue(entries.any { it.sourceItem.endsWith("ios-backup-file-inventory.jsonl") })
        assertTrue(entries.any { it.sourceItem.endsWith("ios-sms.db") || it.sourceItem.endsWith("sms.db") })
        val messageEntry = entries.single { it.sourceItem.endsWith("ios-messages.jsonl") }
        val messageText = context.contentResolver.openInputStream(Uri.parse(messageEntry.destination)).use { input ->
            checkNotNull(input).reader(Charsets.UTF_8).readText()
        }
        assertTrue(messageText.contains("First recovered SMS"))
        assertTrue(messageText.contains("Recovered iMessage"))
        assertTrue(messageText.contains("+15551234567"))

        val indexDatabase = ArtifactIndexDatabase(context, INDEX_DATABASE)
        try {
            val indexed = ArtifactIndexer(context, indexDatabase).rebuild(entries, SOURCE_ID, "Test iPhone")
            assertTrue(indexed.recordsIndexed >= 2)
            val records = indexDatabase.queryRecords(
                sourceId = SOURCE_ID,
                focus = ArtifactFocus.SMS,
                search = "recovered",
                limit = 50,
            )
            assertTrue(records.any { it.summary == "First recovered SMS" })
            assertTrue(records.any { it.summary == "Recovered iMessage" })
        } finally {
            indexDatabase.close()
        }
    }

    @Test
    fun preservesUnreadableBackupAndReturnsMessageRemediation() {
        val backupZip = File(testDirectory, "encrypted-backup.zip")
        ZipOutputStream(backupZip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("backup-id/Manifest.db"))
            output.write("not-a-readable-sqlite-database".toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }

        val result = IosBackupImporter(context, auditLog).importBackup(
            Uri.fromFile(backupZip),
            SOURCE_ID,
            "Test iPhone",
        )

        assertTrue(result.backupPreserved)
        assertEquals(0, result.messagesExported)
        assertTrue(!result.smsRequirementSatisfied)
        assertNotNull(result.error)
        assertTrue(result.error.orEmpty().contains("encrypted", ignoreCase = true))
        assertTrue(result.issues.any { it.remediation.contains("encrypted", ignoreCase = true) })
        assertTrue(auditLog.completedExternalTransfers(SOURCE_ID).any {
            it.category == ConsentCategory.APPLICATION_DATA
        })
    }

    private fun createSmsDatabase(): File {
        val file = File(testDirectory, "sms-source.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE message (guid TEXT, text TEXT, handle_id INTEGER, service TEXT, date INTEGER, is_from_me INTEGER)",
            )
            database.execSQL("CREATE TABLE handle (id TEXT, country TEXT, service TEXT)")
            database.execSQL("CREATE TABLE chat (chat_identifier TEXT, display_name TEXT)")
            database.execSQL("CREATE TABLE chat_message_join (chat_id INTEGER, message_id INTEGER)")
            database.execSQL("CREATE TABLE attachment (filename TEXT, mime_type TEXT, total_bytes INTEGER)")
            database.execSQL("CREATE TABLE message_attachment_join (message_id INTEGER, attachment_id INTEGER)")
            database.execSQL(
                "INSERT INTO handle (ROWID, id, country, service) VALUES (1, '+15551234567', 'us', 'SMS')",
            )
            database.execSQL(
                "INSERT INTO chat (ROWID, chat_identifier, display_name) VALUES (1, '+15551234567', 'Test conversation')",
            )
            database.execSQL(
                "INSERT INTO message (ROWID, guid, text, handle_id, service, date, is_from_me) VALUES " +
                    "(1, 'sms-guid', 'First recovered SMS', 1, 'SMS', 700000000, 0), " +
                    "(2, 'imessage-guid', 'Recovered iMessage', 1, 'iMessage', 700000001000000000, 1)",
            )
            database.execSQL("INSERT INTO chat_message_join (chat_id, message_id) VALUES (1, 1), (1, 2)")
            database.execSQL(
                "INSERT INTO attachment (ROWID, filename, mime_type, total_bytes) " +
                    "VALUES (1, '~/Library/SMS/Attachments/aa/photo.jpg', 'image/jpeg', 5)",
            )
            database.execSQL("INSERT INTO message_attachment_join (message_id, attachment_id) VALUES (2, 1)")
        }
        return file
    }

    private fun createManifestDatabase(files: List<BackupFile>): File {
        val manifest = File(testDirectory, "Manifest.db")
        SQLiteDatabase.openOrCreateDatabase(manifest, null).use { database ->
            database.execSQL(
                "CREATE TABLE Files (fileID TEXT, domain TEXT, relativePath TEXT, flags INTEGER, file BLOB)",
            )
            files.forEach { file ->
                database.execSQL(
                    "INSERT INTO Files (fileID, domain, relativePath, flags, file) VALUES (?, ?, ?, 1, NULL)",
                    arrayOf(file.fileId, file.domain, file.relativePath),
                )
            }
        }
        return manifest
    }

    private fun createBackupZip(manifest: File, files: List<BackupFile>): File {
        val backupZip = File(testDirectory, "owner-approved-iphone-backup.zip")
        ZipOutputStream(backupZip.outputStream()).use { output ->
            output.writeFile("backup-id/Manifest.db", manifest)
            files.forEach { file ->
                output.writeFile("backup-id/${file.fileId.take(2)}/${file.fileId}", file.payload)
            }
        }
        return backupZip
    }

    private fun ZipOutputStream.writeFile(path: String, file: File) {
        putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    private data class BackupFile(
        val fileId: String,
        val domain: String,
        val relativePath: String,
        val payload: File,
    )

    private companion object {
        const val SOURCE_ID = "ios-owner-approved-test-peer"
        const val INDEX_DATABASE = "ios-backup-import-index-test.sqlite"
        const val SMS_FILE_ID = "3d0d7e5fb2ce288813306e4d4636395e047a3d28"
        const val ATTACHMENT_FILE_ID = "abcdef0123456789abcdef0123456789abcdef01"
    }
}