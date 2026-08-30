package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.net.Uri

enum class BackupTargetType {
    PHONE_DOWNLOADS,
    DOCUMENT_TREE,
    ONEDRIVE,
    GOOGLE_DRIVE,
    OTHER_APP,
}

data class ProviderBackupTarget(
    val label: String,
    val packageName: String?,
)

fun BackupTargetType.providerTarget(): ProviderBackupTarget? = when (this) {
    BackupTargetType.ONEDRIVE -> ProviderBackupTarget("OneDrive", "com.microsoft.skydrive")
    BackupTargetType.GOOGLE_DRIVE -> ProviderBackupTarget("Google Drive", "com.google.android.apps.docs")
    BackupTargetType.OTHER_APP -> ProviderBackupTarget("Android app chooser", null)
    BackupTargetType.PHONE_DOWNLOADS,
    BackupTargetType.DOCUMENT_TREE,
    -> null
}

fun BackupTargetType.primaryActionLabel(): String = when (this) {
    BackupTargetType.PHONE_DOWNLOADS -> "Back up to this phone"
    BackupTargetType.DOCUMENT_TREE -> "Back up to selected folder"
    BackupTargetType.ONEDRIVE -> "Push to OneDrive"
    BackupTargetType.GOOGLE_DRIVE -> "Push to Google Drive"
    BackupTargetType.OTHER_APP -> "Choose app and push"
}

data class TargetSelection(
    val type: BackupTargetType,
    val name: String,
    val uri: Uri? = null,
)

class TargetSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TargetSelection? {
        val uri = preferences.getString(KEY_URI, null)?.let(Uri::parse)
        val name = preferences.getString(KEY_NAME, null) ?: "selected folder"
        val type = preferences.getString(KEY_TYPE, null)
            ?.let { storedType -> runCatching { BackupTargetType.valueOf(storedType) }.getOrNull() }
            ?: if (uri != null) BackupTargetType.DOCUMENT_TREE else return null
        if (type == BackupTargetType.DOCUMENT_TREE && uri == null) return null
        return TargetSelection(type = type, name = name, uri = uri)
    }

    fun save(selection: TargetSelection) {
        preferences.edit()
            .putString(KEY_TYPE, selection.type.name)
            .putString(KEY_URI, selection.uri?.toString())
            .putString(KEY_NAME, selection.name)
            .apply()
    }

    fun saveFolder(uri: Uri, name: String) {
        save(
            TargetSelection(
                type = BackupTargetType.DOCUMENT_TREE,
                name = name,
                uri = uri,
            ),
        )
    }

    fun saveProvider(type: BackupTargetType, name: String) {
        require(type in PROVIDER_TYPES) { "A provider target is required." }
        preferences.edit()
            .putString(KEY_TYPE, type.name)
            .remove(KEY_URI)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_TYPE).remove(KEY_URI).remove(KEY_NAME).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "backup_target_selection"
        const val KEY_TYPE = "target_type"
        const val KEY_URI = "target_uri"
        const val KEY_NAME = "target_name"
        val PROVIDER_TYPES = setOf(
            BackupTargetType.ONEDRIVE,
            BackupTargetType.GOOGLE_DRIVE,
            BackupTargetType.OTHER_APP,
        )
    }
}
