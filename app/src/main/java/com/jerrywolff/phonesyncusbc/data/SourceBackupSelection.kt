package com.jerrywolff.phonesyncusbc.data

fun mergeSourceBackupSelection(
    peerId: String?,
    previousPeerId: String?,
    currentIds: Set<Long>,
    knownIds: Set<Long>,
    selectedIds: Set<Long>,
): Set<Long> {
    if (peerId != previousPeerId) return currentIds
    return (selectedIds intersect currentIds) + (currentIds - knownIds)
}
