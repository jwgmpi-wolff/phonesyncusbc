package com.jerrywolff.phonesyncusbc.sync

import android.content.Context
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.RecoveryAccessMethod
import com.jerrywolff.phonesyncusbc.data.RecoveryInventoryItem
import com.jerrywolff.phonesyncusbc.data.RecoveryInventoryResult
import com.jerrywolff.phonesyncusbc.data.RecoveryInventoryWriter
import com.jerrywolff.phonesyncusbc.data.RecoveryItemStatus
import com.jerrywolff.phonesyncusbc.data.SyncStatus
import com.jerrywolff.phonesyncusbc.data.TransferStatus
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import com.jerrywolff.phonesyncusbc.usb.AttachedSource
import com.jerrywolff.phonesyncusbc.usb.PeerIdentity
import com.jerrywolff.phonesyncusbc.usb.UsbSourceResolver
import dadb.AdbAuthException
import dadb.AdbTimeoutException
import java.net.SocketTimeoutException

class AdbSyncEngine(
    context: Context,
    private val auditLog: AuditLog,
    private val sourceResolver: UsbSourceResolver,
    private val targetMediaStore: TargetMediaStore = TargetMediaStore(context),
    private val recoveryInventoryWriter: RecoveryInventoryWriter = RecoveryInventoryWriter(context),
) {
    fun sync(
        source: AttachedSource,
        identity: PeerIdentity,
        recoveryDeviceType: RecoveryDeviceType,
        authorizedCategories: Set<ConsentCategory>,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val startedAt = System.currentTimeMillis()
        val sessionId = auditLog.beginSession(identity.peerId)
        val files = mutableListOf<AdbSharedFile>()
        val recoveryItems = mutableListOf<RecoveryInventoryItem>()
        val inventoriedPaths = mutableSetOf<String>()
        val visibleCategories = linkedSetOf<ConsentCategory>()
        var transferred = 0
        var skipped = 0
        var failed = 0
        var transferredBytes = 0L
        var advertisedBytes = 0L
        var processed = 0
        var mediaVisible = 0
        var mediaTransferred = 0
        var mediaAlreadyCollected = 0
        var mediaNotAuthorized = 0
        var mediaFailed = 0
        var backupCandidates = 0
        var backupCandidateBytes = 0L
        var enumerationErrors = emptyList<String>()

        fun publish(
            currentItem: String? = null,
            currentItemBytes: Long = 0,
            currentItemTotal: Long = 0,
            phase: SyncPhase,
        ) {
            onProgress(
                SyncProgress(
                    currentItem = currentItem,
                    transferredItems = transferred,
                    skippedItems = skipped,
                    failedItems = failed,
                    bytesTransferred = transferredBytes +
                        if (phase == SyncPhase.TRANSFERRING) currentItemBytes else 0,
                    currentItemBytes = currentItemBytes,
                    currentItemTotal = currentItemTotal,
                    phase = phase,
                    discoveredItems = files.size,
                    processedItems = processed,
                    totalItems = files.size,
                    advertisedBytes = advertisedBytes,
                ),
            )
        }

        fun inventory(
            file: AdbSharedFile,
            category: ConsentCategory,
            status: RecoveryItemStatus,
            destination: String? = null,
            recoveredBytes: Long = 0,
            contentSha256: String? = null,
            error: String? = null,
        ) {
            recoveryItems += RecoveryInventoryItem(
                sourcePath = file.path,
                category = category,
                sourceSize = file.size,
                sourceModifiedAtEpochMillis = file.modifiedAtEpochMillis,
                status = status,
                destination = destination,
                recoveredBytes = recoveredBytes,
                contentSha256 = contentSha256,
                error = error,
                sensitive = category == ConsentCategory.PASSWORD_EXPORTS,
            )
            inventoriedPaths += file.path
        }

        fun scanSummary(): MtpScanSummary {
            return MtpScanSummary(
                accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE,
                scannedItems = files.size,
                processedItems = processed,
                advertisedBytes = advertisedBytes,
                visibleCategories = visibleCategories,
                downloadDirectoryVisible = files.any { "/download/" in it.path.lowercase() },
                phoneSyncDirectoryVisible = files.any {
                    val normalized = it.path.lowercase()
                    "/phone sync/" in normalized || "/phonesync/" in normalized
                },
                mediaItemsVisible = mediaVisible,
                mediaItemsTransferred = mediaTransferred,
                mediaItemsAlreadyCollected = mediaAlreadyCollected,
                mediaItemsNotAuthorized = mediaNotAuthorized,
                mediaItemsFailed = mediaFailed,
                backupCandidatesVisible = backupCandidates,
                backupCandidateBytes = backupCandidateBytes,
                enumerationFailures = enumerationErrors.size,
                enumerationErrors = enumerationErrors,
            )
        }

        fun writeInventory(status: SyncStatus): RecoveryInventoryResult {
            return recoveryInventoryWriter.write(
                peerId = identity.peerId,
                sourceName = source.detected.displayName,
                sourcePlatform = source.detected.platform,
                recoveryDeviceType = recoveryDeviceType,
                sessionStatus = status,
                sessionStartedAtEpochMillis = startedAt,
                sessionCompletedAtEpochMillis = System.currentTimeMillis(),
                accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE,
                items = recoveryItems,
            )
        }

        publish(
            currentItem = "Approve Allow USB debugging on the source phone",
            phase = SyncPhase.DISCOVERING,
        )
        val adbSession = try {
            sourceResolver.openAdb(source.device)
        } catch (throwable: Throwable) {
            return finishFailure(sessionId, adbFailureMessage(throwable), writeInventory(SyncStatus.FAILED))
        }
        if (adbSession == null) {
            return finishFailure(
                sessionId,
                "The source is not exposing an ADB USB interface. Enable Developer options and USB debugging, reconnect, and approve both prompts.",
                writeInventory(SyncStatus.FAILED),
            )
        }

        return try {
            adbSession.use { session ->
                val sharedStorage = AdbSharedStorageClient(session.client)
                val listing = sharedStorage.listFiles { file ->
                    files += file
                    advertisedBytes += file.size
                    publish(currentItem = file.path, phase = SyncPhase.DISCOVERING)
                }
                enumerationErrors = listing.errors
                enumerationErrors.forEachIndexed { index, detail ->
                    recoveryItems += RecoveryInventoryItem(
                        sourcePath = "/ADB enumeration failure/${index + 1}",
                        status = RecoveryItemStatus.FAILED,
                        error = "ADB shared-storage enumeration: $detail",
                    )
                }
                failed += enumerationErrors.size
                publish(phase = SyncPhase.TRANSFERRING)

                files.forEach { file ->
                    val category = TransferClassifier.classify(file.path)
                    visibleCategories += category
                    if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaVisible += 1
                    if (isBackupArtifactPath(file.path)) {
                        backupCandidates += 1
                        backupCandidateBytes += file.size
                    }
                    if (category !in authorizedCategories) {
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaNotAuthorized += 1
                        inventory(
                            file,
                            category,
                            RecoveryItemStatus.NOT_AUTHORIZED,
                            error = "This category was not authorized for recovery.",
                        )
                        processed += 1
                        publish(currentItem = file.path, phase = SyncPhase.TRANSFERRING)
                        return@forEach
                    }

                    val fingerprint = DeviceIdentity.sha256(
                        "${identity.peerId}|ADB|${file.path}|${file.size}|${file.modifiedAtEpochMillis}",
                    )
                    val prior = auditLog.completedTransfer(identity.peerId, fingerprint)
                    val priorIntegrity = prior?.let { completed ->
                        targetMediaStore.verifyStoredItem(
                            destination = completed.destination,
                            expectedBytes = completed.sourceSize.takeIf { it > 0 } ?: file.size,
                            expectedSha256 = completed.contentSha256,
                        )
                    }
                    if (prior != null && priorIntegrity != null) {
                        auditLog.updateTransferIntegrity(
                            transferId = prior.id,
                            sourceSize = file.size,
                            sourceModifiedAtEpochMillis = file.modifiedAtEpochMillis,
                            bytesTransferred = priorIntegrity.bytes,
                            contentSha256 = priorIntegrity.sha256,
                        )
                        skipped += 1
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaAlreadyCollected += 1
                        inventory(
                            file,
                            category,
                            RecoveryItemStatus.ALREADY_RECOVERED,
                            destination = prior.destination,
                            recoveredBytes = priorIntegrity.bytes,
                            contentSha256 = priorIntegrity.sha256,
                        )
                        processed += 1
                        publish(currentItem = file.path, phase = SyncPhase.TRANSFERRING)
                        return@forEach
                    }

                    runCatching {
                        targetMediaStore.copyFromWriter(
                            displayName = file.path.substringAfterLast('/').ifBlank { "recovered-artifact" },
                            category = category,
                            sourceName = source.detected.displayName,
                            modifiedAtEpochMillis = file.modifiedAtEpochMillis,
                            expectedBytes = file.size,
                            onIntegrityBytesRead = { currentBytes ->
                                publish(
                                    currentItem = file.path,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = file.size,
                                    phase = SyncPhase.VERIFYING,
                                )
                            },
                        ) { output ->
                            sharedStorage.pull(file, output) { currentBytes ->
                                publish(
                                    currentItem = file.path,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = file.size,
                                    phase = SyncPhase.TRANSFERRING,
                                )
                            }
                        }
                    }.onSuccess { result ->
                        val contentMatch = auditLog.completedTransferByContent(identity.peerId, result.sha256, category)
                        val contentMatchIntegrity = contentMatch?.let { completed ->
                            targetMediaStore.verifyStoredItem(
                                completed.destination,
                                completed.bytesTransferred,
                                result.sha256,
                            )
                        }
                        if (contentMatch != null && contentMatchIntegrity != null) {
                            targetMediaStore.discardStoredItem(result.uri)
                            auditLog.recordTransferAlias(identity.peerId, fingerprint, contentMatch.id)
                            skipped += 1
                            if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaAlreadyCollected += 1
                            inventory(
                                file,
                                category,
                                RecoveryItemStatus.ALREADY_RECOVERED,
                                destination = contentMatch.destination,
                                recoveredBytes = contentMatchIntegrity.bytes,
                                contentSha256 = contentMatchIntegrity.sha256,
                            )
                        } else {
                            transferred += 1
                            transferredBytes += result.bytesWritten
                            if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaTransferred += 1
                            auditLog.recordTransfer(
                                sessionId = sessionId,
                                peerId = identity.peerId,
                                sourceFingerprint = fingerprint,
                                category = category,
                                sourceItem = file.path,
                                destination = result.uri.toString(),
                                bytesTransferred = result.bytesWritten,
                                status = TransferStatus.COMPLETED,
                                sourceSize = file.size,
                                sourceModifiedAtEpochMillis = file.modifiedAtEpochMillis,
                                contentSha256 = result.sha256,
                            )
                            inventory(
                                file,
                                category,
                                RecoveryItemStatus.RECOVERED,
                                destination = result.uri.toString(),
                                recoveredBytes = result.bytesWritten,
                                contentSha256 = result.sha256,
                            )
                        }
                    }.onFailure { throwable ->
                        failed += 1
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaFailed += 1
                        val error = throwable.message ?: throwable.javaClass.simpleName
                        auditLog.recordTransfer(
                            sessionId = sessionId,
                            peerId = identity.peerId,
                            sourceFingerprint = fingerprint,
                            category = category,
                            sourceItem = file.path,
                            destination = null,
                            bytesTransferred = 0,
                            status = TransferStatus.FAILED,
                            sourceSize = file.size,
                            sourceModifiedAtEpochMillis = file.modifiedAtEpochMillis,
                            error = error,
                        )
                        inventory(file, category, RecoveryItemStatus.FAILED, error = error)
                    }
                    processed += 1
                    publish(
                        currentItem = file.path,
                        currentItemBytes = file.size,
                        currentItemTotal = file.size,
                        phase = SyncPhase.TRANSFERRING,
                    )
                }
            }

            publish(phase = SyncPhase.COMPLETE)
            val transferStatus = if (failed == 0) SyncStatus.COMPLETED else SyncStatus.PARTIAL
            val recoveryInventory = writeInventory(transferStatus)
            val inventoryError = recoveryInventory.error?.let { "Recovery inventory failed: $it" }
            val status = if (inventoryError == null) transferStatus else SyncStatus.PARTIAL
            auditLog.finishSession(sessionId, status, transferred, transferredBytes, inventoryError)
            SyncResult(
                status = status,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed,
                bytesTransferred = transferredBytes,
                error = inventoryError,
                accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE,
                mtpScan = scanSummary(),
                recoveryInventory = recoveryInventory,
            )
        } catch (throwable: Throwable) {
            val error = adbFailureMessage(throwable)
            files.filterNot { it.path in inventoriedPaths }.forEach { file ->
                inventory(
                    file,
                    TransferClassifier.classify(file.path),
                    RecoveryItemStatus.NOT_RECOVERED,
                    error = error,
                )
            }
            val status = if (transferred > 0) SyncStatus.PARTIAL else SyncStatus.FAILED
            val recoveryInventory = writeInventory(status)
            val combinedError = recoveryInventory.error
                ?.let { "$error; recovery inventory also failed: $it" }
                ?: error
            auditLog.finishSession(sessionId, status, transferred, transferredBytes, combinedError)
            SyncResult(
                status = status,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed + 1,
                bytesTransferred = transferredBytes,
                error = combinedError,
                accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE,
                mtpScan = scanSummary(),
                recoveryInventory = recoveryInventory,
            )
        }
    }

    private fun finishFailure(
        sessionId: Long,
        error: String,
        recoveryInventory: RecoveryInventoryResult,
    ): SyncResult {
        val combinedError = recoveryInventory.error
            ?.let { "$error; recovery inventory also failed: $it" }
            ?: error
        auditLog.finishSession(sessionId, SyncStatus.FAILED, 0, 0, combinedError)
        return SyncResult(
            status = SyncStatus.FAILED,
            transferredItems = 0,
            skippedItems = 0,
            failedItems = 1,
            bytesTransferred = 0,
            error = combinedError,
            accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE,
            mtpScan = MtpScanSummary(accessMethod = RecoveryAccessMethod.ADB_SHARED_STORAGE),
            recoveryInventory = recoveryInventory,
        )
    }

    private fun adbFailureMessage(throwable: Throwable): String {
        val causes = generateSequence(throwable as Throwable?) { it.cause }.toList()
        return when {
            causes.any { it is AdbAuthException } ->
                "ADB authorization was not approved on the source phone. Unlock it, approve Allow USB debugging, and retry."
            causes.any { it is AdbTimeoutException || it is SocketTimeoutException } ->
                "ADB authorization or transfer timed out. Keep the source unlocked, approve Allow USB debugging, and retry."
            else -> throwable.message ?: "The ADB shared-storage connection failed."
        }
    }
}