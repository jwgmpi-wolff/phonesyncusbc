package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import com.jerrywolff.phonesyncusbc.sync.TargetMediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class IosBackupImportStage {
    PRESERVING_BACKUP,
    READING_MANIFEST,
    VERIFYING_BACKUP_FILES,
    EXTRACTING_MESSAGES,
    EXPORTING_MESSAGES,
    EXPORTING_ATTACHMENTS,
    COMPLETE,
}

data class IosBackupImportProgress(
    val stage: IosBackupImportStage,
    val currentItem: String,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val bytesProcessed: Long = 0,
)

data class IosBackupImportResult(
    val backupPreserved: Boolean,
    val declaredFiles: Int,
    val presentFiles: Int,
    val missingFiles: Int,
    val messagesExported: Int,
    val attachmentsExported: Int,
    val artifactsPublished: Int,
    val issues: List<RecoveryIssue>,
    val error: String? = null,
) {
    val smsRequirementSatisfied: Boolean = messagesExported > 0
}

class IosBackupImporter(
    private val context: Context,
    private val auditLog: AuditLog,
    private val targetMediaStore: TargetMediaStore = TargetMediaStore(context),
) {
    fun importBackup(
        archiveUri: Uri,
        peerId: String,
        sourceName: String,
        onProgress: (IosBackupImportProgress) -> Unit = {},
    ): IosBackupImportResult {
        require(isExternalSourcePeer(peerId)) { "Select the external iPhone before importing its backup." }
        val sessionId = auditLog.beginSession(peerId)
        val workspace = File(context.cacheDir, "ios-backup-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val issues = mutableListOf<RecoveryIssue>()
        var backupPreserved = false
        var artifactsPublished = 0
        var bytesPublished = 0L
        var declaredFiles = 0
        var presentFiles = 0
        var missingFiles = 0
        var messagesExported = 0
        var attachmentsExported = 0

        return try {
            val archiveName = sourceDisplayName(archiveUri)
            onProgress(
                IosBackupImportProgress(
                    IosBackupImportStage.PRESERVING_BACKUP,
                    archiveName,
                ),
            )
            val preserved = publishArtifact(
                sessionId = sessionId,
                peerId = peerId,
                sourceUri = archiveUri,
                displayName = ownerBackupName(archiveName),
                mimeType = "application/zip",
                category = ConsentCategory.APPLICATION_DATA,
                sourceName = sourceName,
                sourceItem = "/Owner-approved iPhone backup/$archiveName",
            )
            backupPreserved = true
            artifactsPublished += if (preserved.wasPublished) 1 else 0
            bytesPublished += if (preserved.wasPublished) preserved.bytes else 0

            onProgress(
                IosBackupImportProgress(
                    IosBackupImportStage.READING_MANIFEST,
                    "Manifest.db",
                    bytesProcessed = preserved.bytes,
                ),
            )
            val backupRoot = extractSingleManifest(archiveUri, workspace)
            val manifestDatabase = openBackupDatabase(
                backupRoot.manifestFile,
                "Manifest.db is not a readable SQLite database. The Apple backup may be encrypted. " +
                    "On a trusted Mac or PC, create an owner-approved unencrypted local backup or decrypt a copy there, " +
                    "then import that backup ZIP again.",
            )
            val files = manifestDatabase.use(::readBackupFiles)
            declaredFiles = files.size
            val archiveEntries = readArchiveEntryNames(archiveUri)
            val resolvedFiles = files.map { file ->
                file.copy(archivePath = resolveArchivePath(backupRoot.rootPrefix, file.fileId, archiveEntries))
            }
            presentFiles = resolvedFiles.count { it.archivePath != null }
            missingFiles = declaredFiles - presentFiles

            onProgress(
                IosBackupImportProgress(
                    IosBackupImportStage.VERIFYING_BACKUP_FILES,
                    "Apple backup file inventory",
                    presentFiles,
                    declaredFiles,
                    preserved.bytes,
                ),
            )
            val inventoryFile = File(workspace, "ios-backup-file-inventory.jsonl")
            writeBackupInventory(inventoryFile, resolvedFiles, onProgress)
            val inventoryArtifact = publishArtifact(
                sessionId = sessionId,
                peerId = peerId,
                sourceUri = Uri.fromFile(inventoryFile),
                displayName = "ios-backup-file-inventory.jsonl",
                mimeType = "application/x-ndjson",
                category = ConsentCategory.SYSTEM_INFORMATION,
                sourceName = sourceName,
                sourceItem = "/Owner-approved iPhone backup/ios-backup-file-inventory.jsonl",
            )
            artifactsPublished += if (inventoryArtifact.wasPublished) 1 else 0
            bytesPublished += if (inventoryArtifact.wasPublished) inventoryArtifact.bytes else 0
            if (missingFiles > 0) {
                issues += RecoveryIssue(
                    sourceItem = "/Owner-approved iPhone backup",
                    reason = RecoveryIssueReason.MISSING_RECOVERED_COPY,
                    remediation = "The backup manifest declares $missingFiles payload file(s) that are absent from the ZIP. " +
                        "Recreate the local Apple backup and ZIP the complete backup directory before importing again.",
                    retryable = true,
                )
            }

            val smsFiles = resolvedFiles.filter { it.isSmsDatabaseComponent() }
            val smsDatabase = smsFiles.firstOrNull { it.relativePath.equals(IOS_SMS_DATABASE_PATH, ignoreCase = true) }
            if (smsDatabase?.archivePath == null) {
                issues += RecoveryIssue(
                    sourceItem = IOS_SMS_DATABASE_PATH,
                    reason = RecoveryIssueReason.MISSING_RECOVERED_COPY,
                    remediation = "This Apple backup does not contain HomeDomain/$IOS_SMS_DATABASE_PATH. " +
                        "On the iPhone, keep Messages enabled and create a complete owner-approved local backup with Apple Devices or Finder, then import it again.",
                    retryable = true,
                )
            } else {
                onProgress(
                    IosBackupImportProgress(
                        IosBackupImportStage.EXTRACTING_MESSAGES,
                        IOS_SMS_DATABASE_PATH,
                    ),
                )
                val smsDirectory = File(workspace, "sms").apply { mkdirs() }
                smsFiles.filter { it.archivePath != null }.forEach { file ->
                    val localName = file.relativePath.substringAfterLast('/')
                    extractArchiveEntry(archiveUri, file.archivePath!!, File(smsDirectory, localName))
                }
                val rawSmsDatabase = File(smsDirectory, "sms.db")
                val rawSmsArtifact = publishArtifact(
                    sessionId = sessionId,
                    peerId = peerId,
                    sourceUri = Uri.fromFile(rawSmsDatabase),
                    displayName = "ios-sms.db",
                    mimeType = "application/vnd.sqlite3",
                    category = ConsentCategory.SMS_EXPORTS,
                    sourceName = sourceName,
                    sourceItem = "/Owner-approved iPhone backup/$IOS_SMS_DATABASE_PATH",
                )
                artifactsPublished += if (rawSmsArtifact.wasPublished) 1 else 0
                bytesPublished += if (rawSmsArtifact.wasPublished) rawSmsArtifact.bytes else 0

                val messagesFile = File(workspace, "ios-messages.jsonl")
                messagesExported = exportMessages(rawSmsDatabase, messagesFile, onProgress)
                if (messagesExported > 0) {
                    val messagesArtifact = publishArtifact(
                        sessionId = sessionId,
                        peerId = peerId,
                        sourceUri = Uri.fromFile(messagesFile),
                        displayName = "ios-messages.jsonl",
                        mimeType = "application/x-ndjson",
                        category = ConsentCategory.SMS_EXPORTS,
                        sourceName = sourceName,
                        sourceItem = "/Owner-approved iPhone backup/Library/SMS/ios-messages.jsonl",
                    )
                    artifactsPublished += if (messagesArtifact.wasPublished) 1 else 0
                    bytesPublished += if (messagesArtifact.wasPublished) messagesArtifact.bytes else 0
                } else {
                    issues += RecoveryIssue(
                        sourceItem = IOS_SMS_DATABASE_PATH,
                        reason = RecoveryIssueReason.MISSING_RECOVERED_COPY,
                        remediation = "sms.db was preserved, but its message table contained no readable rows. " +
                            "Confirm messages exist in the selected backup and import a newer complete backup.",
                        retryable = true,
                    )
                }
            }

            val attachmentFiles = resolvedFiles.filter { it.isSmsAttachment() && it.archivePath != null }
            if (attachmentFiles.isNotEmpty()) {
                onProgress(
                    IosBackupImportProgress(
                        IosBackupImportStage.EXPORTING_ATTACHMENTS,
                        "iOS message attachments",
                        totalItems = attachmentFiles.size,
                    ),
                )
                val attachmentsArchive = File(workspace, "ios-message-attachments.zip")
                attachmentsExported = exportAttachments(
                    archiveUri,
                    attachmentFiles,
                    attachmentsArchive,
                    onProgress,
                )
                val attachmentsArtifact = publishArtifact(
                    sessionId = sessionId,
                    peerId = peerId,
                    sourceUri = Uri.fromFile(attachmentsArchive),
                    displayName = "ios-message-attachments.zip",
                    mimeType = "application/zip",
                    category = ConsentCategory.SMS_EXPORTS,
                    sourceName = sourceName,
                    sourceItem = "/Owner-approved iPhone backup/Library/SMS/Attachments/ios-message-attachments.zip",
                )
                artifactsPublished += if (attachmentsArtifact.wasPublished) 1 else 0
                bytesPublished += if (attachmentsArtifact.wasPublished) attachmentsArtifact.bytes else 0
            }

            val status = if (issues.isEmpty() && messagesExported > 0) SyncStatus.COMPLETED else SyncStatus.PARTIAL
            auditLog.finishSession(
                sessionId,
                status,
                artifactsPublished,
                bytesPublished,
                issues.firstOrNull()?.remediation,
            )
            onProgress(
                IosBackupImportProgress(
                    IosBackupImportStage.COMPLETE,
                    "iPhone backup import complete",
                    presentFiles,
                    declaredFiles,
                    bytesPublished,
                ),
            )
            IosBackupImportResult(
                backupPreserved,
                declaredFiles,
                presentFiles,
                missingFiles,
                messagesExported,
                attachmentsExported,
                artifactsPublished,
                issues,
            )
        } catch (throwable: Throwable) {
            val detail = throwable.message ?: throwable.javaClass.simpleName
            val issue = RecoveryIssue(
                sourceItem = "/Owner-approved iPhone backup",
                reason = RecoveryIssueReason.COPY_FAILED,
                remediation = detail,
                retryable = true,
            )
            issues += issue
            auditLog.finishSession(
                sessionId,
                if (backupPreserved) SyncStatus.PARTIAL else SyncStatus.FAILED,
                artifactsPublished,
                bytesPublished,
                detail,
            )
            IosBackupImportResult(
                backupPreserved,
                declaredFiles,
                presentFiles,
                missingFiles,
                messagesExported,
                attachmentsExported,
                artifactsPublished,
                issues.distinctBy { "${it.reason}:${it.sourceItem}:${it.remediation}" },
                detail,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun publishArtifact(
        sessionId: Long,
        peerId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        category: ConsentCategory,
        sourceName: String,
        sourceItem: String,
    ): PublishedArtifact {
        val imported = targetMediaStore.copyFromProvider(
            sourceUri = sourceUri,
            displayName = displayName,
            mimeType = mimeType,
            category = category,
            sourceName = sourceName,
            modifiedAtEpochMillis = System.currentTimeMillis(),
        )
        val fingerprint = DeviceIdentity.sha256("$peerId|$sourceItem|${imported.sha256}|${imported.bytesWritten}")
        val existing = auditLog.completedTransferByContent(peerId, imported.sha256, category)
        val existingIntegrity = existing?.let {
            targetMediaStore.verifyStoredItem(it.destination, it.bytesTransferred, imported.sha256)
        }
        if (existing != null && existingIntegrity != null) {
            targetMediaStore.discardStoredItem(imported.uri)
            auditLog.recordTransferAlias(peerId, fingerprint, existing.id)
            return PublishedArtifact(existingIntegrity.bytes, existingIntegrity.sha256, wasPublished = false)
        }
        auditLog.recordTransfer(
            sessionId = sessionId,
            peerId = peerId,
            sourceFingerprint = fingerprint,
            category = category,
            sourceItem = sourceItem,
            destination = imported.uri.toString(),
            bytesTransferred = imported.bytesWritten,
            status = TransferStatus.COMPLETED,
            sourceSize = imported.bytesWritten,
            sourceModifiedAtEpochMillis = System.currentTimeMillis(),
            contentSha256 = imported.sha256,
        )
        return PublishedArtifact(imported.bytesWritten, imported.sha256, wasPublished = true)
    }

    private fun extractSingleManifest(archiveUri: Uri, workspace: File): BackupRoot {
        var manifestRoot: String? = null
        val manifestFile = File(workspace, "Manifest.db")
        openArchive(archiveUri).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val normalized = normalizeZipEntry(entry.name)
                if (!entry.isDirectory && normalized.substringAfterLast('/') == "Manifest.db") {
                    check(manifestRoot == null) {
                        "The ZIP contains more than one Apple backup. Package and import one iPhone backup directory at a time."
                    }
                    manifestRoot = normalized.substringBeforeLast('/', "")
                    copyLimited(archive, manifestFile, MAX_DATABASE_BYTES)
                }
                archive.closeEntry()
            }
        }
        check(manifestRoot != null && manifestFile.isFile) {
            "Manifest.db was not found. ZIP the complete Apple Devices/Finder backup directory, not an individual file."
        }
        return BackupRoot(manifestRoot.orEmpty(), manifestFile)
    }

    private fun openBackupDatabase(file: File, failureMessage: String): SQLiteDatabase {
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        }.getOrElse { throwable ->
            error("$failureMessage (${throwable.message.orEmpty()})")
        }
    }

    private fun readBackupFiles(database: SQLiteDatabase): List<IosBackupFile> {
        check(tableExists(database, "Files")) { "Manifest.db does not contain the Apple Files table." }
        database.rawQuery(
            "SELECT fileID, domain, relativePath, flags FROM Files ORDER BY domain, relativePath",
            null,
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val fileId = cursor.getString(0).orEmpty().trim()
                    add(
                        IosBackupFile(
                            fileId = fileId,
                            domain = cursor.getString(1).orEmpty(),
                            relativePath = cursor.getString(2).orEmpty(),
                            flags = cursor.getInt(3),
                        ),
                    )
                }
            }
        }
    }

    private fun readArchiveEntryNames(archiveUri: Uri): Set<String> {
        openArchive(archiveUri).use { archive ->
            return buildSet {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (!entry.isDirectory) add(normalizeZipEntry(entry.name))
                    archive.closeEntry()
                }
            }
        }
    }

    private fun resolveArchivePath(root: String, fileId: String, entries: Set<String>): String? {
        if (fileId.isBlank()) return null
        val prefix = root.takeIf(String::isNotBlank)?.plus('/').orEmpty()
        val candidates = listOf(
            "$prefix${fileId.take(2)}/$fileId",
            "$prefix$fileId",
        )
        return candidates.firstOrNull(entries::contains)
    }

    private fun writeBackupInventory(
        destination: File,
        files: List<IosBackupFile>,
        onProgress: (IosBackupImportProgress) -> Unit,
    ) {
        destination.bufferedWriter(Charsets.UTF_8).use { writer ->
            files.forEachIndexed { index, file ->
                val logicalPath = "/${file.domain}/${file.relativePath}".replace("//", "/")
                writer.append(
                    JSONObject()
                        .put("fileId", file.fileId)
                        .put("domain", file.domain)
                        .put("relativePath", file.relativePath)
                        .put("flags", file.flags)
                        .put("archivePath", file.archivePath ?: JSONObject.NULL)
                        .put("presentInBackupZip", file.archivePath != null)
                        .put("category", classifyBackupFile(file).name)
                        .put("logicalPath", logicalPath)
                        .toString(),
                )
                writer.newLine()
                if ((index + 1) % PROGRESS_ITEM_INTERVAL == 0 || index + 1 == files.size) {
                    onProgress(
                        IosBackupImportProgress(
                            IosBackupImportStage.VERIFYING_BACKUP_FILES,
                            file.relativePath.ifBlank { file.domain },
                            index + 1,
                            files.size,
                        ),
                    )
                }
            }
        }
    }

    private fun exportMessages(
        smsDatabaseFile: File,
        destination: File,
        onProgress: (IosBackupImportProgress) -> Unit,
    ): Int {
        val database = openBackupDatabase(
            smsDatabaseFile,
            "The recovered sms.db could not be opened. Import a complete, unencrypted Apple backup.",
        )
        return database.use { smsDatabase ->
            smsDatabase.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                val integrity = if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "no result"
                check(integrity.equals("ok", ignoreCase = true)) {
                    "sms.db integrity validation failed: $integrity. The raw database remains preserved; create and import a new complete Apple backup."
                }
            }
            check(tableExists(smsDatabase, "message")) { "sms.db does not contain a message table." }
            val handles = loadRowsById(smsDatabase, "handle")
            val chats = loadRelatedRows(smsDatabase, "chat_message_join", "chat", "chat_id")
            val attachments = loadRelatedRows(
                smsDatabase,
                "message_attachment_join",
                "attachment",
                "attachment_id",
            )
            var exported = 0
            destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                smsDatabase.rawQuery("SELECT ROWID AS _message_rowid, * FROM message ORDER BY ROWID", null)
                    .use { cursor ->
                        val total = queryRowCount(smsDatabase, "message")
                        while (cursor.moveToNext()) {
                            val raw = cursorRowToJson(cursor)
                            val rowId = raw.optLong("_message_rowid", -1)
                            val handleId = raw.optLong("handle_id", -1)
                            val handle = handles[handleId]
                            val isFromMe = raw.optLong("is_from_me", 0) != 0L
                            val record = JSONObject()
                                .put("recordType", "iOS Message")
                                .put("messageRowId", rowId)
                                .put("guid", raw.opt("guid") ?: JSONObject.NULL)
                                .put("text", raw.opt("text") ?: JSONObject.NULL)
                                .put("service", raw.opt("service") ?: JSONObject.NULL)
                                .put("isFromMe", isFromMe)
                                .put(
                                    "sender",
                                    if (isFromMe) "Me" else handle?.optString("id")?.takeIf(String::isNotBlank)
                                        ?: JSONObject.NULL,
                                )
                                .put("timestamp", appleTimestamp(raw.optLong("date", 0)) ?: JSONObject.NULL)
                                .put("handle", handle ?: JSONObject.NULL)
                                .put("chats", JSONArray(chats[rowId].orEmpty()))
                                .put("attachments", JSONArray(attachments[rowId].orEmpty()))
                                .put("rawMessage", raw)
                            writer.append(record.toString())
                            writer.newLine()
                            exported += 1
                            if (exported % PROGRESS_ITEM_INTERVAL == 0 || exported == total) {
                                onProgress(
                                    IosBackupImportProgress(
                                        IosBackupImportStage.EXPORTING_MESSAGES,
                                        "Message $exported of $total",
                                        exported,
                                        total,
                                    ),
                                )
                            }
                        }
                    }
            }
            exported
        }
    }

    private fun loadRowsById(database: SQLiteDatabase, table: String): Map<Long, JSONObject> {
        if (!tableExists(database, table)) return emptyMap()
        database.rawQuery("SELECT ROWID AS _rowid, * FROM `$table`", null).use { cursor ->
            return buildMap {
                while (cursor.moveToNext()) {
                    val row = cursorRowToJson(cursor)
                    put(row.optLong("_rowid", -1), row)
                }
            }
        }
    }

    private fun loadRelatedRows(
        database: SQLiteDatabase,
        joinTable: String,
        targetTable: String,
        targetIdColumn: String,
    ): Map<Long, List<JSONObject>> {
        if (!tableExists(database, joinTable) || !tableExists(database, targetTable)) return emptyMap()
        val joinColumns = tableColumns(database, joinTable)
        if ("message_id" !in joinColumns || targetIdColumn !in joinColumns) return emptyMap()
        database.rawQuery(
            "SELECT j.message_id AS _message_id, t.ROWID AS _related_rowid, t.* " +
                "FROM `$joinTable` j JOIN `$targetTable` t ON t.ROWID = j.`$targetIdColumn` " +
                "ORDER BY j.message_id, t.ROWID",
            null,
        ).use { cursor ->
            return buildMap<Long, MutableList<JSONObject>> {
                while (cursor.moveToNext()) {
                    val row = cursorRowToJson(cursor)
                    getOrPut(row.optLong("_message_id", -1)) { mutableListOf() }.add(row)
                }
            }
        }
    }

    private fun exportAttachments(
        sourceArchive: Uri,
        attachmentFiles: List<IosBackupFile>,
        destination: File,
        onProgress: (IosBackupImportProgress) -> Unit,
    ): Int {
        val byArchivePath = attachmentFiles.associateBy { it.archivePath!! }
        var exported = 0
        ZipOutputStream(destination.outputStream().buffered()).use { output ->
            openArchive(sourceArchive).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val file = byArchivePath[normalizeZipEntry(entry.name)]
                    if (!entry.isDirectory && file != null) {
                        val relativeName = safeAttachmentPath(file.relativePath)
                        output.putNextEntry(ZipEntry(relativeName).apply { time = entry.time })
                        archive.copyTo(output)
                        output.closeEntry()
                        exported += 1
                        onProgress(
                            IosBackupImportProgress(
                                IosBackupImportStage.EXPORTING_ATTACHMENTS,
                                relativeName,
                                exported,
                                attachmentFiles.size,
                            ),
                        )
                    }
                    archive.closeEntry()
                }
            }
        }
        return exported
    }

    private fun extractArchiveEntry(sourceArchive: Uri, archivePath: String, destination: File) {
        openArchive(sourceArchive).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && normalizeZipEntry(entry.name) == archivePath) {
                    copyLimited(archive, destination, MAX_DATABASE_BYTES)
                    return
                }
                archive.closeEntry()
            }
        }
        error("The Apple backup payload is missing: $archivePath")
    }

    private fun cursorRowToJson(cursor: Cursor): JSONObject {
        return JSONObject().apply {
            cursor.columnNames.forEachIndexed { index, name ->
                val value: Any = when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).takeIf(Double::isFinite)?.toString()
                        ?: JSONObject.NULL
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                    else -> JSONObject.NULL
                }
                put(name, value)
            }
        }
    }

    private fun tableExists(database: SQLiteDatabase, table: String): Boolean {
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { return it.moveToFirst() }
    }

    private fun tableColumns(database: SQLiteDatabase, table: String): Set<String> {
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
    }

    private fun queryRowCount(database: SQLiteDatabase, table: String): Int {
        database.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun classifyBackupFile(file: IosBackupFile): ConsentCategory {
        val logicalPath = "/${file.domain}/${file.relativePath}".lowercase()
        return when {
            file.relativePath.startsWith("Library/SMS/", ignoreCase = true) -> ConsentCategory.SMS_EXPORTS
            IOS_CHAT_MARKERS.any(logicalPath::contains) -> ConsentCategory.CHAT_EXPORTS
            "/addressbook/" in logicalPath || "addressbook.sqlitedb" in logicalPath -> ConsentCategory.CONTACTS
            "callhistory" in logicalPath -> ConsentCategory.CALL_LOGS
            "/calendar/" in logicalPath || "calendar.sqlitedb" in logicalPath -> ConsentCategory.CALENDAR
            "/mail/" in logicalPath -> ConsentCategory.EMAIL_EXPORTS
            "voicemail" in logicalPath -> ConsentCategory.VOICEMAIL_EXPORTS
            "keychain" in logicalPath || "credential" in logicalPath -> ConsentCategory.PASSWORD_EXPORTS
            file.domain.startsWith("CameraRollDomain", ignoreCase = true) -> ConsentCategory.PHOTOS_AND_VIDEOS
            else -> TransferClassifier.classify(logicalPath)
        }
    }

    private fun IosBackupFile.isSmsDatabaseComponent(): Boolean {
        return domain.equals("HomeDomain", ignoreCase = true) &&
            relativePath.lowercase() in IOS_SMS_DATABASE_COMPONENTS
    }

    private fun IosBackupFile.isSmsAttachment(): Boolean {
        return relativePath.startsWith(IOS_SMS_ATTACHMENTS_PATH, ignoreCase = true)
    }

    private fun openArchive(uri: Uri): ZipInputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Android could not open the selected iPhone backup ZIP.")
        return ZipInputStream(input.buffered())
    }

    private fun copyLimited(input: InputStream, destination: File, maximumBytes: Long) {
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                check(total <= maximumBytes) { "${destination.name} exceeds the supported extraction size." }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun normalizeZipEntry(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        check(normalized.split('/').none { it == ".." }) { "Unsafe ZIP path: $value" }
        return normalized
    }

    private fun safeAttachmentPath(relativePath: String): String {
        val withoutRoot = relativePath.substringAfter("Library/SMS/", relativePath)
        return withoutRoot.replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment ->
                segment.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)
            }
            .ifBlank { "Attachments/recovered-attachment" }
    }

    private fun sourceDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "iphone-backup.zip"
    }

    private fun ownerBackupName(sourceName: String): String {
        val safeName = sourceName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return "owner-approved-${safeName.ifBlank { "iphone-backup.zip" }}"
    }

    private fun appleTimestamp(value: Long): String? {
        if (value == 0L) return null
        val millisecondsSinceAppleEpoch = when {
            kotlin.math.abs(value) >= 10_000_000_000_000L -> value / 1_000_000L
            else -> value * 1_000L
        }
        return runCatching {
            Instant.ofEpochMilli(APPLE_EPOCH_MILLIS + millisecondsSinceAppleEpoch).toString()
        }.getOrNull()
    }

    private data class BackupRoot(
        val rootPrefix: String,
        val manifestFile: File,
    )

    private data class IosBackupFile(
        val fileId: String,
        val domain: String,
        val relativePath: String,
        val flags: Int,
        val archivePath: String? = null,
    )

    private data class PublishedArtifact(
        val bytes: Long,
        val sha256: String,
        val wasPublished: Boolean,
    )

    private companion object {
        const val IOS_SMS_DATABASE_PATH = "Library/SMS/sms.db"
        const val IOS_SMS_ATTACHMENTS_PATH = "Library/SMS/Attachments/"
        val IOS_SMS_DATABASE_COMPONENTS = setOf(
            "library/sms/sms.db",
            "library/sms/sms.db-wal",
            "library/sms/sms.db-shm",
        )
        val IOS_CHAT_MARKERS = listOf(
            "whatsapp",
            "signal",
            "telegram",
            "msteams",
            "microsoft.teams",
            "zoom",
            "webex",
            "slack",
            "discord",
            "messenger",
        )
        const val APPLE_EPOCH_MILLIS = 978_307_200_000L
        const val MAX_DATABASE_BYTES = 8L * 1024 * 1024 * 1024
        const val PROGRESS_ITEM_INTERVAL = 100
    }
}