package com.jerrywolff.phonesyncusbc.domain

object TransferClassifier {
    private val imageExtensions = setOf("bmp", "dng", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    private val videoExtensions = setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
    private val passwordExportMarkers = listOf(
        "/password/",
        "/passwords/",
        "/password_exports/",
        "/password-exports/",
        "/credential/",
        "/credentials/",
        "/credential-backups/",
        "/browser-data/",
        "keepass",
        "bitwarden",
        "1password",
        "lastpass",
        "dashlane",
        "protonpass",
        "enpass",
        "passwordsafe",
        "password-vault",
        "credential-backup",
        "credential_store",
        "keychain",
    )
    private val passkeyExportMarkers = listOf(
        "/passkey/",
        "/passkeys/",
        "/passkey_exports/",
        "/passkey-exports/",
        "passkey-backup",
        "passkey_backup",
        "passkey-export",
        "passkey_export",
        "webauthn-backup",
        "webauthn_export",
        "fido2-backup",
        "fido2_export",
    )
    private val portableCredentialFileNames = setOf(
        "login data",
        "logins.json",
        "key3.db",
        "key4.db",
        "signons.sqlite",
        "passwords.csv",
        "passwords.json",
        "credentials.csv",
        "credentials.json",
        "passkeys.json",
        "passkeys.zip",
        "webauthn-credentials.json",
        "fido2-credentials.json",
    )

    fun classify(path: String): ConsentCategory {
        val normalizedPath = "/" + path.replace('\\', '/').lowercase().trimStart('/')
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val fileName = normalizedPath.substringAfterLast('/')

        return when {
            isPasswordExport(normalizedPath, fileName) -> ConsentCategory.PASSWORD_EXPORTS
            isSmsExport(normalizedPath, fileName) -> ConsentCategory.SMS_EXPORTS
            isCallLogExport(normalizedPath, fileName) -> ConsentCategory.CALL_LOGS
            isCalendarExport(normalizedPath, fileName) -> ConsentCategory.CALENDAR
            isVoicemailExport(normalizedPath, fileName) -> ConsentCategory.VOICEMAIL_EXPORTS
            isChatExport(normalizedPath, fileName) -> ConsentCategory.CHAT_EXPORTS
            isEmailExport(normalizedPath, fileName) -> ConsentCategory.EMAIL_EXPORTS
            isNotificationExport(normalizedPath, fileName) -> ConsentCategory.NOTIFICATION_EXPORTS
            extension == "vcf" || extension == "vcard" -> ConsentCategory.CONTACTS
            extension in imageExtensions || extension in videoExtensions -> ConsentCategory.PHOTOS_AND_VIDEOS
            isSystemInformation(normalizedPath, fileName) -> ConsentCategory.SYSTEM_INFORMATION
            isLog(normalizedPath, fileName) -> ConsentCategory.LOGS
            isRegistryHive(normalizedPath, fileName) -> ConsentCategory.CONFIGURATION
            isConfiguration(normalizedPath, fileName) -> ConsentCategory.CONFIGURATION
            isApplicationData(normalizedPath, extension) -> ConsentCategory.APPLICATION_DATA
            else -> ConsentCategory.DOCUMENTS
        }
    }

    private fun isSystemInformation(path: String, fileName: String): Boolean {
        val systemMarker = listOf(
            "/system-info/",
            "/system_information/",
            "/device-info/",
            "/diagnostics/",
            "system-info",
            "system_information",
            "device-info",
            "device_information",
            "bugreport",
            "build.prop",
        ).any { it in path || it in fileName }
        return systemMarker && fileName.substringAfterLast('.', "") in setOf(
            "json",
            "xml",
            "csv",
            "txt",
            "html",
            "prop",
            "zip",
        )
    }

    private fun isLog(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val logMarker = "/log/" in path || "/logs/" in path || "/winevt/logs/" in path ||
            "crash" in fileName || "tombstone" in fileName || "diagnostic" in fileName
        return extension in setOf("log", "evtx", "etl") ||
            (logMarker && extension in setOf("txt", "json", "xml", "zip", "dmp"))
    }

    private fun isRegistryHive(path: String, fileName: String): Boolean {
        val registryPath = "/system32/config/" in path || "/registry/" in path
        return registryPath || fileName in setOf(
            "sam",
            "security",
            "software",
            "system",
            "default",
            "ntuser.dat",
            "usrclass.dat",
        )
    }

    private fun isConfiguration(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val configurationPath = listOf("/config/", "/configs/", "/configuration/", "/settings/")
            .any(path::contains)
        return extension in setOf("cfg", "conf", "config", "ini", "plist", "properties", "toml", "yaml", "yml") ||
            (configurationPath && extension in setOf("json", "xml", "txt", "zip"))
    }

    private fun isApplicationData(path: String, extension: String): Boolean {
        val applicationPath = listOf(
            "/android/data/",
            "/application-data/",
            "/application_data/",
            "/app-data/",
            "/app_data/",
            "/apps/",
        ).any(path::contains)
        return applicationPath || extension in setOf("db", "sqlite", "sqlite3")
    }

    private fun isSmsExport(path: String, fileName: String): Boolean {
        return (
            "/sms/" in path || "/sms_exports/" in path || "/sms-exports/" in path ||
                "/sms exports/" in path || "sms-export" in path || "sms_backup" in path ||
                "sms-backup" in path || "sms backup" in path || "smsbackup" in path ||
                fileName.startsWith("sms-mms-")
            ) &&
            fileName.substringAfterLast('.', "") in setOf("xml", "json", "csv", "txt", "html", "zip")
    }

    private fun isCallLogExport(path: String, fileName: String): Boolean {
        return (
            "/call_logs/" in path || "/call-logs/" in path || "call-log" in fileName ||
                "call_history" in fileName
            ) && fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "zip")
    }

    private fun isCalendarExport(path: String, fileName: String): Boolean {
        return (
            "/calendar/" in path || "/calendars/" in path || fileName.startsWith("calendar-")
            ) && fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "ics", "ical", "zip")
    }

    private fun isPasswordExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        if (fileName in portableCredentialFileNames) return true
        if (isPasskeyRelatedArtifact(path)) return true
        if (extension in setOf("kdb", "kdbx", "psafe3", "enpassbackup", "1pux", "crd", "vcrd", "keychain-db")) {
            return true
        }
        val passwordExportName = passwordExportMarkers.any { it in path || it in fileName }
        return passwordExportName && extension in setOf(
            "json",
            "csv",
            "xml",
            "zip",
            "bak",
            "backup",
            "db",
            "sqlite",
            "sqlite3",
            "plist",
        )
    }

    fun isPasskeyRelatedArtifact(path: String): Boolean {
        val normalizedPath = "/" + path.replace('\\', '/').lowercase().trimStart('/')
        val fileName = normalizedPath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "")
        val explicitlyNamed = fileName in setOf(
            "passkeys.json",
            "passkeys.zip",
            "webauthn-credentials.json",
            "fido2-credentials.json",
        )
        val marked = passkeyExportMarkers.any { it in normalizedPath || it in fileName }
        return (explicitlyNamed || marked) && extension in setOf(
            "json",
            "cbor",
            "zip",
            "bak",
            "backup",
            "bin",
            "db",
            "sqlite",
            "sqlite3",
            "1pux",
            "kdbx",
        )
    }

    private fun isVoicemailExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val voicemailName = listOf(
            "/voicemail/",
            "/voicemails/",
            "/voicemail_exports/",
            "/voicemail-exports/",
            "visual-voicemail",
            "visual_voicemail",
            "voicemail-",
            "voicemail_",
        ).any { it in path || it in fileName }
        return voicemailName && extension in setOf(
            "3gp",
            "3gpp",
            "aac",
            "aif",
            "aiff",
            "amr",
            "au",
            "awb",
            "caf",
            "evrc",
            "flac",
            "m4a",
            "m4b",
            "mp3",
            "mp4",
            "oga",
            "ogg",
            "opus",
            "qcp",
            "snd",
            "wav",
            "weba",
            "wma",
            "csv",
            "html",
            "json",
            "txt",
            "vvm",
            "xml",
            "zip",
        )
    }

    private fun isChatExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        val chatName = listOf(
            "whatsapp",
            "signal",
            "telegram",
            "messenger",
            "microsoft teams",
            "microsoft_teams",
            "teams-export",
            "teams_export",
            "zoom",
            "webex",
            "slack",
            "discord",
            "google chat",
            "google_chat",
            "meeting-chat",
            "meeting_chat",
            "chat",
            "conversation",
            "transcript",
        )
            .any { it in path || it in fileName }
        val explicitExportLocation = listOf("/export", "/backup", "/download", "/phone sync/")
            .any(path::contains)
        if ("whatsapp" in path && extension.startsWith("crypt")) return true
        if ("signal" in path && extension == "backup") return true
        return chatName && extension in setOf(
            "txt",
            "json",
            "html",
            "htm",
            "xml",
            "zip",
            "csv",
            "db",
            "vtt",
            "srt",
            "pdf",
            "docx",
        ) &&
            (extension != "db" || explicitExportLocation)
    }

    private fun isEmailExport(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "")
        if (extension in setOf("eml", "mbox", "msg", "ost", "pst")) return true
        val mailName = listOf("email", "mail", "gmail", "outlook", "thunderbird", "inbox", "sent")
            .any { it in path || it in fileName }
        return mailName && extension in setOf("txt", "html", "json", "xml", "zip")
    }

    private fun isNotificationExport(path: String, fileName: String): Boolean {
        return ("notification" in path || "notifications" in path || "notification" in fileName) &&
            fileName.substringAfterLast('.', "") in setOf("json", "xml", "csv", "txt", "html", "zip")
    }

    fun isVideo(path: String): Boolean {
        return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in videoExtensions
    }

    fun isProtectedPrivateDatabase(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/').lowercase()
        val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
        val privateLocation = listOf(
            "/android/data/",
            "/data/data/",
            "/private/var/mobile/containers/",
        ).any(normalizedPath::contains)
        val privatePasswordStore = passwordExportMarkers.any(normalizedPath::contains)
        val privateVoicemailStore = listOf("voicemail", "dialer", "telecom").any(normalizedPath::contains)
        return privateLocation && (
            privatePasswordStore || privateVoicemailStore || extension in setOf(
                "db",
                "sqlite",
                "sqlite3",
                "kdb",
                "kdbx",
                "psafe3",
                "enpassbackup",
                "1pux",
            )
        )
    }
}