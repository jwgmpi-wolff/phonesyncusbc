package com.jerrywolff.phonesyncusbc.data

import com.jerrywolff.phonesyncusbc.domain.ConsentCategory

data class FolderMetadata(
    val folderPath: String,
    val folderLabel: String,
    val collectionLabel: String,
    val recordLabel: String,
)

fun deriveFolderMetadata(
    sourcePath: String,
    category: ConsentCategory,
    nestedPath: String? = null,
): FolderMetadata {
    val normalizedSource = sourcePath.replace('\\', '/').trim('/')
    val sourceFolders = normalizedSource.substringBeforeLast('/', "").split('/').filter(String::isNotBlank)
    val nestedFolders = nestedPath
        ?.replace('\\', '/')
        ?.trim('/')
        ?.substringBeforeLast('/', "")
        ?.split('/')
        ?.filter(String::isNotBlank)
        .orEmpty()
    val categoryLabel = humanizeFolderLabel(category.name)
    val allFolders = sourceFolders + nestedFolders
    val folderLabel = allFolders.lastOrNull()?.let(::humanizeFolderLabel) ?: categoryLabel
    val meaningful = allFolders.filterNot { folder -> isGenericFolder(folder, category) }
    val collectionLabel = meaningful.lastOrNull()?.let(::humanizeFolderLabel) ?: categoryLabel
    val nestedLabel = nestedFolders.lastOrNull()?.let(::humanizeFolderLabel)
    return FolderMetadata(
        folderPath = allFolders.joinToString("/", prefix = "/").ifBlank { "/" },
        folderLabel = folderLabel,
        collectionLabel = collectionLabel,
        recordLabel = nestedLabel ?: collectionLabel,
    )
}

fun humanizeFolderLabel(value: String): String {
    return value
        .replace(Regex("[_-]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
        .ifBlank { "Unlabeled" }
}

private fun isGenericFolder(folder: String, category: ConsentCategory): Boolean {
    val normalized = folder.lowercase().replace(Regex("[^a-z0-9]"), "")
    val categoryName = category.name.lowercase().replace(Regex("[^a-z0-9]"), "")
    return normalized == categoryName || normalized in GENERIC_FOLDERS
}

private val GENERIC_FOLDERS = setOf(
    "download",
    "downloads",
    "export",
    "exports",
    "phonesync",
    "internalstorage",
    "phone",
    "storage",
    "data",
)