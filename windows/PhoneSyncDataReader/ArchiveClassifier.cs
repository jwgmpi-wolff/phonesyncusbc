using System.IO;
using System.Globalization;

namespace PhoneSyncDataReader;

public static class ArchiveClassifier
{
    private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".bmp", ".dng", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".tif", ".tiff", ".webp"
    };

    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".3g2", ".3gp", ".avi", ".m4v", ".mkv", ".mov", ".mp4", ".mpeg", ".mpg", ".webm", ".wmv"
    };

    private static readonly string[] SensitiveMarkers =
    {
        "/password/", "/passwords/", "/password_exports/", "/password-exports/", "/credential/",
        "/credentials/", "/credential-backups/", "/browser-data/", "keepass", "bitwarden",
        "1password", "lastpass", "dashlane", "protonpass", "enpass", "passwordsafe",
        "password-vault", "credential-backup", "credential_store", "keychain", "login data",
        "logins.json", "key3.db", "key4.db", "signons.sqlite", "passwords.csv",
        "passwords.json", "credentials.csv", "credentials.json", "/passkey/", "/passkeys/",
        "/passkey_exports/", "/passkey-exports/",
        "passkey-backup", "passkey_export", "webauthn-backup", "webauthn_export",
        "fido2-backup", "fido2_export", "passkeys.json", "webauthn-credentials.json",
        "fido2-credentials.json"
    };

    private static readonly HashSet<string> KnownCategoryFolders = new(StringComparer.OrdinalIgnoreCase)
    {
        "photos_and_videos", "photos-and-videos", "documents", "application_data", "application-data",
        "configuration", "logs", "system_information", "system-information", "contacts", "call_logs",
        "call-logs", "calendar", "sms_exports", "sms-exports", "chat_exports", "chat-exports",
        "email_exports", "email-exports", "notification_exports", "notification-exports",
        "password_exports", "password-exports", "voicemail_exports", "voicemail-exports"
    };

    public static ArchiveClassification Classify(string path)
    {
        var normalized = "/" + Normalize(path).TrimStart('/');
        var extension = Path.GetExtension(path);
        var fileName = Path.GetFileName(path).ToLowerInvariant();
        var sensitive = SensitiveMarkers.Any(normalized.Contains) ||
            extension.Equals(".kdbx", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".1pux", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".psafe3", StringComparison.OrdinalIgnoreCase);
        if (sensitive)
        {
            return new("PASSWORD_EXPORTS", RecordKind.Generic, true, false, false, false);
        }

        var isJson = extension.Equals(".json", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".jsonl", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".ndjson", StringComparison.OrdinalIgnoreCase);

        if (ContainsAny(normalized, "/sms/", "/sms_exports/", "/sms-exports/", "/sms exports/", "sms-mms", "sms_backup", "sms-backup", "sms backup"))
            return new("SMS_EXPORTS", RecordKind.Message, false, isJson, false, false);
        if (ContainsAny(normalized, "whatsapp", "signal", "telegram", "/chat/", "/chats/", "conversation"))
            return new("CHAT_EXPORTS", RecordKind.Message, false, isJson, false, false);
        if (ContainsAny(normalized, "/email/", "/mail/", "outlook", "gmail") || extension is ".eml" or ".mbox" or ".msg" or ".ost" or ".pst")
            return new("EMAIL_EXPORTS", RecordKind.Email, false, isJson, false, false);
        if (extension is ".vcf" or ".vcard" || normalized.Contains("/contacts/"))
            return new("CONTACTS", RecordKind.Contact, false, isJson, false, false);
        if (ContainsAny(normalized, "/call_logs/", "/call-logs/", "call-log", "call_history"))
            return new("CALL_LOGS", RecordKind.Call, false, isJson, false, false);
        if (extension is ".ics" or ".ical" || ContainsAny(normalized, "/calendar/", "/calendars/"))
            return new("CALENDAR", RecordKind.Event, false, isJson, false, false);
        if (normalized.Contains("notification"))
            return new("NOTIFICATION_EXPORTS", RecordKind.Notification, false, isJson, false, false);
        if (ContainsAny(normalized, "/voicemail/", "/voicemails/", "/voicemail_exports/", "/voicemail-exports/", "visual-voicemail", "visual_voicemail", "voicemail-", "voicemail_"))
            return new("VOICEMAIL_EXPORTS", RecordKind.Message, false, isJson, false, false);
        var isImage = ImageExtensions.Contains(extension);
        var isVideo = VideoExtensions.Contains(extension);
        if (isImage || isVideo)
        {
            return new("PHOTOS_AND_VIDEOS", RecordKind.Media, false, false, isImage, isVideo);
        }
        if (extension is ".log" or ".evtx" or ".etl" || ContainsAny(normalized, "/logs/", "/log/", "crash", "diagnostic"))
            return new("LOGS", RecordKind.Log, false, isJson, false, false);
        if (ContainsAny(normalized, "system-info", "system_information", "device-info", "bugreport", "build.prop"))
            return new("SYSTEM_INFORMATION", RecordKind.System, false, isJson, false, false);
        if (extension is ".cfg" or ".conf" or ".config" or ".ini" or ".plist" or ".properties" or ".toml" or ".yaml" or ".yml" || ContainsAny(normalized, "/config/", "/settings/"))
            return new("CONFIGURATION", RecordKind.Configuration, false, isJson, false, false);
        if (extension is ".db" or ".sqlite" or ".sqlite3" || ContainsAny(normalized, "/android/data/", "/app-data/", "/application-data/"))
            return new("APPLICATION_DATA", RecordKind.Application, false, isJson, false, false);

        return new("DOCUMENTS", RecordKind.Document, false, isJson, false, false);
    }

    public static string DetectSourceName(string archiveRoot, string relativePath)
    {
        return new DirectoryInfo(archiveRoot).Name;
    }

    public static string SourceId(string sourceName) => sourceName.Trim().ToLower(CultureInfo.InvariantCulture);

    public static FolderLabels GetFolderLabels(string relativePath, string? archiveEntry, string category)
    {
        var sourceFolders = ParentSegments(relativePath);
        var nestedFolders = archiveEntry is null ? Array.Empty<string>() : ParentSegments(archiveEntry);
        var folders = sourceFolders.Concat(nestedFolders).ToArray();
        var categoryLabel = Humanize(category);
        var folderLabel = folders.LastOrDefault() is { } last ? Humanize(last) : categoryLabel;
        var meaningful = folders.Where(folder => !IsGenericFolder(folder, category)).ToArray();
        var collectionLabel = meaningful.LastOrDefault() is { } collection ? Humanize(collection) : categoryLabel;
        var recordLabel = nestedFolders.LastOrDefault() is { } nested ? Humanize(nested) : collectionLabel;
        var folderPath = folders.Length == 0 ? Path.DirectorySeparatorChar.ToString() : Path.DirectorySeparatorChar + Path.Combine(folders);
        return new(folderPath, folderLabel, collectionLabel, recordLabel);
    }

    public static bool IsImagePath(string path) => ImageExtensions.Contains(Path.GetExtension(path));

    public static bool IsCollectorOwnedPath(string path)
    {
        var normalized = "/" + Normalize(path).Trim('/') + "/";
        return normalized.Contains("/phone sync/this android/") ||
               normalized.Contains("/phonesync/this android/") ||
               normalized.Contains("/phone sync/local-android/") ||
             normalized.Contains("/phonesync/local-android/") ||
             normalized.Contains("/phone sync/selected folder/") ||
             normalized.Contains("/phonesync/selected folder/") ||
             normalized.Contains("/phone sync backups/") ||
             normalized.Contains("/phone sync uploads/") ||
             normalized.Contains("/phone sync/data reader/");
    }

    private static string Normalize(string path) => path.Replace('\\', '/').ToLowerInvariant();

    private static string[] ParentSegments(string path)
    {
        var normalized = path.Replace('\\', '/').Trim('/');
        var parent = normalized.Contains('/') ? normalized[..normalized.LastIndexOf('/')] : string.Empty;
        return parent.Split('/', StringSplitOptions.RemoveEmptyEntries);
    }

    private static bool IsGenericFolder(string folder, string category)
    {
        var normalized = new string(folder.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
        var normalizedCategory = new string(category.ToLowerInvariant().Where(char.IsLetterOrDigit).ToArray());
        return normalized == normalizedCategory || GenericFolders.Contains(normalized);
    }

    private static string Humanize(string value) => string.Join(
        " ",
        value.Replace('_', ' ').Replace('-', ' ').Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Select(word => char.ToUpperInvariant(word[0]) + word[1..].ToLowerInvariant()));

    private static bool ContainsAny(string value, params string[] markers) => markers.Any(value.Contains);

    private static readonly HashSet<string> GenericFolders = new(StringComparer.OrdinalIgnoreCase)
    {
        "download", "downloads", "export", "exports", "phonesync", "internalstorage", "phone", "storage", "data"
    };
}
