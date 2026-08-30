using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;
using Newtonsoft.Json.Linq;

namespace PhoneSyncDataReader;

public sealed class ArchiveIndexer
{
    public static string GetDatabasePath(string archiveRoot)
    {
        var normalized = Path.GetFullPath(archiveRoot).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar).ToUpperInvariant();
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalized)))[..20].ToLowerInvariant();
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneSync", "DataReader", "Indexes");
        Directory.CreateDirectory(directory);
        return Path.Combine(directory, $"{hash}.sqlite");
    }

    public Task<IndexBuildResult> BuildAsync(string archiveRoot, IProgress<IndexProgress>? progress, CancellationToken cancellationToken) =>
        Task.Run(() => Build(archiveRoot, progress, cancellationToken), cancellationToken);

    private static IndexBuildResult Build(string archiveRoot, IProgress<IndexProgress>? progress, CancellationToken cancellationToken)
    {
        var root = Path.GetFullPath(archiveRoot);
        if (!Directory.Exists(root)) throw new DirectoryNotFoundException(root);

        var databasePath = GetDatabasePath(root);
        var temporaryPath = databasePath + ".building";
        DeleteDatabaseFiles(temporaryPath);
        var files = Directory.EnumerateFiles(
                root,
                "*",
                new EnumerationOptions
                {
                    RecurseSubdirectories = true,
                    IgnoreInaccessible = true,
                    AttributesToSkip = FileAttributes.ReparsePoint,
                })
            .Where(path => !path.EndsWith(".sqlite-wal", StringComparison.OrdinalIgnoreCase) &&
                           !path.EndsWith(".sqlite-shm", StringComparison.OrdinalIgnoreCase))
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var sourceIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var contentKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var artifactCount = 0;
        var recordCount = 0;
        var fieldCount = 0;
        var parsedFiles = 0;
        var sensitiveFiles = 0;
        var failedFiles = 0;
        string? firstError = null;

        try
        {
            using (var connection = new SqliteConnection(new SqliteConnectionStringBuilder
            {
                DataSource = temporaryPath,
                Mode = SqliteOpenMode.ReadWriteCreate,
                Cache = SqliteCacheMode.Private,
                Pooling = false,
            }.ToString()))
            {
                connection.Open();
                CreateSchema(connection);
                using var transaction = connection.BeginTransaction();
                using var writer = new IndexWriter(connection, transaction);

                for (var fileIndex = 0; fileIndex < files.Count; fileIndex++)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var fullPath = files[fileIndex];
                    var relativePath = Path.GetRelativePath(root, fullPath);
                    progress?.Report(new(fileIndex, files.Count, relativePath, recordCount, fieldCount));
                    long artifactId = 0;
                    try
                    {
                        if (ArchiveClassifier.IsCollectorOwnedPath(relativePath)) continue;
                        var info = new FileInfo(fullPath);
                        var classification = ArchiveClassifier.Classify(relativePath);
                        var sourceName = ArchiveClassifier.DetectSourceName(root, relativePath);
                        var sourceId = ArchiveClassifier.SourceId(sourceName);
                        var contentHash = FileSha256(fullPath);
                        if (!contentKeys.Add($"{sourceId}:{contentHash}"))
                        {
                            progress?.Report(new(fileIndex + 1, files.Count, relativePath, recordCount, fieldCount));
                            continue;
                        }
                        var folderLabels = ArchiveClassifier.GetFolderLabels(relativePath, null, classification.Category);
                        sourceIds.Add(sourceId);
                        writer.EnsureSource(sourceId, sourceName, root);
                        artifactId = writer.AddArtifact(
                            sourceId,
                            classification.Category,
                            classification.Kind,
                            contentHash,
                            folderLabels,
                            relativePath,
                            fullPath,
                            info.Extension,
                            info.Length,
                            info.LastWriteTimeUtc);
                        artifactCount++;

                        var recordsBefore = writer.RecordCount;
                        var fieldsBefore = writer.FieldCount;
                        ArtifactOutcome outcome;
                        if (classification.Sensitive)
                        {
                            sensitiveFiles++;
                            outcome = new("SKIPPED_SENSITIVE", 0, 0, null);
                        }
                        else if (classification.IsJson)
                        {
                            outcome = ParseJsonFile(writer, artifactId, sourceId, classification, relativePath, fullPath, cancellationToken);
                        }
                        else if (info.Extension.Equals(".zip", StringComparison.OrdinalIgnoreCase))
                        {
                            outcome = ParseZip(writer, artifactId, sourceId, relativePath, fullPath, cancellationToken);
                        }
                        else
                        {
                            var synthetic = SyntheticRecord(classification.Kind, info.Name, relativePath, info.Length, info.LastWriteTimeUtc, 0);
                            writer.AddRecord(artifactId, sourceId, classification.Category, relativePath, null, relativePath, synthetic);
                            outcome = new("NO_JSON", 1, synthetic.Fields.Count, null);
                        }

                        outcome = outcome with
                        {
                            RecordCount = writer.RecordCount - recordsBefore,
                            FieldCount = writer.FieldCount - fieldsBefore,
                        };
                        writer.CompleteArtifact(artifactId, outcome.Status, outcome.RecordCount, outcome.FieldCount, outcome.Error);
                        if (outcome.Status == "PARSED") parsedFiles++;
                        recordCount += outcome.RecordCount;
                        fieldCount += outcome.FieldCount;
                    }
                    catch (Exception exception)
                    {
                        failedFiles++;
                        firstError ??= $"{relativePath}: {exception.Message}";
                        if (artifactId > 0) writer.CompleteArtifact(artifactId, "ERROR", 0, 0, exception.Message);
                    }
                    progress?.Report(new(fileIndex + 1, files.Count, relativePath, recordCount, fieldCount));
                }

                transaction.Commit();
            }

            File.Move(temporaryPath, databasePath, true);
            DeleteDatabaseSidecars(databasePath);
            return new(root, databasePath, sourceIds.Count, artifactCount, recordCount, fieldCount, parsedFiles, sensitiveFiles, failedFiles, firstError);
        }
        catch
        {
            DeleteDatabaseFiles(temporaryPath);
            throw;
        }
    }

    private static ArtifactOutcome ParseJsonFile(
        IndexWriter writer,
        long artifactId,
        string sourceId,
        ArchiveClassification classification,
        string relativePath,
        string fullPath,
        CancellationToken cancellationToken)
    {
        using var stream = File.OpenRead(fullPath);
        if (Path.GetExtension(fullPath).Equals(".jsonl", StringComparison.OrdinalIgnoreCase) ||
            Path.GetExtension(fullPath).Equals(".ndjson", StringComparison.OrdinalIgnoreCase))
        {
            return ParseJsonLines(writer, artifactId, sourceId, classification.Category, classification.Kind, relativePath, null, stream, cancellationToken);
        }
        return ParseJsonStream(writer, artifactId, sourceId, classification.Category, classification.Kind, relativePath, null, stream);
    }

    private static ArtifactOutcome ParseZip(
        IndexWriter writer,
        long artifactId,
        string sourceId,
        string relativePath,
        string fullPath,
        CancellationToken cancellationToken)
    {
        var records = 0;
        var fields = 0;
        var parsedJson = false;
        var archiveClassification = ArchiveClassifier.Classify(relativePath);
        var isSmsArchive = archiveClassification.Category == "SMS_EXPORTS";
        using var archive = ZipFile.OpenRead(fullPath);
        var trustedManifestPaths = TrustedManifestPaths(archive);
        foreach (var entry in archive.Entries)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (string.IsNullOrEmpty(entry.Name)) continue;
            if (entry.FullName.Equals("backup-manifest.json", StringComparison.OrdinalIgnoreCase)) continue;
            if (trustedManifestPaths is not null && !trustedManifestPaths.Contains(NormalizeEntry(entry.FullName))) continue;
            var virtualPath = $"{relativePath}!/{entry.FullName}";
            if (ArchiveClassifier.IsCollectorOwnedPath(virtualPath)) continue;
            var classification = ArchiveClassifier.Classify(virtualPath);
            var entryClassification = ArchiveClassifier.Classify(entry.FullName);
            if (classification.Sensitive || entryClassification.Sensitive) continue;
            var category = isSmsArchive ? "SMS_EXPORTS" : classification.Category;
            if (classification.IsJson)
            {
                parsedJson = true;
                using var input = entry.Open();
                var kind = isSmsArchive ? RecordKind.Message : classification.Kind;
                var outcome = Path.GetExtension(entry.Name).Equals(".jsonl", StringComparison.OrdinalIgnoreCase) ||
                              Path.GetExtension(entry.Name).Equals(".ndjson", StringComparison.OrdinalIgnoreCase)
                    ? ParseJsonLines(writer, artifactId, sourceId, category, kind, relativePath, entry.FullName, input, cancellationToken)
                    : ParseJsonStream(writer, artifactId, sourceId, category, kind, relativePath, entry.FullName, input);
                records += outcome.RecordCount;
                fields += outcome.FieldCount;
            }
            else
            {
                var synthetic = ReadArchiveEntryRecord(entryClassification.Kind, entry, relativePath, records, cancellationToken);
                writer.AddRecord(artifactId, sourceId, category, relativePath, entry.FullName, entry.FullName, synthetic);
                records++;
                fields += synthetic.Fields.Count;
            }
        }

        if (records == 0)
        {
            var info = new FileInfo(fullPath);
            var synthetic = SyntheticRecord(RecordKind.Document, info.Name, relativePath, info.Length, info.LastWriteTimeUtc, 0);
            writer.AddRecord(artifactId, sourceId, "DOCUMENTS", relativePath, null, relativePath, synthetic);
            return new("NO_JSON", 1, synthetic.Fields.Count, null);
        }
        return new(parsedJson ? "PARSED" : "NO_JSON", records, fields, null);
    }

    private static HashSet<string>? TrustedManifestPaths(ZipArchive archive)
    {
        var manifestEntry = archive.Entries.FirstOrDefault(entry =>
            entry.FullName.Equals("backup-manifest.json", StringComparison.OrdinalIgnoreCase));
        if (manifestEntry is null) return null;
        using var input = manifestEntry.Open();
        using var text = new StreamReader(input, Encoding.UTF8, true, 64 * 1024);
        var manifest = JObject.Parse(text.ReadToEnd());
        var externalPeerId = manifest.Value<string>("externalPeerId");
        var allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(externalPeerId)) return allowed;
        foreach (var item in manifest["entries"]?.OfType<JObject>() ?? [])
        {
            var peerId = item.Value<string>("peerId");
            var fingerprint = item.Value<string>("sourceFingerprint");
            var sourceItem = item.Value<string>("sourceItem");
            var archivePath = item.Value<string>("archivePath");
            if (!string.Equals(peerId, externalPeerId, StringComparison.Ordinal) ||
                string.IsNullOrWhiteSpace(fingerprint) ||
                string.IsNullOrWhiteSpace(sourceItem) ||
                string.IsNullOrWhiteSpace(archivePath) ||
                ArchiveClassifier.IsCollectorOwnedPath(sourceItem))
            {
                continue;
            }
            allowed.Add(NormalizeEntry(archivePath));
        }
        return allowed;
    }

    private static string NormalizeEntry(string path) => path.Replace('\\', '/').TrimStart('/');

    private static ArtifactOutcome ParseJsonStream(
        IndexWriter writer,
        long artifactId,
        string sourceId,
        string category,
        RecordKind kind,
        string relativePath,
        string? archiveEntry,
        Stream input)
    {
        using var text = new StreamReader(input, Encoding.UTF8, true, 64 * 1024, leaveOpen: true);
        var source = archiveEntry ?? relativePath;
        var defaultType = ArchiveClassifier.GetFolderLabels(relativePath, archiveEntry, category).RecordLabel;
        var summary = JsonRecordFlattener.Flatten(text, kind, defaultType, record =>
            writer.AddRecord(artifactId, sourceId, category, relativePath, archiveEntry, source, record));
        return new(summary.RecordCount > 0 ? "PARSED" : "NO_JSON", summary.RecordCount, summary.FieldCount, null);
    }

    private static ArtifactOutcome ParseJsonLines(
        IndexWriter writer,
        long artifactId,
        string sourceId,
        string category,
        RecordKind kind,
        string relativePath,
        string? archiveEntry,
        Stream input,
        CancellationToken cancellationToken)
    {
        using var text = new StreamReader(input, Encoding.UTF8, true, 64 * 1024, leaveOpen: true);
        var source = archiveEntry ?? relativePath;
        var defaultType = ArchiveClassifier.GetFolderLabels(relativePath, archiveEntry, category).RecordLabel;
        var records = 0;
        var fields = 0;
        while (text.ReadLine() is { } line)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (string.IsNullOrWhiteSpace(line)) continue;
            var summary = JsonRecordFlattener.Flatten(new StringReader(line), kind, defaultType, record =>
                writer.AddRecord(artifactId, sourceId, category, relativePath, archiveEntry, source, record with { Index = records + record.Index }));
            records += summary.RecordCount;
            fields += summary.FieldCount;
        }
        return new(records > 0 ? "PARSED" : "NO_JSON", records, fields, null);
    }

    private static ParsedRecord SyntheticRecord(RecordKind kind, string name, string path, long bytes, DateTime modifiedUtc, int index)
    {
        var fields = new List<FlatField>
        {
            new("file.name", "name", ValueType.String, name),
            new("file.path", "path", ValueType.String, path),
            new("file.bytes", "bytes", ValueType.Number, bytes.ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new("file.modifiedUtc", "modifiedUtc", ValueType.String, modifiedUtc.ToString("O")),
        };
        return new(index, kind.ToString(), kind, name, FormatBytes(bytes), modifiedUtc.ToString("O"), fields);
    }

    private static ParsedRecord ReadArchiveEntryRecord(
        RecordKind kind,
        ZipArchiveEntry entry,
        string containerPath,
        int index,
        CancellationToken cancellationToken)
    {
        var virtualPath = $"{containerPath}!/{entry.FullName}";
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        using var input = entry.Open();
        var buffer = new byte[64 * 1024];
        long bytes = 0;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var read = input.Read(buffer, 0, buffer.Length);
            if (read == 0) break;
            digest.AppendData(buffer, 0, read);
            bytes += read;
        }
        var contentHash = Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant();
        var fields = new List<FlatField>
        {
            new("archive.container", "container", ValueType.String, containerPath),
            new("archive.entry", "entry", ValueType.String, entry.FullName),
            new("archive.extension", "extension", ValueType.String, Path.GetExtension(entry.Name)),
            new("archive.bytes", "bytes", ValueType.Number, bytes.ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new("archive.compressedBytes", "compressedBytes", ValueType.Number, entry.CompressedLength.ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new("archive.sha256", "sha256", ValueType.String, contentHash),
            new("archive.modifiedUtc", "modifiedUtc", ValueType.String, entry.LastWriteTime.UtcDateTime.ToString("O")),
        };
        return new(index, "Archive item", kind, entry.Name, $"{FormatBytes(bytes)} · {virtualPath}", entry.LastWriteTime.UtcDateTime.ToString("O"), fields);
    }

    private static void CreateSchema(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = DELETE;
            PRAGMA synchronous = NORMAL;
            CREATE TABLE sources (
                source_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                root_path TEXT NOT NULL
            );
            CREATE TABLE artifacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id TEXT NOT NULL,
                category TEXT NOT NULL,
                artifact_kind TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                folder_path TEXT NOT NULL,
                folder_label TEXT NOT NULL,
                collection_label TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                full_path TEXT NOT NULL,
                extension TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                modified_utc TEXT NOT NULL,
                parse_status TEXT NOT NULL,
                record_count INTEGER NOT NULL DEFAULT 0,
                field_count INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                FOREIGN KEY(source_id) REFERENCES sources(source_id) ON DELETE CASCADE,
                UNIQUE(source_id, content_hash)
            );
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                artifact_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                category TEXT NOT NULL,
                json_source TEXT NOT NULL,
                archive_entry TEXT,
                folder_label TEXT NOT NULL,
                collection_label TEXT NOT NULL,
                record_label TEXT NOT NULL,
                record_type TEXT NOT NULL,
                record_kind TEXT NOT NULL,
                record_index INTEGER NOT NULL,
                record_hash TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                timestamp_text TEXT,
                search_text TEXT NOT NULL,
                FOREIGN KEY(artifact_id) REFERENCES artifacts(id) ON DELETE CASCADE,
                FOREIGN KEY(source_id) REFERENCES sources(source_id) ON DELETE CASCADE,
                UNIQUE(source_id, category, record_kind, collection_label, record_hash)
            );
            CREATE TABLE fields (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_id INTEGER NOT NULL,
                field_path TEXT NOT NULL,
                field_name TEXT NOT NULL,
                value_type TEXT NOT NULL,
                text_value TEXT NOT NULL,
                FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE
            );
            CREATE INDEX artifacts_source_category ON artifacts(source_id, category, parse_status);
            CREATE INDEX artifacts_kind_source ON artifacts(artifact_kind, source_id);
            CREATE INDEX records_source_kind ON records(source_id, record_kind, collection_label, record_type);
            CREATE INDEX records_artifact_order ON records(artifact_id, json_source, record_index);
            CREATE INDEX fields_record_path ON fields(record_id, field_path);
            CREATE INDEX fields_name_value ON fields(field_name, text_value);
            """;
        command.ExecuteNonQuery();
    }

    private static void DeleteDatabaseFiles(string path)
    {
        foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
        {
            if (File.Exists(candidate)) File.Delete(candidate);
        }
    }

    private static void DeleteDatabaseSidecars(string path)
    {
        foreach (var candidate in new[] { path + "-wal", path + "-shm" })
        {
            if (File.Exists(candidate)) File.Delete(candidate);
        }
    }

    private static string FileSha256(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    private static string FormatBytes(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024d:F1} KB",
        < 1024L * 1024 * 1024 => $"{bytes / (1024d * 1024):F1} MB",
        _ => $"{bytes / (1024d * 1024 * 1024):F1} GB",
    };

    private sealed record ArtifactOutcome(string Status, int RecordCount, int FieldCount, string? Error);

    private sealed class IndexWriter : IDisposable
    {
        private readonly SqliteTransaction _transaction;
        private readonly SqliteCommand _source;
        private readonly SqliteCommand _artifact;
        private readonly SqliteCommand _record;
        private readonly SqliteCommand _field;
        private readonly SqliteCommand _complete;
        public int RecordCount { get; private set; }
        public int FieldCount { get; private set; }

        public IndexWriter(SqliteConnection connection, SqliteTransaction transaction)
        {
            _transaction = transaction;
            _source = Command(connection, "INSERT INTO sources(source_id, display_name, root_path) VALUES($id,$name,$root) ON CONFLICT(source_id) DO UPDATE SET display_name=excluded.display_name, root_path=excluded.root_path", "$id", "$name", "$root");
            _artifact = Command(connection, "INSERT INTO artifacts(source_id,category,artifact_kind,content_hash,folder_path,folder_label,collection_label,relative_path,full_path,extension,bytes,modified_utc,parse_status) VALUES($source,$category,$kind,$hash,$folderPath,$folderLabel,$collection,$relative,$full,$extension,$bytes,$modified,'INDEXING') RETURNING id", "$source", "$category", "$kind", "$hash", "$folderPath", "$folderLabel", "$collection", "$relative", "$full", "$extension", "$bytes", "$modified");
            _record = Command(connection, "INSERT OR IGNORE INTO records(artifact_id,source_id,category,json_source,archive_entry,folder_label,collection_label,record_label,record_type,record_kind,record_index,record_hash,title,summary,timestamp_text,search_text) VALUES($artifact,$source,$category,$json,$entry,$folderLabel,$collection,$recordLabel,$type,$kind,$index,$hash,$title,$summary,$timestamp,$search) RETURNING id", "$artifact", "$source", "$category", "$json", "$entry", "$folderLabel", "$collection", "$recordLabel", "$type", "$kind", "$index", "$hash", "$title", "$summary", "$timestamp", "$search");
            _field = Command(connection, "INSERT INTO fields(record_id,field_path,field_name,value_type,text_value) VALUES($record,$path,$name,$type,$value)", "$record", "$path", "$name", "$type", "$value");
            _complete = Command(connection, "UPDATE artifacts SET parse_status=$status,record_count=$records,field_count=$fields,error=$error WHERE id=$id", "$status", "$records", "$fields", "$error", "$id");
        }

        public void EnsureSource(string id, string name, string root)
        {
            Set(_source, "$id", id); Set(_source, "$name", name); Set(_source, "$root", root); _source.ExecuteNonQuery();
        }

        public long AddArtifact(string source, string category, RecordKind kind, string contentHash, FolderLabels labels, string relative, string full, string extension, long bytes, DateTime modifiedUtc)
        {
            Set(_artifact, "$source", source); Set(_artifact, "$category", category); Set(_artifact, "$kind", kind.ToString());
            Set(_artifact, "$hash", contentHash); Set(_artifact, "$folderPath", labels.FolderPath);
            Set(_artifact, "$folderLabel", labels.FolderLabel); Set(_artifact, "$collection", labels.CollectionLabel);
            Set(_artifact, "$relative", relative); Set(_artifact, "$full", full); Set(_artifact, "$extension", extension);
            Set(_artifact, "$bytes", bytes); Set(_artifact, "$modified", modifiedUtc.ToString("O"));
            return Convert.ToInt64(_artifact.ExecuteScalar(), System.Globalization.CultureInfo.InvariantCulture);
        }

        public long AddRecord(long artifactId, string sourceId, string category, string relativePath, string? archiveEntry, string jsonSource, ParsedRecord record)
        {
            var labels = ArchiveClassifier.GetFolderLabels(relativePath, archiveEntry, category);
            var recordHash = JsonRecordFlattener.CanonicalHash(record);
            var search = new StringBuilder(labels.FolderLabel).Append(' ').Append(labels.CollectionLabel).Append(' ')
                .Append(record.Title).Append(' ').Append(record.Summary);
            foreach (var field in record.Fields)
            {
                search.Append(' ').Append(field.Name).Append(' ').Append(field.Value[..Math.Min(field.Value.Length, 2048)]);
                if (search.Length >= 262144) break;
            }
            Set(_record, "$artifact", artifactId); Set(_record, "$source", sourceId); Set(_record, "$category", category);
            Set(_record, "$json", jsonSource); Set(_record, "$entry", archiveEntry);
            Set(_record, "$folderLabel", labels.FolderLabel); Set(_record, "$collection", labels.CollectionLabel);
            Set(_record, "$recordLabel", labels.RecordLabel); Set(_record, "$type", record.RecordType);
            Set(_record, "$kind", record.Kind.ToString()); Set(_record, "$index", record.Index); Set(_record, "$title", record.Title);
            Set(_record, "$hash", recordHash);
            Set(_record, "$summary", record.Summary); Set(_record, "$timestamp", record.Timestamp);
            Set(_record, "$search", search.ToString()[..Math.Min(search.Length, 262144)]);
            var scalar = _record.ExecuteScalar();
            if (scalar is null || scalar is DBNull) return -1;
            var recordId = Convert.ToInt64(scalar, System.Globalization.CultureInfo.InvariantCulture);
            RecordCount++;
            foreach (var field in record.Fields)
            {
                Set(_field, "$record", recordId); Set(_field, "$path", field.Path); Set(_field, "$name", field.Name);
                Set(_field, "$type", field.ValueType.ToString()); Set(_field, "$value", field.Value); _field.ExecuteNonQuery();
                FieldCount++;
            }
            return recordId;
        }

        public void CompleteArtifact(long id, string status, int records, int fields, string? error)
        {
            Set(_complete, "$status", status); Set(_complete, "$records", records); Set(_complete, "$fields", fields);
            Set(_complete, "$error", error); Set(_complete, "$id", id); _complete.ExecuteNonQuery();
        }

        private SqliteCommand Command(SqliteConnection connection, string sql, params string[] parameters)
        {
            var command = connection.CreateCommand();
            command.Transaction = _transaction;
            command.CommandText = sql;
            foreach (var parameter in parameters) command.Parameters.Add(new SqliteParameter(parameter, null));
            command.Prepare();
            return command;
        }

        private static void Set(SqliteCommand command, string name, object? value) => command.Parameters[name].Value = value ?? DBNull.Value;

        public void Dispose()
        {
            _source.Dispose(); _artifact.Dispose(); _record.Dispose(); _field.Dispose(); _complete.Dispose();
        }
    }
}
