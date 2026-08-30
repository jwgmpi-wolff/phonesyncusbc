package com.jerrywolff.phonesyncusbc.sync

import android.content.Context
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import com.jerrywolff.phonesyncusbc.data.AuditLog
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
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

data class SyncProgress(
    val currentItem: String?,
    val transferredItems: Int,
    val skippedItems: Int,
    val failedItems: Int,
    val bytesTransferred: Long,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
    val phase: SyncPhase = SyncPhase.TRANSFERRING,
    val discoveredItems: Int = 0,
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val advertisedBytes: Long = 0,
    val readMethod: MtpReadMode? = null,
    val readAttempt: Int = 0,
    val readAttemptsForMethod: Int = 0,
)

enum class SyncPhase {
    DISCOVERING,
    TRANSFERRING,
    VERIFYING,
    COMPLETE,
}

data class MtpScanSummary(
    val scannedItems: Int = 0,
    val processedItems: Int = 0,
    val advertisedBytes: Long = 0,
    val visibleCategories: Set<ConsentCategory> = emptySet(),
    val downloadDirectoryVisible: Boolean = false,
    val phoneSyncDirectoryVisible: Boolean = false,
    val fullObjectSupported: Boolean? = null,
    val partialObjectSupported: Boolean? = null,
    val partialObject64Supported: Boolean? = null,
    val mediaItemsVisible: Int = 0,
    val mediaItemsTransferred: Int = 0,
    val mediaItemsAlreadyCollected: Int = 0,
    val mediaItemsNotAuthorized: Int = 0,
    val mediaItemsFailed: Int = 0,
    val backupCandidatesVisible: Int = 0,
    val backupCandidateBytes: Long = 0,
    val enumerationFailures: Int = 0,
    val enumerationErrors: List<String> = emptyList(),
)

data class SyncResult(
    val status: SyncStatus,
    val transferredItems: Int,
    val skippedItems: Int,
    val failedItems: Int,
    val bytesTransferred: Long,
    val error: String? = null,
    val mtpScan: MtpScanSummary? = null,
    val recoveryInventory: RecoveryInventoryResult? = null,
)

private data class MtpCandidate(
    val handle: Int,
    val path: String,
    val size: Long,
    val createdAtEpochMillis: Long,
    val modifiedAtEpochMillis: Long,
    val formatCode: Int,
    val protectionStatus: Int,
)

class MtpSyncEngine(
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
        val sessionStartedAtEpochMillis = System.currentTimeMillis()
        val sessionId = auditLog.beginSession(identity.peerId)
        var transferred = 0
        var skipped = 0
        var failed = 0
        var transferredBytes = 0L
        var scannedItems = 0
        val visibleCategories = linkedSetOf<ConsentCategory>()
        var downloadDirectoryVisible = false
        var phoneSyncDirectoryVisible = false
        var fullObjectSupported: Boolean? = null
        var partialObjectSupported: Boolean? = null
        var partialObject64Supported: Boolean? = null
        var mediaItemsVisible = 0
        var mediaItemsTransferred = 0
        var mediaItemsAlreadyCollected = 0
        var mediaItemsNotAuthorized = 0
        var mediaItemsFailed = 0
        var backupCandidatesVisible = 0
        var backupCandidateBytes = 0L
        var advertisedBytes = 0L
        var processedItems = 0
        val candidates = mutableListOf<MtpCandidate>()
        val recoveryItems = mutableListOf<RecoveryInventoryItem>()
        val inventoriedHandles = mutableSetOf<Int>()

        fun inventory(candidate: MtpCandidate, item: RecoveryInventoryItem) {
            recoveryItems += item
            inventoriedHandles += candidate.handle
        }

        val mtpSession = sourceResolver.openMtp(source.device)
        if (mtpSession == null) {
            val error = "The source is not exposing an MTP/PTP data connection."
            auditLog.finishSession(sessionId, SyncStatus.FAILED, 0, 0, error)
            val recoveryInventory = recoveryInventoryWriter.write(
                peerId = identity.peerId,
                sourceName = source.detected.displayName,
                sourcePlatform = source.detected.platform,
                recoveryDeviceType = recoveryDeviceType,
                sessionStatus = SyncStatus.FAILED,
                sessionStartedAtEpochMillis = sessionStartedAtEpochMillis,
                sessionCompletedAtEpochMillis = System.currentTimeMillis(),
                items = recoveryItems,
            )
            return SyncResult(
                SyncStatus.FAILED,
                0,
                0,
                0,
                0,
                error,
                recoveryInventory = recoveryInventory,
            )
        }

        return try {
            mtpSession.use { session ->
                runCatching { session.device.deviceInfo }.getOrNull()?.let { deviceInfo ->
                    fullObjectSupported = deviceInfo.isOperationSupported(
                        MtpConstants.OPERATION_GET_OBJECT,
                    )
                    partialObjectSupported = deviceInfo.isOperationSupported(
                        MtpConstants.OPERATION_GET_PARTIAL_OBJECT,
                    )
                    partialObject64Supported = deviceInfo.isOperationSupported(
                        MtpConstants.OPERATION_GET_PARTIAL_OBJECT_64,
                    )
                }
                val enumerationErrors = walkObjects(session.device) { candidate ->
                    candidates += candidate
                    scannedItems += 1
                    advertisedBytes += candidate.size
                    publishProgress(
                        currentItem = candidate.path,
                        transferred = transferred,
                        skipped = skipped,
                        failed = failed,
                        transferredBytes = transferredBytes,
                        phase = SyncPhase.DISCOVERING,
                        discoveredItems = scannedItems,
                        processedItems = processedItems,
                        totalItems = 0,
                        advertisedBytes = advertisedBytes,
                        onProgress = onProgress,
                    )
                }
                enumerationErrors.forEachIndexed { index, detail ->
                    recoveryItems += RecoveryInventoryItem(
                        sourcePath = "/MTP enumeration failure/${index + 1}",
                        status = RecoveryItemStatus.FAILED,
                        error = detail,
                    )
                }
                failed += enumerationErrors.size
                publishProgress(
                    currentItem = null,
                    transferred = transferred,
                    skipped = skipped,
                    failed = failed,
                    transferredBytes = transferredBytes,
                    phase = SyncPhase.TRANSFERRING,
                    discoveredItems = scannedItems,
                    processedItems = processedItems,
                    totalItems = candidates.size,
                    advertisedBytes = advertisedBytes,
                    onProgress = onProgress,
                )
                candidates.forEach { candidate ->
                    val normalizedPath = candidate.path.replace('\\', '/').lowercase()
                    downloadDirectoryVisible = downloadDirectoryVisible ||
                        "/download/" in normalizedPath || "/downloads/" in normalizedPath
                    phoneSyncDirectoryVisible = phoneSyncDirectoryVisible ||
                        "/phone sync/" in normalizedPath || "/phonesync/" in normalizedPath
                    val category = TransferClassifier.classify(candidate.path)
                    if (isBackupArtifactPath(candidate.path)) {
                        backupCandidatesVisible += 1
                        backupCandidateBytes += candidate.size
                    }
                    if (category == ConsentCategory.PHOTOS_AND_VIDEOS) mediaItemsVisible += 1
                    visibleCategories += category
                    if (category !in authorizedCategories) {
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) {
                            mediaItemsNotAuthorized += 1
                        }
                        inventory(
                            candidate,
                            candidate.toInventoryItem(
                                category = category,
                                status = RecoveryItemStatus.NOT_AUTHORIZED,
                                error = "This category was not authorized for recovery.",
                            ),
                        )
                        processedItems += 1
                        publishProgress(
                            currentItem = candidate.path,
                            transferred = transferred,
                            skipped = skipped,
                            failed = failed,
                            transferredBytes = transferredBytes,
                            phase = SyncPhase.TRANSFERRING,
                            discoveredItems = scannedItems,
                            processedItems = processedItems,
                            totalItems = candidates.size,
                            advertisedBytes = advertisedBytes,
                            onProgress = onProgress,
                        )
                        return@forEach
                    }

                    val fingerprint = DeviceIdentity.sha256(
                        "${identity.peerId}|${candidate.path}|${candidate.size}|${candidate.modifiedAtEpochMillis}",
                    )
                    val priorRecovery = auditLog.completedTransfer(identity.peerId, fingerprint)
                    val priorIntegrity = priorRecovery?.let { prior ->
                        targetMediaStore.verifyStoredItem(
                            destination = prior.destination,
                            expectedBytes = prior.sourceSize.takeIf { it > 0 } ?: candidate.size,
                            expectedSha256 = prior.contentSha256,
                            onBytesRead = { currentBytes ->
                                publishProgress(
                                    currentItem = candidate.path,
                                    transferred = transferred,
                                    skipped = skipped,
                                    failed = failed,
                                    transferredBytes = transferredBytes,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = candidate.size,
                                    phase = SyncPhase.VERIFYING,
                                    discoveredItems = scannedItems,
                                    processedItems = processedItems,
                                    totalItems = candidates.size,
                                    advertisedBytes = advertisedBytes,
                                    onProgress = onProgress,
                                )
                            },
                        )
                    }
                    if (priorRecovery != null && priorIntegrity != null) {
                        auditLog.updateTransferIntegrity(
                            transferId = priorRecovery.id,
                            sourceSize = candidate.size,
                            sourceModifiedAtEpochMillis = candidate.modifiedAtEpochMillis,
                            bytesTransferred = priorIntegrity.bytes,
                            contentSha256 = priorIntegrity.sha256,
                        )
                        skipped += 1
                        processedItems += 1
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) {
                            mediaItemsAlreadyCollected += 1
                        }
                        inventory(
                            candidate,
                            candidate.toInventoryItem(
                                category = category,
                                status = RecoveryItemStatus.ALREADY_RECOVERED,
                                destination = priorRecovery.destination,
                                recoveredBytes = priorIntegrity.bytes,
                                contentSha256 = priorIntegrity.sha256,
                            ),
                        )
                        publishProgress(
                            currentItem = candidate.path,
                            transferred = transferred,
                            skipped = skipped,
                            failed = failed,
                            transferredBytes = transferredBytes,
                            phase = SyncPhase.TRANSFERRING,
                            discoveredItems = scannedItems,
                            processedItems = processedItems,
                            totalItems = candidates.size,
                            advertisedBytes = advertisedBytes,
                            onProgress = onProgress,
                        )
                        return@forEach
                    }

                    var activeReadAttempt: MtpReadAttempt? = null
                    val transfer = runCatching {
                        targetMediaStore.importMtpObject(
                            mtpDevice = session.device,
                            objectHandle = candidate.handle,
                            displayName = candidate.path.substringAfterLast('/').ifBlank { "recovered-artifact" },
                            category = category,
                            sourceName = source.detected.displayName,
                            modifiedAtEpochMillis = candidate.modifiedAtEpochMillis,
                            expectedBytes = candidate.size,
                            onBytesTransferred = { currentBytes ->
                                publishProgress(
                                    currentItem = candidate.path,
                                    transferred = transferred,
                                    skipped = skipped,
                                    failed = failed,
                                    transferredBytes = transferredBytes + currentBytes,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = candidate.size,
                                    phase = SyncPhase.TRANSFERRING,
                                    discoveredItems = scannedItems,
                                    processedItems = processedItems,
                                    totalItems = candidates.size,
                                    advertisedBytes = advertisedBytes,
                                    readMethod = activeReadAttempt?.mode,
                                    readAttempt = activeReadAttempt?.attempt ?: 0,
                                    readAttemptsForMethod = activeReadAttempt?.attemptsForMode ?: 0,
                                    onProgress = onProgress,
                                )
                            },
                            onIntegrityBytesRead = { currentBytes ->
                                publishProgress(
                                    currentItem = candidate.path,
                                    transferred = transferred,
                                    skipped = skipped,
                                    failed = failed,
                                    transferredBytes = transferredBytes,
                                    currentItemBytes = currentBytes,
                                    currentItemTotal = candidate.size,
                                    phase = SyncPhase.VERIFYING,
                                    discoveredItems = scannedItems,
                                    processedItems = processedItems,
                                    totalItems = candidates.size,
                                    advertisedBytes = advertisedBytes,
                                    onProgress = onProgress,
                                )
                            },
                            onReadAttempt = { attempt ->
                                activeReadAttempt = attempt
                                publishProgress(
                                    currentItem = candidate.path,
                                    transferred = transferred,
                                    skipped = skipped,
                                    failed = failed,
                                    transferredBytes = transferredBytes,
                                    phase = SyncPhase.TRANSFERRING,
                                    discoveredItems = scannedItems,
                                    processedItems = processedItems,
                                    totalItems = candidates.size,
                                    advertisedBytes = advertisedBytes,
                                    readMethod = attempt.mode,
                                    readAttempt = attempt.attempt,
                                    readAttemptsForMethod = attempt.attemptsForMode,
                                    onProgress = onProgress,
                                )
                            },
                        )
                    }
                    transfer.onSuccess { result ->
                        val contentMatch = auditLog.completedTransferByContent(identity.peerId, result.sha256, category)
                        val contentMatchIntegrity = contentMatch?.let { existing ->
                            targetMediaStore.verifyStoredItem(
                                destination = existing.destination,
                                expectedBytes = existing.bytesTransferred,
                                expectedSha256 = result.sha256,
                            )
                        }
                        if (contentMatch != null && contentMatchIntegrity != null) {
                            targetMediaStore.discardStoredItem(result.uri)
                            auditLog.recordTransferAlias(identity.peerId, fingerprint, contentMatch.id)
                            skipped += 1
                            if (category == ConsentCategory.PHOTOS_AND_VIDEOS) {
                                mediaItemsAlreadyCollected += 1
                            }
                            inventory(
                                candidate,
                                candidate.toInventoryItem(
                                    category = category,
                                    status = RecoveryItemStatus.ALREADY_RECOVERED,
                                    destination = contentMatch.destination,
                                    recoveredBytes = contentMatchIntegrity.bytes,
                                    contentSha256 = contentMatchIntegrity.sha256,
                                ),
                            )
                        } else {
                            transferred += 1
                            if (category == ConsentCategory.PHOTOS_AND_VIDEOS) {
                                mediaItemsTransferred += 1
                            }
                            transferredBytes += result.bytesWritten
                            auditLog.recordTransfer(
                                sessionId = sessionId,
                                peerId = identity.peerId,
                                sourceFingerprint = fingerprint,
                                category = category,
                                sourceItem = candidate.path,
                                destination = result.uri.toString(),
                                bytesTransferred = result.bytesWritten,
                                status = TransferStatus.COMPLETED,
                                sourceSize = candidate.size,
                                sourceModifiedAtEpochMillis = candidate.modifiedAtEpochMillis,
                                contentSha256 = result.sha256,
                            )
                            inventory(
                                candidate,
                                candidate.toInventoryItem(
                                    category = category,
                                    status = RecoveryItemStatus.RECOVERED,
                                    destination = result.uri.toString(),
                                    recoveredBytes = result.bytesWritten,
                                    contentSha256 = result.sha256,
                                ),
                            )
                        }
                    }.onFailure { throwable ->
                        failed += 1
                        if (category == ConsentCategory.PHOTOS_AND_VIDEOS) {
                            mediaItemsFailed += 1
                        }
                        auditLog.recordTransfer(
                            sessionId = sessionId,
                            peerId = identity.peerId,
                            sourceFingerprint = fingerprint,
                            category = category,
                            sourceItem = candidate.path,
                            destination = null,
                            bytesTransferred = 0,
                            status = TransferStatus.FAILED,
                            sourceSize = candidate.size,
                            sourceModifiedAtEpochMillis = candidate.modifiedAtEpochMillis,
                            error = throwable.message ?: throwable.javaClass.simpleName,
                        )
                        inventory(
                            candidate,
                            candidate.toInventoryItem(
                                category = category,
                                status = RecoveryItemStatus.FAILED,
                                error = throwable.message ?: throwable.javaClass.simpleName,
                            ),
                        )
                    }
                    processedItems += 1
                    publishProgress(
                        currentItem = candidate.path,
                        transferred = transferred,
                        skipped = skipped,
                        failed = failed,
                        transferredBytes = transferredBytes,
                        currentItemBytes = candidate.size,
                        currentItemTotal = candidate.size,
                        phase = SyncPhase.TRANSFERRING,
                        discoveredItems = scannedItems,
                        processedItems = processedItems,
                        totalItems = candidates.size,
                        advertisedBytes = advertisedBytes,
                        onProgress = onProgress,
                    )
                }
                publishProgress(
                    currentItem = null,
                    transferred = transferred,
                    skipped = skipped,
                    failed = failed,
                    transferredBytes = transferredBytes,
                    phase = SyncPhase.COMPLETE,
                    discoveredItems = scannedItems,
                    processedItems = processedItems,
                    totalItems = candidates.size,
                    advertisedBytes = advertisedBytes,
                    onProgress = onProgress,
                )
            }
            val transferStatus = if (failed == 0) SyncStatus.COMPLETED else SyncStatus.PARTIAL
            val recoveryInventory = recoveryInventoryWriter.write(
                peerId = identity.peerId,
                sourceName = source.detected.displayName,
                sourcePlatform = source.detected.platform,
                recoveryDeviceType = recoveryDeviceType,
                sessionStatus = transferStatus,
                sessionStartedAtEpochMillis = sessionStartedAtEpochMillis,
                sessionCompletedAtEpochMillis = System.currentTimeMillis(),
                items = recoveryItems,
            )
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
                mtpScan = MtpScanSummary(
                    scannedItems = scannedItems,
                    processedItems = processedItems,
                    advertisedBytes = advertisedBytes,
                    visibleCategories = visibleCategories,
                    downloadDirectoryVisible = downloadDirectoryVisible,
                    phoneSyncDirectoryVisible = phoneSyncDirectoryVisible,
                    fullObjectSupported = fullObjectSupported,
                    partialObjectSupported = partialObjectSupported,
                    partialObject64Supported = partialObject64Supported,
                    mediaItemsVisible = mediaItemsVisible,
                    mediaItemsTransferred = mediaItemsTransferred,
                    mediaItemsAlreadyCollected = mediaItemsAlreadyCollected,
                    mediaItemsNotAuthorized = mediaItemsNotAuthorized,
                    mediaItemsFailed = mediaItemsFailed,
                    backupCandidatesVisible = backupCandidatesVisible,
                    backupCandidateBytes = backupCandidateBytes,
                    enumerationFailures = recoveryItems.count {
                        it.sourcePath.startsWith("/MTP enumeration failure/")
                    },
                    enumerationErrors = recoveryItems
                        .filter { it.sourcePath.startsWith("/MTP enumeration failure/") }
                        .mapNotNull(RecoveryInventoryItem::error),
                ),
                recoveryInventory = recoveryInventory,
            )
        } catch (throwable: Throwable) {
            val error = throwable.message ?: throwable.javaClass.simpleName
            candidates
                .filterNot { it.handle in inventoriedHandles }
                .forEach { candidate ->
                    recoveryItems += candidate.toInventoryItem(
                        category = TransferClassifier.classify(candidate.path),
                        status = RecoveryItemStatus.NOT_RECOVERED,
                        error = error,
                    )
                }
            val status = if (transferred > 0) SyncStatus.PARTIAL else SyncStatus.FAILED
            val recoveryInventory = recoveryInventoryWriter.write(
                peerId = identity.peerId,
                sourceName = source.detected.displayName,
                sourcePlatform = source.detected.platform,
                recoveryDeviceType = recoveryDeviceType,
                sessionStatus = status,
                sessionStartedAtEpochMillis = sessionStartedAtEpochMillis,
                sessionCompletedAtEpochMillis = System.currentTimeMillis(),
                items = recoveryItems,
            )
            val combinedError = recoveryInventory.error
                ?.let { "$error; recovery inventory also failed: $it" }
                ?: error
            auditLog.finishSession(
                sessionId,
                status,
                transferred,
                transferredBytes,
                combinedError,
            )
            SyncResult(
                status = status,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed + 1,
                bytesTransferred = transferredBytes,
                error = combinedError,
                mtpScan = MtpScanSummary(
                    scannedItems = scannedItems,
                    processedItems = processedItems,
                    advertisedBytes = advertisedBytes,
                    visibleCategories = visibleCategories,
                    downloadDirectoryVisible = downloadDirectoryVisible,
                    phoneSyncDirectoryVisible = phoneSyncDirectoryVisible,
                    fullObjectSupported = fullObjectSupported,
                    partialObjectSupported = partialObjectSupported,
                    partialObject64Supported = partialObject64Supported,
                    mediaItemsVisible = mediaItemsVisible,
                    mediaItemsTransferred = mediaItemsTransferred,
                    mediaItemsAlreadyCollected = mediaItemsAlreadyCollected,
                    mediaItemsNotAuthorized = mediaItemsNotAuthorized,
                    mediaItemsFailed = mediaItemsFailed,
                    backupCandidatesVisible = backupCandidatesVisible,
                    backupCandidateBytes = backupCandidateBytes,
                    enumerationFailures = recoveryItems.count {
                        it.sourcePath.startsWith("/MTP enumeration failure/")
                    },
                    enumerationErrors = recoveryItems
                        .filter { it.sourcePath.startsWith("/MTP enumeration failure/") }
                        .mapNotNull(RecoveryInventoryItem::error),
                ),
                recoveryInventory = recoveryInventory,
            )
        }
    }

    private fun walkObjects(mtpDevice: MtpDevice, visit: (MtpCandidate) -> Unit): List<String> {
        val visited = mutableSetOf<Int>()
        val errors = mutableListOf<String>()
        val storageIds = mtpDevice.storageIds
        if (storageIds == null) {
            return listOf("The external device did not return its storage list. Keep it unlocked and retry.")
        }
        if (storageIds.isEmpty()) {
            return listOf(
                "The external device reported no MTP/PTP storage. Unlock it, enable file/data access, and retry.",
            )
        }
        storageIds.forEach { storageId ->
            walkChildren(mtpDevice, storageId, ROOT_OBJECT_HANDLE, "", visited, errors, visit)
        }
        return errors
    }

    private fun walkChildren(
        mtpDevice: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        parentPath: String,
        visited: MutableSet<Int>,
        errors: MutableList<String>,
        visit: (MtpCandidate) -> Unit,
    ) {
        val handles = mtpDevice.getObjectHandles(storageId, 0, parentHandle)
        if (handles == null) {
            errors += "The external device stopped enumerating ${parentPath.ifBlank { "storage $storageId" }}."
            return
        }
        handles.forEach { handle ->
            if (!visited.add(handle)) return@forEach
            val objectInfo = mtpDevice.getObjectInfo(handle)
            if (objectInfo == null) {
                errors += "The external device did not return metadata for object $handle in " +
                    parentPath.ifBlank { "storage $storageId" } + "."
                return@forEach
            }
            val name = objectInfo.name?.takeIf(String::isNotBlank) ?: "object-$handle"
            val path = "$parentPath/${sanitizeSourcePathSegment(name)}"
            if (objectInfo.format == MtpConstants.FORMAT_ASSOCIATION) {
                walkChildren(mtpDevice, storageId, handle, path, visited, errors, visit)
            } else {
                visit(objectInfo.toCandidate(handle, path))
            }
        }
    }

    private fun MtpObjectInfo.toCandidate(handle: Int, path: String): MtpCandidate {
        return MtpCandidate(
            handle = handle,
            path = path,
            size = compressedSizeLong.coerceAtLeast(0),
            createdAtEpochMillis = dateCreated.coerceAtLeast(0),
            modifiedAtEpochMillis = dateModified.coerceAtLeast(0),
            formatCode = format,
            protectionStatus = protectionStatus,
        )
    }

    private fun MtpCandidate.toInventoryItem(
        category: ConsentCategory,
        status: RecoveryItemStatus,
        destination: String? = null,
        recoveredBytes: Long = 0,
        contentSha256: String? = null,
        error: String? = null,
    ): RecoveryInventoryItem {
        return RecoveryInventoryItem(
            sourcePath = path,
            category = category,
            sourceSize = size,
            sourceCreatedAtEpochMillis = createdAtEpochMillis,
            sourceModifiedAtEpochMillis = modifiedAtEpochMillis,
            mtpFormatCode = formatCode,
            mtpProtectionStatus = protectionStatus,
            status = status,
            destination = destination,
            recoveredBytes = recoveredBytes,
            contentSha256 = contentSha256,
            error = error,
            sensitive = category == ConsentCategory.PASSWORD_EXPORTS,
        )
    }

    private fun publishProgress(
        currentItem: String?,
        transferred: Int,
        skipped: Int,
        failed: Int,
        transferredBytes: Long,
        currentItemBytes: Long = 0,
        currentItemTotal: Long = 0,
        phase: SyncPhase = SyncPhase.TRANSFERRING,
        discoveredItems: Int = 0,
        processedItems: Int = 0,
        totalItems: Int = 0,
        advertisedBytes: Long = 0,
        readMethod: MtpReadMode? = null,
        readAttempt: Int = 0,
        readAttemptsForMethod: Int = 0,
        onProgress: (SyncProgress) -> Unit,
    ) {
        onProgress(
            SyncProgress(
                currentItem = currentItem,
                transferredItems = transferred,
                skippedItems = skipped,
                failedItems = failed,
                bytesTransferred = transferredBytes,
                currentItemBytes = currentItemBytes,
                currentItemTotal = currentItemTotal,
                phase = phase,
                discoveredItems = discoveredItems,
                processedItems = processedItems,
                totalItems = totalItems,
                advertisedBytes = advertisedBytes,
                readMethod = readMethod,
                readAttempt = readAttempt,
                readAttemptsForMethod = readAttemptsForMethod,
            ),
        )
    }

    private fun sanitizeSourcePathSegment(value: String): String {
        return value.replace('/', '_').replace('\\', '_').take(255).ifBlank { "source-item" }
    }

    private companion object {
        const val ROOT_OBJECT_HANDLE = 0
    }
}

internal fun isBackupArtifactPath(path: String): Boolean {
    val normalized = "/" + path.replace('\\', '/').trim('/').lowercase()
    val fileName = normalized.substringAfterLast('/')
    val extension = fileName.substringAfterLast('.', "")
    return fileName in setOf(
        "backup-manifest.json",
        "recoverbybackup-manifest.json",
        "manifest.db",
    ) ||
        extension in setOf("ab", "bak", "backup") ||
        (extension == "zip" && listOf("backup", "export", "recover", "phone sync").any(normalized::contains)) ||
        listOf("/backup/", "/backups/", "/exports/", "/phone sync/", "/phonesync/")
            .any(normalized::contains)
}