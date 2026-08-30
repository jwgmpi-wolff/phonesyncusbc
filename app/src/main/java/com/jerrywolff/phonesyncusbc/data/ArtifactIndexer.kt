package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ArtifactIndexProgress(
    val processedArtifacts: Int,
    val totalArtifacts: Int,
    val currentArtifact: String,
    val recordsIndexed: Int,
    val fieldsIndexed: Int,
)

data class ArtifactIndexResult(
    val indexedArtifacts: Int,
    val parsedArtifacts: Int,
    val skippedSensitiveArtifacts: Int,
    val failedArtifacts: Int,
    val recordsIndexed: Int,
    val fieldsIndexed: Int,
    val firstError: String? = null,
)

class ArtifactIndexer(
    private val context: Context,
    private val database: ArtifactIndexDatabase,
) {
    fun rebuild(
        entries: List<AuditEntry>,
        sourceId: String,
        sourceName: String,
        onProgress: (ArtifactIndexProgress) -> Unit = {},
    ): ArtifactIndexResult {
        val sourceEntries = externalDeviceRecoveryEntries(entries, sourceId)
        return database.replaceSourceArtifacts(sourceId, sourceName) { sourceWriter ->
            var indexedArtifacts = 0
            var parsedArtifacts = 0
            var sensitiveArtifacts = 0
            var failedArtifacts = 0
            var recordsIndexed = 0
            var fieldsIndexed = 0
            var firstError: String? = null

            sourceEntries.forEachIndexed { index, entry ->
                val displaySourceItem = entry.displaySourceItem()
                val currentName = displaySourceItem.substringAfterLast('/').ifBlank { "artifact-${entry.id}" }
                onProgress(
                    ArtifactIndexProgress(
                        index,
                        sourceEntries.size,
                        currentName,
                        recordsIndexed,
                        fieldsIndexed,
                    ),
                )
                val metadata = entry.toIndexMetadata(sourceId, sourceName)
                val outcome = when {
                    entry.category == ConsentCategory.PASSWORD_EXPORTS -> {
                        sensitiveArtifacts += 1
                        sourceWriter.replaceArtifact(
                            metadata,
                            statusWhenNotParsed = ArtifactParseStatus.SKIPPED_SENSITIVE,
                        )
                    }
                    !entry.canContainJson() -> indexRecoveredFile(entry, metadata, sourceWriter)
                    else -> runCatching { indexJsonArtifact(entry, metadata, sourceWriter) }
                        .getOrElse { throwable ->
                            failedArtifacts += 1
                            val error = throwable.message ?: throwable.javaClass.simpleName
                            firstError = firstError ?: error
                            sourceWriter.markArtifactError(metadata, error)
                        }
                }
                indexedArtifacts += 1
                if (outcome.status == ArtifactParseStatus.PARSED) parsedArtifacts += 1
                recordsIndexed += outcome.recordCount
                fieldsIndexed += outcome.fieldCount
                onProgress(
                    ArtifactIndexProgress(
                        index + 1,
                        sourceEntries.size,
                        currentName,
                        recordsIndexed,
                        fieldsIndexed,
                    ),
                )
            }
            ArtifactIndexResult(
                indexedArtifacts,
                parsedArtifacts,
                sensitiveArtifacts,
                failedArtifacts,
                recordsIndexed,
                fieldsIndexed,
                firstError,
            )
        }
    }

    private fun indexJsonArtifact(
        entry: AuditEntry,
        metadata: ArtifactIndexMetadata,
        sourceWriter: ArtifactIndexDatabase.SourceArtifactWriter,
    ): ArtifactParseOutcome {
        val destination = entry.destination?.let(Uri::parse)
            ?: error("${entry.displaySourceItem()} has no readable destination URI.")
        return sourceWriter.replaceArtifact(metadata) { writer ->
            context.contentResolver.openInputStream(destination).use { input ->
                checkNotNull(input) { "Android could not open ${entry.displaySourceItem()}." }
                when (entry.extension()) {
                    "zip" -> parseZip(input, entry, writer)
                    "jsonl", "ndjson" -> parseJsonLines(input, entry.displaySourceItem(), entry.category, writer)
                    else -> parseJson(input, entry.displaySourceItem(), entry.category, writer)
                }
            }
        }
    }

    private fun indexRecoveredFile(
        entry: AuditEntry,
        metadata: ArtifactIndexMetadata,
        sourceWriter: ArtifactIndexDatabase.SourceArtifactWriter,
    ): ArtifactParseOutcome {
        val displaySourceItem = entry.displaySourceItem()
        return sourceWriter.replaceArtifact(metadata) { writer ->
            val modified = entry.sourceModifiedAtEpochMillis.takeIf { it > 0 }
                ?.let { Instant.ofEpochMilli(it).toString() }
            val fields = buildList {
                add(FlattenedJsonField("file.name", "name", FlattenedValueType.STRING, displaySourceItem.substringAfterLast('/')))
                add(FlattenedJsonField("file.path", "path", FlattenedValueType.STRING, displaySourceItem))
                add(FlattenedJsonField("file.mimeType", "mimeType", FlattenedValueType.STRING, metadata.mimeType))
                add(FlattenedJsonField("file.bytes", "bytes", FlattenedValueType.NUMBER, entry.bytesTransferred.toString()))
                entry.contentSha256?.let { add(FlattenedJsonField("file.sha256", "sha256", FlattenedValueType.STRING, it)) }
                modified?.let { add(FlattenedJsonField("file.modifiedUtc", "modifiedUtc", FlattenedValueType.STRING, it)) }
            }
            writer.insert(
                jsonSource = displaySourceItem,
                category = entry.category,
                record = FlattenedJsonRecord(
                    recordIndex = 0,
                    recordType = recoveredFileType(entry),
                    recordKind = recordKindForArchiveEntry(entry.category),
                    title = displaySourceItem.substringAfterLast('/').ifBlank { "Recovered file" },
                    summary = "${formatBytes(entry.bytesTransferred)} · $displaySourceItem",
                    timestamp = modified,
                    fields = fields,
                ),
            )
            ArtifactParseOutcome(ArtifactParseStatus.NO_JSON)
        }
    }

    private fun recoveredFileType(entry: AuditEntry): String = when {
        entry.category == ConsentCategory.VOICEMAIL_EXPORTS -> "Voicemail"
        entry.category == ConsentCategory.SMS_EXPORTS -> "SMS export"
        entry.category == ConsentCategory.PHOTOS_AND_VIDEOS -> "Recovered media"
        else -> "Recovered file"
    }

    private fun parseZip(
        input: InputStream,
        entry: AuditEntry,
        writer: ArtifactIndexDatabase.ArtifactRecordWriter,
    ): ArtifactParseOutcome {
        var records = 0
        var fields = 0
        var jsonEntries = 0
        val displaySourceItem = entry.displaySourceItem()
        val detectedArchiveCategory = TransferClassifier.classify(displaySourceItem)
        val archiveCategory = if (
            entry.category == ConsentCategory.SMS_EXPORTS || detectedArchiveCategory == ConsentCategory.SMS_EXPORTS
        ) {
            ConsentCategory.SMS_EXPORTS
        } else {
            entry.category
        }
        ZipInputStream(input.buffered()).use { archive ->
            while (true) {
                val zipEntry = archive.nextEntry ?: break
                if (!zipEntry.isDirectory) {
                    val normalizedEntryPath = zipEntry.name.replace('\\', '/').trimStart('/').lowercase()
                    if (normalizedEntryPath in ARCHIVE_METADATA_PATHS) {
                        archive.closeEntry()
                        continue
                    }
                    val virtualPath = "$displaySourceItem/${zipEntry.name}"
                    val detectedNestedCategory = TransferClassifier.classify(virtualPath)
                    val standaloneCategory = TransferClassifier.classify(zipEntry.name)
                    if (
                        detectedNestedCategory == ConsentCategory.PASSWORD_EXPORTS ||
                        standaloneCategory == ConsentCategory.PASSWORD_EXPORTS
                    ) {
                        archive.closeEntry()
                        continue
                    }
                    val nestedCategory = if (archiveCategory == ConsentCategory.SMS_EXPORTS) {
                        ConsentCategory.SMS_EXPORTS
                    } else {
                        detectedNestedCategory
                    }
                    if (zipEntry.name.isJsonFile()) {
                        jsonEntries += 1
                        val outcome = if (zipEntry.name.extension() in setOf("jsonl", "ndjson")) {
                            parseJsonLines(archive, zipEntry.name, nestedCategory, writer)
                        } else {
                            parseJson(archive, zipEntry.name, nestedCategory, writer)
                        }
                        records += outcome.recordCount
                        fields += outcome.fieldCount
                    } else {
                        val record = readArchiveEntryRecord(
                            input = archive,
                            zipEntry = zipEntry,
                            containerPath = displaySourceItem,
                            detectedCategory = standaloneCategory,
                            recordIndex = records,
                        )
                        writer.insert(zipEntry.name, nestedCategory, record)
                        records += 1
                        fields += record.fields.size
                    }
                }
                archive.closeEntry()
            }
        }
        return if (jsonEntries == 0) {
            ArtifactParseOutcome(ArtifactParseStatus.NO_JSON)
        } else {
            ArtifactParseOutcome(ArtifactParseStatus.PARSED, records, fields)
        }
    }

    private fun readArchiveEntryRecord(
        input: InputStream,
        zipEntry: ZipEntry,
        containerPath: String,
        detectedCategory: ConsentCategory,
        recordIndex: Int,
    ): FlattenedJsonRecord {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            bytes += read
        }
        val modifiedEpochMillis = zipEntry.time.takeIf { it >= 0 }
        val modified = modifiedEpochMillis?.let { Instant.ofEpochMilli(it).toString() }
        val contentSha256 = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val fields = listOf(
            FlattenedJsonField("archive.container", "container", FlattenedValueType.STRING, containerPath),
            FlattenedJsonField("archive.entry", "entry", FlattenedValueType.STRING, zipEntry.name),
            FlattenedJsonField("archive.extension", "extension", FlattenedValueType.STRING, zipEntry.name.extension()),
            FlattenedJsonField("archive.bytes", "bytes", FlattenedValueType.NUMBER, bytes.toString()),
            FlattenedJsonField("archive.compressedBytes", "compressedBytes", FlattenedValueType.NUMBER, zipEntry.compressedSize.toString()),
            FlattenedJsonField("archive.crc32", "crc32", FlattenedValueType.NUMBER, zipEntry.crc.toString()),
            FlattenedJsonField("archive.sha256", "sha256", FlattenedValueType.STRING, contentSha256),
            FlattenedJsonField("archive.modifiedUtc", "modifiedUtc", FlattenedValueType.STRING, modified.orEmpty()),
        )
        return FlattenedJsonRecord(
            recordIndex = recordIndex,
            recordType = "Archive item",
            recordKind = recordKindForArchiveEntry(detectedCategory),
            title = zipEntry.name.substringAfterLast('/').ifBlank { zipEntry.name },
            summary = "${formatBytes(bytes)} · $containerPath!/${zipEntry.name}",
            timestamp = modified,
            fields = fields,
        )
    }

    private fun recordKindForArchiveEntry(category: ConsentCategory): ParsedRecordKind = when (category) {
        ConsentCategory.SMS_EXPORTS, ConsentCategory.CHAT_EXPORTS, ConsentCategory.VOICEMAIL_EXPORTS -> ParsedRecordKind.MESSAGE
        ConsentCategory.EMAIL_EXPORTS -> ParsedRecordKind.EMAIL
        ConsentCategory.CONTACTS -> ParsedRecordKind.CONTACT
        ConsentCategory.CALL_LOGS -> ParsedRecordKind.CALL
        ConsentCategory.CALENDAR -> ParsedRecordKind.EVENT
        ConsentCategory.NOTIFICATION_EXPORTS -> ParsedRecordKind.NOTIFICATION
        ConsentCategory.PHOTOS_AND_VIDEOS -> ParsedRecordKind.MEDIA
        ConsentCategory.SYSTEM_INFORMATION -> ParsedRecordKind.SYSTEM
        ConsentCategory.APPLICATION_DATA -> ParsedRecordKind.APPLICATION
        ConsentCategory.CONFIGURATION -> ParsedRecordKind.CONFIGURATION
        ConsentCategory.LOGS -> ParsedRecordKind.LOG
        ConsentCategory.DOCUMENTS, ConsentCategory.SELECTED_FOLDERS -> ParsedRecordKind.DOCUMENT
        ConsentCategory.CLOUD_ACCOUNTS -> ParsedRecordKind.GENERIC
        ConsentCategory.PASSWORD_EXPORTS -> error("Password archive entries cannot be indexed.")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        bytes < 1_073_741_824 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    }

    private fun parseJson(
        input: InputStream,
        jsonSource: String,
        category: ConsentCategory,
        writer: ArtifactIndexDatabase.ArtifactRecordWriter,
    ): ArtifactParseOutcome {
        val sourceType = deriveFolderMetadata("", category, jsonSource).recordLabel
        val summary = JsonArtifactFlattener.flatten(
            InputStreamReader(input, Charsets.UTF_8),
            category,
            sourceType,
        ) { record -> writer.insert(jsonSource, category, record) }
        return ArtifactParseOutcome(
            status = if (summary.recordCount > 0) ArtifactParseStatus.PARSED else ArtifactParseStatus.NO_JSON,
            recordCount = summary.recordCount,
            fieldCount = summary.fieldCount,
        )
    }

    private fun parseJsonLines(
        input: InputStream,
        jsonSource: String,
        category: ConsentCategory,
        writer: ArtifactIndexDatabase.ArtifactRecordWriter,
    ): ArtifactParseOutcome {
        var records = 0
        var fields = 0
        val sourceType = deriveFolderMetadata("", category, jsonSource).recordLabel
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) {
                val summary = JsonArtifactFlattener.flatten(line.reader(), category, sourceType) { record ->
                    writer.insert(jsonSource, category, record.copy(recordIndex = records + record.recordIndex))
                }
                records += summary.recordCount
                fields += summary.fieldCount
            }
        }
        return ArtifactParseOutcome(
            status = if (records > 0) ArtifactParseStatus.PARSED else ArtifactParseStatus.NO_JSON,
            recordCount = records,
            fieldCount = fields,
        )
    }

    private fun AuditEntry.toIndexMetadata(sourceId: String, sourceName: String): ArtifactIndexMetadata {
        val displaySourceItem = displaySourceItem()
        return ArtifactIndexMetadata(
            transferId = id,
            sourceId = sourceId,
            sourceName = sourceName,
            category = category,
            sourcePath = displaySourceItem,
            destinationUri = destination,
            mimeType = DataExportManager(context).mimeType(this),
            bytes = bytesTransferred,
            sha256 = contentSha256,
            folderMetadata = deriveFolderMetadata(displaySourceItem, category),
        )
    }

    private fun AuditEntry.displaySourceItem(): String = Uri.decode(sourceItem)

    private fun AuditEntry.canContainJson(): Boolean {
        return extension() in JSON_EXTENSIONS || sourceItem.lowercase(Locale.US).endsWith(".json.zip")
    }

    private fun AuditEntry.extension(): String = sourceItem.extension()

    private fun String.isJsonFile(): Boolean = extension() in JSON_EXTENSIONS - "zip"

    private fun String.extension(): String = substringAfterLast('.', "").lowercase(Locale.US)

    private companion object {
        val JSON_EXTENSIONS = setOf("json", "jsonl", "ndjson", "zip")
    }
}

private val ARCHIVE_METADATA_PATHS = setOf(
    "backup-manifest.json",
    "recoverbybackup-manifest.json",
)