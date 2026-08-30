package com.jerrywolff.phonesyncusbc.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory

private const val LEGACY_COLLECTOR_PEER_ID = "local-android"

fun isExternalSourcePeer(peerId: String): Boolean = peerId.isNotBlank() && peerId != LEGACY_COLLECTOR_PEER_ID

fun isCollectorOwnedSourceItem(sourceItem: String): Boolean {
    val normalized = "/" + sourceItem.replace('\\', '/').trim('/').lowercase() + "/"
    return "/phone sync/this android/" in normalized ||
        "/phonesync/this android/" in normalized ||
        "/phone sync/local-android/" in normalized ||
        "/phonesync/local-android/" in normalized ||
        "/phone sync/selected folder/" in normalized ||
        "/phonesync/selected folder/" in normalized ||
        "/phone sync backups/" in normalized ||
        "/phone sync uploads/" in normalized ||
        "/phone sync/data reader/" in normalized
}

fun isExternalSourceRecord(peerId: String, sourceItem: String): Boolean {
    return isExternalSourcePeer(peerId) && !isCollectorOwnedSourceItem(sourceItem)
}

fun AuditEntry.isExternalSourceFor(expectedPeerId: String): Boolean {
    return expectedPeerId.isNotBlank() &&
        peerId == expectedPeerId &&
        isExternalSourceRecord(peerId, sourceItem) &&
        sourceFingerprint.isNotBlank() &&
        status == TransferStatus.COMPLETED &&
        !destination.isNullOrBlank()
}

enum class SyncStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
}

enum class TransferStatus {
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class AuditEntry(
    val id: Long,
    val transferredAtEpochMillis: Long,
    val category: ConsentCategory,
    val sourceItem: String,
    val destination: String?,
    val bytesTransferred: Long,
    val status: TransferStatus,
    val error: String?,
    val sourceSize: Long = 0,
    val sourceModifiedAtEpochMillis: Long = 0,
    val contentSha256: String? = null,
    val peerId: String = "",
    val sourceFingerprint: String = "",
)

fun AuditEntry.idempotencyKey(): String = when {
    contentSha256?.isNotBlank() == true -> "sha256:${contentSha256.lowercase()}"
    sourceFingerprint.isNotBlank() -> "fingerprint:$sourceFingerprint"
    else -> "metadata:${sourceItem.replace('\\', '/').lowercase()}|$sourceSize|$sourceModifiedAtEpochMillis"
}

fun externalDeviceRecoveryEntries(entries: List<AuditEntry>): List<AuditEntry> {
    return entries
        .asSequence()
        .filter { isExternalSourceRecord(it.peerId, it.sourceItem) && it.sourceFingerprint.isNotBlank() }
        .distinctBy(AuditEntry::idempotencyKey)
        .toList()
}

fun externalDeviceRecoveryEntries(entries: List<AuditEntry>, expectedPeerId: String): List<AuditEntry> {
    return entries
        .asSequence()
        .filter { it.isExternalSourceFor(expectedPeerId) }
        .distinctBy(AuditEntry::idempotencyKey)
        .toList()
}

fun AuditEntry.displayName(): String {
    return destination?.substringAfterLast('/')?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
        ?: sourceItem.substringAfterLast('/').ifBlank { "Recovered artifact" }
}

fun AuditEntry.storageLocation(): String {
    return destination ?: "Recovered artifact is no longer available"
}

data class SyncSummary(
    val id: Long,
    val completedAtEpochMillis: Long?,
    val status: SyncStatus,
    val itemCount: Int,
    val bytesTransferred: Long,
)

class AuditLog(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sync_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                peer_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                status TEXT NOT NULL,
                item_count INTEGER NOT NULL DEFAULT 0,
                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                error TEXT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE transfers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                peer_id TEXT NOT NULL,
                source_fingerprint TEXT NOT NULL,
                transferred_at INTEGER NOT NULL,
                category TEXT NOT NULL,
                source_item TEXT NOT NULL,
                source_size INTEGER NOT NULL DEFAULT 0,
                source_modified_at INTEGER NOT NULL DEFAULT 0,
                destination TEXT,
                bytes_transferred INTEGER NOT NULL DEFAULT 0,
                content_sha256 TEXT,
                status TEXT NOT NULL,
                error TEXT,
                FOREIGN KEY(session_id) REFERENCES sync_sessions(id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX transfers_peer_time ON transfers(peer_id, transferred_at DESC)",
        )
        database.execSQL(
            "CREATE INDEX transfers_fingerprint ON transfers(peer_id, source_fingerprint, status)",
        )
        createTransferAliases(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(database, "source_size", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(database, "source_modified_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(database, "content_sha256", "TEXT")
        }
        if (oldVersion < 3) createTransferAliases(database)
    }

    private fun createTransferAliases(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transfer_aliases (
                peer_id TEXT NOT NULL,
                source_fingerprint TEXT NOT NULL,
                transfer_id INTEGER NOT NULL,
                PRIMARY KEY(peer_id, source_fingerprint),
                FOREIGN KEY(transfer_id) REFERENCES transfers(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS transfer_alias_target ON transfer_aliases(transfer_id)",
        )
    }

    private fun addColumnIfMissing(
        database: SQLiteDatabase,
        columnName: String,
        definition: String,
    ) {
        if (hasColumn(database, columnName)) return
        database.execSQL("ALTER TABLE transfers ADD COLUMN $columnName $definition")
    }

    private fun hasColumn(database: SQLiteDatabase, columnName: String): Boolean {
        database.rawQuery("PRAGMA table_info(transfers)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    fun beginSession(peerId: String): Long {
        val values = ContentValues().apply {
            put("peer_id", peerId)
            put("started_at", System.currentTimeMillis())
            put("status", SyncStatus.RUNNING.name)
        }
        return writableDatabase.insertOrThrow("sync_sessions", null, values)
    }

    fun finishSession(
        sessionId: Long,
        status: SyncStatus,
        itemCount: Int,
        bytesTransferred: Long,
        error: String? = null,
    ) {
        val values = ContentValues().apply {
            put("completed_at", System.currentTimeMillis())
            put("status", status.name)
            put("item_count", itemCount)
            put("bytes_transferred", bytesTransferred)
            put("error", error)
        }
        writableDatabase.update("sync_sessions", values, "id = ?", arrayOf(sessionId.toString()))
    }

    fun recordTransfer(
        sessionId: Long,
        peerId: String,
        sourceFingerprint: String,
        category: ConsentCategory,
        sourceItem: String,
        destination: String?,
        bytesTransferred: Long,
        status: TransferStatus,
        sourceSize: Long = 0,
        sourceModifiedAtEpochMillis: Long = 0,
        contentSha256: String? = null,
        error: String? = null,
    ) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("peer_id", peerId)
            put("source_fingerprint", sourceFingerprint)
            put("transferred_at", System.currentTimeMillis())
            put("category", category.name)
            put("source_item", sourceItem)
            put("source_size", sourceSize)
            put("source_modified_at", sourceModifiedAtEpochMillis)
            put("destination", destination)
            put("bytes_transferred", bytesTransferred)
            put("content_sha256", contentSha256)
            put("status", status.name)
            put("error", error)
        }
        val transferId = writableDatabase.insertOrThrow("transfers", null, values)
        if (status == TransferStatus.COMPLETED) {
            recordTransferAlias(peerId, sourceFingerprint, transferId)
        }
    }

    fun updateTransferIntegrity(
        transferId: Long,
        sourceSize: Long,
        sourceModifiedAtEpochMillis: Long,
        bytesTransferred: Long,
        contentSha256: String,
    ) {
        val values = ContentValues().apply {
            put("source_size", sourceSize)
            put("source_modified_at", sourceModifiedAtEpochMillis)
            put("bytes_transferred", bytesTransferred)
            put("content_sha256", contentSha256)
        }
        writableDatabase.update("transfers", values, "id = ?", arrayOf(transferId.toString()))
    }

    fun wasTransferred(peerId: String, sourceFingerprint: String): Boolean {
        return completedTransfer(peerId, sourceFingerprint) != null
    }

    fun completedTransfer(peerId: String, sourceFingerprint: String): AuditEntry? {
        readableDatabase.rawQuery(
            """
            SELECT t.id, t.transferred_at, t.category, t.source_item, t.destination,
                   t.bytes_transferred, t.status, t.error, t.source_size, t.source_modified_at,
                   t.content_sha256, t.peer_id, t.source_fingerprint
            FROM transfers t
            LEFT JOIN transfer_aliases a ON a.transfer_id = t.id
            WHERE t.peer_id = ? AND (t.source_fingerprint = ? OR a.source_fingerprint = ?)
              AND t.status = ?
            ORDER BY t.transferred_at DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(peerId, sourceFingerprint, sourceFingerprint, TransferStatus.COMPLETED.name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.toAuditEntry()
        }
    }

    fun completedTransferByContent(
        peerId: String,
        contentSha256: String,
        category: ConsentCategory? = null,
    ): AuditEntry? {
        val selection = buildString {
            append("peer_id = ? AND content_sha256 = ? AND status = ? AND destination IS NOT NULL")
            if (category != null) append(" AND category = ?")
        }
        val arguments = buildList {
            add(peerId)
            add(contentSha256)
            add(TransferStatus.COMPLETED.name)
            category?.let { add(it.name) }
        }.toTypedArray()
        readableDatabase.query(
            "transfers",
            AUDIT_ENTRY_COLUMNS,
            selection,
            arguments,
            null,
            null,
            "transferred_at DESC",
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toAuditEntry() else null }
    }

    fun recordTransferAlias(peerId: String, sourceFingerprint: String, transferId: Long) {
        writableDatabase.insertWithOnConflict(
            "transfer_aliases",
            null,
            ContentValues().apply {
                put("peer_id", peerId)
                put("source_fingerprint", sourceFingerprint)
                put("transfer_id", transferId)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun latestSession(peerId: String): SyncSummary? {
        readableDatabase.query(
            "sync_sessions",
            arrayOf("id", "completed_at", "status", "item_count", "bytes_transferred"),
            "peer_id = ? AND completed_at IS NOT NULL",
            arrayOf(peerId),
            null,
            null,
            "completed_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SyncSummary(
                id = cursor.getLong(0),
                completedAtEpochMillis = cursor.getLong(1),
                status = SyncStatus.valueOf(cursor.getString(2)),
                itemCount = cursor.getInt(3),
                bytesTransferred = cursor.getLong(4),
            )
        }
    }

    fun recentTransfers(peerId: String, limit: Int = 100): List<AuditEntry> {
        readableDatabase.query(
            "transfers",
            arrayOf(
                "id",
                "transferred_at",
                "category",
                "source_item",
                "destination",
                "bytes_transferred",
                "status",
                "error",
                "source_size",
                "source_modified_at",
                "content_sha256",
                "peer_id",
                "source_fingerprint",
            ),
            "peer_id = ?",
            arrayOf(peerId),
            null,
            null,
            "transferred_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        AuditEntry(
                            id = cursor.getLong(0),
                            transferredAtEpochMillis = cursor.getLong(1),
                            category = ConsentCategory.valueOf(cursor.getString(2)),
                            sourceItem = cursor.getString(3),
                            destination = cursor.getString(4),
                            bytesTransferred = cursor.getLong(5),
                            status = TransferStatus.valueOf(cursor.getString(6)),
                            error = cursor.getString(7),
                            sourceSize = cursor.getLong(8),
                            sourceModifiedAtEpochMillis = cursor.getLong(9),
                            contentSha256 = cursor.getString(10),
                            peerId = cursor.getString(11),
                            sourceFingerprint = cursor.getString(12),
                        ),
                    )
                }
            }
        }
    }

    fun completedTransfers(peerId: String, limit: Int? = null): List<AuditEntry> {
        readableDatabase.query(
            "transfers",
            arrayOf(
                "id",
                "transferred_at",
                "category",
                "source_item",
                "destination",
                "bytes_transferred",
                "status",
                "error",
                "source_size",
                "source_modified_at",
                "content_sha256",
                "peer_id",
                "source_fingerprint",
            ),
            "peer_id = ? AND status = ? AND destination IS NOT NULL",
            arrayOf(peerId, TransferStatus.COMPLETED.name),
            null,
            null,
            "transferred_at DESC",
            limit?.coerceIn(1, 100_000)?.toString(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        AuditEntry(
                            id = cursor.getLong(0),
                            transferredAtEpochMillis = cursor.getLong(1),
                            category = ConsentCategory.valueOf(cursor.getString(2)),
                            sourceItem = cursor.getString(3),
                            destination = cursor.getString(4),
                            bytesTransferred = cursor.getLong(5),
                            status = TransferStatus.valueOf(cursor.getString(6)),
                            error = cursor.getString(7),
                            sourceSize = cursor.getLong(8),
                            sourceModifiedAtEpochMillis = cursor.getLong(9),
                            contentSha256 = cursor.getString(10),
                            peerId = cursor.getString(11),
                            sourceFingerprint = cursor.getString(12),
                        ),
                    )
                }
            }
        }
    }

    fun completedExternalTransfers(peerId: String?, limit: Int? = null): List<AuditEntry> {
        if (peerId == null || !isExternalSourcePeer(peerId)) return emptyList()
        return externalDeviceRecoveryEntries(completedTransfers(peerId), peerId)
            .asSequence()
            .let { entries -> limit?.let(entries::take) ?: entries }
            .toList()
    }

    fun latestExternalPeerId(): String? {
        readableDatabase.query(
            "transfers",
            arrayOf("peer_id", "source_item", "source_fingerprint"),
            "peer_id != ? AND status = ? AND destination IS NOT NULL",
            arrayOf(LEGACY_COLLECTOR_PEER_ID, TransferStatus.COMPLETED.name),
            null,
            null,
            "transferred_at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val peerId = cursor.getString(0)
                if (isExternalSourceRecord(peerId, cursor.getString(1)) && cursor.getString(2).isNotBlank()) return peerId
            }
            return null
        }
    }

    fun clear(peerId: String) {
        writableDatabase.delete("transfers", "peer_id = ?", arrayOf(peerId))
        writableDatabase.delete("sync_sessions", "peer_id = ?", arrayOf(peerId))
    }

    private companion object {
        const val DATABASE_NAME = "transfer_audit.db"
        const val DATABASE_VERSION = 3
        val AUDIT_ENTRY_COLUMNS = arrayOf(
            "id",
            "transferred_at",
            "category",
            "source_item",
            "destination",
            "bytes_transferred",
            "status",
            "error",
            "source_size",
            "source_modified_at",
            "content_sha256",
            "peer_id",
            "source_fingerprint",
        )
    }

    private fun android.database.Cursor.toAuditEntry(): AuditEntry {
        return AuditEntry(
            id = getLong(0),
            transferredAtEpochMillis = getLong(1),
            category = ConsentCategory.valueOf(getString(2)),
            sourceItem = getString(3),
            destination = getString(4),
            bytesTransferred = getLong(5),
            status = TransferStatus.valueOf(getString(6)),
            error = getString(7),
            sourceSize = getLong(8),
            sourceModifiedAtEpochMillis = getLong(9),
            contentSha256 = getString(10),
            peerId = getString(11),
            sourceFingerprint = getString(12),
        )
    }
}