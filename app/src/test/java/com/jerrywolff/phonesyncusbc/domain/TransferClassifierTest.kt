package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClassifierTest {
    @Test
    fun `classifies camera media`() {
        assertEquals(
            ConsentCategory.PHOTOS_AND_VIDEOS,
            TransferClassifier.classify("/Internal storage/DCIM/Camera/IMG_1001.HEIC"),
        )
        assertEquals(
            ConsentCategory.PHOTOS_AND_VIDEOS,
            TransferClassifier.classify("/Phone/DCIM/Camera/video.mp4"),
        )
    }

    @Test
    fun `classifies contacts only from vCard files`() {
        assertEquals(
            ConsentCategory.CONTACTS,
            TransferClassifier.classify("/Downloads/contacts.vcf"),
        )
    }

    @Test
    fun `classifies sensitive portable exports only from explicit folders`() {
        assertEquals(
            ConsentCategory.SMS_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/SMS/messages.xml"),
        )
        assertEquals(
            ConsentCategory.CHAT_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Chat/conversation.zip"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Email/archive.mbox"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/Downloads/archive.mbox"),
        )
        assertEquals(
            ConsentCategory.NOTIFICATION_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Notifications/history.json"),
        )
        assertEquals(
            ConsentCategory.SMS_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/sms_exports/sms-mms-20260814.zip"),
        )
        assertEquals(
            ConsentCategory.SMS_EXPORTS,
            TransferClassifier.classify("sms-backup.zip"),
        )
        assertEquals(
            ConsentCategory.CALL_LOGS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/call_logs/call-log-20260814.json"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/email_exports/account-mail.mbox"),
        )
        assertEquals(
            ConsentCategory.PASSWORD_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/This Android/password_exports/passwords.kdbx"),
        )
        assertEquals(
            ConsentCategory.PASSWORD_EXPORTS,
            TransferClassifier.classify("/Downloads/bitwarden_encrypted_export.json"),
        )
        assertEquals(
            ConsentCategory.PASSWORD_EXPORTS,
            TransferClassifier.classify("/PhoneSync/Exports/Passwords/vault.sqlite"),
        )
        assertEquals(
            ConsentCategory.VOICEMAIL_EXPORTS,
            TransferClassifier.classify("/Download/Phone Sync/Voicemails/voicemail-20260814.m4a"),
        )
        assertEquals(
            ConsentCategory.VOICEMAIL_EXPORTS,
            TransferClassifier.classify("/Downloads/visual-voicemail-export.zip"),
        )
        listOf(
            "message.amr",
            "message.awb",
            "message.qcp",
            "message.evrc",
            "message.caf",
            "message.aiff",
            "message.flac",
            "message.opus",
            "message.vvm",
            "transcript.txt",
            "metadata.json",
        ).forEach { fileName ->
            assertEquals(
                fileName,
                ConsentCategory.VOICEMAIL_EXPORTS,
                TransferClassifier.classify("/PhoneSync/Exports/Voicemail/$fileName"),
            )
        }
        assertEquals(
            ConsentCategory.CALENDAR,
            TransferClassifier.classify("/Download/Phone Sync/This Android/calendar/calendar-20260814.json"),
        )
    }

    @Test
    fun `classifies normally exposed recovery artifacts without bypassing access controls`() {
        assertEquals(
            ConsentCategory.APPLICATION_DATA,
            TransferClassifier.classify("/Android/data/chat.application/private.db"),
        )
        assertTrue(
            TransferClassifier.isProtectedPrivateDatabase(
                "/Android/data/chat.application/private.db",
            ),
        )
        assertFalse(
            TransferClassifier.isProtectedPrivateDatabase(
                "/PhoneSync/Exports/Chat/conversation.zip",
            ),
        )
        assertEquals(
            ConsentCategory.DOCUMENTS,
            TransferClassifier.classify("/Downloads/accounts.csv"),
        )
        assertEquals(
            ConsentCategory.DOCUMENTS,
            TransferClassifier.classify("/Music/meeting.m4a"),
        )
        assertTrue(
            TransferClassifier.isProtectedPrivateDatabase(
                "/Android/data/com.keepass/database.kdbx",
            ),
        )
        assertTrue(
            TransferClassifier.isProtectedPrivateDatabase(
                "/Android/data/com.bitwarden/files/vault.json",
            ),
        )
        assertTrue(
            TransferClassifier.isProtectedPrivateDatabase(
                "/Android/data/com.android.dialer/files/voicemail-message.m4a",
            ),
        )
        assertEquals(
            ConsentCategory.CONFIGURATION,
            TransferClassifier.classify("/PhoneSync/Exports/Configuration/application.yaml"),
        )
        assertEquals(
            ConsentCategory.LOGS,
            TransferClassifier.classify("/PhoneSync/Exports/Logs/crash-20260814.log"),
        )
        assertEquals(
            ConsentCategory.SYSTEM_INFORMATION,
            TransferClassifier.classify("/PhoneSync/Exports/System-Info/device-info.json"),
        )
    }

    @Test
    fun `classifies portable password artifacts for every recovery profile`() {
        listOf(
            "/Users/Jerry/AppData/Local/Google/Chrome/User Data/Default/Login Data",
            "/Users/Jerry/AppData/Roaming/Mozilla/Firefox/Profiles/default/logins.json",
            "/Android/Exports/Passwords/passwords.csv",
            "/iPhone/Exports/1Password/vault.1pux",
            "/Camera/Backup/Credentials/credentials.json",
            "/IoT/Configuration/credential-backup.zip",
            "/Windows/Vault/backup.vcrd",
        ).forEach { path ->
            assertEquals(path, ConsentCategory.PASSWORD_EXPORTS, TransferClassifier.classify(path))
        }
    }

    @Test
    fun `sensitive markers take precedence over message folder names`() {
        assertEquals(
            ConsentCategory.PASSWORD_EXPORTS,
            TransferClassifier.classify("/SMS/Exports/Passwords/sms-password-vault.json"),
        )
        assertEquals(
            ConsentCategory.PASSWORD_EXPORTS,
            TransferClassifier.classify("/WhatsApp/Backups/1Password/vault.1pux"),
        )
    }

    @Test
    fun `classifies explicit passkey backups as opaque credential artifacts`() {
        listOf(
            "/Android/Exports/Passkeys/passkeys.json",
            "/Backups/passkey-backup.zip",
            "/Browser/webauthn-backup/credentials.cbor",
            "/Security/fido2_export/fido2-credentials.json",
        ).forEach { path ->
            assertTrue(path, TransferClassifier.isPasskeyRelatedArtifact(path))
            assertEquals(path, ConsentCategory.PASSWORD_EXPORTS, TransferClassifier.classify(path))
        }
        assertFalse(TransferClassifier.isPasskeyRelatedArtifact("/Documents/passkey-notes.txt"))
    }

    @Test
    fun `classifies Windows and Android recovery artifacts`() {
        assertEquals(
            ConsentCategory.LOGS,
            TransferClassifier.classify("/Windows/System32/winevt/Logs/System.evtx"),
        )
        assertEquals(
            ConsentCategory.CONFIGURATION,
            TransferClassifier.classify("/Windows/System32/config/SYSTEM"),
        )
        assertEquals(
            ConsentCategory.EMAIL_EXPORTS,
            TransferClassifier.classify("/Users/Jerry/AppData/Local/Outlook/account.ost"),
        )
        assertEquals(
            ConsentCategory.CHAT_EXPORTS,
            TransferClassifier.classify("/Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15"),
        )
        assertEquals(
            ConsentCategory.CHAT_EXPORTS,
            TransferClassifier.classify("/Backups/Signal/signal-2026-08-14.backup"),
        )
    }

    @Test
    fun `classifies portable collaboration chat exports and transcripts`() {
        listOf(
            "/Downloads/WhatsApp Chat with Family.zip",
            "/Downloads/Microsoft Teams/teams-export/messages.json",
            "/Downloads/Zoom/meeting-chat.txt",
            "/Downloads/Zoom/transcript.vtt",
            "/Downloads/Webex/webex-meeting-transcript.srt",
            "/Exports/Slack/slack-export.zip",
            "/Exports/Google Chat/conversations.json",
        ).forEach { path ->
            assertEquals(path, ConsentCategory.CHAT_EXPORTS, TransferClassifier.classify(path))
        }
    }
}