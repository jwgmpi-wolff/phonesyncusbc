package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory

fun recoveredCoverageCategories(entries: List<AuditEntry>): Set<ConsentCategory> {
    val categories = entries
        .filter { it.status == TransferStatus.COMPLETED && !it.destination.isNullOrBlank() }
        .mapTo(linkedSetOf()) { it.category }
    if (entries.none(AuditEntry::isMessageContentEvidence)) {
        categories -= ConsentCategory.SMS_EXPORTS
    }
    return categories
}

fun AuditEntry.isMessageContentEvidence(): Boolean {
    if (category != ConsentCategory.SMS_EXPORTS || status != TransferStatus.COMPLETED) return false
    val normalized = sourceItem.replace('\\', '/').lowercase()
    return !normalized.endsWith("/sms.db") &&
        !normalized.endsWith("/sms.db-wal") &&
        !normalized.endsWith("/sms.db-shm") &&
        !normalized.endsWith("/ios-message-attachments.zip")
}