package com.jerrywolff.phonesyncusbc.sync

import android.content.ContentValues
import android.content.Context
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import java.io.InputStream
import java.security.MessageDigest

data class TargetWriteResult(
    val uri: Uri,
    val bytesWritten: Long,
    val sha256: String,
)

data class StoredItemIntegrity(
    val bytes: Long,
    val sha256: String,
)

private data class IntegrityResult(
    val bytesRead: Long,
    val sha256: String,
)

enum class MtpReadMode {
    PARTIAL_64,
    PARTIAL_STANDARD,
    FULL_OBJECT,
}

data class MtpReadAttempt(
    val mode: MtpReadMode,
    val attempt: Int,
    val attemptsForMode: Int,
)

fun mtpReadPlan(
    supportsPartial64: Boolean,
    supportsPartialStandard: Boolean,
): List<MtpReadMode> = buildList {
    if (supportsPartial64) add(MtpReadMode.PARTIAL_64)
    if (supportsPartialStandard) add(MtpReadMode.PARTIAL_STANDARD)
    add(MtpReadMode.FULL_OBJECT)
}

fun mtpReadAttempts(
    supportsPartial64: Boolean,
    supportsPartialStandard: Boolean,
    attemptsPerMode: Int = DEFAULT_ATTEMPTS_PER_MODE,
): List<MtpReadAttempt> {
    require(attemptsPerMode > 0) { "At least one read attempt is required." }
    return mtpReadPlan(supportsPartial64, supportsPartialStandard).flatMap { mode ->
        (1..attemptsPerMode).map { attempt -> MtpReadAttempt(mode, attempt, attemptsPerMode) }
    }
}

class TargetMediaStore(private val context: Context) {
    fun discardStoredItem(destination: Uri) {
        context.contentResolver.delete(destination, null, null)
    }

    fun verifyStoredItem(
        destination: String?,
        expectedBytes: Long,
        expectedSha256: String?,
        onBytesRead: (Long) -> Unit = {},
    ): StoredItemIntegrity? {
        val uri = destination?.let(Uri::parse) ?: return null
        return runCatching {
            val integrity = calculateIntegrity(uri, onBytesRead)
            val sizeMatches = expectedBytes <= 0 || integrity.bytesRead == expectedBytes
            val hashMatches = expectedSha256 == null ||
                integrity.sha256.equals(expectedSha256, ignoreCase = true)
            if (sizeMatches && hashMatches) {
                StoredItemIntegrity(integrity.bytesRead, integrity.sha256)
            } else {
                null
            }
        }.getOrNull()
    }

    fun importMtpObject(
        mtpDevice: MtpDevice,
        objectHandle: Int,
        displayName: String,
        category: ConsentCategory,
        sourceName: String,
        modifiedAtEpochMillis: Long,
        expectedBytes: Long,
        onBytesTransferred: (Long) -> Unit = {},
        onIntegrityBytesRead: (Long) -> Unit = {},
        onReadAttempt: (MtpReadAttempt) -> Unit = {},
    ): TargetWriteResult {
        return writePendingItem(
            displayName = displayName,
            category = category,
            sourceName = sourceName,
            modifiedAtEpochMillis = modifiedAtEpochMillis,
            mimeType = mimeType(displayName, category),
            expectedBytes = expectedBytes,
            onIntegrityBytesRead = onIntegrityBytesRead,
        ) { destination ->
            val deviceInfo = runCatching { mtpDevice.deviceInfo }.getOrNull()
            val readAttempts = mtpReadAttempts(
                supportsPartial64 =
                    deviceInfo?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64) == true,
                supportsPartialStandard =
                    deviceInfo?.isOperationSupported(MtpConstants.OPERATION_GET_PARTIAL_OBJECT) == true,
            )
            val failures = mutableListOf<Pair<MtpReadAttempt, Throwable>>()
            readAttempts.forEach { readAttempt ->
                if (readAttempt.mode != MtpReadMode.FULL_OBJECT && expectedBytes <= 0) return@forEach
                onReadAttempt(readAttempt)
                val result = runCatching {
                    if (readAttempt.mode == MtpReadMode.FULL_OBJECT) {
                        importWholeObject(
                            mtpDevice = mtpDevice,
                            objectHandle = objectHandle,
                            destination = destination,
                            expectedBytes = expectedBytes,
                            onBytesTransferred = onBytesTransferred,
                        )
                    } else {
                        importPartialObject(
                            mtpDevice = mtpDevice,
                            objectHandle = objectHandle,
                            destination = destination,
                            expectedBytes = expectedBytes,
                            mode = readAttempt.mode,
                            onBytesTransferred = onBytesTransferred,
                        )
                        expectedBytes
                    }
                }
                if (result.isSuccess) return@writePendingItem result.getOrThrow()
                result.exceptionOrNull()?.let { failures += readAttempt to it }
            }
            val details = failures.joinToString("; ") { (attempt, throwable) ->
                "${attempt.mode.name} ${attempt.attempt}/${attempt.attemptsForMode}: " +
                    (throwable.message ?: throwable.javaClass.simpleName)
            }
            error("All USB read methods failed. $details")
        }
    }

    private fun importPartialObject(
        mtpDevice: MtpDevice,
        objectHandle: Int,
        destination: Uri,
        expectedBytes: Long,
        mode: MtpReadMode,
        onBytesTransferred: (Long) -> Unit,
    ) {
        context.contentResolver.openOutputStream(destination, "w").use { output ->
            checkNotNull(output) { "Android could not open the destination item." }
            val buffer = ByteArray(MTP_CHUNK_BYTES)
            var offset = 0L
            while (offset < expectedBytes) {
                val requested = minOf(buffer.size.toLong(), expectedBytes - offset)
                val read = when (mode) {
                    MtpReadMode.PARTIAL_64 ->
                        mtpDevice.getPartialObject64(objectHandle, offset, requested, buffer)
                    MtpReadMode.PARTIAL_STANDARD ->
                        mtpDevice.getPartialObject(objectHandle, offset, requested, buffer)
                    MtpReadMode.FULL_OBJECT -> error("Full-object mode cannot be used for partial reads.")
                }
                check(read > 0) { "The source phone stopped transferring this item." }
                val bytesRead = read.coerceAtMost(buffer.size.toLong()).toInt()
                output.write(buffer, 0, bytesRead)
                offset += bytesRead
                onBytesTransferred(offset)
            }
        }
    }

    private fun importWholeObject(
        mtpDevice: MtpDevice,
        objectHandle: Int,
        destination: Uri,
        expectedBytes: Long,
        onBytesTransferred: (Long) -> Unit,
    ): Long {
        val fullObjectFailure = runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(destination, "w")
                ?: error("Android could not open the destination item.")
            descriptor.use {
                check(mtpDevice.importFile(objectHandle, it)) {
                    "The source refused the standard full-object transfer request."
                }
            }
        }.exceptionOrNull()
        if (fullObjectFailure != null) {
            error("Full-object transfer failed: ${fullObjectFailure.message.orEmpty()}")
        }
        val copiedBytes = destinationSize(destination).takeIf { it > 0 }
            ?: expectedBytes.coerceAtLeast(0)
        onBytesTransferred(copiedBytes)
        return copiedBytes
    }

    private fun destinationSize(destination: Uri): Long {
        return runCatching {
            context.contentResolver.query(
                destination,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun copyFromProvider(
        sourceUri: Uri,
        displayName: String,
        mimeType: String?,
        category: ConsentCategory,
        sourceName: String,
        modifiedAtEpochMillis: Long,
    ): TargetWriteResult {
        return writePendingItem(
            displayName = displayName,
            category = category,
            sourceName = sourceName,
            modifiedAtEpochMillis = modifiedAtEpochMillis,
            mimeType = mimeType ?: mimeType(displayName, category),
        ) { destination ->
            val source = context.contentResolver.openInputStream(sourceUri)
                ?: error("The selected provider could not open this item.")
            source.use { input ->
                context.contentResolver.openOutputStream(destination, "w").use { output ->
                    checkNotNull(output) { "Android could not open the destination item." }
                    input.copyTo(output)
                }
            }
        }
    }

    fun copyFromStream(
        source: InputStream,
        displayName: String,
        mimeType: String?,
        category: ConsentCategory,
        sourceName: String,
        modifiedAtEpochMillis: Long,
        onBytesTransferred: (Long) -> Unit = {},
    ): TargetWriteResult {
        return writePendingItem(
            displayName = displayName,
            category = category,
            sourceName = sourceName,
            modifiedAtEpochMillis = modifiedAtEpochMillis,
            mimeType = mimeType ?: mimeType(displayName, category),
        ) { destination ->
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                checkNotNull(output) { "Android could not open the destination item." }
                val buffer = ByteArray(MTP_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    total += count
                    onBytesTransferred(total)
                }
                total
            }
        }
    }

    private fun writePendingItem(
        displayName: String,
        category: ConsentCategory,
        sourceName: String,
        modifiedAtEpochMillis: Long,
        mimeType: String,
        expectedBytes: Long = 0,
        onIntegrityBytesRead: (Long) -> Unit = {},
        writer: (Uri) -> Long,
    ): TargetWriteResult {
        val safeName = sanitizePathSegment(displayName)
        val isVideo = category == ConsentCategory.PHOTOS_AND_VIDEOS && TransferClassifier.isVideo(safeName)
        val collection = when {
            category != ConsentCategory.PHOTOS_AND_VIDEOS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val rootDirectory = when {
            category != ConsentCategory.PHOTOS_AND_VIDEOS -> Environment.DIRECTORY_DOWNLOADS
            isVideo -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_PICTURES
        }
        val categoryDirectory = category.name.lowercase().replace('_', '-')
        val relativePath = "$rootDirectory/Phone Sync/${sanitizePathSegment(sourceName)}/$categoryDirectory"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_MODIFIED, modifiedAtEpochMillis / 1_000)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(collection, values)
            ?: error("Android could not create the destination item.")
        try {
            writer(destination)
            val integrity = calculateIntegrity(destination, onIntegrityBytesRead)
            check(expectedBytes <= 0 || integrity.bytesRead == expectedBytes) {
                "Recovered size mismatch: expected $expectedBytes bytes but verified ${integrity.bytesRead} bytes."
            }
            context.contentResolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return TargetWriteResult(destination, integrity.bytesRead, integrity.sha256)
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            throw throwable
        }
    }

    private fun calculateIntegrity(
        destination: Uri,
        onBytesRead: (Long) -> Unit,
    ): IntegrityResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(destination)
            ?: error("Android could not reopen the recovered item for integrity verification.")
        var total = 0L
        input.use { source ->
            val buffer = ByteArray(MTP_CHUNK_BYTES)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
                onBytesRead(total)
            }
        }
        return IntegrityResult(
            bytesRead = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun mimeType(displayName: String, category: ConsentCategory): String {
        if (category == ConsentCategory.CONTACTS) return "text/vcard"
        if (category == ConsentCategory.PASSWORD_EXPORTS) return "application/octet-stream"
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun sanitizePathSegment(value: String): String {
        return value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "source" }
    }

    private companion object {
        const val MTP_CHUNK_BYTES = 1024 * 1024
    }

}

private const val DEFAULT_ATTEMPTS_PER_MODE = 2