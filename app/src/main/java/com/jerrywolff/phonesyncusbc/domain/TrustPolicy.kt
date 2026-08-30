package com.jerrywolff.phonesyncusbc.domain

enum class ConsentCategory {
    PHOTOS_AND_VIDEOS,
    DOCUMENTS,
    APPLICATION_DATA,
    CONFIGURATION,
    LOGS,
    SYSTEM_INFORMATION,
    CONTACTS,
    CALL_LOGS,
    CALENDAR,
    SELECTED_FOLDERS,
    CLOUD_ACCOUNTS,
    SMS_EXPORTS,
    CHAT_EXPORTS,
    EMAIL_EXPORTS,
    NOTIFICATION_EXPORTS,
    PASSWORD_EXPORTS,
    VOICEMAIL_EXPORTS,
}

data class TrustRecord(
    val peerDeviceId: String,
    val localDeviceId: String,
    val encryptionKeyProof: String,
    val authorizedCategories: Set<ConsentCategory>,
    val revokedAtEpochMillis: Long? = null,
)

data class TrustContext(
    val peerDeviceId: String,
    val localDeviceId: String,
    val encryptionKeyProof: String,
)

enum class ReapprovalReason {
    NO_TRUST_RECORD,
    TRUST_REVOKED,
    SOURCE_DEVICE_IDENTITY_CHANGED,
    LOCAL_DEVICE_IDENTITY_CHANGED,
    ENCRYPTION_KEYS_CHANGED,
    NO_AUTHORIZED_CATEGORIES,
}

sealed interface TrustDecision {
    data object Approved : TrustDecision
    data class ReapprovalRequired(val reason: ReapprovalReason) : TrustDecision
}

object TrustPolicy {
    fun evaluate(record: TrustRecord?, context: TrustContext): TrustDecision {
        val reason = when {
            record == null -> ReapprovalReason.NO_TRUST_RECORD
            record.revokedAtEpochMillis != null -> ReapprovalReason.TRUST_REVOKED
            record.peerDeviceId != context.peerDeviceId -> ReapprovalReason.SOURCE_DEVICE_IDENTITY_CHANGED
            record.localDeviceId != context.localDeviceId -> ReapprovalReason.LOCAL_DEVICE_IDENTITY_CHANGED
            record.encryptionKeyProof != context.encryptionKeyProof -> ReapprovalReason.ENCRYPTION_KEYS_CHANGED
            record.authorizedCategories.isEmpty() -> ReapprovalReason.NO_AUTHORIZED_CATEGORIES
            else -> null
        }

        return reason?.let(TrustDecision::ReapprovalRequired) ?: TrustDecision.Approved
    }
}