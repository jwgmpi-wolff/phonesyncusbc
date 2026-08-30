package com.jerrywolff.phonesyncusbc.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ArtifactParseStatus {
    INDEXING,
    PARSED,
    NO_JSON,
    SKIPPED_SENSITIVE,
    ERROR,
}

enum class ArtifactFocus(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    MESSAGES("Messages"),
    SMS("SMS"),
    VOICEMAILS("Voicemails"),
}

data class IndexedSource(
    val sourceId: String,
    val displayName: String,
    val artifactCount: Int,
    val recordCount: Int,
)

data class IndexedRecordSummary(
    val id: Long,
    val artifactId: Long,
    val sourceId: String,
    val sourceName: String,
    val sourcePath: String,
    val destinationUri: String?,
    val jsonSource: String,
    val folderLabel: String,
    val collectionLabel: String,
    val recordLabel: String,
    val category: ConsentCategory,
    val recordType: String,
    val recordKind: ParsedRecordKind,
    val recordIndex: Int,
    val title: String,
    val summary: String,
    val timestamp: String?,
)

data class IndexedRecordDetail(
    val record: IndexedRecordSummary,
    val fields: List<FlattenedJsonField>,
)

data class ArtifactIndexStats(
    val sourceCount: Int,
    val artifactCount: Int,
    val parsedArtifactCount: Int,
    val recordCount: Int,
    val fieldCount: Int,
)

data class ArtifactIndexExportResult(
    val uri: Uri? = null,
    val displayName: String? = null,
    val bytes: Long = 0,
    val error: String? = null,
)

internal data class ArtifactIndexMetadata(
    val transferId: Long,
    val sourceId: String,
    val sourceName: String,
    val category: ConsentCategory,
    val sourcePath: String,
    val destinationUri: String?,
    val mimeType: String,
    val bytes: Long,
    val sha256: String?,
    val folderMetadata: FolderMetadata,
)

internal data class ArtifactParseOutcome(
    val status: ArtifactParseStatus,
    val recordCount: Int = 0,
    val fieldCount: Int = 0,
    val error: String? = null,
)

class ArtifactIndexDatabase(
    private val context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sources (
                source_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE artifacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transfer_id INTEGER NOT NULL UNIQUE,
                source_id TEXT NOT NULL,
                category TEXT NOT NULL,
                source_path TEXT NOT NULL,
                folder_path TEXT NOT NULL,
                folder_label TEXT NOT NULL,
                collection_label TEXT NOT NULL,
                destination_uri TEXT,
                mime_type TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                sha256 TEXT,
                indexed_at INTEGER NOT NULL,
                parse_status TEXT NOT NULL,
                record_count INTEGER NOT NULL DEFAULT 0,
                field_count INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                FOREIGN KEY(source_id) REFERENCES sources(source_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                artifact_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                category TEXT NOT NULL,
                json_source TEXT NOT NULL,
                folder_label TEXT NOT NULL,
                collection_label TEXT NOT NULL,
                record_label TEXT NOT NULL,
                record_type TEXT NOT NULL,
                record_kind TEXT NOT NULL,
                record_index INTEGER NOT NULL,
                record_hash TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                timestamp_text TEXT,
                search_text TEXT NOT NULL,
                FOREIGN KEY(artifact_id) REFERENCES artifacts(id) ON DELETE CASCADE,
                FOREIGN KEY(source_id) REFERENCES sources(source_id) ON DELETE CASCADE,
                UNIQUE(source_id, category, record_kind, collection_label, record_hash)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE fields (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_id INTEGER NOT NULL,
                field_path TEXT NOT NULL,
                field_name TEXT NOT NULL,
                value_type TEXT NOT NULL,
                text_value TEXT NOT NULL,
                FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX artifacts_source_category ON artifacts(source_id, category, parse_status)")
        database.execSQL("CREATE INDEX records_source_kind ON records(source_id, record_kind, collection_label, record_type)")
        database.execSQL("CREATE INDEX records_artifact_order ON records(artifact_id, json_source, record_index)")
        database.execSQL("CREATE INDEX fields_record_path ON fields(record_id, field_path)")
        database.execSQL("CREATE INDEX fields_name_value ON fields(field_name, text_value)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            database.execSQL("DROP TABLE IF EXISTS fields")
            database.execSQL("DROP TABLE IF EXISTS records")
            database.execSQL("DROP TABLE IF EXISTS artifacts")
            database.execSQL("DROP TABLE IF EXISTS sources")
            onCreate(database)
        }
    }

    @Synchronized
    internal fun replaceArtifact(
        metadata: ArtifactIndexMetadata,
        statusWhenNotParsed: ArtifactParseStatus = ArtifactParseStatus.NO_JSON,
        parse: ((ArtifactRecordWriter) -> ArtifactParseOutcome)? = null,
    ): ArtifactParseOutcome {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            upsertSource(database, metadata.sourceId, metadata.sourceName)
            val outcome = writeArtifact(database, metadata, statusWhenNotParsed, parse)
            database.setTransactionSuccessful()
            outcome
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    internal fun <T> replaceSourceArtifacts(
        sourceId: String,
        sourceName: String,
        rebuild: (SourceArtifactWriter) -> T,
    ): T {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            upsertSource(database, sourceId, sourceName)
            database.delete("artifacts", "source_id = ?", arrayOf(sourceId))
            val result = rebuild(SourceArtifactWriter(database, sourceId))
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    internal fun markArtifactError(metadata: ArtifactIndexMetadata, error: String): ArtifactParseOutcome {
        return replaceArtifact(
            metadata = metadata,
            parse = { ArtifactParseOutcome(ArtifactParseStatus.ERROR, error = error) },
        )
    }

    private fun writeArtifact(
        database: SQLiteDatabase,
        metadata: ArtifactIndexMetadata,
        statusWhenNotParsed: ArtifactParseStatus,
        parse: ((ArtifactRecordWriter) -> ArtifactParseOutcome)?,
    ): ArtifactParseOutcome {
        database.delete("artifacts", "transfer_id = ?", arrayOf(metadata.transferId.toString()))
        val artifactId = database.insertOrThrow(
            "artifacts",
            null,
            ContentValues().apply {
                put("transfer_id", metadata.transferId)
                put("source_id", metadata.sourceId)
                put("category", metadata.category.name)
                put("source_path", metadata.sourcePath)
                put("folder_path", metadata.folderMetadata.folderPath)
                put("folder_label", metadata.folderMetadata.folderLabel)
                put("collection_label", metadata.folderMetadata.collectionLabel)
                put("destination_uri", metadata.destinationUri)
                put("mime_type", metadata.mimeType)
                put("bytes", metadata.bytes)
                put("sha256", metadata.sha256)
                put("indexed_at", System.currentTimeMillis())
                put("parse_status", ArtifactParseStatus.INDEXING.name)
            },
        )
        val writer = ArtifactRecordWriter(database, artifactId, metadata)
        val parsedOutcome = parse?.invoke(writer)
        val outcome = parsedOutcome?.copy(
            recordCount = writer.recordsInserted,
            fieldCount = writer.fieldsInserted,
        ) ?: ArtifactParseOutcome(statusWhenNotParsed)
        database.update(
            "artifacts",
            ContentValues().apply {
                put("parse_status", outcome.status.name)
                put("record_count", outcome.recordCount)
                put("field_count", outcome.fieldCount)
                put("error", outcome.error)
            },
            "id = ?",
            arrayOf(artifactId.toString()),
        )
        return outcome
    }

    @Synchronized
    fun removeMissingArtifacts(sourceId: String, transferIds: Set<Long>) {
        val database = writableDatabase
        database.query(
            "artifacts",
            arrayOf("transfer_id"),
            "source_id = ?",
            arrayOf(sourceId),
            null,
            null,
            null,
        ).use { cursor ->
            val staleIds = buildList {
                while (cursor.moveToNext()) {
                    cursor.getLong(0).takeIf { it !in transferIds }?.let(::add)
                }
            }
            staleIds.forEach { transferId ->
                database.delete("artifacts", "transfer_id = ?", arrayOf(transferId.toString()))
            }
        }
    }

    fun stats(): ArtifactIndexStats {
        val database = readableDatabase
        return ArtifactIndexStats(
            sourceCount = scalarCount(database, "SELECT COUNT(*) FROM sources"),
            artifactCount = scalarCount(database, "SELECT COUNT(*) FROM artifacts"),
            parsedArtifactCount = scalarCount(
                database,
                "SELECT COUNT(*) FROM artifacts WHERE parse_status = ?",
                arrayOf(ArtifactParseStatus.PARSED.name),
            ),
            recordCount = scalarCount(database, "SELECT COUNT(*) FROM records"),
            fieldCount = scalarCount(database, "SELECT COUNT(*) FROM fields"),
        )
    }

    fun sources(): List<IndexedSource> {
        readableDatabase.rawQuery(
            """
            SELECT s.source_id, s.display_name, COUNT(DISTINCT a.id), COUNT(r.id)
            FROM sources s
            LEFT JOIN artifacts a ON a.source_id = s.source_id
            LEFT JOIN records r ON r.artifact_id = a.id
            GROUP BY s.source_id, s.display_name
            ORDER BY s.display_name COLLATE NOCASE
            """.trimIndent(),
            null,
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(IndexedSource(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getInt(3)))
                }
            }
        }
    }

    @Synchronized
    fun retainOnlySource(sourceId: String) {
        writableDatabase.delete("sources", "source_id != ?", arrayOf(sourceId))
    }

    fun sourceTransferIds(sourceId: String): Set<Long> {
        readableDatabase.query(
            "artifacts",
            arrayOf("transfer_id"),
            "source_id = ?",
            arrayOf(sourceId),
            null,
            null,
            null,
        ).use { cursor ->
            return buildSet {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
    }

    fun recordKinds(sourceId: String? = null): List<ParsedRecordKind> {
        val selection = sourceId?.let { " WHERE source_id = ?" }.orEmpty()
        readableDatabase.rawQuery(
            "SELECT DISTINCT record_kind FROM records$selection ORDER BY record_kind",
            sourceId?.let { arrayOf(it) },
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(ParsedRecordKind.valueOf(cursor.getString(0)))
            }
        }
    }

    fun queryRecords(
        search: String = "",
        sourceId: String? = null,
        category: ConsentCategory? = null,
        recordKind: ParsedRecordKind? = null,
        focus: ArtifactFocus = ArtifactFocus.ALL,
        recordIds: Set<Long>? = null,
        limit: Int = 250,
        offset: Int = 0,
    ): List<IndexedRecordSummary> {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        sourceId?.let {
            conditions += "r.source_id = ?"
            arguments += it
        }
        category?.let {
            conditions += "r.category = ?"
            arguments += it.name
        }
        recordKind?.let {
            conditions += "r.record_kind = ?"
            arguments += it.name
        }
        when (focus) {
            ArtifactFocus.ALL -> Unit
            ArtifactFocus.IMAGES -> {
                conditions += IMAGE_EXTENSIONS.joinToString(" OR ", prefix = "(", postfix = ")") {
                    "LOWER(r.json_source) LIKE ?"
                }
                arguments += IMAGE_EXTENSIONS.map { "%.${it}" }
            }
            ArtifactFocus.MESSAGES -> {
                conditions += "r.record_kind = ? AND r.category NOT IN (?, ?)"
                arguments += ParsedRecordKind.MESSAGE.name
                arguments += ConsentCategory.SMS_EXPORTS.name
                arguments += ConsentCategory.VOICEMAIL_EXPORTS.name
            }
            ArtifactFocus.SMS -> {
                conditions += "r.category = ?"
                arguments += ConsentCategory.SMS_EXPORTS.name
            }
            ArtifactFocus.VOICEMAILS -> {
                conditions += "r.category = ?"
                arguments += ConsentCategory.VOICEMAIL_EXPORTS.name
            }
        }
        recordIds?.let { ids ->
            val boundedIds = ids.take(MAX_SELECTED_RECORDS)
            if (boundedIds.isEmpty()) {
                conditions += "1 = 0"
            } else {
                conditions += boundedIds.joinToString(",", prefix = "r.id IN (", postfix = ")") { "?" }
                arguments += boundedIds.map(Long::toString)
            }
        }
        search.trim().takeIf(String::isNotEmpty)?.let { query ->
            conditions += "(r.search_text LIKE ? ESCAPE '\\' OR EXISTS (" +
                "SELECT 1 FROM fields f WHERE f.record_id = r.id AND f.text_value LIKE ? ESCAPE '\\'))"
            val pattern = "%${escapeLike(query)}%"
            arguments += pattern
            arguments += pattern
        }
        val where = conditions.takeIf(List<String>::isNotEmpty)?.joinToString(" AND ", prefix = "WHERE ").orEmpty()
        val sql = """
            SELECT r.id, r.artifact_id, r.source_id, s.display_name, a.source_path, a.destination_uri,
                   r.json_source, r.folder_label, r.collection_label, r.record_label,
                   r.category, r.record_type, r.record_kind, r.record_index,
                   r.title, r.summary, r.timestamp_text
            FROM records r
            JOIN artifacts a ON a.id = r.artifact_id
            JOIN sources s ON s.source_id = r.source_id
            $where
            ORDER BY COALESCE(r.timestamp_text, '') DESC, r.id DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        arguments += limit.coerceIn(1, 1_000).toString()
        arguments += offset.coerceAtLeast(0).toString()
        readableDatabase.rawQuery(sql, arguments.toTypedArray()).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toRecordSummary())
            }
        }
    }

    fun recordDetail(recordId: Long, sourceId: String): IndexedRecordDetail? {
        val record = readableDatabase.rawQuery(
            """
            SELECT r.id, r.artifact_id, r.source_id, s.display_name, a.source_path, a.destination_uri,
                   r.json_source, r.folder_label, r.collection_label, r.record_label,
                   r.category, r.record_type, r.record_kind, r.record_index,
                   r.title, r.summary, r.timestamp_text
            FROM records r
            JOIN artifacts a ON a.id = r.artifact_id
            JOIN sources s ON s.source_id = r.source_id
            WHERE r.id = ? AND r.source_id = ?
            """.trimIndent(),
            arrayOf(recordId.toString(), sourceId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecordSummary() else null } ?: return null
        val fields = readableDatabase.query(
            "fields",
            arrayOf("field_path", "field_name", "value_type", "text_value"),
            "record_id = ?",
            arrayOf(recordId.toString()),
            null,
            null,
            "field_path COLLATE NOCASE",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        FlattenedJsonField(
                            path = cursor.getString(0),
                            name = cursor.getString(1),
                            valueType = FlattenedValueType.valueOf(cursor.getString(2)),
                            value = cursor.getString(3),
                        ),
                    )
                }
            }
        }
        return IndexedRecordDetail(record, fields)
    }

    @Synchronized
    fun exportSnapshot(): ArtifactIndexExportResult {
        val displayName = "PhoneSyncArtifactIndex-${timestamp()}.sqlite"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.sqlite3")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Phone Sync/Data Reader",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ArtifactIndexExportResult(error = "Android could not create the database export.")
        return try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            var copied = 0L
            databaseFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(destination, "w").use { output ->
                    checkNotNull(output) { "Android could not open the database export." }
                    copied = input.copyTo(output)
                }
            }
            context.contentResolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            ArtifactIndexExportResult(destination, displayName, copied)
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            ArtifactIndexExportResult(error = throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    internal class ArtifactRecordWriter(
        private val database: SQLiteDatabase,
        private val artifactId: Long,
        private val metadata: ArtifactIndexMetadata,
    ) {
        var recordsInserted: Int = 0
            private set
        var fieldsInserted: Int = 0
            private set

        fun insert(
            jsonSource: String,
            category: ConsentCategory = metadata.category,
            record: FlattenedJsonRecord,
        ): Long {
            val folderMetadata = deriveFolderMetadata(metadata.sourcePath, category, jsonSource)
            val recordHash = canonicalRecordHash(record)
            val searchText = buildString {
                append(folderMetadata.folderLabel)
                append(' ')
                append(folderMetadata.collectionLabel)
                append(' ')
                append(record.title)
                append(' ')
                append(record.summary)
                record.fields.forEach { field ->
                    append(' ')
                    append(field.name)
                    append(' ')
                    append(field.value.take(MAX_SEARCH_FIELD_CHARS))
                }
            }.take(MAX_SEARCH_TEXT_CHARS)
            val recordId = database.insertWithOnConflict(
                "records",
                null,
                ContentValues().apply {
                    put("artifact_id", artifactId)
                    put("source_id", metadata.sourceId)
                    put("category", category.name)
                    put("json_source", jsonSource.take(1_024))
                    put("folder_label", folderMetadata.folderLabel)
                    put("collection_label", folderMetadata.collectionLabel)
                    put("record_label", folderMetadata.recordLabel)
                    put("record_type", record.recordType)
                    put("record_kind", record.recordKind.name)
                    put("record_index", record.recordIndex)
                    put("record_hash", recordHash)
                    put("title", record.title)
                    put("summary", record.summary)
                    put("timestamp_text", record.timestamp)
                    put("search_text", searchText)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (recordId < 0) return recordId
            recordsInserted += 1
            record.fields.forEach { field ->
                database.insertOrThrow(
                    "fields",
                    null,
                    ContentValues().apply {
                        put("record_id", recordId)
                        put("field_path", field.path)
                        put("field_name", field.name)
                        put("value_type", field.valueType.name)
                        put("text_value", field.value)
                    },
                )
                fieldsInserted += 1
            }
            return recordId
        }
    }

    internal inner class SourceArtifactWriter(
        private val database: SQLiteDatabase,
        private val sourceId: String,
    ) {
        fun replaceArtifact(
            metadata: ArtifactIndexMetadata,
            statusWhenNotParsed: ArtifactParseStatus = ArtifactParseStatus.NO_JSON,
            parse: ((ArtifactRecordWriter) -> ArtifactParseOutcome)? = null,
        ): ArtifactParseOutcome {
            require(metadata.sourceId == sourceId) { "Artifact source does not match the active source rebuild." }
            return writeArtifact(database, metadata, statusWhenNotParsed, parse)
        }

        fun markArtifactError(metadata: ArtifactIndexMetadata, error: String): ArtifactParseOutcome {
            return replaceArtifact(
                metadata = metadata,
                parse = { ArtifactParseOutcome(ArtifactParseStatus.ERROR, error = error) },
            )
        }
    }

    private fun upsertSource(database: SQLiteDatabase, sourceId: String, displayName: String) {
        val values = ContentValues().apply {
            put("source_id", sourceId)
            put("display_name", displayName)
            put("updated_at", System.currentTimeMillis())
        }
        database.insertWithOnConflict(
            "sources",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        database.update("sources", values, "source_id = ?", arrayOf(sourceId))
    }

    private fun scalarCount(database: SQLiteDatabase, sql: String, arguments: Array<String>? = null): Int {
        database.rawQuery(sql, arguments).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private fun android.database.Cursor.toRecordSummary(): IndexedRecordSummary {
        return IndexedRecordSummary(
            id = getLong(0),
            artifactId = getLong(1),
            sourceId = getString(2),
            sourceName = getString(3),
            sourcePath = getString(4),
            destinationUri = getString(5),
            jsonSource = getString(6),
            folderLabel = getString(7),
            collectionLabel = getString(8),
            recordLabel = getString(9),
            category = ConsentCategory.valueOf(getString(10)),
            recordType = getString(11),
            recordKind = ParsedRecordKind.valueOf(getString(12)),
            recordIndex = getInt(13),
            title = getString(14),
            summary = getString(15),
            timestamp = getString(16),
        )
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val DATABASE_NAME = "artifact_index.sqlite"
        const val DATABASE_VERSION = 4
        const val MAX_SEARCH_FIELD_CHARS = 2_048
        const val MAX_SEARCH_TEXT_CHARS = 262_144
        const val MAX_SELECTED_RECORDS = 900
        val IMAGE_EXTENSIONS = listOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    }
}