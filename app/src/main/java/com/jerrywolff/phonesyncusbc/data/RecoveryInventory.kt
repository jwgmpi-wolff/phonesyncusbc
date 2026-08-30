package com.jerrywolff.phonesyncusbc.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import com.jerrywolff.phonesyncusbc.domain.RecoveryProfiles
import com.jerrywolff.phonesyncusbc.domain.LOGICAL_ACQUISITION_LIMIT
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.domain.TransferClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecoveryItemStatus {
    RECOVERED,
    ALREADY_RECOVERED,
    NOT_AUTHORIZED,
    NOT_RECOVERED,
    FAILED,
}

data class RecoveryInventoryItem(
    val sourcePath: String,
    val category: ConsentCategory? = null,
    val sourceSize: Long = 0,
    val sourceCreatedAtEpochMillis: Long = 0,
    val sourceModifiedAtEpochMillis: Long = 0,
    val mtpFormatCode: Int? = null,
    val mtpProtectionStatus: Int? = null,
    val status: RecoveryItemStatus,
    val destination: String? = null,
    val recoveredBytes: Long = 0,
    val contentSha256: String? = null,
    val error: String? = null,
    val sensitive: Boolean = false,
)

data class RecoveryInventoryResult(
    val uri: Uri? = null,
    val displayName: String? = null,
    val itemCount: Int = 0,
    val passwordArtifactCount: Int = 0,
    val recoveredPasswordArtifactCount: Int = 0,
    val passkeyRelatedArtifactCount: Int = 0,
    val recoveredPasskeyRelatedArtifactCount: Int = 0,
    val error: String? = null,
)

data class RecoveryInventorySummary(
    val discoveredItems: Int,
    val recoveredItems: Int,
    val alreadyRecoveredItems: Int,
    val notRecoveredItems: Int,
    val failedItems: Int,
    val recoveredBytes: Long,
    val accountedBytes: Long,
    val passwordArtifacts: Int,
    val recoveredPasswordArtifacts: Int,
    val passkeyRelatedArtifacts: Int,
    val recoveredPasskeyRelatedArtifacts: Int,
)

fun summarizeRecoveryItems(items: List<RecoveryInventoryItem>): RecoveryInventorySummary {
    return RecoveryInventorySummary(
        discoveredItems = items.size,
        recoveredItems = items.count { it.status == RecoveryItemStatus.RECOVERED },
        alreadyRecoveredItems = items.count { it.status == RecoveryItemStatus.ALREADY_RECOVERED },
        notRecoveredItems = items.count {
            it.status in setOf(RecoveryItemStatus.NOT_AUTHORIZED, RecoveryItemStatus.NOT_RECOVERED)
        },
        failedItems = items.count { it.status == RecoveryItemStatus.FAILED },
        recoveredBytes = items
            .filter { it.status == RecoveryItemStatus.RECOVERED }
            .sumOf { it.recoveredBytes },
        accountedBytes = items
            .filter { it.status in setOf(RecoveryItemStatus.RECOVERED, RecoveryItemStatus.ALREADY_RECOVERED) }
            .sumOf { it.recoveredBytes },
        passwordArtifacts = items.count { it.category == ConsentCategory.PASSWORD_EXPORTS },
        recoveredPasswordArtifacts = items.count {
            it.category == ConsentCategory.PASSWORD_EXPORTS &&
                it.status in setOf(RecoveryItemStatus.RECOVERED, RecoveryItemStatus.ALREADY_RECOVERED)
        },
        passkeyRelatedArtifacts = items.count {
            it.category == ConsentCategory.PASSWORD_EXPORTS &&
                TransferClassifier.isPasskeyRelatedArtifact(it.sourcePath)
        },
        recoveredPasskeyRelatedArtifacts = items.count {
            it.category == ConsentCategory.PASSWORD_EXPORTS &&
                TransferClassifier.isPasskeyRelatedArtifact(it.sourcePath) &&
                it.status in setOf(RecoveryItemStatus.RECOVERED, RecoveryItemStatus.ALREADY_RECOVERED)
        },
    )
}

class RecoveryInventoryWriter(private val context: Context) {
    fun write(
        peerId: String,
        sourceName: String,
        sourcePlatform: SourcePlatform,
        recoveryDeviceType: RecoveryDeviceType,
        sessionStatus: SyncStatus,
        sessionStartedAtEpochMillis: Long,
        sessionCompletedAtEpochMillis: Long,
        items: List<RecoveryInventoryItem>,
    ): RecoveryInventoryResult {
        val displayName = "PhoneSync-Recovery-Inventory-${timestamp()}.json"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Phone Sync/Recovery Inventories",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return RecoveryInventoryResult(error = "Android could not create the recovery inventory.")
        val summary = summarizeRecoveryItems(items)

        return try {
            val document = buildDocument(
                peerId = peerId,
                sourceName = sourceName,
                sourcePlatform = sourcePlatform,
                recoveryDeviceType = recoveryDeviceType,
                sessionStatus = sessionStatus,
                sessionStartedAtEpochMillis = sessionStartedAtEpochMillis,
                sessionCompletedAtEpochMillis = sessionCompletedAtEpochMillis,
                items = items,
            )
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                checkNotNull(output) { "Android could not open the recovery inventory." }
                output.write(document.toString(2).toByteArray(Charsets.UTF_8))
            }
            context.contentResolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            RecoveryInventoryResult(
                uri = destination,
                displayName = displayName,
                itemCount = items.size,
                passwordArtifactCount = summary.passwordArtifacts,
                recoveredPasswordArtifactCount = summary.recoveredPasswordArtifacts,
                passkeyRelatedArtifactCount = summary.passkeyRelatedArtifacts,
                recoveredPasskeyRelatedArtifactCount = summary.recoveredPasskeyRelatedArtifacts,
            )
        } catch (throwable: Throwable) {
            context.contentResolver.delete(destination, null, null)
            RecoveryInventoryResult(
                itemCount = items.size,
                passwordArtifactCount = summary.passwordArtifacts,
                recoveredPasswordArtifactCount = summary.recoveredPasswordArtifacts,
                passkeyRelatedArtifactCount = summary.passkeyRelatedArtifacts,
                recoveredPasskeyRelatedArtifactCount = summary.recoveredPasskeyRelatedArtifacts,
                error = throwable.message ?: throwable.javaClass.simpleName,
            )
        }
    }

    private fun buildDocument(
        peerId: String,
        sourceName: String,
        sourcePlatform: SourcePlatform,
        recoveryDeviceType: RecoveryDeviceType,
        sessionStatus: SyncStatus,
        sessionStartedAtEpochMillis: Long,
        sessionCompletedAtEpochMillis: Long,
        items: List<RecoveryInventoryItem>,
    ): JSONObject {
        val summary = summarizeRecoveryItems(items)
        val profile = RecoveryProfiles.forDevice(recoveryDeviceType)
        val entries = JSONArray()
        items.forEach { item -> entries.put(item.toJson()) }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put(
                "source",
                JSONObject()
                    .put("peerId", peerId)
                    .put("displayName", sourceName)
                    .put("platform", sourcePlatform.name)
                    .put("recoveryDeviceType", recoveryDeviceType.name)
                    .put("accessMethod", "MTP_PTP")
                    .put("readOnly", true)
                    .put("securityControlsBypassed", false),
            )
            .put(
                "recoveryProfile",
                JSONObject()
                    .put("deviceType", recoveryDeviceType.label)
                    .put("purposes", JSONArray(profile.purposes.map { it.label }))
                    .put("recoverableTargets", JSONArray(profile.recoverableTargets))
                    .put("passwordTarget", profile.passwordTarget)
                    .put("passwordHandling", "COPIED_OPAQUE_NO_DECRYPTION")
                    .put("passkeyHandling", "PROVIDER_MANAGED_PRIVATE_KEYS_NOT_EXTRACTED")
                    .put("passkeyRestoreRequired", true)
                    .put("acquisitionType", "READ_ONLY_LOGICAL_MTP_PTP")
                    .put("physicalDiskImageCreated", false)
                    .put("deletedBlockCarvingPerformed", false),
            )
            .put(
                "scope",
                "Only objects advertised by the external device through owner-authorized MTP/PTP access were read. " +
                    "Encrypted, password-protected, locked, and otherwise inaccessible data was not bypassed. " +
                    LOGICAL_ACQUISITION_LIMIT,
            )
            .put(
                "session",
                JSONObject()
                    .put("startedAtEpochMillis", sessionStartedAtEpochMillis)
                    .put("completedAtEpochMillis", sessionCompletedAtEpochMillis)
                    .put("status", sessionStatus.name),
            )
            .put(
                "summary",
                JSONObject()
                    .put("discoveredItems", summary.discoveredItems)
                    .put("recoveredItems", summary.recoveredItems)
                    .put("alreadyRecoveredItems", summary.alreadyRecoveredItems)
                    .put("notRecoveredItems", summary.notRecoveredItems)
                    .put("failedItems", summary.failedItems)
                    .put("recoveredBytes", summary.recoveredBytes)
                    .put("accountedBytes", summary.accountedBytes)
                    .put("passwordArtifacts", summary.passwordArtifacts)
                    .put("recoveredPasswordArtifacts", summary.recoveredPasswordArtifacts)
                    .put("passkeyRelatedArtifacts", summary.passkeyRelatedArtifacts)
                    .put("recoveredPasskeyRelatedArtifacts", summary.recoveredPasskeyRelatedArtifacts)
                    .put("passkeyPrivateKeysExtracted", false),
            )
            .put("items", entries)
    }

    private fun RecoveryInventoryItem.toJson(): JSONObject {
        return JSONObject()
            .put("sourcePath", sourcePath)
            .put("sourceSize", sourceSize)
            .put("sourceCreatedAtEpochMillis", sourceCreatedAtEpochMillis)
            .put("sourceModifiedAtEpochMillis", sourceModifiedAtEpochMillis)
            .put("status", status.name)
            .put("recoveredBytes", recoveredBytes)
            .put("sensitive", sensitive)
            .apply {
                category?.let { put("category", it.name) }
                mtpFormatCode?.let { put("mtpFormatCode", it) }
                mtpProtectionStatus?.let { put("mtpProtectionStatus", it) }
                destination?.let { put("destination", it) }
                contentSha256?.let { put("contentSha256", it) }
                error?.let { put("error", it) }
            }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
}