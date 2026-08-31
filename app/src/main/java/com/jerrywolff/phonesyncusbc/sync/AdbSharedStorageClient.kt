package com.jerrywolff.phonesyncusbc.sync

import dadb.Dadb
import dadb.SyncResult as DadbSyncResult
import okio.sink
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal

internal data class AdbSharedFile(
    val path: String,
    val size: Long,
    val modifiedAtEpochMillis: Long,
)

internal data class AdbSharedStorageListing(
    val files: List<AdbSharedFile>,
    val errors: List<String>,
)

internal class AdbSharedStorageClient(private val client: Dadb) {
    fun listFiles(onFileDiscovered: (AdbSharedFile) -> Unit = {}): AdbSharedStorageListing {
        val listing = client.shell(SHARED_STORAGE_FIND_METADATA_COMMAND)
        if (listing.exitCode != 0 && listing.output.isBlank()) {
            return listFilesWithLegacyFind(onFileDiscovered)
        }
        val errors = listing.errorOutput.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toMutableList()
        val files = parseAdbSharedStorageListing(listing.output)
        val metadataRecordCount = listing.output.split('\u0000').count(String::isNotEmpty)
        val malformedRecordCount = metadataRecordCount - files.size
        if (malformedRecordCount > 0) {
            errors += "$malformedRecordCount shared-storage entries returned unreadable metadata."
        }
        files.forEach(onFileDiscovered)
        return AdbSharedStorageListing(files, errors)
    }

    private fun listFilesWithLegacyFind(
        onFileDiscovered: (AdbSharedFile) -> Unit,
    ): AdbSharedStorageListing {
        val listing = client.shell(SHARED_STORAGE_FIND_PATHS_COMMAND)
        val errors = listing.errorOutput.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toMutableList()
        if (listing.exitCode != 0 && listing.output.isBlank()) {
            throw IOException(
                errors.joinToString("; ").ifBlank {
                    "The source refused to enumerate shared storage."
                },
            )
        }
        val files = parseAdbSharedStoragePaths(listing.output).map { path ->
            AdbSharedFile(
                path = path,
                size = 0,
                modifiedAtEpochMillis = 0,
            ).also(onFileDiscovered)
        }
        return AdbSharedStorageListing(files, errors)
    }

    fun pull(
        file: AdbSharedFile,
        destination: OutputStream,
        onBytesTransferred: (Long) -> Unit = {},
    ): Long {
        val countingOutput = ProgressOutputStream(destination, onBytesTransferred)
        return when (val result = client.pull(countingOutput.sink(), file.path)) {
            DadbSyncResult.Success -> countingOutput.bytesWritten
            is DadbSyncResult.Failure -> throw IOException(result.reason)
        }
    }
}

internal fun parseAdbSharedStorageListing(output: String): List<AdbSharedFile> {
    return output.split('\u0000')
        .asSequence()
        .mapNotNull { record ->
            val firstSeparator = record.indexOf('\t')
            val secondSeparator = record.indexOf('\t', firstSeparator + 1)
            if (firstSeparator <= 0 || secondSeparator <= firstSeparator) return@mapNotNull null
            val size = record.substring(0, firstSeparator).toLongOrNull()?.coerceAtLeast(0)
                ?: return@mapNotNull null
            val modifiedAt = parseAdbEpochMillis(
                record.substring(firstSeparator + 1, secondSeparator),
            )
                ?: return@mapNotNull null
            val path = record.substring(secondSeparator + 1)
            if (!isPrimarySharedStoragePath(path)) return@mapNotNull null
            AdbSharedFile(path, size, modifiedAt)
        }
        .distinctBy(AdbSharedFile::path)
        .toList()
}

internal fun parseAdbSharedStoragePaths(output: String): List<String> {
    return output.split('\u0000')
        .asSequence()
        .filter(::isPrimarySharedStoragePath)
        .distinct()
        .toList()
}

private fun isPrimarySharedStoragePath(path: String): Boolean {
    return path.startsWith("/storage/emulated/0/") &&
        !path.contains("/../") &&
        !path.endsWith("/..")
}

internal fun parseAdbEpochMillis(value: String): Long? {
    val milliseconds = value.toBigDecimalOrNull()?.multiply(MILLIS_PER_SECOND) ?: return null
    return when {
        milliseconds.signum() < 0 -> 0
        milliseconds > MAX_EPOCH_MILLIS -> Long.MAX_VALUE
        else -> milliseconds.toLong()
    }
}

private class ProgressOutputStream(
    private val destination: OutputStream,
    private val onBytesTransferred: (Long) -> Unit,
) : OutputStream() {
    var bytesWritten: Long = 0
        private set

    override fun write(value: Int) {
        destination.write(value)
        publish(1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        destination.write(buffer, offset, length)
        publish(length)
    }

    override fun flush() {
        destination.flush()
    }

    private fun publish(count: Int) {
        bytesWritten += count
        onBytesTransferred(bytesWritten)
    }
}

private val MILLIS_PER_SECOND = BigDecimal.valueOf(1_000)
private val MAX_EPOCH_MILLIS = BigDecimal.valueOf(Long.MAX_VALUE)
private const val SHARED_STORAGE_FIND_METADATA_COMMAND =
    "find /storage/emulated/0 -type f -printf '%s\\t%T@\\t%p\\0'"
private const val SHARED_STORAGE_FIND_PATHS_COMMAND =
    "find /storage/emulated/0 -type f -print0"