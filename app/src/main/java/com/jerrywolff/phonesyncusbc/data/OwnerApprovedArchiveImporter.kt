package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import com.jerrywolff.phonesyncusbc.sync.TargetMediaStore
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

enum class OwnerArchiveImportStage {
    PRESERVING_SOURCE,
    COUNTING_ITEMS,
    RECOVERING_ITEMS,
    WRITING_INVENTORY,
    COMPLETE,
}

data class OwnerArchiveImportProgress(
    val stage: OwnerArchiveImportStage,
    val currentItem: String,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val bytesProcessed: Long = 0,
)

data class OwnerArchiveImportResult(
    val sourcePreserved: Boolean,
    val declaredItems: Int,
    val recoveredItems: Int,
    val alreadyRecoveredItems: Int,
    val directoryItems: Int,
    val failedItems: Int,
    val artifactsPublished: Int,
    val bytesPublished: Long,
    val categoriesRecovered: Set<ConsentCategory>,
    val issues: List<RecoveryIssue>,
    val error: String? = null,
)

class OwnerApprovedArchiveImporter(
    private val context: Context,
    private val auditLog: AuditLog,
    private val targetMediaStore: TargetMediaStore = TargetMediaStore(context),
) {
    fun importSource(
        sourceUri: Uri,
        peerId: String,
        sourceName: String,
        deviceType: RecoveryDeviceType,
        onProgress: (OwnerArchiveImportProgress) -> Unit = {},
    ): OwnerArchiveImportResult {
        require(isExternalSourcePeer(peerId)) { "Select the external source device before importing its owner-approved data." }
        val sessionId = auditLog.beginSession(peerId)
        val displayName = sourceDisplayName(sourceUri)
        val sourceMimeType = context.contentResolver.getType(sourceUri)
        val zipSource = isZipSource(sourceUri, displayName, sourceMimeType)
        val workspace = File(context.cacheDir, "owner-approved-source-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val issues = mutableListOf<RecoveryIssue>()
        val categories = linkedSetOf<ConsentCategory>()
        var sourcePreserved = false
        var declaredItems = 0
        var recoveredItems = 0
        var alreadyRecoveredItems = 0
        var directoryItems = 0
        var failedItems = 0
        var artifactsPublished = 0
        var bytesPublished = 0L
        var sourcePackageSha256 = ""

        return try {
            onProgress(
                OwnerArchiveImportProgress(
                    OwnerArchiveImportStage.PRESERVING_SOURCE,
                    displayName,
                ),
            )
            val sourceCategory = if (zipSource) {
                ConsentCategory.APPLICATION_DATA
            } else {
                TransferClassifier.classify(displayName)
            }
            val preserved = publishProviderArtifact(
                sessionId = sessionId,
                peerId = peerId,
                sourceUri = sourceUri,
                displayName = ownerSourceName(displayName),
                mimeType = sourceMimeType,
                category = sourceCategory,
                sourceName = sourceName,
                sourceItem = "/Owner-approved ${deviceType.name.lowercase()} source/$displayName",
            )
            sourcePreserved = true
            sourcePackageSha256 = preserved.sha256
            if (preserved.wasPublished) {
                artifactsPublished += 1
                bytesPublished += preserved.bytes
            } else {
                alreadyRecoveredItems += 1
            }
            categories += sourceCategory

            if (!zipSource) {
                declaredItems = 1
                recoveredItems = 1
                val inventoryFile = File(workspace, "owner-approved-source-inventory.jsonl")
                inventoryFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.append(
                        inventoryLine(
                            index = 1,
                            path = displayName,
                            directory = false,
                            category = sourceCategory,
                            status = if (preserved.wasPublished) "RECOVERED" else "ALREADY_RECOVERED",
                            bytes = preserved.bytes,
                            sha256 = preserved.sha256,
                        ).toString(),
                    )
                    writer.newLine()
                }
                val inventory = publishInventory(
                    sessionId,
                    peerId,
                    sourceName,
                    deviceType,
                    inventoryFile,
                    preserved.sha256,
                )
                if (inventory.wasPublished) {
                    artifactsPublished += 1
                    bytesPublished += inventory.bytes
                }
                auditLog.finishSession(sessionId, SyncStatus.COMPLETED, artifactsPublished, bytesPublished)
                onProgress(
                    OwnerArchiveImportProgress(
                        OwnerArchiveImportStage.COMPLETE,
                        displayName,
                        1,
                        1,
                        bytesPublished,
                    ),
                )
                return OwnerArchiveImportResult(
                    sourcePreserved,
                    declaredItems,
                    recoveredItems,
                    alreadyRecoveredItems,
                    directoryItems,
                    failedItems,
                    artifactsPublished,
                    bytesPublished,
                    categories,
                    issues,
                )
            }

            onProgress(
                OwnerArchiveImportProgress(
                    OwnerArchiveImportStage.COUNTING_ITEMS,
                    displayName,
                    bytesProcessed = preserved.bytes,
                ),
            )
            val declaredEntries = readArchiveEntries(sourceUri)
            declaredItems = declaredEntries.size
            val inventoryFile = File(workspace, "owner-approved-source-inventory.jsonl")
            var processedItems = 0
            var archiveFailure: Throwable? = null
            inventoryFile.bufferedWriter(Charsets.UTF_8).use { inventoryWriter ->
                runCatching {
                    openArchive(sourceUri).use { archive ->
                        while (true) {
                            val entry = archive.nextEntry ?: break
                            processedItems += 1
                            val normalized = safeArchivePath(entry.name)
                            if (normalized == null) {
                                failedItems += 1
                                val issue = RecoveryIssue(
                                    sourceItem = "Archive entry ${entry.name}",
                                    reason = RecoveryIssueReason.COPY_FAILED,
                                    remediation = "The entry uses an unsafe absolute or parent path. It remains preserved inside the original archive; recreate the export with relative paths before extracting it.",
                                    retryable = true,
                                )
                                issues += issue
                                inventoryWriter.append(
                                    inventoryLine(
                                        processedItems,
                                        entry.name,
                                        entry.isDirectory,
                                        ConsentCategory.APPLICATION_DATA,
                                        "PRESERVED_IN_SOURCE_ARCHIVE_REMEDIATION_REQUIRED",
                                        error = issue.remediation,
                                    ).toString(),
                                )
                                inventoryWriter.newLine()
                                archive.closeEntry()
                                publishProgress(
                                    onProgress,
                                    entry.name,
                                    processedItems,
                                    declaredItems,
                                    bytesPublished,
                                )
                                continue
                            }
                            val category = TransferClassifier.classify(normalized)
                            if (entry.isDirectory) {
                                directoryItems += 1
                                inventoryWriter.append(
                                    inventoryLine(
                                        processedItems,
                                        normalized,
                                        directory = true,
                                        category = category,
                                        status = "DIRECTORY_ACCOUNTED",
                                    ).toString(),
                                )
                                inventoryWriter.newLine()
                                archive.closeEntry()
                                publishProgress(
                                    onProgress,
                                    normalized,
                                    processedItems,
                                    declaredItems,
                                    bytesPublished,
                                )
                                continue
                            }

                            val sourceItem = ownerEntrySourceItem(deviceType, processedItems, normalized)
                            val published = runCatching {
                                publishStreamArtifact(
                                    sessionId = sessionId,
                                    peerId = peerId,
                                    source = archive,
                                    displayName = entryDisplayName(processedItems, normalized),
                                    mimeType = mimeType(normalized, category),
                                    category = category,
                                    sourceName = sourceName,
                                    sourceItem = sourceItem,
                                    sourcePackageSha256 = preserved.sha256,
                                    originalArchivePath = normalized,
                                    modifiedAtEpochMillis = entry.time.takeIf { it > 0 }
                                        ?: System.currentTimeMillis(),
                                    onBytesTransferred = { currentBytes ->
                                        onProgress(
                                            OwnerArchiveImportProgress(
                                                OwnerArchiveImportStage.RECOVERING_ITEMS,
                                                normalized,
                                                processedItems - 1,
                                                declaredItems,
                                                bytesPublished + currentBytes,
                                            ),
                                        )
                                    },
                                )
                            }
                            published.onSuccess { artifact ->
                                recoveredItems += 1
                                categories += category
                                if (artifact.wasPublished) {
                                    artifactsPublished += 1
                                    bytesPublished += artifact.bytes
                                } else {
                                    alreadyRecoveredItems += 1
                                }
                                inventoryWriter.append(
                                    inventoryLine(
                                        processedItems,
                                        normalized,
                                        directory = false,
                                        category = category,
                                        status = if (artifact.wasPublished) "RECOVERED" else "ALREADY_RECOVERED",
                                        bytes = artifact.bytes,
                                        sha256 = artifact.sha256,
                                        sensitive = category == ConsentCategory.PASSWORD_EXPORTS,
                                    ).toString(),
                                )
                                inventoryWriter.newLine()
                            }.onFailure { throwable ->
                                failedItems += 1
                                val detail = throwable.message ?: throwable.javaClass.simpleName
                                val issue = RecoveryIssue(
                                    sourceItem = normalized,
                                    reason = RecoveryIssueReason.COPY_FAILED,
                                    remediation = "The original package remains preserved. Restore destination storage access or recreate the owner export, then import again. Error: $detail",
                                    retryable = true,
                                )
                                issues += issue
                                inventoryWriter.append(
                                    inventoryLine(
                                        processedItems,
                                        normalized,
                                        directory = false,
                                        category = category,
                                        status = "PRESERVED_IN_SOURCE_ARCHIVE_REMEDIATION_REQUIRED",
                                        error = detail,
                                        sensitive = category == ConsentCategory.PASSWORD_EXPORTS,
                                    ).toString(),
                                )
                                inventoryWriter.newLine()
                            }
                            runCatching { archive.closeEntry() }
                            publishProgress(
                                onProgress,
                                normalized,
                                processedItems,
                                declaredItems,
                                bytesPublished,
                            )
                        }
                    }
                }.onFailure { throwable ->
                    archiveFailure = throwable
                }
                archiveFailure?.let { throwable ->
                    val unprocessedEntries = declaredEntries.drop(processedItems)
                    failedItems += unprocessedEntries.size
                    val detail = throwable.message ?: throwable.javaClass.simpleName
                    val issue = RecoveryIssue(
                        sourceItem = displayName,
                        reason = RecoveryIssueReason.COPY_FAILED,
                        remediation = "The complete package was preserved, but archive reading stopped after $processedItems of $declaredItems entries. Recreate or repair the ZIP and import it again. Error: $detail",
                        retryable = true,
                    )
                    issues += issue
                    unprocessedEntries.forEachIndexed { remainingIndex, entry ->
                        inventoryWriter.append(
                            inventoryLine(
                                processedItems + remainingIndex + 1,
                                entry.path,
                                directory = entry.directory,
                                category = TransferClassifier.classify(entry.path),
                                status = "PRESERVED_IN_SOURCE_ARCHIVE_REMEDIATION_REQUIRED",
                                error = issue.remediation,
                            ).toString(),
                        )
                        inventoryWriter.newLine()
                    }
                }
            }

            onProgress(
                OwnerArchiveImportProgress(
                    OwnerArchiveImportStage.WRITING_INVENTORY,
                    inventoryFile.name,
                    processedItems,
                    declaredItems,
                    bytesPublished,
                ),
            )
            val inventory = publishInventory(
                sessionId,
                peerId,
                sourceName,
                deviceType,
                inventoryFile,
                preserved.sha256,
            )
            if (inventory.wasPublished) {
                artifactsPublished += 1
                bytesPublished += inventory.bytes
            }
            val status = if (failedItems == 0 && issues.isEmpty()) SyncStatus.COMPLETED else SyncStatus.PARTIAL
            auditLog.finishSession(
                sessionId,
                status,
                artifactsPublished,
                bytesPublished,
                issues.firstOrNull()?.remediation,
            )
            onProgress(
                OwnerArchiveImportProgress(
                    OwnerArchiveImportStage.COMPLETE,
                    displayName,
                    processedItems,
                    declaredItems,
                    bytesPublished,
                ),
            )
            OwnerArchiveImportResult(
                sourcePreserved,
                declaredItems,
                recoveredItems,
                alreadyRecoveredItems,
                directoryItems,
                failedItems,
                artifactsPublished,
                bytesPublished,
                categories,
                issues,
                archiveFailure?.message,
            )
        } catch (throwable: Throwable) {
            val detail = throwable.message ?: throwable.javaClass.simpleName
            issues += RecoveryIssue(
                sourceItem = displayName,
                reason = RecoveryIssueReason.COPY_FAILED,
                remediation = "Reconnect the destination storage or recreate the owner-approved export, then import it again. Error: $detail",
                retryable = true,
            )
            if (sourcePreserved) {
                runCatching {
                    val failureInventory = File(workspace, "owner-approved-source-inventory.jsonl")
                    failureInventory.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.append(
                            inventoryLine(
                                index = 0,
                                path = displayName,
                                directory = false,
                                category = ConsentCategory.APPLICATION_DATA,
                                status = "SOURCE_PRESERVED_SAFE_EXTRACTION_BLOCKED",
                                error = detail,
                            ).toString(),
                        )
                        writer.newLine()
                    }
                    val inventory = publishInventory(
                        sessionId,
                        peerId,
                        sourceName,
                        deviceType,
                        failureInventory,
                        sourcePackageSha256,
                    )
                    if (inventory.wasPublished) {
                        artifactsPublished += 1
                        bytesPublished += inventory.bytes
                    }
                }.onFailure { inventoryFailure ->
                    issues += RecoveryIssue(
                        sourceItem = "owner-approved-source-inventory.jsonl",
                        reason = RecoveryIssueReason.COPY_FAILED,
                        remediation = "The source package remains preserved, but its failure inventory could not be published: ${inventoryFailure.message.orEmpty()}",
                        retryable = true,
                    )
                }
            }
            auditLog.finishSession(
                sessionId,
                if (sourcePreserved) SyncStatus.PARTIAL else SyncStatus.FAILED,
                artifactsPublished,
                bytesPublished,
                detail,
            )
            OwnerArchiveImportResult(
                sourcePreserved,
                declaredItems,
                recoveredItems,
                alreadyRecoveredItems,
                directoryItems,
                failedItems + 1,
                artifactsPublished,
                bytesPublished,
                categories,
                issues,
                detail,
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun readArchiveEntries(sourceUri: Uri): List<DeclaredArchiveEntry> {
        openArchive(sourceUri).use { archive ->
            return buildList {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    add(DeclaredArchiveEntry(entry.name, entry.isDirectory))
                    archive.closeEntry()
                }
            }
        }
    }

    private fun publishInventory(
        sessionId: Long,
        peerId: String,
        sourceName: String,
        deviceType: RecoveryDeviceType,
        inventoryFile: File,
        sourcePackageSha256: String,
    ): PublishedOwnerArtifact {
        return publishProviderArtifact(
            sessionId = sessionId,
            peerId = peerId,
            sourceUri = Uri.fromFile(inventoryFile),
            displayName = inventoryFile.name,
            mimeType = "application/x-ndjson",
            category = ConsentCategory.SYSTEM_INFORMATION,
            sourceName = sourceName,
            sourceItem = "/Owner-approved ${deviceType.name.lowercase()} source/owner-approved-source-inventory.jsonl",
            fingerprintMaterial = sourcePackageSha256,
        )
    }

    private fun publishProviderArtifact(
        sessionId: Long,
        peerId: String,
        sourceUri: Uri,
        displayName: String,
        mimeType: String?,
        category: ConsentCategory,
        sourceName: String,
        sourceItem: String,
        fingerprintMaterial: String = sourceItem,
    ): PublishedOwnerArtifact {
        val imported = targetMediaStore.copyFromProvider(
            sourceUri,
            displayName,
            mimeType,
            category,
            sourceName,
            System.currentTimeMillis(),
        )
        return registerImportedArtifact(
            sessionId,
            peerId,
            category,
            sourceItem,
            imported.uri,
            imported.bytesWritten,
            imported.sha256,
            "$fingerprintMaterial|${imported.sha256}",
        )
    }

    private fun publishStreamArtifact(
        sessionId: Long,
        peerId: String,
        source: ZipInputStream,
        displayName: String,
        mimeType: String?,
        category: ConsentCategory,
        sourceName: String,
        sourceItem: String,
        sourcePackageSha256: String,
        originalArchivePath: String,
        modifiedAtEpochMillis: Long,
        onBytesTransferred: (Long) -> Unit,
    ): PublishedOwnerArtifact {
        val imported = targetMediaStore.copyFromStream(
            source,
            displayName,
            mimeType,
            category,
            sourceName,
            modifiedAtEpochMillis,
            onBytesTransferred,
        )
        return registerImportedArtifact(
            sessionId,
            peerId,
            category,
            sourceItem,
            imported.uri,
            imported.bytesWritten,
            imported.sha256,
            "$sourcePackageSha256|$originalArchivePath|${imported.sha256}",
        )
    }

    private fun registerImportedArtifact(
        sessionId: Long,
        peerId: String,
        category: ConsentCategory,
        sourceItem: String,
        importedUri: Uri,
        importedBytes: Long,
        importedSha256: String,
        fingerprintMaterial: String,
    ): PublishedOwnerArtifact {
        val fingerprint = DeviceIdentity.sha256("$peerId|owner-approved|$fingerprintMaterial")
        val existing = auditLog.completedTransferByContent(peerId, importedSha256, category)
        val existingIntegrity = existing?.let {
            targetMediaStore.verifyStoredItem(it.destination, it.bytesTransferred, importedSha256)
        }
        if (existing != null && existingIntegrity != null) {
            targetMediaStore.discardStoredItem(importedUri)
            auditLog.recordTransferAlias(peerId, fingerprint, existing.id)
            return PublishedOwnerArtifact(existingIntegrity.bytes, existingIntegrity.sha256, wasPublished = false)
        }
        auditLog.recordTransfer(
            sessionId = sessionId,
            peerId = peerId,
            sourceFingerprint = fingerprint,
            category = category,
            sourceItem = sourceItem,
            destination = importedUri.toString(),
            bytesTransferred = importedBytes,
            status = TransferStatus.COMPLETED,
            sourceSize = importedBytes,
            sourceModifiedAtEpochMillis = System.currentTimeMillis(),
            contentSha256 = importedSha256,
        )
        return PublishedOwnerArtifact(importedBytes, importedSha256, wasPublished = true)
    }

    private fun inventoryLine(
        index: Int,
        path: String,
        directory: Boolean,
        category: ConsentCategory,
        status: String,
        bytes: Long? = null,
        sha256: String? = null,
        error: String? = null,
        sensitive: Boolean = false,
    ): JSONObject = JSONObject()
        .put("index", index)
        .put("originalPath", path)
        .put("directory", directory)
        .put("category", category.name)
        .put("status", status)
        .put("bytes", bytes ?: JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
        .put("sensitive", sensitive)
        .put(
            "handling",
            if (sensitive) "COPIED_OPAQUE_NO_DECRYPTION" else "RECOVERED_WITH_INTEGRITY_VERIFICATION",
        )

    private fun publishProgress(
        onProgress: (OwnerArchiveImportProgress) -> Unit,
        currentItem: String,
        completedItems: Int,
        totalItems: Int,
        bytesProcessed: Long,
    ) {
        onProgress(
            OwnerArchiveImportProgress(
                OwnerArchiveImportStage.RECOVERING_ITEMS,
                currentItem,
                completedItems,
                totalItems,
                bytesProcessed,
            ),
        )
    }

    private fun safeArchivePath(value: String): String? {
        if (value.startsWith('/') || value.startsWith('\\')) return null
        val normalized = value.replace('\\', '/').trim('/')
        val segments = normalized.split('/')
        if (normalized.isBlank() || segments.any { it == "." || it == ".." }) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        if (normalized.any(Char::isISOControl)) return null
        return normalized
    }

    private fun ownerEntrySourceItem(
        deviceType: RecoveryDeviceType,
        index: Int,
        originalPath: String,
    ): String {
        val baseName = originalPath.substringAfterLast('/').ifBlank { "archive-item" }
            .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .take(120)
        return "/Owner-approved ${deviceType.name.lowercase()} source/entries/" +
            String.format(Locale.US, "%06d", index) + "/$baseName"
    }

    private fun entryDisplayName(index: Int, originalPath: String): String {
        val baseName = originalPath.substringAfterLast('/').ifBlank { "archive-item" }
            .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .take(100)
        return String.format(Locale.US, "%06d-%s", index, baseName)
    }

    private fun ownerSourceName(displayName: String): String {
        return "owner-approved-" + displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
            .ifBlank { "source-export" }
    }

    private fun sourceDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "owner-approved-source-export"
    }

    private fun mimeType(path: String, category: ConsentCategory): String {
        if (category == ConsentCategory.CONTACTS) return "text/vcard"
        if (category == ConsentCategory.PASSWORD_EXPORTS) return "application/octet-stream"
        val extension = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun openArchive(uri: Uri): ZipInputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Android could not open the selected owner-approved ZIP.")
        return ZipInputStream(input.buffered())
    }

    private fun isZipSource(uri: Uri, displayName: String, mimeType: String?): Boolean {
        if (displayName.lowercase().endsWith(".zip") || mimeType in ZIP_MIME_TYPES) return true
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@use false
                val signature = ByteArray(4)
                if (input.read(signature) != signature.size) return@use false
                signature[0] == 'P'.code.toByte() &&
                    signature[1] == 'K'.code.toByte() &&
                    signature[2] in setOf(3, 5, 7).map(Int::toByte) &&
                    signature[3] in setOf(4, 6, 8).map(Int::toByte)
            }
        }.getOrDefault(false)
    }

    private data class PublishedOwnerArtifact(
        val bytes: Long,
        val sha256: String,
        val wasPublished: Boolean,
    )

    private data class DeclaredArchiveEntry(
        val path: String,
        val directory: Boolean,
    )

    private companion object {
        val ZIP_MIME_TYPES = setOf(
            "application/zip",
            "application/x-zip-compressed",
        )
    }
}