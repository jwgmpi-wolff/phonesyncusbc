package com.jerrywolff.phonesyncusbc.data

enum class RecoveryIssueReason {
    NO_SELECTED_SOURCE,
    COLLECTOR_ORIGIN,
    MISSING_SOURCE_PEER,
    DIFFERENT_SOURCE_PEER,
    MISSING_SOURCE_FINGERPRINT,
    TRANSFER_NOT_COMPLETED,
    MISSING_RECOVERED_COPY,
    COPY_FAILED,
}

data class RecoveryIssue(
    val sourceItem: String,
    val reason: RecoveryIssueReason,
    val remediation: String,
    val retryable: Boolean,
)

data class RecoverySelectionPlan(
    val eligibleEntries: List<AuditEntry>,
    val issues: List<RecoveryIssue>,
) {
    val excludedItems: Int = issues.size
}

fun planExternalRecoveryEntries(
    entries: List<AuditEntry>,
    expectedPeerId: String?,
): RecoverySelectionPlan {
    val expectedPeer = expectedPeerId.orEmpty()
    val eligible = mutableListOf<AuditEntry>()
    val issues = mutableListOf<RecoveryIssue>()
    entries.forEach { entry ->
        val issue = entry.recoveryEligibilityIssue(expectedPeer)
        if (issue == null) eligible += entry else issues += issue
    }
    return RecoverySelectionPlan(
        eligibleEntries = eligible.distinctBy(AuditEntry::idempotencyKey),
        issues = issues,
    )
}

fun copyFailureIssue(sourceItem: String, detail: String?): RecoveryIssue {
    val suffix = detail?.takeIf(String::isNotBlank)?.let { " Error: $it" }.orEmpty()
    return RecoveryIssue(
        sourceItem = sourceItem,
        reason = RecoveryIssueReason.COPY_FAILED,
        remediation = "Keep the source and destination available, restore storage permission if prompted, then retry.$suffix",
        retryable = true,
    )
}

private fun AuditEntry.recoveryEligibilityIssue(expectedPeerId: String): RecoveryIssue? {
    return when {
        expectedPeerId.isBlank() -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.NO_SELECTED_SOURCE,
            "Reconnect and select the external source device, then retry recovery.",
            retryable = true,
        )
        isCollectorOwnedSourceItem(sourceItem) || peerId == "local-android" -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.COLLECTOR_ORIGIN,
            "Reacquire this item from the connected external device. Collector-origin data is kept out of external-source archives.",
            retryable = true,
        )
        peerId.isBlank() -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.MISSING_SOURCE_PEER,
            "Reconnect the owning external device and reacquire the item so a source identity is recorded.",
            retryable = true,
        )
        peerId != expectedPeerId -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.DIFFERENT_SOURCE_PEER,
            "Select the external device that owns this item and preserve it as a separate source set.",
            retryable = false,
        )
        sourceFingerprint.isBlank() -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.MISSING_SOURCE_FINGERPRINT,
            "Reconnect the same external device and reacquire the item to generate verifiable source provenance.",
            retryable = true,
        )
        status != TransferStatus.COMPLETED -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.TRANSFER_NOT_COMPLETED,
            "Unlock and reconnect the external device, keep the cable attached, then retry recovery.",
            retryable = true,
        )
        destination.isNullOrBlank() -> RecoveryIssue(
            sourceItem,
            RecoveryIssueReason.MISSING_RECOVERED_COPY,
            "Restore access to the recovered copy or reconnect the external device and reacquire it.",
            retryable = true,
        )
        else -> null
    }
}