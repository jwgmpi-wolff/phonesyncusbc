using System.Text;
using System.IO.Compression;
using PhoneSyncDataReader;

namespace PhoneSyncDataReader.Tests;

public sealed class ArchiveIndexerTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "PhoneSyncDataReaderTests", Guid.NewGuid().ToString("N"), "Lumia");

    [Fact]
    public async Task RepeatedBuildsCollapseDuplicateFilesAndDuplicateRecords()
    {
        var family = Path.Combine(_root, "SMS Exports", "Family");
        Directory.CreateDirectory(family);
        var json = "[{\"sender\":\"Ada\",\"body\":\"Hello\"},{\"body\":\"Hello\",\"sender\":\"Ada\"}]";
        await File.WriteAllTextAsync(Path.Combine(family, "messages.json"), json, Encoding.UTF8);
        await File.WriteAllTextAsync(Path.Combine(family, "messages-copy.json"), json, Encoding.UTF8);

        var indexer = new ArchiveIndexer();
        var first = await indexer.BuildAsync(_root, null, CancellationToken.None);
        using (var firstRepository = new ArtifactRepository(first.DatabasePath))
            Assert.Equal(1, firstRepository.GetStats().ArtifactCount);
        var second = await indexer.BuildAsync(_root, null, CancellationToken.None);

        Assert.Equal(1, first.ArtifactCount);
        Assert.Equal(1, first.RecordCount);
        Assert.Equal(first.ArtifactCount, second.ArtifactCount);
        Assert.Equal(first.RecordCount, second.RecordCount);
        using var repository = new ArtifactRepository(second.DatabasePath);
        var row = Assert.Single(repository.Search(string.Empty, null, null));
        Assert.Equal("Lumia", row.SourceName);
        Assert.Equal("Family", row.CollectionLabel);
        Assert.Equal("Ada", row.Title);
    }

    [Fact]
    public async Task CollectorFolderIsExcludedFromExternalArchiveIndex()
    {
        var external = Path.Combine(_root, "SMS Exports", "Family");
        var collector = Path.Combine(_root, "Phone Sync", "This Android", "sms_exports");
        var legacyCollector = Path.Combine(_root, "Phone Sync", "Selected folder", "sms_exports");
        Directory.CreateDirectory(external);
        Directory.CreateDirectory(collector);
        Directory.CreateDirectory(legacyCollector);
        await File.WriteAllTextAsync(Path.Combine(external, "messages.json"), "[{\"body\":\"external\"}]");
        await File.WriteAllTextAsync(Path.Combine(collector, "messages.json"), "[{\"body\":\"collector\"}]");
        await File.WriteAllTextAsync(Path.Combine(legacyCollector, "messages.json"), "[{\"body\":\"legacy collector\"}]");

        var result = await new ArchiveIndexer().BuildAsync(_root, null, CancellationToken.None);
        using var repository = new ArtifactRepository(result.DatabasePath);
        var rows = repository.Search(string.Empty, null, null);

        Assert.Single(rows);
        Assert.Equal("external", rows[0].Summary);
    }

        [Fact]
        public async Task PhoneSyncBackupManifestExcludesCollectorAndMixedPeerEntries()
        {
                Directory.CreateDirectory(_root);
                var zipPath = Path.Combine(_root, "PhoneSyncBackup-verified.zip");
                using (var archive = ZipFile.Open(zipPath, ZipArchiveMode.Create))
                {
                        WriteEntry(archive, "documents/external.txt", "external");
                        WriteEntry(archive, "sms_exports/collector.json", "[{\"body\":\"collector\"}]");
                        WriteEntry(archive, "documents/other-peer.txt", "other");
                        WriteEntry(
                                archive,
                                "backup-manifest.json",
                                """
                                {
                                    "externalPeerId": "usb-external",
                                    "entries": [
                                        {"peerId":"usb-external","sourceFingerprint":"fp-1","sourceItem":"/Documents/external.txt","archivePath":"documents/external.txt"},
                                        {"peerId":"usb-external","sourceFingerprint":"fp-2","sourceItem":"/Download/Phone Sync/Selected folder/messages.json","archivePath":"sms_exports/collector.json"},
                                        {"peerId":"other-peer","sourceFingerprint":"fp-3","sourceItem":"/Documents/other.txt","archivePath":"documents/other-peer.txt"}
                                    ]
                                }
                                """);
                }

                var result = await new ArchiveIndexer().BuildAsync(_root, null, CancellationToken.None);
                using var repository = new ArtifactRepository(result.DatabasePath);
                var rows = repository.Search(string.Empty, null, null);

                var record = Assert.Single(rows);
                Assert.Equal("documents/external.txt", record.ArchiveEntry);
                Assert.DoesNotContain(rows, row => row.Summary.Contains("collector", StringComparison.OrdinalIgnoreCase));
        }

    [Fact]
    public void FolderLabelsUseNestedArchiveMetadata()
    {
        var labels = ArchiveClassifier.GetFolderLabels(
            "Backups/messages.zip",
            "conversations/Project Team/messages.json",
            "CHAT_EXPORTS");

        Assert.Equal("Project Team", labels.FolderLabel);
        Assert.Equal("Project Team", labels.CollectionLabel);
        Assert.Equal("Project Team", labels.RecordLabel);
    }

    [Fact]
    public void PasskeyExportsRemainOpaqueSensitiveArtifacts()
    {
        var passkeys = ArchiveClassifier.Classify("Exports/Passkeys/passkeys.json");

        Assert.True(passkeys.Sensitive);
        Assert.Equal("PASSWORD_EXPORTS", passkeys.Category);
        Assert.False(passkeys.IsJson);
    }

    [Fact]
    public async Task SmsZipIndexesEveryNonSensitiveEntryAndRemainsIdempotent()
    {
        Assert.Equal("SMS_EXPORTS", ArchiveClassifier.Classify("sms-backup.zip").Category);
        var folder = Path.Combine(_root, "sms_exports", "Family");
        Directory.CreateDirectory(folder);
        var zipPath = Path.Combine(folder, "sms-backup.zip");
        using (var archive = ZipFile.Open(zipPath, ZipArchiveMode.Create))
        {
            WriteEntry(archive, "messages/messages.json", "[{\"sender\":\"Ada\",\"body\":\"From ZIP\"}]");
            WriteEntry(archive, "messages/sms.xml", "<smses><sms body=\"XML message\" /></smses>");
            WriteEntry(archive, "attachments/photo.jpg", "image-bytes");
            WriteEntry(archive, "attachments/raw.bin", "opaque-attachment");
            WriteEntry(archive, "databases/mmssms.db", "sqlite-bytes");
            WriteEntry(archive, "credentials/credentials.json", "{\"password\":\"excluded\"}");
        }

        var indexer = new ArchiveIndexer();
        var first = await indexer.BuildAsync(_root, null, CancellationToken.None);
        var second = await indexer.BuildAsync(_root, null, CancellationToken.None);

        Assert.Equal(5, first.RecordCount);
        Assert.Equal(first.RecordCount, second.RecordCount);
        using var repository = new ArtifactRepository(second.DatabasePath);
        var rows = repository.Search(string.Empty, null, null);
        Assert.Equal(5, rows.Count);
        Assert.All(rows, row => Assert.Equal("SMS_EXPORTS", row.Category));
        Assert.DoesNotContain(rows, row => row.ArchiveEntry?.Contains("credentials", StringComparison.OrdinalIgnoreCase) == true);
        Assert.Contains(rows, row => row.ArchiveEntry == "attachments/photo.jpg" && row.Kind == RecordKind.Media);
        Assert.Contains(rows, row => row.ArchiveEntry == "databases/mmssms.db" && row.Kind == RecordKind.Application);
        var raw = Assert.Single(rows, row => row.ArchiveEntry == "attachments/raw.bin" && row.RecordType == "Archive item");
        var rawDetail = Assert.IsType<RecordDetail>(repository.GetDetail(raw.Id));
        Assert.Contains(rawDetail.Fields, field => field.Path == "archive.sha256" && field.Value.Length == 64);
        Assert.Single(repository.Search(string.Empty, null, null, RecordFocus.Images));
        Assert.Equal(5, repository.Search(string.Empty, null, null, RecordFocus.Sms).Count);
        Assert.Single(repository.Search(string.Empty, null, null, RecordFocus.All, new[] { raw.Id }));
    }

    [Fact]
    public async Task VoicemailFocusFindsLooseAudio()
    {
        var folder = Path.Combine(_root, "voicemail_exports");
        Directory.CreateDirectory(folder);
        await File.WriteAllBytesAsync(Path.Combine(folder, "voicemail-message.amr"), [1, 2, 3]);

        var result = await new ArchiveIndexer().BuildAsync(_root, null, CancellationToken.None);
        using var repository = new ArtifactRepository(result.DatabasePath);
        var rows = repository.Search(string.Empty, null, null, RecordFocus.Voicemails);

        var voicemail = Assert.Single(rows);
        Assert.Equal("VOICEMAIL_EXPORTS", voicemail.Category);
        Assert.Equal("voicemail-message.amr", voicemail.Title);
    }

    private static void WriteEntry(ZipArchive archive, string path, string content)
    {
        var entry = archive.CreateEntry(path);
        using var writer = new StreamWriter(entry.Open(), Encoding.UTF8);
        writer.Write(content);
    }

    public void Dispose()
    {
        if (Directory.Exists(Path.GetDirectoryName(_root)!))
            Directory.Delete(Path.GetDirectoryName(_root)!, true);
    }
}
