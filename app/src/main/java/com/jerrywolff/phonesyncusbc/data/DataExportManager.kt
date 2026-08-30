package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(
    val exportedItems: Int,
    val failedItems: Int,
    val bytesExported: Long,
    val error: String? = null,
    val excludedItems: Int = 0,
    val recoveryIssues: List<RecoveryIssue> = emptyList(),
)

data class ExportProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItem: String,
    val bytesExported: Long,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
)

data class ArchiveProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentItem: String,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
    val sourceBytesArchived: Long = 0,
)

data class ArchiveResult(
    val uri: Uri? = null,
    val displayName: String? = null,
    val archivedItems: Int = 0,
    val sourceBytes: Long = 0,
    val archiveBytes: Long = 0,
    val archiveSha256: String? = null,
    val error: String? = null,
    val excludedItems: Int = 0,
    val recoveryIssues: List<RecoveryIssue> = emptyList(),
)

private data class PackageIntegrity(
    val bytes: Long,
    val sha256: String,
)

class DataExportManager(private val context: Context) {
    fun cleanupInterruptedUploadArchives(): Int {
        return cleanupUploadArchives(pendingOnly = true)
    }

    fun cleanupObsoleteUploadArchives(): Int {
        return cleanupUploadArchives(pendingOnly = false)
    }

    private fun cleanupUploadArchives(pendingOnly: Boolean): Int {
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = android.provider.MediaStore.MediaColumns.RELATIVE_PATH
        val displayName = android.provider.MediaStore.MediaColumns.DISPLAY_NAME
        val selection: String
        val arguments: Array<String>
        if (pendingOnly) {
            selection = "(($relativePath LIKE ? AND $displayName LIKE ?) OR " +
                "($relativePath LIKE ? AND $displayName LIKE ?) OR " +
                "($relativePath LIKE ? AND $displayName LIKE ?)) AND " +
                "${android.provider.MediaStore.MediaColumns.IS_PENDING} = 1"
            arguments = arrayOf(
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Uploads%",
                "PhoneSyncBackup-%",
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/RecoverByBackup Packages%",
                "RecoverByBackup-%",
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync USB-C Packages%",
                "Phone Sync USB-C-%",
            )
        } else {
            selection = "$relativePath LIKE ? AND $displayName LIKE ?"
            arguments = arrayOf(
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Uploads%",
                "PhoneSyncBackup-%",
            )
        }
        val ids = buildList {
            context.contentResolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                selection,
                arguments,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        return ids.count { id ->
            context.contentResolver.delete(
                android.content.ContentUris.withAppendedId(collection, id),
                null,
                null,
            ) > 0
        }
    }

    fun backupToDownloads(
        entries: List<AuditEntry>,
        expectedPeerId: String?,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        if (entries.isEmpty()) return ExportResult(0, 0, 0, "No recovered artifacts are available to preserve.")
        val selection = planExternalRecoveryEntries(entries, expectedPeerId)
        val eligibleEntries = selection.eligibleEntries
        if (eligibleEntries.isEmpty()) {
            return ExportResult(
                0,
                0,
                0,
                "No selected-source items are eligible. Review the recovery actions and retry.",
                selection.excludedItems,
                selection.issues,
            )
        }
        val folder = "${android.os.Environment.DIRECTORY_DOWNLOADS}/Phone Sync Backups/" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var exported = 0
        var failed = 0
        var bytes = 0L
        var processed = 0
        var firstError: String? = null
        val recoveryIssues = selection.issues.toMutableList()

        eligibleEntries.forEach { entry ->
            val displayName = sanitizeName(entry.sourceItem.substringAfterLast('/'))
                .ifBlank { "recovered-artifact" }
            onProgress(ExportProgress(processed, eligibleEntries.size, displayName, bytes))
            val source = entry.destination?.let(Uri::parse)
            if (source == null) {
                failed += 1
                recoveryIssues += copyFailureIssue(entry.sourceItem, "Recovered copy is unavailable.")
                processed += 1
                firstError = firstError ?: "A recovered artifact has no stored location."
                onProgress(ExportProgress(processed, eligibleEntries.size, displayName, bytes))
                return@forEach
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType(entry))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/${entry.category.name.lowercase()}")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = runCatching {
                context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                )
            }.getOrNull()
            if (target == null) {
                failed += 1
                recoveryIssues += copyFailureIssue(entry.sourceItem, "Backup destination could not be created.")
                processed += 1
                firstError = firstError ?: "Could not create the backup destination for $displayName."
                onProgress(ExportProgress(processed, eligibleEntries.size, displayName, bytes))
                return@forEach
            }
            val itemTotal = sourceSize(source)
            var itemBytes = 0L
            runCatching {
                copy(source, target) { copied ->
                    itemBytes = copied
                    onProgress(
                        ExportProgress(
                            processed,
                            eligibleEntries.size,
                            displayName,
                            bytes + copied,
                            copied,
                            itemTotal,
                        ),
                    )
                }
            }
                .onSuccess {
                    context.contentResolver.update(
                        target,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )
                    exported += 1
                    bytes += it
                }
                .onFailure { throwable ->
                    context.contentResolver.delete(target, null, null)
                    failed += 1
                    recoveryIssues += copyFailureIssue(entry.sourceItem, throwable.message)
                    firstError = firstError ?: (throwable.message ?: "Could not back up $displayName.")
                }
            processed += 1
            onProgress(
                ExportProgress(processed, eligibleEntries.size, displayName, bytes, itemBytes, itemTotal),
            )
        }
        return ExportResult(
            exported,
            failed,
            bytes,
            firstError,
            selection.excludedItems,
            recoveryIssues,
        )
    }

    fun export(
        entries: List<AuditEntry>,
        destinationTree: Uri,
        expectedPeerId: String?,
        folderNamePrefix: String = "Phone Sync Export",
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val selection = planExternalRecoveryEntries(entries, expectedPeerId)
        val eligibleEntries = selection.eligibleEntries
        if (eligibleEntries.isEmpty()) {
            return ExportResult(
                0,
                0,
                0,
                "No selected-source items are eligible. Review the recovery actions and retry.",
                selection.excludedItems,
                selection.issues,
            )
        }
        val root = if (destinationTree.scheme == "file") {
            destinationTree.path?.let(::File)?.let(DocumentFile::fromFile)
        } else {
            DocumentFile.fromTreeUri(context, destinationTree)
        }
            ?: return ExportResult(0, 0, 0, "The selected destination is unavailable.", selection.excludedItems, selection.issues)
        if (!root.canWrite()) {
            return ExportResult(0, 0, 0, "The selected destination is not writable.", selection.excludedItems, selection.issues)
        }

        val folderName = "$folderNamePrefix " +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val exportFolder = root.createDirectory(folderName)
            ?: return ExportResult(0, 0, 0, "Could not create the export folder.", selection.excludedItems, selection.issues)

        var exported = 0
        var failed = 0
        var bytes = 0L
        var processed = 0
        var firstError: String? = null
        val recoveryIssues = selection.issues.toMutableList()
        eligibleEntries.forEach { entry ->
            val source = entry.destination?.let(Uri::parse)
            val sourceName = source?.let { sourceDisplayName(it, entry) }
                ?: entry.sourceItem.substringAfterLast('/').ifBlank { "recovered-artifact" }
            onProgress(ExportProgress(processed, eligibleEntries.size, sourceName, bytes))
            if (source == null) {
                failed += 1
                recoveryIssues += copyFailureIssue(entry.sourceItem, "Recovered copy is unavailable.")
                processed += 1
                firstError = firstError ?: "A recovered artifact has no stored location."
                onProgress(ExportProgress(processed, eligibleEntries.size, sourceName, bytes))
                return@forEach
            }

            val displayName = uniqueName(
                exportFolder,
                sanitizeName(sourceName),
            )
            val targetMimeType = context.contentResolver.getType(source) ?: mimeType(entry)
            val target = exportFolder.createFile(
                targetMimeType,
                displayName.withoutMimeExtension(targetMimeType),
            )
            if (target == null) {
                failed += 1
                recoveryIssues += copyFailureIssue(entry.sourceItem, "Export destination could not be created.")
                processed += 1
                firstError = firstError ?: "Could not create $displayName."
                onProgress(ExportProgress(processed, eligibleEntries.size, displayName, bytes))
                return@forEach
            }

            val itemTotal = sourceSize(source)
            var itemBytes = 0L
            runCatching {
                val copied = copy(source, target.uri) { copied ->
                    itemBytes = copied
                    onProgress(
                        ExportProgress(
                            processed,
                            eligibleEntries.size,
                            displayName,
                            bytes + copied,
                            copied,
                            itemTotal,
                        ),
                    )
                }
                val sourceIntegrity = if (entry.contentSha256.isNullOrBlank()) {
                    calculateUriIntegrity(source)
                } else {
                    PackageIntegrity(
                        bytes = entry.bytesTransferred.takeIf { it > 0 } ?: copied,
                        sha256 = entry.contentSha256,
                    )
                }
                val destinationIntegrity = calculateUriIntegrity(target.uri)
                check(destinationIntegrity.bytes == copied && destinationIntegrity.bytes == sourceIntegrity.bytes) {
                    "Destination size verification failed for $displayName."
                }
                check(destinationIntegrity.sha256.equals(sourceIntegrity.sha256, ignoreCase = true)) {
                    "Destination SHA-256 verification failed for $displayName."
                }
                destinationIntegrity.bytes
            }.onSuccess { copied ->
                exported += 1
                bytes += copied
            }.onFailure { throwable ->
                context.contentResolver.delete(target.uri, null, null)
                failed += 1
                recoveryIssues += copyFailureIssue(entry.sourceItem, throwable.message)
                firstError = firstError ?: (throwable.message ?: "Could not export $displayName.")
            }
            processed += 1
            onProgress(
                ExportProgress(processed, eligibleEntries.size, displayName, bytes, itemBytes, itemTotal),
            )
        }
        return ExportResult(exported, failed, bytes, firstError, selection.excludedItems, recoveryIssues)
    }

    fun createUploadArchive(
        entries: List<AuditEntry>,
        expectedPeerId: String?,
        archiveNamePrefix: String = "PhoneSyncBackup",
        destinationFolder: String = "Phone Sync Uploads",
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ArchiveResult {
        if (entries.isEmpty()) return ArchiveResult(error = "No recovered artifacts are selected for preservation.")
        val selection = planExternalRecoveryEntries(entries, expectedPeerId)
        val eligibleEntries = selection.eligibleEntries
        if (eligibleEntries.isEmpty()) {
            return ArchiveResult(
                error = "No selected-source items are eligible. Review the recovery actions and retry.",
                excludedItems = selection.excludedItems,
                recoveryIssues = selection.issues,
            )
        }

        val safeArchivePrefix = sanitizeName(archiveNamePrefix).ifBlank { "RecoveryBackup" }
        val displayName = "$safeArchivePrefix-${timestamp()}.zip"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DOWNLOADS}/$destinationFolder",
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return ArchiveResult(error = "Android could not create the upload archive.")

        return try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: error("Android could not open the upload archive.")
            val countingOutput = CountingOutputStream(output)
            val manifestEntries = JSONArray()
            val usedPaths = mutableSetOf<String>()
            var sourceBytes = 0L

            ZipOutputStream(countingOutput).use { archive ->
                archive.setLevel(Deflater.NO_COMPRESSION)
                eligibleEntries.forEachIndexed { index, entry ->
                    val source = entry.destination?.let(Uri::parse)
                        ?: error("${entry.sourceItem} has no stored location.")
                    val sourceName = sanitizeName(sourceDisplayName(source, entry))
                        .ifBlank { "recovered-artifact" }
                    val archivePath = uniqueArchivePath(
                        usedPaths,
                        "${entry.category.name.lowercase()}/$sourceName",
                    )
                    val itemTotal = sourceSize(source)
                    onProgress(
                        ArchiveProgress(
                            index,
                            eligibleEntries.size,
                            sourceName,
                            currentItemTotal = itemTotal,
                            sourceBytesArchived = sourceBytes,
                        ),
                    )
                    archive.putNextEntry(
                        ZipEntry(archivePath).apply {
                            time = entry.transferredAtEpochMillis
                        },
                    )
                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = context.contentResolver.openInputStream(source)
                        ?: error("Could not read $sourceName.")
                    val copied = input.use { sourceStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        var lastReported = 0L
                        while (true) {
                            val count = sourceStream.read(buffer)
                            if (count < 0) break
                            archive.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            total += count
                            if (total - lastReported >= PROGRESS_REPORT_BYTES) {
                                onProgress(
                                    ArchiveProgress(
                                        index,
                                        eligibleEntries.size,
                                        sourceName,
                                        total,
                                        itemTotal,
                                        sourceBytes + total,
                                    ),
                                )
                                lastReported = total
                            }
                        }
                        total
                    }
                    archive.closeEntry()
                    val archiveSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    check(entry.bytesTransferred <= 0 || copied == entry.bytesTransferred) {
                        "Recovered artifact size changed before preservation: $sourceName."
                    }
                    check(entry.contentSha256 == null || archiveSha256.equals(entry.contentSha256, ignoreCase = true)) {
                        "Recovered artifact SHA-256 changed before preservation: $sourceName."
                    }
                    sourceBytes += copied
                    manifestEntries.put(
                        JSONObject()
                            .put("category", entry.category.name)
                            .put("peerId", entry.peerId)
                            .put("sourceFingerprint", entry.sourceFingerprint)
                            .put("sourceItem", entry.sourceItem)
                            .put("sourceSize", entry.sourceSize)
                            .put("sourceModifiedAtEpochMillis", entry.sourceModifiedAtEpochMillis)
                            .put("recoveredAtEpochMillis", entry.transferredAtEpochMillis)
                            .put("archivePath", archivePath)
                            .put("bytes", copied)
                            .put("sha256", archiveSha256)
                            .put("recoveryContentSha256", entry.contentSha256 ?: JSONObject.NULL)
                            .put("sensitive", entry.category == ConsentCategory.PASSWORD_EXPORTS),
                    )
                    onProgress(
                        ArchiveProgress(
                            index + 1,
                            eligibleEntries.size,
                            sourceName,
                            copied,
                            itemTotal,
                            sourceBytes,
                        ),
                    )
                }

                val manifest = JSONObject()
                    .put("createdAtEpochMillis", System.currentTimeMillis())
                    .put("externalPeerId", expectedPeerId)
                    .put("itemCount", eligibleEntries.size)
                    .put("excludedItemCount", selection.excludedItems)
                    .put("sourceBytes", sourceBytes)
                    .put("entries", manifestEntries)
                archive.putNextEntry(ZipEntry("backup-manifest.json"))
                archive.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
            context.contentResolver.openFileDescriptor(destination, "rw").use { descriptor ->
                checkNotNull(descriptor) { "Android could not flush the packaged backup." }
                descriptor.fileDescriptor.sync()
            }
            val packageIntegrity = calculateUriIntegrity(destination)
            check(packageIntegrity.bytes == countingOutput.bytesWritten) {
                "Packaged backup size changed before publication."
            }
            context.contentResolver.update(
                destination,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            ArchiveResult(
                uri = destination,
                displayName = displayName,
                archivedItems = eligibleEntries.size,
                sourceBytes = sourceBytes,
                archiveBytes = packageIntegrity.bytes,
                archiveSha256 = packageIntegrity.sha256,
                excludedItems = selection.excludedItems,
                recoveryIssues = selection.issues,
            )
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            ArchiveResult(
                error = throwable.message ?: throwable.javaClass.simpleName,
                excludedItems = selection.excludedItems,
                recoveryIssues = selection.issues + copyFailureIssue("Upload archive", throwable.message),
            )
        }
    }

    private fun sourceDisplayName(source: Uri, entry: AuditEntry): String {
        val queriedName = runCatching {
            context.contentResolver.query(
                source,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queriedName
            ?.takeIf { it.isNotBlank() }
            ?: entry.sourceItem.substringAfterLast('/').ifBlank { "recovered-artifact" }
    }

    private fun calculateUriIntegrity(uri: Uri): PackageIntegrity {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Android could not reopen the packaged backup for verification." }
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        return PackageIntegrity(
            bytes = bytes,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    fun mimeType(entry: AuditEntry): String {
        if (entry.category == ConsentCategory.CONTACTS) return "text/vcard"
        if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return "application/octet-stream"
        val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun sourceSize(source: Uri): Long {
        return runCatching {
            context.contentResolver.query(
                source,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).coerceAtLeast(0) else 0
            } ?: 0
        }.getOrDefault(0)
    }

    private fun copy(
        source: Uri,
        target: Uri,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val input = context.contentResolver.openInputStream(source)
            ?: error("Could not read the recovered artifact.")
        val output = context.contentResolver.openOutputStream(target, "w")
            ?: error("Could not open the export destination.")
        return input.use { sourceStream ->
            output.use { targetStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var lastReported = 0L
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    targetStream.write(buffer, 0, count)
                    total += count
                    if (total - lastReported >= PROGRESS_REPORT_BYTES) {
                        onProgress(total)
                        lastReported = total
                    }
                }
                if (total != lastReported) onProgress(total)
                total
            }
        }
    }

    private fun uniqueName(folder: DocumentFile, baseName: String): String {
        val safeBase = baseName.ifBlank { "recovered-artifact" }
        val extension = safeBase.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isBlank()) safeBase else safeBase.removeSuffix(".$extension")
        var candidate = safeBase
        var index = 2
        while (folder.findFile(candidate) != null) {
            candidate = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            index += 1
        }
        return candidate
    }

    private fun String.withoutMimeExtension(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf(String::isNotBlank)
            ?: return this
        val suffix = ".$extension"
        return if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
    }

    private fun uniqueArchivePath(usedPaths: MutableSet<String>, basePath: String): String {
        val extension = basePath.substringAfterLast('.', missingDelimiterValue = "")
        val stem = if (extension.isBlank()) basePath else basePath.removeSuffix(".$extension")
        var candidate = basePath
        var index = 2
        while (!usedPaths.add(candidate)) {
            candidate = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            index += 1
        }
        return candidate
    }

    private fun sanitizeName(value: String): String {
        return value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_REPORT_BYTES = 1024 * 1024L
    }
}